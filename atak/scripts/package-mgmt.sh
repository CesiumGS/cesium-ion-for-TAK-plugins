# Sourced by release-smoke.sh and device-test.sh (needs ADB, REPORT_DIR, STAMP).
# ATAK clears a plugin's auto-load after an unclean exit, and does not always
# offer to load it again; load_in_package_mgmt does what a user does then.
pm_dump_ui() {
    "$ADB" shell input keyevent KEYCODE_WAKEUP </dev/null >/dev/null 2>&1 || true
    "$ADB" shell uiautomator dump /sdcard/cesium-ui.xml </dev/null >/dev/null 2>&1 || true
    "$ADB" exec-out cat /sdcard/cesium-ui.xml </dev/null > /tmp/cesium-ui.xml 2>/dev/null || true
}
# Centre of the first node whose text matches the Perl regex $1.
pm_find_text() {
    perl -0777 -sne 'while (/<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g) {
        my ($t, $x1, $y1, $x2, $y2) = ($1, $2, $3, $4, $5);
        if ($t =~ /$re/) { printf "%d %d\n", ($x1+$x2)/2, ($y1+$y2)/2; exit } }' -- -re="$1" /tmp/cesium-ui.xml
}
# What a user does when ATAK does not load a plugin (it clears auto-load
# after an unclean exit): menu > Plugins (TAK Package Mgmt) > the Status
# cell in the plugin's row.
load_in_package_mgmt() {
    "$ADB" shell input tap 2508 110 </dev/null; sleep 3
    pm_dump_ui; local at; at="$(pm_find_text '^Plugins$')"
    [ -n "$at" ] || { echo "Package Mgmt: no Plugins item in ATAK's menu."; "$ADB" shell input keyevent BACK </dev/null; return; }
    "$ADB" shell input tap $at </dev/null; sleep 5
    pm_dump_ui
    local status
    status="$(perl -0777 -ne 'my @n; while (/<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g) { push @n, [$1,$2,$3,$4,$5] }
        my ($row) = grep { $_->[0] eq "Cesium ion" } @n; exit unless $row;
        for my $c (@n) { my $cy = ($c->[2]+$c->[4])/2;
            if ($c->[1] > $row->[3] - 10 && $c->[1] < $row->[3] + 700 && $cy > $row->[2] - 10 && $cy < $row->[4] + 10) {
                printf "%d %d %s\n", ($c->[1]+$c->[3])/2, $cy, $c->[0]; exit } }' /tmp/cesium-ui.xml)"
    "$ADB" exec-out screencap -p > "$REPORT_DIR/$STAMP-package-mgmt.png"
    if [ -n "$status" ]; then
        set -- $status
        echo "Package Mgmt: Cesium ion is \"${*:3}\"; tapping it to load."
        "$ADB" shell input tap "$1" "$2" </dev/null
        # ATAK asks "Load Cesium ion?" (Cancel / OK).
        local tries
        for tries in 1 2 3 4 5 6; do
            sleep 3
            pm_dump_ui; at="$(pm_find_text '^(Load|OK|Yes)$')"
            if [ -n "$at" ]; then echo "Package Mgmt: confirming the load."; "$ADB" shell input tap $at </dev/null; sleep 3; break; fi
        done
    else
        echo "Package Mgmt: no Cesium ion row."
    fi
    back_to_map
}
# BACK out of ATAK's settings and Package Mgmt screens to the map.
back_to_map() {
    local tries
    for tries in 1 2 3 4; do
        pm_dump_ui
        grep -qE 'Package Mgmt|Settings/' /tmp/cesium-ui.xml || return 0
        "$ADB" shell input keyevent BACK </dev/null; sleep 2
    done
}
