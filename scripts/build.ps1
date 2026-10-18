param([switch]$Debug, [switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
Push-Location $projectDir
try {
    if (!$env:JAVA_HOME -or !(Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $java = Get-Command java.exe -ErrorAction Stop
        $env:JAVA_HOME = Split-Path (Split-Path $java.Source -Parent) -Parent
    }
    if (!(Test-Path 'local.properties')) {
        $sdkDir = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$projectDir\.tooling\sdk", "$env:LOCALAPPDATA\Android\Sdk") |
            Where-Object { $_ -and (Test-Path (Join-Path $_ 'platforms')) } | Select-Object -First 1
        if (!$sdkDir) { throw 'Android SDK not found. Run scripts\setup-sdk.ps1 or install the SDK through Android Studio.' }
        Set-Content local.properties ('sdk.dir=' + $sdkDir.Replace('\', '/')) -Encoding ascii
    }
    if (!$Debug) { & "$PSScriptRoot\initialize-signing.ps1" }
    [string[]]$tasks = if ($Debug) { @(':app:assembleDebug') } else { @(':app:assembleRelease') }
    if (!$SkipChecks) { $tasks += @(':app:testDebugUnitTest', ':app:lintDebug') }
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
    $variant = if ($Debug) { 'debug' } else { 'release' }
    $metadata = Get-Content "app\build\outputs\apk\$variant\output-metadata.json" -Raw | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Unexpected APK version name.' }
    $destination = Join-Path $projectDir "releases\v$version"
    New-Item -ItemType Directory -Force $destination | Out-Null
    $name = "simultaneous-translator-android-$version-$variant.apk"
    Copy-Item -LiteralPath "app\build\outputs\apk\$variant\app-$variant.apk" -Destination (Join-Path $destination $name) -Force
    $lines = Get-ChildItem $destination -Filter *.apk | ForEach-Object { (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.Name }
    Set-Content (Join-Path $destination 'SHA256SUMS.txt') $lines -Encoding ascii
    Write-Output "APK: $destination\$name"
} finally { Pop-Location }
