$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$sdkDir = Join-Path $projectDir '.tooling\sdk'
$downloadsDir = Join-Path $projectDir '.tooling\downloads'
if (!$env:JAVA_HOME -or !(Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $java = Get-Command java.exe -ErrorAction Stop
    $env:JAVA_HOME = Split-Path (Split-Path $java.Source -Parent) -Parent
}
$manager = Join-Path $sdkDir 'cmdline-tools\19.0\bin\sdkmanager.bat'
if (!(Test-Path $manager)) {
    New-Item -ItemType Directory -Force $downloadsDir | Out-Null
    $archive = Join-Path $downloadsDir 'commandlinetools-19.zip'
    Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' -OutFile $archive
    # Check against the checksum in Google's repository2-1.xml before extracting.
    if ((Get-FileHash $archive -Algorithm SHA1).Hash -ne '54a582f3bf73e04253602f2d1c80bd5868aac115') { throw 'Android command-line tools checksum mismatch.' }
    $staging = Join-Path $projectDir '.tooling\sdk-tools19'
    Expand-Archive -LiteralPath $archive -DestinationPath $staging -Force
    New-Item -ItemType Directory -Force (Join-Path $sdkDir 'cmdline-tools') | Out-Null
    Copy-Item -LiteralPath (Join-Path $staging 'cmdline-tools') -Destination (Join-Path $sdkDir 'cmdline-tools\19.0') -Recurse -Force
}
# Displays Google's SDK licenses for interactive acceptance.
& $manager "--sdk_root=$sdkDir" --licenses
& $manager "--sdk_root=$sdkDir" 'platforms;android-35' 'build-tools;34.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed.' }
Set-Content (Join-Path $projectDir 'local.properties') ('sdk.dir=' + $sdkDir.Replace('\', '/')) -Encoding ascii
Write-Output "SDK ready: $sdkDir"
