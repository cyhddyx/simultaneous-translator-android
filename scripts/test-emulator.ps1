param([string]$Serial = 'emulator-5580')
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Use a disposable emulator for instrumentation tests, not a personal phone.' }
$projectDir = Split-Path $PSScriptRoot -Parent
Push-Location $projectDir
try {
    $adb = Join-Path $projectDir '.tooling\sdk\platform-tools\adb.exe'
    if (!(Test-Path $adb)) { $adb = (Get-Command adb.exe -ErrorAction Stop).Source }
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Test APK build failed.' }
    & $adb -s $Serial install -r app\build\outputs\apk\debug\app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Could not install debug APK. Use an emulator without a release-signed installation.' }
    & $adb -s $Serial install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Could not install test APK.' }
    $result = & $adb -s $Serial shell am instrument -w com.cyh.translator.test/androidx.test.runner.AndroidJUnitRunner
    $result | Write-Output
    if (($result -join "`n") -notmatch 'OK \(\d+ tests\)') { throw 'Android instrumentation tests failed.' }
} finally { Pop-Location }
