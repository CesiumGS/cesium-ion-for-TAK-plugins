#!/usr/bin/env bash
# Why ATAK will not load the installed Cesium ion plugin: checks that every
# ATAK class the plugin refers to exists in the installed ATAK (a TAK.gov
# build refers to ATAK's obfuscated release names, which must match the
# installed ATAK), then asks ATAK to load the plugin again and keeps ATAK's
# error. Writes atak/device-reports/<time>-report.txt (redacted).
set -uo pipefail
ATAK_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_DIR="$ATAK_DIR/device-reports"; mkdir -p "$REPORT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"; REPORT="$REPORT_DIR/$STAMP-report.txt"
ATAK_PACKAGE=com.atakmap.app.civ
PLUGIN_PACKAGE=com.cesium.atak.ion
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$(command -v adb || echo "$ANDROID_HOME/platform-tools/adb")"
serial="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -v '^emulator-' | head -1)"
[ -n "$serial" ] || { echo "No tablet attached." | tee "$REPORT"; exit 1; }
export ANDROID_SERIAL="$serial"
. "$ATAK_DIR/scripts/package-mgmt.sh"
work="$(mktemp -d)"
pull() { "$ADB" pull "$("$ADB" shell pm path "$1" </dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)" "$2" </dev/null >/dev/null 2>&1; }
{
    echo "Cesium ion plugin diagnostics $STAMP on $serial"
    echo "ATAK: $("$ADB" shell dumpsys package "$ATAK_PACKAGE" </dev/null | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1)"
    echo "Plugin: $("$ADB" shell dumpsys package "$PLUGIN_PACKAGE" </dev/null | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1)"
    pull "$ATAK_PACKAGE" "$work/atak.apk"; pull "$PLUGIN_PACKAGE" "$work/plugin.apk"
    mkdir -p "$work/atak" "$work/plugin"
    unzip -q -o "$work/atak.apk" 'classes*.dex' -d "$work/atak"; unzip -q -o "$work/plugin.apk" 'classes*.dex' -d "$work/plugin"
    echo "---- ATAK classes the plugin refers to, and whether the installed ATAK has them ----"
    python3 - "$work" <<'PY'
import glob, re, sys
work = sys.argv[1]
def descriptors(folder):
    out = set()
    for f in glob.glob(folder + '/classes*.dex'):
        out |= set(m.decode() for m in re.findall(rb'L(?:com/atakmap|gov/tak|com/atak|transapps)[A-Za-z0-9_/$]*;', open(f, 'rb').read()))
    return out
atak = descriptors(work + '/atak')
plugin = descriptors(work + '/plugin')
own = {d for d in plugin if d.startswith('Lcom/atakmap/android/cesium') }
missing = sorted(d for d in plugin - own if d not in atak)
print('ATAK dex descriptors: %d; plugin refers to %d ATAK classes; missing in ATAK: %d' % (len(atak), len(plugin), len(missing)))
for d in sorted(plugin):
    print(('  MISSING ' if d in missing else '  ok      ') + d)
for probe in ['Lgov/tak/api/plugin/IServiceController;', 'Lgov/tak/api/plugin/a;', 'Lcom/atakmap/map/g;', 'Lcom/atakmap/android/maps/ap;']:
    print('ATAK has %-45s %s' % (probe, probe in atak))
PY
    echo "---- ask ATAK to load the plugin again ----"
    "$ADB" logcat -c </dev/null
    "$ADB" shell monkey -p "$ATAK_PACKAGE" -c android.intent.category.LAUNCHER 1 </dev/null >/dev/null 2>&1
    sleep 10
    load_in_package_mgmt
    sleep 10
    "$ADB" exec-out screencap -p > "$REPORT_DIR/$STAMP-diag-screen.png"
    echo "---- ATAK's log around the load (redacted) ----"
    "$ADB" logcat -d </dev/null | grep -E -A12 'failed to load extension|AtakPluginRegistry|PluginLoader|Incompatible|incompatible' \
        | grep -vE 'adbd|glance' \
        | perl -pe 's/(access_token|token)=[^&\s"]+/$1=REDACTED/gi; s/eyJ[\w-]+\.[\w-]+\.[\w-]+/REDACTED/g' | head -120
    echo "DIAG: DONE"
} 2>&1 | tee "$REPORT"
