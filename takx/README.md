# Cesium ion for TAKX

**Planned.** TAKX is the successor to WinTAK, TAK's Windows app. A Cesium ion
plugin for TAKX will follow the next ATAK and VR-TAK features, with the same
sign-in, Cesium ion Cloud and Self-Hosted support, and the same design as the
[ATAK](../atak/README.md) and [VR-TAK](../vrtak/README.md) plugins. It will be
estimated when the work starts.

## Background: WinTAK

An earlier prototype found that WinTAK's built-in 3D engine cannot display
Google Photorealistic 3D Tiles: hierarchy traversal worked, but leaf content
failed to load. The write-up prepared for the TAK product team is in
[`docs/wintak-google-3d-tiles-findings.docx`](docs/wintak-google-3d-tiles-findings.docx),
with a summary in [`../docs/architecture-and-plan.md`](../docs/architecture-and-plan.md)
(section 4). Whether TAKX's 3D engine can display it decides how a TAKX
plugin shows 3D city content; imagery and terrain are expected to work either
way.
