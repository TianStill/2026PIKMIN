param(
    [switch]$ConfirmRelease,
    [string]$Version = "",
    [string]$Notes = "Application update and improvements"
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
if (-not $ConfirmRelease) { throw "Release requires explicit user approval. Re-run with -ConfirmRelease only after approval." }
$env:Path = "C:\Program Files\GitHub CLI;C:\Program Files\Git\cmd;" + $env:Path
$preferredJdk = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
if (Test-Path -LiteralPath (Join-Path $preferredJdk 'bin\java.exe')) {
    $env:JAVA_HOME = $preferredJdk
} else {
    $javaCommand = Get-Command java -ErrorAction Stop
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
}

function Convert-ProtectedText([string]$value) {
    $secure = ConvertTo-SecureString $value
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

$signingConfigPath = Join-Path $env:USERPROFILE '.android\pikmin-release-signing.dpapi.json'
if (([string]::IsNullOrWhiteSpace($env:PIKMIN_RELEASE_STORE_FILE) -or
     [string]::IsNullOrWhiteSpace($env:PIKMIN_RELEASE_STORE_PASSWORD) -or
     [string]::IsNullOrWhiteSpace($env:PIKMIN_RELEASE_KEY_ALIAS) -or
     [string]::IsNullOrWhiteSpace($env:PIKMIN_RELEASE_KEY_PASSWORD)) -and
    (Test-Path -LiteralPath $signingConfigPath -PathType Leaf)) {
    $signingConfig = Get-Content -LiteralPath $signingConfigPath -Raw | ConvertFrom-Json
    $env:PIKMIN_RELEASE_STORE_FILE = $signingConfig.storeFile
    $env:PIKMIN_RELEASE_KEY_ALIAS = $signingConfig.keyAlias
    $env:PIKMIN_RELEASE_STORE_PASSWORD = Convert-ProtectedText $signingConfig.storePasswordProtected
    $env:PIKMIN_RELEASE_KEY_PASSWORD = Convert-ProtectedText $signingConfig.keyPasswordProtected
}
function Assert-Exit([string]$operation) {
    if ($LASTEXITCODE -ne 0) { throw "$operation failed (exit $LASTEXITCODE)." }
}
Push-Location $PSScriptRoot
try {
    foreach ($key in @('PIKMIN_RELEASE_STORE_FILE', 'PIKMIN_RELEASE_STORE_PASSWORD', 'PIKMIN_RELEASE_KEY_ALIAS', 'PIKMIN_RELEASE_KEY_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key))) { throw "Missing signing setting: $key" }
    }
    if (-not (Test-Path -LiteralPath $env:PIKMIN_RELEASE_STORE_FILE -PathType Leaf)) { throw "Signing keystore does not exist." }
    $branch = git branch --show-current
    Assert-Exit 'Read branch'
    if ($branch -ne 'main') { throw "Release must run from main." }
    $changes = git status --porcelain
    Assert-Exit 'Read working tree'
    if ($changes) { throw "Commit reviewed changes before releasing. Working tree must be clean." }
    $remote = git remote get-url origin
    Assert-Exit 'Read origin'
    if ($remote -notin @('https://github.com/TianStill/2026PIKMIN.git', 'git@github.com:TianStill/2026PIKMIN.git')) { throw "Unexpected release repository." }
    gh auth status
    Assert-Exit 'GitHub authentication'
    git fetch origin main:refs/remotes/origin/main --tags
    Assert-Exit 'Fetch main'
    $behind = git rev-list --count HEAD..origin/main
    Assert-Exit 'Check remote history'
    if ([int]$behind -ne 0) { throw "Local main is behind or diverged from origin/main." }

    $gradleFile = Join-Path $PSScriptRoot 'app/build.gradle.kts'
    $original = [IO.File]::ReadAllBytes($gradleFile)
    $content = [IO.File]::ReadAllText($gradleFile)
    $codeMatch = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')
    $nameMatch = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"')
    if (-not $codeMatch.Success -or -not $nameMatch.Success) { throw "Cannot read version." }
    $currentVersion = [version]$nameMatch.Groups[1].Value
    if ([string]::IsNullOrWhiteSpace($Version)) { $Version = "$($currentVersion.Major).$($currentVersion.Minor).$($currentVersion.Build + 1)" }
    $Version = $Version.TrimStart('v', 'V')
    if ($Version -notmatch '^\d+\.\d+\.\d+$' -or [version]$Version -le $currentVersion) { throw "Release version must be a newer major.minor.patch version." }
    $tag = "v$Version"
    $existingTags = git tag --list $tag
    Assert-Exit 'Check tag'
    if ($existingTags) { throw "Tag already exists: $tag" }
    $newCode = [int]$codeMatch.Groups[1].Value + 1
    $committed = $false
    $notesFile = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
    try {
        $content = [regex]::Replace($content, 'versionCode\s*=\s*\d+', "versionCode = $newCode")
        $content = [regex]::Replace($content, 'versionName\s*=\s*"[^"]+"', "versionName = `"$Version`"")
        [IO.File]::WriteAllText($gradleFile, $content, [Text.UTF8Encoding]::new($false))
        & ./gradlew.bat testDebugUnitTest lintDebug assembleRelease
        Assert-Exit 'Release validation and build'
        $apkPath = Join-Path $PSScriptRoot 'app/build/outputs/apk/release/app-release.apk'
        if (-not (Test-Path -LiteralPath $apkPath)) { throw "Signed release APK missing." }
        $hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $checksumPath = "$apkPath.sha256"
        [IO.File]::WriteAllText($checksumPath, "$hash  app-release.apk`n", [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText($notesFile, $Notes, [Text.UTF8Encoding]::new($false))
        git add -- app/build.gradle.kts
        Assert-Exit 'Stage version'
        git commit -m "chore(release): bump version to $tag"
        Assert-Exit 'Commit version'
        $committed = $true
        git tag -a $tag -m "Release $tag"
        Assert-Exit 'Create tag'
        git push --atomic origin HEAD:main "refs/tags/$tag"
        Assert-Exit 'Push release commit and tag'
        gh release create $tag $apkPath $checksumPath --verify-tag --title "Fake GPS Pro $tag" --notes-file $notesFile
        Assert-Exit 'Publish release'
        Write-Host "Published $tag successfully."
    } catch {
        if (-not $committed) {
            [IO.File]::WriteAllBytes($gradleFile, $original)
            git restore --staged -- app/build.gradle.kts
        }
        throw
    } finally {
        if (Test-Path -LiteralPath $notesFile) { Remove-Item -LiteralPath $notesFile -Force }
    }
} finally { Pop-Location }
