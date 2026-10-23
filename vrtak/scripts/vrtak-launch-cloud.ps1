<#
VR-TAK with the Cesium ion plugin on Cesium ion Cloud (vrtak-launch -Mode cloud):
Google Photorealistic 3D Tiles at Philadelphia with the saved sign-in. Without
saved access the panel shows its sign-in card and ion's sign-in page opens in
the PC's browser; the person signs in there (a test never sets a token), and
the access is saved for this Windows user (DPAPI) for later runs. Runs 15
minutes to leave time for that.
#>
param([string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK')
& "$PSScriptRoot\vrtak-launch.ps1" -VrTak $VrTak -Mode cloud -ScreenshotAt 240 -QuitAfter 900
