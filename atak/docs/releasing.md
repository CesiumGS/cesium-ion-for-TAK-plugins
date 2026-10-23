# ATAK plugin v1.0: scope freeze and TAK.gov signing

The owner froze v1 on 21 October 2026 for submission to the TAK.gov
Third-Party Pipeline, so the plugin loads in official ATAK builds (Play Store
ATAK-CIV and other release builds), not only in the SDK's developer build.

## In v1.0 (frozen)

- Sign in with Cesium ion (OAuth 2.0 + PKCE in the device browser), including
  ion's "Log in as a team"; or a pasted access token (`assets:list`,
  `assets:read`).
- Cesium ion Cloud and single-user ion Self-Hosted (server and API URL).
- Asset list with search and type filters; Add to map / Remove / Remove all.
- 3D Tiles (Google Photorealistic 3D Tiles, Cesium OSM Buildings, ion
  tilesets), imagery (ion TMS, Bing Maps), terrain (quantized mesh, as ATAK's
  elevation), GeoJSON and KML (as ATAK file overlays).
- Status with who is signed in, Settings behind the gear, attribution on the
  map.

Only fixes go into v1.0 from here. Everything else moves to v1.1: sharing a
layer through TAK, offline and degraded-network use, Self-Hosted
administration (several servers, certificates, managed settings), layer
controls, and the UX set.

## How the Third-Party Pipeline works

As described by developers who have used it (tak.gov itself was not reachable
from the build environment; check the pipeline page when you log in):

1. A tak.gov account, signed in, with the pipeline's terms accepted. Older
   accounts mention a government sponsor; current reports describe it as open
   to account holders.
2. Upload the plugin's **source** as one zip (no APKs, `local.properties`,
   keystores or credentials). The pipeline builds it against the ATAK version
   the build names (`ATAK_VERSION`), resolving the SDK and the takdev Gradle
   plugin from TAK's own repository, runs security scans, and signs the APK
   with TAK's third-party key. Reported time: about 30 minutes per build.
3. Download the signed APK and test it in a store/release ATAK of that version.

## Checklist

| Step | Who | Status |
|---|---|---|
| Freeze v1 scope | Owner | Done (21 Oct) |
| Release (minified) build passes and loads in ATAK | Claude (Mac runner) | Done (21 Oct): `release-check` builds it; `release-smoke` installs it on the tablet, where it loads, signs in, opens the pane and lists assets, with no crash or missing class |
| Build works from the source zip alone: takdev and SDK from TAK's repository when no local SDK is present; shared core inside the zip | Claude | Done (21 Oct): the unpacked zip builds the release APK |
| Source zip for upload (attached to a draft release) | Claude | Done: draft release "Cesium ion for ATAK 1.0.0: source for TAK.gov signing" on the Releases page (`[atak-source]` rebuilds it) |
| Upload the zip at tak.gov, download the signed APK | Owner (needs the tak.gov account) | Done (21 Oct): submission 1 failed to build (below); submission 2 built and was signed |
| Test the signed APK in store ATAK-CIV 5.7 on the tablet; publish v1.0.0 | Claude + owner | After signing |

## Submission 1 (21 October): build failed

The pipeline returned `build.log`, a Fortify report and a dependency-check
report, and no APK.

- **Why the build failed:** `Configuration with name
  'testCivReleaseImplementation' not found`. The pipeline's takdev plugin
  (from artifacts.tak.gov) adds dependencies to the release unit-test
  configuration. AGP 9 creates unit-test variants only for the tested build
  type (debug). The local SDK's takdev never touches it, which is why local
  and zip-only builds passed.
- **Fix:** `android.onlyEnableUnitTestForTheTestedBuildType=false` in
  `atak/gradle.properties`. `release-check` now builds from the zip and
  confirms the configuration exists. It also confirms the configuration is
  missing without the setting, which reproduces the pipeline's error.
- **Fortify:** 5 Critical and 11 High findings, none blocking the build.
  The real issues are fixed, test code is no longer in the package, and the
  rest are explained in [`../SECURITY-NOTES.md`](../SECURITY-NOTES.md)
  (shipped in the zip as `SECURITY-NOTES.md`).
- **Dependency check:** one low-confidence match on TAK's own
  `takdevlint.aar`, not on the plugin.

## Submission 2 (21 October): signed

- `ATAK-Plugin-CesiumIon-1.0.0-local-5.7.0-civ-release.apk` (160,621 bytes),
  signed by "TAK Product Center ATAK Untrusted Plugin Release" (TAK's
  third-party plugin key; certificate SHA-256
  `F2:4A:38:05:72:75:FC:EC:F6:7B:E9:75:AB:80:3D:12:F7:5D:C2:35:81:BE:F6:9C:BA:9E:B0:3A:15:BB:8C:17`).
  "local" in the name is the build's revision label: the pipeline builds
  from the zip, without git.
- The pipeline also returned an `.aab`, the R8 mapping (for reading crash
  stack traces), an SBOM, and the scan reports.
- Fortify: 17 findings, all documented in
  [`../SECURITY-NOTES.md`](../SECURITY-NOTES.md). Dependency check: the same
  low-confidence match on TAK's lint tool.

## Testing the signed build

- The signed build uses ATAK's release (obfuscated) class names, so it loads
  only in a release ATAK of the same version, never in the SDK's developer
  ATAK. The Play Store had ATAK-CIV 5.6 on 21 October, so v1.0 (ATAK 5.7)
  is tested with the ATAK-CIV 5.7.0.16 release APK from tak.gov
  (`atak/scripts/atak-install.sh` installs it from the Mac's Downloads).
- ATAK 5.7.0.16 contains the obfuscated names the signed build refers to
  (checked with `atak/scripts/plugin-diag.sh`).
- A freshly installed plugin is loaded in TAK Package Mgmt (tap Cesium ion,
  then OK). `release-smoke.sh` does this itself when the screen is on and
  unlocked; when the signed plugin is already installed and loaded by hand,
  it tests it as it is.

## 1.0.0 not released; 1.0.1 (22 October)

The signed 1.0.0 ran in standard ATAK-CIV 5.7.0.16 (pane, sign-in, Google
Photorealistic 3D Tiles and Vexcel 3D Cities on the map), but the release
check hit "ATAK isn't responding". `atak/scripts/anr-diag.sh` read the ANR
trace: ATAK's main thread, creating the Cesium ion pane, waited on a write
lock in ATAK's quantized-mesh terrain while Cesium terrain was attached and
loading. 1.0.1 re-attaches terrain far less often and only while the map is
still, and restores saved terrain 30 s after start. That alone did not fix
it (`terrain-stress-signin` at 1442946: 2 ANRs). The fix (32df853): the
terrain layer gives ATAK's main thread no tiles, so main-thread elevation
lookups never enter ATAK's quantized-mesh seam code; the 3D map, drawn on
other threads, still uses Cesium terrain. `terrain-stress` at 32df853: 0 ANRs,
terrain on screen. It needs its own TAK.gov submission, then the release
check of the signed APK in standard ATAK.

## Submission 3 (22 October, 1.0.1): "AAB signing has failed"

The Gradle build passed (`BUILD SUCCESSFUL`, same 57 tasks as the signed
submission 2, including `signCivReleaseBundle` and `bundleCivRelease` with
TAK's keystore-5.7.0.16.jks), Fortify reported the same 17 findings, and the
dependency check finished. Only the pipeline's own step after that failed:
`Pipeline Error: AAB signing has failed.` Nothing in the log points at the
plugin; the logs of submissions 2 and 3 differ only in task order and
timing. Next: resubmit the same zip; if it fails again, send the log to TAK.gov
support.

## Submission 4 (22 October, 1.0.1): signed

The same zip, resubmitted, built and was signed: "TAK Product Center ATAK
Untrusted Plugin Release" (SHA-256 F2:4A:38:05:…:8C:17), versionName
`1.0.1 (local) - [5.7.0]`, Fortify 17 findings as before. APK SHA-256
`92039921585e4b582017b22103747d78c2b652c8e55f58db65ca8db18ea32f6f`.

Release check: standard ATAK 5.7.0.16 installed on the tablet (90b8ac2; the
encryption-passphrase prompt after switching ATAK builds is answered with
"Remove and quit"), then `[atak-release: atak-v1.0.1]` with the signed APK.

Released 22 October as the GitHub release `atak-v1.0.1` after the
release check passed on the tablet (standard ATAK 5.7.0.16, account CesiumBD):
signed plugin installed, pane, sign-in, assets, Google Photorealistic 3D Tiles
on the map, no crash, no missing classes. The check's own UI steps needed
fixes for a fresh ATAK (open the pane from ATAK's menu, search the asset list).
Mac note: a second adb (Unity's) on the Mac kills the tests' adb server; quit
Unity while device tests run, or point Unity at the same Android SDK.
