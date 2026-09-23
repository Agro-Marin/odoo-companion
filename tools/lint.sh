#!/usr/bin/env bash
# Formatting (ktlint), static analysis (detekt) and Android Lint in one pass.
#
# Usage: tools/lint.sh [--fix] [--static-only]
#
#   --fix          let ktlint rewrite what it can
#   --static-only  ktlint and detekt, without Android Lint. What CI's
#                  static-analysis job runs: it has no Gradle or SDK setup, and
#                  the build job already runs lintDebug -- doing it in both is a
#                  second full Android build for the same answer.
#
# KTLINT and DETEKT may point at a specific binary; otherwise PATH is used. The
# version is checked either way, because "clean" is only a claim about the
# version that said it.
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

# A tool that is absent, or is not the pinned version, does not get to report a
# clean tree. Both failures used to pass silently: CI chmod'd a launcher name
# that does not exist in the release zip, so the job died before either tool
# ran, and a local ktlint two minors ahead of the pin called the tree clean when
# the pinned one would not.
require() {
    local name="$1" tool="$2" want="$3" got
    if ! got=$("$tool" --version 2>/dev/null); then
        echo "== $name: not runnable as '$tool' -- set ${name^^}=/path/to/$name" >&2
        return 1
    fi
    got=$(echo "$got" | tr -dc '0-9.' )
    case "$got" in
        *"$want"*) return 0 ;;
        *) echo "== $name $got is not the pinned $want; results will not match CI" >&2
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
# Tests included: they are half the Kotlin here, they encode the delivery
# semantics nothing else states, and they were analysed by nothing.
"$DETEKT" --input app/src/main/java,app/src/test/java --config config/detekt.yml || status=1

# detekt's SwallowedException and TooGenericExceptionCaught are both on, and
# neither can see this: they inspect `catch` blocks, and `runCatching` catches
# Throwable -- CancellationException included. A drain stopped by WorkManager
# was being charged to the retry budget as if the server had refused it, with a
# clean detekt report the whole time. `attempt` in OutboxDrainer rethrows
# cancellation; nothing in the upload path may go back to the bare form.
echo "== cancellation-safe suspend calls =="
bare=$(grep -rn 'runCatching' app/src/main/java/com/odoocompanion/sync || true)
if [ -n "$bare" ]; then
    echo "$bare" >&2
    echo "runCatching swallows CancellationException; use attempt {} in the upload path" >&2
    status=1
fi

# A call recording is named by an OEM dialer, and the project's own filename
# corpus is why that matters: "call_5512345678.m4a", "Call recording
# +525512345678_251028_143501.m4a". A log line carrying the path carries the
# counterparty's phone number into logcat, into every bug report, and into
# whatever the OEM ships logs to. The directory is the useful diagnostic --
# it names the per-OEM folder -- and holds nothing personal.
echo "== no recording paths in log statements =="
leaked=$(python3 - <<'PYEOF'
import pathlib, re, sys
bad = []
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

    # debug {} takes its message as a trailing lambda, so the call runs to the
    # closing brace, not the closing parenthesis.
    for match in re.finditer(r"(?:\bLog\.[vdiwe]|\bdebug)\(", text):
        index = closing(match.end() - 1, "(", ")")
        rest = text[index + 1:]
        if rest.lstrip(" ").startswith("{"):
            index = closing(index + 1 + len(rest) - len(rest.lstrip(" ")), "{", "}")
        call = text[match.start():index + 1]
        if "absolutePath" in call or "filePath" in call:
            line = text[:match.start()].count("\n") + 1
            bad.append(f"{path}:{line}: {call.splitlines()[0].strip()}")
print("\n".join(bad))
PYEOF
)
if [ -n "$leaked" ]; then
    echo "$leaked" >&2
    echo "a recording filename holds the other party's phone number; log the directory" >&2
    status=1
fi

if [ "$android_lint" -eq 1 ]; then
    echo "== android lint =="
    ./gradlew lintDebug || status=1
fi

exit $status
