#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

SRC=app/src/main/java/com/odoocompanion
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

serializable=$(grep -rl '^@Serializable' "$SRC" \
    | xargs -r grep -hE '^(private |internal )?data class' \
    | sed -E 's/^(private |internal )?data class ([A-Za-z0-9_]*).*/\2/' | sort -u)
[ -n "$serializable" ] || { echo "found no @Serializable classes -- check the source layout" >&2; exit 1; }

for cls in $serializable; do
    if grep -qE "^com\.odoocompanion\.[a-z.]+\.$cls\\\$\\\$serializer ->" "$MAPPING"; then
        echo "  ok    $cls\$\$serializer kept"
    else
        echo "  FAIL  $cls\$\$serializer was dropped by R8" >&2
        status=1
    fi
done

wire=$(python3 - "$SRC" "$work" <<'PYEOF'
import pathlib, re, sys
src, work = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
dex = b"".join(p.read_bytes() for p in sorted(work.glob("classes*.dex")))
keys = set()
for path in src.rglob("*.kt"):
    text = path.read_text()
    for cls in re.finditer(r"@Serializable\s+(?:private |internal )?data class \w+\(", text):
        depth, end = 1, cls.end()
        while depth:
            depth += {"(": 1, ")": -1}.get(text[end], 0)
            end += 1
        params = text[cls.end():end - 1]
        for sn, name in re.findall(r'(?:@SerialName\("([^"]+)"\)\s*)?val (\w+)', params):
            keys.add(sn or name)
if not keys:
    print("FAIL no wire keys found -- check the source layout")
for key in sorted(keys):
    raw = key.encode()
    entry = bytes([len(raw)]) + raw + b"\0"
    print(("ok  " if entry in dex else "FAIL") + " " + key)
PYEOF
)
while read -r verdict key; do
    if [ "$verdict" = "ok" ]; then
        echo "  ok    \"$key\" present in the dex"
    else
        echo "  FAIL  \"$key\" is not in the minified dex" >&2
        status=1
    fi
done <<< "$wire"

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

[ "$status" -eq 0 ] && echo "release wire format and managed configuration intact"
exit "$status"
