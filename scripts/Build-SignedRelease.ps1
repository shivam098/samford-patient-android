<#
.SYNOPSIS
  Builds the signed release APK of Samford Patient on the maintainer's Windows PC.

.DESCRIPTION
  The release keystore and its DPAPI-protected password live OUTSIDE the repository, by default in
  "$env:USERPROFILE\OneDrive - Microsoft\Documents\Samford-Android-Signing". The password is decrypted
  only into environment variables of this process and the no-daemon Gradle child it starts, then
  removed. Nothing secret is printed or written. CI builds unsigned APKs only.

.EXAMPLE
  $env:JAVA_HOME = '...jdk-21'; $env:ANDROID_HOME = '...\Android\Sdk'
  .\scripts\Build-SignedRelease.ps1 -OutputDirectory "$env:USERPROFILE\Downloads\Samford-Paperless-OPD\patient-app"
#>
[CmdletBinding()]
param(
    [string]$SigningDirectory = (Join-Path $env:USERPROFILE 'OneDrive - Microsoft\Documents\Samford-Android-Signing'),
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$keystore = Join-Path $SigningDirectory 'samford-patient-release.jks'
$credentialFile = Join-Path $SigningDirectory 'samford-patient-signing.clixml'
if (-not (Test-Path $keystore) -or -not (Test-Path $credentialFile)) { throw "Signing material not found in $SigningDirectory" }
if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) { throw 'Set JAVA_HOME and ANDROID_HOME first.' }

$credential = Import-Clixml $credentialFile
$names = 'SAMFORD_PATIENT_KEYSTORE', 'SAMFORD_PATIENT_KEY_ALIAS', 'SAMFORD_PATIENT_STORE_PASSWORD', 'SAMFORD_PATIENT_KEY_PASSWORD'
try {
    $env:SAMFORD_PATIENT_KEYSTORE = $keystore
    $env:SAMFORD_PATIENT_KEY_ALIAS = $credential.UserName
    $env:SAMFORD_PATIENT_STORE_PASSWORD = $credential.GetNetworkCredential().Password
    $env:SAMFORD_PATIENT_KEY_PASSWORD = $env:SAMFORD_PATIENT_STORE_PASSWORD
    Push-Location $repo
    & .\gradlew.bat --no-daemon --console=plain :app:clean :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
    foreach ($n in $names) { Remove-Item "Env:\$n" -ErrorAction SilentlyContinue }
    Remove-Variable credential -ErrorAction SilentlyContinue
}

$apk = Join-Path $repo 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw 'Signed APK was not produced (is release signing configured?)' }
$buildTools = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') | Sort-Object Name -Descending | Select-Object -First 1
& (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verification failed' }

$hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$versionName = [regex]::Match((Get-Content (Join-Path $repo 'app\build.gradle.kts') -Raw), 'versionName = "([^"]+)"').Groups[1].Value
if (-not $versionName) { throw 'versionName not found in app/build.gradle.kts' }
$name = "samford-patient-v$versionName.apk"
if ($OutputDirectory) {
    New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
    Copy-Item $apk (Join-Path $OutputDirectory $name) -Force
    Set-Content -Path (Join-Path $OutputDirectory "$name.sha256") -Value "$hash  $name" -NoNewline
}
Write-Output "APK: $apk"
Write-Output ("Size: {0:N2} MB" -f ((Get-Item $apk).Length / 1MB))
Write-Output "SHA-256: $hash"
