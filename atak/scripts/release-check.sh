#!/usr/bin/env bash
# Release readiness for the TAK.gov Third-Party Pipeline (Mac with the ATAK SDK):
#  1. how the SDK's own plugin template builds (the pipeline builds plugins
#     from a source zip the same way: takdev from TAK's Maven repository);
#  2. a release build (minified, ProGuard) of this plugin, with its size and
#     any R8/ProGuard warnings.
# Writes atak/device-reports/<time>-release-check-report.txt. Never prints
# credentials (takrepo values are masked).
set -uo pipefail
ATAK_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_DIR="$ATAK_DIR/device-reports"; mkdir -p "$REPORT_DIR"
REPORT="$REPORT_DIR/$(date +%Y%m%d-%H%M%S)-release-check-report.txt"
mask() { perl -pe 's/((?:password|user|token|key)\s*[=:]\s*["'"'"']?)[^"'"'"'\s]+/${1}MASKED/gi'; }
{
    echo "Cesium ion ATAK release check $(date -u +%FT%TZ), commit $(git -C "$ATAK_DIR" rev-parse --short HEAD)"
    cd "$ATAK_DIR"
    export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    sdk="$(./gradlew -q --console=plain help 2>&1 | sed -n 's/^ATAK SDK: \(.*\) (from .*)$/\1/p' | head -1)"
    [ -n "$sdk" ] || sdk="$(dirname "$(ls -d ~/Documents/*ATAK*SDK*/atak-gradle-takdev.jar 2>/dev/null | head -1)")"
    echo "SDK: $sdk"
    root="$(dirname "$sdk")"
    echo "---- SDK folder (2 levels) ----"
    find "$sdk" -maxdepth 2 \( -name '*.pdf' -o -name '*.md' -o -name '*.txt' -o -name '*.gradle' -o -name '*.zip' -o -type d \) 2>/dev/null | sed "s|$root/||" | head -80
    template="$(find "$sdk" "$root" -maxdepth 5 -path '*plugintemplate*' -name build.gradle 2>/dev/null | head -3)"
    for f in $template; do
        echo "---- ${f#$root/} ----"; mask < "$f"
        d="$(dirname "$f")"
        for g in "$d/../settings.gradle" "$d/../build.gradle" "$d/../gradle.properties" "$d/../template.local.properties" "$d/../local.properties.template"; do
            [ -f "$g" ] && { echo "---- ${g#$root/} ----"; mask < "$g"; }
        done
    done
    echo "---- pipeline / third party docs in the SDK ----"
    find "$sdk" "$root" -maxdepth 4 -iname '*pipeline*' -o -maxdepth 4 -iname '*third*party*' 2>/dev/null | head -20
    # proguard-gradle.txt applies ATAK's release mapping (atak.proguard.mapping),
    # which the takdev plugin fetches from TAK's repository: the TAK.gov
    # pipeline has it, a local SDK does not. Locally, an empty mapping matches
    # the SDK's developer ATAK (not obfuscated) and still runs R8 on our code.
    mapping="$(mktemp)"; : > "$mapping"
    echo "---- release build: ./gradlew assembleCivRelease (empty ATAK mapping) ----"
    start=$(date +%s)
    if ./gradlew --console=plain --warning-mode none -Datak.proguard.mapping="$mapping" assembleCivRelease > /tmp/cesium-release-build.log 2>&1; then
        echo "RELEASE BUILD: OK in $(( $(date +%s) - start )) s"
        ls -la app/build/outputs/apk/civ/release/*.apk 2>/dev/null
    else
        echo "RELEASE BUILD: FAILED"
    fi
    grep -E 'error:|Missing class|R8|FAILED' /tmp/cesium-release-build.log | grep -v 'Failed to resolve remote' | head -40 | mask
    echo "---- release build: the error in full ----"
    sed -n '/What went wrong/,/Get more help/p' /tmp/cesium-release-build.log | head -60 | mask
    echo "---- release build: --stacktrace causes ----"
    ./gradlew --console=plain --warning-mode none --stacktrace -Datak.proguard.mapping="$mapping" assembleCivRelease 2>&1 | grep -E '^Caused by|> ' | head -25 | mask
    echo "---- source zip for the TAK.gov pipeline, and a release build from it alone ----"
    zipdir="$(mktemp -d)"
    if "$ATAK_DIR/scripts/source-zip.sh" "$zipdir"; then
        zip="$(ls "$zipdir"/*.zip | head -1)"
        unzip -l "$zip" | awk 'NR>3 {print $4}' | grep -v '/$' | sed 's|^CesiumIon/||' | awk -F/ '{print $1}' | sort | uniq -c
        work="$(mktemp -d)"; (cd "$work" && unzip -q "$zip")
        # Local SDK mode (this Mac); the pipeline sets takrepo.url instead.
        if (cd "$work/CesiumIon" && ./gradlew --console=plain --warning-mode none -Datak.proguard.mapping="$mapping" assembleCivRelease > /tmp/cesium-zip-build.log 2>&1); then
            echo "ZIP RELEASE BUILD: OK"; ls -la "$work"/CesiumIon/app/build/outputs/apk/civ/release/*.apk
        else
            echo "ZIP RELEASE BUILD: FAILED"
            sed -n '/What went wrong/,/Get more help/p' /tmp/cesium-zip-build.log | head -40 | mask
        fi
        echo "---- TAK.gov pipeline: configurations its takdev plugin uses ----"
        # The first submission failed with "Configuration with name
        # 'testCivReleaseImplementation' not found" (AGP 9 creates unit-test
        # variants only for the tested build type unless told otherwise).
        for flag in "" "-Pandroid.onlyEnableUnitTestForTheTestedBuildType=true"; do
            if (cd "$work/CesiumIon" && ./gradlew -q --console=plain --warning-mode none $flag \
                    :app:dependencies --configuration testCivReleaseImplementation > /tmp/cesium-config.log 2>&1); then
                echo "testCivReleaseImplementation ${flag:-(as packaged)}: present"
            else
                echo "testCivReleaseImplementation ${flag:-(as packaged)}: MISSING"
                grep -m1 -E "UnknownConfiguration|not found" /tmp/cesium-config.log
            fi
        done
        echo "---- takdev: the SDK's copy, and TAK's repository ----"
        unzip -p "$sdk/atak-gradle-takdev.jar" META-INF/MANIFEST.MF 2>/dev/null | grep -iE "version|title" | head -5
        unzip -p "$sdk/atak-gradle-takdev.jar" 2>/dev/null | strings | grep -oE "test[A-Za-z%$]*Implementation|[a-zA-Z%$]*ReleaseImplementation" | sort | uniq -c | head -10
        meta="https://artifacts.tak.gov/artifactory/maven-release/com/atakmap/gradle/atak-gradle-takdev/maven-metadata.xml"
        echo "artifacts.tak.gov takdev metadata (anonymous): HTTP $(curl -sS -m 20 -o /tmp/cesium-takdev-meta.xml -w '%{http_code}' "$meta" 2>&1)"
        grep -oE "<(latest|release|version)>[^<]+" /tmp/cesium-takdev-meta.xml 2>/dev/null | tail -8
        rm -rf "$work"
    fi
} 2>&1 | tee "$REPORT"
