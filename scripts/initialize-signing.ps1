$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$propertiesPath = Join-Path $projectDir 'signing.properties'
$keyPath = Join-Path $projectDir '.signing\translator-release.p12'
if (Test-Path $propertiesPath) {
    if (!(Test-Path $keyPath)) { throw 'Release keystore is missing. Restore it from your backup; do not generate a replacement for an existing release.' }
    return
}
if (Test-Path $keyPath) { throw 'Keystore exists without signing.properties. Restore the signing properties instead of replacing the key.' }
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 or 21 first.' }
New-Item -ItemType Directory -Force (Split-Path $keyPath -Parent) | Out-Null
$secret = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$env:TRANSLATOR_SIGNING_PASSWORD = $secret
try {
    & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $keyPath -storetype PKCS12 -alias translator -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Simultaneous Translator Android' -storepass:env TRANSLATOR_SIGNING_PASSWORD -keypass:env TRANSLATOR_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the release signing key.' }
    @('storeFile=.signing/translator-release.p12', "storePassword=$secret", 'keyAlias=translator', "keyPassword=$secret") | Set-Content $propertiesPath -Encoding ascii
    Write-Output 'Local release signing key created. Back up .signing and signing.properties privately for future updates.'
} finally { Remove-Item Env:TRANSLATOR_SIGNING_PASSWORD; $secret = $null }
