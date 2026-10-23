<#
CHANGES THE PC (run only with the owner's approval): keeps the WSL
distribution that hosts Cesium ion Self-Hosted running, so MicroK8s and the
ion server stay up when no WSL window is open. WSL stops its VM shortly
after the last WSL session closes, which takes the server down.

What it does, for the current user only (no admin rights needed):
  * registers a Scheduled Task "Cesium ion WSL keep-alive" that runs at log
    on, hidden: wsl.exe -d <distro> --exec sleep infinity
  * starts it now, then waits for MicroK8s and the ion pods and checks the
    API and web UI on the LAN address.
Undo:  powershell -ExecutionPolicy Bypass -File selfhosted-keepalive.ps1 -Remove
       (or delete the task in Task Scheduler). Nothing else is changed.
#>
# Approved by the PC's owner on 2026-10-20 for overnight self-hosted testing.
param([string]$Distro = '', [switch]$Remove, [string]$LanHost = '192.168.1.247')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"
$TaskName = 'Cesium ion WSL keep-alive'

if ($Remove) {
    Section 'Removing the keep-alive task'
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    "Removed (WSL will again stop when no session is open)."
    return
}

if (-not $Distro) {
    $default = Invoke-Bounded wsl @('--list', '--verbose') 20 | ForEach-Object { $_ -replace "`0", '' } |
        Where-Object { $_ -match '^\s*\*' } | Select-Object -First 1
    if ($default -match '^\s*\*\s+(\S+)') { $Distro = $Matches[1] }
}
if (-not $Distro) { throw 'No default WSL distribution found.' }

Section "Registering '$TaskName' for $env:USERNAME ($Distro)"
$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existing) { "Already registered; updating it." }
$action = New-ScheduledTaskAction -Execute "$env:WINDIR\System32\wsl.exe" -Argument "-d $Distro --exec /bin/sleep infinity"
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -Hidden
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal `
    -Description 'Keeps WSL (and Cesium ion Self-Hosted on MicroK8s) running. Added by the Cesium TAK plugin work-PC scripts; remove with selfhosted-keepalive.ps1 -Remove.' -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Seconds 10
"Task state: $((Get-ScheduledTask -TaskName $TaskName).State)"

Section 'Waiting for MicroK8s and the ion pods (up to 10 minutes)'
$ErrorActionPreference = 'Continue'
Invoke-Bounded wsl @('-d', $Distro, '--', 'bash', '-lc', 'microk8s status --wait-ready --timeout 540 | head -3') 600
Invoke-Bounded wsl @('-d', $Distro, '--', 'bash', '-lc', 'microk8s kubectl wait --for=condition=Ready pod --all -n cesium-ion --timeout=300s; microk8s kubectl get pods -n cesium-ion') 360

Section "Checking the server on $LanHost"
foreach ($url in "http://${LanHost}:32470/", "http://${LanHost}:31813/v1/version") {
    try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 20 $url; "$url  HTTP $($r.StatusCode)" }
    catch { "$url  $($_.Exception.Message)" }
}
Section 'WSL state'
Invoke-Bounded wsl @('--list', '--verbose') 20 | ForEach-Object { $_ -replace "`0", '' } | Where-Object { $_.Trim() }
"Done."
