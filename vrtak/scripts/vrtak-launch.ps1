<#
Runs VR-TAK (desktop, windowed) with the Cesium ion plugin against the ion
Self-Hosted server on this PC, and collects the plugin's screenshots and log.

The plugin's test switches: -CesiumIonServer/-CesiumIonApi (single-user
Self-Hosted, no sign-in), -CesiumIonOrigin (VR-TAK's globe origin, here
Philadelphia), -CesiumIonLoad (asset IDs on that server, added in order),
-CesiumIonScreenshots (three screenshots with the UI) and -CesiumIonQuitAfter.

The first start compiles shaders and can take long, so the run is detached:
a first call starts VR-TAK; later calls report progress, and once the
screenshots exist they are added to this task's report.
State: %USERPROFILE%\cesium-vrtak\vrtak-launch.*

VR-TAK is marked Distribution Statement F (USSOCOM). The owner approved this
private repository for VR-TAK material and the screenshots for the status
page (20 October); copies also stay on the PC (%USERPROFILE%\cesium-vrtak\screens).
#>
param(
    [string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK',
    [string]$Server = 'http://192.168.1.247:32470',
    [string]$Api = 'http://192.168.1.247:31813',
    [string]$Origin = '39.9526,-75.1635,0',
    [string]$Load = '11,21',
    # selfhosted: the Self-Hosted server above (no sign-in). cloud: Cesium ion Cloud with
    # the saved sign-in (DPAPI, this Windows user); without one the panel shows its
    # sign-in card and ion's sign-in page opens in the browser for the person to finish.
    [ValidateSet('selfhosted', 'cloud')] [string]$Mode = 'selfhosted',
    # The build arguments match vrtak-engine-build exactly, so only changed files compile.
    # Seconds after start: leave the start-up room for the world, first screenshot, quit.
    [int]$EnterWorld = 20, [int]$ScreenshotAt = 240, [int]$QuitAfter = 330,
    # Extra log patterns the regression checks require (for example, other asset types).
    [string[]]$Expect = @(),
    # More plugin test switches (for example -CesiumIonRaise=60).
    [string[]]$ExtraArgs = @(),
    [switch]$Restart
)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\common.ps1"
$root = Join-Path $env:USERPROFILE 'cesium-vrtak'
$state = Join-Path $root 'vrtak-launch.pid'
$editor = Join-Path $VrTak 'Engine\Binaries\Win64\UnrealEditor.exe'
$uproject = Join-Path $VrTak 'Game\Game.uproject'
$shots = Join-Path $VrTak 'Game\Saved\Screenshots'
$log = Join-Path $VrTak 'Game\Saved\Logs\Game.log'
$keep = Join-Path $root 'screens'
New-Item -ItemType Directory -Force -Path $keep | Out-Null
$reports = (Resolve-Path "$PSScriptRoot\..\reports").Path

function Show-Run([string]$RunMode = $Mode) {
    Section 'VR-TAK run'
    if (Test-Path $log) {
        "Log: $log (updated $((Get-Item $log).LastWriteTime))"
        '-- Cesium ion plugin'
        Get-Content $log -ErrorAction SilentlyContinue | Select-String 'LogCesiumIonVrtak|LogCesium|Cesium ion' |
            Select-Object -Last 40 | ForEach-Object { $_.Line.Trim() } | Mask
        '-- errors'
        Get-Content $log -ErrorAction SilentlyContinue | Select-String 'Error:|Fatal|Assertion failed|Unable to load' |
            Select-Object -First 25 | ForEach-Object { $_.Line.Trim() } | Mask
        '-- shader compilation'
        Get-Content $log -Tail 400 -ErrorAction SilentlyContinue | Select-String 'Compiling shaders|ShaderCompil' |
            Select-Object -Last 3 | ForEach-Object { $_.Line.Trim() }
    } else { 'No VR-TAK log yet.' }
    '-- last log lines'
    Get-Content $log -Tail 40 -ErrorAction SilentlyContinue | ForEach-Object { $_.Trim() } | Mask
    Copy-Item $log (Join-Path $keep 'Game.log') -Force -ErrorAction SilentlyContinue
    Section 'VR-TAK processes and windows'
    $procs = @(Get-Process -Name 'UnrealEditor', 'CrashReportClient*', 'ShaderCompileWorker' -ErrorAction SilentlyContinue)
    $procs | Group-Object Name | ForEach-Object { '   {0} x{1}' -f $_.Name, $_.Count }
    $procs | Where-Object { $_.Name -ne 'ShaderCompileWorker' } | ForEach-Object {
        '   {0} pid {1}: responding {2}, CPU {3:N0} s, window "{4}"' -f $_.Name, $_.Id, $_.Responding, $_.CPU, $_.MainWindowTitle
    }
    # A picture of VR-TAK's own window only (not the rest of the desktop), to see a dialog or the view.
    $window = $procs | Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero -and $_.Name -ne 'ShaderCompileWorker' } | Select-Object -First 1
    if ($window) {
        try {
            Add-Type -AssemblyName System.Drawing
            Add-Type -Namespace CesiumVrtak -Name Win -MemberDefinition @'
[System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
public struct RECT { public int Left, Top, Right, Bottom; }
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern bool GetWindowRect(System.IntPtr h, out RECT r);
'@ -ErrorAction SilentlyContinue
            $r = New-Object CesiumVrtak.Win+RECT
            if ([CesiumVrtak.Win]::GetWindowRect($window.MainWindowHandle, [ref]$r) -and ($r.Right - $r.Left) -gt 0) {
                $bmp = New-Object System.Drawing.Bitmap ($r.Right - $r.Left), ($r.Bottom - $r.Top)
                $g = [System.Drawing.Graphics]::FromImage($bmp)
                $g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
                $out = Join-Path $keep ('vrtak-window-{0}.png' -f (Get-Date).ToString('HHmmss'))
                $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $bmp.Dispose()
                Copy-Item $out (Join-Path $reports 'vrtak-window.png') -Force
                "Window picture: vrtak-window.png ($($r.Right - $r.Left) x $($r.Bottom - $r.Top))"
            }
        } catch { "Window picture failed: $($_.Exception.Message)" }
    }
    $found = Get-ChildItem $shots -Recurse -Filter 'CesiumIon-*.png' -ErrorAction SilentlyContinue
    foreach ($f in $found) {
        Copy-Item $f.FullName (Join-Path $keep $f.Name) -Force
        Copy-Item $f.FullName (Join-Path $reports ("vrtak-" + $f.Name)) -Force
        "Screenshot: vrtak-$($f.Name) ($([int]($f.Length / 1KB)) KB, $($f.LastWriteTime))"
    }
    if (-not $found) { 'No screenshots yet.' }
    # Regression checks: the core flow must keep working with every change.
    if (($RunMode -eq 'selfhosted' -or $Expect) -and (Test-Path $log)) {
        Section 'Regression checks'
        $text = Get-Content $log -ErrorAction SilentlyContinue
        $failed = 0
        $patterns = @('LogCesiumIonVrtak: Found \d+ assets', "LogCesiumIonVrtak: Added .+ to VR-TAK's world",
                'LogCesiumIonVrtak: Georeference aligned', 'LogCesiumIonVrtak: Cesium ion panel on screen',
                'LogCesiumIonVrtak: Screenshot requested', 'LogCesiumIonVrtak: Quitting after the test run') + $Expect
        foreach ($pattern in $patterns) {
            if ($text | Select-String -Pattern $pattern -Quiet) { "PASS  $pattern" } else { "FAIL  $pattern"; $failed++ }
        }
        $fatal = @($text | Select-String -Pattern 'Fatal error|Assertion failed|Unhandled Exception|LogWindows: Error: ===')
        if ($fatal.Count -eq 0) { 'PASS  no fatal errors' } else { "FAIL  $($fatal.Count) fatal line(s)"; $failed++ }
        if ($failed -eq 0) { 'REGRESSION: PASS' } else { "REGRESSION: FAIL ($failed)" }
    }
}

# A run still going: report it. Otherwise start a new one (each run reports itself).
if ((Test-Path $state) -and -not $Restart -and (Get-Process -Id ([int](Get-Content $state -TotalCount 1)) -ErrorAction SilentlyContinue)) {
    "VR-TAK running (pid $(Get-Content $state -TotalCount 1))."
    Show-Run
    return
}
$buildState = Join-Path $root 'engine-build.pid'
if ((Test-Path $buildState) -and (Get-Process -Id ([int](Get-Content $buildState -TotalCount 1)) -ErrorAction SilentlyContinue)) {
    'A build is still running; VR-TAK is started once it has finished.'
    & "$PSScriptRoot\vrtak-engine-build.ps1" -VrTak $VrTak
    return
}
if (-not (Test-Path (Join-Path $VrTak 'Game\Plugins\VTAK_CesiumIon\Binaries\Win64'))) {
    'The Cesium ion plugin is not built yet (no Binaries in Game\Plugins\VTAK_CesiumIon); run vrtak-engine-build first.'
    return
}
Section 'Build the plugin (GameEditor, incremental)'
& "$PSScriptRoot\vrtak-plugin-install.ps1" -VrTak $VrTak -NoStatus | Select-Object -First 3
$buildLog = Join-Path $root 'plugin-build.log'
$build = Join-Path $VrTak 'Engine\Build\BatchFiles\Build.bat'
& cmd.exe /c "`"$build`" GameEditor Win64 Development -Project=`"$uproject`" -WaitMutex -NoHotReload -CompilerVersion=14.36.32532 -CompilerArguments=/wd4756 > `"$buildLog`" 2>&1"
$buildExit = $LASTEXITCODE
Get-Content $buildLog | Select-String -Pattern ': error |: fatal error|Result: |Total execution time|CesiumIonVrtak' | Select-Object -First 30 | ForEach-Object { '   ' + $_.Line.Trim() }
"Build exit code: $buildExit"
if ($buildExit -ne 0) { 'The plugin did not build; VR-TAK not started.'; return }

Get-ChildItem $shots -Recurse -Filter 'CesiumIon-*.png' -ErrorAction SilentlyContinue | Remove-Item -Force
$args_ = @("`"$uproject`"", '-game', '-windowed', '-ResX=1920', '-ResY=1080', '-nohmd', '-log',
    "-CesiumIonOrigin=$Origin", "-CesiumIonEnterWorld=$EnterWorld",
    "-CesiumIonScreenshots=$ScreenshotAt", "-CesiumIonShowSettings=$($ScreenshotAt + 15)", "-CesiumIonQuitAfter=$QuitAfter")
if ($Mode -eq 'cloud') {
    # Google Photorealistic 3D Tiles; signs in if there is no saved access.
    $args_ += @('-CesiumIonLoad=' + $(if ($PSBoundParameters.ContainsKey('Load')) { $Load } else { '2275207' }), '-CesiumIonSignIn')
} else {
    $args_ += @("-CesiumIonServer=$Server", "-CesiumIonApi=$Api", "-CesiumIonLoad=$Load")
}
if ($ExtraArgs) {
    $args_ += $ExtraArgs
}
Section 'Start VR-TAK (detached)'
"$editor $($args_ -join ' ')"
$env:RUNNER_TRACKING_ID = ''
$process = Start-Process -FilePath $editor -ArgumentList $args_ -PassThru
Set-Content -Path $state -Value $process.Id -Encoding ascii
"Started pid $($process.Id) at $(Get-Date). Run this task again for progress and screenshots."
Start-Sleep -Seconds ($QuitAfter + 120)
Show-Run
