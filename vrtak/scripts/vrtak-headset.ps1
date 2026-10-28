<#
VR-TAK on this PC with the headset enabled (no -nohmd), for testing the Cesium ion plugin in
the Quest 3: the Self-Hosted server's content (terrain 9, imagery 12, 3D Tiles 21 and 11) in
VR-TAK's world, 15 minutes. Start Quest Link (headset connected and worn) first; once VR-TAK
shows its world, "Switch To VR" in its ribbon enters VR. The log (published with the report)
says every 5 s whether the view is stereoscopic and whether the panel is a screen widget.
#>
param([string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK')
& "$PSScriptRoot\vrtak-launch.ps1" -VrTak $VrTak -Headset -Load '9,12,21,11' -EnterWorld 20 -ScreenshotAt 840 -QuitAfter 900 -Expect @(
    'LogCesiumIonVrtak: XR system')
