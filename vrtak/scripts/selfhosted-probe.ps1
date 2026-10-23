<#
Probes Cesium ion Self-Hosted on this PC the way the ATAK plugin uses it,
terrain first (layer.json, a tile per zoom level, a parallel burst), plus
imagery, 3D Tiles and the KML/GeoJSON files. Runs selfhosted-probe.sh (which uses
atak/scripts/server-probe.sh) inside WSL against the LAN address (through the Windows port proxy) and
the WSL VM address (direct), to tell the two apart.

For the duration of the probe only, it keeps a hidden WSL session open so
WSL (and MicroK8s) stays up, then closes it; nothing on the PC is changed.
Tokens are redacted.
#>
param([string]$Distro = '', [string]$LanApi = 'http://192.168.1.247:31813',
      [string]$Assets = '9 12 21 5 4')
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\common.ps1"

if (-not $Distro) {
    $default = Invoke-Bounded wsl @('--list', '--verbose') 20 | ForEach-Object { $_ -replace "`0", '' } |
        Where-Object { $_ -match '^\s*\*' } | Select-Object -First 1
    if ($default -match '^\s*\*\s+(\S+)') { $Distro = $Matches[1] }
}
if (-not $Distro) { 'No default WSL distribution found.'; return }

Section "Holding WSL ($Distro) open for the probe"
$hold = Start-Process -FilePath "$env:WINDIR\System32\wsl.exe" -ArgumentList "-d $Distro --exec /bin/sleep 3000" `
    -WindowStyle Hidden -PassThru
try {
    Start-Sleep -Seconds 5
    $script = (Resolve-Path "$PSScriptRoot\selfhosted-probe.sh").Path
    $scriptWsl = (Invoke-Bounded wsl @('-d', $Distro, '--exec', 'wslpath', '-a', ($script -replace '\\', '/')) 60 | Select-Object -Last 1).Trim()
    # --exec passes each argument as is (no Linux shell re-parsing).
    Invoke-Bounded wsl (@('-d', $Distro, '--exec', 'bash', $scriptWsl, $LanApi) + $Assets.Split(" ")) 1800
} finally {
    if ($hold -and -not $hold.HasExited) { Stop-Process -Id $hold.Id -Force -ErrorAction SilentlyContinue }
    "WSL hold released."
}
"Done."
