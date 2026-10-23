# Cesium ion for TAK: assessment and two-phase plan

Status: **approved**. M0 (repository cleanup), M1 (shared core), and M2 (ATAK skeleton) are done, and S1 passed: Google Photorealistic 3D Tiles render in ATAK 5.7 (§4). M3 (asset browser), M5 (tile proxy and ion 3D Tiles), and M6 (imagery) are written and awaiting device testing.

- Phase 1: ATAK-CIV plugin, developed on a Mac with Android Studio
- Phase 2: VR-TAK plugin (Unreal Engine 5.3.2), developed on Windows
- Both phases use a shared ion core

---

## 1. What was in the repository (before M0)

This section describes the repository before M0. That code was kept under `legacy/` while the plugins were rebuilt, then removed in the October 2026 clean-up; it is in the git history.

| Path | What it is | Size |
|---|---|---|
| `client/` | React + CesiumJS web app: asset browser, viewer, and the JS side of the TAK bridges | ~2,000 LOC (`App.tsx` is 1,800) |
| `server/` | Express backend: ion OAuth (PKCE), session, asset listing, plus a **tile proxy** that rewrites tilesets, expands implicit tiling, decodes Draco, strips/transcodes KTX2, and bakes Google node transforms | ~2,700 LOC in one file (`server/src/index.ts`) |
| `atak-cesium-ion-plugin/` | ATAK plugin: an `IPlugin` that opens a pane containing a **WebView pointed at `http://localhost:5173`**, plus a JS bridge that hands URLs to ATAK importers | 2 Java classes, ~720 LOC |
| `wintak-webview-spike/` | WinTAK plugin: WebView2 dock pane plus a C# bridge | ~3,700 LOC C# (`MapMessagingBridge.cs` is 3,178) |
| `docs/` | WinTAK spike reports | – |

### How the old ATAK plugin worked

```
ATAK process                              Developer laptop (via `adb reverse`)
┌───────────────────────────────┐         ┌───────────────────────────────┐
│ CesiumIonPlugin (IPlugin)     │         │ Vite dev server :5173         │
│  └─ Pane ─ WebView ───────────┼────────▶│  React + CesiumJS UI          │
│        └─ AtakCesiumBridge    │         │ Express :3001                 │
│            (JS interface)     │         │  OAuth, /v1/assets, tile proxy│
│  ModelImporter / map sources ─┼────────▶│  http://localhost:3001/api/   │
└───────────────────────────────┘         └───────────────────────────────┘
```

This was a reasonable way to prove the ion workflow, but it **can't be deployed**. The plugin only works while a laptop is tethered over USB, running Node, with two `adb reverse` rules active.

## 2. Assessment

### Worth keeping (port the logic, not the code)

- **Endpoint resolution** (`server/src/index.ts`, `resolveEndpointRoot`, the `/api/native/assets/:id/endpoint` handler). It covers ion-hosted assets and the external `BING` and `GOOGLE_2D_MAPS` types, and Google Photorealistic 3D Tiles detection. This is the core domain logic.
- **Team / workspace handling** that tolerates `403/404/405` from `/v1/teams`. Self-hosted deployments may not expose teams.
- **TMS `tilemapresource.xml` parsing** with an XXE-hardened parser (`AtakCesiumBridge.readImageryMetadata`).
- **ATAK custom map source generation** for imagery, and `ModelImporter` streaming import for 3D Tiles. These are the right native integration points.
- **Build guardrails** in `app/build.gradle`. They give clear errors when the SDK, `main.jar`, or `android_keystore` is missing.
- **Apple silicon emulator note** (`-DUSE_GENERIC_EGL_CONFIG=true`).
- **Tile-proxy workarounds** (implicit-tiling expansion, Draco/KTX2 handling). These were written for WinTAK's engine. Bring them back only if device testing shows ATAK needs them.

### Problems

**Architecture**

1. The ATAK plugin depends on a laptop at runtime: it uses a localhost Node server, a Vite dev server, and `adb reverse`.
2. **No shared core exists.** The ion logic lives in a TypeScript server that neither ATAK (JVM) nor Unreal (C++) can embed.
3. The two monoliths (`server/src/index.ts` and `MapMessagingBridge.cs`, ~3k LOC each) mix auth, REST, proxying, glTF transcoding, and UI messaging.
4. The UI runs CesiumJS inside a WebView inside ATAK. That means a second 3D engine and GPU context next to ATAK's renderer, and it isn't the "render inside TAK" requirement.
5. There are no Tool Preferences. Authentication is OAuth-only via the server, so it can't use a pasted ion token. Self-hosted ion isn't supported: the ion URLs are hard-coded constants in `server/src/index.ts:31-33`.

**Security**

6. The imagery map-source XML is written to `/sdcard/atak/imagery/mobile/mapsources` (`AtakCesiumBridge.java:43, 334`). It can embed Google session keys or Bing keys, and it sits on shared storage that other apps can read. The path is also hard-coded instead of using ATAK's `FileSystemUtils`.
7. Full bridge messages, including tokenized URLs, are logged at debug level (`AtakCesiumBridge.java:70, 120, 156, 226`).
8. The JS-interface origin gating isn't reliable. `addJavascriptInterface` and `removeJavascriptInterface` only take effect on the next page load, and the interface is enabled before the first load (`CesiumIonPlugin.java:183`). So the bridge can be visible to pages that aren't trusted, for example during the OAuth redirect.
9. The plugin's `network_security_config.xml` and `usesCleartextTraffic` have **no effect**, because plugin code runs inside ATAK's process and ATAK's manifest governs. This matters for self-hosted ion with a private CA.
10. The browser gets the raw ion access token. The repo's own README already flags this.

**Repository hygiene**

11. 117 build outputs and NuGet packages are committed under `wintak-webview-spike/{bin,obj,packages}`, about 72 MB, despite `.gitignore`. They include **WinTAK SDK binaries** (`WinTak.*.dll`, `TAK.Engine.dll`), which probably shouldn't be redistributed from a GitHub repo.
12. Many commits are titled "Save uncommitted changes". `wintak` naming leaks into the shared code (`LOCAL_BOUNDING_VOLUME_MARKER = '__wintakLocalBoundingVolume'`, the package name `wintak-cesium-ion-prototype`).

**Build details (minor)**

13. `ATAK_PACKAGE_NAME` is hard-coded to `com.atakmap.app.civ` for every flavor, including `mil`.
14. `ndk.abiFilters` isn't needed in a pure-Java plugin.
15. AGP 8.13 / Gradle 8.14 must be checked against the `atak-gradle-takdev` version in your SDK. The SDK's `plugintemplate` is the reference.
16. Release signing reuses the SDK debug keystore. That's fine for development, but a release build needs to go through the TAK.gov Third-Party Plugin pipeline (see §6).

### Verdict

Do a **targeted rebuild** of the ATAK plugin around a new shared core. Don't refactor the WebView approach. The domain knowledge in the server and bridge carries over; the runtime architecture does not.

## 3. Target architecture

```
                 ┌─────────────────────────────────────────────┐
                 │ spec/ion-contract/                          │
                 │  REST endpoints used, JSON fixtures,        │
                 │  expected parse/resolve results             │
                 └──────────────┬───────────────┬──────────────┘
                    conformance │               │ conformance
             ┌──────────────────▼───┐   ┌───────▼──────────────────────┐
             │ core/ion-client-java │   │ Phase 2: C++ ion client      │
             │ (pure Java 11 lib,   │   │ (cesium-native               │
             │  no Android deps)    │   │  CesiumIonClient or thin port)│
             └──────────┬───────────┘   └───────┬──────────────────────┘
                        │                       │
             ┌──────────▼───────────┐   ┌───────▼──────────────────────┐
             │ atak/ (Android)      │   │ vrtak/ (Unreal 5.3.2)        │
             │  prefs + secure token│   │  UMG/VR UI                   │
             │  native asset browser│   │  Cesium for Unreal actors    │
             │  loopback tile proxy │   │  (3D Tiles, terrain, overlays)│
             │  ATAK renderers      │   │                              │
             └──────────────────────┘   └──────────────────────────────┘
```

### Shared core responsibilities (both platforms)

- `IonServerConfig`: Cloud (`https://ion.cesium.com`, API `https://api.cesium.com`) or Self-Hosted (a user-entered server URL). The API base is discovered from `<server>/config.json` (`apiHostname`), as cesium-native does, with a manual override.
- `IonClient`: `GET /v1/me`, `/v1/assets` (paged, search, `team`), `/v1/assets/{id}`, `/v1/assets/{id}/endpoint`, `/v1/defaults`, and `/v1/teams` (tolerant of 403/404/405).
- `AssetEndpoint` (sealed):
  - `IonHosted{url, accessToken}`
  - `Bing{url, key, mapStyle}`
  - `Google2D{url, session, key}`
  - `GooglePhotorealistic{url, key}`
- `EndpointTokenCache`: re-resolves endpoint tokens before expiry and after a 401.
- Asset classification: `IMAGERY`, `TERRAIN`, `3DTILES` are supported. The 3D Tiles version, `GLTF`, `CZML`, `KML`, and `GEOJSON` are recorded and reported as not yet supported.
- Errors are typed: `Unauthorized`, `Forbidden`, `NotFound`, `Network`, `ServerError`. **Tokens are redacted** from every `toString` and log line.
- HTTP goes through a small `IonHttp` interface. The default implementation uses `HttpURLConnection`. ATAK can swap in a transport that uses ATAK's trust store, which self-hosted servers with private CAs need.

### ATAK side

- **Lifecycle**: `IPlugin` with `onStart`/`onStop`, and a toolbar item that opens a `Pane` with a native Android UI (no WebView).
- **Tool Preferences** (registered through ATAK's tool-preferences mechanism):
  - Mode: Cloud or Self-Hosted
  - Self-hosted server URL, with an optional API URL override
  - Access token (masked; shows "Test connection" and the `/v1/me` username)
  - Default team
- **Token storage**: ATAK's encrypted credential store (`AtakAuthenticationDatabase`) if the SDK exposes it to plugins, otherwise Android Keystore AES-GCM. Never ATAK's default shared prefs, because those are included in ATAK's preference export. Never shared storage. The token is never logged.
- **Loopback tile proxy** (`127.0.0.1` only, with an unguessable per-install path secret and a stable port). ATAK's importers get stable URLs like `http://127.0.0.1:<port>/<secret>/a/<assetId>/tileset.json`. The proxy then:
  - adds `Authorization: Bearer <endpoint token>` and refreshes it after a 401 or when it expires (endpoint tokens last about an hour, so baked URLs go stale)
  - keeps tokens and keys out of map-source files and ATAK's persisted layer state
  - gives one place to add Draco/KTX2/implicit-tiling fixes if ATAK needs them

  This replaces what the Node server did, but runs on the device.
- **Rendering**:
  - `3DTILES`: streaming import through `ModelImporter`, which uses the TAK kernel's own 3D Tiles path (cesium-native, where present).
  - `IMAGERY`: custom map source pointed at the proxy, written to ATAK's private storage and imported as `External Native Data` / `application/xml` (as ATAK imports its bundled map sources), so neither tokens nor the proxy secret reach shared storage.
  - `TERRAIN`: depends on spike S3 below.

## 4. Key requirement: Google Photorealistic 3D Tiles in TAK

Google Photorealistic 3D Tiles (ion asset **2275207**) must stream from ion and render natively in TAK. **This is the highest-risk item in Phase 1.**

### What we know

- **ion endpoint**: `externalType: "3DTILES"` with `options.url` = `https://tile.googleapis.com/v1/3dtiles/root.json?key=...`. The legacy prototype proved this against real ion.
- **Streaming rules**:
  - Child references are absolute paths (`/v1/3dtiles/datasets/...`).
  - The first level carries a `session` parameter.
  - Every request needs both `key` and `session`.

  The core implements this as same-origin query inheritance (`TileUrls.deriveChild`), and it's covered by `spec/ion-contract`.
- **Content, measured with `ion-probe google` against live ion on 2026-10-12** (6/6 requests OK, `key`/`session` propagated through 3 levels):
  - 3D Tiles **1.0** with the `3DTILES_content_gltf` tileset extension: `.glb` content, no b3dm wrapper.
  - glTF 2.0 leaves whose only **required** extension is `KHR_materials_unlit`. **No Draco** on this path, so the WinTAK prototype's Draco decoding isn't needed.
  - The root tileset is about 64 KB, with deep `REPLACE` hierarchies and oriented-box bounding volumes.
  - The root `geometricError` is `1e+100`, an "always refine" sentinel.
  - Credits to show: Google and Cesium ion logos, plus per-tile copyrights (e.g. "Google; Data SIO, NOAA, U.S. Navy, NGA, GEBCO").
- **Findings from the `jastman-fix-atak-google-tiles` branch (ATAK 5.7)**:
  - Handing a tileset URL straight to `ModelImporter` did not stream. What worked was importing a small `{"ModelInfo":{"uri","name","type":"Cesium3DTiles"}}` JSON file as content type `Streaming Mesh Configuration` with MIME type `application/json`, the same path as ATAK's own "Add Content from Service".
  - ATAK's renderer would not traverse past the `1e+100` root, so the branch clamped it. The core now supports this as an optional `geometricError` ceiling in `TilesetUriRewriter`.
  - Neither change has a recorded on-device render result yet.
- **WinTAK finding**: in WinTAK's native `SceneLayer`, hierarchy traversal worked but leaf content failed inside `Cesium3DTilesSceneInfoSpi`, even after proxy-side transcoding. See `docs/TAK_Product_Team_Google_3D_Tiles_Writeup.docx`. ATAK 5.7's 3D Tiles path may differ (cesium-native in the TAK kernel), but that's exactly what has to be proven on a device.
- **Display obligations**:
  - Show the Google logo, plus the copyright strings of the visible tiles ordered by frequency. The core provides `TileContent` and `AttributionCollector` for this.
  - Respect the Google and ion terms on caching. Avoid bulk prefetch and persistent offline caches of Google tiles unless the terms allow it.

### S1 result (2026-10-13): Google Photorealistic 3D Tiles render natively in ATAK 5.7

Tested on a Samsung SM-X730 (Android 16, ATAK-CIV 5.7.0.10) with path (a), direct.

- **How it loaded:** the plugin wrote a Streaming Mesh Configuration and
  imported it. ATAK's `StreamingMeshServiceImportUtil` then re-imported
  Google's root URL as a "3D Model", and `ModelImporter` accepted it.
- **What ATAK handled on its own:** it carried `key`/`session` to child tiles,
  refined past the `1e+100` root, and drew the glTF leaves. No proxy,
  transcoding, or `geometricError` clamp was needed.
- **Logs:** clean apart from ATAK probing other model formats. No credentials
  appeared in logcat.

Open items this exposed:

1. **Two ground surfaces.** ATAK's basemap, draped on its own terrain (DTED),
   intersects Google's photogrammetry ground and shows through it. The fix
   hides or fades ATAK's surface while photorealistic tiles are shown, as
   CesiumJS does with `globe.show = false`. The device script now scans the
   SDK for the renderer and terrain API, and grabs a screenshot.
2. **Attribution.** The Google logo and per-tile credits are not displayed
   yet. This is required before any demo outside the team.
3. **Credential persistence.** Path (a) leaves ion's Google key in the import
   file and ATAK's model database. ion-hosted assets also carry endpoint
   tokens that expire in about an hour. The loopback proxy (M5) is now
   justified by credential hygiene and token refresh, not by rendering.

### How we de-risk it

1. **Now, on the Mac (no ATAK needed):** run `ion-probe google` (see `core/README.md`). It confirms the token can stream asset 2275207 and records the 3D Tiles version, formats, and **required glTF extensions** ATAK must support.
2. **S1, right after the M2 skeleton (before the asset browser):** a debug action loads asset 2275207 into ATAK two ways:
   - **(a) direct:** hand `ModelImporter` the root URL with its key. This works only if ATAK's loader inherits query parameters the way cesium-native does.
   - **(b) proxied:** route through the loopback proxy, which uses `TilesetUriRewriter` plus `deriveChild` and injects credentials per request.

   Use the Streaming Mesh Configuration import path above in both cases, and apply the `geometricError` ceiling. Record what renders, with logcat from the TAK engine. If leaves still fail to draw, a cheap next test is having the proxy drop `KHR_materials_unlit` from each GLB's `extensionsRequired` (leaving it in `extensionsUsed`): a strict glTF loader rejects tiles that require an extension it doesn't know, while unlit is safe to ignore.
3. **If ATAK 5.7 can't render Google leaves:** escalate to the TAK product team immediately, with the probe output and logcat (an update to the existing writeup), rather than spending weeks on proxy transcoding as was done for WinTAK. Imagery and terrain milestones continue in parallel.

## 5. Phase 1 milestones (ATAK on Mac)

| # | Milestone | Testable on Mac without a device? |
|---|---|---|
| M0 ✅ | **Repo hygiene**: removed committed `bin/`, `obj/`, `packages/` (117 files, ~72 MB); moved the web prototype, WinTAK spike, and WebView ATAK plugin to `legacy/`; CI for core tests | yes |
| M1 ✅ | **`core/ion-client-java`**: Cloud/Self-Hosted config and API discovery; REST client; endpoint model, including Google P3DT; endpoint-token cache; redaction; tile URL derivation and tileset rewriting; glTF credit extraction. 149 tests, 90 of them from `spec/ion-contract`. Plus the `ion-probe` command-line tool | yes (`cd core && ./gradlew test`) |
| M2 ✅ | **ATAK skeleton** (`atak/`, built and run on an SM-X730 with ATAK 5.7): `:app` plus `:ion-client-java` included from `core/`; AGP 9.3.1 / Gradle 9.5.0; lifecycle, toolbar, Tool Preferences, Keystore-encrypted token store, "Test connection", and the S1 path (a) Google diagnostic. Compile-checked here against Android API 34 and ATAK API stubs; 9 plugin unit tests pass | build needs the ATAK SDK; run needs ATAK |
| M3 🔨 | **Asset browser**: list, search, paging, and loaded/unloaded state (written; type filter and team picker to follow) | needs device or emulator |
| M4 | **Device spikes** (below). I'll ship an instrumented debug build and a checklist; you run it on the tablet or emulator. **S1 (Google P3DT) runs right after M2.** | needs device |
| M5 🔨 | **Loopback proxy and 3D Tiles**: load, unload, survive an ATAK restart, endpoint-token refresh (proxy written and unit-tested; ion 3D Tiles load through it) | proxy unit-tested on JVM; render on device |
| M6 🔨 | **Imagery**: ion TMS, Bing, Google 2D as ATAK mobile map sources (written) | device |
| M7 | **Terrain**: based on S3. The 5.7 SDK has native quantized-mesh support (`com.atakmap.map.formats.quantizedmesh.QMESourceLayer`, `QMESourceManager`), to be scanned next | device |
| M8 | **Docs**: Mac build guide, test-plan results, known limitations, TPP release notes | – |

### Device spikes that decide M5–M7

These need the real ATAK SDK and a device. I can't run ATAK in this environment.

- **S1: 3D Tiles in ATAK 5.7, starting with Google P3DT (§4).** Does `ModelImporter` streaming handle these directly through the proxy, with no transcoding?
  - 3D Tiles 1.1 (implicit tiling, `.glb` content)
  - Draco
  - KTX2
  - Google Photorealistic 3D Tiles

  This decides whether any of the server's transcoding logic gets ported.
- **S2: Imagery.** Does a custom map source pointing at `http://127.0.0.1` load, persist across restart, and drape over terrain? Does ATAK draw imagery on 3D Tiles? It probably doesn't, and if so that becomes a documented limitation.
- **S3: Terrain.** Does the 5.x SDK expose an `ElevationSource`/`ElevationSourceManager` path that a plugin can feed? Options, from best to worst:
  - (a) use a native quantized-mesh source if the kernel has one
  - (b) write a Java `ElevationSource` that decodes quantized-mesh tiles from the proxy
  - (c) keep the current behavior: list the asset but report "terrain not supported on this ATAK version"

### Phase 1 test plan (summary; full matrix delivered with M8)

| Area | Cases |
|---|---|
| **Connection** | Cloud with a valid, expired, or revoked token, and a token missing `assets:list` / `assets:read` scopes. Self-hosted via `config.json` discovery, via manual API override, behind a private CA, and with the server unreachable. Switching modes clears cached state. |
| **Discovery** | Paging past 100 assets; search; team switching; a self-hosted server without `/teams`; Asset Depot / global assets added to the account (World Terrain 1, Bing 2/3/4, OSM Buildings 96188, Google P3DT 2275207). |
| **Imagery** | ion-hosted TMS, Bing, Google 2D. Load, unload, reload, ATAK restart, and more than an hour of use (token refresh). |
| **Terrain** | ion quantized-mesh (asset 1 and a custom upload), plus imagery draped on it. |
| **3D Tiles** | Photogrammetry (1.0 b3dm), 1.1 implicit/glb, Draco, KTX2, OSM Buildings, Google P3DT (with attribution shown). |
| **Security** | No tokens in logcat (`adb logcat \| grep -iE 'access_token\|key='`), none in `/sdcard/atak/**`, none in an ATAK preference export. The proxy rejects requests without the secret. |
| **Lifecycle** | Enable/disable the plugin in Plugin Manager, rotate the screen, background/foreground ATAK, kill and restart ATAK. |

Core unit tests run with `./gradlew test` on the Mac. Device tests are a manual checklist with expected results.

## 6. Phase 2 outline (VR-TAK on Windows), after Phase 1 sign-off

1. **First, confirm what the VR-TAK SDK (UE 5.3.2) allows.** Does it already bundle Cesium for Unreal, and can a VR-TAK plugin add Unreal plugins?
   - If Cesium for Unreal is available, rendering, terrain, and imagery draping on terrain *and* on 3D Tiles come from `ACesium3DTileset` and `UCesiumIonRasterOverlay`. Self-hosted ion works through the `UCesiumIonServer` asset.
   - If not, we evaluate adding Cesium for Unreal (Apache-2.0) to the plugin.
2. **C++ ion client**: use cesium-native's `CesiumIonClient` (bundled with Cesium for Unreal; it already does `config.json` discovery, `/me`, `/assets`, and `/defaults`), wrapped in the same `IonClient` interface. Add a thin C++ port only where it differs, such as teams and external endpoint types. It must pass the same `spec/ion-contract` fixtures as the Java core.
3. VR UI, settings persistence (Windows DPAPI / UE secure storage for the token), PC setup guide, and a test plan in the VR preview / simulator.

## 7. Manual steps only you can do

- Download from tak.gov the **ATAK-CIV SDK** matching your device's ATAK version, plus the matching `atak.apk` for development (SDK-signed debug plugins only load into an SDK-signed ATAK).
- For release builds, submit to the **TAK.gov Third-Party Plugin pipeline** for signing, so the plugin loads in Play Store or official ATAK builds.
- Create a least-privilege ion token (`assets:list`, `assets:read`, optionally `profile:read`) for testing. For self-hosted, provide a test server URL and CA if it's private.
- Phase 2: get the VR-TAK SDK from TAK-Product-Center/VR and install UE 5.3.2 and VS 2022 on the PC.

## 8. Decisions (agreed)

1. **Shared core**: a shared contract (`spec/ion-contract`) with a pure-Java core for ATAK, and cesium-native `CesiumIonClient` (or a thin C++ port) for VR-TAK. No JNI.
2. **Authentication**: Cesium ion access tokens only for Phase 1. Native OAuth/PKCE sign-in can come later.
3. **Legacy code**: moved to `legacy/`, later removed (it is in the git history). Committed build outputs and WinTAK SDK DLLs are removed from the tip. They remain in git history; purging them needs a force-push and is still the repository owner's call.
4. **UI**: no CesiumJS preview. Assets render only through ATAK's own engine.
5. **ATAK version**: ATAK-CIV **5.7** (plugin API `com.atakmap.app@5.7.0.CIV`, minSdk 26).
6. **Key requirement**: Google Photorealistic 3D Tiles must stream from ion and render in TAK (§4).
