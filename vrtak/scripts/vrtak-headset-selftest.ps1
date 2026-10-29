<#
VR-TAK with the headset enabled and the Self-Hosted server's content, for runs with nobody in
the headset: ninety seconds after start the plugin exercises its own panel (resize limits,
bring to me, grab pointer discovery, View on each 3D Tiles asset) and logs "Self-test:" lines
with PASS / FAIL. The report is the result. Three minutes, then VR-TAK closes itself.
#>
param([string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK', [int]$QuitAfter = 240)
& "$PSScriptRoot\vrtak-launch.ps1" -VrTak $VrTak -Headset -Load '9,12,21,11' -EnterWorld 20 -ScreenshotAt ([Math]::Max(60, $QuitAfter - 60)) -QuitAfter $QuitAfter -ExtraArgs @('-CesiumIonSelfTest=90') -Expect @(
    'LogCesiumIonVrtak: XR system', 'Self-test: done', 'Self-test: resize limits .*PASS', 'Self-test: panel brought to .*PASS')
