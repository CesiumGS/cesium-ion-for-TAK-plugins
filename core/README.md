# Shared Cesium ion core (Java)

`ion-client-java` is the platform-neutral Cesium ion layer for the TAK
plugins. It has no Android or TAK dependencies, so it builds and tests on any
machine with JDK 17 through 25 (Gradle 9.8). The ATAK plugin (Phase 1, milestone M2) compiles
this module directly. VR-TAK (Phase 2) mirrors it in C++ against the same
[`spec/ion-contract`](../spec/ion-contract/README.md) cases.

`ion-probe` is a command-line tool built on the core. Use it to check tokens,
Self-Hosted servers, and 3D Tiles streaming (including Google Photorealistic
3D Tiles) from a desktop, without ATAK.

## Build and test on a Mac

Android Studio ships a JDK, so you don't need to install one. Recent Android Studio versions bundle JDK 25, which this build supports:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd core
./gradlew test
```

Test reports are written to `ion-client-java/build/reports/tests/test/index.html`.

## Probe ion from the command line

```bash
cd core
./gradlew :ion-probe:installDist
read -s CESIUM_ION_TOKEN && export CESIUM_ION_TOKEN   # paste the token; it is not echoed
ion-probe/build/install/ion-probe/bin/ion-probe check
ion-probe/build/install/ion-probe/bin/ion-probe assets --all
ion-probe/build/install/ion-probe/bin/ion-probe endpoint 2275207
ion-probe/build/install/ion-probe/bin/ion-probe google
```

- `google` walks the Google Photorealistic 3D Tiles hierarchy the way a
  renderer would. It applies `key` and `session` per request, then reports the
  3D Tiles version, content formats, **required glTF extensions**, and the tile
  credits that must be shown. The ATAK rendering spike (S1) checks ATAK 5.7
  against that list.
- For Self-Hosted, add `--server https://ion.example.mil`. Add `--api URL` if
  `config.json` discovery fails, `--single-user` for a server that runs
  without tokens, and `--allow-http` only for an isolated lab server.
- The probe never accepts the token as an argument and never prints it.

## Package layout (`com.cesium.tak.ion`)

| Package | Contents |
|---|---|
| (root) | `IonClient` (REST calls), `IonServer` (Cloud / Self-Hosted), `ApiUrlResolver`, `EndpointParser`, `EndpointCache` (refreshes short-lived endpoint tokens), `IonException` |
| `model` | `IonAsset`, `AssetType`, `AssetStatus`, `AssetEndpoint` (variants: `IonHosted`, `External3DTiles`, `BingImagery`, `Google2DImagery`, `ExternalTerrain`, `Unsupported`), `Attribution`, `Defaults`, `Team`, `Profile` |
| `tiles` | `TileUrls` (RFC 3986 resolution, query inheritance, credential splitting), `TilesetUriRewriter` (for the M5 tile proxy), `TileContent` (GLB/B3DM header and copyright), `AttributionCollector` (Google credit line), `TmsMetadata` |
| `http` | `HttpTransport` interface, plus `UrlConnectionTransport` (manual redirects, size limits, gzip) |
| `json` | Minimal JSON reader. Deliberately not Gson or org.json, which would collide with ATAK's own copies |
| `util` | `Secret` (credential that never prints), `Redactor`, `Jwt` (reads `exp` only) |

## Constraints this module keeps

- **Java 8 APIs only** (`--release 8`). Everything it uses exists on Android
  API 26, the minSdk for ATAK 5.7.
- **No runtime dependencies.** Inside ATAK, a plugin's copy of a library can
  resolve to ATAK's own copy at a different version.
- **Credentials:**
  - They live only in `Secret` values and in the request being sent.
  - Endpoint URLs are stored without them.
  - `Authorization` is dropped on cross-origin redirects, and HTTPS-to-HTTP
    redirects are refused.
  - Tile credentials are applied only to the endpoint's own origin.
- **Blocking calls.** Platforms run them off the UI thread.
