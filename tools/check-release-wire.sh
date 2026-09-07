#!/usr/bin/env bash
# Assert that the release build kept what a fleet handset depends on: the wire
# format R8 could rename away, and the managed-configuration keys resource
# shrinking could drop.
#
# Usage: tools/check-release-wire.sh   (after ./gradlew assembleRelease)
#
# The CI step around this was called "R8 must keep the kotlinx serializers" and
# checked only that the build succeeded, which it does either way. If R8 ever
# dropped a generated serializer or renamed a @SerialName string, a release
# handset would post payloads Odoo cannot read and every unit test would still
# be green, because none of them runs against minified output.
#
# Nothing is listed here by hand: the classes come from the source and the keys
# from their annotations, so a new @Serializable is covered by existing.
#
# The release build also sets isShrinkResources. app_restrictions.xml is reached
# only through a manifest meta-data reference, which is enough to keep it today
# -- but "enough today" is what the wire-format check above exists because of.
# An MDM whose keys were shrunk away pushes a configuration the app never reads,
# and the handset just keeps running the old one. No unit test sees it: none of
# them runs against a shrunk resource table.
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

# Every @Serializable class must still have its generated serializer. R8 may
# rename it -- the compiler plugin references it statically, so a new name is
# harmless -- but it must not have been removed.
serializable=$(grep -rl '^@Serializable' "$SRC" | xargs -r grep -h '^data class' \
    | sed 's/^data class \([A-Za-z0-9_]*\).*/\1/' | sort -u)
[ -n "$serializable" ] || { echo "found no @Serializable classes -- check the source layout" >&2; exit 1; }

for cls in $serializable; do
    if grep -q "com\.odoocompanion\.net\.$cls\$\$serializer ->" "$MAPPING"; then
        echo "  ok    $cls\$\$serializer kept"
    else
        echo "  FAIL  $cls\$\$serializer was dropped by R8" >&2
        status=1
    fi
done

# Every wire key must still be a string in the minified dex. A @SerialName that
# R8 removed is a field the endpoint never sees.
keys=$(grep -rho '@SerialName("[^"]*")' "$SRC" | sed 's/.*("\(.*\)").*/\1/' | sort -u)
for key in $keys; do
    if grep -qa -- "$key" "$work"/classes*.dex; then
        echo "  ok    \"$key\" present in the dex"
    else
        echo "  FAIL  \"$key\" is not in the minified dex" >&2
        status=1
    fi
done

# Every restriction key the MDM can send must still be in the shipped APK. The
# resource path is obfuscated in a release build (res/xml/app_restrictions.xml
# becomes res/Kt.xml or similar), so the file is resolved through the resource
# table rather than by name.
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
    # Dumped once, to a file. Under `set -o pipefail` a `grep -q` per key kills
    # aapt2 with SIGPIPE the moment it matches, and the pipeline then reports the
    # signal rather than the match -- which read as "shrunk out" for exactly the
    # keys that appear early enough in the dump for aapt2 to still be writing.
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
