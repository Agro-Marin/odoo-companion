#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=tools/versions.env
. tools/versions.env

KTLINT="${KTLINT:-ktlint}"
DETEKT="${DETEKT:-detekt}"
status=0
fix=0
android_lint=1
for arg in "$@"; do
    case "$arg" in
        --fix) fix=1 ;;
        --static-only) android_lint=0 ;;
        *) echo "unknown option: $arg" >&2; exit 64 ;;
    esac
done

require() {
    local name="$1" tool="$2" want="$3" got
    if ! got=$("$tool" --version 2>/dev/null); then
        echo "== $name: not runnable as '$tool' -- set ${name^^}=/path/to/$name" >&2
        return 1
    fi
    got=$(echo "$got" | tr -dc '0-9.' )
    case "$got" in
        *"$want"*) return 0 ;;
        *) echo "== $name $got is not the pinned $want; its verdict is not the pinned one" >&2
           return 1 ;;
    esac
}

require ktlint "$KTLINT" "$KTLINT_VERSION" || status=1
require detekt "$DETEKT" "$DETEKT_VERSION" || status=1
[ "$status" -eq 0 ] || exit "$status"

echo "== ktlint $KTLINT_VERSION =="
if [ "$fix" -eq 1 ]; then
    "$KTLINT" -F "app/src/**/*.kt"
else
    "$KTLINT" "app/src/**/*.kt" || status=1
fi

echo "== detekt $DETEKT_VERSION =="
"$DETEKT" --input app/src/main/java,app/src/test/java --config config/detekt.yml || status=1

echo "== cancellation-safe suspend calls =="
bare=$(grep -rn 'runCatching' app/src/main/java/com/odoocompanion/sync || true)
if [ -n "$bare" ]; then
    echo "$bare" >&2
    echo "runCatching swallows CancellationException; use attempt {} in the upload path" >&2
    status=1
fi

echo "== no recording paths or personal fields in log statements =="
leaked=$(python3 - <<'PYEOF'
import pathlib, re, sys
bad = []
PERSONAL = re.compile(
    r"\.(chat|sender|senderPhone|phone|rfc|label|number|contactName|remote)\b"
    r"(?!\s*[!=]=\s*null)"
)
for path in pathlib.Path("app/src/main").rglob("*.kt"):
    text = path.read_text()
    def closing(index, opener, closer):
        depth = 0
        while index < len(text):
            if text[index] == opener:
                depth += 1
            elif text[index] == closer:
                depth -= 1
                if depth == 0:
                    break
            index += 1
        return index

    for match in re.finditer(r"(?:\bLog\.[vdiwe]|\bdebug)\(", text):
        index = closing(match.end() - 1, "(", ")")
        rest = text[index + 1:]
        if rest.lstrip(" ").startswith("{"):
            index = closing(index + 1 + len(rest) - len(rest.lstrip(" ")), "{", "}")
        call = text[match.start():index + 1]
        if "absolutePath" in call or "filePath" in call or PERSONAL.search(call):
            line = text[:match.start()].count("\n") + 1
            bad.append(f"{path}:{line}: {call.splitlines()[0].strip()}")
print("\n".join(bad))
PYEOF
)
if [ -n "$leaked" ]; then
    echo "$leaked" >&2
    echo "a recording filename holds the other party's phone number, and these fields" \
        "name a person; log the directory, a count or a presence" >&2
    status=1
fi

if [ "$android_lint" -eq 1 ]; then
    echo "== android lint =="
    ./gradlew lintDebug || status=1
fi

exit $status
