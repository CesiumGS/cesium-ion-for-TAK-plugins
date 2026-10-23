<#
Makes VR-TAK's UE 5.3 engine compile on this PC, then restarts the build
(incremental: finished work is kept). Approved by the PC's owner as part of
building VR-TAK in place.

1. The Windows SDK here (10.0.26100) defines INFINITY and NAN so that UE 5.3
   code using them in constant expressions fails with error C4756. For every
   file and line the build log reports with C4756, only that line is changed:
   INFINITY -> std::numeric_limits<float>::infinity(),
   NAN -> std::numeric_limits<float>::quiet_NaN() (case-sensitive, rebuilt
   from the original each run). Each file's original is
   kept under %USERPROFILE%\cesium-vrtak\backup\<path in VR-TAK>, and
   backup\manifest.txt lists every changed line; restore the originals to undo.
2. Object files the linker reports as unreadable (LNK1106, left by a build
   that was stopped mid-write) are deleted, so they are compiled again.
3. Disk: removes copies this work made that are no longer needed, and the
   Android emulator's quick-boot snapshots (it cold-boots next time).
4. Cesium for Unreal files that rely on headers included earlier in a unity
   batch (this build compiles files one by one) get those includes when the
   build reports them (CoreMinimal.h, or a known symbol's header); backed up
   the same way.
5. Disables the Fab plugin in Game.uproject when the build log shows it
   failing (approved by the owner; backed up).
6. Reinstalls the Cesium ion plugin source (vrtak-plugin-install), so the
   restarted build compiles the latest plugin.
#>
param([string]$VrTak = 'C:\Program Files\Epic Games\VR-TAK')
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\common.ps1"
$root = Join-Path $env:USERPROFILE 'cesium-vrtak'
$backupRoot = Join-Path $root 'backup'
$manifest = Join-Path $backupRoot 'manifest.txt'
$log = Join-Path $root 'engine-build.log'
# Earlier runs' logs (vrtak-engine-build keeps them when it restarts).
$history = Join-Path $root 'engine-build-history.log'
New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
$sourceDir = Join-Path $VrTak 'Engine\Source'

function Backup-File([string]$file) {
    $relative = $file.Substring($VrTak.Length).TrimStart('\')
    $backup = Join-Path $backupRoot $relative
    if (-not (Test-Path -LiteralPath $backup)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $backup) | Out-Null
        Copy-Item -LiteralPath $file $backup
    }
    return $relative
}

function Resolve-Reported([string]$path) {
    # UBT reports engine paths absolute, or relative to Engine\Source.
    if ([IO.Path]::IsPathRooted($path)) { return $path }
    return [IO.Path]::GetFullPath((Join-Path $sourceDir $path))
}

Section 'C4756 lines from the build log'
$lines = @(Get-Content $history -ErrorAction SilentlyContinue) + @(Get-Content $log -ErrorAction SilentlyContinue)
$hits = @($lines | Select-String -Pattern '^(.+\.(?:cpp|h|inl))\((\d+)\)\s*:\s*error C4756' | ForEach-Object {
    [pscustomobject]@{ File = (Resolve-Reported $_.Matches[0].Groups[1].Value); Line = [int]$_.Matches[0].Groups[2].Value }
})
# Reported before the logs were kept across restarts.
$hits += [pscustomobject]@{ File = (Join-Path $VrTak 'Engine\Plugins\Runtime\AudioSynesthesia\Source\AudioSynesthesiaCore\Private\PeakPicker.cpp'); Line = 17 }
$hits = $hits | Sort-Object File, Line -Unique
# Earlier fix (RenderGraphPrivate.cpp, before this script kept a manifest).
$hits = @($hits) + [pscustomobject]@{ File = (Join-Path $sourceDir 'Runtime\RenderCore\Private\RenderGraphPrivate.cpp'); Line = 0 }
foreach ($group in ($hits | Group-Object File)) {
    $file = $group.Name
    if (-not (Test-Path -LiteralPath $file)) { "   not found: $file"; continue }
    $relative = $file.Substring($VrTak.Length).TrimStart('\')
    $backup = Join-Path $backupRoot $relative
    $legacyBackup = Join-Path $backupRoot (Split-Path $file -Leaf)
    if (-not (Test-Path -LiteralPath $backup)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $backup) | Out-Null
        if (Test-Path -LiteralPath $legacyBackup) { Copy-Item -LiteralPath $legacyBackup $backup } else { Copy-Item -LiteralPath $file $backup }
    }
    $text = @(Get-Content -LiteralPath $file)
    # Each line is rebuilt from the untouched original, so running this again changes nothing
    # (and repairs a line an earlier, case-insensitive version of this script mangled).
    $original = @(Get-Content -LiteralPath $backup)
    $targets = @($group.Group | ForEach-Object { $_.Line } | Where-Object { $_ -gt 0 })
    if ($targets.Count -eq 0) {
        # RenderGraphPrivate.cpp: the two return statements patched earlier.
        for ($i = 0; $i -lt $original.Count; $i++) { if ($original[$i] -cmatch '^\s*return (INFINITY|NAN);\s*$') { $targets += $i + 1 } }
    }
    $changed = $false
    foreach ($n in $targets) {
        $i = $n - 1
        if ($i -lt 0 -or $i -ge $text.Count -or $i -ge $original.Count) { continue }
        $new = $original[$i] -creplace '\bINFINITY\b', 'std::numeric_limits<float>::infinity()' -creplace '\bNAN\b', 'std::numeric_limits<float>::quiet_NaN()'
        if ($new -cne $text[$i]) {
            $text[$i] = $new; $changed = $true
            Add-Content -Path $manifest -Value "$relative($n): $new"
        }
        '   {0}({1}): {2}' -f $relative, $n, $text[$i].Trim()
    }
    if ($changed) {
        [IO.File]::WriteAllText($file, (($text -join "`r`n") + "`r`n"), (New-Object Text.UTF8Encoding $false))
    }
}

Section 'Cesium for Unreal: Build.cs originals'
# UE 5.3's ModuleRules has no ForceIncludeFiles (the rules said so), so the
# force-include tried earlier did nothing: put the original Build.cs back and drop
# the extra header. Missing includes are added per file below instead.
$cesiumSource = Join-Path $VrTak 'Game\Plugins\CesiumForUnreal\Source'
foreach ($module in 'CesiumRuntime', 'CesiumEditor') {
    $buildCs = Join-Path $cesiumSource "$module\$module.Build.cs"
    $relative = $buildCs.Substring($VrTak.Length).TrimStart('\')
    $original = Join-Path $backupRoot $relative
    if ((Test-Path -LiteralPath $original) -and (Test-Path -LiteralPath $buildCs) -and
            ((Get-Content -LiteralPath $buildCs -Raw) -cne (Get-Content -LiteralPath $original -Raw))) {
        Copy-Item -LiteralPath $original $buildCs -Force
        Add-Content -Path $manifest -Value "${relative}: original restored"
        "   ${module}: original Build.cs restored"
    }
    $header = Join-Path $cesiumSource "$module\Private\VrtakNonUnityIncludes.h"
    if (Test-Path -LiteralPath $header) { Remove-Item -LiteralPath $header -Force; "   ${module}: removed VrtakNonUnityIncludes.h" }
}

Section 'Fab plugin (approved: disable for the VR-TAK project if it fails to build)'
$uproject = Join-Path $VrTak 'Game\Game.uproject'
$fabErrors = @($lines | Select-String -Pattern '\\Engine\\Plugins\\Fab\\.*(error|fatal error) ')
if ($fabErrors.Count -gt 0) {
    $json = Get-Content -LiteralPath $uproject -Raw
    $project = $json | ConvertFrom-Json
    $fab = @($project.Plugins | Where-Object { $_.Name -eq 'Fab' })
    if ($fab.Count -gt 0 -and $fab[0].Enabled -eq $false) {
        '   Fab is already disabled in Game.uproject.'
    } else {
        $relative = Backup-File $uproject
        if ($fab.Count -gt 0) {
            # An existing entry: switch it off where it is.
            $json = [regex]::Replace($json, '("Name"\s*:\s*"Fab"\s*,\s*"Enabled"\s*:\s*)true', '${1}false')
        } elseif ($json -match '"Plugins"\s*:\s*\[') {
            $json = [regex]::Replace($json, '("Plugins"\s*:\s*\[)', ('$1' + "`r`n`t`t{`r`n`t`t`t`"Name`": `"Fab`",`r`n`t`t`t`"Enabled`": false`r`n`t`t},"), 1)
        } else {
            $json = [regex]::Replace($json, '\}\s*$', (",`r`n`t`"Plugins`": [`r`n`t`t{`r`n`t`t`t`"Name`": `"Fab`",`r`n`t`t`t`"Enabled`": false`r`n`t`t}`r`n`t]`r`n}"))
        }
        $check = $json | ConvertFrom-Json
        if (@($check.Plugins | Where-Object { $_.Name -eq 'Fab' -and $_.Enabled -eq $false }).Count -eq 1) {
            [IO.File]::WriteAllText($uproject, $json, (New-Object Text.UTF8Encoding $false))
            Add-Content -Path $manifest -Value "${relative}: Fab plugin disabled"
            "   Fab disabled in Game.uproject ($($fabErrors.Count) error lines from Engine\Plugins\Fab)."
        } else { '   Could not disable Fab cleanly; Game.uproject left as it was.' }
    }
} else { '   No Fab errors in the log.' }

Section 'Cesium for Unreal: missing includes'
# This build compiles files one by one (no unity batches), so Cesium for Unreal files
# that rely on a header included by an earlier file in their batch fail. Known
# symbols get their header; unknown ones are listed for a later fix.
$headerFor = @{
    'ENQUEUE_RENDER_COMMAND' = 'RenderingThread.h'; 'FlushRenderingCommands' = 'RenderingThread.h'
    'FRHICommandListImmediate' = 'RHICommandList.h'; 'TActorIterator' = 'EngineUtils.h'
    'GEngine' = 'Engine/Engine.h'; 'UGameplayStatics' = 'Kismet/GameplayStatics.h'
    'UKismetMathLibrary' = 'Kismet/KismetMathLibrary.h'; 'FPaths' = 'Misc/Paths.h'
    'IPluginManager' = 'Interfaces/IPluginManager.h'; 'UTexture2D' = 'Engine/Texture2D.h'
    'UMaterialInstanceDynamic' = 'Materials/MaterialInstanceDynamic.h'
    'UStaticMeshComponent' = 'Components/StaticMeshComponent.h'
    'APlayerController' = 'GameFramework/PlayerController.h'; 'UWorld' = 'Engine/World.h'
    'FParse' = 'Misc/Parse.h'; 'IFileManager' = 'HAL/FileManager.h'; 'FFileHelper' = 'Misc/FileHelper.h'
    'AsyncTask' = 'Async/Async.h'; 'FJsonObject' = 'Dom/JsonObject.h'
    'TWeakObjectPtr' = 'UObject/WeakObjectPtr.h'; 'ULocalPlayer' = 'Engine/LocalPlayer.h'; 'UGameViewportClient' = 'Engine/GameViewportClient.h'
    'FSceneView' = 'SceneView.h'; 'UTextureRenderTarget2D' = 'Engine/TextureRenderTarget2D.h'
}
$cesiumErrors = $lines | Select-String -Pattern '^(.+\\Game\\Plugins\\CesiumForUnreal\\.+\.(?:h|cpp))\((\d+)\)\s*:\s*error (C\d+): (.*)$' | ForEach-Object {
    $m = $_.Matches[0]
    $symbol = if ($m.Groups[4].Value -match "'([A-Za-z_][A-Za-z0-9_]*)'") { $Matches[1] } else { '' }
    [pscustomobject]@{ File = $m.Groups[1].Value; Code = $m.Groups[3].Value; Symbol = $symbol; Text = $m.Groups[4].Value }
}
# An engine template error (e.g. FWeakObjectPtr undefined) is reported in the engine header;
# the note after it names the Cesium file that instantiated the template.
$cesiumErrors = @($cesiumErrors) + @($lines | Select-String -Pattern "^(.+\\Game\\Plugins\\CesiumForUnreal\\.+\.(?:h|cpp))\((\d+)\)\s*:\s*note: see reference to class template instantiation '(TWeakObjectPtr)<" | ForEach-Object {
    $m = $_.Matches[0]
    [pscustomobject]@{ File = $m.Groups[1].Value; Code = 'NOTE'; Symbol = $m.Groups[3].Value; Text = $_.Line }
})
foreach ($group in ($cesiumErrors | Group-Object File)) {
    $file = $group.Name
    if (-not (Test-Path -LiteralPath $file)) { "   not found: $file"; continue }
    $wanted = @()
    if ($file -like '*.h' -and ($group.Group | Where-Object { $_.Code -in 'C3646', 'C4430', 'C2061' })) { $wanted += 'CoreMinimal.h' }
    $group.Group | Where-Object { $headerFor.ContainsKey($_.Symbol) } | ForEach-Object { $wanted += $headerFor[$_.Symbol] }
    # Types whose header an engine template needs complete (errors then point into engine headers).
    $raw = Get-Content -LiteralPath $file -Raw
    if ($raw -match '\bTWeakObjectPtr\b') { $wanted += 'UObject/WeakObjectPtr.h' }
    if ($raw -match '\bFAssetData\b') { $wanted += 'AssetRegistry/AssetData.h' }
    if ($raw -match 'DECLARE_(MULTICAST_|DYNAMIC_)?DELEGATE' -and $file -like '*.h') { $wanted += 'CoreMinimal.h' }
    $wanted = @($wanted | Sort-Object -Unique)
    # CoreMinimal.h first.
    $wanted = @($wanted | Where-Object { $_ -eq 'CoreMinimal.h' }) + @($wanted | Where-Object { $_ -ne 'CoreMinimal.h' })
    $group.Group | Where-Object { $_.Symbol -and -not $headerFor.ContainsKey($_.Symbol) -and $_.Code -in 'C2065', 'C3861', 'C2027', 'C2079' } |
        Sort-Object Symbol -Unique | ForEach-Object { "   unknown symbol in $(Split-Path $file -Leaf): $($_.Code) $($_.Text)" }
    $text = @(Get-Content -LiteralPath $file)
    $wanted = @($wanted | Where-Object { -not ($text -match ('#include\s+"' + [regex]::Escape($_) + '"')) })
    if ($wanted.Count -eq 0) { continue }
    $relative = Backup-File $file
    # Headers: after #pragma once. Sources: after their first #include (their own header).
    $pattern = if ($file -like '*.h') { '^\s*#pragma once' } else { '^\s*#include\s' }
    $at = [Array]::FindIndex([string[]]$text, [Predicate[string]] { param($l) $l -match $pattern })
    if ($at -lt 0) { "   no place to add includes, left alone: $relative"; continue }
    $added = @($wanted | ForEach-Object { "#include `"$_`"" })
    $text = @($text[0..$at]) + $added + @($text | Select-Object -Skip ($at + 1))
    [IO.File]::WriteAllText($file, (($text -join "`r`n") + "`r`n"), (New-Object Text.UTF8Encoding $false))
    foreach ($line in $added) { Add-Content -Path $manifest -Value "${relative}: added $line" }
    "   ${relative}: added $($wanted -join ', ')"
}

Section 'Why no unity batches (read-only)'
foreach ($xml in "$VrTak\Engine\Saved\UnrealBuildTool\BuildConfiguration.xml", "$VrTak\Game\Saved\UnrealBuildTool\BuildConfiguration.xml",
        "$env:APPDATA\Unreal Engine\UnrealBuildTool\BuildConfiguration.xml", "$([Environment]::GetFolderPath('MyDocuments'))\Unreal Engine\UnrealBuildTool\BuildConfiguration.xml") {
    if (Test-Path -LiteralPath $xml) { "-- $xml"; Get-Content -LiteralPath $xml | Select-Object -First 40 }
}
foreach ($dir in $VrTak, "$VrTak\Game") { if (Test-Path -LiteralPath "$dir\.git") { "git repository: $dir" } }
Get-ChildItem -LiteralPath "$VrTak\Game\Source" -Filter *.Target.cs -ErrorAction SilentlyContinue | ForEach-Object {
    $_.Name; Get-Content $_.FullName | Select-String 'Unity|PCH|BuildEnvironment|DefaultBuildSettings|IncludeOrder' | ForEach-Object { '   ' + $_.Line.Trim() }
}
$lines | Select-String -Pattern 'unity|adaptive|working set' | Select-Object -First 10 | ForEach-Object { '   ' + $_.Line.Trim() }

Section 'Unreadable object files (LNK1106)'
$lines | Select-String -Pattern '^(.+\.obj) : fatal error LNK1106' | ForEach-Object { Resolve-Reported $_.Matches[0].Groups[1].Value } |
    Sort-Object -Unique | ForEach-Object {
        if (Test-Path -LiteralPath $_) { Remove-Item -LiteralPath $_ -Force; "   deleted $_" } else { "   already gone: $_" }
    }

Section 'Disk'
foreach ($path in "$root\CesiumHost", "$env:LOCALAPPDATA\cesium-tak\atak-apks") {
    if (Test-Path $path) { Remove-Item -Recurse -Force $path; "Removed $path (not needed any more)" }
}
$snapshots = Join-Path $env:USERPROFILE '.android\avd\ATAK_PC_Tablet.avd\snapshots'
if ((Test-Path $snapshots) -and -not (Get-Process -Name 'qemu-system-*', 'emulator' -ErrorAction SilentlyContinue)) {
    $size = (Get-ChildItem -LiteralPath $snapshots -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    Remove-Item -Recurse -Force $snapshots
    'Removed the emulator quick-boot snapshots ({0:N1} GB)' -f ($size / 1GB)
}
foreach ($dir in "$VrTak\Engine\Intermediate", "$VrTak\Engine\Plugins", "$VrTak\Game\Intermediate", "$env:LOCALAPPDATA\Android", "$env:USERPROFILE\.android") {
    $size = (Get-ChildItem -LiteralPath $dir -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    '{0,8:N1} GB  {1}' -f ($size / 1GB), $dir
}
Get-PSDrive C, F -ErrorAction SilentlyContinue | ForEach-Object { '{0}: {1:N0} GB free' -f $_.Name, ($_.Free / 1GB) }

Section 'Restart the build (incremental)'
$state = Join-Path $root 'engine-build.pid'
if (Test-Path $state) {
    $old = [int](Get-Content $state -TotalCount 1)
    if (Get-Process -Id $old -ErrorAction SilentlyContinue) {
        "Stopping build $old."
        & taskkill.exe /PID $old /T /F 2>&1 | Select-Object -Last 1
        Start-Sleep -Seconds 15
    }
}
& "$PSScriptRoot\vrtak-plugin-install.ps1" -NoStatus
& "$PSScriptRoot\vrtak-engine-build.ps1" -Restart
