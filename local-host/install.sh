#!/usr/bin/env bash
# ==============================================================================
# ESP32 Download - Trình cài đặt tự động cho Linux / Ubuntu Server / NAS
# ==============================================================================
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}=================================================================${NC}"
echo -e "${GREEN}       ESP32 CHATBOT AUTO DOWNLOADER - LINUX HOST INSTALLER      ${NC}"
echo -e "${YELLOW}                 Hỗ trợ Ubuntu, Debian, Linux Server & NAS       ${NC}"
echo -e "${CYAN}=================================================================${NC}"
echo ""

ESP_IP="${1:-}"

# 1. Kiểm tra quyền sudo/root
if [ "$EUID" -ne 0 ] && command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
else
    SUDO=""
fi

# 2. Cài đặt các gói phụ trợ
echo -e "${CYAN}[BƯỚC 1/4] Kiểm tra và cài đặt các gói phụ trợ (Node.js, Python, FFmpeg)...${NC}"

if command -v apt-get >/dev/null 2>&1; then
    $SUDO apt-get update -qq
    $SUDO apt-get install -y -qq nodejs npm python3 python3-pip ffmpeg curl git
elif command -v apk >/dev/null 2>&1; then
    apk update
    apk add nodejs npm python3 py3-pip ffmpeg curl git bash
elif command -v yum >/dev/null 2>&1; then
    $SUDO yum install -y nodejs npm python3 python3-pip ffmpeg curl git
fi

# Cài đặt yt-dlp & gallery-dl qua pip
echo -e "${CYAN}[BƯỚC 2/4] Cài đặt / Cập nhật yt-dlp và gallery-dl...${NC}"
python3 -m pip install --upgrade --break-system-packages yt-dlp gallery-dl 2>/dev/null || \
python3 -m pip install --upgrade yt-dlp gallery-dl 2>/dev/null || true

# 3. Cấu hình IP ESP32
echo -e "${CYAN}[BƯỚC 3/4] Cấu hình kết nối ESP32...${NC}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$HERE/data"
mkdir -p "$HERE/downloads"

CONFIG_FILE="$HERE/data/local-config.json"

if [ -z "$ESP_IP" ]; then
    echo -e "${YELLOW}Nhập địa chỉ IP của ESP32 (để trống nếu muốn cấu hình sau qua Web):${NC}"
    read -r -p "ESP32 IP: " USER_ESP_IP
    ESP_IP="$USER_ESP_IP"
fi

if [ -n "$ESP_IP" ]; then
    echo -e "${GREEN}[OK] Gán địa chỉ ESP32: http://$ESP_IP${NC}"
    if [ -f "$CONFIG_FILE" ]; then
        # Cập nhật deviceBaseUrl nếu file config đã tồn tại
        node -e "
            const fs = require('fs');
            const file = '$CONFIG_FILE';
            try {
                const conf = JSON.parse(fs.readFileSync(file, 'utf8'));
                conf.deviceBaseUrl = 'http://$ESP_IP';
                fs.writeFileSync(file, JSON.stringify(conf, null, 2));
            } catch(e) {}
        " 2>/dev/null || true
    fi
    # Gửi đăng ký runtime lên ESP32 nếu ESP32 đang online
    HOST_IP=$(hostname -I | awk '{print $1}')
    if [ -n "$HOST_IP" ]; then
        curl -s -X POST -H "Content-Type: application/json" -d "{\"runtimeBaseUrl\":\"http://$HOST_IP:8787\"}" "http://$ESP_IP/setup/integrations" >/dev/null 2>&1 || true
    fi
fi

# 4. Thiết lập Systemd Service (nếu có systemd)
echo -e "${CYAN}[BƯỚC 4/4] Khởi tạo dịch vụ nền (Service)...${NC}"

if command -v systemctl >/dev/null 2>&1 && [ "$EUID" -eq 0 -o -n "$SUDO" ]; then
    SERVICE_FILE="/etc/systemd/system/esp32-download-host.service"
    $SUDO bash -c "cat <<EOF > $SERVICE_FILE
[Unit]
Description=ESP32 Chatbot Auto Downloader Host Service
After=network.target

[Service]
Type=simple
User=$(whoami)
WorkingDirectory=$HERE
ExecStart=$(which node) $HERE/server.mjs
Restart=on-failure
RestartSec=5
Environment=NODE_ENV=production

[Install]
WantedBy=multi-user.target
EOF"
    $SUDO systemctl daemon-reload
    $SUDO systemctl enable esp32-download-host.service
    $SUDO systemctl restart esp32-download-host.service
    echo -e "${GREEN}[OK] Đã kích hoạt dịch vụ tự khởi động cùng hệ thống (systemd).${NC}"
else
    # Chạy nền với nohup nếu không có systemd
    pkill -f "node.*server.mjs" 2>/dev/null || true
    nohup node "$HERE/server.mjs" > "$HERE/data/host.log" 2>&1 &
    echo -e "${GREEN}[OK] Đã khởi chạy tiến trình server.mjs ngầm (nohup).${NC}"
fi

HOST_LAN_IP=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "127.0.0.1")
echo ""
echo -e "${GREEN}=================================================================${NC}"
echo -e "${GREEN}                HOÀN TẤT CÀI ĐẶT LINUX HOST!                     ${NC}"
echo -e "${CYAN}   - Dashboard máy chủ: http://$HOST_LAN_IP:8787                  ${NC}"
echo -e "${CYAN}   - Dashboard cục bộ : http://127.0.0.1:8787                    ${NC}"
echo -e "${YELLOW}   - Tài khoản mặc định: admin / admin                            ${NC}"
echo -e "${GREEN}=================================================================${NC}"
echo ""
