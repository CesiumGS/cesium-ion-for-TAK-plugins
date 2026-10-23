<#
Builds VR-TAK's editor in place (approved by the PC's owner): the engine
layer, the Game module and its plugins, as the VR-TAK Plugin Developer
Guide's "build the engine once" step does in Visual Studio. Cesium for
Unreal's 5.3 source release goes into Game\Plugins first, so it is compiled
with the same engine.

The build takes longer than a runner job may, so the first run starts it
detached (RUNNER_TRACKING_ID cleared, so the runner does not stop it when
the job ends) and returns; later runs report progress and the result.
Log and state: %USERPROFILE%\cesium-vrtak\engine-build.*
#>
# 14.36 is the MSVC toolset UE 5.3 targets, and VS 2022 here has it.
# -BuildTarget builds another target the same way (e.g. ShaderCompileWorker, a program: no -Project).
param([string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK', [switch]$Restart,
    [string]$CompilerVersion = '14.36.32532', [string]$BuildTarget = '')
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\common.ps1"
$root = Join-Path $env:USERPROFILE 'cesium-vrtak'
$log = Join-Path $root 'engine-build.log'
$state = Join-Path $root 'engine-build.pid'
New-Item -ItemType Directory -Force -Path $root | Out-Null

function Show-Progress {
    Section 'Build progress'
    if (Test-Path $log) {
        $lines = Get-Content $log -ErrorAction SilentlyContinue
        $steps = $lines | Select-String -Pattern '^\[(\d+)/(\d+)\]' | Select-Object -Last 1
        "Log: $log ($($lines.Count) lines, updated $((Get-Item $log).LastWriteTime))"
        if ($steps) { "Latest step: $($steps.Line.Trim())" }
        $errors = @($lines | Select-String -Pattern ': error |: fatal error')
        $done = @($lines | Select-String -Pattern '^\[\d+/\d+\]')
        "Steps logged: $($done.Count); error lines: $($errors.Count) (Cesium for Unreal: $(@($errors | Where-Object { $_.Line -match 'CesiumForUnreal' }).Count), VTAK_CesiumIon: $(@($errors | Where-Object { $_.Line -match 'VTAK_CesiumIon' }).Count))"
        '-- result'
        $lines | Select-String -Pattern 'Result: |Total execution time|BUILD SUCCESSFUL|BUILD FAILED' | ForEach-Object { '   ' + $_.Line.Trim() }
        '-- folders with errors'
        $errors | ForEach-Object { if ($_.Line -match '\\(Source|Plugins)\\(.+?)\\(Private|Public|Classes|Internal)\\') { $Matches[2] } } |
            Group-Object | Sort-Object Count -Descending | ForEach-Object { '   {0,4}  {1}' -f $_.Count, $_.Name }
        '-- VTAK_CesiumIon steps'
        $lines | Select-String -Pattern 'CesiumIonVrtak|VTAK_CesiumIon' | Select-Object -First 15 | ForEach-Object { '   ' + $_.Line.Trim() } | Mask
        '-- errors (first 40)'
        $lines | Select-String -Pattern ': error |: fatal error|error MSB|ERROR:|BUILD FAILED|Unable to' |
            Select-Object -First 40 | ForEach-Object { $_.Line.Trim() } | Mask
        '-- last 25 lines'
        $lines | Select-Object -Last 25 | Mask
    } else { 'No build log yet.' }
    Get-PSDrive C | ForEach-Object { 'C: {0:N0} GB free' -f ($_.Free / 1GB) }
    '-- editor binaries'
    # (Launch is linked into UnrealEditor.exe in this modular build: no DLL of its own.)
    foreach ($f in 'Engine\Binaries\Win64\UnrealEditor.exe', 'Engine\Binaries\Win64\UnrealEditor-Core.dll',
            'Game\Plugins\CesiumForUnreal\Binaries\Win64\UnrealEditor-CesiumRuntime.dll',
            'Game\Plugins\CesiumForUnreal\Binaries\Win64\UnrealEditor-CesiumEditor.dll',
            'Game\Plugins\VTAK_CesiumIon\Binaries\Win64\UnrealEditor-VTAK_CesiumIon.dll') {
        $p = Join-Path $VrTak $f
        if (Test-Path -LiteralPath $p) { '   present {0}  {1}' -f $f, (Get-Item -LiteralPath $p).LastWriteTime } else { "   missing $f" }
    }
}

$argsFile = Join-Path $root 'engine-build.args'
# Windows SDK 10.0.26100's INFINITY trips C4756 ("overflow in constant
# arithmetic") in RenderGraphPrivate.cpp, an error under UE's warnings-as-errors;
# the warning is turned off for this build instead of editing engine source.
$wanted = "-CompilerVersion=$CompilerVersion -CompilerArguments=/wd4756"
# A build started with other options (an older toolset choice) is stopped and restarted.
if ((Test-Path $state) -and -not $Restart -and ((Get-Content $argsFile -ErrorAction SilentlyContinue) -ne $wanted)) {
    $old = [int](Get-Content $state -TotalCount 1)
    if (Get-Process -Id $old -ErrorAction SilentlyContinue) {
        "Stopping build $old (started without $wanted)."
        & taskkill.exe /PID $old /T /F 2>&1 | Select-Object -Last 2
        Start-Sleep -Seconds 10
    }
    Remove-Item $state -ErrorAction SilentlyContinue
}

# A build already running: report and leave it alone.
if ((Test-Path $state) -and -not $Restart) {
    $buildPid = [int](Get-Content $state -TotalCount 1)
    $running = Get-Process -Id $buildPid -ErrorAction SilentlyContinue
    if ($running) {
        "Build running (pid $buildPid, started $($running.StartTime))."
        Show-Progress
        return
    }
    "Build process $buildPid has ended."
    Show-Progress
    $done = Get-Content $log -Tail 5 -ErrorAction SilentlyContinue | Select-String -Pattern 'Total execution time|Result: Succeeded|BUILD SUCCESSFUL' -Quiet
    "Finished: $done"
    return
}

Section 'Cesium for Unreal into Game\Plugins'
$source = Join-Path $root 'CesiumHost\Plugins\CesiumForUnreal'
$target = Join-Path $VrTak 'Game\Plugins\CesiumForUnreal'
if (-not (Test-Path (Join-Path $target 'CesiumForUnreal.uplugin'))) {
    if (-not (Test-Path (Join-Path $source 'CesiumForUnreal.uplugin'))) { "Missing $source (run cesium-unreal-build first)."; return }
    "Copying $source to $target"
    Copy-Item -Recurse -Force $source $target
}
Get-Content (Join-Path $target 'CesiumForUnreal.uplugin') | Select-String '"VersionName"|"EnabledByDefault"' | ForEach-Object { $_.Line.Trim() }

Section 'Targets'
$uproject = Join-Path $VrTak 'Game\Game.uproject'
$targets = Get-ChildItem -LiteralPath (Join-Path $VrTak 'Game\Source') -Filter *.Target.cs -ErrorAction SilentlyContinue
$targets | ForEach-Object { "   $($_.Name)" }
$editor = ($targets | Where-Object { $_.Name -match 'Editor\.Target\.cs$' } | Select-Object -First 1).Name -replace '\.Target\.cs$', ''
if ($BuildTarget) { $editor = $BuildTarget }
if (-not $editor) { 'No editor target found.'; return }

Section "Start: $editor Win64 Development (detached)"
$build = Join-Path $VrTak 'Engine\Build\BatchFiles\Build.bat'
$projectArg = if ($BuildTarget) { '' } else { "-Project=`"$uproject`"" }
$command = "`"$build`" $editor Win64 Development $projectArg -WaitMutex -NoHotReload $wanted > `"$log`" 2>&1"
$command
# Keep the previous run's log: vrtak-engine-fix reads errors from both.
if (Test-Path $log) { Get-Content $log | Add-Content -Path (Join-Path $root 'engine-build-history.log') }
$env:RUNNER_TRACKING_ID = ''
$process = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', "`"$command`"" -WindowStyle Hidden -PassThru
Set-Content -Path $state -Value $process.Id -Encoding ascii
Set-Content -Path $argsFile -Value $wanted -Encoding ascii
"Started pid $($process.Id) at $(Get-Date). Run this task again for progress."
Start-Sleep -Seconds 90
Show-Progress
