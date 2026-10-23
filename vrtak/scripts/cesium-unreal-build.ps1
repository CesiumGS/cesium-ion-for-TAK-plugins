<#
Builds Cesium for Unreal (the UE 5.3 source release) with VR-TAK's engine,
in a small host project under the user profile:
  %USERPROFILE%\cesium-vrtak\downloads   the release zip
  %USERPROFILE%\cesium-vrtak\CesiumHost   host project; Plugins\CesiumForUnreal
Approved by the PC's owner. Nothing is added under Program Files: VR-TAK's
engine is only used to compile (-NoEngineChanges makes UBT fail rather than
rebuild engine modules), and the report lists any engine files the build
touched. Rerunning resumes: the download and extraction are reused.
#>
param(
    [string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK',
    [string]$Version = 'v2.17.0',
    [int]$BuildMinutes = 30
)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\common.ps1"
$root = Join-Path $env:USERPROFILE 'cesium-vrtak'
$downloads = Join-Path $root 'downloads'
$host_ = Join-Path $root 'CesiumHost'
New-Item -ItemType Directory -Force -Path $downloads, $host_ | Out-Null

Section "Cesium for Unreal $Version for UE 5.3 (source release)"
$zipName = "CesiumForUnreal-53-SourceOnly-$Version.zip"
$zip = Join-Path $downloads $zipName
if (-not (Test-Path $zip)) {
    $url = "https://github.com/CesiumGS/cesium-unreal/releases/download/$Version/$zipName"
    "Downloading $url"
    $ProgressPreference = 'SilentlyContinue'
    try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 1200 -OutFile "$zip.part" $url; Move-Item "$zip.part" $zip }
    catch { "Download failed: $($_.Exception.Message)"; return }
}
'{0}: {1:N0} MB' -f $zipName, ((Get-Item $zip).Length / 1MB)

$plugins = Join-Path $host_ 'Plugins'
$uplugin = Get-ChildItem $plugins -Recurse -Filter CesiumForUnreal.uplugin -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $uplugin) {
    "Extracting to $plugins"
    New-Item -ItemType Directory -Force -Path $plugins | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $plugins)
    $uplugin = Get-ChildItem $plugins -Recurse -Filter CesiumForUnreal.uplugin | Select-Object -First 1
}
if (-not $uplugin) { 'No CesiumForUnreal.uplugin in the release.'; return }
"Plugin: $($uplugin.FullName)"
Get-Content $uplugin.FullName -TotalCount 14
'Plugin folders: ' + ((Get-ChildItem $uplugin.DirectoryName -Directory | Select-Object -ExpandProperty Name) -join ', ')
$third = Get-ChildItem $uplugin.DirectoryName -Recurse -Filter *.lib -ErrorAction SilentlyContinue
"Prebuilt cesium-native libraries: $($third.Count) .lib files"

Section 'Host project'
Copy-Item -Recurse -Force (Join-Path $PSScriptRoot '..\host\*') $host_
Get-ChildItem $host_ -Recurse -File -Include *.uproject, *.cs, *.cpp, *.h | ForEach-Object { $_.FullName.Substring($host_.Length) }
$uproject = Join-Path $host_ 'CesiumHost.uproject'

Section 'Build (VR-TAK engine, Development Editor, Win64)'
$build = Join-Path $VrTak 'Engine\Build\BatchFiles\Build.bat'
$log = Join-Path $root 'build.log'
$started = Get-Date
$args_ = @('CesiumHostEditor', 'Win64', 'Development', "-Project=`"$uproject`"", '-WaitMutex', '-NoHotReload', '-NoEngineChanges')
"$build $($args_ -join ' ')"
$process = Start-Process -FilePath $build -ArgumentList $args_ -RedirectStandardOutput $log -RedirectStandardError "$log.err" -NoNewWindow -PassThru
$null = $process.Handle  # keeps the exit code readable after the process ends
if (-not $process.WaitForExit($BuildMinutes * 60 * 1000)) {
    "Still building after $BuildMinutes minutes; stopping it (rerun to continue: UBT resumes)."
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id)" | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
}
"Exit code: $($process.ExitCode)  ($([int]((Get-Date) - $started).TotalMinutes) min)"
'-- errors and warnings (first 60)'
Get-Content $log, "$log.err" -ErrorAction SilentlyContinue | Select-String -Pattern ': error |: fatal error|error MSB|ERROR:|Unable to|-NoEngineChanges|is not compatible|warning C4' |
    Select-Object -First 60 | ForEach-Object { $_.Line.Trim() } | Mask
'-- last 40 lines'
Get-Content $log -Tail 40 -ErrorAction SilentlyContinue | Mask

Section 'Results'
Get-ChildItem (Join-Path $uplugin.DirectoryName 'Binaries\Win64'), (Join-Path $host_ 'Binaries\Win64') -File -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -ge $started -or $_.Extension -eq '.dll' } |
    ForEach-Object { '{0}  {1:N0} KB  {2}' -f $_.Name, ($_.Length / 1KB), $_.LastWriteTime }
'-- engine files written during the build (should be none, or only UBT caches)'
foreach ($dir in 'Engine\Binaries\Win64', 'Engine\Intermediate\Build') {
    $changed = Get-ChildItem (Join-Path $VrTak $dir) -Recurse -Depth 3 -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $started } | Select-Object -First 15
    foreach ($f in $changed) { "   $($f.FullName.Substring($VrTak.Length))" }
}
