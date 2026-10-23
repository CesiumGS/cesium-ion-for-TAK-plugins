<#
ATAK on this PC's Android emulator against the ion Self-Hosted server here:
downloads the newest "atak-apks" artifact (the plugin and the SDK's
atak.apk, built by the Mac runner and kept for a day), then runs
atak/scripts/device-test.sh under Git Bash with --prebuilt and the
selfhosted-showcase scenario. The report and screenshots are published with
this task's report. Set ATAK_SCENARIO to run another scenario.
#>
param([string]$Scenario = $(if ($env:ATAK_SCENARIO) { $env:ATAK_SCENARIO } else { 'selfhosted-showcase' }))
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
. "$PSScriptRoot\common.ps1"
$repoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$work = Join-Path $env:LOCALAPPDATA 'cesium-tak\atak-apks'

if (-not (Test-Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'))) {
    Section 'Android SDK and emulator (first run)'
    & "$PSScriptRoot\android-setup.ps1"
}

Section 'Plugin and ATAK APKs (artifact from the Mac runner)'
if (-not $env:GH_TOKEN) { 'No GH_TOKEN; the workflow passes the job token.'; return }
$headers = @{ Authorization = "Bearer $env:GH_TOKEN"; Accept = 'application/vnd.github+json'; 'User-Agent' = 'cesium-tak' }
# The Mac may still be building it: wait up to 10 minutes. When this commit
# also asked the Mac for APKs ([atak-apks]), wait up to 25 minutes for that
# build instead of taking an older one (the Mac may be busy with other runs).
$sameCommit = $env:GITHUB_SHA -and "$env:HEAD_MESSAGE" -match '\[atak-apks\]'
$tries = if ($sameCommit) { 50 } else { 20 }
$artifact = $null
for ($i = 0; $i -lt $tries -and -not $artifact; $i++) {
    if ($i -gt 0) { Start-Sleep -Seconds 30 }
    $list = Invoke-RestMethod -Headers $headers "https://api.github.com/repos/$env:GITHUB_REPOSITORY/actions/artifacts?name=atak-apks&per_page=10"
    $artifact = $list.artifacts | Where-Object { -not $_.expired -and (-not $sameCommit -or $_.workflow_run.head_sha -eq $env:GITHUB_SHA) } |
        Sort-Object created_at -Descending | Select-Object -First 1
}
if (-not $artifact) { 'No unexpired atak-apks artifact; run the "ATAK APKs for the work PC" workflow first.'; return }
"Artifact $($artifact.id) from $($artifact.created_at) (run $($artifact.workflow_run.id), $([math]::Round($artifact.size_in_bytes / 1MB)) MB)"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $work | Out-Null
$zip = Join-Path $work 'atak-apks.zip'
# The API answers with a redirect to storage that must not get the token
# (Windows PowerShell's Invoke-WebRequest hides that redirect's Location).
$request = [System.Net.HttpWebRequest]::Create($artifact.archive_download_url)
$request.AllowAutoRedirect = $false
$request.UserAgent = 'cesium-tak'
$request.Headers.Add('Authorization', "Bearer $env:GH_TOKEN")
$request.Accept = 'application/vnd.github+json'
$location = $null
try {
    $response = $request.GetResponse()
    $location = $response.Headers['Location']
    $response.Close()
} catch [System.Net.WebException] {
    if ($_.Exception.Response) { $location = $_.Exception.Response.Headers['Location'] }
    else { "Artifact request failed: $($_.Exception.Message)" }
}
if (-not $location) { 'Could not get the artifact download location.'; return }
Invoke-WebRequest -UseBasicParsing -TimeoutSec 900 -OutFile $zip $location
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $work)
Get-ChildItem $work -Filter *.apk | ForEach-Object { '   {0}  {1:N0} KB' -f $_.Name, ($_.Length / 1KB) }

Section "Device test ($Scenario) under Git Bash"
$bash = 'C:\Program Files\Git\bin\bash.exe'
if (-not (Test-Path $bash)) { "No Git Bash at $bash"; return }
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:AVD_NAME = 'ATAK_PC_Tablet'
$env:Path = "$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:Path"
$prebuilt = $work -replace '\\', '/'
# Git Bash rewrites arguments that look like POSIX paths (adb's /sdcard/...)
# into Windows paths; turn that off for the device test.
$env:MSYS_NO_PATHCONV = '1'
$env:MSYS2_ARG_CONV_EXCL = '*'
& $bash -lc "cd '$($repoRoot -replace '\\', '/')' && atak/scripts/device-test.sh --prebuilt '$prebuilt' --scenario $Scenario 900" 2>&1 |
    ForEach-Object { "$_" } | Mask

Section 'Report and screenshots'
$reports = Join-Path $repoRoot 'atak\device-reports'
$out = Join-Path $repoRoot 'vrtak\reports'
Get-ChildItem $reports -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '-(report|scenario)\.txt$|\.png$' } |
    ForEach-Object { Copy-Item $_.FullName $out; "   $($_.Name)" }
