<#
VR-TAK with the Cesium ion plugin on the Self-Hosted server here, with every
asset type VR-TAK shows: the Philadelphia terrain (9), the aerial imagery
draped over it (12), and the elevated test box (21) and test box (11) as
3D Tiles, added in that order. The plugin logs each layer's load progress
and where its tiles are every 30 s (Diagnostics). With Cesium terrain in the
world, the plugin turns VR-TAK's own map off, as VR-TAK's terrain setting does.
#>
param([string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK')
& "$PSScriptRoot\vrtak-launch.ps1" -VrTak $VrTak -Load '9,12,21,11' -Expect @(
    'LogCesiumIonVrtak: Diagnostics: #',
    "LogCesiumIonVrtak: VR-TAK's own terrain is off while Cesium terrain is in the world",
    "LogCesiumIonVrtak: Added Mapzen.+ to VR-TAK's world",
    'LogCesiumIonVrtak: Added USGS.+ over the Cesium terrain')
