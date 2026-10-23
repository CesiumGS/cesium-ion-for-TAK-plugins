<#
Helpers shared by the work-PC task scripts (dot-source this file). They
keep read-only surveys fast and safe on a work PC: every external command
runs with a time limit, and file searches are depth-limited, skip build and
cache folders, and never open cloud-only (OneDrive "Files On-Demand") files,
which would download them.
#>

function Section($title) {
    "`n==== $title ====  [$((Get-Date).ToString('HH:mm:ss'))]"
}

# Masks values of lines that look like secrets.
function Mask {
    process { $_ -replace '(?i)((password|passwd|secret|token|apikey|api_key|license|licence|private[_-]?key|credential|client-key-data|client-certificate-data|certificate-authority-data)[^:=]*[:=]\s*).+', '$1***' }
}

# Runs an external command with a time limit; prints its output, or a note
# when it timed out. Arguments are passed as an array (no shell parsing).
function Invoke-Bounded {
    param([string]$Exe, [string[]]$Arguments = @(), [int]$Seconds = 20)
    $job = Start-Job -ScriptBlock {
        param($exe, $arguments)
        & $exe @arguments 2>&1 | ForEach-Object { "$_" }
    } -ArgumentList $Exe, $Arguments
    if (Wait-Job $job -Timeout $Seconds) {
        Receive-Job $job
    } else {
        Stop-Job $job
        "($Exe $($Arguments -join ' ') did not answer within $Seconds s; skipped)"
    }
    Remove-Job $job -Force
}

# Cloud-only placeholders (OneDrive and similar): reading them downloads them.
# Offline, RecallOnOpen, RecallOnDataAccess (the last two are not in .NET's enum).
$script:CloudOnly = 0x00001000 -bor 0x00040000 -bor 0x00400000

# Lists files under $Root to $MaxDepth levels, skipping folders whose name
# matches $SkipDirs, reparse points (junctions, OneDrive roots) and
# cloud-only files; stops after $Limit files or $Seconds seconds.
function Find-Files {
    param([string]$Root, [string[]]$Extensions = @(), [int]$MaxDepth = 4,
          [string]$SkipDirs = '^(\.git|node_modules|Intermediate|Saved|DerivedDataCache|\.vs|Temp|Cache|Caches)$',
          [int]$Limit = 20000, [int]$Seconds = 60)
    if (-not (Test-Path -LiteralPath $Root)) { return }
    $deadline = (Get-Date).AddSeconds($Seconds)
    $count = 0
    $queue = New-Object System.Collections.Generic.Queue[object]
    $queue.Enqueue(@((Get-Item -LiteralPath $Root), 0))
    while ($queue.Count -gt 0) {
        if ((Get-Date) -gt $deadline -or $count -ge $Limit) {
            "(search of $Root stopped early: time or file limit)"
            return
        }
        $entry = $queue.Dequeue()
        $dir = $entry[0]; $depth = $entry[1]
        try { $children = $dir.GetFileSystemInfos() } catch { continue }
        foreach ($child in $children) {
            if ($child -is [IO.DirectoryInfo]) {
                if ($depth -lt $MaxDepth -and $child.Name -notmatch $SkipDirs -and
                    -not ($child.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                    $queue.Enqueue(@($child, ($depth + 1)))
                }
            } elseif (-not ([int]$child.Attributes -band $script:CloudOnly)) {
                if ($Extensions.Count -eq 0 -or $Extensions -contains $child.Extension.ToLowerInvariant()) {
                    $count++
                    $child
                }
            }
        }
    }
}
