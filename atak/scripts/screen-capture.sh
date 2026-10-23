#!/usr/bin/env bash
# Saves what the tablet shows right now, without installing, restarting or
# touching anything: atak/device-reports/<time>-tablet-screen.png and a short
# report. For status screenshots of a view a person set up by hand.
set -uo pipefail
ATAK_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_DIR="$ATAK_DIR/device-reports"; mkdir -p "$REPORT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
ADB="$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")"
# Prefer the physical device when an emulator is also attached.
serial="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -v '^emulator-' | head -1)"
[ -n "$serial" ] || serial="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
[ -n "$serial" ] || { echo "No device attached." | tee "$REPORT_DIR/$STAMP-report.txt"; exit 1; }
shot="$REPORT_DIR/$STAMP-tablet-screen.png"
"$ADB" -s "$serial" exec-out screencap -p > "$shot" </dev/null
{
    echo "Cesium ion ATAK screen capture $STAMP"
    echo "Device: $serial $("$ADB" -s "$serial" shell getprop ro.product.model </dev/null | tr -d '\r')"
    echo "Screen: $(basename "$shot") ($(wc -c < "$shot") bytes); nothing installed, restarted or tapped."
} | tee "$REPORT_DIR/$STAMP-report.txt"
[ -s "$shot" ]
