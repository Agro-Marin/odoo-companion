#!/usr/bin/env bash
# Replays the payloads the app sends against a live Odoo instance, and checks
# each answer against what remote_mobile is known to reply. Exits non-zero on
# the first answer that differs, so it can gate a release rather than be read.
#
# Usage: tools/smoke-test.sh <base-url> <device-identifier> <bearer-token>
# Example: tools/smoke-test.sh http://localhost:8069 phone-01 6389eae0...
#
# The device must be in the Mobile Phone category (duplicate detection on, a
# 50 MB cap), which is how provisioning creates it. Every row posted is stored,
# so use a device record made for testing.
set -euo pipefail

if [ $# -ne 3 ]; then
    sed -n '6,7p' "$0"
    exit 64
fi

ROOT="${1%/}/remote/mobile"
BASE="$ROOT/$2"
TOKEN="$3"
NOW=$(($(date +%s) * 1000))
NUMBER="+525512345678"
CALL_SECONDS=300
failures=0

# check <label> <codes> <body> <needle>... : POST the body, require one of the
# comma-separated status codes and every needle as a substring of the reply.
check() {
    local label="$1" codes="$2" url="$3" body="$4" token="${TOKEN}"
    shift 4
    if [ "${1:-}" = "--token" ]; then token="$2"; shift 2; fi
    local reply code
    reply=$(curl -s -w $'\n%{http_code}' -X POST "$url" \
        -H "Authorization: Bearer $token" \
        -H "Content-Type: application/json" \
        -d "$body")
    code="${reply##*$'\n'}"
    reply="${reply%$'\n'*}"
    local ok=1
    [[ ",$codes," == *",$code,"* ]] || ok=0
    for needle in "$@"; do
        [[ "$reply" == *"$needle"* ]] || ok=0
    done
    if [ "$ok" -eq 1 ]; then
        echo "ok    $label (HTTP $code)"
    else
        echo "FAIL  $label: expected HTTP $codes containing $*" >&2
        echo "      got HTTP $code: $reply" >&2
        failures=$((failures + 1))
    fi
}

SERVICE='"service":"remote_mobile"'

check "a batch of two fixes is stored" 200 "$BASE/location" "{\"points\":[
    {\"latitude\":19.4326,\"longitude\":-99.1332,\"timestamp\":$NOW,\"accuracy\":12.5,
     \"altitude\":2240.0,\"speed\":3.4,\"heading\":275.0,\"battery_level\":87},
    {\"latitude\":19.4330,\"longitude\":-99.1340,\"timestamp\":$((NOW + 30000)),\"accuracy\":9.0}]}" \
    "$SERVICE" '"status":"success"' '"accepted":2' '"max_payload_bytes":52428800'

CALL="{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"outgoing\",\"timestamp\":$NOW,
     \"duration\":$CALL_SECONDS,\"contact_name\":\"Smoke Test\"}]}"
check "a call, in the words CallDirection sends" 200 "$BASE/calllog" "$CALL" \
    "$SERVICE" '"accepted":1'

# Inside duplicate_window_seconds the transport layer answers 409; past it the
# model layer answers 200 with duplicates:1. Both are deliveries. A 422 here is
# the defect that dead-lettered calls the server was holding.
check "the same batch again is a delivery, not a refusal" 409,200 "$BASE/calllog" "$CALL" \
    "$SERVICE"

check "a batch route names the rows it could not store" 200 "$BASE/calllog" "{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"incoming\",\"timestamp\":$((NOW + 500)),\"duration\":1},
    {\"number\":\"\",\"direction\":\"incoming\",\"timestamp\":$((NOW + 600)),\"duration\":1}]}" \
    "$SERVICE" '"skipped":1' '"skipped_indexes":[1]'

check "the raw integer an older queue still holds" 200 "$BASE/calllog" "{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"1\",\"timestamp\":$((NOW + 1000)),\"duration\":7}]}" \
    '"accepted":1'

check "an Android call type this build does not know is stored, not refused" 200 \
    "$BASE/calllog" "{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"9\",\"timestamp\":$((NOW + 2000)),\"duration\":3}]}" \
    '"accepted":1'

# Stamped the way the app stamps it: the file's write time, which is when the
# call ended. A stamp a few seconds after the start is what this script used to
# send, and it matched on a server that could not match a real recording.
AUDIO=$(head -c 2048 /dev/urandom | base64 -w0)
check "a recording stamped at the call's end matches the call" 200 "$BASE/recording" \
    "{\"number\":\"$NUMBER\",\"recorded_at\":$((NOW + CALL_SECONDS * 1000)),
    \"file_name\":\"call_${NUMBER}.m4a\",\"mimetype\":\"audio/mp4\",\"audio_b64\":\"$AUDIO\"}" \
    "$SERVICE" '"status":"success"' '"matched":true'

check "a wrong token is refused, and says who refused it" 401 "$BASE/location" '{"points":[]}' \
    --token definitely-not-the-token "$SERVICE" '"error":"authentication_failed"'

check "an unknown identifier is a 404 the phone keeps its queue through" 404 \
    "$ROOT/$2-does-not-exist/location" '{"points":[]}' "$SERVICE" '"error":"endpoint_not_found"'

if [ "$failures" -gt 0 ]; then
    echo "$failures answer(s) differ from what the app is built against" >&2
    exit 1
fi
echo "every answer is the one the app is built against"
