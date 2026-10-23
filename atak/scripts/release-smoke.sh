#!/usr/bin/env bash
# The minified release build in the developer ATAK on the tablet: R8 can break
# code reached by reflection, and release builds have no test hooks, so this
# installs the release APK, restarts ATAK, opens the pane from the toolbar
# button, and checks the log and the screen. Built with an empty ATAK mapping
# (the SDK's developer ATAK is not obfuscated; see release-check.sh).
# Writes atak/device-reports/<time>-report.txt and screenshots.
# SIGNED_APK=<file>: test that APK (the TAK.gov-signed build) instead of
# building one; it needs standard ATAK-CIV 5.7 on the tablet, because the
# signed build uses ATAK's release mapping, which the developer ATAK lacks.
set -uo pipefail
ATAK_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_DIR="$ATAK_DIR/device-reports"; mkdir -p "$REPORT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"; REPORT="$REPORT_DIR/$STAMP-report.txt"
ATAK_PACKAGE=com.atakmap.app.civ
PLUGIN_PACKAGE=com.cesium.atak.ion
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$(command -v adb || echo "$ANDROID_HOME/platform-tools/adb")"
# Start adb first: a starting daemon can drop the first command (ATAK "not installed").
"$ADB" start-server >/dev/null 2>&1
for try in $(seq 1 30); do   # up to a minute for the tablet to show up after a daemon restart
    serial="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -v '^emulator-' | head -1)"
    [ -n "$serial" ] && break; sleep 2
done
[ -n "$serial" ] || { echo "No tablet attached." | tee "$REPORT"; exit 1; }
export ANDROID_SERIAL="$serial"
failed=0
check() { if eval "$2"; then echo "PASS  $1"; else echo "FAIL  $1"; failed=$((failed + 1)); fi; }
# pm_dump_ui, pm_find_text, load_in_package_mgmt
. "$ATAK_DIR/scripts/package-mgmt.sh"
{
    echo "Cesium ion ATAK release smoke test $STAMP, commit $(git -C "$ATAK_DIR" rev-parse --short HEAD)"
    echo "Device: $serial $("$ADB" shell getprop ro.product.model </dev/null | tr -d '\r')"
    # Keep the screen on while plugged in (the owner's standing setting for
    # this tablet; undo: adb shell svc power stayon false), and unlock it.
    "$ADB" shell svc power stayon true </dev/null
    "$ADB" shell input keyevent KEYCODE_WAKEUP </dev/null; "$ADB" shell wm dismiss-keyguard </dev/null >/dev/null 2>&1
    # Which ATAK is installed: version and signer (the SDK's developer build
    # or a standard release build).
    atak_apk="$(mktemp -d)/atak.apk"
    "$ADB" pull "$("$ADB" shell pm path "$ATAK_PACKAGE" </dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)" "$atak_apk" </dev/null >/dev/null 2>&1
    "$ADB" wait-for-device </dev/null
    for try in 1 2 3; do
        atak_version="$("$ADB" shell dumpsys package "$ATAK_PACKAGE" </dev/null | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1)"
        [ -n "$atak_version" ] && break; sleep 3
    done
    atak_signer="$("$JAVA_HOME/bin/keytool" -printcert -jarfile "$atak_apk" 2>/dev/null | sed -n 's/^Owner: //p' | head -1)"
    echo "ATAK: ${atak_version:-not installed}, signed by: ${atak_signer:-unknown}"
    if [ -n "${SIGNED_APK:-}" ]; then
        apk="$SIGNED_APK"
        echo "Signed APK: $(basename "$apk"), signed by: $("$JAVA_HOME/bin/keytool" -printcert -jarfile "$apk" 2>/dev/null | sed -n 's/^Owner: //p' | head -1)"
    else
        mapping="$(mktemp)"; : > "$mapping"
        (cd "$ATAK_DIR" && ./gradlew --console=plain --warning-mode none -Datak.proguard.mapping="$mapping" assembleCivRelease > /tmp/cesium-release-smoke-build.log 2>&1) \
            && echo "Release build: OK" || { echo "Release build: FAILED"; tail -30 /tmp/cesium-release-smoke-build.log; exit 1; }
        apk="$(ls -t "$ATAK_DIR"/app/build/outputs/apk/civ/release/*.apk | head -1)"
    fi
    echo "APK: $(basename "$apk") ($(wc -c < "$apk") bytes)"
    # ATAK clears plugin auto-load after an unclean exit (force-stop, or a
    # reinstall killing it), so let the installed debug build close ATAK first.
    if "$ADB" shell pidof "$ATAK_PACKAGE" </dev/null >/dev/null 2>&1; then
        "$ADB" shell am broadcast -a com.cesium.atak.ion.TEST -p "$ATAK_PACKAGE" --es cmd quit </dev/null >/dev/null 2>&1 || true
        sleep 6
    fi
    # Signed APK already installed and loaded by hand (ATAK running): test it as it is,
    # without reinstalling or restarting ATAK (which would unload it again).
    reuse=0
    if [ -n "${SIGNED_APK:-}" ]; then
        current="$(mktemp -d)/current.apk"
        "$ADB" pull "$("$ADB" shell pm path "$PLUGIN_PACKAGE" </dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)" "$current" </dev/null >/dev/null 2>&1
        if cmp -s "$current" "$SIGNED_APK" && "$ADB" shell pidof "$ATAK_PACKAGE" </dev/null >/dev/null 2>&1; then
            reuse=1; echo "The signed plugin is already installed and ATAK is running: testing it as loaded, no reinstall."
        else
            # Another signer's build (e.g. the debug build) cannot be replaced in place.
            echo "Uninstall earlier plugin: $("$ADB" uninstall "$PLUGIN_PACKAGE" </dev/null 2>&1 | tr -d '\r' | tail -1)"
        fi
    fi
    if [ "$reuse" = 0 ]; then
        installed="$("$ADB" install -r -d "$apk" </dev/null 2>&1 | tr -d '\r' | grep -v '^$' | tail -1)"
        echo "Install: ${installed:-no output}"
    fi
    plugin_apk="$(mktemp -d)/plugin.apk"
    "$ADB" pull "$("$ADB" shell pm path "$PLUGIN_PACKAGE" </dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)" "$plugin_apk" </dev/null >/dev/null 2>&1
    plugin_version="$("$ADB" shell dumpsys package "$PLUGIN_PACKAGE" </dev/null | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1)"
    plugin_signer="$("$JAVA_HOME/bin/keytool" -printcert -jarfile "$plugin_apk" 2>/dev/null | sed -n 's/^Owner: //p' | head -1)"
    echo "Installed plugin: ${plugin_version:-none}, signed by: ${plugin_signer:-unknown}"
    started=0
    if [ "$reuse" = 0 ]; then
        "$ADB" logcat -c </dev/null
        "$ADB" shell am force-stop "$ATAK_PACKAGE" </dev/null
    fi
    "$ADB" shell input keyevent KEYCODE_WAKEUP </dev/null
    "$ADB" shell monkey -p "$ATAK_PACKAGE" -c android.intent.category.LAUNCHER 1 </dev/null >/dev/null 2>&1
    if [ "$reuse" = 1 ]; then
        sleep 5
        # Loaded by hand: the toolbar button (and the plugin's log) show it is running.
        "$ADB" logcat -d </dev/null | grep -q "Cesium ion plugin started\|Loaded com.cesium.atak.ion.CesiumIonPlugin" && started=1
    fi
    for i in $(seq 1 60); do
        [ "$started" = 1 ] || [ "$reuse" = 1 ] && break
        sleep 5
        if "$ADB" logcat -d -s CesiumIon:I </dev/null | grep -q "Cesium ion plugin started"; then started=1; break; fi
        # Not loaded a minute after start: load it the way a user would.
        [ "$i" = 12 ] && load_in_package_mgmt
        # ATAK's "Load Plugins" prompt after an unclean exit: answer it while waiting.
        [ "$i" -ge 3 ] && [ "$i" -le 24 ] && [ "$i" != 12 ] || continue
        pm_dump_ui
        grep -qi plugin /tmp/cesium-ui.xml || continue
        load="$(perl -ne 'while (/<node [^>]*?text="(Load|Load Plugins|Reload Plugins|OK)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g) { printf "%d %d\n", ($2+$4)/2, ($3+$5)/2; exit }' /tmp/cesium-ui.xml)"
        if [ -n "$load" ]; then
            "$ADB" exec-out screencap -p > "$REPORT_DIR/$STAMP-release-plugin-dialog.png"
            echo "Tapped ATAK's plugin prompt after about $((i * 5)) s."
            "$ADB" shell input tap $load </dev/null
        fi
    done
    echo "Plugin started after about $((i * 5)) s: $started"
    [ "$started" = 1 ] || "$ADB" exec-out screencap -p > "$REPORT_DIR/$STAMP-release-not-loaded.png"
    sleep 20
    back_to_map
    # ATAK in front (not the browser of an unfinished sign-in) before tapping its toolbar.
    activity="$("$ADB" shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$ATAK_PACKAGE" </dev/null | tr -d '\r' | tail -1)"
    "$ADB" shell am start -n "$activity" </dev/null >/dev/null 2>&1
    sleep 3
    focus="$("$ADB" shell dumpsys window </dev/null | tr -d '\r' | grep -m1 -E 'mCurrentFocus|mFocusedApp')"
    echo "In front: ${focus##* }"
    # The Cesium ion toolbar button (top right, left of ATAK's menu button).
    # (Not when the pane is already open: the button would close it.)
    pane_up() { pm_dump_ui; grep -qE 'text="(Your assets|Add to map|Remove)"' /tmp/cesium-ui.xml; }
    # Android's notification or quick-settings panel over the toolbar would take the tap.
    "$ADB" shell cmd statusbar collapse </dev/null >/dev/null 2>&1; sleep 1
    # From ATAK's menu (its item is "Cesium ion"): a freshly installed ATAK has
    # its own toolbar layout, where the toolbar spot is another tool.
    open_pane() {
        "$ADB" shell input tap 2508 110 </dev/null; sleep 3
        pm_dump_ui; local at; at="$(pm_find_text '^Cesium ion$')"
        if [ -n "$at" ]; then echo "Opening the pane from ATAK's menu."; "$ADB" shell input tap $at </dev/null
        else echo "No Cesium ion item in ATAK's menu (a dialog in the way?)."; "$ADB" shell input keyevent BACK </dev/null; fi
        sleep 8
    }
    if printf '%s' "$focus" | grep -q "$ATAK_PACKAGE" && ! pane_up; then
        open_pane
        # One more try if the pane did not open (a dialog or panel took the first tap).
        pane_up || { "$ADB" shell cmd statusbar collapse </dev/null >/dev/null 2>&1; "$ADB" shell input keyevent BACK </dev/null; sleep 2; open_pane; }
    fi
    sleep 12
    "$ADB" exec-out screencap -p > "$REPORT_DIR/$STAMP-release-pane.png"
    pm_dump_ui
    # The asset list comes from ion over the network: give it up to 90 s.
    for i in $(seq 1 18); do
        "$ADB" logcat -d -s CesiumIon:I </dev/null | grep -qE "Found [0-9]* assets|Could not|failed|error" && break
        sleep 5
    done
    # A fresh ATAK has no sign-in yet (e.g. standard ATAK just installed): open
    # ion's sign-in page and give the person at the tablet 10 minutes to sign in.
    if ! "$ADB" logcat -d -s CesiumIon:I </dev/null | grep -qE "Found [0-9]* assets"; then
        pm_dump_ui
        at="$(pm_find_text '^Sign in with Cesium ion$')"
        if [ -n "$at" ]; then
            echo "Not signed in: opening Cesium ion's sign-in page; waiting up to 10 minutes for you to sign in on the tablet."
            "$ADB" shell input tap $at </dev/null
            for i in $(seq 1 120); do
                "$ADB" logcat -d -s CesiumIon:I </dev/null | grep -qE "Found [0-9]* assets" && { echo "Signed in after about $((i * 5)) s."; break; }
                sleep 5
            done
            # Back from the browser to ATAK, with the pane open.
            "$ADB" shell monkey -p "$ATAK_PACKAGE" -c android.intent.category.LAUNCHER 1 </dev/null >/dev/null 2>&1
            sleep 8
            pm_dump_ui
            grep -q "Your assets" /tmp/cesium-ui.xml || { open_pane; sleep 2; }
        fi
    fi
    "$ADB" exec-out screencap -p > "$REPORT_DIR/$STAMP-release-assets.png"
    # Add Google Photorealistic 3D Tiles from the pane's "From Cesium" list
    # (unless it is already on the map).
    pm_dump_ui
    # Listed under "On your map" (before "From Cesium"), or its asset row says "On the map".
    google_on_map() { perl -0777 -ne 'exit !(/text="On your map[^"]*"(?:(?!text="From Cesium").)*?text="Google Photorealistic 3D Tiles"/s || /text="Google Photorealistic 3D Tiles".{0,3000}?text="On the map"/s)' /tmp/cesium-ui.xml; }
    if google_on_map; then echo "Google Photorealistic 3D Tiles is already on the map."; fi
    # Not in view (a long asset list): search for it in the pane.
    if ! grep -q 'text="Google Photorealistic 3D Tiles"' /tmp/cesium-ui.xml; then
        at="$(pm_find_text '^Search your assets$')"
        if [ -n "$at" ]; then
            echo "Searching the pane for Google."
            "$ADB" shell input tap $at </dev/null; sleep 1
            "$ADB" shell input text Google </dev/null; sleep 1
            "$ADB" shell input keyevent BACK </dev/null; sleep 1   # hide the keyboard
            pm_dump_ui; at="$(pm_find_text '^Search$')"; [ -n "$at" ] && "$ADB" shell input tap $at </dev/null
            sleep 8
            # Bring the result row up from the bottom edge, where its button may be cut off.
            "$ADB" shell input swipe 2100 1400 2100 700 400 </dev/null; sleep 2
            pm_dump_ui
        fi
    fi
    add="$(perl -0777 -ne 'my @n; while (/<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g) { push @n, [$1,$2,$3,$4,$5] }
        my ($row) = (grep { $_->[0] eq "Google Photorealistic 3D Tiles" } @n)[-1]; exit unless $row;  # the asset row, not the On your map one
        for my $c (@n) { my $cy = ($c->[2]+$c->[4])/2;
            if ($c->[0] eq "Add to map" && $cy > $row->[2] - 60 && $cy < $row->[4] + 90) {
                printf "%d %d\n", ($c->[1]+$c->[3])/2, $cy; exit } }
        # Button not matched to the row: tap in the button column, level with the title.
        my ($b) = grep { $_->[0] eq "Add to map" } @n;
        printf "%d %d\n", ($b->[1]+$b->[3])/2, ($row->[4]+$row->[2])/2 + 20 if $b;' /tmp/cesium-ui.xml)"
    if [ -n "$add" ] && ! google_on_map; then
        echo "Adding Google Photorealistic 3D Tiles (tap at $add)."
        "$ADB" shell input tap $add </dev/null
        for i in $(seq 1 12); do
            "$ADB" logcat -d -s CesiumIon:I </dev/null | grep -qE "Added\. In ATAK|Could not|failed" && break
            sleep 5
        done
        sleep 30
        "$ADB" exec-out screencap -p > "$REPORT_DIR/$STAMP-release-google-3d.png"
    elif ! google_on_map; then
        echo "No Add to map button found for Google Photorealistic 3D Tiles."
    fi
    pm_dump_ui
    "$ADB" exec-out screencap -p > "$REPORT_DIR/$STAMP-release-final.png"
    log="$("$ADB" logcat -d </dev/null)"
    echo "---- plugin log ----"
    printf '%s\n' "$log" | grep -E 'CesiumIon|System.err.*(cesium|ion)|at com\.cesium' | perl -pe 's/(access_token|token)=[^&\s"]+/$1=REDACTED/gi; s/eyJ[\w-]+\.[\w-]+\.[\w-]+/REDACTED/g' 2>/dev/null | head -40
    echo "---- checks ----"
    check "plugin running" '{ [ "$reuse" = 0 ] && [ "$started" = 1 ]; } || { [ "$reuse" = 1 ] && grep -qE "text=\"(Your assets|Add to map|Remove)\"" /tmp/cesium-ui.xml; }'
    check "pane open (Your assets, Add to map or Remove on screen)" 'grep -qE "text=\"(Your assets|Add to map|Remove)\"" /tmp/cesium-ui.xml'
    check "assets listed (log, or asset rows on screen)" 'printf "%s\n" "$log" 2>/dev/null | grep -q "Found [0-9]* assets" || [ "$(grep -oE "text=\"[^\"]*, ID [0-9]+\"" /tmp/cesium-ui.xml | wc -l)" -ge 1 ] || grep -qE "text=\"(All|3D Tiles|Imagery|Terrain)[^\"]*[0-9]" /tmp/cesium-ui.xml || google_on_map'
    check "Google Photorealistic 3D Tiles on the map" 'printf "%s\n" "$log" 2>/dev/null | grep -q "Added\. In ATAK" || google_on_map'
    if [ -n "${SIGNED_APK:-}" ]; then
        check "the TAK-signed plugin is the one installed" '[ "$(cmp -s "$plugin_apk" "$SIGNED_APK" && echo same)" = same ]'
        check "ATAK-CIV 5.7 installed" 'printf "%s" "$atak_version" | grep -q "^5\.7\."'
    fi
    check "no crash in the plugin" '! printf "%s\n" "$log" | grep -A8 "FATAL EXCEPTION" | grep -qiE "cesium|atakplugin"'
    check "no missing classes or methods (R8)" '! printf "%s\n" "$log" | grep -E "ClassNotFoundException|NoSuchMethodError|NoClassDefFoundError|NoSuchFieldError" | grep -qiE "cesium|atakplugin"'
    if [ "$failed" -eq 0 ]; then echo "REGRESSION: PASS"; else echo "REGRESSION: FAIL ($failed)"; fi
} 2>&1 | tee "$REPORT"
grep -q "^REGRESSION: PASS" "$REPORT"
