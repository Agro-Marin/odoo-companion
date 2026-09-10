#!/usr/bin/env bash
# Replays the payloads the app sends against a live Odoo instance.
#
# Usage: tools/smoke-test.sh <base-url> <device-identifier> <bearer-token>
# Example: tools/smoke-test.sh http://localhost:8069 phone-01 6389eae0...
set -euo pipefail

if [ $# -ne 3 ]; then
    sed -n '2,5p' "$0"
    exit 64
fi

BASE="${1%/}/remote/mobile/$2"
TOKEN="$3"
NOW=$(($(date +%s) * 1000))
NUMBER="+525512345678"

post() {
    curl -s -w "\n  HTTP %{http_code}\n" -X POST "$BASE/$1" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "$2"
}

echo "1. location — a batch of two fixes"
post location "{\"points\":[
    {\"latitude\":19.4326,\"longitude\":-99.1332,\"timestamp\":$NOW,\"accuracy\":12.5,
     \"altitude\":2240.0,\"speed\":3.4,\"heading\":275.0,\"battery_level\":87},
    {\"latitude\":19.4330,\"longitude\":-99.1340,\"timestamp\":$((NOW + 30000)),\"accuracy\":9.0}]}"

echo "2. calllog — the words the app sends since CallDirection translated them"
CALL="{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"outgoing\",\"timestamp\":$NOW,
     \"duration\":42,\"contact_name\":\"Smoke Test\"}]}"
post calllog "$CALL"

echo "2b. the same batch again — a duplicate is a delivery, not a refusal"
echo "    (expect 200 with duplicates:1; a 422 here dead-letters delivered calls)"
post calllog "$CALL"

echo "2b'. every reply names the service and the cap; a batch route names its skipped rows"
echo "    (expect \"service\":\"remote_mobile\", \"max_payload_bytes\", \"skipped_indexes\":[1])"
post calllog "{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"incoming\",\"timestamp\":$((NOW + 500)),\"duration\":1},
    {\"number\":\"\",\"direction\":\"incoming\",\"timestamp\":$((NOW + 600)),\"duration\":1}]}"

echo "2c. calllog — the raw integer an older queue still holds"
post calllog "{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"1\",\"timestamp\":$((NOW + 1000)),
     \"duration\":7}]}"

echo "2d. calllog — an Android call type this build does not know"
echo "    (expect 200: stored as Unknown, not dropped and 422'd back at the phone)"
post calllog "{\"calls\":[
    {\"number\":\"$NUMBER\",\"direction\":\"9\",\"timestamp\":$((NOW + 2000)),
     \"duration\":3}]}"

echo "3. recording — matches the call above by number and time"
AUDIO=$(head -c 2048 /dev/urandom | base64 -w0)
post recording "{\"number\":\"$NUMBER\",\"recorded_at\":$((NOW + 5000)),
    \"file_name\":\"smoke_${NUMBER}_test.m4a\",\"mimetype\":\"audio/mp4\",
    \"audio_b64\":\"$AUDIO\"}"

echo "4. a wrong token must be refused"
curl -s -o /dev/null -w "  HTTP %{http_code} (expected 401)\n" -X POST "$BASE/location" \
    -H "Authorization: Bearer definitely-not-the-token" \
    -H "Content-Type: application/json" \
    -d '{"points":[]}'
