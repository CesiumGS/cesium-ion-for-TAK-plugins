<#
One-time setup on the Windows work PC: registers it as a GitHub Actions
runner (labels: self-hosted, Windows, vrtak) so VR-TAK / Unreal builds and
self-hosted ion checks can run there unattended, like the ATAK tablet runner
on the Mac (atak/scripts/setup-runner.sh).

  powershell -ExecutionPolicy Bypass -File .github\scripts\setup-runner-windows.ps1          # install, then run in this window
  powershell -ExecutionPolicy Bypass -File .github\scripts\setup-runner-windows.ps1 -Remove  # unregister

The runner runs in an interactive window (run.cmd), not as a service:
Unreal, the GPU and an attached headset need the logged-in user's session.
Close the window (or Ctrl+C) to stop it; run "$HOME\actions-runner-vrtak\run.cmd"
to start it again.

It needs a short-lived registration token. Set $env:RUNNER_TOKEN to it, or with the GitHub CLI logged in
(gh auth login) the script fetches one; otherwise open the repository on
GitHub > Settings > Actions > Runners > New self-hosted runner, copy the
token shown after --token, and paste it when asked (it is not echoed).

While the runner is up, anyone who can push to the development branch or
dispatch workflows in this private repository can run a build on this PC.
#>
param([switch]$Remove)

$ErrorActionPreference = 'Stop'
$Repo = if ($env:REPO) { $env:REPO } else { 'CesiumGS/cesium-ion-for-TAK-plugins' }
$Dir = if ($env:RUNNER_DIR) { $env:RUNNER_DIR } else { Join-Path $HOME 'actions-runner-vrtak' }
$Labels = 'vrtak'

function Get-RunnerToken([string]$Kind) {
    $token = ''
    if (Get-Command gh -ErrorAction SilentlyContinue) {
        try { $token = (gh api -X POST "repos/$Repo/actions/runners/$Kind-token" --jq .token 2>$null) } catch { $token = '' }
    }
    # No gh login: a token in $env:RUNNER_TOKEN, else the hidden prompt. Some consoles paste
    # only one character into the hidden prompt, so a short result falls back to a visible one.
    if (-not $token -and $env:RUNNER_TOKEN) { $token = $env:RUNNER_TOKEN }
    if (-not $token) {
        $secure = Read-Host "Paste the $Kind token from GitHub > $Repo > Settings > Actions > Runners" -AsSecureString
        $token = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
        if ($token.Trim().Length -lt 20) {
            Write-Host "That is only $($token.Trim().Length) character(s); a token has about 29. Paste it again (it will show on screen; it expires within the hour):"
            $token = Read-Host 'Token'
        }
    }
    if (-not $token) { throw 'No token.' }
    return $token.Trim()
}

if ($Remove) {
    if (-not (Test-Path (Join-Path $Dir 'config.cmd'))) { throw "No runner in $Dir." }
    Push-Location $Dir
    try { & .\config.cmd remove --token (Get-RunnerToken 'remove') } finally { Pop-Location }
    Write-Host "Runner removed. You can delete $Dir."
    exit 0
}

if (-not (Test-Path (Join-Path $Dir 'config.cmd'))) {
    $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/actions/runner/releases/latest'
    $version = $release.tag_name.TrimStart('v')
    $zip = Join-Path $env:TEMP "actions-runner-win-x64-$version.zip"
    Write-Host "Downloading GitHub Actions runner $version into $Dir"
    Invoke-WebRequest -Uri "https://github.com/actions/runner/releases/download/v$version/actions-runner-win-x64-$version.zip" -OutFile $zip
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    Expand-Archive -Path $zip -DestinationPath $Dir -Force
    Remove-Item $zip
}

Push-Location $Dir
try {
    if (-not (Test-Path '.runner')) {
        & .\config.cmd --unattended --replace `
            --url "https://github.com/$Repo" `
            --token (Get-RunnerToken 'registration') `
            --name "$env:COMPUTERNAME-vrtak" `
            --labels $Labels `
            --work _work
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path '.runner')) {
            throw 'Runner registration failed (see the errors above). Registration tokens expire after an hour and work once: get a fresh one and run this script again.'
        }
    }
    Write-Host ''
    Write-Host "Runner registered (labels: self-hosted, Windows, $Labels). Starting it in this window;"
    Write-Host 'keep the window open while tests run, Ctrl+C to stop.'
    & .\run.cmd
} finally {
    Pop-Location
}
