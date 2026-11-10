param(
    [string]$Apk = "app\build\outputs\apk\release\app-release.apk",
    [string]$VersionCode = "",
    [string]$VersionName = "",
    [string]$Notes = "Perbaikan dan peningkatan terbaru.",
    [string]$BaseUrl = "",
    [string]$OutDir = "updates"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path $Apk)) {
    throw "APK tidak ditemukan: $Apk. Jalankan .\gradlew.bat --offline :app:assembleRelease dulu."
}

$gradle = Get-Content "app\build.gradle.kts" -Raw
if (-not $VersionCode) {
    $m = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)')
    if ($m.Success) { $VersionCode = $m.Groups[1].Value }
}
if (-not $VersionName) {
    $m = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"')
    if ($m.Success) { $VersionName = $m.Groups[1].Value }
}
if (-not $VersionCode -or -not $VersionName) {
    throw "versionCode/versionName tidak ditemukan di app\build.gradle.kts"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$dest = Join-Path $OutDir "app-release.apk"
Copy-Item $Apk $dest -Force

$sha = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
$apkUrl = if ($BaseUrl) { "$($BaseUrl.TrimEnd('/'))/app-release.apk" } else { "app-release.apk" }

$manifest = [ordered]@{
    versionCode = [int]$VersionCode
    versionName = $VersionName
    apkUrl      = $apkUrl
    sha256      = $sha
    notes       = $Notes
} | ConvertTo-Json

$jsonPath = Join-Path $OutDir "update.json"
[System.IO.File]::WriteAllText(
    (Resolve-Path $OutDir).Path + "\update.json",
    $manifest,
    (New-Object System.Text.UTF8Encoding($false))
)

Write-Host ""
Write-Host "OK  versi $VersionName (versionCode $VersionCode)"
Write-Host "SHA-256 : $sha"
Write-Host "Manifest: $jsonPath"
Write-Host "APK     : $dest"
Write-Host ""
Write-Host "Otomatis: push tag vX.Y -> GitHub Actions build + publish Release (APK + update.json)."
Write-Host "Manual  : unggah kedua file di atas ke folder HTTPS yang sama, lalu isi URL update.json di aplikasi."
