First test build of **Cesium ion for ATAK**, for testers. Install steps:
[README › Install for testers](https://github.com/CesiumGS/cesium-ion-for-TAK-plugins/blob/main/atak/README.md).

**Needs ATAK-CIV 5.7.0 developer build** (the `atak.apk` in the ATAK-CIV 5.7.0 SDK from tak.gov).
ATAK from the Play Store or another release build will not load this plugin until it is
signed by the TAK Product Center.

## What it does

- **Sign in with Cesium ion** in the device browser (no tokens to copy); choose
  "Log in as a team" on ion's page to use a team's assets. Pasting an access token
  (`assets:list`, `assets:read`) also works.
- **Cesium ion Cloud or Self-Hosted** (single-user servers; server and API URL in Settings).
- **Your assets** with search and type filters, and **Add to map** for:
  - 3D Tiles, including Google Photorealistic 3D Tiles and Cesium OSM Buildings
  - imagery (ion-hosted, Bing Maps)
  - terrain (Cesium World Terrain and ion terrain, as ATAK's elevation)
  - GeoJSON and KML (as ATAK file overlays)
- **On your map** says where ATAK shows each layer, which is also where to hide it.
- The status at the top shows who is signed in (hold or hover it); the gear opens Settings.

## Tested

The core features passed the automatic regression checks on a Samsung Galaxy Tab
(Android 16) before this build was published: sign-in, asset list, OSM Buildings,
Google Photorealistic 3D Tiles, Bing imagery, terrain, removal, no crashes or freezes.
All five content types were also checked against ion Self-Hosted 1.10.

## Known limitations

- Debug build: signed with the SDK's debug key, for the ATAK developer build only.
  It keeps test hooks that answer only to `adb` over USB debugging.
- Built for ATAK-CIV 5.7.0; other ATAK versions or flavors (MIL, GOV) will not load it.
- No offline use yet: layers stream from Cesium ion while connected.
