# ==============================================================================
# ESP32 Download - Trình cài đặt tự động, Nhận diện cổng COM & Nạp Firmware
# ==============================================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Host.UI.RawUI.WindowTitle = "ESP32 Download - All-in-One Installer"

function Print-Banner {
    Clear-Host
    Write-Host "=================================================================" -ForegroundColor Cyan
    Write-Host "       ESP32 CHATBOT AUTO DOWNLOADER - BỘ CÀI ĐẶT TOÀN DIỆN      " -ForegroundColor Green
    Write-Host "                   Hỗ trợ Windows & ESP32 v1.3                   " -ForegroundColor Yellow
    Write-Host "=================================================================" -ForegroundColor Cyan
    Write-Host ""
}

Print-Banner

$rootDir = Split-Path -Parent $PSScriptRoot
$releasesDir = Join-Path $rootDir "releases\v1.3"
$localHostDir = Join-Path $rootDir "local-host"

# ------------------------------------------------------------------------------
# 1. KIỂM TRA PYTHON & ESPTOOL
# ------------------------------------------------------------------------------
Write-Host "[BƯỚC 1/4] Kiểm tra công cụ Python & Esptool..." -ForegroundColor Cyan

$pythonCmd = $null
$esptoolCmd = $null

if (Get-Command "python" -ErrorAction SilentlyContinue) {
    $pythonCmd = "python"
} elseif (Get-Command "py" -ErrorAction SilentlyContinue) {
    $pythonCmd = "py"
}

if (-not $pythonCmd) {
    Write-Host "[!] Không tìm thấy Python trên máy tính." -ForegroundColor Yellow
    Write-Host "    Đang thử kiểm tra qua winget..." -ForegroundColor Gray
    try {
        winget install Python.Python.3.12 --accept-package-agreements --accept-source-agreements --silent
        $pythonCmd = "python"
    } catch {
        Write-Host "[X] Không thể tự cài Python. Vui lòng cài Python từ python.org hoặc Microsoft Store." -ForegroundColor Red
    }
}

if ($pythonCmd) {
    Write-Host "[OK] Python: $pythonCmd" -ForegroundColor Green
    # Kiểm tra esptool
    $hasEsptool = & $pythonCmd -m esptool version 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    Đang cài đặt esptool qua pip..." -ForegroundColor Gray
        & $pythonCmd -m pip install --upgrade esptool --quiet
    }
    $esptoolCmd = "$pythonCmd -m esptool"
}

# ------------------------------------------------------------------------------
# 2. QUÉT CỔNG COM VÀ TỰ ĐỘNG NHẬN DIỆN ESP32
# ------------------------------------------------------------------------------
Write-Host ""
Write-Host "[BƯỚC 2/4] Quét cổng COM tìm phần cứng ESP32..." -ForegroundColor Cyan

function Get-AvailableComPorts {
    $ports = @()
    try {
        $ports = [System.IO.Ports.SerialPort]::GetPortNames() | Sort-Object -Unique
    } catch {}
    return $ports
}

$detectedPort = $null
while (-not $detectedPort) {
    $ports = Get-AvailableComPorts
    if ($ports.Count -eq 1) {
        $detectedPort = $ports[0]
        Write-Host "[OK] Phát hiện thiết bị tại cổng: $detectedPort" -ForegroundColor Green
    } elseif ($ports.Count -gt 1) {
        Write-Host "Phát hiện nhiều cổng COM kết nối: $($ports -join ', ')" -ForegroundColor Yellow
        $detectedPort = Read-Host "Nhập cổng COM muốn sử dụng (ví dụ: $($ports[0]))"
        if (-not $detectedPort) { $detectedPort = $ports[0] }
    } else {
        Write-Host "[!] Chưa phát hiện cổng COM nào." -ForegroundColor Yellow
        Write-Host "    -> VUI LÒNG CẮM CÁP USB KẾT NỐI ESP32 VÀO MÁY TÍNH..." -ForegroundColor White -BackgroundColor DarkRed
        Write-Host "    (Tự động quét lại sau 3 giây hoặc nhấn Ctrl+C để hủy)" -ForegroundColor Gray
        Start-Sleep -Seconds 3
    }
}

# Nhận diện dòng chip ESP32
$chipModel = "cyd_wroom32_4mb"
$esptoolChip = "esp32"

if ($pythonCmd) {
    Write-Host "Đang nhận diện dòng chip ESP32 qua $detectedPort..." -ForegroundColor Gray
    $chipInfo = & $pythonCmd -m esptool --port $detectedPort chip_id 2>&1 | Out-String
    
    if ($chipInfo -match "ESP32-S3") {
        $chipModel = "esp32_s3_4mb"
        $esptoolChip = "esp32s3"
    } elseif ($chipInfo -match "ESP32-S2") {
        $chipModel = "esp32_s2_4mb"
        $esptoolChip = "esp32s2"
    } elseif ($chipInfo -match "ESP32-C3") {
        $chipModel = "esp32_c3_4mb"
        $esptoolChip = "esp32c3"
    } elseif ($chipInfo -match "ESP32-C5") {
        $chipModel = "esp32_c5_4mb"
        $esptoolChip = "esp32c5"
    } elseif ($chipInfo -match "ESP32-C6") {
        $chipModel = "esp32_c6_4mb"
        $esptoolChip = "esp32c6"
    } else {
        $chipModel = "cyd_wroom32_4mb"
        $esptoolChip = "esp32"
    }
    Write-Host "[OK] Đã nhận diện phần cứng: $esptoolChip ($chipModel)" -ForegroundColor Green
}

# ------------------------------------------------------------------------------
# 3. NẠP FIRMWARE VÀO ESP32
# ------------------------------------------------------------------------------
Write-Host ""
Write-Host "[BƯỚC 3/4] Nạp firmware vào ESP32 ($detectedPort)..." -ForegroundColor Cyan

$fileMap = @{
    "esp32"    = "ESP32-WROOM-32-firmware.bin"
    "esp32s2"  = "ESP32-S2-firmware.bin"
    "esp32s3"  = "ESP32-S3-firmware.bin"
    "esp32c3"  = "ESP32-C3-firmware.bin"
    "esp32c5"  = "ESP32-C5-firmware.bin"
    "esp32c6"  = "ESP32-C6-firmware.bin"
}
$targetFileName = $fileMap[$esptoolChip]
$firmwareFile = Join-Path $releasesDir $targetFileName
if (-not (Test-Path $firmwareFile)) {
    $firmwareFile = Join-Path $rootDir "firmware-cyd\releases\$targetFileName"
}
if (-not (Test-Path $firmwareFile)) {
    $firmwareFile = Join-Path $releasesDir "firmware-$chipModel.bin"
}

if (Test-Path $firmwareFile) {
    Write-Host "Tìm thấy firmware: $(Split-Path $firmwareFile -Leaf)" -ForegroundColor Gray
    $flashConfirm = Read-Host "Bạn có muốn nạp firmware sạch v1.3 vào ESP32 ngay bây giờ? (Y/n)"
    if ($flashConfirm -ne 'n' -and $flashConfirm -ne 'N') {
        Write-Host "Đang nạp firmware (Offset 0x10000)..." -ForegroundColor Yellow
        & $pythonCmd -m esptool --chip $esptoolChip --port $detectedPort write_flash 0x10000 $firmwareFile
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[THÀNH CÔNG] Đã nạp firmware vào ESP32!" -ForegroundColor Green
        } else {
            Write-Host "[CẢNH BÁO] Quá trình nạp firmware gặp lỗi. Có thể nạp lại sau." -ForegroundColor Yellow
        }
    }
} else {
    Write-Host "[!] Không tìm thấy file firmware: $firmwareFile. Bỏ qua bước nạp." -ForegroundColor Yellow
}

# ------------------------------------------------------------------------------
# 4. CÀI ĐẶT RUNTIME TRÊN WINDOWS & MỞ DASHBOARD
# ------------------------------------------------------------------------------
Write-Host ""
Write-Host "[BƯỚC 4/4] Cài đặt & Kích hoạt Local Host trên Windows..." -ForegroundColor Cyan

# Kiểm tra Node.js
if (-not (Get-Command "node" -ErrorAction SilentlyContinue)) {
    Write-Host "Đang cài đặt Node.js qua winget..." -ForegroundColor Yellow
    winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements --silent
}

# Chạy Install-TrayRuntime.ps1
$installTrayScript = Join-Path $localHostDir "Install-TrayRuntime.ps1"
if (Test-Path $installTrayScript) {
    Write-Host "Đang cấu hình Local Host và phát hiện IP LAN..." -ForegroundColor Gray
    & $installTrayScript
} else {
    Write-Host "Khởi động Local Host trực tiếp bằng Node.js..." -ForegroundColor Gray
    Start-Process -FilePath "node" -ArgumentList "server.mjs" -WorkingDirectory $localHostDir -WindowStyle Hidden
    Start-Sleep -Seconds 2
    Start-Process "http://127.0.0.1:8787"
}

Write-Host ""
Write-Host "=================================================================" -ForegroundColor Green
Write-Host "                 HOÀN TẤT CÀI ĐẶT HỆ THỐNG!                      " -ForegroundColor Green
Write-Host "   - Dashboard: http://127.0.0.1:8787                            " -ForegroundColor Cyan
Write-Host "   - Đăng nhập mặc định: admin / admin                           " -ForegroundColor White
Write-Host "=================================================================" -ForegroundColor Green
Write-Host ""
