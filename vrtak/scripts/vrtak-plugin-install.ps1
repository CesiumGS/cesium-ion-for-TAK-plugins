<#
Copies the Cesium ion plugin for VR-TAK (vrtak/plugin/VTAK_CesiumIon) into
VR-TAK's Game\Plugins (approved by the PC's owner), replacing an earlier
copy, then reports the VR-TAK build's progress. The plugin is compiled by the
next build of GameEditor (vrtak-engine-build). -NoStatus skips that report
(vrtak-engine-fix installs, then restarts the build itself).
#>
param([string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK', [switch]$NoStatus)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\common.ps1"
$source = (Resolve-Path "$PSScriptRoot\..\plugin\VTAK_CesiumIon").Path
$target = Join-Path $VrTak 'Game\Plugins\VTAK_CesiumIon'
Section 'Install VTAK_CesiumIon'
# Keep build products (Binaries, Intermediate) of an earlier copy; replace the rest.
foreach ($item in 'VTAK_CesiumIon.uplugin', 'Source', 'Resources') {
    Remove-Item -Recurse -Force (Join-Path $target $item) -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item -Recurse -Force (Join-Path $source '*') $target
Get-ChildItem $target -Recurse -File | Where-Object { $_.FullName -notmatch '\\(Binaries|Intermediate)\\' } |
    ForEach-Object { '   {0}  {1:N0} B' -f $_.FullName.Substring($target.Length), $_.Length }

Section 'Disk clean-up'
# The binary Cesium for Unreal release is not needed: VR-TAK builds the source release.
foreach ($path in "$env:USERPROFILE\cesium-vrtak\CesiumHostBin", "$env:USERPROFILE\cesium-vrtak\downloads\CesiumForUnreal-53-v2.17.0.zip") {
    if (Test-Path $path) { Remove-Item -Recurse -Force $path; "Removed $path" }
}

if (-not $NoStatus) { & "$PSScriptRoot\vrtak-engine-build.ps1" }
