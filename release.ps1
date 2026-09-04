param(
    [string]$Version = "",
    [string]$Notes = "Application update and improvements"
)

$ErrorActionPreference = "Stop"

# 1. Setup environment paths
$env:Path = "C:\Program Files\GitHub CLI;C:\Program Files\Git\cmd;C:\Program Files\Git\bin;" + $env:Path
$jdkPath = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
if (Test-Path $jdkPath) {
    $env:JAVA_HOME = $jdkPath
}

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "  Fake GPS Pro - Release and Sync to GitHub" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

# 2. Read app/build.gradle.kts
$gradleFile = Join-Path $PSScriptRoot "app\build.gradle.kts"
if (-not (Test-Path $gradleFile)) {
    Write-Error "Cannot find $gradleFile"
}
$content = Get-Content $gradleFile -Raw

$codeMatch = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')
$nameMatch = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"')

if (-not $codeMatch.Success -or -not $nameMatch.Success) {
    Write-Error "Failed to parse versionCode or versionName from build.gradle.kts"
}

$currCode = $codeMatch.Groups[1].Value
$currName = $nameMatch.Groups[1].Value

$newCode = [int]$currCode + 1
if ([string]::IsNullOrWhiteSpace($Version)) {
    $parts = $currName.Split('.')
    if ($parts.Length -ge 3) {
        $lastNum = [int]$parts[2] + 1
        $newName = "$($parts[0]).$($parts[1]).$lastNum"
    } else {
        $newName = "$currName.1"
    }
} else {
    $newName = $Version.TrimStart('v').TrimStart('V').Trim()
}

$tag = "v$newName"

Write-Host "Current version: v$currName (Code: $currCode)" -ForegroundColor Yellow
Write-Host "Target version:  $tag (Code: $newCode)" -ForegroundColor Green
Write-Host "Release notes:   $Notes" -ForegroundColor Gray

# 3. Update app/build.gradle.kts
$content = [regex]::Replace($content, 'versionCode\s*=\s*\d+', "versionCode = $newCode")
$replacement = "versionName = `"$newName`""
$content = [regex]::Replace($content, 'versionName\s*=\s*"[^"]+"', $replacement)
[System.IO.File]::WriteAllText($gradleFile, $content, [System.Text.Encoding]::UTF8)
Write-Host "build.gradle.kts updated successfully." -ForegroundColor Green

# 4. Build APK
Write-Host "`nBuilding APK with Gradle..." -ForegroundColor Cyan
$gradlewBat = Join-Path $PSScriptRoot "gradlew.bat"
& $gradlewBat assembleDebug
if ($LASTEXITCODE -ne 0) {
    Write-Error "Gradle build failed!"
}

$apkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    Write-Error "Cannot find APK at $apkPath"
}
$apkSize = (Get-Item $apkPath).Length / 1MB
Write-Host ("APK built successfully: {0:N2} MB" -f $apkSize) -ForegroundColor Green

# 5. Git Commit & Tag
Write-Host "`nCommitting version change..." -ForegroundColor Cyan
Copy-Item $apkPath (Join-Path $PSScriptRoot "app-debug.apk") -Force
git add -A
git commit -m "chore(release): bump version to $tag - $Notes"
git tag -a $tag -m "Release $tag - $Notes"

# 6. Push to GitHub
Write-Host "Pushing commits and tags to GitHub..." -ForegroundColor Cyan
git push origin main --tags
if ($LASTEXITCODE -ne 0) {
    Write-Error "Git push failed!"
}

# 7. Create GitHub Release and upload APK
Write-Host "`nCreating GitHub Release and uploading APK..." -ForegroundColor Cyan
& "C:\Program Files\GitHub CLI\gh.exe" release create $tag $apkPath --title "Fake GPS Pro $tag" --notes "$Notes"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Release creation returned non-zero, attempting upload with clobber..." -ForegroundColor Yellow
    & "C:\Program Files\GitHub CLI\gh.exe" release upload $tag $apkPath --clobber
}

Write-Host "`nRelease $tag successfully published and synced to GitHub!" -ForegroundColor Green
Write-Host "URL: https://github.com/TianStill/2026PIKMIN/releases/tag/$tag" -ForegroundColor Cyan
Write-Host "Mobile app and in-game updater will detect this update automatically!" -ForegroundColor Yellow
