#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

SRC=app/src/main/kotlin/com/odoocompanion
MAPPING=app/build/outputs/mapping/release/mapping.txt
DEX_DIR=app/build/outputs/apk/release

for path in "$MAPPING" "$DEX_DIR"; do
    [ -e "$path" ] || { echo "missing $path -- run ./gradlew assembleRelease first" >&2; exit 1; }
done

apk=$(find "$DEX_DIR" -name '*.apk' | head -1)
[ -n "$apk" ] || { echo "no release apk under $DEX_DIR" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unzip -qo "$apk" -d "$work" 'classes*.dex'

status=0

python3 - "$SRC" "$work" "$MAPPING" <<'PYEOF' || status=1
import pathlib, re, sys
src, work, mapping = map(pathlib.Path, sys.argv[1:])
dex = b"".join(p.read_bytes() for p in sorted(work.glob("classes*.dex")))
serializers = set()
keys = set()
for path in src.rglob("*.kt"):
    text = path.read_text()
    for cls in re.finditer(r"^@Serializable\s+(?:private |internal )?data class (\w+)\(", text, re.M):
        package = re.search(r"^package ([\w.]+)", text, re.M)[1]
        serializers.add(f"{package}.{cls[1]}$$serializer")
        depth, end = 1, cls.end()
        while depth:
            depth += {"(": 1, ")": -1}.get(text[end], 0)
            end += 1
        params = text[cls.end():end - 1]
        for sn, name in re.findall(r'(?:@SerialName\("([^"]+)"\)\s*)?val (\w+)', params):
            keys.add(sn or name)
if not serializers or not keys:
    sys.exit("FAIL no serializable classes or wire keys found -- check the source layout")

with mapping.open() as lines:
    kept = {line.partition(" -> ")[0] for line in lines if "$$serializer -> " in line}

passed = True
for name in sorted(serializers):
    if name in kept:
        print(f"  ok    {name} kept")
    else:
        print(f"  FAIL  {name} was dropped by R8", file=sys.stderr)
        passed = False
for key in sorted(keys):
    raw = key.encode()
    entry = bytes([len(raw)]) + raw + b"\0"
    if entry in dex:
        print(f'  ok    "{key}" present in the dex')
    else:
        print(f'  FAIL  "{key}" is not in the minified dex', file=sys.stderr)
        passed = False
sys.exit(0 if passed else 1)
PYEOF

RESTRICTIONS=app/src/main/res/xml/app_restrictions.xml
aapt2=$(find "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}}/build-tools" \
    -name aapt2 2>/dev/null | sort -V | tail -1)

if [ -z "$aapt2" ]; then
    echo "  FAIL  no aapt2 found -- cannot verify the managed-configuration keys" >&2
    status=1
else
    packaged=$("$aapt2" dump resources "$apk" \
        | awk '/xml\/app_restrictions$/{getline; print}' | grep -oE 'res/[^ ]+')
    [ -n "$packaged" ] || { echo "  FAIL  app_restrictions is not in the shipped resource table" >&2; status=1; }
    [ -n "$packaged" ] && "$aapt2" dump xmltree --file "$packaged" "$apk" > "$work/restrictions.txt"
    for key in $(grep -o 'android:key="[^"]*"' "$RESTRICTIONS" | sed 's/.*"\(.*\)"/\1/'); do
        if [ -n "$packaged" ] && grep -q ":key(.*)=\"$key\"" "$work/restrictions.txt"; then
            echo "  ok    restriction \"$key\" survives resource shrinking"
        else
            echo "  FAIL  restriction \"$key\" was shrunk out of the release APK" >&2
            status=1
        fi
    done
fi

if [ -n "$aapt2" ] && "$aapt2" dump xmltree --file AndroidManifest.xml "$apk" | grep -q LabEnrollReceiver; then
    echo "  FAIL  the debug-only lab enrolment receiver is declared in the release APK" >&2
    status=1
elif grep -qa LabEnrollReceiver "$work"/classes*.dex; then
    echo "  FAIL  the debug-only lab enrolment receiver is compiled into the release APK" >&2
    status=1
else
    echo "  ok    no lab enrolment receiver in the release APK"
fi

[ "$status" -eq 0 ] && echo "release wire format and managed configuration intact"
exit "$status"
