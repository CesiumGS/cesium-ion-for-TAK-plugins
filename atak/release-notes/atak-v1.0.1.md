**Cesium ion for ATAK 1.0.1**: the first release signed by the TAK Product Center
through TAK.gov's Third-Party Pipeline, so it loads in **standard ATAK-CIV 5.7**
(Play Store or tak.gov). Install steps:
[atak/README.md › Install](../../blob/atak-v1.0.1/atak/README.md#install).

**Needs ATAK-CIV 5.7** (the CIV flavor). Other ATAK versions or flavors (MIL, GOV) will not load it.

## What it does

- **Sign in with Cesium ion** in the device browser (no tokens to copy); choose
  "Log in as a team" on ion's page to use a team's assets.
- **Cesium ion Cloud or Self-Hosted** (single-user servers; server and API URL in Settings).
- **Your assets** with search and type filters, and **Add to map** for:
  - 3D Tiles, including Google Photorealistic 3D Tiles and Cesium OSM Buildings
  - imagery (ion-hosted, Bing Maps)
  - terrain (Cesium World Terrain and ion terrain, as ATAK's elevation)
  - GeoJSON and KML (as ATAK file overlays)
- **On your map** says where ATAK shows each layer, which is also where to hide it.
- Google and Cesium credits stay on the map while content from them is shown.

## Tested

- This signed APK in standard ATAK-CIV 5.7.0.16 on a Samsung Galaxy Tab (Android 16):
  loads, signs in, lists assets, adds Google Photorealistic 3D Tiles, no crashes, and no
  freeze while the pane opens and closes over loading terrain.
- The same code passed the full automatic regression on the tablet (sign-in, asset
  list, OSM Buildings, Google Photorealistic 3D Tiles, Bing imagery, terrain, removal,
  no crashes or freezes), and all five content types against ion Self-Hosted 1.10.
- TAK.gov's security scan (Fortify) and dependency check: findings and how each is
  handled are in [atak/SECURITY-NOTES.md](../../blob/atak-v1.0.1/atak/SECURITY-NOTES.md).

## Changes since the 1.0.0 build

1.0.0 was signed by TAK.gov but not released: in standard ATAK-CIV 5.7.0.16
it could freeze ATAK ("ATAK isn't responding") when the Cesium ion pane was
opened while Cesium terrain was loading. The hang was in ATAK's own terrain
code, reached when ATAK looks up the elevation of a new map item. In 1.0.1,
those quick lookups use ATAK's other elevation data, while the 3D map still
shows Cesium terrain. 1.0.1 also rebuilds ATAK's terrain far less often and
restores Cesium terrain 30 seconds after ATAK starts instead of at once.

## Known limitations

- The elevation ATAK gives a newly placed point comes from ATAK's other
  elevation data (for example DTED), not from Cesium terrain.

- No offline use yet: layers stream from Cesium ion while connected.
- Self-Hosted: single-user servers; multi-user servers with sign-in come later.
