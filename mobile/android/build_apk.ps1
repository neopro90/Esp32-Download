$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# --- Tim JDK (Android Studio JBR hoac JAVA_HOME) ---
$jdkCandidates = @(
    'C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot',
    'C:\Program Files\Android\Android Studio\jbr',
    'C:\Program Files\Android\Android Studio1\jbr',
    $env:JAVA_HOME
) | Select-Object -Unique | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) }
if (-not $jdkCandidates) { throw 'Khong tim thay JDK 17+. Cai Android Studio hoac dat JAVA_HOME.' }
$env:JAVA_HOME = @($jdkCandidates)[0]

# --- Tim Android SDK ---
$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) | Where-Object { $_ -and (Test-Path $_) }
if (-not $sdkCandidates) { throw 'Khong tim thay Android SDK.' }
$env:ANDROID_HOME = @($sdkCandidates)[0]
$sdkForProps = $env:ANDROID_HOME -replace '\\', '\\'
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'local.properties') -Value "sdk.dir=$sdkForProps" -Encoding ASCII

# --- Tim Gradle: gradlew > gradle trong PATH > ban Gradle 8.13 da tai san ---
$gradle = $null
$wrapper = Join-Path $PSScriptRoot 'gradlew.bat'
if (Test-Path $wrapper) { $gradle = $wrapper }
if (-not $gradle) { $cmd = Get-Command gradle.bat -ErrorAction SilentlyContinue; if ($cmd) { $gradle = $cmd.Source } }
if (-not $gradle) {
    $dists = Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
    $found = Get-ChildItem -Path $dists -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'gradle-8\.' } | Select-Object -First 1
    if ($found) { $gradle = $found.FullName }
}
if (-not $gradle) { throw 'Khong tim thay Gradle. Mo thu muc mobile\android bang Android Studio va de no tai Gradle 8.13.' }

Write-Host "JDK    : $env:JAVA_HOME"
Write-Host "SDK    : $env:ANDROID_HOME"
Write-Host "Gradle : $gradle"

& $gradle --no-daemon assembleStandardDebug assembleFreshTestDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle build that bai, ma loi $LASTEXITCODE" }

$apkSource = Join-Path $PSScriptRoot 'app\build\outputs\apk\standard\debug\app-standard-debug.apk'
if (-not (Test-Path $apkSource)) { throw "Khong thay APK tai $apkSource" }
$freshApkSource = Join-Path $PSScriptRoot 'app\build\outputs\apk\freshTest\debug\app-freshTest-debug.apk'
if (-not (Test-Path $freshApkSource)) { throw "Khong thay APK test sach tai $freshApkSource" }
$size = [math]::Round((Get-Item -LiteralPath $apkSource).Length / 1KB)
$freshSize = [math]::Round((Get-Item -LiteralPath $freshApkSource).Length / 1KB)
Write-Host "APK build xong ($size KB) tai dung cau truc outputs:" -ForegroundColor Green
Write-Host "  $((Resolve-Path $apkSource).Path)"
Write-Host "APK test sach ($freshSize KB):" -ForegroundColor Green
Write-Host "  $((Resolve-Path $freshApkSource).Path)"
