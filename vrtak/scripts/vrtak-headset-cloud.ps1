<#
VR-TAK with the headset enabled (no -nohmd) and Google Photorealistic 3D Tiles from Cesium ion
Cloud (the saved sign-in, DPAPI, this Windows user; without one the panel shows its sign-in
card and ion's page opens in the browser), 15 minutes. Start Quest Link / Air Link (headset
connected and worn) first; once VR-TAK shows its world, "Switch To VR" in its ribbon enters VR.
The Self-Hosted server's own 3D Tiles are only 3 m test boxes, so Google's 3D city is the
way to see 3D data in the headset.
#>
# QuitAfter: seconds until VR-TAK closes itself (3600 for an hour of hands-on use).
param([string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK', [int]$QuitAfter = 900)
& "$PSScriptRoot\vrtak-launch.ps1" -VrTak $VrTak -Headset -Mode cloud -Load '2275207' -EnterWorld 20 -ScreenshotAt ([Math]::Max(60, $QuitAfter - 60)) -QuitAfter $QuitAfter -Expect @(
    'LogCesiumIonVrtak: XR system')
