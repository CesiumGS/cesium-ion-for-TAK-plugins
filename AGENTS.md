# Notes for coding agents (Claude Code, GitHub Copilot)

Read this first. It is the handoff for picking up work on this repository.
Plugin guides: `atak/README.md`, `vrtak/README.md`; plan: `docs/architecture-and-plan.md`.

## Rules (do not break)

- Repository: `CesiumGS/cesium-ion-for-TAK-plugins` (public). Work on a feature branch
  (`claude/...` or similar) and open a PR into `main`; device tests run on pushes to
  `main` and `claude/**` branches only (never on pull requests: the runners are the
  owner's own machines).
- Never hard-code, log, commit or print Cesium ion tokens. Test commands never set
  tokens; sign-in only opens the browser (the owner signs in on the tablet).
- Never commit `local.properties`, keystores, ATAK/VR-TAK SDK files, the R8 mapping
  file, raw logcat or Android bug reports. Reports are redacted.
- Releases ship only our plugin APK; never redistribute ATAK.
- VR-TAK material is Distribution Statement F (USSOCOM). The owner decided on 29 October to keep
  `vrtak/` in this public repository for now. VR-TAK runs publish a text log only (no pictures);
  never add VR-TAK screenshots or VR-TAK binaries/SDK files to this repo or to `device-reports`.
- No empty commits to re-run CI; never skip or disable tests.
- Don't break core function: sign-in (Cloud, team, Self-Hosted), asset list, adding
  3D Tiles, imagery and terrain.

## How testing works

Self-hosted runners: the owner's Mac (label `atak-device`, tablet on USB) and a
Windows PC (VR-TAK). A commit message marker starts a run; results are pushed to
the `device-reports` branch (public in this repository: reports are redacted, never
put tokens or personal data in them) under `runs/<time>-<sha>-<scenario>/report.txt`.

- `[device-test: <scenario>]` runs `atak/scripts/scenarios/<scenario>.txt` on the
  tablet (also special names: `release-check`, `release-smoke`, `atak-install`,
  `atak-install-dev`, `plugin-diag`, `anr-diag`). See `.github/workflows/device-test.yml`.
- `[vrtak: <task>]` runs VR-TAK scripts on the PC (`.github/workflows/vrtak.yml`).
- `[atak-source]` builds the TAK.gov submission zip as a draft release.
- `[atak-release: atak-vX.Y.Z]` publishes a release; if
  `atak/release-artifacts/<tag>/*.apk` (the TAK.gov-signed APK) exists, it smoke-tests
  that APK in standard ATAK first.
- Shared core: `cd core && ./gradlew test` (197 tests). The ATAK build needs the ATAK
  SDK, so it only builds on the Mac runner.

## Current state (2026-10-28)

**ATAK plugin v1.0.2 is released** (GitHub release `atak-v1.0.2`, TAK.gov-signed, for
standard ATAK-CIV 5.7; 1.0.1 fixed the ANR below, 1.0.2 the imagery/basemap report). v1.0.0 was signed but not released because ATAK froze (ANR).
The tablet now has standard ATAK 5.7.0.16 with the signed plugin; test builds need the
developer ATAK again (`[device-test: atak-install-dev]`).
On the Mac, quit Unity during device tests: its adb (v36) and Homebrew's (v37) kill each other.

**The freeze.** ATAK's main thread hangs in
`ElevationManager.getElevation → QMElevationSource::query → TerrainData::resolveSeams
→ addSkirt → RWMutex::lockWrite` (ATAK's native quantized-mesh code). It happens when a
map item is added (e.g. opening our pane) while Cesium terrain is attached. None of our
threads are on the stack. Throttling terrain re-attach (commit 1442946) did NOT fix it:
`terrain-stress-signin` still gave 2 ANRs.

**Fix (32df853, verified):** `IonTerrainLayer.hasTile/getTileFile` give the main thread
no tiles (`onMainThread()`), so main-thread point queries fall back to ATAK's other
elevation sources; the 3D map still draws Cesium terrain. `[device-test:
terrain-stress]` at 32df853: `ANRs: 0`, terrain visible. If a freeze comes back, run
`[device-test: anr-diag]` and read the main-thread stack.

## Next steps (in order)

1. Done: v1.0.1 (22 October) and v1.0.2 (28 October) released.
2. Later: ATAK 5.6 build (Play Store ATAK is 5.6.0.12; needs the 5.6 SDK); VR-TAK
   toolbar button and headset panel; TAKX plugin; features in `docs/architecture-and-plan.md`.

The owner also keeps a private status page (claude.ai artifact) with ATAK and VR-TAK
trackers; tell the owner what changed so it can be updated.
