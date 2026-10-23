<#
Diagnostic: VR-TAK with the Self-Hosted Philadelphia terrain (9), the aerial
imagery over it (12) and the elevated test box (21), with every Cesium
layer raised 60 m (-CesiumIonRaise). If they show raised but not at their
true height, VR-TAK's own ground hides them.
#>
param([string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK')
& "$PSScriptRoot\vrtak-launch.ps1" -VrTak $VrTak -Load '9,12,21' -ExtraArgs @('-CesiumIonRaise=60') -Expect @(
    'LogCesiumIonVrtak: Test: Cesium layers raised 60 m',
    "LogCesiumIonVrtak: Added Mapzen.+ to VR-TAK's world",
    'LogCesiumIonVrtak: Added USGS.+ over the Cesium terrain')
