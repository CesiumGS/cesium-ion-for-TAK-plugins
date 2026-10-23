<#
VR-TAK on Cesium ion Cloud (saved sign-in) with Cesium World Terrain (1) and
Bing Maps Aerial (2) draped over it, at Philadelphia: worldwide Cesium terrain
and imagery replacing VR-TAK's own map while they are loaded.
#>
param([string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK')
& "$PSScriptRoot\vrtak-launch.ps1" -VrTak $VrTak -Mode cloud -Load '1,2' -ScreenshotAt 240 -QuitAfter 420 -Expect @(
    "LogCesiumIonVrtak: VR-TAK's own terrain is off while Cesium terrain is in the world",
    "LogCesiumIonVrtak: Added .+ over the Cesium terrain")
