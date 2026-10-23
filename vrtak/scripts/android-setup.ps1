<#
Installs the Android SDK pieces for an ATAK emulator on this PC (approved by
the PC's owner), all under %LOCALAPPDATA%, no admin:
  cesium-tak\jdk17         portable JDK 17 (sdkmanager needs Java)
  Android\Sdk              command-line tools, platform-tools, emulator,
                           Android 14 x86_64 Google APIs image (it runs arm64
                           apps such as ATAK through ARM translation)
  AVD ATAK_PC_Tablet       Pixel Tablet profile
Rerunning skips what is installed. Ends with the emulator's acceleration check.
#>
param([string]$Image = 'system-images;android-34;google_apis;x86_64', [string]$Avd = 'ATAK_PC_Tablet')
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
. "$PSScriptRoot\common.ps1"
$base = Join-Path $env:LOCALAPPDATA 'cesium-tak'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
New-Item -ItemType Directory -Force -Path $base, $sdk | Out-Null

function Expand-Zip($zip, $to) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $to)
}

Section 'JDK 17'
$jdk = Get-ChildItem $base -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdk) {
    $zip = Join-Path $base 'jdk17.zip'
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 900 -OutFile $zip 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse'
    Expand-Zip $zip $base
    Remove-Item $zip
    $jdk = Get-ChildItem $base -Directory -Filter 'jdk-17*' | Select-Object -First 1
}
$env:JAVA_HOME = $jdk.FullName
$env:Path = "$($jdk.FullName)\bin;$env:Path"
"JAVA_HOME=$env:JAVA_HOME"
Invoke-Bounded (Join-Path $jdk.FullName 'bin\java.exe') @('-version') 60

Section 'Command-line tools'
$tools = Join-Path $sdk 'cmdline-tools\latest'
if (-not (Test-Path (Join-Path $tools 'bin\sdkmanager.bat'))) {
    $zip = Join-Path $base 'cmdline-tools.zip'
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 900 -OutFile $zip 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
    $tmp = Join-Path $base 'cmdline-tools-tmp'
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    Expand-Zip $zip $tmp
    New-Item -ItemType Directory -Force -Path (Split-Path $tools) | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') $tools
    Remove-Item -Recurse -Force $tmp, $zip
}
$sdkmanager = Join-Path $tools 'bin\sdkmanager.bat'
$avdmanager = Join-Path $tools 'bin\avdmanager.bat'
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

Section 'Packages'
# Accept the SDK licences (the owner approved installing the emulator). Piped
# "y" answers do not reach sdkmanager through the .bat, so write the licence
# files the SDK tools check (the hashes of the accepted licence texts).
$licenses = Join-Path $sdk 'licenses'
New-Item -ItemType Directory -Force -Path $licenses | Out-Null
@{
    'android-sdk-license' = @('24333f8a63b6825ea9c5514f83c2829b004d1fee', '8933bad161af4178b1185d1a37fbf41ea5269c55', 'd56f5187479451eabf01fb78af6dfcb131a6481e')
    'android-sdk-preview-license' = @('84831b9409646a918e30573bab4c9c91346d8abd')
    'android-sdk-arm-dbt-license' = @('859f317696f67ef3d7f30a50a5560e7834b43903')
}.GetEnumerator() | ForEach-Object {
    Set-Content -Encoding ascii -Path (Join-Path $licenses $_.Key) -Value ([Environment]::NewLine + ($_.Value -join [Environment]::NewLine))
}
& $sdkmanager --sdk_root=$sdk --licenses 2>&1 | Select-Object -First 2
$started = Get-Date
& $sdkmanager --sdk_root=$sdk --install 'platform-tools' 'emulator' $Image 2>&1 |
    Where-Object { $_ -notmatch '^\[=*\s*\]|^\s*$' } | Select-Object -Last 15
"Install took $([int]((Get-Date) - $started).TotalMinutes) min"
foreach ($p in 'platform-tools\adb.exe', 'emulator\emulator.exe', ('system-images\' + ($Image -replace '^system-images;', '' -replace ';', '\'))) {
    "   $p : $(Test-Path (Join-Path $sdk $p))"
}

Section "AVD $Avd"
$avdDir = Join-Path $env:USERPROFILE ".android\avd\$Avd.avd"
if (-not (Test-Path $avdDir)) {
    'no' | & $avdmanager create avd -n $Avd -k $Image -d pixel_tablet --force 2>&1 | Select-Object -Last 5
}
$config = Join-Path $avdDir 'config.ini'
if (Test-Path $config) {
    # A tablet in landscape, with room for ATAK's data.
    $lines = Get-Content $config | Where-Object { $_ -notmatch '^(hw\.ramSize|disk\.dataPartition\.size|hw\.keyboard|hw\.gpu\.enabled|hw\.gpu\.mode)=' }
    $lines + @('hw.ramSize=6144', 'disk.dataPartition.size=12G', 'hw.keyboard=yes', 'hw.gpu.enabled=yes', 'hw.gpu.mode=host') | Set-Content -Encoding ascii $config
    Get-Content $config | Select-String 'image.sysdir|hw.device.name|hw.ramSize|hw.lcd|abi.type' | ForEach-Object { "   $($_.Line)" }
}

Section 'Emulator acceleration'
Invoke-Bounded (Join-Path $sdk 'emulator\emulator.exe') @('-accel-check') 120
Invoke-Bounded (Join-Path $sdk 'emulator\emulator.exe') @('-list-avds') 60
