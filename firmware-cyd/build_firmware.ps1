param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Continue'
Set-Location -LiteralPath $PSScriptRoot

$targets = @(
    @{ Environment = 'cyd_wroom32_4mb'; Model = 'ESP32-WROOM-32'; Board = 'esp32dev'; BuildFlag = ''; UploadPort = 'COM3'; MonitorPort = 'COM3' },
    @{ Environment = 'esp32_s2_4mb'; Model = 'ESP32-S2'; Board = 'esp32-s2-saola-1'; BuildFlag = '-DESP_DOWNLOAD_TARGET_S2'; UploadPort = ''; MonitorPort = '' },
    @{ Environment = 'esp32_s3_4mb'; Model = 'ESP32-S3'; Board = 'esp32-s3-devkitc1-n4r2'; BuildFlag = '-DESP_DOWNLOAD_TARGET_S3'; UploadPort = ''; MonitorPort = '' },
    @{ Environment = 'esp32_c3_4mb'; Model = 'ESP32-C3'; Board = 'esp32-c3-devkitm-1'; BuildFlag = '-DESP_DOWNLOAD_TARGET_C3'; UploadPort = ''; MonitorPort = '' },
    @{ Environment = 'esp32_c5_4mb'; Model = 'ESP32-C5'; Board = 'esp32-c5-devkitc1-n4'; BuildFlag = '-DESP_DOWNLOAD_TARGET_C5'; UploadPort = ''; MonitorPort = '' },
    @{ Environment = 'esp32_c6_4mb'; Model = 'ESP32-C6'; Board = 'esp32-c6-devkitm-1'; BuildFlag = '-DESP_DOWNLOAD_TARGET_C6'; UploadPort = ''; MonitorPort = '' }
)

$releaseRoot = Join-Path $PSScriptRoot 'releases'
foreach ($target in $targets) {
    $modelDirectory = Join-Path $releaseRoot $target.Model
    New-Item -ItemType Directory -Path $modelDirectory -Force | Out-Null
    $releaseImage = Join-Path $modelDirectory ($target.Model + '-firmware.bin')
    if (-not $SkipBuild) {
        Write-Host "Building $($target.Model) ($($target.Environment))..."
        & pio run -e $target.Environment
        if ($LASTEXITCODE -ne 0) { throw "PlatformIO build failed for $($target.Model)." }

        $builtImage = Join-Path $PSScriptRoot (".pio\build\{0}\firmware.bin" -f $target.Environment)
        if (-not (Test-Path -LiteralPath $builtImage)) { throw "PlatformIO did not produce firmware.bin for $($target.Model)." }
        Copy-Item -LiteralPath $builtImage -Destination $releaseImage -Force
    } elseif (-not (Test-Path -LiteralPath $releaseImage)) {
        throw "Cannot refresh source package because firmware image is missing: $releaseImage"
    }

    $size = (Get-Item -LiteralPath $releaseImage).Length
    if ($size -le 0 -or $size -gt 32MB) { throw "Built image size is invalid for $($target.Model)." }
    Write-Host "Ready: $releaseImage ($([math]::Round($size / 1KB)) KB)" -ForegroundColor Green

    $sourceDirectory = Join-Path $modelDirectory 'source'
    $sourceCodeDirectory = Join-Path $sourceDirectory 'src'
    New-Item -ItemType Directory -Path $sourceCodeDirectory -Force | Out-Null
    Copy-Item -Path (Join-Path $PSScriptRoot 'src\*') -Destination $sourceCodeDirectory -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'partitions.csv') -Destination (Join-Path $sourceDirectory 'partitions.csv') -Force

    $sourceConfig = @(
        '[platformio]',
        "default_envs = $($target.Environment)",
        '',
        '[env]',
        'platform = https://github.com/pioarduino/platform-espressif32/releases/download/55.03.39/platform-espressif32.zip',
        'framework = arduino',
        'upload_speed = 460800',
        'monitor_speed = 115200',
        'monitor_filters = time, esp32_exception_decoder',
        'board_build.flash_size = 4MB',
        'board_upload.flash_size = 4MB',
        'board_upload.maximum_size = 3932160',
        'board_build.partitions = partitions.csv',
        'board_build.filesystem = littlefs',
        'lib_deps =',
        '    bblanchon/ArduinoJson@^7.4.2',
        '',
        "[env:$($target.Environment)]",
        "board = $($target.Board)"
    )
    if ($target.BuildFlag) { $sourceConfig += "build_flags = $($target.BuildFlag)" }
    if ($target.UploadPort) { $sourceConfig += "upload_port = $($target.UploadPort)" }
    if ($target.MonitorPort) { $sourceConfig += "monitor_port = $($target.MonitorPort)" }
    [System.IO.File]::WriteAllLines(
        (Join-Path $sourceDirectory 'platformio.ini'),
        [string[]]$sourceConfig,
        [System.Text.UTF8Encoding]::new($false)
    )
    Set-Content -LiteralPath (Join-Path $sourceDirectory 'README.txt') -Value @(
        "$($target.Model) firmware source package",
        '',
        'This standalone project includes its own platformio.ini, board-specific build flags, partition table, and complete src directory.',
        'Build from this directory with PlatformIO:',
        "  pio run -e $($target.Environment)",
        '',
        'The generated firmware.bin is an application image intended for flash offset 0x10000.',
        'The source files and partition table are copied from the build that produced the adjacent firmware image.',
        $(if ($target.UploadPort) { "This WROOM-32 configuration uses $($target.UploadPort) for upload and serial monitoring." } else { 'Set the upload port for your board when flashing from this source package.' })
    ) -Encoding UTF8
    Write-Host "Source: $sourceDirectory" -ForegroundColor Green
}
