#!/usr/bin/env bash
# One iteration of the device test loop, run from a Mac with the device on USB:
# build, unit test, install, restart ATAK, capture logcat while you test,
# redact credentials, and print a report to paste back.
#
#   atak/scripts/device-test.sh [max-capture-seconds]
#   atak/scripts/device-test.sh --scenario <name|file> [max-capture-seconds]
#
# Interactive (default): the capture runs until you press Enter (or Ctrl-C)
# in this terminal. An optional number of seconds ends it automatically.
#
# Scenario: no input needed. Once the plugin is running, the script drives it
# over adb (debug builds only) from atak/scripts/scenarios/<name>.txt, takes
# the screenshots the scenario asks for, and writes the report. The tablet
# must be on and unlocked, with ATAK set to load the plugin at startup.
#
# Environment: ANDROID_SERIAL picks a device when several are attached;
# ATAK_PACKAGE overrides com.atakmap.app.civ.
set -euo pipefail

ATAK_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ATAK_DIR"
ATAK_PACKAGE="${ATAK_PACKAGE:-com.atakmap.app.civ}"
PLUGIN_PACKAGE="com.cesium.atak.ion"
usage() {
    echo "Usage: $0 [--scenario <name|file>] [--prebuilt <dir>] [max-capture-seconds]" >&2
    echo "       $0 --build-only <dir>" >&2
    exit 2
}
MAX_CAPTURE_SECONDS=0
SCENARIO=""
# --build-only: build and copy the plugin APK and the SDK's atak.apk to <dir>,
# then stop (the Mac runner hands them to the work PC this way).
# --prebuilt: install the APKs from <dir> instead of building (the work PC,
# which has no ATAK SDK, runs this script under Git Bash).
BUILD_ONLY=""
PREBUILT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --scenario) [ $# -ge 2 ] || usage; SCENARIO="$2"; shift 2 ;;
        --build-only) [ $# -ge 2 ] || usage; BUILD_ONLY="$2"; shift 2 ;;
        --prebuilt) [ $# -ge 2 ] || usage; PREBUILT="$2"; shift 2 ;;
        --scenario=*) SCENARIO="${1#--scenario=}"; shift ;;
        ''|*[!0-9]*) usage ;;
        *) MAX_CAPTURE_SECONDS="$1"; shift ;;
    esac
done
if [ -n "$SCENARIO" ] && [ ! -f "$SCENARIO" ]; then
    if [ -f "$ATAK_DIR/scripts/scenarios/$SCENARIO.txt" ]; then
        SCENARIO="$ATAK_DIR/scripts/scenarios/$SCENARIO.txt"
    else
        echo "No scenario $SCENARIO (looked in atak/scripts/scenarios/)." >&2
        exit 2
    fi
fi
STAMP="$(date +%Y%m%d-%H%M%S)"
REPORT_DIR="$ATAK_DIR/device-reports"
mkdir -p "$REPORT_DIR"
RAW="$REPORT_DIR/$STAMP-logcat-raw.txt"
REPORT="$REPORT_DIR/$STAMP-report.txt"
BUILD_LOG="$REPORT_DIR/$STAMP-build.txt"
SDK_DIR=""

step() { printf '\n==> %s\n' "$*"; }
fail() { printf '\nSTOPPED: %s\n' "$*" >&2; exit 1; }

# Credentials never belong in a report: mask query-string secrets, bearer
# tokens, JWTs, and the local tile proxy's URL secret.
redact() {
    perl -pe 's/((?:access_token|token|key|session|apikey|api_key)=)[^&"\s]+/${1}REDACTED/gi; s/(bearer\s+)[A-Za-z0-9._~+\/=-]+/${1}REDACTED/gi; s/eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]+/REDACTED/g; s#(127\.0\.0\.1:\d+/)[A-Za-z0-9_-]{20,}/#${1}PROXY_SECRET/#g; s#/(?=[A-Za-z0-9_-]*[0-9])(?=[A-Za-z0-9_-]*[A-Z])(?=[A-Za-z0-9_-]*[a-z])[A-Za-z0-9_-]{20,}(?=[/?\s"]|$)#/REDACTED#g'
}

# Lists SDK classes the plugin needs next, with javap signatures, so plugin
# code can match the real API (the SDK is the only authority for it).
# Found: GLMapSurface.setConfigChooser uses setEGLConfigChooser(5,6,5,0,8,8)
# (exact RGB565, which the emulator lacks) when "OPEN GL is good", and a
# generic RGBA8888 chooser when it is "a bit busted". Next: what decides.
# Earlier: ATAK dies at start on the Android emulator with
# "IllegalArgumentException: No config chosen" in every emulator GPU mode,
# so find the EGL config ATAK's map surface asks for: which classes set an
# EGL config chooser, and the EGL attribute constants (EGL_SAMPLES 0x3031
# = 12337, EGL_DEPTH_SIZE 12325, EGL_STENCIL_SIZE 12326, ...) in their code.
# Earlier scans found ATAK's EGL config switch (opengl.broken) and that
# AtakMapController.panZoomTo looks up terrain height on the calling thread.
# Now: a fresh ATAK (the work PC's emulator) does not load the plugin until
# someone loads it in Plugin Manager. Find how ATAK records which plugins to
# load at startup (preference keys, intents), so a fresh emulator can be set up
# without taps.
SCAN_LIST_PATTERN='^$NOTHING'
SCAN_JAVAP_PATTERN='^$NOTHING'
SCAN_FILTERED_CLASSES=''
SCAN_FILTER=''
SCAN_STRINGS_PATTERN='^$NOTHING'
SCAN_USERS_PATH='^$NOTHING'
SCAN_USERS_SYMBOL='NOTHING'
SCAN_USERS_CODE=''
SCAN_CODE_CLASSES=''
SCAN_CODE_METHODS=''
# Answer: AtakPluginRegistry.SHOULD_LOAD = "shouldLoad-" + package, in ATAK's
# default preferences; enable_plugin_on_emulator sets it. Scan off.
SCAN_STRING_USERS=''

sdk_api_scan() {
    [ -n "$SDK_DIR" ] && [ -f "$SDK_DIR/main.jar" ] || return 0
    echo "---- ATAK SDK API scan: $SDK_DIR/main.jar ----"
    local entries
    entries="$(unzip -l "$SDK_DIR/main.jar" | awk '{print $4}' | grep -E '\.class$' | grep -vE '\$[0-9]+\.class$' || true)"
    printf '%s\n' "$entries" | grep -iE "$SCAN_LIST_PATTERN" | head -150 || true
    local javap="${JAVA_HOME:+$JAVA_HOME/bin/}javap"
    for class in $(printf '%s\n' "$entries" | grep -E "$SCAN_JAVAP_PATTERN" | sed 's/\.class$//; s#/#.#g' | head -8); do
        echo "-- javap -constants $class"
        "$javap" -constants -cp "$SDK_DIR/main.jar" "$class" 2>&1 | head -120 || true
    done
    for class in $SCAN_FILTERED_CLASSES; do
        echo "-- javap $class (members matching: $SCAN_FILTER)"
        "$javap" -constants -cp "$SDK_DIR/main.jar" "$class" 2>&1 | grep -iE "$SCAN_FILTER" | head -60 || true
    done
    local all_entries
    all_entries="$(unzip -l "$SDK_DIR/main.jar" | awk '{print $4}' | grep -E '\.class$' || true)"
    echo "-- classes referring to $SCAN_USERS_SYMBOL"
    for class in $(printf '%s\n' "$all_entries" | grep -iE "$SCAN_USERS_PATH" | sed 's/\.class$//; s#/#.#g' | head -400); do
        if "$javap" -v -cp "$SDK_DIR/main.jar" "$class" 2>/dev/null | grep -qE "$SCAN_USERS_SYMBOL"; then
            echo "== $class"
            "$javap" -p -cp "$SDK_DIR/main.jar" "$class" 2>&1 | head -60 || true
            "$javap" -c -p -cp "$SDK_DIR/main.jar" "$class" 2>&1 | grep -oE '// String .*' | sort -u | head -40 || true
            if [ -n "${SCAN_USERS_CODE:-}" ]; then
                echo "   EGL attribute constants (sipush 12320-12354) and calls, with the method they are in:"
                "$javap" -c -p -cp "$SDK_DIR/main.jar" "$class" 2>&1 \
                    | awk '/^  [a-zA-Z].*\(.*\);$/ { method = $0 }
                           /sipush +123[2-5][0-9]|EGL|ConfigChooser|chooseConfig|getConfigAttrib/ { print "     " method " :: " $0 }' \
                    | head -120 || true
            fi
        fi
    done
    for class in ${SCAN_CODE_CLASSES:-}; do
        echo "-- javap -c $class (methods matching: $SCAN_CODE_METHODS)"
        "$javap" -c -p -cp "$SDK_DIR/main.jar" "$class" 2>&1 \
            | awk -v pat="$SCAN_CODE_METHODS" '
                /^  [a-zA-Z].*\(.*\);$/ { on = ($0 ~ ("[ .](" pat ")\\(")); n = 0 }
                on && n < 260 { print; n++ }' | head -900 || true
    done
    if [ -n "${SCAN_STRING_USERS:-}" ]; then
        echo "-- classes using the string \"$SCAN_STRING_USERS\", with the methods that use it"
        printf '%s\n' "$all_entries" | while read -r entry; do
            unzip -p "$SDK_DIR/main.jar" "$entry" 2>/dev/null | grep -a -q -F "$SCAN_STRING_USERS" || continue
            class="$(printf '%s' "$entry" | sed 's/\.class$//; s#/#.#g')"
            echo "== $class"
            "$javap" -c -p -cp "$SDK_DIR/main.jar" "$class" 2>&1 | awk -v s="$SCAN_STRING_USERS" '
                /^  [a-zA-Z].*\(.*\);$/ { if (hit) printf "%s", body; body = ""; hit = 0; n = 0 }
                { if (n < 200) { body = body $0 "\n"; n++ } }
                index($0, s) { hit = 1 }
                END { if (hit) printf "%s", body }' | head -300 || true
        done || true  # head closing early is not a failure (pipefail)
    fi
    for class in $(printf '%s\n' "$all_entries" | grep -E "$SCAN_STRINGS_PATTERN" | sed 's/\.class$//; s#/#.#g' | head -10); do
        echo "-- string constants in $class"
        "$javap" -c -p -cp "$SDK_DIR/main.jar" "$class" 2>&1 | grep -oE '// String .*' | sort -u | head -80 || true
    done
}

# --- Scenario runner -----------------------------------------------------------
# Lines: load <assetId> [name] | remove <assetId> | removeAll | basemap <percent>
#        fly <lat> <lon> <range-metres> [tilt [jump|animate]]
#        select <imagery layer name>
#        camera [label] (logs where the map is looking)
#        pane [open|close] (the Cesium ion pane, as the toolbar button)
#        signIn (opens ion's sign-in page in the browser; never signs out)
#        loadFirst <TILES_3D|IMAGERY|TERRAIN|GEOJSON|KML> (first such asset on the server)
#        server cloud | server <server-url> [api-url] (single-user Self-Hosted; never a token)
#        state (resumed/focused activity, screen power) | mark <name> (logcat marker)
#        wait <seconds> | screenshot <name>
#        tap <button text> (a dialog left on screen) | key <BACK|HOME|...>
#        settings [open|close] (the pane's settings panel, as the gear button)
#        workspace first|personal (the account's first team workspace, or personal)
#        atak (brings ATAK to the front, e.g. after signing in in the browser)
#        longpress <text> (first view containing the text, e.g. the status pill's tooltip)
#        scroll [down|up] (the pane on the right)
#        expect <extended regex> (a plugin log line the run must contain; checked in the report)
#        include <scenario name> (its steps, e.g. "include regression" in a feature scenario)
#        note <text> | # comment
SCENARIO_LOG=""
# "expect <extended regex>" lines: plugin log lines the run must contain (regression checks).
EXPECT_FILE=""
# Which activity Android has resumed and focused, and whether the screen is
# awake: ATAK's map only draws while its activity is resumed.
window_state() {
    {
        "$ADB" shell dumpsys activity activities </dev/null 2>/dev/null \
            | grep -E "topResumedActivity|mResumedActivity|ResumedActivity:" | head -3
        "$ADB" shell dumpsys window </dev/null 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -2
        "$ADB" shell dumpsys power </dev/null 2>/dev/null | grep -E "mWakefulness=|mHoldingDisplaySuspendBlocker" | head -2
    } | sed 's/^ */        /' | tee -a "$SCENARIO_LOG" || true
}
test_command() {
    # </dev/null: adb would otherwise consume the rest of the scenario file.
    "$ADB" shell am broadcast -a com.cesium.atak.ion.TEST -p "$ATAK_PACKAGE" --es step "$STEP" "$@" \
        </dev/null >/dev/null 2>&1 || echo "        adb broadcast failed" | tee -a "$SCENARIO_LOG"
}
# If ATAK shows its "load plugins?" dialog after an unclean exit, tap the
# button that loads them. Uses the UI hierarchy; only taps inside a screen
# that mentions plugins. Saves the dump next to the report.
# No tablet attached: boot an Android phone/tablet emulator headless and
# wait for it. AVD_NAME picks one; otherwise the first AVD that is a plain
# Android device (not the PICO XR emulator, Wear OS, TV or Automotive),
# preferring the Mac's own CPU architecture. With none, it creates
# "ATAK_Tablet" (Android 14, Google APIs) when the SDK's cmdline-tools are
# installed. The emulator keeps running after the test so the next run starts
# faster; stop it with: adb -s <serial> emu kill
NON_TABLET_AVD='pico|xr|wear|android-tv|_tv|tv_|automotive'
SDK_ROOT_GUESS="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# ATAK's renderer needs an EGL config the software renderer
# (swiftshader_indirect) lacks ("No config chosen" on its GL thread), so the
# emulator uses the Mac's GPU; EMULATOR_GPU overrides (e.g. swangle_indirect).
EMULATOR_GPU="${EMULATOR_GPU:-host}"
# Tried in turn when ATAK cannot get a GL config under the current mode.
# On the Android 14 tablet AVD none helped (host, swangle_indirect,
# angle_indirect and guest all gave "No config chosen"), so none by default.
EMULATOR_GPU_FALLBACKS="${EMULATOR_GPU_FALLBACKS:-}"
BOOTED_EMULATOR=""   # AVD this run booted, if any
EMULATOR_HEADLESS="-no-window -no-audio -no-boot-anim -no-snapshot-save -gpu"
EMULATOR_FLAGS="$EMULATOR_HEADLESS $EMULATOR_GPU"

avd_config() {
    local ini="$HOME/.android/avd/$1.ini" dir
    dir="$(sed -n 's/^path=//p' "$ini" 2>/dev/null | head -1)"
    [ -n "$dir" ] || dir="$HOME/.android/avd/$1.avd"
    printf '%s/config.ini' "$dir"
}

# True when the AVD is an ordinary Android phone/tablet image.
avd_is_android() {
    printf '%s' "$1" | grep -qiE "$NON_TABLET_AVD" && return 1
    grep -iE '^(tag\.id|tag\.ids|image\.sysdir\.1|hw\.device\.name|hw\.device\.manufacturer|avd\.ini\.displayname)=' \
        "$(avd_config "$1")" 2>/dev/null | grep -qiE "$NON_TABLET_AVD" && return 1
    return 0
}

host_abi() {
    case "$(uname -m)" in arm64|aarch64) echo arm64-v8a ;; *) echo x86_64 ;; esac
}

pick_avd() {
    local emulator="$1" avd fallback="" abi
    abi="$(host_abi)"
    for avd in $("$emulator" -list-avds 2>/dev/null </dev/null | tr -d '\r' | grep -E '^[A-Za-z0-9._-]+$' || true); do
        avd_is_android "$avd" || { echo "Skipping AVD \"$avd\" (not an Android phone/tablet)." >&2; continue; }
        if grep -q "^abi.type=$abi" "$(avd_config "$avd")" 2>/dev/null; then
            echo "$avd"; return 0
        fi
        [ -n "$fallback" ] || fallback="$avd"
    done
    if [ -n "$fallback" ]; then
        echo "$fallback"
    fi
}

create_tablet_avd() {
    local tools="" candidate image
    for candidate in "$SDK_ROOT_GUESS"/cmdline-tools/latest/bin "$SDK_ROOT_GUESS"/cmdline-tools/*/bin; do
        [ -x "$candidate/avdmanager" ] && tools="$candidate" && break
    done
    [ -n "$tools" ] || { echo "No Android phone/tablet AVD, and no cmdline-tools to create one." >&2; return 1; }
    image="system-images;android-34;google_apis;$(host_abi)"
    echo "Creating AVD ATAK_Tablet ($image); the first download takes a few minutes." >&2
    "$tools/sdkmanager" --sdk_root="$SDK_ROOT_GUESS" "platforms;android-34" "$image" </dev/null >&2 \
        || { echo "sdkmanager could not install $image (licenses not accepted? run: sdkmanager --licenses)." >&2; return 1; }
    echo no | "$tools/avdmanager" create avd -n ATAK_Tablet -k "$image" -d pixel_tablet >&2 \
        || "$tools/avdmanager" create avd -n ATAK_Tablet -k "$image" </dev/null >&2 || return 1
    echo ATAK_Tablet
}

# Serials of attached devices, leaving out emulators that are not Android
# phones/tablets (a PICO XR emulator left running, for example).
usable_devices() {
    local serial name
    for serial in $("$ADB" devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}'); do
        case "$serial" in
            emulator-*)
                name="$("$ADB" -s "$serial" emu avd name 2>/dev/null </dev/null | head -1 | tr -d '\r' || true)"
                if [ -n "$name" ] && ! avd_is_android "$name"; then
                    echo "Ignoring $serial (\"$name\" is not an Android phone/tablet emulator)." >&2
                    continue
                fi ;;
        esac
        echo "$serial"
    done
}

# Stops emulators that an earlier run of this script booted (recognisable by
# its headless flags) when they are not Android phones/tablets or use another
# GPU mode than EMULATOR_GPU; emulators opened by hand stay.
emulator_command() {  # the command line of the emulator running AVD $1, if this script booted it (else empty)
    ps -ax -o command= 2>/dev/null | grep -F -- "-avd $1 " | grep -F -- "-no-snapshot-save" | grep -v -F "grep" | head -1 || true
}

stop_wrong_emulators() {
    local serial name stopped=0 command
    for serial in $("$ADB" devices | tr -d '\r' | awk 'NR>1 && $1 ~ /^emulator-/ {print $1}'); do
        name="$("$ADB" -s "$serial" emu avd name 2>/dev/null </dev/null | head -1 | tr -d '\r' || true)"
        [ -n "$name" ] || continue
        command="$(emulator_command "$name")"
        [ -n "$command" ] || continue
        if ! avd_is_android "$name"; then
            echo "Stopping \"$name\" ($serial), which an earlier test run booted by mistake."
        elif ! printf '%s ' "$command" | grep -q -F -- "-gpu $EMULATOR_GPU "; then
            echo "Restarting \"$name\" ($serial) with -gpu $EMULATOR_GPU (an earlier run booted it with another GPU mode)."
        else
            continue
        fi
        "$ADB" -s "$serial" emu kill </dev/null >/dev/null 2>&1 || true
        stopped=1
    done
    if [ "$stopped" -eq 1 ]; then
        sleep 8  # let adb drop the stopped emulators
    fi
}

start_emulator() {
    local emulator="$SDK_ROOT_GUESS/emulator/emulator" avd before serial=""
    [ -x "$emulator" ] || [ ! -x "$emulator.exe" ] || emulator="$emulator.exe"  # Windows
    [ -x "$emulator" ] || { echo "No Android emulator in $SDK_ROOT_GUESS." >&2; return 1; }
    stop_wrong_emulators
    avd="${AVD_NAME:-$(pick_avd "$emulator")}"
    [ -n "$avd" ] || avd="$(create_tablet_avd)" || return 1
    [ -n "$avd" ] || return 1
    before="$("$ADB" devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')"
    echo "No device attached: booting the Android emulator \"$avd\" (headless)."
    # shellcheck disable=SC2086
    if command -v nohup >/dev/null 2>&1; then
        nohup "$emulator" -avd "$avd" $EMULATOR_FLAGS >"$REPORT_DIR/$STAMP-emulator.log" 2>&1 </dev/null &
    else
        "$emulator" -avd "$avd" $EMULATOR_FLAGS >"$REPORT_DIR/$STAMP-emulator.log" 2>&1 </dev/null &
    fi
    local waited=0
    while :; do
        if [ -z "$serial" ]; then
            serial="$("$ADB" devices | tr -d '\r' | awk 'NR>1 && $1 ~ /^emulator-/ {print $1}' \
                | grep -vxF "$before" | head -1 || true)"
        fi
        if [ -n "$serial" ] && [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null </dev/null \
                | tr -d '\r')" = "1" ]; then
            break
        fi
        [ "$waited" -lt 300 ] || { echo "The emulator did not boot within 300 s (log: $REPORT_DIR/$STAMP-emulator.log)."; return 1; }
        sleep 5; waited=$((waited + 5))
    done
    "$ADB" -s "$serial" shell input keyevent KEYCODE_WAKEUP </dev/null >/dev/null 2>&1 || true
    "$ADB" -s "$serial" shell wm dismiss-keyguard </dev/null >/dev/null 2>&1 || true
    echo "Emulator $serial (\"$avd\", -gpu $EMULATOR_GPU) booted after ${waited}s."
    BOOTED_EMULATOR="$avd"
    EMULATOR_SERIAL="$serial"
}

# Taps through ATAK's first-run screens on a fresh device (EULA, permission
# prompts, setup wizards): the first button whose label is in the list.
# Scenario step: taps the first on-screen element whose text is exactly
# $* (case-insensitive), e.g. "Cancel" on a dialog left over from earlier.
# On an emulator (where adbd can run as root), prints the main thread's stack
# from Android's newest ANR trace, and the stack of the thread holding the
# lock main waits on, so a report says what blocked ATAK's UI thread.
anr_main_stack() {
    case "$ANDROID_SERIAL" in emulator-*) ;; *) return 0 ;; esac
    grep -qE 'ANR in com\.atakmap' "$RAW" || return 0
    "$ADB" root </dev/null >/dev/null 2>&1 || return 0
    sleep 3
    "$ADB" wait-for-device </dev/null >/dev/null 2>&1 || return 0
    local name trace holder
    name="$("$ADB" shell ls -t /data/anr </dev/null 2>/dev/null | tr -d '\r' | head -1)"
    [ -n "$name" ] || { echo "(no ANR trace file readable)"; return 0; }
    trace="$(mktemp)"
    "$ADB" exec-out cat "/data/anr/$name" </dev/null >"$trace" 2>/dev/null || true
    echo "Trace: /data/anr/$name"
    awk '/^"main"/{p=1} p&&/^$/{exit} p' "$trace" | head -70
    holder="$(awk '/^"main"/{p=1} p&&/^$/{exit} p' "$trace" | grep -oE 'held by thread [0-9]+' | head -1 | grep -oE '[0-9]+$' || true)"
    if [ -n "$holder" ]; then
        echo "-- thread $holder (holds the lock main waits for)"
        awk -v t="tid=$holder " 'index($0, t) && /^"/{p=1} p&&/^$/{exit} p' "$trace" | head -70
    fi
    rm -f "$trace"
}

# A fresh ATAK loads a plugin only after someone loads it once in Plugin
# Manager, which ATAK records as the preference "shouldLoad-<package>"
# (AtakPluginRegistry.SHOULD_LOAD). On an emulator (adbd can run as root) set
# it directly, rewriting the preferences file in place so it keeps ATAK's
# ownership. Needs ATAK to have run once, so the file exists.
enable_plugin_on_emulator() {
    local prefs="/data/data/$ATAK_PACKAGE/shared_prefs/${ATAK_PACKAGE}_preferences.xml"
    local key="shouldLoad-$PLUGIN_PACKAGE"
    "$ADB" root </dev/null >/dev/null 2>&1 || return 0
    sleep 3
    "$ADB" wait-for-device </dev/null >/dev/null 2>&1 || return 0
    if ! "$ADB" shell "test -f $prefs" </dev/null >/dev/null 2>&1; then
        echo "Emulator: ATAK has no preferences yet; Plugin Manager may need the plugin loaded once."
        return 0
    fi
    if "$ADB" shell "grep -q '\"$key\" value=\"true\"' $prefs" </dev/null >/dev/null 2>&1; then
        echo "Emulator: ATAK already loads the plugin at startup."
        return 0
    fi
    "$ADB" shell am force-stop "$ATAK_PACKAGE" </dev/null >/dev/null 2>&1 || true
    "$ADB" shell "sed -e '/\"$key\"/d' -e 's#</map>#<boolean name=\"$key\" value=\"true\" /></map>#' $prefs > /data/local/tmp/atak-prefs.xml && cat /data/local/tmp/atak-prefs.xml > $prefs && rm /data/local/tmp/atak-prefs.xml" \
        </dev/null >/dev/null 2>&1 || true
    if "$ADB" shell "grep -q '\"$key\" value=\"true\"' $prefs" </dev/null >/dev/null 2>&1; then
        echo "Emulator: set $key so ATAK loads the plugin at startup."
    else
        echo "Emulator: could not set $key; load the plugin once in Plugin Manager."
    fi
}

# Swipes in the right-hand pane (where ATAK docks the plugin's pane) to
# scroll it: "down" shows what is further down.
scroll_pane() {
    local size w h x top bottom
    size="$("$ADB" shell wm size </dev/null 2>/dev/null | tr -d '\r' | sed -n 's/.*: *\([0-9]*x[0-9]*\).*/\1/p' | tail -1)"
    w="${size%x*}"; h="${size#*x}"
    [ -n "$w" ] && [ -n "$h" ] || return 0
    if [ "$w" -lt "$h" ]; then local t="$w"; w="$h"; h="$t"; fi  # landscape tablet
    x=$((w * 3 / 4)); top=$((h * 30 / 100)); bottom=$((h * 80 / 100))
    if [ "$1" = "up" ]; then
        "$ADB" shell input swipe "$x" "$top" "$x" "$bottom" 400 </dev/null >/dev/null 2>&1 || true
    else
        "$ADB" shell input swipe "$x" "$bottom" "$x" "$top" 400 </dev/null >/dev/null 2>&1 || true
    fi
    sleep 1
}

tap_text() {
    local label="$*" dump="$REPORT_DIR/$STAMP-ui.xml" target
    "$ADB" shell uiautomator dump /sdcard/cesium-ion-ui.xml </dev/null >/dev/null 2>&1 || return 0
    "$ADB" exec-out cat /sdcard/cesium-ion-ui.xml </dev/null >"$dump" 2>/dev/null || return 0
    target="$(LABEL="$label" perl -ne 'while (/<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g) {
            if (lc($1) eq lc($ENV{LABEL})) { printf "%d %d\n", ($2+$4)/2, ($3+$5)/2; exit } }' "$dump")"
    if [ -n "$target" ]; then
        echo "        tapping \"$label\" at $target" | tee -a "$SCENARIO_LOG"
        # shellcheck disable=SC2086
        "$ADB" shell input tap $target </dev/null >/dev/null 2>&1 || true
    else
        echo "        no \"$label\" on screen" | tee -a "$SCENARIO_LOG"
    fi
}

# Long-presses the first view whose text contains the label (e.g. the status
# pill, to show its tooltip on a touch screen).
long_press_text() {
    local label="$*" dump="$REPORT_DIR/$STAMP-ui.xml" target
    "$ADB" shell uiautomator dump /sdcard/cesium-ion-ui.xml </dev/null >/dev/null 2>&1 || return 0
    "$ADB" exec-out cat /sdcard/cesium-ion-ui.xml </dev/null >"$dump" 2>/dev/null || return 0
    target="$(LABEL="$label" perl -ne 'while (/<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g) {
            if (index(lc($1), lc($ENV{LABEL})) >= 0) { printf "%d %d\n", ($2+$4)/2, ($3+$5)/2; exit } }' "$dump")"
    if [ -n "$target" ]; then
        echo "        long-pressing \"$label\" at $target" | tee -a "$SCENARIO_LOG"
        # shellcheck disable=SC2086
        "$ADB" shell input swipe $target $target 1200 </dev/null >/dev/null 2>&1 || true
    else
        echo "        no \"$label\" on screen" | tee -a "$SCENARIO_LOG"
    fi
}

# Taps the first view whose content description contains the label (icon
# buttons without text, such as ATAK's menu button).
tap_desc() {
    local label="$*" dump="$REPORT_DIR/$STAMP-ui.xml" target
    "$ADB" shell uiautomator dump /sdcard/cesium-ion-ui.xml </dev/null >/dev/null 2>&1 || return 0
    "$ADB" exec-out cat /sdcard/cesium-ion-ui.xml </dev/null >"$dump" 2>/dev/null || return 0
    target="$(LABEL="$label" perl -ne 'while (/<node [^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g) {
            if ($1 ne "" && index(lc($1), lc($ENV{LABEL})) >= 0) { printf "%d %d\n", ($2+$4)/2, ($3+$5)/2; exit } }' "$dump")"
    if [ -n "$target" ]; then
        echo "        tapping \"$label\" (description) at $target" | tee -a "$SCENARIO_LOG"
        # shellcheck disable=SC2086
        "$ADB" shell input tap $target </dev/null >/dev/null 2>&1 || true
    else
        echo "        no view described \"$label\"" | tee -a "$SCENARIO_LOG"
    fi
}

# pm_dump_ui, pm_find_text, load_in_package_mgmt
. "$ATAK_DIR/scripts/package-mgmt.sh"

# Lists what is on screen (text, description, bounds) in the scenario log, to
# find views for tap/tapdesc/tapxy.
dump_ui() {
    local dump="$REPORT_DIR/$STAMP-ui.xml"
    "$ADB" shell uiautomator dump /sdcard/cesium-ion-ui.xml </dev/null >/dev/null 2>&1 || return 0
    "$ADB" exec-out cat /sdcard/cesium-ion-ui.xml </dev/null >"$dump" 2>/dev/null || return 0
    perl -ne 'while (/<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="(\[\d+,\d+\]\[\d+,\d+\])"/g) {
            print "        ui: \"$1\" desc \"$2\" $3\n" if ($1 ne "" || $2 ne "") }' "$dump" | head -90 | tee -a "$SCENARIO_LOG"
}

tap_first_run() {
    local dump="$REPORT_DIR/$STAMP-ui.xml"
    "$ADB" shell uiautomator dump /sdcard/cesium-ion-ui.xml </dev/null >/dev/null 2>&1 || return 0
    "$ADB" exec-out cat /sdcard/cesium-ion-ui.xml </dev/null >"$dump" 2>/dev/null || return 0
    local target
    target="$(perl -ne 'while (/<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g) {
            my ($t,$x1,$y1,$x2,$y2)=($1,$2,$3,$4,$5);
            if ($t =~ /^\s*(i agree|agree|accept|allow|allow all the time|while using the app|only this time|continue|next|done|ok|skip|dismiss|close|got it|i understand)\s*[.!]?\s*$/i) {
                printf "%d %d %s\n", ($x1+$x2)/2, ($y1+$y2)/2, $t; exit }
        }' "$dump")"
    if [ -n "$target" ]; then
        set -- $target
        echo "Tapping \"${*:3}\" (first-run screen)." | tee -a "$SCENARIO_LOG"
        "$ADB" shell input tap "$1" "$2" </dev/null >/dev/null 2>&1 || true
    fi
}

tap_plugin_dialog() {
    local dump="$REPORT_DIR/$STAMP-ui.xml"
    "$ADB" shell uiautomator dump /sdcard/cesium-ion-ui.xml </dev/null >/dev/null 2>&1 || return 0
    "$ADB" exec-out cat /sdcard/cesium-ion-ui.xml </dev/null >"$dump" 2>/dev/null || return 0
    "$ADB" shell rm -f /sdcard/cesium-ion-ui.xml </dev/null >/dev/null 2>&1 || true
    grep -qi 'plugin' "$dump" || return 0
    local target
    target="$(perl -ne 'while (/<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g) {
            my ($t,$x1,$y1,$x2,$y2)=($1,$2,$3,$4,$5);
            if ($t =~ /^(load|load plugins|reload|reload plugins|yes|ok)$/i) { printf "%d %d %s\n", ($x1+$x2)/2, ($y1+$y2)/2, $t; exit }
        }' "$dump")"
    if [ -n "$target" ]; then
        set -- $target
        # For the install guide: what a user sees when ATAK finds the plugin.
        "$ADB" exec-out screencap -p >"$REPORT_DIR/$STAMP-plugin-dialog.png" 2>/dev/null </dev/null || true
        echo "Tapping \"${*:3}\" in ATAK's plugin dialog." | tee -a "$SCENARIO_LOG"
        "$ADB" shell input tap "$1" "$2" </dev/null >/dev/null 2>&1 || true
    fi
}
# ATAK's map barely draws (under 1 frame/s) until every 3D model layer it
# restores at startup has loaded or failed; one on an unreachable host
# holds it for minutes. Steps sent before then land on a map that is not
# drawing, so wait for ATAK's own framerate log to show it drawing.
MAP_READY_FPS=5
wait_for_map() {
    local waited=0 fps=""
    while [ "$waited" -lt 300 ]; do
        # The last three readings (about 3 s): a single early reading can
        # come just before a stall.
        fps="$(grep -E 'GLMapRenderer.*map framerate' "$RAW" 2>/dev/null | tail -3 \
            | sed -n 's/.*= *\([0-9.]*\).*/\1/p' | tr '\n' ' ' || true)"
        if [ -n "$fps" ] && printf '%s\n' $fps | awk -v min="$MAP_READY_FPS" \
            '{ n++; if ($1 < min) low = 1 } END { exit !(n == 3 && !low) }'; then
            echo "Map drawing at ${fps} frames/s after waiting ${waited}s." | tee -a "$SCENARIO_LOG"
            return 0
        fi
        sleep 2; waited=$((waited + 2))
    done
    echo "Map still not drawing after ${waited}s (last framerate: ${fps:-none}); running the steps anyway." \
        | tee -a "$SCENARIO_LOG"
}

# Starts ATAK's launcher activity and reports what Android answered (a
# fresh emulator install can refuse or crash silently otherwise).
launch_atak() {
    local activity
    activity="$("$ADB" shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$ATAK_PACKAGE" \
        </dev/null 2>/dev/null | tr -d '\r' | tail -1)"
    case "$activity" in
        */*) "$ADB" shell am start -W -n "$activity" </dev/null 2>&1 | grep -E 'Status|Activity|Error|Warning' \
                 | sed 's/^/        /' | tee -a "${SCENARIO_LOG:-/dev/null}" || true ;;
        *) echo "        No launcher activity for $ATAK_PACKAGE ($activity); trying monkey." | tee -a "${SCENARIO_LOG:-/dev/null}"
           "$ADB" shell monkey -p "$ATAK_PACKAGE" -c android.intent.category.LAUNCHER 1 </dev/null >/dev/null 2>&1 || true ;;
    esac
}

atak_running() {
    "$ADB" shell pidof "$ATAK_PACKAGE" </dev/null >/dev/null 2>&1
}

run_scenario() {
    EXPECT_FILE="$REPORT_DIR/$STAMP-expect.txt"
    : >"$EXPECT_FILE"
    SCENARIO_LOG="$REPORT_DIR/$STAMP-scenario.txt"
    : >"$SCENARIO_LOG"
    "$ADB" shell input keyevent KEYCODE_WAKEUP </dev/null >/dev/null 2>&1 || true
    echo "Scenario $(basename "$SCENARIO"): waiting for the plugin..."
    local waited=0 prompted=0
    until grep -q "Test commands enabled" "$RAW" 2>/dev/null; do
        if [ "$waited" -eq 20 ] || [ "$waited" -eq 30 ]; then
            tap_plugin_dialog
        fi
        if [ "$waited" -ge 10 ] && [ $((waited % 6)) -eq 0 ]; then
            tap_first_run
        fi
        if [ "$waited" -ge 20 ] && [ $((waited % 30)) -eq 0 ] && ! atak_running; then
            echo "ATAK is not running after ${waited}s; launching it again." | tee -a "$SCENARIO_LOG"
            window_state
            launch_atak
        fi
        if [ "$waited" -ge 40 ] && [ "$prompted" -eq 0 ]; then
            prompted=1
            # ATAK 5.7 does not auto-load a plugin after it is reinstalled.
            "$ADB" exec-out screencap -p >"$REPORT_DIR/$STAMP-plugin-not-loaded.png" 2>/dev/null </dev/null || true
            # On the tablet (its menu button position is known), load it in Package Mgmt.
            case "${ANDROID_SERIAL:-}" in
                emulator-*) ;;
                *) { load_in_package_mgmt 2>&1 || true; } | tee -a "$SCENARIO_LOG" || true ;;
            esac
            printf '\n>>> ATAK did not load the plugin at startup. On the tablet: Settings >\n'
            printf '>>> Plugins (Plugin Manager) > load "Cesium ion". Waiting up to 5 more minutes...\n\n'
            "$ADB" shell input keyevent KEYCODE_WAKEUP </dev/null >/dev/null 2>&1 || true
        fi
        if [ "$waited" -ge 340 ]; then
            echo "The plugin did not start within ${waited}s; scenario skipped." | tee -a "$SCENARIO_LOG"
            echo "ATAK process: $("$ADB" shell pidof "$ATAK_PACKAGE" </dev/null 2>/dev/null | tr -d '\r' || true)" | tee -a "$SCENARIO_LOG"
            window_state
            return 0
        fi
        sleep 2; waited=$((waited + 2))
    done
    if [ "$prompted" -eq 1 ]; then
        echo "Plugin loaded by hand after ${waited}s." | tee -a "$SCENARIO_LOG"
    fi
    sleep 8  # let ATAK finish starting up
    wait_for_map
    STEP=0
    # "include <name>" lines pull in scenarios/<name>.txt (e.g. the regression checks).
    local expanded="$REPORT_DIR/$STAMP-steps.txt"
    awk -v dir="$(dirname "$SCENARIO")" '
        /^[[:space:]]*include[[:space:]]+/ { file = dir "/" $2 ".txt"
            while ((getline inc < file) > 0) print inc; close(file); next }
        { print }' "$SCENARIO" >"$expanded"
    local line command
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%%#*}"
        line="$(printf '%s' "$line" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
        [ -n "$line" ] || continue
        STEP=$((STEP + 1))
        printf '+%3ss  step %s: %s\n' "$((SECONDS - CAPTURE_START))" "$STEP" "$line" | tee -a "$SCENARIO_LOG"
        set -- $line
        command="$1"; shift
        case "$command" in
            load)
                local name; name="$(printf '%s' "$*" | cut -d' ' -f2- | tr -d "'\"")"
                [ $# -ge 2 ] || name=""
                if [ -n "$name" ]; then test_command --es cmd load --el asset "$1" --es name "'$name'"
                else test_command --es cmd load --el asset "$1"; fi ;;
            remove) test_command --es cmd remove --el asset "$1" ;;
            removeAll) test_command --es cmd removeAll ;;
            basemap) test_command --es cmd basemap --ei percent "$1" ;;
            select) test_command --es cmd select --es layer "'$(printf '%s' "$*" | tr -d "'\"")'" ;;
            fly) test_command --es cmd fly --es lat "$1" --es lon "$2" --es range "$3" --es tilt "${4:-0}" \
                    --es motion "${5:-animate}" ;;
            loadFirst) test_command --es cmd loadFirst --es type "$1" ;;
            server) if [ "${1:-cloud}" = "cloud" ]; then test_command --es cmd server --es url cloud
                    else test_command --es cmd server --es url "$1" --es api "${2:-}"; fi ;;
            pane) test_command --es cmd pane --es state "${1:-open}" ;;
            signIn) test_command --es cmd signIn ;;
            settings) test_command --es cmd settings --es state "${1:-open}" ;;
            workspace) test_command --es cmd workspace --es which "${1:-personal}" ;;
            atak) launch_atak ;;
            camera) test_command --es cmd camera --es label "'$(printf '%s' "$*" | tr -d "'\"")'" ;;
            wait) sleep "$1" ;;
            screenshot)
                local shot="$REPORT_DIR/$STAMP-$1.png"
                "$ADB" exec-out screencap -p >"$shot" 2>/dev/null </dev/null && [ -s "$shot" ] \
                    && echo "        saved $(basename "$shot")" | tee -a "$SCENARIO_LOG" || rm -f "$shot" ;;
            state) window_state ;;
            mark) "$ADB" shell log -t CesiumTest "mark-$1" </dev/null >/dev/null 2>&1 || true ;;
            tap) tap_text "$@" ;;
            tapdesc) tap_desc "$@" ;;
            tapxy) echo "        tapping at $1 $2" | tee -a "$SCENARIO_LOG"
                   "$ADB" shell input tap "$1" "$2" </dev/null >/dev/null 2>&1 || true ;;
            dumpui) dump_ui ;;
            longpress) long_press_text "$@" ;;
            scroll) scroll_pane "${1:-down}" ;;
            key) "$ADB" shell input keyevent "KEYCODE_${1#KEYCODE_}" </dev/null >/dev/null 2>&1 || true ;;
            note) : ;;
            expect) printf '%s\n' "$*" >>"$EXPECT_FILE" ;;
            *) echo "        unknown step, skipped" | tee -a "$SCENARIO_LOG" ;;
        esac
    done <"$expanded"
    sleep 3  # let the last step's log lines arrive
}

# --- Tools -------------------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
# A fresh checkout (e.g. a CI runner) has no local.properties; Gradle then reads ANDROID_HOME.
if [ -z "${ANDROID_HOME:-}" ] && [ ! -f "$ATAK_DIR/local.properties" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
# Builds the plugin and runs its unit tests; sets APK and SDK_DIR, or writes
# a failure report and stops.
build_plugin() {
    step "Build and unit tests (log: $BUILD_LOG)"
    local ok=1
    ./gradlew --console=plain --warning-mode none testCivDebugUnitTest assembleCivDebug >"$BUILD_LOG" 2>&1 || ok=0
    SDK_DIR="$(sed -n 's/^ATAK SDK: \(.*\) (from .*)$/\1/p' "$BUILD_LOG" | head -1)"
    grep -E '^ATAK SDK:' "$BUILD_LOG" || true
    if [ "$ok" -eq 0 ]; then
        {
            echo "Cesium ion ATAK build failure $STAMP"
            echo "Plugin commit $(git -C "$ATAK_DIR" rev-parse --short HEAD 2>/dev/null || echo '?')"
            echo "---- build errors ----"
            grep -E 'error:|warning: \[|FAILED|What went wrong|> ' "$BUILD_LOG" | grep -vE 'Task :|^> Configure' | head -60
            sdk_api_scan
        } | redact >"$REPORT"
        cat "$REPORT"
        fail "Build failed. Paste the report above ($REPORT); the full log is $BUILD_LOG."
    fi
    APK="$(ls -t app/build/outputs/apk/civ/debug/*.apk 2>/dev/null | head -1)"
    [ -n "$APK" ] || fail "No APK was produced."
    echo "APK: $APK"
}

if [ -n "$BUILD_ONLY" ]; then
    build_plugin
    atak_apk="$(ls "$SDK_DIR"/atak.apk "$SDK_DIR"/*/atak.apk 2>/dev/null | head -1 || true)"
    [ -n "$atak_apk" ] || fail "The SDK ($SDK_DIR) has no atak.apk."
    mkdir -p "$BUILD_ONLY"
    cp "$APK" "$atak_apk" "$BUILD_ONLY/"
    unzip -l "$atak_apk" | awk '{print $4}' | grep -oE '^lib/[^/]+/' | sort -u | sed 's/^/ATAK native ABIs: /' || true
    echo "Copied to $BUILD_ONLY:"; ls -la "$BUILD_ONLY"
    exit 0
fi
# Windows (Git Bash): the SDK tools are .exe files.
if [ -z "$(command -v adb || true)" ] && [ -x "${ANDROID_HOME:-}/platform-tools/adb.exe" ]; then
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
fi
ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
    for candidate in "${ANDROID_HOME:-}/platform-tools/adb" "$HOME/Library/Android/sdk/platform-tools/adb"; do
        [ -x "$candidate" ] && ADB="$candidate" && break
    done
fi
[ -n "$ADB" ] || fail "adb not found. Install Android SDK platform-tools or add them to PATH."

# --- Device ------------------------------------------------------------------
step "Device"
if [ -z "${ANDROID_SERIAL:-}" ]; then
    stop_wrong_emulators
    DEVICES="$(usable_devices)"
    COUNT="$(printf '%s\n' "$DEVICES" | grep -c . || true)"
    if [ "$COUNT" -eq 0 ] && start_emulator; then
        DEVICES="$(usable_devices)"
        COUNT="$(printf '%s\n' "$DEVICES" | grep -c . || true)"
    fi
    if [ "$COUNT" -eq 0 ]; then
        "$ADB" devices -l
        fail "No authorized device and no emulator to boot. Unlock the tablet and accept the USB debugging prompt."
    elif [ "$COUNT" -gt 1 ]; then
        # A physical tablet and an emulator: test on the tablet (the real device).
        PHYSICAL="$(printf '%s\n' "$DEVICES" | grep -v '^emulator-' || true)"
        if [ "$(printf '%s\n' "$PHYSICAL" | grep -c . || true)" -eq 1 ]; then
            echo "Several devices attached; using the physical device $PHYSICAL."
            DEVICES="$PHYSICAL"
        else
            "$ADB" devices -l
            fail "Several devices attached. Rerun with ANDROID_SERIAL=<serial> $0"
        fi
    fi
    export ANDROID_SERIAL="$DEVICES"
fi
MODEL="$("$ADB" shell getprop ro.product.model | tr -d '\r')"
ANDROID_VERSION="$("$ADB" shell getprop ro.build.version.release | tr -d '\r')"
ABI="$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "$ANDROID_SERIAL: $MODEL, Android $ANDROID_VERSION, $ABI"
# Keep the test machines awake between runs (owner's request): the Mac for 4 hours
# from each run (caffeinate, detached so the runner leaves it running), and a
# physical tablet's screen on while it is powered over USB, so it never sleeps or
# locks. Undo on the tablet: adb shell svc power stayon false
if command -v caffeinate >/dev/null 2>&1; then
    RUNNER_TRACKING_ID="" nohup caffeinate -dimsu -t 14400 >/dev/null 2>&1 &
    echo "Mac kept awake for the next 4 hours (caffeinate)."
fi
case "$ANDROID_SERIAL" in
    emulator-*) ;;
    *) "$ADB" shell svc power stayon usb </dev/null >/dev/null 2>&1 \
           && echo "Tablet screen stays on while it is plugged in (svc power stayon usb)." || true
       "$ADB" shell input keyevent KEYCODE_WAKEUP </dev/null >/dev/null 2>&1 || true ;;
esac
NEED_ATAK=0
"$ADB" shell pm path "$ATAK_PACKAGE" >/dev/null 2>&1 || NEED_ATAK=1

# --- Build and install -------------------------------------------------------
if [ -n "$PREBUILT" ]; then
    APK="$(ls -t "$PREBUILT"/ATAK-Plugin-CesiumIon-*.apk 2>/dev/null | head -1)"
    [ -n "$APK" ] || fail "No plugin APK in $PREBUILT."
    echo "APK (prebuilt): $APK"
else
    build_plugin
fi

# A fresh emulator has no ATAK: install the SDK's developer build (the
# build has just told us where the SDK is).
if [ "$NEED_ATAK" -eq 1 ]; then
    if [ -n "$PREBUILT" ]; then
        ATAK_APK="$(ls "$PREBUILT"/atak.apk 2>/dev/null | head -1 || true)"
    else
        ATAK_APK="$(ls "$SDK_DIR"/atak.apk "$SDK_DIR"/*/atak.apk 2>/dev/null | head -1 || true)"
    fi
    [ -n "$ATAK_APK" ] || fail "$ATAK_PACKAGE is not installed and there is no atak.apk (SDK: ${SDK_DIR:-none}; prebuilt: ${PREBUILT:-none})."
    step "Install ATAK ($ATAK_APK)"
    "$ADB" install -r -g "$ATAK_APK" </dev/null | tail -1
    "$ADB" shell pm path "$ATAK_PACKAGE" >/dev/null 2>&1 || fail "Installing $ATAK_APK failed."
fi
ATAK_VERSION="$("$ADB" shell dumpsys package "$ATAK_PACKAGE" | grep -m1 versionName | tr -d '\r' | sed 's/^ *//')"
echo "ATAK: $ATAK_VERSION"

# ATAK's renderer dies at start ("No config chosen") when the emulator's GPU
# mode offers no EGL config it accepts. On an emulator this run booted, try
# the GPU modes in turn until ATAK keeps running, and record what the GL
# stack reports.
gl_report() {
    echo "GL: egl=$("$ADB" shell getprop ro.hardware.egl </dev/null | tr -d '\r') opengles=$("$ADB" shell getprop ro.opengles.version </dev/null | tr -d '\r') gpu-mode=$EMULATOR_GPU"
    "$ADB" shell dumpsys SurfaceFlinger </dev/null 2>/dev/null | grep -m2 -E 'GLES:|EGL' | sed 's/^/    /' || true
    grep -i -m8 -E 'gpu|gles|egl|renderer|angle|swiftshader|vulkan' "$REPORT_DIR/$STAMP-emulator.log" 2>/dev/null | cut -c1-200 | sed 's/^/    emulator: /' || true
}
atak_gl_ok() {
    "$ADB" logcat -c </dev/null >/dev/null 2>&1 || true
    "$ADB" shell am force-stop "$ATAK_PACKAGE" </dev/null >/dev/null 2>&1 || true
    launch_atak >/dev/null
    sleep 20
    ! "$ADB" logcat -d </dev/null 2>/dev/null | grep -q "No config chosen"
}
# ATAK asks for an exact RGB565 EGL config, which the Android emulator does
# not offer ("No config chosen" at start), unless <ATAK root>/opengl.broken
# exists: then MapView sets USE_GENERIC_EGL_CONFIG and GLMapSurface uses a
# generic RGBA8888 config. Emulators only; real devices keep ATAK's default.
case "$ANDROID_SERIAL" in
    emulator-*)
        if "$ADB" shell "mkdir -p /sdcard/atak && touch /sdcard/atak/opengl.broken" </dev/null >/dev/null 2>&1; then
            echo "Emulator: created /sdcard/atak/opengl.broken so ATAK uses its generic EGL config."
        else
            echo "Emulator: could not create /sdcard/atak/opengl.broken; ATAK may not render."
        fi
        # ATAK stops at "File System Access Changes" until it has all-files
        # access; on a fresh emulator grant it here, as a person would once.
        "$ADB" shell appops set --uid "$ATAK_PACKAGE" MANAGE_EXTERNAL_STORAGE allow </dev/null >/dev/null 2>&1 \
            && echo "Emulator: granted ATAK all-files access."
        enable_plugin_on_emulator ;;
esac

if [ -n "$BOOTED_EMULATOR" ]; then
    step "Check ATAK can render on the emulator"
    for mode in $EMULATOR_GPU_FALLBACKS ""; do
        gl_report
        if atak_gl_ok; then
            echo "ATAK renders with -gpu $EMULATOR_GPU."
            break
        fi
        echo "ATAK cannot get a GL config with -gpu $EMULATOR_GPU."
        [ -n "$mode" ] || { echo "No GPU mode worked; continuing so the report shows the crash."; break; }
        echo "Rebooting the emulator with -gpu $mode."
        "$ADB" -s "$EMULATOR_SERIAL" emu kill </dev/null >/dev/null 2>&1 || true
        sleep 8
        EMULATOR_GPU="$mode"; EMULATOR_FLAGS="$EMULATOR_HEADLESS $EMULATOR_GPU"
        start_emulator || fail "The emulator did not come back with -gpu $mode."
        export ANDROID_SERIAL="$EMULATOR_SERIAL"
    done
fi

step "Close ATAK cleanly"
# ATAK clears plugin auto-load after an unclean exit (force-stop, or a
# reinstall killing it). The running plugin (debug build) closes ATAK's
# activity so it records a clean shutdown; harmless when it is not running.
if "$ADB" shell pidof "$ATAK_PACKAGE" </dev/null >/dev/null 2>&1; then
    "$ADB" shell am broadcast -a com.cesium.atak.ion.TEST -p "$ATAK_PACKAGE" --es cmd quit \
        </dev/null >/dev/null 2>&1 || true
    sleep 6
fi

step "Install"
if ! INSTALL_OUTPUT="$("$ADB" install -r "$APK" 2>&1)"; then
    echo "$INSTALL_OUTPUT"
    if printf '%s' "$INSTALL_OUTPUT" | grep -q INSTALL_FAILED_VERSION_DOWNGRADE; then
        # Both builds are debuggable, so a deliberate downgrade is allowed.
        echo "The installed $PLUGIN_PACKAGE has a higher version code; reinstalling with downgrade allowed."
        INSTALL_OUTPUT="$("$ADB" install -r -d "$APK" 2>&1)" || {
            echo "$INSTALL_OUTPUT"
            fail "Install failed. Remove the old plugin with: $ADB uninstall $PLUGIN_PACKAGE"
        }
    elif printf '%s' "$INSTALL_OUTPUT" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then
        # Our own plugin, signed by someone else (e.g. the TAK.gov-signed build after a release test).
        echo "The installed $PLUGIN_PACKAGE has a different signature (e.g. the TAK-signed build); replacing it."
        "$ADB" uninstall "$PLUGIN_PACKAGE" </dev/null || true
        INSTALL_OUTPUT="$("$ADB" install "$APK" 2>&1)" || {
            echo "$INSTALL_OUTPUT"
            fail "Install failed after removing the differently signed plugin."
        }
    else
        fail "Install failed."
    fi
fi
echo "$INSTALL_OUTPUT" | tail -1

# --- Capture -----------------------------------------------------------------
step "Restart ATAK and capture logs"
"$ADB" logcat -c
"$ADB" shell am force-stop "$ATAK_PACKAGE"
launch_atak
"$ADB" logcat -v time >"$RAW" 2>&1 &
LOGCAT_PID=$!
[ -n "$SCENARIO" ] || cat <<STEPS

ATAK is restarting. Test on the tablet at your own pace, then come back here:
  1. First run only: Plugin Manager > load "Cesium ion"; then Settings > Tool Preferences >
     Cesium ion > paste an access token (assets:list + assets:read).
  2. Toolbar > Cesium ion. For a clean slate tap "Remove all Cesium ion layers".
     "My assets" lists your ion assets; Load / Remove each one.
     Imagery: then open Map Layers > Mobile and select "Cesium ion - <name>".
     3D Tiles: listed in Overlay Manager > 3D Models.
     GeoJSON / KML: listed in Overlay Manager; the map flies to GeoJSON.
     Terrain: load "Cesium World Terrain", then tilt over hills.
  3. Note what you see for each asset (drawn? right place? any errors in the pane log?).
  4. Leave the view you want me to see on screen: a screenshot is taken when the capture ends.
STEPS
CAPTURE_START=$SECONDS
if [ -n "$SCENARIO" ]; then
    run_scenario
else
    if [ "$MAX_CAPTURE_SECONDS" -gt 0 ]; then
        LIMIT_NOTE=" (or it ends by itself after ${MAX_CAPTURE_SECONDS}s)"
    else
        LIMIT_NOTE=""
    fi
    printf '\n>>> Capturing logs. Press Enter here when you are done testing%s.\n' "$LIMIT_NOTE"
    trap 'echo' INT
    # Read from the terminal even if stdin is redirected; Ctrl-C also ends it.
    if { exec 3</dev/tty; } 2>/dev/null; then
        if [ "$MAX_CAPTURE_SECONDS" -gt 0 ]; then
            read -r -t "$MAX_CAPTURE_SECONDS" -u 3 _ || true
        else
            read -r -u 3 _ || true
        fi
        exec 3<&-
    elif [ "$MAX_CAPTURE_SECONDS" -gt 0 ]; then
        sleep "$MAX_CAPTURE_SECONDS" || true
    else
        echo "No terminal to read Enter from; pass a number of seconds to capture for." >&2
    fi
    trap - INT
fi
echo "Capture ended after $((SECONDS - CAPTURE_START))s."
SCREEN="$REPORT_DIR/$STAMP-screen.png"
if "$ADB" exec-out screencap -p >"$SCREEN" 2>/dev/null && [ -s "$SCREEN" ]; then
    echo "Screenshot: $SCREEN"
else
    rm -f "$SCREEN"
fi
kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true

# --- Report ------------------------------------------------------------------
redact <"$RAW" >"$RAW.tmp" && mv "$RAW.tmp" "$RAW"
PATTERN='ANR in|Input dispatching timed out|not responding|Process com\.atakmap|Start proc [0-9]+:com\.atakmap|ActivityTaskManager.*atakmap|has died|CesiumIon|LayersManagerBroadcastReceiver|SELECT_LAYER|AbstractRasterLayer2|LayerSelection|Cesium3D|3DTiles|3D Tiles|StreamingMesh|ModelInfo|ModelImporter|SceneInfo|gltf|glb|b3dm|Mobac|MapSource|mapsource|LayersMapComponent|cesium-ion-|ImportExport|ElevationSource|PluginLoader|PluginManager|AtakPluginRegistry|AndroidRuntime|FATAL'
{
    echo "Cesium ion ATAK device report $STAMP"
    echo "Device: $ANDROID_SERIAL $MODEL, Android $ANDROID_VERSION, $ABI"
    echo "ATAK: $ATAK_VERSION"
    echo "Plugin: $(basename "$APK")  commit $(git -C "$ATAK_DIR" rev-parse --short HEAD 2>/dev/null || echo '?')"
    echo "Plugin lines: $(grep -c 'CesiumIon' "$RAW" || true)   Crashes: $(grep -cE 'FATAL EXCEPTION' "$RAW" || true)   ANRs: $(grep -cE 'ANR in com\.atakmap' "$RAW" || true)"
    if [ -n "$SCENARIO_LOG" ] && [ -f "$SCENARIO_LOG" ]; then
        echo "Scenario: $(basename "$SCENARIO")   Plugin failures: $(grep -cE '/CesiumIon\(.*(Failed|TEST command failed)' "$RAW" || true)"
        echo "---- scenario steps ----"
        cat "$SCENARIO_LOG"
    fi
    if [ -n "$EXPECT_FILE" ] && [ -s "$EXPECT_FILE" ]; then
        echo "---- regression checks ----"
        failed=0
        while IFS= read -r pattern; do
            if grep -E "/CesiumIon\(" "$RAW" | grep -qE -- "$pattern"; then
                echo "PASS  $pattern"
            else
                echo "FAIL  $pattern"; failed=$((failed + 1))
            fi
        done <"$EXPECT_FILE"
        crashes="$(grep -cE 'FATAL EXCEPTION' "$RAW" || true)"
        anrs="$(grep -cE 'ANR in com\.atakmap' "$RAW" || true)"
        [ "${crashes:-0}" -eq 0 ] && echo "PASS  no crashes" || { echo "FAIL  $crashes crash(es)"; failed=$((failed + 1)); }
        [ "${anrs:-0}" -eq 0 ] && echo "PASS  no ANRs" || { echo "FAIL  $anrs ANR(s)"; failed=$((failed + 1)); }
        [ "$failed" -eq 0 ] && echo "REGRESSION: PASS" || echo "REGRESSION: FAIL ($failed)"
    fi
    echo "---- plugin log (credentials redacted) ----"
    grep -E "/CesiumIon\(" "$RAW" | tail -200 || true
    echo "---- other relevant logcat, stack frames omitted ----"
    grep -iE "$PATTERN" "$RAW" | grep -vE "/CesiumIon\(|\): +at |\): \s+at |\.\.\. [0-9]+ more" | tail -200 || true
    atak_pid="$(grep -m1 -oE 'CesiumIon\( *[0-9]+\)' "$RAW" | grep -oE '[0-9]+' || true)"
    echo "---- ATAK map readiness: framerate and 3D model loading ----"
    grep -E "map framerate|Errors when loading tileset|Received status code|Could not load content|ModelImporter (import|delete)" "$RAW" \
        | cut -c1-220 | head -300 || true
    echo "---- ATAK process tag timeline (first and last time, line count per tag) ----"
    grep -E "\( *${atak_pid:-none}\)" "$RAW" \
        | awk '{ key = $3; sub(/\(.*/, "", key)
                 if (!(key in first)) { first[key] = $2; order[n++] = key }
                 last[key] = $2; count[key]++ }
               END { for (i = 0; i < n; i++) print first[order[i]], last[order[i]], count[order[i]], order[i] }' \
        | sort | head -400 || true
    if grep -q 'mark-window-start' "$RAW"; then
        echo "---- activity, window and ATAK lines between the window-start and window-end marks ----"
        sed -n '/mark-window-start/,/mark-window-end/p' "$RAW" \
            | grep -E "\( *${atak_pid:-none}\)|ActivityTaskManager|ActivityManager|WindowManager|PowerManagerService|InputDispatcher|DesktopMode|CesiumTest" \
            | grep -vE "\): +at |\.\.\. [0-9]+ more|MobacTileClient|HashingUtils" | head -400 || true
    fi
    if grep -qE 'ANR in com\.atakmap' "$RAW"; then
        echo "---- ANR: ATAK's main thread (emulator trace) ----"
        anr_main_stack | redact || true
    fi
    sdk_api_scan
} >"$REPORT"

step "Report: $REPORT"
cat "$REPORT"
echo
if [ -z "$SCENARIO" ]; then
    echo "Paste the report above (or attach $REPORT) and describe what you saw on the map."
fi
[ -f "${SCREEN:-}" ] && echo "Attach the screenshot too: $SCREEN"
echo "The full redacted log is $RAW."
