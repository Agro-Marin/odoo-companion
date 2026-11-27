#!/usr/bin/env bash
set -uo pipefail

cd "$(dirname "$0")/.."

apis=35
build=1
for arg in "$@"; do
    case "$arg" in
        --api=*) apis="${arg#--api=}" ;;
        --skip-build) build=0 ;;
        *) echo "unknown option: $arg" >&2; exit 64 ;;
    esac
done

: "${ODOO_PYTHON:?set ODOO_PYTHON to the interpreter that runs odoo-bin}"
: "${ODOO_BIN:?set ODOO_BIN to the odoo-bin to serve the lab database}"
: "${ODOO_CONF:?set ODOO_CONF to that server configuration}"
read -r -a odoo_args <<< "${ODOO_ARGS:-}"
odoo_port=${ODOO_PORT:-8185}
sdk=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}
adb=$sdk/platform-tools/adb
emulator=$sdk/emulator/emulator
avdmanager=$sdk/cmdline-tools/latest/bin/avdmanager
emu_port=${EMULATOR_PORT:-5584}
serial=emulator-$emu_port
apk=app/build/outputs/apk/debug/app-debug.apk

work=$(mktemp -d "${TMPDIR:-/tmp}/device-check.XXXXXX")
db=device_check_$$
identifier=DEVICE-CHECK-$$
avd=
emu_pid=
stub_pid=
odoo_pid=
failures=0
finished=0
ODOO_API_ENCRYPTION_KEY=$(python3 -c 'import base64, os; print(base64.urlsafe_b64encode(os.urandom(32)).decode())')
export ODOO_API_ENCRYPTION_KEY

odoo() { "$ODOO_PYTHON" "$ODOO_BIN" "$@"; }
on() { "$adb" -s "$serial" shell "$@"; }

stop_emulator() {
    [ -n "$emu_pid" ] || return 0
    "$adb" -s "$serial" logcat -d > "$work/logcat-$api.txt" 2>/dev/null
    "$adb" -s "$serial" emu kill >/dev/null 2>&1
    wait "$emu_pid" 2>/dev/null
    emu_pid=
    "$avdmanager" delete avd -n "$avd" >/dev/null 2>&1
}

cleanup() {
    stop_emulator
    if [ "$failures" -ne 0 ] || [ "$finished" -ne 1 ]; then
        kept=${TMPDIR:-/tmp}/device-check-$$-logs
        mkdir -p "$kept"
        cp "$work"/*.log "$work"/*.txt "$kept"/ 2>/dev/null
        echo "logs kept in $kept"
    fi
    for pid in "$stub_pid" "$odoo_pid"; do
        [ -n "$pid" ] || continue
        kill "$pid" 2>/dev/null
        for _ in $(seq 20); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
        kill -9 "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
    done
    odoo db -c "$ODOO_CONF" drop "$db" >/dev/null 2>&1
    rm -rf "$work"
}
trap cleanup EXIT

result() {
    if [ "$2" -eq 0 ]; then
        echo "PASS  api $api  $1"
    else
        echo "FAIL  api $api  $1${3:+ -- $3}"
        failures=$((failures + 1))
    fi
}

within() {
    local seconds=$1
    shift
    for _ in $(seq "$seconds"); do
        "$@" && return 0
        sleep 1
    done
    return 1
}

top_is() {
    on dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q "$1"
}
locked() { on dumpsys window | grep -qE 'isKeyguardShowing=true|mIsShowing=true'; }
gone() { ! on dumpsys activity activities | grep -q "$1"; }
logged() { "$adb" -s "$serial" logcat -d | grep -q "$1"; }
stub_count() { grep -c "$1" "$work/sip.log"; }
stub_grew() { [ "$(stub_count "$1")" -gt "$2" ]; }
noticed() { on dumpsys notification | grep -q "pkg=com.odoocompanion .*id=$1 "; }

enrol() {
    on am broadcast -n com.odoocompanion/.system.LabEnrollReceiver \
        --es base_url "http://10.0.2.2:$odoo_port" --es identifier "$identifier" \
        --es token "$(cat "$work/token")" --el upload_window_seconds 0 > /dev/null
}

booted() { [ "$(on getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }

relock() {
    on input keyevent KEYCODE_SLEEP
    sleep 2
    on input keyevent KEYCODE_WAKEUP
    sleep 2
}

bounds_of() {
    on uiautomator dump /sdcard/ui.xml >/dev/null
    on cat /sdcard/ui.xml | tr '>' '\n' | grep "id/$1\"" | grep -oE 'bounds="[^"]*"' | head -1 \
        | grep -oE '[0-9]+' | paste -sd' '
}

navigation_top() {
    {
        on dumpsys window | grep -E 'navigationBars' | grep -oE 'frame=\[[0-9]+,[0-9]+\]'
        on dumpsys window windows | grep -A30 'Window{.*NavigationBar' \
            | grep -oE 'mFrame=\[[0-9]+,[0-9]+\]'
    } | head -1 | grep -oE '[0-9]+\]$' | tr -d ']'
}

if [ "$build" -eq 1 ]; then
    ./gradlew -q assembleDebug || exit 1
    ./gradlew -q --stop
fi
[ -f "$apk" ] || { echo "no debug APK at $apk" >&2; exit 1; }

echo "== lab Odoo: database $db on port $odoo_port"
odoo -c "$ODOO_CONF" "${odoo_args[@]}" -d "$db" -i device_mobile,device_phone_call,voip_device \
    --without-demo=all --stop-after-init --http-port "$odoo_port" > "$work/install.log" 2>&1 \
    || { tail -20 "$work/install.log" >&2; exit 1; }
LAB_IDENTIFIER=$identifier LAB_PBX=10.0.2.2 LAB_TOKEN_FILE=$work/token \
    odoo shell -c "$ODOO_CONF" "${odoo_args[@]}" -d "$db" --no-http < tools/lab-odoo.py \
    > "$work/provision.log" 2>&1
grep -q 'ready: True' "$work/provision.log" || { tail -20 "$work/provision.log" >&2; exit 1; }
"$ODOO_PYTHON" "$ODOO_BIN" -c "$ODOO_CONF" "${odoo_args[@]}" -d "$db" --db-filter="^$db\$" \
    --http-port "$odoo_port" > "$work/odoo.log" 2>&1 &
odoo_pid=$!
within 120 grep -q 'HTTP service running on' "$work/odoo.log" \
    || { tail -20 "$work/odoo.log" >&2; exit 1; }

echo "== SIP stand-in"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$work/ca.key" -out "$work/ca.pem" -days 1 \
    -subj "/CN=Device check CA" -addext "basicConstraints=critical,CA:TRUE" 2>/dev/null
openssl req -newkey rsa:2048 -nodes -keyout "$work/server.key" -out "$work/server.csr" \
    -subj "/CN=10.0.2.2" 2>/dev/null
openssl x509 -req -in "$work/server.csr" -CA "$work/ca.pem" -CAkey "$work/ca.key" \
    -CAcreateserial -days 1 -out "$work/server.pem" \
    -extfile <(printf "subjectAltName=IP:10.0.2.2\n") 2>/dev/null
python3 -u tools/sip-stub.py --cert "$work/server.pem" --key "$work/server.key" \
    --hangup-after 5 > "$work/sip.log" 2>&1 &
stub_pid=$!
within 10 grep -q listening "$work/sip.log" || { cat "$work/sip.log" >&2; exit 1; }

for api in ${apis//,/ }; do
    echo "== Android API $api"
    avd=device-check-$api-$$
    if ! echo no | "$avdmanager" create avd -n "$avd" \
        -k "system-images;android-$api;default;x86_64" -d pixel_5 > "$work/avd-$api.log" 2>&1; then
        result "an emulator image is installed" 1 "$(tail -1 "$work/avd-$api.log")"
        continue
    fi
    "$emulator" -avd "$avd" -no-window -no-audio -no-snapshot -gpu swangle_indirect \
        -memory 4096 -port "$emu_port" > "$work/emulator-$api.log" 2>&1 &
    emu_pid=$!
    if ! timeout 300 "$adb" -s "$serial" wait-for-device || ! within 300 booted; then
        result "the emulator boots" 1
        stop_emulator
        continue
    fi
    "$adb" -s "$serial" root >/dev/null
    sleep 3
    "$adb" -s "$serial" wait-for-device
    "$adb" -s "$serial" install -r "$apk" > /dev/null
    for permission in RECORD_AUDIO POST_NOTIFICATIONS READ_CALL_LOG READ_PHONE_STATE \
        ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION; do
        on pm grant com.odoocompanion "android.permission.$permission" 2>/dev/null
    done
    "$adb" -s "$serial" push "$work/ca.pem" /data/local/tmp/lab-ca.pem > /dev/null
    on run-as com.odoocompanion mkdir -p files
    on run-as com.odoocompanion cp /data/local/tmp/lab-ca.pem files/extra-ca.pem
    on run-as com.odoocompanion ls files/extra-ca.pem > /dev/null 2>&1
    result "the lab CA is in the debug build's trust file" $?
    on locksettings set-pin 1234 > /dev/null
    "$adb" -s "$serial" logcat -c

    enrol
    within 30 logged 'Lab enrolment: Saved'
    result "the handset enrols with the lab Odoo" $?
    if [ "$api" -ge 31 ]; then
        within 30 noticed 7401
        result "a start Android refuses in the background is announced" $?
    fi

    on cmd deviceidle whitelist +com.odoocompanion > /dev/null
    enrol
    within 60 logged 'SoftphoneService: Registration Ok'
    result "the softphone registers with the phone system" $?

    relock
    on am start -n com.odoocompanion/.softphone.InCallActivity > /dev/null
    sleep 3
    ! top_is InCallActivity && locked
    result "a locked phone with no call does not show the dialer" $?

    relock
    answered=$(stub_count '<< SIP/2.0 200')
    hangups=$(stub_count '>> BYE')
    kill -USR1 "$stub_pid"
    within 20 top_is InCallActivity && locked
    result "an incoming call rings over the lock screen" $?

    read -r left top_edge right bottom <<< "$(bounds_of answer)"
    nav=$(navigation_top)
    if [ -n "${bottom:-}" ] && [ -n "$nav" ]; then
        [ "$bottom" -le "$nav" ]
        result "Answer is clear of the navigation bar" $? "button ends at $bottom, bar starts at $nav"
    fi
    if [ -n "${bottom:-}" ]; then
        on input tap $(((left + right) / 2)) $(((top_edge + bottom) / 2))
        within 10 stub_grew '<< SIP/2.0 200' "$answered"
        result "a tap on the centre of Answer answers the call" $?
    else
        result "the call screen shows Answer" 1
    fi

    within 20 stub_grew '>> BYE' "$hangups" && within 10 gone InCallActivity && locked
    result "the call screen closes on a locked phone when the far end hangs up" $?

    on content insert --uri content://call_log/calls --bind number:s:5512345678 \
        --bind type:i:1 --bind "date:l:$(date +%s)000" --bind duration:i:30 --bind new:i:1
    within 180 logged 'accepted={calllog=1}'
    result "a call in the handset's log reaches Odoo" $?
    within 60 grep -q "POST /remote/mobile/$identifier/health HTTP/1.1\" 200" "$work/odoo.log"
    result "the handset reports its health to Odoo" $?

    stop_emulator
done

finished=1
echo "== $failures failure(s)"
[ "$failures" -eq 0 ]
