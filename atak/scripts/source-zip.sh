#!/usr/bin/env bash
# Packages the plugin's source for the TAK.gov Third-Party Pipeline:
#   atak/scripts/source-zip.sh <out-dir>   ->  <out-dir>/CesiumIon-<version>-source.zip
# One root folder, CesiumIon/, holding the atak/ Gradle project (app, Gradle
# wrapper, settings) and a copy of the shared core in CesiumIon/ion-client-java
# (settings.gradle uses it when present). Committed files only: never local.properties,
# keystores, APKs, build output, device reports or test scripts.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:?usage: source-zip.sh <out-dir>}"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
version="$(sed -n 's/^ *ext.PLUGIN_VERSION = "\(.*\)"/\1/p' "$ROOT/atak/app/build.gradle" | head -1)"
stage="$(mktemp -d)"
dest="$stage/CesiumIon"
mkdir -p "$dest"
# Committed files only (git archive), so nothing local can slip in.
git -C "$ROOT" archive --format=tar HEAD atak core/ion-client-java | tar -x -C "$stage"
mv "$stage/atak/"* "$stage/atak/".[!.]* "$dest/" 2>/dev/null || true
mv "$stage/core/ion-client-java" "$dest/ion-client-java"
rm -rf "$stage/atak" "$stage/core" \
    "$dest/scripts" "$dest/release-notes" "$dest/device-reports" "$dest/template.local.properties"
find "$dest" \( -name 'local.properties' -o -name '*.apk' -o -name '*.aab' -o -iname '*keystore*' -o -name '*.jks' \) -delete
# Unit tests stay in the repository: they are not part of the plugin, and their
# fake tokens and local test servers only add noise to the pipeline's scans.
rm -rf "$dest/app/src/test" "$dest/ion-client-java/src/test"
# Install guide screenshots and release process notes are for the repository.
rm -rf "$dest/docs"
# The pipeline builds the release flavor; record what this zip is.
{
    echo "Cesium ion for ATAK $version, source for the TAK.gov Third-Party Pipeline"
    echo "Commit: $(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "Build: ./gradlew assembleCivRelease (ATAK_VERSION in app/build.gradle)"
    echo "Unit tests (197 in the shared core, 26 in the plugin) are in the project repository, not in this package."
    echo "Static-analysis findings and their handling: SECURITY-NOTES.md"
} > "$dest/SUBMISSION.txt"
zip="$OUT/CesiumIon-$version-source.zip"
rm -f "$zip"
(cd "$stage" && zip -qr -X "$zip" CesiumIon)
rm -rf "$stage"
echo "Source zip: $zip ($(wc -c < "$zip") bytes, $(unzip -l "$zip" | tail -1 | awk '{print $2}') files)"
# Nothing that must not leave the machine.
if unzip -l "$zip" | grep -Ei 'local\.properties|keystore|\.jks|\.apk' ; then
    echo "The zip holds a file it must not." >&2; exit 1
fi
