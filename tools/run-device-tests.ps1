<#
.SYNOPSIS
  Runs the instrumented tests on ONE named emulator or device, never on "whatever is attached".

.DESCRIPTION
  `gradlew connectedDebugAndroidTest` runs on every attached device and uninstalls the app afterwards, which wipes a
  real phone's sign-in. This script builds the app and test APKs, installs them on the serial you name, runs the
  instrumentation there, and leaves the app installed. It refuses a physical device unless -AllowPhysical is given.

.EXAMPLE
  .\tools\run-device-tests.ps1 -Serial emulator-5554
#>
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$AllowPhysical,
    [string]$Filter
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'

if ($Serial -notmatch '^emulator-\d+$' -and -not $AllowPhysical) {
    throw "'$Serial' is not an emulator. Pass -AllowPhysical only if you mean to run the tests on a real device."
}
$attached = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device" }
if (-not $attached) { throw "Device '$Serial' is not attached and ready." }

Push-Location $root
try {
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }

    $app = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    $test = Join-Path $root 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    & $adb -s $Serial install -r -t $app | Out-Null
    & $adb -s $Serial install -r -t $test | Out-Null

    $args = @('-s', $Serial, 'shell', 'am', 'instrument', '-w')
    if ($Filter) { $args += @('-e', 'class', $Filter) }
    $args += 'dev.ahpclient.test/androidx.test.runner.AndroidJUnitRunner'
    $output = & $adb @args 2>&1 | Out-String
    Write-Host $output.Trim()

    if ($output -match 'OK \((\d+) tests?\)') { Write-Host "PASS on $Serial ($($Matches[1]) tests)"; exit 0 }
    Write-Host "FAIL on $Serial"; exit 1
}
finally {
    Pop-Location
}
