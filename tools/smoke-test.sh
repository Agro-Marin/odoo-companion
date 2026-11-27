#!/usr/bin/env bash
set -euo pipefail

if [ $# -ne 3 ]; then
    echo "Usage: tools/smoke-test.sh <base-url> <device-identifier> <bearer-token>" >&2
    echo "Example: tools/smoke-test.sh http://localhost:8069 phone-01 6389eae0..." >&2
    exit 64
fi

ROOT="${1%/}/remote/mobile"
BASE="$ROOT/$2"
TOKEN="$3"
NOW=$(($(date +%s) * 1000))
NUMBER="+525512345678"
CALL_SECONDS=300
failures=0

check() {
    local label="$1" url="$2" body="$3" token="${TOKEN}"
    shift 3
    if [ "${1:-}" = "--token" ]; then token="$2"; shift 2; fi
    local reply code
    reply=$(curl -s -w $'\n%{http_code}' -X POST "$url" \
        -H "Authorization: Bearer $token" \
        -H "Content-Type: application/json" \
        -d "$body")
    code="${reply##*$'\n'}"
    reply="${reply%$'\n'*}"
    if python3 -c '
import json, sys
code, *cases = sys.argv[1:]
expected = dict(case.split("=", 1) for case in cases).get(code)
if expected is None:
    sys.exit(f"unexpected HTTP {code}")
data = json.load(sys.stdin)
fields = {"service": "remote_mobile", **json.loads(expected)}
if not isinstance(data, dict):
    sys.exit("expected a JSON object")
for key, value in fields.items():
    if key not in data or json.dumps(data[key], sort_keys=True) != json.dumps(value, sort_keys=True):
        sys.exit(f"unexpected or missing {key}")
' "$code" "$@" <<< "$reply"; then
        echo "ok    $label (HTTP $code)"
    else
        echo "FAIL  $label (HTTP $code)" >&2
        failures=$((failures + 1))
    fi
}

check "a batch of two fixes is stored" "$BASE/location" "{\"points\":[
    {\"latitude\":19.4326,\"longitude\":-99.1332,\"timestamp\":$NOW,\"accuracy\":12.5,
     \"altitude\":2240.0,\"speed\":3.4,\"heading\":275.0,\"battery_level\":87},
    {\"latitude\":19.4330,\"longitude\":-99.1340,\"timestamp\":$((NOW + 30000)),\"accuracy\":9.0}]}" \
    '200={"status":"success","accepted":2,"max_payload_bytes":52428800}'

CALL="{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"outgoing\",\"timestamp\":$NOW,
     \"duration\":$CALL_SECONDS,\"contact_name\":\"Smoke Test\"}]}"
check "a call, in the words CallDirection sends" "$BASE/calllog" "$CALL" \
    '200={"status":"success","accepted":1}'

check "the same batch again is a delivery, not a refusal" "$BASE/calllog" "$CALL" \
    '409={"error":"duplicate_event"}' '200={"status":"success","accepted":0,"duplicates":1}'

check "a batch route names the rows it could not store" "$BASE/calllog" "{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"incoming\",\"timestamp\":$((NOW + 500)),\"duration\":1},
    {\"number\":\"\",\"direction\":\"incoming\",\"timestamp\":$((NOW + 600)),\"duration\":1}]}" \
    '200={"status":"success","accepted":1,"skipped":1,"skipped_indexes":[1]}'

check "a call from a withheld number is stored without one" "$BASE/calllog" "{\"calls\":[
    {\"number\":\"\",\"direction\":\"missed\",\"timestamp\":$((NOW + 700)),\"duration\":0,
     \"presentation\":\"restricted\"}]}" \
    '200={"status":"success","accepted":1}'

check "the raw integer an older queue still holds" "$BASE/calllog" "{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"1\",\"timestamp\":$((NOW + 1000)),\"duration\":7}]}" \
    '200={"status":"success","accepted":1}'

check "an Android call type this build does not know is stored, not refused" \
    "$BASE/calllog" "{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"9\",\"timestamp\":$((NOW + 2000)),\"duration\":3}]}" \
    '200={"status":"success","accepted":1}'

AUDIO=$( { printf '\x00\x00\x00\x18ftypM4A \x00\x00\x00\x00M4A isom'; head -c 2048 /dev/urandom; } | base64 | tr -d '\r\n')
check "a recording stamped at the call's end matches the call" "$BASE/recording" \
    "{\"number\":\"$NUMBER\",\"recorded_at\":$((NOW + CALL_SECONDS * 1000)),
    \"file_name\":\"call_${NUMBER}.m4a\",\"mimetype\":\"audio/mp4\",\"audio_b64\":\"$AUDIO\"}" \
    '200={"status":"success","matched":true}'

check "a wrong token is refused, and says who refused it" "$BASE/location" '{"points":[]}' \
    --token definitely-not-the-token '401={"error":"authentication_failed"}'

check "an unknown identifier is a 404 the phone keeps its queue through" \
    "$ROOT/$2-does-not-exist/location" '{"points":[]}' '404={"error":"endpoint_not_found"}'

if [ "$failures" -gt 0 ]; then
    echo "$failures answer(s) differ from what the app is built against" >&2
    exit 1
fi
echo "every answer is the one the app is built against"
