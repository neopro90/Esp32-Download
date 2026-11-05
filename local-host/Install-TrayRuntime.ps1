param(
    [string]$EspIp,
    [switch]$FirewallOnly,
    [switch]$CleanOnly
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$runtimeRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$port = 8787
$nodePath = (Get-Command node.exe -ErrorAction Stop).Source

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

Write-Host "=== CÀI ĐẶT & CẤU HÌNH ESP DOWNLOAD TRAY RUNTIME ===" -ForegroundColor Cyan

# 1. Hủy Scheduled Task cũ (nếu có) để tránh xung đột phiên SYSTEM vs User Session
if ($isAdmin) {
    $taskName = 'Esp32DownloadRuntime'
    Write-Host "Kiểm tra và dọn dẹp Scheduled Task '$taskName'..." -ForegroundColor Gray
    try {
        Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
        Write-Host "Đã gỡ bỏ Scheduled Task SYSTEM '$taskName' để chạy thống nhất qua Tray runtime." -ForegroundColor Green
    } catch {}

    # Mở tường lửa cho cổng 8787
    $ruleName = 'ESP Download Windows runtime (private LAN)'
    Write-Host "Cấu hình tường lửa Windows cho cổng TCP $port..." -ForegroundColor Gray
    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP `
        -LocalPort $port -Program $nodePath -Profile Private -RemoteAddress LocalSubnet `
        -EdgeTraversalPolicy Block | Out-Null
    Write-Host "Đã cho phép kết nối LAN TCP $port vào Windows runtime (Profile Private)." -ForegroundColor Green

    if ($FirewallOnly) {
        Write-Host "Hoàn tất thiết lập tường lửa." -ForegroundColor Green
        exit 0
    }
} else {
    Write-Host "LƯU Ý: Chạy với quyền Administrator nếu muốn tự động mở tường lửa Windows cho kết nối LAN từ ESP32." -ForegroundColor Yellow
}

# 2. Dừng các tiến trình node đang chiếm cổng 8787 hoặc đang chạy server.mjs
Write-Host "Dọn dẹp các tiến trình cũ chiếm cổng $port..." -ForegroundColor Gray
try {
    $connections = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
    foreach ($conn in $connections) {
        if ($conn.OwningProcess -and $conn.OwningProcess -ne 0) {
            Write-Host "Dừng tiến trình PID $($conn.OwningProcess) đang chiếm cổng $port..." -ForegroundColor Yellow
            taskkill.exe /F /PID $conn.OwningProcess /T 2>$null
        }
    }
} catch {}

# Dừng các tiến trình Run-TrayRuntime cũ nếu có
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -match 'Run-TrayRuntime\.ps1' -and $_.ProcessId -ne $PID } | ForEach-Object {
    try { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } catch {}
}

if ($CleanOnly) {
    Write-Host "Đã dọn dẹp sạch tiến trình." -ForegroundColor Green
    exit 0
}

# 3. Cài đặt Shortcut trong thư mục Startup của người dùng
$startup = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup'
New-Item -ItemType Directory -Path $startup -Force | Out-Null
$shortcutPath = Join-Path $startup 'ESP Download Runtime.lnk'
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = (Get-Command powershell.exe -ErrorAction Stop).Source
$runScriptPath = Join-Path $runtimeRoot 'Run-TrayRuntime.ps1'
$shortcut.Arguments = '-NoLogo -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $runScriptPath + '"'
$shortcut.WorkingDirectory = $runtimeRoot
$shortcut.Description = 'ESP Download background runtime and tray icon'
$shortcut.Save()
Write-Host "Đã cài đặt shortcut khởi động cùng Windows tại thư mục Startup." -ForegroundColor Green

# 4. Khởi chạy Run-TrayRuntime.ps1
Write-Host "Đang khởi động ESP Download Tray Runtime..." -ForegroundColor Cyan
Start-Process -FilePath $shortcut.TargetPath -ArgumentList $shortcut.Arguments `
    -WorkingDirectory $runtimeRoot -WindowStyle Hidden

Start-Sleep -Seconds 2
Write-Host "✅ ESP Download Tray Runtime đã được cài đặt và khởi động thành công!" -ForegroundColor Green
Write-Host "   - Biểu tượng khay hệ thống (System Tray) đang hoạt động." -ForegroundColor White
Write-Host "   - Dashboard có thể truy cập tại: http://127.0.0.1:$port" -ForegroundColor White

# 5. Nếu có tham số EspIp, tự động phát hiện IP LAN của PC và gửi lên ESP32, đồng thời cập nhật local-config.json
if ($EspIp) {
    Write-Host "Đang dò IP LAN của máy tính để kết nối với ESP32 ($EspIp)..." -ForegroundColor Cyan
    $pcLanIp = $null
    try {
        $ipEntries = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object {
            $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.InterfaceAlias -notmatch 'Virtual|Loopback|WSL|Hyper-V|vEthernet'
        }
        if ($ipEntries) {
            $pcLanIp = ($ipEntries | Select-Object -First 1).IPAddress
        }
    } catch {}

    if (-not $pcLanIp) {
        $pcLanIp = (Get-NetIPConfiguration -ErrorAction SilentlyContinue | Where-Object { $_.IPv4DefaultGateway } | Select-Object -ExpandProperty IPv4Address | Select-Object -First 1).IPAddress
    }

    if ($pcLanIp) {
        Write-Host "Phát hiện IP LAN của máy tính: $pcLanIp" -ForegroundColor Green
        $runtimeUrl = "http://${pcLanIp}:${port}"
        Write-Host "Đang đăng ký Windows Runtime URL ($runtimeUrl) lên ESP32 ($EspIp)..." -ForegroundColor Gray
        try {
            $payload = @{
                runtimeBaseUrl = $runtimeUrl
            } | ConvertTo-Json
            $resp = Invoke-RestMethod -Uri "http://${EspIp}/setup/integrations" -Method POST -ContentType "application/json" -Body $payload -TimeoutSec 4
            Write-Host "✅ ESP32 đã lưu Windows Runtime URL: $runtimeUrl" -ForegroundColor Green
        } catch {
            Write-Host "Lưu ý: Không gửi được tới ESP32: $($_.Exception.Message)" -ForegroundColor Yellow
        }

        # Cập nhật deviceBaseUrl trong data/local-config.json
        $cfgDir = Join-Path $runtimeRoot "data"
        $cfgFile = Join-Path $cfgDir "local-config.json"
        if (Test-Path $cfgFile) {
            try {
                $rawCfg = Get-Content $cfgFile -Raw -Encoding UTF8 | ConvertFrom-Json
                $rawCfg.deviceBaseUrl = "http://${EspIp}"
                $rawCfg.runtimeBaseUrl = $runtimeUrl
                $rawCfg | ConvertTo-Json -Depth 10 | Set-Content $cfgFile -Encoding UTF8
                Write-Host "✅ Đã tự động gán ESP32 (http://${EspIp}) vào Local Host config." -ForegroundColor Green
            } catch {}
        }
    }
}

Start-Process "http://127.0.0.1:$port"
Write-Host "==========================================================" -ForegroundColor Cyan
