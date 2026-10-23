<#
Finds why the ion asset server crashes on terrain tiles at zoom 8 and
deeper: runs selfhosted-terrain-crash.sh inside WSL, which requests one
deep tile (crashing the asset server once; Kubernetes restarts it) and
collects the exit reason, the log before the crash, events, memory and
how the asset is stored. Holds a hidden WSL session open only while it
runs; changes nothing else. Tokens are masked.
#>
param([string]$Distro = '', [string]$LanApi = 'http://192.168.1.247:31813', [string]$Asset = '9')
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\common.ps1"
if (-not $Distro) {
    $default = Invoke-Bounded wsl @('--list', '--verbose') 20 | ForEach-Object { $_ -replace "`0", '' } |
        Where-Object { $_ -match '^\s*\*' } | Select-Object -First 1
    if ($default -match '^\s*\*\s+(\S+)') { $Distro = $Matches[1] }
}
if (-not $Distro) { 'No default WSL distribution found.'; return }
Section "Holding WSL ($Distro) open while this runs"
$hold = Start-Process -FilePath "$env:WINDIR\System32\wsl.exe" -ArgumentList "-d $Distro --exec /bin/sleep 3000" `
    -WindowStyle Hidden -PassThru
try {
    Start-Sleep -Seconds 5
    $script = (Resolve-Path "$PSScriptRoot\selfhosted-terrain-crash.sh").Path
    $scriptWsl = (Invoke-Bounded wsl @('-d', $Distro, '--exec', 'wslpath', '-a', ($script -replace '\\', '/')) 60 | Select-Object -Last 1).Trim()
    Invoke-Bounded wsl @('-d', $Distro, '--exec', 'bash', $scriptWsl, $LanApi, $Asset) 1200
} finally {
    if ($hold -and -not $hold.HasExited) { Stop-Process -Id $hold.Id -Force -ErrorAction SilentlyContinue }
    "WSL hold released."
}
