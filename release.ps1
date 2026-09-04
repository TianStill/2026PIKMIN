<#
.SYNOPSIS
  Fake GPS Pro 自動版次更新、編譯與 GitHub Release 同步腳本
.DESCRIPTION
  自動推進版本號 (versionCode & versionName)、編譯最新 APK、
  推播 Commit 與 Git Tag 至 GitHub，並自動在 GitHub 建立 Release 上傳 APK。
.EXAMPLE
  .\release.ps1 -Version 1.0.1 -Notes "新增從遊戲中一鍵安裝更新與 GitHub 同步功能"
  .\release.ps1 (自動遞增修訂號，如 1.0.0 -> 1.0.1)
#>

param(
    [string]$Version = "",
    [string]$Notes = "應用程式功能更新與最佳化"
)

$ErrorActionPreference = "Stop"

# 1. 設置環境變數 (Git, GitHub CLI 與 JDK 17)
$env:Path = "C:\Program Files\GitHub CLI;C:\Program Files\Git\cmd;C:\Program Files\Git\bin;" + $env:Path
$jdkPath = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
if (Test-Path $jdkPath) {
    $env:JAVA_HOME = $jdkPath
}

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "  🚀 Fake GPS Pro - 自動發布與同步至 GitHub" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

# 2. 讀取目前的 build.gradle.kts
$gradleFile = "$PSScriptRoot\app\build.gradle.kts"
if (-not (Test-Path $gradleFile)) {
    Write-Error "找不到 $gradleFile"
}
$content = Get-Content $gradleFile -Raw

# 取得目前 versionCode 與 versionName
$currCode = [regex]::Match($content, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$currName = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value

if (-not $currCode -or -not $currName) {
    Write-Error "無法從 build.gradle.kts 解析目前的版號"
}

# 計算新版號
$newCode = [int]$currCode + 1
if ([string]::IsNullOrWhiteSpace($Version)) {
    $parts = $currName.Split('.')
    if ($parts.Length -ge 3) {
        $parts[2] = ([int]$parts[2] + 1).ToString()
        $newName = [string]::Join('.', $parts)
    } else {
        $newName = "$($currName).1"
    }
} else {
    $newName = $Version.TrimStart('v', 'V').Trim()
}

$tag = "v$newName"

Write-Host "📌 目前版本: v$currName (Code: $currCode)" -ForegroundColor Yellow
Write-Host "✨ 升級版本: $tag (Code: $newCode)" -ForegroundColor Green
Write-Host "📝 更新說明: $Notes" -ForegroundColor Gray

# 3. 更新 app/build.gradle.kts
$content = [regex]::Replace($content, 'versionCode\s*=\s*\d+', "versionCode = $newCode")
$content = [regex]::Replace($content, 'versionName\s*=\s*"[^"]+"', ('versionName = "' + $newName + '"'))
Set-Content -Path $gradleFile -Value $content -NoNewline
Write-Host "✅ 已更新 app/build.gradle.kts 版次！" -ForegroundColor Green

# 4. 編譯最新 APK
Write-Host "`n🔨 正在使用 Gradle 編譯最新 APK..." -ForegroundColor Cyan
& "$PSScriptRoot\gradlew.bat" assembleDebug
if ($LASTEXITCODE -ne 0) {
    Write-Error "APK 編譯失敗！請檢查錯誤訊息。"
}

$apkPath = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    Write-Error "編譯完成但找不到 APK: $apkPath"
}
$apkSize = (Get-Item $apkPath).Length / 1MB
Write-Host ("✅ APK 建置成功: {0:N2} MB" -f $apkSize) -ForegroundColor Green

# 5. Git Commit & Tag
Write-Host "`n📦 正在提交版本變更至 Git..." -ForegroundColor Cyan
git add app/build.gradle.kts
git commit -m "chore(release): bump version to $tag"
git tag -a $tag -m "Release $tag - $Notes"

# 6. 推送至 GitHub
Write-Host "🌐 正在推播分支與 Tag 至 GitHub..." -ForegroundColor Cyan
git push origin main --tags
if ($LASTEXITCODE -ne 0) {
    Write-Error "推播至 GitHub 失敗！"
}

# 7. 使用 GitHub CLI 建立 Release 並掛載 APK
Write-Host "`n🚀 正在建立 GitHub Release 並上傳 APK..." -ForegroundColor Cyan
gh release create $tag $apkPath --title "Fake GPS Pro $tag" --notes "$Notes"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Release 可能已存在，嘗試上傳覆蓋 APK..." -ForegroundColor Yellow
    gh release upload $tag $apkPath --clobber
}

Write-Host "`n🎉 恭喜！版本 $tag 已成功發布並同步至 GitHub！" -ForegroundColor Green
Write-Host "🔗 網址: https://github.com/TianStill/2026PIKMIN/releases/tag/$tag" -ForegroundColor Cyan
Write-Host "📱 手機端開啟 App 或遊戲時，將會自動偵測並提示直接安裝此更新檔！" -ForegroundColor Yellow
