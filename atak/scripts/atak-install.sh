#!/usr/bin/env bash
# Installs a standard ATAK-CIV release APK on the tablet and starts it, for
# testing the TAK.gov-signed plugin (which the SDK's developer ATAK cannot
# load). Looks in ~/Downloads (or ATAK_APK=<file>) for an ATAK-CIV APK of
# version ATAK_VERSION (default 5.7), installs it with its runtime
# permissions granted, allows all-files access (ATAK asks for it at first
# start), starts ATAK and answers its first-start screens.
# Writes atak/device-reports/<time>-report.txt and screenshots.
set -uo pipefail
ATAK_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_DIR="$ATAK_DIR/device-reports"; mkdir -p "$REPORT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"; REPORT="$REPORT_DIR/$STAMP-report.txt"
ATAK_PACKAGE=com.atakmap.app.civ
WANT="${ATAK_VERSION:-5.7}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$(command -v adb || echo "$ANDROID_HOME/platform-tools/adb")"
AAPT="$(ls -d "$ANDROID_HOME"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1)"
serial="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -v '^emulator-' | head -1)"
[ -n "$serial" ] || { echo "No tablet attached." | tee "$REPORT"; exit 1; }
export ANDROID_SERIAL="$serial"
. "$ATAK_DIR/scripts/package-mgmt.sh"
{
    echo "ATAK-CIV install $STAMP on $serial $("$ADB" shell getprop ro.product.model </dev/null | tr -d '\r')"
    # Pick the APK: package com.atakmap.app.civ, version $WANT.*.
    apk="${ATAK_APK:-}"
    # ATAK_FLAVOR=developer: the SDK's developer ATAK (for test builds), from the ATAK-CIV SDK on the Mac.
    if [ -z "$apk" ] && [ "${ATAK_FLAVOR:-}" = developer ]; then
        # The SDK folder the build uses (atak-sdk.gradle prints it).
        sdk="$(cd "$ATAK_DIR" && ./gradlew -q --console=plain help 2>/dev/null | sed -n 's/^ATAK SDK: \(.*\) (from .*)$/\1/p' | head -1)"
        apk="$(ls "$sdk"/atak.apk "$sdk"/*/atak.apk 2>/dev/null | head -1)"
        echo "Developer ATAK from the SDK: ${apk:-not found (SDK: ${sdk:-none})}"
        [ -n "$apk" ] || exit 1
    fi
    if [ -z "$apk" ]; then
        echo "---- ATAK files in ~/Downloads ----"
        echo "aapt: ${AAPT:-not found}"
        ls -d "$HOME/Downloads" >/dev/null 2>&1 && echo "Downloads readable: $(ls -A "$HOME/Downloads" 2>&1 | wc -l | tr -d ' ') entries" \
            || echo "Downloads not readable by the runner: $(ls "$HOME/Downloads" 2>&1 | head -1)"
        work="$(mktemp -d)"
        # APKs, and APKs inside zips (tak.gov downloads often come zipped).
        while IFS= read -r z; do
            echo "zip: $(basename "$z")"
            unzip -o -q -j "$z" '*.apk' -d "$work/$(basename "$z" .zip)" 2>/dev/null || true
        done < <(find "$HOME/Downloads" -maxdepth 2 -iname '*atak*.zip' 2>/dev/null)
        while IFS= read -r f; do
            badge="$("$AAPT" dump badging "$f" 2>/dev/null | head -1)"
            pkg="$(printf '%s' "$badge" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
            ver="$(printf '%s' "$badge" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")"
            echo "$(basename "$f"): ${pkg:-?} ${ver:-?}"
            [ "$pkg" = "$ATAK_PACKAGE" ] || continue
            echo "    signed by: $("$JAVA_HOME/bin/keytool" -printcert -jarfile "$f" 2>/dev/null | sed -n 's/^Owner: //p' | head -1)"
            case "$ver" in "$WANT".*) [ -z "$apk" ] && apk="$f" ;; esac
        done < <( { find "$HOME/Downloads" -maxdepth 3 -iname '*.apk' 2>/dev/null; find "$work" -iname '*.apk'; } | sort)
    fi
    [ -n "$apk" ] || { echo "No ATAK-CIV $WANT APK found in ~/Downloads (the runner may lack access to it; set ATAK_APK)."; exit 1; }
    echo "Installing: $(basename "$apk")"
    if "$ADB" shell pm path "$ATAK_PACKAGE" </dev/null 2>/dev/null | grep -q package:; then
        echo "ATAK already installed: $("$ADB" shell dumpsys package "$ATAK_PACKAGE" </dev/null | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1)"
    fi
    want_ver="$("$AAPT" dump badging "$apk" 2>/dev/null | head -1 | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")"; want_ver="${want_ver%% *}"
    installed_ver() { "$ADB" shell dumpsys package "$ATAK_PACKAGE" </dev/null 2>/dev/null | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1 | cut -d' ' -f1; }
    # Two tries: adb can drop the connection during a large install.
    for try in 1 2; do
        "$ADB" wait-for-device </dev/null
        if ! "$ADB" install -r -g "$apk" </dev/null 2>&1 | tail -1 | tee /dev/stderr | grep -q Success; then
            # Another signer's ATAK (release vs developer) cannot be replaced in place.
            echo "Replacing the installed ATAK (another signer): $("$ADB" uninstall "$ATAK_PACKAGE" </dev/null 2>&1 | tail -1)"
            "$ADB" install -g "$apk" </dev/null 2>&1 | tail -1
        fi
        [ -z "$want_ver" ] || [ "$(installed_ver)" = "$want_ver" ] && break
        echo "Installed version is $(installed_ver), not $want_ver; restarting adb and trying again."
        "$ADB" kill-server </dev/null; sleep 3; "$ADB" start-server </dev/null; sleep 5
    done
    "$ADB" shell appops set --uid "$ATAK_PACKAGE" MANAGE_EXTERNAL_STORAGE allow </dev/null 2>&1 | tail -1
    echo "Installed: $("$ADB" shell dumpsys package "$ATAK_PACKAGE" </dev/null | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1)"
    "$ADB" shell input keyevent KEYCODE_WAKEUP </dev/null
    "$ADB" shell monkey -p "$ATAK_PACKAGE" -c android.intent.category.LAUNCHER 1 </dev/null >/dev/null 2>&1
    echo "---- first start ----"
    quiet=0
    for i in $(seq 1 40); do
        sleep 5
        pm_dump_ui
        target="$(perl -0777 -ne 'while (/<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g) {
                my ($t,$x1,$y1,$x2,$y2)=($1,$2,$3,$4,$5);
                if ($t =~ /^\s*(i agree|agree|accept|allow|allow all the time|while using the app|only this time|continue|next|done|ok|skip|dismiss|close|got it|i understand|start)\s*[.!]?\s*$/i) {
                    printf "%d %d %s\n", ($x1+$x2)/2, ($y1+$y2)/2, $t; exit } }' /tmp/cesium-ui.xml)"
        if [ -n "$target" ]; then
            set -- $target
            [ "$quiet" -eq 0 ] && "$ADB" exec-out screencap -p > "$REPORT_DIR/$STAMP-first-start-$i.png"
            echo "+$((i * 5))s tapping \"${*:3}\""
            "$ADB" shell input tap "$1" "$2" </dev/null
            quiet=0
        else
            quiet=$((quiet + 1))
            # 30 s without a prompt: ATAK is on its map.
            [ "$quiet" -ge 6 ] && break
        fi
    done
    "$ADB" exec-out screencap -p > "$REPORT_DIR/$STAMP-atak-started.png"
    echo "---- on screen ----"
    perl -0777 -ne 'while (/<node [^>]*?text="([^"]+)"/g) { print "  $1\n" }' /tmp/cesium-ui.xml | head -20
    if [ -n "$want_ver" ] && [ "$(installed_ver)" != "$want_ver" ]; then echo "ATAK INSTALL: FAILED (installed $(installed_ver), wanted $want_ver)"
    elif "$ADB" shell pidof "$ATAK_PACKAGE" </dev/null >/dev/null 2>&1; then echo "ATAK INSTALL: OK (running $(installed_ver))"; else echo "ATAK INSTALL: ATAK is not running"; fi
} 2>&1 | tee "$REPORT"
grep -q "^ATAK INSTALL: OK" "$REPORT"
