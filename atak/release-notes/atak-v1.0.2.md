**Cesium ion for ATAK 1.0.2**: signed by the TAK Product Center through TAK.gov's
Third-Party Pipeline, so it loads in **standard ATAK-CIV 5.7** (tak.gov). Install steps:
[atak/README.md › Install](../../blob/atak-v1.0.2/atak/README.md#install).

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
  loads, signs in, lists assets, adds Google Photorealistic 3D Tiles, no crashes. The
  release is only published when these checks pass.
- The same code passed the full automatic regression on the tablet (sign-in, asset
  list, OSM Buildings, Google Photorealistic 3D Tiles, Bing imagery, terrain, removal,
  no crashes or freezes), and ion-hosted Sentinel-2 and Blue Marble imagery: the map
  you had before comes back when the imagery is removed.
- TAK.gov's security scan (Fortify) and dependency check: findings and how each is
  handled are in [atak/SECURITY-NOTES.md](../../blob/atak-v1.0.2/atak/SECURITY-NOTES.md).

## Changes since 1.0.1

Found by a tester: adding ion-hosted imagery (Sentinel-2, Blue Marble) replaced their
online map with a flat colour, and after removing it the map stayed blank until ATAK was
restarted.

- **Removing Cesium ion imagery brings back the map you had before** (an online source such
  as ESRI World Topo, or any other map source). 1.0.1 returned to automatic selection,
  which could leave the removed source on screen.
- **Low-resolution imagery now says so.** Blue Marble's finest detail is about 300 m per
  pixel, so zoomed in close ATAK stretches it into flat colour. The pane notes this when
  you add imagery coarser than 30 m per pixel: zoom out to see it.

## Known limitations

- The elevation ATAK gives a newly placed point comes from ATAK's other
  elevation data (for example DTED), not from Cesium terrain.

- No offline use yet: layers stream from Cesium ion while connected.
- Self-Hosted: single-user servers; multi-user servers with sign-in come later.
