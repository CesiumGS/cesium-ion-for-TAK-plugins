<#
Stops the detached VR-TAK editor build (vrtak-engine-build) before C: fills
up: a full disk leaves half-written object files. Finished work is kept, so
vrtak-engine-build -Restart (or vrtak-engine-fix) continues from here.
Then lists, read-only, what uses C:, for the owner to decide where the rest
of the build may go.
#>
param([string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK')
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\common.ps1"
$root = Join-Path $env:USERPROFILE 'cesium-vrtak'
$state = Join-Path $root 'engine-build.pid'
$log = Join-Path $root 'engine-build.log'

Section 'Stop the build'
if (Test-Path $state) {
    $buildPid = [int](Get-Content $state -TotalCount 1)
    if (Get-Process -Id $buildPid -ErrorAction SilentlyContinue) {
        "Stopping build $buildPid."
        & taskkill.exe /PID $buildPid /T /F 2>&1 | Select-Object -Last 1
        Start-Sleep -Seconds 15
    } else { "Build $buildPid is not running." }
} else { 'No build state file.' }
Get-Process -Name 'cl', 'link', 'UnrealBuildTool', 'dotnet' -ErrorAction SilentlyContinue |
    ForEach-Object { '   still running: {0} ({1})' -f $_.Name, $_.Id }
$steps = Get-Content $log -ErrorAction SilentlyContinue | Select-String -Pattern '^\[(\d+)/(\d+)\]' | Select-Object -Last 1
if ($steps) { "Last step: $($steps.Line.Trim())" }

Section 'Free space'
Get-PSDrive C, F -ErrorAction SilentlyContinue | ForEach-Object { '{0}: {1:N1} GB free of {2:N0} GB' -f $_.Name, ($_.Free / 1GB), (($_.Used + $_.Free) / 1GB) }

function Show-Size([string]$dir) {
    if (-not (Test-Path -LiteralPath $dir)) { return }
    $size = (Get-ChildItem -LiteralPath $dir -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    '{0,8:N1} GB  {1}' -f ($size / 1GB), $dir
}

Section 'What uses C: (read-only)'
'-- VR-TAK'
Get-ChildItem -LiteralPath $VrTak -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object { Show-Size $_.FullName }
foreach ($dir in 'Engine\Intermediate', 'Engine\Binaries', 'Engine\Plugins', 'Engine\DerivedDataCache', 'Game\Intermediate', 'Game\Binaries', 'Game\DerivedDataCache', 'Game\Saved') {
    Show-Size (Join-Path $VrTak $dir)
}
'-- Engine\Binaries\Win64 debug symbols'
$pdb = (Get-ChildItem -LiteralPath (Join-Path $VrTak 'Engine\Binaries\Win64') -Filter *.pdb -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum)
'{0,8:N1} GB  {1} .pdb files' -f ($pdb.Sum / 1GB), $pdb.Count
'-- Caches and this work'
foreach ($dir in "$env:LOCALAPPDATA\UnrealEngine", "$env:LOCALAPPDATA\UnrealBuildTool", "$env:LOCALAPPDATA\Temp", "$env:LOCALAPPDATA\Android",
        "$env:USERPROFILE\.android", "$env:USERPROFILE\cesium-vrtak", "$env:USERPROFILE\.nuget", "$env:LOCALAPPDATA\Microsoft\VisualStudio") {
    Show-Size $dir
}
'-- Largest folders under C:\ (top level)'
Get-ChildItem -LiteralPath 'C:\' -Directory -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notin 'Windows', 'Program Files', 'Users', '$Recycle.Bin', 'System Volume Information' } |
    ForEach-Object { Show-Size $_.FullName }
