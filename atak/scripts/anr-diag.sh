#!/usr/bin/env bash
# What ATAK was stuck on when Android reported "ATAK isn't responding": takes
# an Android bug report on the Mac, and publishes only ATAK's ANR reason and
# its main-thread stack (plus any thread running Cesium ion code). The bug
# report itself holds personal data and never leaves the Mac; it is deleted.
# Writes atak/device-reports/<time>-report.txt.
set -uo pipefail
ATAK_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_DIR="$ATAK_DIR/device-reports"; mkdir -p "$REPORT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"; REPORT="$REPORT_DIR/$STAMP-report.txt"
ATAK_PACKAGE=com.atakmap.app.civ
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$(command -v adb || echo "$ANDROID_HOME/platform-tools/adb")"
serial="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -v '^emulator-' | head -1)"
[ -n "$serial" ] || { echo "No tablet attached." | tee "$REPORT"; exit 1; }
export ANDROID_SERIAL="$serial"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
redact() { perl -pe 's/(access_token|token|key|session)=[^&\s"]+/$1=REDACTED/gi; s/eyJ[\w-]+\.[\w-]+\.[\w-]+/REDACTED/g'; }
{
    echo "ATAK not-responding diagnostics $STAMP on $serial"
    "$ADB" exec-out screencap -p > "$REPORT_DIR/$STAMP-anr-screen.png"
    echo "---- Android's ANR reports for ATAK (logcat) ----"
    "$ADB" logcat -d -b main,system </dev/null | grep -A6 "ANR in $ATAK_PACKAGE" | grep -vE '^\s*$' | redact | head -40
    echo "---- bug report (kept on the Mac only while reading it) ----"
    "$ADB" bugreport "$work/bugreport.zip" </dev/null >/dev/null 2>&1 && echo "bug report taken" || echo "bug report failed"
    unzip -q -o "$work/bugreport.zip" 'FS/data/anr/*' -d "$work/br" 2>/dev/null
    trace="$(grep -l "Cmd line: $ATAK_PACKAGE" "$work"/br/FS/data/anr/* 2>/dev/null | xargs ls -t 2>/dev/null | head -1)"
    if [ -z "$trace" ]; then
        echo "No ANR trace for ATAK in the bug report."
    else
        echo "Trace: $(basename "$trace") ($(head -5 "$trace" | grep -m1 -E '^----- pid' || true))"
        echo "---- ATAK main thread ----"
        awk '/^"main" /{p=1} p&&/^$/{exit} p' "$trace" | head -60 | redact
        echo "---- threads running Cesium ion code ----"
        awk 'BEGIN{RS=""} /com\.cesium/' "$trace" | head -120 | redact
        echo "---- threads in ATAK's terrain or elevation code (the lock holder is among them) ----"
        awk 'BEGIN{RS=""} /QuantizedMesh|TerrainData|QMElevation|ElevationManager|ElevationSource|terrain/ && !/^"main" /' "$trace" \
            | grep -vE 'BuildId|^\s*\| (stack|held|group|sysTid|state)=' | head -200 | redact
        echo "---- ANR traces on the tablet ----"
        ls -la "$work"/br/FS/data/anr/ 2>/dev/null | awk '{print $5, $6, $7, $8, $9}'
        echo "---- what the main thread is waiting for (lock owners) ----"
        awk '/^"main" /{p=1} p&&/^$/{exit} p' "$trace" | grep -E 'waiting to lock|held by|blocked' | redact
    fi
    echo "ANR DIAG: DONE"
} 2>&1 | tee "$REPORT"
