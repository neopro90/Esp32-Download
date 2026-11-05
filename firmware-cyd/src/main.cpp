#include <Arduino.h>
#include <ArduinoJson.h>
#include <DNSServer.h>
#include <ESPmDNS.h>
#include <LittleFS.h>
#include <Preferences.h>
#include <WebServer.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <esp_timer.h>
#include <esp_system.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>
#include <freertos/task.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <initializer_list>
#include <time.h>
#include <vector>

#include "telegram_root_ca.h"

namespace {

constexpr char kFirmwareVersion[] = "1.3.1.1";
constexpr uint16_t kFirmwareConfigRevision = 1;
constexpr char kHostname[] = "espdownload";
constexpr char kSetupSsid[] = "espsystem";
constexpr char kSetupPassword[] = "espsystem";
constexpr char kDefaultWifiSsid[] = "";
constexpr char kDefaultWifiPassword[] = "";
constexpr char kPreferencesNamespace[] = "espdl";
constexpr char kJobsPath[] = "/jobs.json";
constexpr char kJobsTempPath[] = "/jobs.tmp";
constexpr char kHostSettingsPath[] = "/host-settings.json";
constexpr char kHostSettingsTempPath[] = "/host-settings.tmp";
constexpr size_t kMaxJobs = 24;
constexpr size_t kMaxUrlLength = 1024;
constexpr size_t kMaxTelegramLogs = 64;
constexpr size_t kMaxProcessedTelegramUpdates = 256;
constexpr size_t kMaxSeenTelegramUrls = 512;
constexpr size_t kMaxGpioCommands = 8;
#if defined(ESP_DOWNLOAD_TARGET_C3)
constexpr char kBoardProfile[] = "ESP32-C3-DevKitM-1 4MB";
constexpr uint8_t kAllowedGpioPins[] = {0, 1, 3, 4, 5, 6, 7, 10};
#elif defined(ESP_DOWNLOAD_TARGET_C5)
constexpr char kBoardProfile[] = "ESP32-C5-DevKitC-1 4MB";
constexpr uint8_t kAllowedGpioPins[] = {0, 1, 2, 3, 4, 5, 6, 7, 10, 11, 14, 15, 16, 17, 18, 19, 20, 21};
#elif defined(ESP_DOWNLOAD_TARGET_C6)
constexpr char kBoardProfile[] = "ESP32-C6-DevKitM-1 4MB";
constexpr uint8_t kAllowedGpioPins[] = {0, 1, 2, 3, 4, 5, 6, 7, 10, 11, 14, 15, 16, 17, 18, 19, 20, 21};
#elif defined(ESP_DOWNLOAD_TARGET_S2)
constexpr char kBoardProfile[] = "ESP32-S2-Saola-1 4MB";
constexpr uint8_t kAllowedGpioPins[] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 21};
#elif defined(ESP_DOWNLOAD_TARGET_S3)
constexpr char kBoardProfile[] = "ESP32-S3-DevKitC-1 4MB";
constexpr uint8_t kAllowedGpioPins[] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 21};
#else
constexpr char kBoardProfile[] = "CYD-3248S035R WROOM-32 4MB";
constexpr uint8_t kAllowedGpioPins[] = {2, 4, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33};
#endif
constexpr uint32_t kApRecoveryDelayMs = 30000UL;
constexpr uint32_t kApRecoveryStableMs = 30000UL;

const char kSetupPage[] PROGMEM = R"HTML(<!doctype html>
<html lang="vi" data-theme="dark"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ESP Download · Controller Setup</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#151c18;color:#e7eee9;font:14px system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;line-height:1.5}
.wrap{max-width:680px;margin:0 auto;padding:16px 14px 40px}
.topbar{display:flex;align-items:center;justify-content:space-between;padding:14px 0 18px;border-bottom:1px solid #303c34;margin-bottom:18px}
.topbar-tools{display:flex;align-items:center;gap:8px}
.language-toggle{width:34px;height:34px;border-radius:50%;padding:0;font:700 11px monospace;background:#2b3c31;color:#7ee0a9;border:1px solid #3d5244;display:inline-flex;align-items:center;justify-content:center;cursor:pointer}
.language-toggle:hover{background:#354a3d;color:#a8e6cf}
.brand{display:flex;align-items:center;gap:12px}
.brand-mark{width:36px;height:36px;background:#1c664e;color:#fff;display:grid;place-items:center;border-radius:10px;font:700 13px monospace;letter-spacing:-1px}
.brand strong{display:block;font-size:15px;letter-spacing:-.3px;color:#e7eee9}
.brand small{display:block;margin-top:2px;font:9px monospace;letter-spacing:1px;color:#98a49b}
.pill{display:inline-flex;align-items:center;gap:7px;padding:6px 12px;border:1px solid #35443b;border-radius:99px;font-size:11px;color:#a2afa5;background:#202b25}
.pill i{width:7px;height:7px;border-radius:50%;background:#adb7b0}
.pill.ok i{background:#2aa475;box-shadow:0 0 0 3px #1c4e38}
.pill.bad i{background:#cf654f;box-shadow:0 0 0 3px #4e221c}
.card{background:#202b25;border:1px solid #35443b;border-radius:10px;padding:20px;margin:14px 0;box-shadow:0 8px 24px #0005}
h2{font-size:16px;margin:0 0 12px;color:#b6dfc4;display:flex;align-items:center;gap:8px}
.eyebrow{margin:0 0 6px;font:10px monospace;letter-spacing:1.2px;color:#55ae86;text-transform:uppercase}
.muted{color:#a7b3aa;font-size:12px;line-height:1.6}
label{display:block;font-weight:600;font-size:12px;margin:14px 0 6px;color:#b5d8c2}
input,select{width:100%;height:41px;border:1px solid #3a4b40;border-radius:6px;padding:0 12px;font-size:13px;background:#18221c;color:#e0e9e3;outline:none}
input:focus,select:focus{border-color:#55ae86;box-shadow:0 0 0 3px #1f4233}
.row{display:flex;gap:8px}.row input{min-width:0}
button{border:0;border-radius:6px;background:#246d52;color:#fff;font-weight:600;font-size:12px;height:41px;padding:0 16px;cursor:pointer;transition:background .15s}
button:hover{background:#1a5d44}
button.secondary{background:#2b3c31;color:#b6dec5;border:1px solid #35443b}
button.secondary:hover{background:#354a3d}
button:disabled{opacity:.5;cursor:wait}
small{display:block;color:#8a988f;margin-top:6px;font-size:11px;line-height:1.4}
#feedback,#integration-feedback{min-height:20px;font-size:12px;margin-top:10px}
.ok{color:#55ae86}.bad{color:#e8a89b}
.network-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px;margin-top:10px;max-height:220px;overflow:auto}
@media(max-width:520px){.network-list{grid-template-columns:1fr}}
button.network-option{display:flex;justify-content:space-between;align-items:center;gap:10px;width:100%;min-height:38px;height:auto;padding:8px 11px;border:1px solid #3a4b40;background:#18221c;color:#e0e9e3;text-align:left;border-radius:6px}
button.network-option strong{overflow-wrap:anywhere;font-size:12px}
button.network-option span{flex:none;font-size:11px;color:#8fa195}
button.network-option.selected{border-color:#55ae86;background:#253b2e}
.status-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:14px}
@media(max-width:480px){.status-grid{grid-template-columns:1fr}}
.stat-box{background:#18221c;border:1px solid #303c34;border-radius:6px;padding:10px 12px}
.stat-box small{color:#8fa195;margin:0 0 4px;font:9px monospace;letter-spacing:.8px;text-transform:uppercase}
.stat-box strong{font-size:13px;color:#e0e9e3;display:block}
.dashboard-link{display:flex;align-items:center;justify-content:center;gap:9px;height:44px;background:#1c664e;color:#fff;border-radius:6px;text-decoration:none;font-weight:600;font-size:13px;margin-top:14px;box-shadow:0 4px 14px #1c664e40}
.dashboard-link:hover{background:#247d60}
.queue-filter{display:flex;gap:6px;margin:12px 0}
.queue-filter button{flex:1;height:34px;padding:0 8px;background:#18221c;border:1px solid #35443b;color:#aab7ae}
.queue-filter button.active{background:#246d52;border-color:#55ae86;color:#fff}
.queue-list{display:grid;gap:8px}
.queue-job{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px;align-items:center;background:#18221c;border:1px solid #303c34;border-radius:6px;padding:10px}
.queue-job-url{overflow-wrap:anywhere;font-size:12px;font-weight:600}
.queue-job-meta{grid-column:1;color:#8fa195;font-size:10px;font-family:monospace;margin-top:3px}
.queue-job-status{grid-column:2;grid-row:1;color:#55ae86;font:10px monospace}
.queue-job-actions{grid-column:1/-1;display:flex;justify-content:flex-end;gap:6px}
.queue-job-actions button{height:32px;padding:0 12px}
.queue-delete{background:#713c31!important}
</style></head>
<body>
<main class="wrap">
  <div class="topbar">
    <div class="brand">
      <div class="brand-mark">ESP</div>
      <div><strong>ESP Download</strong><small>HEADLESS WROOM-32 CONTROLLER</small></div>
    </div>
    <div class="topbar-tools">
      <button id="language-toggle" class="language-toggle" type="button" aria-label="Change language">VI</button>
      <div id="topbar-pill" class="pill"><i></i><span id="topbar-text">Đang kiểm tra…</span></div>
    </div>
  </div>

  <section class="card">
    <div class="eyebrow">STAGE 3 · CORE</div>
    <h2>Trạng thái hệ thống ESP32</h2>
    <div class="status-grid">
      <div class="stat-box"><small>WI-FI STATUS</small><strong id="state-wifi">Đang kiểm tra…</strong></div>
      <div class="stat-box"><small>IP LAN</small><strong id="state-ip">—</strong></div>
      <div class="stat-box"><small>TÍN HIỆU RSSI</small><strong id="state-rssi">—</strong></div>
      <div class="stat-box"><small>SETUP AP</small><strong id="state-ap">—</strong></div>
      <div class="stat-box"><small>RUNTIME SERVICE</small><strong id="state-runtime">Chờ kết nối</strong></div>
      <div class="stat-box"><small>TELEGRAM BOT</small><strong id="state-telegram">—</strong></div>
    </div>
    <a id="dashboard-link" class="dashboard-link" href="http://192.168.1.2:8787" target="_blank">🖥 Mở Web Dashboard đầy đủ (:8787)</a>
  </section>

  <section class="card">
    <div class="eyebrow">ESP32 · LOCAL QUEUE</div>
    <h2>Hàng đợi URL</h2>
    <div class="queue-filter">
      <button type="button" class="active" data-queue-filter="queued">Đang chờ</button>
      <button type="button" data-queue-filter="completed">Hoàn tất</button>
      <button type="button" data-queue-filter="failed">Lỗi</button>
    </div>
    <div id="queue-list" class="queue-list"></div>
  </section>

  <section class="card">
    <div class="eyebrow">STAGE 0 · BACKUP & RESTORE</div>
    <h2>Sao lưu & Khôi phục cấu hình</h2>
    <small class="muted" style="display:block;margin-bottom:12px;">Xuất file cấu hình để lưu trữ hoặc nạp nhanh vào ESP32 mới mà không cần cài đặt lại thủ công.</small>
    <div class="row">
      <button id="btn-backup" type="button" class="secondary" style="flex:1;">⬇️ Sao lưu cấu hình</button>
      <button id="btn-restore-trigger" type="button" style="flex:1;">⬆️ Khôi phục cấu hình</button>
    </div>
    <input id="restore-file" type="file" accept=".espbackup,.json,application/json" style="display:none;">
    <div id="backup-feedback" role="status"></div>
  </section>

  <section class="card">
    <div class="eyebrow">STAGE 2 · NETWORK</div>
    <h2>Cài đặt kết nối Wi‑Fi</h2>
    <label for="ssid">Wi‑Fi SSID</label>
    <div class="row">
      <input id="ssid" autocomplete="off" placeholder="Chọn SSID bên dưới hoặc nhập thủ công">
      <button id="scan" class="secondary" type="button" style="white-space:nowrap;">Quét mạng</button>
    </div>
    <small id="scan-detail">Quét các mạng 2.4GHz quanh ESP32, sau đó nhấp chọn SSID hoặc nhập trực tiếp.</small>
    <div id="networks" class="network-list" aria-live="polite"></div>
    <label for="password">Mật khẩu Wi‑Fi</label>
    <input id="password" type="password" autocomplete="new-password" placeholder="Để trống nếu mạng mở không mật khẩu">
    <button id="save" type="button" style="width:100%;margin-top:16px;">Lưu & Kết nối Wi‑Fi</button>
    <div id="feedback" role="status"></div>
    <hr style="border:0;border-top:1px solid #303c34;margin:22px 0 16px;">
    <h3 style="font-size:14px;margin:0 0 6px;color:#b6dfc4;">Truy cập từ xa (DuckDNS / Dynamic DNS)</h3>
    <small class="muted" style="display:block;margin-bottom:8px;">Tự động cập nhật IP WAN mạng nhà lên DuckDNS để vào từ mạng ngoài khi đã NAT port.</small>
    <div style="background:#151d18;border:1px solid #2b3930;border-radius:4px;padding:8px 10px;font-size:10px;color:#a0ada3;line-height:1.5;margin-bottom:10px;">
      <strong style="color:#92d4ae;display:block;margin-bottom:2px;">HƯỚNG DẪN NAT PORT CHO ESP32:</strong>
      • Cấu hình trên modem: Mở Port ngoài <strong>80</strong> ➜ IP ESP32 <strong>192.168.1.5:80</strong> (TCP hoặc ALL)<br>
      • Truy cập trực tiếp từ xa: <em>http://&lt;domain&gt;.duckdns.org</em> (không cần khai báo port)
    </div>
    <label style="display:flex;align-items:center;gap:9px;cursor:pointer;margin-top:12px;"><input id="ddns-enabled" type="checkbox" style="width:18px;height:18px;"> Bật tự động cập nhật DuckDNS</label>
    <label for="ddns-domain">Tên miền DuckDNS</label>
    <input id="ddns-domain" placeholder="ví dụ: esp32 hoặc esp32.duckdns.org">
    <label for="ddns-token">DuckDNS Token</label>
    <input id="ddns-token" type="password" autocomplete="off" placeholder="Để trống nếu giữ token đã lưu">
    <button id="save-ddns" class="secondary" type="button" style="width:100%;margin-top:14px;">Lưu cấu hình DuckDNS</button>
    <div id="ddns-feedback" role="status"></div>
  </section>

  <section class="card">
    <div class="eyebrow">STAGE 1 & 4 · INTEGRATIONS</div>
    <h2>Telegram & Runtime Service</h2>
    <label style="display:flex;align-items:center;gap:9px;cursor:pointer;"><input id="tg-enabled" type="checkbox" checked style="width:18px;height:18px;"> Bật nhận link qua Telegram Bot</label>
    <label for="tg-token">Telegram Bot Token</label>
    <input id="tg-token" type="password" autocomplete="off" placeholder="Để trống để giữ token hiện tại">
    <label style="display:flex;align-items:center;gap:9px;cursor:pointer;font-weight:normal;font-size:11px;color:#8fa195;"><input id="tg-clear" type="checkbox" style="width:16px;height:16px;"> Xóa token Telegram đã lưu</label>
    <label for="tg-chats">Allowed Telegram Chat ID</label>
    <input id="tg-chats" inputmode="numeric" placeholder="Một hoặc nhiều ID số, phân cách bằng dấu phẩy">
    <label for="runtime-url">Runtime Service URL (LAN)</label>
    <div class="row">
      <input id="runtime-url" type="url" placeholder="http://192.168.1.25:8787">
      <button id="scan-runtime" class="secondary" type="button" style="white-space:nowrap;">Quét tìm</button>
    </div>
    <small id="runtime-detail">Địa chỉ IP máy tính/server đang chạy Runtime Service (Node.js, Docker, Linux, Windows...).</small>
    <div style="margin-top:8px;padding:9px 12px;background:#1e2e25;border:1px solid #334d3e;border-radius:6px;font-size:11px;">
      <strong style="color:#7ee0a9;">⚡ Lệnh cài đặt & kết nối Runtime Service tự động:</strong>
      <div style="margin:6px 0;background:#15201a;padding:6px 8px;border-radius:4px;font-family:monospace;color:#d5ede0;word-break:break-all;" id="runtime-cmd">irm http://192.168.1.5/install-runtime.ps1 | iex</div>
      <button type="button" class="btn" style="padding:4px 10px;font-size:10px;cursor:pointer;" onclick="navigator.clipboard.writeText($('#runtime-cmd').textContent.trim());alert('Đã sao chép lệnh!')">📋 Sao chép lệnh</button>
    </div>
    <label for="retention">Thời hạn lưu Job & Log URL (ngày)</label>
    <input id="retention" type="number" min="0" max="3650" step="1">
    <div id="gpio-list" class="network-list"></div>
    <button id="add-gpio" class="secondary" type="button" style="margin-top:12px;">＋ Thêm lệnh GPIO</button>
    <button id="save-integrations" type="button" style="width:100%;margin-top:16px;">Lưu cấu hình tích hợp</button>
    <div id="integration-feedback" role="status"></div>
  </section>
</main>

<script>
const $=s=>document.querySelector(s);
const translations={
  "Đang kiểm tra…":["Checking…","正在检查…"],
  "Trạng thái hệ thống ESP32":["ESP32 system status","ESP32 系统状态"],
  "Hàng đợi URL":["URL queue","URL 队列"],
  "Đang chờ":["Queued","等待中"],
  "Hoàn tất":["Completed","已完成"],
  "Lỗi":["Failed","失败"],
  "Cài đặt kết nối Wi‑Fi":["Wi-Fi connection settings","Wi-Fi 连接设置"],
  "Chọn SSID bên dưới hoặc nhập thủ công":["Select an SSID below or enter it manually","选择下方的 SSID 或手动输入"],
  "Quét mạng":["Scan networks","扫描网络"],
  "Quét các mạng 2.4GHz quanh ESP32, sau đó nhấp chọn SSID hoặc nhập trực tiếp.":["Scan nearby 2.4GHz networks, then select an SSID or enter it directly.","扫描 ESP32 附近的 2.4GHz 网络，然后选择 SSID 或直接输入。"],
  "Mật khẩu Wi‑Fi":["Wi-Fi password","Wi-Fi 密码"],
  "Để trống nếu mạng mở không mật khẩu":["Leave blank for an open network without a password","开放网络无需密码时请留空"],
  "Lưu & Kết nối Wi‑Fi":["Save & connect Wi-Fi","保存并连接 Wi-Fi"],
  "Telegram & Runtime Service":["Telegram & Runtime Service","Telegram 与 Runtime Service"],
  "Telegram & Windows Runtime":["Telegram & Runtime Service","Telegram 与 Runtime Service"],
  "Runtime Service URL (LAN)":["Runtime Service URL (LAN)","Runtime Service URL（局域网）"],
  "Quét tìm":["Scan","扫描"],
  "Bật nhận link qua Telegram Bot":["Receive links via Telegram bot","通过 Telegram Bot 接收链接"],
  "Để trống để giữ token hiện tại":["Leave blank to keep the current token","留空以保留当前令牌"],
  "Xóa token Telegram đã lưu":["Clear saved Telegram token","清除已保存的 Telegram 令牌"],
  "Allowed Telegram Chat ID":["Allowed Telegram Chat ID","允许的 Telegram Chat ID"],
  "Một hoặc nhiều ID số, phân cách bằng dấu phẩy":["One or more numeric IDs, separated by commas","一个或多个数字 ID，以逗号分隔"],
  "Windows Runtime URL (LAN)":["Runtime Service URL (LAN)","Runtime Service URL（局域网）"],
  "Địa chỉ IP máy tính Windows đang chạy node server.mjs.":["IP address of the server running Runtime Service (Node.js/Docker/Linux/Windows).","运行 Runtime Service（Node.js/Docker/Linux/Windows）的服务器 IP 地址。"],
  "Thời hạn lưu Job & Log URL (ngày)":["Job & URL log retention (days)","任务和 URL 日志保留时间（天）"],
  "＋ Thêm lệnh GPIO":["＋ Add GPIO command","＋ 添加 GPIO 命令"],
  "Lưu cấu hình tích hợp":["Save integration settings","保存集成设置"],
  "Không có job trong mục này.":["No jobs in this section.","此部分没有任务。"],
  "Xóa":["Delete","删除"],
  "Lỗi đọc trạng thái":["Failed to read status","读取状态失败"],
  "Đã kết nối":["Connected","已连接"],
  "Đang kết nối…":["Connecting…","正在连接…"],
  "Sai mật khẩu":["Incorrect password","密码错误"],
  "Không tìm thấy SSID":["SSID not found","未找到 SSID"],
  "Mất kết nối":["Connection lost","连接已断开"],
  "Chưa kết nối":["Not connected","未连接"],
  "Đã tắt":["Off","已关闭"],
  "Chờ kết nối":["Waiting for connection","等待连接"],
  "Chưa online":["Offline","离线"],
  "Online · Sẵn sàng":["Online · Ready","在线 · 就绪"],
  "Online":["Online","在线"],
  "Đã cấu hình":["Configured","已配置"],
  "Chưa cấu hình":["Not configured","未配置"],
  "Đang quét mạng Wi‑Fi quanh ESP32…":["Scanning nearby Wi-Fi networks…","正在扫描 ESP32 附近的 Wi-Fi 网络…"],
  "Đang quét nền · kết nối ESP32 vẫn giữ…":["Scanning in background · ESP32 connection remains active…","正在后台扫描 · ESP32 连接保持中…"],
  "Không bắt đầu được quét":["Could not start scan","无法开始扫描"],
  "Quét Wi‑Fi thất bại":["Wi-Fi scan failed","Wi-Fi 扫描失败"],
  "Quét quá thời gian; hãy thử lại":["Scan timed out; try again","扫描超时，请重试"],
  "Khóa":["Secured","加密"],
  "Mở":["Open","开放"],
  "Đang dùng":["In use","正在使用"],
  " mạng tìm thấy trong ":[", networks found in "," 个网络，耗时 "],
  " ms.":[" ms."," 毫秒。"],
  "Đang lưu thông tin Wi‑Fi xuống ESP32…":["Saving Wi-Fi settings to ESP32…","正在保存 Wi-Fi 设置到 ESP32…"],
  "Không lưu được":["Could not save","无法保存"],
  "Đã lưu thành công! ESP32 đang kết nối Wi‑Fi mới…":["Saved! ESP32 is connecting to the new Wi-Fi…","已保存！ESP32 正在连接新的 Wi-Fi…"],
  "Tối đa 8 lệnh GPIO.":["Maximum 8 GPIO commands.","最多 8 条 GPIO 命令。"],
  "Đang lưu cài đặt…":["Saving settings…","正在保存设置…"],
  "Đã lưu cài đặt tích hợp trên ESP32.":["Integration settings saved on ESP32.","集成设置已保存到 ESP32。"],
  "Lệnh (vd: relay_on)":["Command (e.g. relay_on)","命令（例如 relay_on）"],
  "Đã lưu token (nhập mới để đổi)":["Token saved (enter a new one to change it)","令牌已保存（输入新令牌以更改）"],
  "Dán token từ BotFather":["Paste token from BotFather","粘贴来自 BotFather 的令牌"],
  "Mở Web Dashboard đầy đủ trên PC (:8787)":["Open the full web dashboard on PC (:8787)","在电脑上打开完整 Web 控制面板（:8787）"],
  "Sao lưu & Khôi phục cấu hình":["Backup & restore config","配置备份与恢复"],
  "⬇️ Sao lưu cấu hình":["⬇️ Backup config","⬇️ 备份配置"],
  "⬆️ Khôi phục cấu hình":["⬆️ Restore config","⬆️ 恢复配置"]
};
const languageCodes=['en','vi','zh'];
const languageLabels=['EN','VI','CN'];
const reverseTranslations=new Map();
for(const [source,values] of Object.entries(translations)){
  reverseTranslations.set(source,source);
  values.forEach(value=>reverseTranslations.set(value,source));
}
let uiLanguage=localStorage.getItem('espdownload-ui-language')||'vi';
if(!languageCodes.includes(uiLanguage))uiLanguage='vi';
const textSources=new WeakMap();
function translateTextNode(node){
  if(!textSources.has(node)){
    const trimmed=node.nodeValue.trim();
    const canonical=reverseTranslations.get(trimmed);
    textSources.set(node,canonical===undefined?node.nodeValue:node.nodeValue.replace(trimmed,canonical));
  }
  const source=textSources.get(node);
  const trimmed=source.trim();
  const values=translations[trimmed];
  const translated=values?(uiLanguage==='en'?values[0]:uiLanguage==='zh'?values[1]:trimmed):source;
  if(node.nodeValue!==translated)node.nodeValue=translated;
}
function translateElement(element){
  const walker=document.createTreeWalker(element,NodeFilter.SHOW_TEXT);
  while(walker.nextNode())translateTextNode(walker.currentNode);
  for(const node of [element,...element.querySelectorAll('*')]){
    for(const attribute of ['placeholder','aria-label','title']){
      const value=node.getAttribute(attribute);
      if(value){
        const canonical=reverseTranslations.get(value)||value;
        const values=translations[canonical];
        const translated=values?(uiLanguage==='en'?values[0]:uiLanguage==='zh'?values[1]:canonical):value;
        if(value!==translated)node.setAttribute(attribute,translated);
      }
    }
  }
}
function applyLanguage(){
  document.documentElement.lang=uiLanguage==='zh'?'zh-CN':uiLanguage;
  const button=$('#language-toggle');
  button.textContent=languageLabels[languageCodes.indexOf(uiLanguage)];
  button.setAttribute('aria-label',uiLanguage==='en'?'Change language':uiLanguage==='zh'?'切换语言':'Đổi ngôn ngữ');
  translateElement(document.body);
}
$('#language-toggle').onclick=()=>{
  uiLanguage=languageCodes[(languageCodes.indexOf(uiLanguage)+1)%languageCodes.length];
  localStorage.setItem('espdownload-ui-language',uiLanguage);
  applyLanguage();
};
new MutationObserver(records=>{
  for(const record of records){
    if(record.type==='characterData')translateTextNode(record.target);
    else for(const node of record.addedNodes)if(node.nodeType===Node.ELEMENT_NODE)translateElement(node);else if(node.nodeType===Node.TEXT_NODE)translateTextNode(node);
  }
}).observe(document.body,{subtree:true,childList:true,characterData:true});
const labels={CONNECTED:"Đã kết nối",CONNECTING:"Đang kết nối…",AUTH_FAILED:"Sai mật khẩu",SSID_NOT_FOUND:"Không tìm thấy SSID",CONNECTION_LOST:"Mất kết nối",DISCONNECTED:"Chưa kết nối"};
async function status(){
  try{
    const r=await fetch('/setup/status',{cache:'no-store'});
    const d=await r.json();
    const pill=$('#topbar-pill');
    pill.className='pill '+(d.wifiConnected?'ok':'bad');
    $('#topbar-text').textContent=d.wifiConnected?`${d.wifiSsid} · ${d.wifiIp}`:`AP: ${d.apSsid}`;
    $('#state-wifi').textContent=labels[d.wifiStatus]||d.wifiStatus;
    $('#state-ip').textContent=d.wifiConnected?d.wifiIp:'—';
    $('#state-rssi').textContent=d.wifiConnected?`${d.rssi} dBm`:'—';
    $('#state-ap').textContent=d.apActive?`${d.apSsid} (bật)`:'Đã tắt';
    $('#state-runtime').textContent=d.runtimeOnline?(d.runtimeReady?'Online · Sẵn sàng':'Online'):'Chưa online';
    $('#state-telegram').textContent=d.telegramConfigured?'Đã cấu hình':'Chưa cấu hình';
    if($('#runtime-cmd')) $('#runtime-cmd').textContent=`irm http://${window.location.host}/install-runtime.ps1 | iex`;
    if($('#dashboard-link')){
      const h=window.location.hostname;
      const isExt=h.includes('.')&&!h.startsWith('192.168.')&&!h.startsWith('10.')&&!h.startsWith('172.')&&h!=='localhost';
      if(isExt){
        $('#dashboard-link').href=`${window.location.protocol}//${h}:8787/`;
        $('#dashboard-link').textContent='🌐 Mở Web Dashboard trên PC (:8787 từ xa)';
      }else if(d.runtimeUrl){
        $('#dashboard-link').href=d.runtimeUrl;
      }
    }
  }catch(e){
    $('#topbar-pill').className='pill bad';
    $('#topbar-text').textContent='Lỗi đọc trạng thái';
  }
}
$('#scan').onclick=async()=>{
  const b=$('#scan');b.disabled=true;$('#scan-detail').textContent='Đang quét mạng Wi‑Fi quanh ESP32…';
  try{
    let r=await fetch('/setup/scan',{cache:'no-store'});
    let d=await r.json();
    if(!r.ok)throw new Error(d.error?.message||'Không bắt đầu được quét');
    const deadline=Date.now()+25000;
    while(d.scanning===true&&Date.now()<deadline){
      $('#scan-detail').textContent='Đang quét nền · kết nối ESP32 vẫn giữ…';
      await new Promise(res=>setTimeout(res,600));
      r=await fetch('/setup/scan',{cache:'no-store'});d=await r.json();
      if(!r.ok)throw new Error(d.error?.message||'Quét Wi‑Fi thất bại');
    }
    if(d.scanning===true)throw new Error('Quét quá thời gian; hãy thử lại');
    const list=$('#networks');list.replaceChildren();
    for(const n of d.networks||[]){
      const o=document.createElement('button');o.type='button';o.className='network-option';
      const name=document.createElement('strong');name.textContent=n.ssid;
      const meta=document.createElement('span');meta.textContent=`${n.rssi} dBm · k.${n.channel} · ${n.secure?'Khóa':'Mở'}${n.connected?' · Đang dùng':''}`;
      o.append(name,meta);
      o.onclick=()=>{$('#ssid').value=n.ssid;list.querySelectorAll('.network-option').forEach(i=>i.classList.remove('selected'));o.classList.add('selected');};
      list.append(o);
    }
    $('#scan-detail').textContent=`${d.count||0} mạng tìm thấy trong ${d.scanDurationMs||0} ms.`;
  }catch(e){$('#scan-detail').textContent='Quét thất bại: '+e.message;}finally{b.disabled=false;}
};
$('#save').onclick=async()=>{
  const b=$('#save');b.disabled=true;$('#feedback').textContent='Đang lưu thông tin Wi‑Fi xuống ESP32…';$('#feedback').className='';
  try{
    const r=await fetch('/setup/network',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ssid:$('#ssid').value.trim(),password:$('#password').value})});
    const d=await r.json();
    if(!r.ok)throw new Error(d.error?.message||'Không lưu được');
    $('#password').value='';
    $('#feedback').textContent='Đã gửi Wi-Fi! Đang chờ ESP32 kết nối Gateway router…';$('#feedback').className='ok';
    let pollCount=0;
    const pollTimer=setInterval(async()=>{
      pollCount++;
      try{
        const st=await (await fetch('/setup/status',{cache:'no-store'})).json();
        if(st.wifiConnected&&st.wifiIp&&st.wifiIp!=='0.0.0.0'){
          clearInterval(pollTimer);
          b.disabled=false;
          $('#feedback').innerHTML='🎉 ESP32 đã kết nối Wi‑Fi thành công!<br>Địa chỉ IP mới do Gateway cấp: <strong>http://'+st.wifiIp+'/</strong><div style="margin-top:10px;"><a href="http://'+st.wifiIp+'/" target="_blank" style="display:inline-block;padding:9px 16px;background:#2aa475;color:#fff;border-radius:6px;font-weight:700;text-decoration:none;">🚀 Mở trang cài đặt ESP32 tại http://'+st.wifiIp+'/</a></div><small class="muted" style="display:block;margin-top:6px;">Chuyển thiết bị về Wi-Fi vừa cài rồi bấm vào link trên để tiếp tục.</small>';
          status();
          return;
        }
      }catch(err){}
      if(pollCount>=25){
        clearInterval(pollTimer);
        b.disabled=false;
        $('#feedback').textContent='Đã gửi cấu hình Wi-Fi. Hãy chuyển sang Wi-Fi đã kết nối và kiểm tra IP router đã cấp cho ESP32.';
      }
    },1500);
  }catch(e){$('#feedback').textContent=e.message;$('#feedback').className='bad';b.disabled=false;}
};
if($('#scan-runtime')) $('#scan-runtime').onclick=async()=>{
  const b=$('#scan-runtime');b.disabled=true;
  const detail=$('#runtime-detail');
  detail.textContent='Đang quét mạng LAN tìm Runtime Service (Port 8787)…';
  try{
    const baseHost=window.location.hostname;
    let candidates=[];
    if(baseHost&&baseHost.includes('.')){
      const parts=baseHost.split('.');
      if(parts.length===4){
        const prefix=parts[0]+'.'+parts[1]+'.'+parts[2]+'.';
        for(let i=1;i<255;i++) candidates.push('http://'+prefix+i+':8787');
      }
    }
    if(!candidates.length){
      for(let i=1;i<255;i++) candidates.push('http://192.168.1.'+i+':8787');
    }
    let found=null;
    const controller=new AbortController();
    const timeout=setTimeout(()=>controller.abort(),4000);
    const probes=candidates.map(url=>fetch(url+'/api/mobile/health',{signal:controller.signal,mode:'no-cors'}).then(()=>{
      if(!found){found=url;controller.abort();}
    }).catch(()=>{}));
    await Promise.allSettled(probes);
    clearTimeout(timeout);
    if(found){
      $('#runtime-url').value=found;
      detail.textContent='✅ Đã tìm thấy Runtime Service: '+found;
    }else{
      detail.textContent='Không quét thấy Runtime Service (:8787). Hãy kiểm tra server đã chạy hoặc nhập tay.';
    }
  }catch(e){
    detail.textContent='Hoàn tất quét LAN.';
  }finally{
    b.disabled=false;
  }
};
let allowedGpioPins=[21,22];
function gpioRows(items=[]){
  const box=$('#gpio-list');box.replaceChildren();
  for(const item of items){
    const row=document.createElement('div');row.className='row';row.style.marginTop='6px';
    const name=document.createElement('input');name.placeholder='Lệnh (vd: relay_on)';name.maxLength=32;name.value=item.command||'';
    const pin=document.createElement('select');for(const n of allowedGpioPins){const o=document.createElement('option');o.value=n;o.textContent='GPIO '+n;pin.append(o);}pin.value=item.pin||allowedGpioPins[0];
    const ms=document.createElement('input');ms.type='number';ms.min=50;ms.max=5000;ms.step=50;ms.value=item.pulseMs||500;
    const level=document.createElement('select');level.innerHTML='<option value="high">Active High</option><option value="low">Active Low</option>';level.value=item.activeHigh===false?'low':'high';
    const del=document.createElement('button');del.type='button';del.className='secondary';del.textContent='✕';del.onclick=()=>row.remove();
    row.append(name,pin,ms,level,del);box.append(row);
  }
}
async function loadSettings(){
  const r=await fetch('/setup/settings',{cache:'no-store'});const d=await r.json();if(!r.ok)return;
  const i=d.integrations||{};
  allowedGpioPins=Array.isArray(i.allowedGpioPins)&&i.allowedGpioPins.length?i.allowedGpioPins:[21,22];
  $('#tg-enabled').checked=i.telegramEnabled!==false;
  $('#tg-token').placeholder=i.telegramTokenConfigured?'Đã lưu token (nhập mới để đổi)':'Dán token từ BotFather';
  $('#tg-chats').value=i.telegramAllowedChatIds||'';
  $('#runtime-url').value=i.runtimeBaseUrl||'';
  if(i.runtimeBaseUrl&&$('#dashboard-link')) $('#dashboard-link').href=i.runtimeBaseUrl;
  $('#retention').value=i.urlRetentionDays??30;
  if(d.network){
    if($('#ddns-enabled')) $('#ddns-enabled').checked=!!d.network.ddnsEnabled;
    if($('#ddns-domain')) $('#ddns-domain').value=d.network.ddnsDomain||'';
    if($('#ddns-token')) $('#ddns-token').placeholder=d.network.ddnsTokenConfigured?'Đã lưu token (nhập mới để đổi)':'Nhập DuckDNS Token';
  }
  gpioRows(i.gpioCommands||[]);
}
if($('#save-ddns')) $('#save-ddns').onclick=async()=>{
  const b=$('#save-ddns');b.disabled=true;$('#ddns-feedback').textContent='Đang lưu DDNS…';$('#ddns-feedback').className='';
  try{
    const r=await fetch('/setup/ddns',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({enabled:$('#ddns-enabled').checked,domain:$('#ddns-domain').value.trim(),token:$('#ddns-token').value.trim()})});
    const d=await r.json();
    if(!r.ok)throw new Error(d.error?.message||'Không lưu được');
    $('#ddns-token').value='';
    $('#ddns-feedback').textContent='Đã lưu thành công cấu hình DuckDNS!';$('#ddns-feedback').className='ok';
  }catch(e){$('#ddns-feedback').textContent=e.message;$('#ddns-feedback').className='bad';}finally{b.disabled=false;}
};
if($('#btn-backup')) $('#btn-backup').onclick=async()=>{
  const b=$('#btn-backup');b.disabled=true;$('#backup-feedback').textContent='Đang tải bản sao lưu từ ESP32…';$('#backup-feedback').className='';
  try{
    const r=await fetch('/setup/backup',{cache:'no-store'});
    if(!r.ok){const err=await r.json().catch(()=>({}));throw new Error(err.error?.message||`HTTP ${r.status}`);}
    const blob=await r.blob();
    const url=URL.createObjectURL(blob);
    const a=document.createElement('a');a.href=url;a.download=`esp32-backup-${Date.now()}.espbackup`;
    document.body.appendChild(a);a.click();a.remove();URL.revokeObjectURL(url);
    $('#backup-feedback').textContent='✅ Đã tải file sao lưu thành công!';$('#backup-feedback').className='ok';
  }catch(e){$('#backup-feedback').textContent='Lỗi tải sao lưu: '+e.message;$('#backup-feedback').className='bad';}finally{b.disabled=false;}
};
if($('#btn-restore-trigger')) $('#btn-restore-trigger').onclick=()=>{$('#restore-file').click();};
if($('#restore-file')) $('#restore-file').onchange=async(e)=>{
  const f=e.target.files?.[0];if(!f)return;
  if(!confirm(`Khôi phục cấu hình từ "${f.name}"? ESP32 sẽ nạp lại cài đặt và tự khởi động lại.`)){$('#restore-file').value='';return;}
  const b=$('#btn-restore-trigger');b.disabled=true;$('#backup-feedback').textContent='Đang nạp file cấu hình lên ESP32…';$('#backup-feedback').className='';
  try{
    const text=await f.text();
    let payload;try{payload=JSON.parse(text);}catch(err){throw new Error('File không hợp lệ: phải là file JSON hoặc .espbackup');}
    const r=await fetch('/setup/restore',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
    const d=await r.json().catch(()=>({}));
    if(!r.ok)throw new Error(d.error?.message||`HTTP ${r.status}`);
    $('#backup-feedback').innerHTML='🎉 Khôi phục cấu hình thành công! ESP32 đang khởi động lại…<br><small class="muted">Vui lòng đợi 5–10 giây rồi tải lại trang.</small>';
    $('#backup-feedback').className='ok';
    setTimeout(()=>{location.reload();},7000);
  }catch(err){$('#backup-feedback').textContent='Khôi phục thất bại: '+err.message;$('#backup-feedback').className='bad';}
  finally{b.disabled=false;$('#restore-file').value='';}
};
let queueJobs=[];
let queueFilter='queued';
const queuedStatuses=['QUEUED','DISPATCHED','DOWNLOADING','VERIFYING','CANCEL_REQUESTED'];
const completedStatuses=['COMPLETED'];
const failedStatuses=['FAILED','ERROR1','ERROR2','CANCELLED','RECOVERY_REQUIRED'];
function renderQueue(){
  const box=$('#queue-list');box.replaceChildren();
  const allowed=queueFilter==='queued'?queuedStatuses:queueFilter==='completed'?completedStatuses:failedStatuses;
  const jobs=queueJobs.filter(j=>allowed.includes(j.status)).slice().reverse();
  if(!jobs.length){const empty=document.createElement('div');empty.className='muted';empty.textContent='Không có job trong mục này.';box.append(empty);return;}
  for(const job of jobs){
    const row=document.createElement('div');row.className='queue-job';
    const url=document.createElement('div');url.className='queue-job-url';url.textContent=job.url||'';
    const meta=document.createElement('div');meta.className='queue-job-meta';meta.textContent=`#${job.id||''} · ${job.source||'esp32'}`;
    const state=document.createElement('div');state.className='queue-job-status';state.textContent=job.status||'';
    const actions=document.createElement('div');actions.className='queue-job-actions';
    const remove=document.createElement('button');remove.type='button';remove.className='queue-delete';remove.textContent='Xóa';
    remove.onclick=async()=>{
      remove.disabled=true;
      try{
        const r=await fetch(`/setup/jobs/${encodeURIComponent(job.id)}/delete`,{method:'DELETE',cache:'no-store'});
        const d=await r.json();if(!r.ok)throw new Error(d.error?.message||d.error||'Không xóa được job');
        if(d.runtimeDeleted===false)throw new Error('Đã xóa trên ESP32, nhưng Windows runtime chưa xác nhận.');
        await loadQueue();
      }catch(e){remove.disabled=false;remove.textContent=e.message;}
    };
    actions.append(remove);row.append(url,meta,state,actions);box.append(row);
  }
}
async function loadQueue(){
  try{
    const r=await fetch('/setup/jobs',{cache:'no-store'});
    const d=await r.json();if(!r.ok)return;
    queueJobs=d.jobs||[];
    renderQueue();
  }catch(e){}
}
document.querySelectorAll('[data-queue-filter]').forEach(button=>button.onclick=()=>{
  queueFilter=button.dataset.queueFilter;
  document.querySelectorAll('[data-queue-filter]').forEach(item=>item.classList.toggle('active',item===button));
  renderQueue();
});
$('#add-gpio').onclick=()=>{
  const rows=[...$('#gpio-list').children];
  if(rows.length>=8){$('#integration-feedback').textContent='Tối đa 8 lệnh GPIO.';return;}
  const current=rows.map(r=>({command:r.children[0].value,pin:Number(r.children[1].value),pulseMs:Number(r.children[2].value),activeHigh:r.children[3].value==='high'}));
  current.push({command:'',pin:allowedGpioPins[0],pulseMs:500,activeHigh:true});
  gpioRows(current);
};
$('#save-integrations').onclick=async()=>{
  const b=$('#save-integrations');b.disabled=true;$('#integration-feedback').textContent='Đang lưu cài đặt…';$('#integration-feedback').className='';
  try{
    const gpioCommands=[...$('#gpio-list').children].map(r=>({command:r.children[0].value.trim(),pin:Number(r.children[1].value),pulseMs:Number(r.children[2].value),activeHigh:r.children[3].value==='high'}));
    const r=await fetch('/setup/integrations',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({telegramEnabled:$('#tg-enabled').checked,telegramBotToken:$('#tg-token').value,clearTelegramToken:$('#tg-clear').checked,telegramAllowedChatIds:$('#tg-chats').value.trim(),runtimeBaseUrl:$('#runtime-url').value.trim(),urlRetentionDays:Number($('#retention').value),gpioCommands})});
    const d=await r.json();
    if(!r.ok)throw new Error(d.error?.message||'Không lưu được');
    $('#tg-token').value='';$('#tg-clear').checked=false;
    $('#tg-token').placeholder=d.telegramTokenConfigured?'Đã lưu token (nhập mới để đổi)':'Dán token từ BotFather';
    $('#integration-feedback').textContent='Đã lưu cài đặt tích hợp trên ESP32.';$('#integration-feedback').className='ok';
    if($('#runtime-url').value.trim()&&$('#dashboard-link')) $('#dashboard-link').href=$('#runtime-url').value.trim();
  }catch(e){$('#integration-feedback').textContent=e.message;$('#integration-feedback').className='bad';}finally{b.disabled=false;}
};
applyLanguage();status();loadSettings();loadQueue();setInterval(status,3000);setInterval(loadQueue,5000);
</script>
</body></html>)HTML";

struct Job {
    String id;
    String url;
    String source;
    String telegramChatId;
    String status;
    String createdAt;
    String workerId;
    String outputPath;
    uint64_t outputBytes = 0;
    String error;
    String speed;
    String eta;
    float progress = 0.0F;
    uint32_t attempts = 0;
    uint32_t terminalAt = 0;
    uint32_t telegramUpdateId = 0;
    String telegramEventKey;
    String urlFingerprint;
    bool force = false;
};

struct TelegramLogEntry {
    uint32_t id = 0;
    String url;
    String chatId;
    String result;
    String detail;
    uint32_t receivedAt = 0;
    uint32_t telegramUpdateId = 0;
    String telegramEventKey;
    String urlFingerprint;
};

struct TelegramUrlState {
    String fingerprint;
    String status;
    String jobId;
    uint32_t telegramUpdateId = 0;
    uint32_t seenAt = 0;
};

struct PendingTelegramUpdate {
    uint32_t updateId = 0;
    char messageJson[6144] = {};
};

struct OutgoingTelegramMessage {
    char chatId[32] = {};
    char text[512] = {};
};

struct GpioCommand {
    String command;
    String trigger;
    uint8_t pin = 22;
    uint16_t pulseMs = 500;
    bool activeHigh = true;
    String replyMessage;
};

Preferences preferences;
WebServer server(80);
DNSServer dnsServer;
std::vector<Job> jobs;
std::vector<TelegramLogEntry> telegramLog;
std::vector<String> processedTelegramUpdates;
std::vector<TelegramUrlState> telegramUrlStates;
std::vector<GpioCommand> gpioCommands;
String apiToken;
String setupSsid;
String setupPassword;
String telegramBotToken;
String telegramAllowedChatIds;
String runtimeBaseUrl;
String telegramLastError;
bool ddnsEnabled = false;
String ddnsDomain;
String ddnsToken;
uint32_t lastDdnsUpdateMs = 0;
String ddnsLastStatus = "Chưa cập nhật";
bool telegramEnabled = true;
bool apEnabled = true;
bool setupApStarted = false;
bool apRecoveryMode = false;
bool apTogglePending = false;
uint32_t wifiDisconnectedSinceMs = 0;
uint32_t wifiConnectedSinceMs = 0;
uint32_t wifiNextReconnectAtMs = 0;
uint16_t urlRetentionDays = 30;
uint32_t telegramUpdateOffset = 0;
uint32_t lastRuntimeHeartbeatMs = 0;
uint32_t lastRuntimeDeliveryMs = 0;
uint32_t lastRetentionCleanupMs = 0;
uint32_t gpioPulseUntilMs = 0;
uint8_t activeGpioPulsePin = 0;
bool gpioPulseActive = false;
bool activeGpioPulseInactiveHigh = false;
bool runtimeOnline = false;
bool runtimeReady = false;
uint32_t runtimeOnlineSinceMs = 0;
bool clockSyncRequested = false;
bool storageReady = false;
bool mdnsStarted = false;
String lastStorageError;
JsonDocument sharedHostSettings;
uint32_t sharedHostSettingsRevision = 0;
bool sharedHostSettingsInitialized = false;
bool cpuLoadReady = false;
uint8_t cpuBusyPercent = 0;
uint8_t cpuCore0BusyPercent = 0;
uint8_t cpuCore1BusyPercent = 0;
uint64_t previousCpuSampleUs = 0;
uint32_t previousIdleCore0Us = 0;
uint32_t previousIdleCore1Us = 0;
QueueHandle_t pendingTelegramUpdates = nullptr;
QueueHandle_t outgoingTelegramMessages = nullptr;
SemaphoreHandle_t telegramStateMutex = nullptr;
TaskHandle_t telegramTaskHandle = nullptr;
uint32_t telegramConfigGeneration = 0;
uint32_t wifiScanStartedAt = 0;
bool apToggleTarget = false;
std::atomic<uint32_t> wifiDisconnectCount{0};
std::atomic<uint32_t> wifiGotIpCount{0};
std::atomic<uint32_t> wifiLastDisconnectAtMs{0};
std::atomic<uint32_t> wifiLastGotIpAtMs{0};
std::atomic<uint8_t> wifiLastDisconnectReason{0};

String telegramLastErrorSnapshot() {
    if (!telegramStateMutex) return telegramLastError;
    xSemaphoreTake(telegramStateMutex, portMAX_DELAY);
    const String value = telegramLastError;
    xSemaphoreGive(telegramStateMutex);
    return value;
}

void setTelegramLastError(const String &value) {
    if (!telegramStateMutex) {
        telegramLastError = value;
        return;
    }
    xSemaphoreTake(telegramStateMutex, portMAX_DELAY);
    telegramLastError = value;
    xSemaphoreGive(telegramStateMutex);
}

bool isSetupApClient();
void handleSetupStatus();
bool isPrivateRuntimeUrl(const String &url);
void loadHostSettings();
String telegramUrlFingerprint(const String &url);
bool hasSeenTelegramUrl(const String &url);
void rememberTelegramUrlState(const String &url, uint32_t updateId, const String &status, const String &jobId, uint32_t seenAt);

void updateCpuLoad() {
#if CONFIG_FREERTOS_GENERATE_RUN_TIME_STATS && !CONFIG_FREERTOS_SMP
    const uint64_t nowUs = static_cast<uint64_t>(esp_timer_get_time());
    const uint32_t idleCore0Us = static_cast<uint32_t>(ulTaskGetIdleRunTimeCounterForCore(0));
    const uint32_t idleCore1Us = static_cast<uint32_t>(ulTaskGetIdleRunTimeCounterForCore(1));
    if (previousCpuSampleUs != 0 && nowUs > previousCpuSampleUs) {
        const uint64_t elapsedUs = nowUs - previousCpuSampleUs;
        const uint32_t idleDelta0 = idleCore0Us - previousIdleCore0Us;
        const uint32_t idleDelta1 = idleCore1Us - previousIdleCore1Us;
        const uint8_t idlePercent0 = static_cast<uint8_t>(std::min<uint64_t>(100, static_cast<uint64_t>(idleDelta0) * 100 / elapsedUs));
        const uint8_t idlePercent1 = static_cast<uint8_t>(std::min<uint64_t>(100, static_cast<uint64_t>(idleDelta1) * 100 / elapsedUs));
        cpuCore0BusyPercent = 100 - idlePercent0;
        cpuCore1BusyPercent = 100 - idlePercent1;
        cpuBusyPercent = static_cast<uint8_t>((cpuCore0BusyPercent + cpuCore1BusyPercent) / 2);
        cpuLoadReady = true;
    }
    previousCpuSampleUs = nowUs;
    previousIdleCore0Us = idleCore0Us;
    previousIdleCore1Us = idleCore1Us;
#endif
}

const char *wifiStatusName() {
    switch (WiFi.status()) {
        case WL_CONNECTED: return "CONNECTED";
        case WL_NO_SSID_AVAIL: return "SSID_NOT_FOUND";
        case WL_CONNECT_FAILED: return "AUTH_FAILED";
        case WL_CONNECTION_LOST: return "CONNECTION_LOST";
        case WL_DISCONNECTED: return "DISCONNECTED";
        case WL_IDLE_STATUS: return "CONNECTING";
        default: return "CONNECTING";
    }
}

void registerWifiEvents() {
    WiFi.onEvent([](arduino_event_id_t event, arduino_event_info_t info) {
        if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
            wifiLastDisconnectReason.store(info.wifi_sta_disconnected.reason, std::memory_order_relaxed);
            wifiLastDisconnectAtMs.store(millis(), std::memory_order_relaxed);
            const String savedSsid = preferences.getString("wifiSsid", "");
            if (!savedSsid.isEmpty()) {
                wifiNextReconnectAtMs = millis() + 4000UL;
            } else {
                wifiNextReconnectAtMs = 0;
            }
            const uint32_t count = wifiDisconnectCount.fetch_add(1, std::memory_order_relaxed) + 1;
            Serial.printf("Wi-Fi event: station disconnected, reason=%u, count=%lu\n",
                          static_cast<unsigned>(wifiLastDisconnectReason.load(std::memory_order_relaxed)), static_cast<unsigned long>(count));
        } else if (event == ARDUINO_EVENT_WIFI_STA_CONNECTED) {
            WiFi.setSleep(false);
            Serial.println("Wi-Fi event: station associated");
        } else if (event == ARDUINO_EVENT_WIFI_STA_GOT_IP) {
            WiFi.setSleep(false);
            wifiNextReconnectAtMs = 0;
            wifiLastGotIpAtMs.store(millis(), std::memory_order_relaxed);
            const uint32_t count = wifiGotIpCount.fetch_add(1, std::memory_order_relaxed) + 1;
            Serial.printf("Wi-Fi event: station got IP, count=%lu\n", static_cast<unsigned long>(count));
        } else if (event == ARDUINO_EVENT_WIFI_AP_STACONNECTED) {
            Serial.printf("[AP EVENT] Phone/Client CONNECTED! MAC: %02x:%02x:%02x:%02x:%02x:%02x\n",
                          info.wifi_ap_staconnected.mac[0], info.wifi_ap_staconnected.mac[1],
                          info.wifi_ap_staconnected.mac[2], info.wifi_ap_staconnected.mac[3],
                          info.wifi_ap_staconnected.mac[4], info.wifi_ap_staconnected.mac[5]);
        } else if (event == ARDUINO_EVENT_WIFI_AP_STAIPASSIGNED) {
            Serial.printf("[AP EVENT] Client DHCP assigned IP: %s\n", IPAddress(info.wifi_ap_staipassigned.ip.addr).toString().c_str());
        } else if (event == ARDUINO_EVENT_WIFI_AP_STADISCONNECTED) {
            Serial.println("[AP EVENT] Phone/Client DISCONNECTED from AP.");
        }
    });
}

String randomHex(uint8_t byteCount) {
    static constexpr char hex[] = "0123456789abcdef";
    String result;
    result.reserve(byteCount * 2);
    for (uint8_t i = 0; i < byteCount; ++i) {
        const uint8_t value = static_cast<uint8_t>(esp_random());
        result += hex[value >> 4];
        result += hex[value & 0x0F];
    }
    return result;
}

String jobId() {
    return randomHex(6);
}

void sendJson(int status, JsonDocument &document) {
    String body;
    serializeJson(document, body);
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.sendHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
    server.sendHeader("Access-Control-Allow-Headers", "Content-Type, X-Device-Token, Authorization");
    server.sendHeader("Cache-Control", "no-store");
    server.send(status, "application/json; charset=utf-8", body);
}

void sendError(int status, const char *code, const char *message) {
    JsonDocument document;
    document["error"]["code"] = code;
    document["error"]["message"] = message;
    sendJson(status, document);
}

bool authorized() {
    if (server.header("X-Device-Token") == apiToken) return true;
    if (server.header("X-Local-Session") == apiToken) return true;
    if (server.header("Authorization") == "Bearer " + apiToken) return true;
    return false;
}

bool readRequestJson(JsonDocument &document) {
    const String body = server.arg("plain");
    if (body.isEmpty() || body.length() > 8192) {
        sendError(400, "INVALID_BODY", "A JSON body of at most 8192 bytes is required.");
        return false;
    }
    const DeserializationError error = deserializeJson(document, body);
    if (error) {
        sendError(400, "INVALID_JSON", "Request body is not valid JSON.");
        return false;
    }
    return true;
}

void serializeJob(JsonObject destination, const Job &job) {
    destination["id"] = job.id;
    destination["url"] = job.url;
    destination["source"] = job.source;
    destination["telegramChatId"] = job.telegramChatId;
    destination["status"] = job.status;
    destination["createdAt"] = job.createdAt;
    destination["workerId"] = job.workerId;
    destination["outputPath"] = job.outputPath;
    destination["outputBytes"] = job.outputBytes;
    destination["error"] = job.error;
    destination["speed"] = job.speed;
    destination["eta"] = job.eta;
    destination["progress"] = job.progress;
    destination["attempts"] = job.attempts;
    destination["terminalAt"] = job.terminalAt;
    destination["telegramUpdateId"] = job.telegramUpdateId;
    destination["telegramEventKey"] = job.telegramEventKey;
    destination["urlFingerprint"] = job.urlFingerprint;
    destination["force"] = job.force;
}

String telegramUrlStatus(const String &fingerprint) {
    for (auto it = telegramUrlStates.rbegin(); it != telegramUrlStates.rend(); ++it) {
        if (it->fingerprint == fingerprint) return it->status;
    }
    return "";
}

String telegramUrlFingerprint(const String &url) {
    String canonical = url;
    canonical.trim();
    const int fragment = canonical.indexOf('#');
    if (fragment >= 0) canonical.remove(fragment);

    const int schemeEnd = canonical.indexOf("://");
    if (schemeEnd >= 0) {
        const int hostStart = schemeEnd + 3;
        int hostEnd = canonical.indexOf('/', hostStart);
        const int queryAtHost = canonical.indexOf('?', hostStart);
        if (hostEnd < 0 || (queryAtHost >= 0 && queryAtHost < hostEnd)) hostEnd = queryAtHost;
        if (hostEnd < 0) hostEnd = canonical.length();
        String host = canonical.substring(hostStart, hostEnd);
        host.toLowerCase();

        const bool isTikTok = host == "tiktok.com" || host.endsWith(".tiktok.com");
        const int query = canonical.indexOf('?', hostEnd);
        if (query >= 0 && isTikTok) {
            canonical.remove(query);
        } else if (query >= 0) {
            const String base = canonical.substring(0, query);
            const String queryText = canonical.substring(query + 1);
            String kept;
            int start = 0;
            while (start <= static_cast<int>(queryText.length())) {
                int end = queryText.indexOf('&', start);
                if (end < 0) end = queryText.length();
                const String part = queryText.substring(start, end);
                const int equals = part.indexOf('=');
                String key = equals >= 0 ? part.substring(0, equals) : part;
                key.toLowerCase();
                const bool tracking = key.startsWith("utm_") || key == "si" || key == "feature" ||
                    key == "ref" || key == "ref_src" || key == "is_from_webapp" ||
                    key == "sender_device" || key == "sender_web_id" || key == "share_app_id" ||
                    key == "share_link_id";
                if (!tracking && !part.isEmpty()) {
                    if (!kept.isEmpty()) kept += '&';
                    kept += part;
                }
                if (end >= static_cast<int>(queryText.length())) break;
                start = end + 1;
            }
            canonical = base;
            if (!kept.isEmpty()) canonical += String("?") + kept;
        }
    }

    uint64_t hash = 14695981039346656037ULL;
    for (size_t i = 0; i < canonical.length(); ++i) {
        hash ^= static_cast<uint8_t>(canonical[i]);
        hash *= 1099511628211ULL;
    }
    static const char hex[] = "0123456789abcdef";
    char output[17];
    for (int i = 15; i >= 0; --i) {
        output[i] = hex[hash & 0x0fU];
        hash >>= 4;
    }
    output[16] = '\0';
    return String(output);
}

bool hasSeenTelegramUrl(const String &url) {
    const String fingerprint = telegramUrlFingerprint(url);
    return std::any_of(telegramUrlStates.begin(), telegramUrlStates.end(), [&fingerprint](const TelegramUrlState &state) {
        return state.fingerprint == fingerprint;
    });
}

void rememberTelegramUrlFingerprint(const String &fingerprint, uint32_t updateId, const String &status, const String &jobId, uint32_t seenAt) {
    if (fingerprint.isEmpty()) return;
    auto found = std::find_if(telegramUrlStates.begin(), telegramUrlStates.end(), [&fingerprint](const TelegramUrlState &state) {
        return state.fingerprint == fingerprint;
    });
    if (found != telegramUrlStates.end()) {
        if (!status.isEmpty()) found->status = status;
        if (!jobId.isEmpty()) found->jobId = jobId;
        if (updateId > 0) found->telegramUpdateId = updateId;
        if (seenAt > 0) found->seenAt = seenAt;
        return;
    }
    if (telegramUrlStates.size() >= kMaxSeenTelegramUrls) telegramUrlStates.erase(telegramUrlStates.begin());
    TelegramUrlState state;
    state.fingerprint = fingerprint;
    state.status = status;
    state.jobId = jobId;
    state.telegramUpdateId = updateId;
    state.seenAt = seenAt;
    telegramUrlStates.push_back(state);
}

void rememberTelegramUrlState(const String &url, uint32_t updateId, const String &status, const String &jobId, uint32_t seenAt) {
    rememberTelegramUrlFingerprint(telegramUrlFingerprint(url), updateId, status, jobId, seenAt);
}

bool validHostSettingText(JsonVariantConst value, size_t maximumLength, bool required = false) {
    if (!value.is<JsonString>()) return false;
    const JsonString text = value.as<JsonString>();
    if (!text.c_str()) return false;
    return text.size() <= maximumLength && (!required || text.size() > 0);
}

bool allowedValue(const String &value, std::initializer_list<const char *> options) {
    for (const char *option : options) if (value == option) return true;
    return false;
}

bool validateHostSettings(JsonObjectConst settings, String &error) {
    if (settings.isNull()) { error = "Settings object is required."; return false; }
    const String workerName = settings["workerName"] | "";
    if (workerName.isEmpty() || workerName.length() > 48) { error = "Worker name must contain 1-48 characters."; return false; }
    for (size_t i = 0; i < workerName.length(); ++i) {
        const char c = workerName[i];
        if (!(isAlphaNumeric(c) || c == ' ' || c == '.' || c == '_' || c == '-')) { error = "Worker name contains an unsupported character."; return false; }
    }
    if (!validHostSettingText(settings["ytDlpPath"], 512, true)) { error = "ytDlpPath must be a non-empty string no longer than 512 characters."; return false; }
    if (!validHostSettingText(settings["ffmpegPath"], 512)) { error = "ffmpegPath must be a string no longer than 512 characters."; return false; }
    if (!validHostSettingText(settings["denoPath"], 512)) { error = "denoPath must be a string no longer than 512 characters."; return false; }
    if (!validHostSettingText(settings["outputDirectory"], 512, true)) { error = "outputDirectory must be a non-empty string no longer than 512 characters."; return false; }
    const int retention = settings["urlRetentionDays"] | -1;
    if (retention < 0 || retention > 3650) { error = "URL retention must be between 0 and 3650 days."; return false; }
    JsonObjectConst security = settings["securitySettings"].as<JsonObjectConst>();
    const int sessionTimeout = security["sessionTimeoutMinutes"] | 0;
    const int maxLoginAttempts = security["maxLoginAttempts"] | 0;
    const int loginWindow = security["loginWindowMinutes"] | 0;
    if (security.isNull() || sessionTimeout < 15 || sessionTimeout > 1440 || maxLoginAttempts < 3 || maxLoginAttempts > 20 || loginWindow < 1 || loginWindow > 60) {
        error = "A shared login security setting is outside its supported range."; return false;
    }
    JsonObjectConst d = settings["downloadSettings"].as<JsonObjectConst>();
    if (d.isNull()) { error = "Download settings are required."; return false; }
    const String quality = d["videoQuality"] | "";
    const String thumbnail = d["thumbnailFormat"] | "";
    const String structure = d["outputStructure"] | "";
    const String filename = d["filenameTemplate"] | "";
    const String mergeFormat = d["mergeOutputFormat"] | "";
    const String duplicate = d["duplicateUrlPolicy"] | "";
    const String existing = d["existingFilePolicy"] | "";
    if (!allowedValue(quality, {"best", "1080p", "720p", "audio"}) ||
        !allowedValue(thumbnail, {"off", "original", "jpg", "png", "webp"}) ||
        !allowedValue(structure, {"flat", "date", "extractor", "extractor_date"}) ||
        !allowedValue(mergeFormat, {"best", "mp4", "flv", "avi", "mkv", "ts", "webm"}) ||
        !allowedValue(duplicate, {"skip_pending", "allow"}) ||
        !allowedValue(existing, {"skip", "overwrite", "rename"})) {
        error = "A download setting has an unsupported option."; return false;
    }
    if (filename.isEmpty() || filename.length() > 180 || !filename.endsWith("%(ext)s") || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0 || filename.indexOf("..") >= 0) {
        error = "Filename template must be a file name, at most 180 characters, and include %(ext)s."; return false;
    }
    const int maxJobs = d["maxConcurrentJobs"] | 0;
    const int fragments = d["fragmentConcurrency"] | 0;
    const int retries = d["retryLimit"] | -1;
    const int backoff = d["retryBackoffSeconds"] | 0;
    const int socketTimeout = d["socketTimeoutSeconds"] | 0;
    const int jobTimeout = d["jobTimeoutMinutes"] | -1;
    const int history = d["runtimeHistoryLimit"] | 0;
    const double maxOutputGb = d["maxOutputDirectoryGb"] | -1.0;
    const double minFreeGb = d["minimumFreeDiskGb"] | -1.0;
    if (maxJobs < 1 || maxJobs > 4 || fragments < 1 || fragments > 16 || retries < 0 || retries > 20 ||
        backoff < 1 || backoff > 60 || socketTimeout < 5 || socketTimeout > 600 || jobTimeout < 0 || jobTimeout > 1440 ||
        history < 20 || history > 1000 || !std::isfinite(maxOutputGb) || maxOutputGb < 0 || maxOutputGb > 10000 ||
        !std::isfinite(minFreeGb) || minFreeGb < 0 || minFreeGb > 10000) {
        error = "A download numeric setting is outside its supported range."; return false;
    }
    if (!validHostSettingText(d["bandwidthLimit"], 16) || !d["mergeAudioVideo"].is<bool>() || !d["allowImageDownloads"].is<bool>() || !d["verifyOutput"].is<bool>() || !d["adaptiveMode"].is<bool>()) {
        error = "A download option has an invalid type."; return false;
    }
    return true;
}

bool writeHostSettings(JsonDocument &document) {
    if (!storageReady) { lastStorageError = "LittleFS is not mounted"; return false; }
    File output = LittleFS.open(kHostSettingsTempPath, "w");
    if (!output) { lastStorageError = "Cannot open temporary settings file"; return false; }
    const size_t written = serializeJson(document, output);
    output.flush(); output.close();
    if (written == 0) { LittleFS.remove(kHostSettingsTempPath); lastStorageError = "Settings serialization failed"; return false; }
    const char *backupPath = "/host-settings.bak";
    LittleFS.remove(backupPath);
    if (LittleFS.exists(kHostSettingsPath) && !LittleFS.rename(kHostSettingsPath, backupPath)) {
        LittleFS.remove(kHostSettingsTempPath); lastStorageError = "Cannot rotate settings backup"; return false;
    }
    if (!LittleFS.rename(kHostSettingsTempPath, kHostSettingsPath)) {
        if (LittleFS.exists(backupPath)) LittleFS.rename(backupPath, kHostSettingsPath);
        lastStorageError = "Cannot commit settings database"; return false;
    }
    LittleFS.remove(backupPath); lastStorageError = ""; return true;
}

void loadHostSettings() {
    sharedHostSettings.clear(); sharedHostSettingsRevision = 0; sharedHostSettingsInitialized = false;
    if (!storageReady || !LittleFS.exists(kHostSettingsPath)) return;
    File input = LittleFS.open(kHostSettingsPath, "r"); if (!input) return;
    JsonDocument stored; const DeserializationError error = deserializeJson(stored, input); input.close();
    if (error || (stored["schemaVersion"] | 0) != 1 || !stored["settings"].is<JsonObjectConst>()) return;
    sharedHostSettings.set(stored["settings"].as<JsonObjectConst>());
    sharedHostSettingsRevision = stored["revision"] | 1U;
    sharedHostSettingsInitialized = true;
}

void handleSharedSettings() {
    if (server.method() == HTTP_GET) {
        JsonDocument response; response["schemaVersion"] = 1; response["initialized"] = sharedHostSettingsInitialized;
        response["revision"] = sharedHostSettingsRevision;
        if (sharedHostSettingsInitialized) response["settings"].set(sharedHostSettings.as<JsonVariantConst>());
        sendJson(200, response); return;
    }
    JsonDocument request;
    if (!readRequestJson(request)) return;
    JsonObjectConst settings = request["settings"].as<JsonObjectConst>(); String error;
    if (!validateHostSettings(settings, error)) { sendError(400, "INVALID_SHARED_SETTINGS", error.c_str()); return; }
    JsonDocument next; next["schemaVersion"] = 1; next["revision"] = sharedHostSettingsRevision + 1;
    next["settings"].set(settings);
    if (!writeHostSettings(next)) { sendError(507, "SETTINGS_STORAGE_FAILED", lastStorageError.c_str()); return; }
    sharedHostSettings.set(next["settings"].as<JsonVariantConst>());
    sharedHostSettingsRevision = next["revision"].as<uint32_t>(); sharedHostSettingsInitialized = true;
    JsonDocument response; response["saved"] = true; response["schemaVersion"] = 1; response["revision"] = sharedHostSettingsRevision;
    sendJson(200, response);
}

bool saveJobs() {
    if (!storageReady) {
        lastStorageError = "LittleFS is not mounted";
        return false;
    }

    JsonDocument document;
    JsonArray array = document["jobs"].to<JsonArray>();
    for (const Job &job : jobs) {
        JsonObject item = array.add<JsonObject>();
        serializeJob(item, job);
    }

    File output = LittleFS.open(kJobsPath, "w");
    if (!output) {
        lastStorageError = "Cannot open queue file for writing";
        return false;
    }
    const size_t written = serializeJson(document, output);
    output.flush();
    output.close();
    if (written == 0) {
        lastStorageError = "Queue serialization wrote 0 bytes";
        return false;
    }
    lastStorageError = "";
    return true;
}

bool saveTelegramLog() {
    if (!storageReady) return false;
    JsonDocument document;
    JsonArray processed = document["processedUpdates"].to<JsonArray>();
    for (const String &eventKey : processedTelegramUpdates) processed.add(eventKey);
    JsonArray urlStates = document["urlStates"].to<JsonArray>();
    for (const TelegramUrlState &state : telegramUrlStates) {
        JsonObject item = urlStates.add<JsonObject>();
        item["fingerprint"] = state.fingerprint;
        item["status"] = state.status;
        item["jobId"] = state.jobId;
        item["telegramUpdateId"] = state.telegramUpdateId;
        item["seenAt"] = state.seenAt;
    }
    JsonArray array = document["entries"].to<JsonArray>();
    for (const TelegramLogEntry &entry : telegramLog) {
        JsonObject item = array.add<JsonObject>();
        item["id"] = entry.id;
        item["url"] = entry.url;
        item["chatId"] = entry.chatId;
        item["result"] = entry.result;
        item["detail"] = entry.detail;
        item["receivedAt"] = entry.receivedAt;
        item["telegramUpdateId"] = entry.telegramUpdateId;
        item["urlStatus"] = telegramUrlStatus(entry.urlFingerprint);
        item["telegramEventKey"] = entry.telegramEventKey;
        item["urlFingerprint"] = entry.urlFingerprint;
    }
    File output = LittleFS.open("/telegram-log.json", "w");
    if (!output) {
        lastStorageError = "Cannot open telegram log for writing";
        return false;
    }
    const size_t written = serializeJson(document, output);
    output.flush();
    output.close();
    if (written == 0) {
        lastStorageError = "Zero bytes written to telegram log";
        return false;
    }
    lastStorageError = "";
    return true;
}

void loadTelegramLog() {
    if (!LittleFS.exists("/telegram-log.json") && LittleFS.exists("/telegram-log.bak")) {
        LittleFS.rename("/telegram-log.bak", "/telegram-log.json");
    }
    if (!storageReady || !LittleFS.exists("/telegram-log.json")) return;
    File input = LittleFS.open("/telegram-log.json", "r");
    if (!input) return;
    JsonDocument document;
    const DeserializationError error = deserializeJson(document, input);
    input.close();
    if (error) return;
    JsonArrayConst processed = document["processedUpdates"].as<JsonArrayConst>();
    for (JsonVariantConst value : processed) {
        const String eventKey = value.as<String>();
        if (eventKey.isEmpty() || std::find(processedTelegramUpdates.begin(), processedTelegramUpdates.end(), eventKey) != processedTelegramUpdates.end()) continue;
        if (processedTelegramUpdates.size() >= kMaxProcessedTelegramUpdates) processedTelegramUpdates.erase(processedTelegramUpdates.begin());
        processedTelegramUpdates.push_back(eventKey);
    }
    JsonArrayConst urlStates = document["urlStates"].as<JsonArrayConst>();
    for (JsonObjectConst item : urlStates) {
        const String fingerprint = item["fingerprint"] | "";
        if (fingerprint.isEmpty()) continue;
        rememberTelegramUrlFingerprint(
            fingerprint,
            item["telegramUpdateId"] | 0U,
            item["status"] | "SEEN",
            item["jobId"] | "",
            item["seenAt"] | 0U);
    }
    JsonArrayConst entries = document["entries"].as<JsonArrayConst>();
    for (JsonObjectConst item : entries) {
        if (telegramLog.size() >= kMaxTelegramLogs) break;
        TelegramLogEntry entry;
        entry.id = item["id"] | 0U;
        if (entry.id == 0) { entry.id = esp_random(); if (entry.id == 0) entry.id = 1; }
        entry.url = item["url"] | "";
        entry.chatId = item["chatId"] | "";
        entry.result = item["result"] | "";
        entry.detail = item["detail"] | "";
        entry.receivedAt = item["receivedAt"] | 0U;
        entry.telegramUpdateId = item["telegramUpdateId"] | 0U;
        entry.telegramEventKey = item["telegramEventKey"] | "";
        entry.urlFingerprint = item["urlFingerprint"] | "";
        if (entry.urlFingerprint.isEmpty()) entry.urlFingerprint = telegramUrlFingerprint(entry.url);
        if (!entry.telegramEventKey.isEmpty() && std::find(processedTelegramUpdates.begin(), processedTelegramUpdates.end(), entry.telegramEventKey) == processedTelegramUpdates.end()) {
            if (processedTelegramUpdates.size() >= kMaxProcessedTelegramUpdates) processedTelegramUpdates.erase(processedTelegramUpdates.begin());
            processedTelegramUpdates.push_back(entry.telegramEventKey);
        }
        // Older logs only stored the initial queued state. Preserve a newer
        // completion/failure marker already present in urlStates after jobs
        // or visible log rows have been deleted.
        if (!entry.url.isEmpty() && entry.result == "QUEUED" && telegramUrlStatus(entry.urlFingerprint).isEmpty()) {
            rememberTelegramUrlFingerprint(entry.urlFingerprint, entry.telegramUpdateId, "LEGACY_SEEN", "", entry.receivedAt);
        }
        if (!entry.url.isEmpty()) telegramLog.push_back(entry);
    }
    // Queue records are authoritative for the latest per-URL state. This
    // reconciles older log snapshots that only knew a URL was initially queued.
    for (const Job &job : jobs) {
        if (job.source == "telegram" && !job.urlFingerprint.isEmpty()) {
            rememberTelegramUrlFingerprint(job.urlFingerprint, job.telegramUpdateId, job.status, job.id,
                job.terminalAt > 0 ? job.terminalAt : static_cast<uint32_t>(time(nullptr)));
        }
    }
    saveTelegramLog();
}

bool isTerminalJobStatus(const String &status) {
    return status == "COMPLETED" || status == "FAILED" || status == "CANCELLED" || status == "RECOVERY_REQUIRED";
}

void cleanupExpiredRecords() {
    if (urlRetentionDays == 0) return;
    const time_t nowValue = time(nullptr);
    if (nowValue < 1760000000) return;
    const uint32_t nowEpoch = static_cast<uint32_t>(nowValue);
    const uint32_t cutoff = nowEpoch - static_cast<uint32_t>(urlRetentionDays) * 86400UL;

    const size_t oldJobCount = jobs.size();
    jobs.erase(std::remove_if(jobs.begin(), jobs.end(), [cutoff](const Job &job) {
        return isTerminalJobStatus(job.status) && job.terminalAt > 0 && job.terminalAt < cutoff;
    }), jobs.end());
    if (jobs.size() != oldJobCount) saveJobs();

    const size_t oldLogCount = telegramLog.size();
    telegramLog.erase(std::remove_if(telegramLog.begin(), telegramLog.end(), [cutoff](const TelegramLogEntry &entry) {
        return entry.receivedAt > 0 && entry.receivedAt < cutoff;
    }), telegramLog.end());
    if (telegramLog.size() != oldLogCount) saveTelegramLog();
}

void retentionCleanupTick() {
    if (lastRetentionCleanupMs != 0 && millis() - lastRetentionCleanupMs < 3600000UL) return;
    lastRetentionCleanupMs = millis();
    const size_t before = jobs.size();
    jobs.erase(std::remove_if(jobs.begin(), jobs.end(), [](const Job &job) {
        return isTerminalJobStatus(job.status);
    }), jobs.end());
    if (jobs.size() != before) saveJobs();
    cleanupExpiredRecords();
}

void gpioPulseTick() {
    if (!gpioPulseActive || static_cast<int32_t>(millis() - gpioPulseUntilMs) < 0) return;
    digitalWrite(activeGpioPulsePin, activeGpioPulseInactiveHigh ? HIGH : LOW);
    gpioPulseActive = false;
}

bool triggerGpioCommand(const GpioCommand &command) {
    if (command.pulseMs == 0) {
        const int current = digitalRead(command.pin);
        digitalWrite(command.pin, current == HIGH ? LOW : HIGH);
        return true;
    }
    if (gpioPulseActive && activeGpioPulsePin != command.pin) return false;
    const bool inactiveHigh = !command.activeHigh;
    digitalWrite(command.pin, command.activeHigh ? HIGH : LOW);
    activeGpioPulsePin = command.pin;
    activeGpioPulseInactiveHigh = inactiveHigh;
    gpioPulseUntilMs = millis() + command.pulseMs;
    gpioPulseActive = true;
    return true;
}

void loadJobs() {
    if (!storageReady || !LittleFS.exists(kJobsPath)) {
        return;
    }

    File input = LittleFS.open(kJobsPath, "r");
    if (!input) {
        lastStorageError = "Cannot open queue file";
        return;
    }
    JsonDocument document;
    const DeserializationError error = deserializeJson(document, input);
    input.close();
    if (error) {
        lastStorageError = "Queue file contains invalid JSON";
        return;
    }

    JsonArrayConst array = document["jobs"].as<JsonArrayConst>();
    for (JsonObjectConst item : array) {
        if (jobs.size() >= kMaxJobs) {
            break;
        }
        Job job;
        job.id = item["id"] | "";
        job.url = item["url"] | "";
        job.source = item["source"] | "local-host";
        job.telegramChatId = item["telegramChatId"] | "";
        job.status = item["status"] | "FAILED";
        job.createdAt = item["createdAt"] | "";
        job.workerId = item["workerId"] | "";
        job.outputPath = item["outputPath"] | "";
        job.outputBytes = item["outputBytes"] | 0ULL;
        job.error = item["error"] | "";
        job.speed = item["speed"] | "";
        job.eta = item["eta"] | "";
        job.progress = item["progress"] | 0.0F;
        job.attempts = item["attempts"] | 0U;
        job.terminalAt = item["terminalAt"] | 0U;
        job.telegramUpdateId = item["telegramUpdateId"] | 0U;
        job.telegramEventKey = item["telegramEventKey"] | "";
        job.urlFingerprint = item["urlFingerprint"] | "";
        if (job.urlFingerprint.isEmpty() && job.source == "telegram") job.urlFingerprint = telegramUrlFingerprint(job.url);
        if (!job.telegramEventKey.isEmpty() && std::find(processedTelegramUpdates.begin(), processedTelegramUpdates.end(), job.telegramEventKey) == processedTelegramUpdates.end()) {
            if (processedTelegramUpdates.size() >= kMaxProcessedTelegramUpdates) processedTelegramUpdates.erase(processedTelegramUpdates.begin());
            processedTelegramUpdates.push_back(job.telegramEventKey);
        }
        job.force = item["force"] | false;
        if (job.id.isEmpty() || job.url.isEmpty()) {
            continue;
        }
        if (job.status == "DISPATCHED" || job.status == "DOWNLOADING" || job.status == "CANCEL_REQUESTED") {
            job.status = job.status == "CANCEL_REQUESTED" ? "CANCELLED" : "RECOVERY_REQUIRED";
            job.error = "Device restarted before the worker reported a final result.";
            job.terminalAt = static_cast<uint32_t>(time(nullptr));
        }
        if ((job.status == "COMPLETED" || job.status == "FAILED" || job.status == "CANCELLED" || job.status == "RECOVERY_REQUIRED") && job.terminalAt == 0) {
            job.terminalAt = static_cast<uint32_t>(time(nullptr));
        }
        if (isTerminalJobStatus(job.status)) {
            continue;
        }
        if (job.source == "telegram" && !job.urlFingerprint.isEmpty()) {
            rememberTelegramUrlFingerprint(job.urlFingerprint, job.telegramUpdateId, job.status, job.id, job.terminalAt > 0 ? job.terminalAt : static_cast<uint32_t>(time(nullptr)));
        }
        jobs.push_back(job);
    }
    saveJobs();
}

bool isSetupApActive() {
    const wifi_mode_t mode = WiFi.getMode();
    if (mode != WIFI_AP && mode != WIFI_AP_STA) return false;
    return setupApStarted;
}

void startSetupAccessPoint() {
    if (!apEnabled && !apRecoveryMode) {
        setupApStarted = false;
        dnsServer.stop();
        return;
    }
    setupSsid = kSetupSsid;
    setupPassword = kSetupPassword;

    const String savedSsid = preferences.getString("wifiSsid", "");
    if (savedSsid.isEmpty()) {
        WiFi.mode(WIFI_AP);
    } else {
        WiFi.mode(WIFI_AP_STA);
    }
    WiFi.setSleep(false);

    WiFi.softAPConfig(IPAddress(192, 168, 4, 1), IPAddress(192, 168, 4, 1), IPAddress(255, 255, 255, 0));
    const uint8_t channel = (WiFi.status() == WL_CONNECTED && !savedSsid.isEmpty()) ? static_cast<uint8_t>(WiFi.channel()) : 1;
    bool apStarted = WiFi.softAP(setupSsid.c_str(), setupPassword.c_str(), channel, false, 4);
    if (!apStarted) {
        delay(250);
        const uint8_t retryChannel = (WiFi.status() == WL_CONNECTED && !savedSsid.isEmpty()) ? static_cast<uint8_t>(WiFi.channel()) : 1;
        apStarted = WiFi.softAP(setupSsid.c_str(), setupPassword.c_str(), retryChannel, false, 4);
    }
    setupApStarted = apStarted;
    if (apStarted) {
        dnsServer.setErrorReplyCode(DNSReplyCode::NoError);
        dnsServer.start(53, "*", WiFi.softAPIP());
        Serial.printf("[AP SUCCESS] Provisioning AP active: SSID '%s' (IP: %s)\n", setupSsid.c_str(), WiFi.softAPIP().toString().c_str());
        Serial.printf("[AP SUCCESS] Provisioning AP password: '%s'\n", setupPassword.c_str());
        Serial.println("[AP SUCCESS] Captive Portal DNS running. Connect phone/PC, then open http://192.168.4.1/");
    } else {
        Serial.println("[AP ERROR] Could not start setup AP!");
    }
}

void initializeStorage() {
    const bool hadFilesystem = preferences.getBool("fsReady", false);
    storageReady = LittleFS.begin(false);
    if (!storageReady && !hadFilesystem) {
        Serial.println("LittleFS is new; formatting its dedicated partition once.");
        storageReady = LittleFS.format() && LittleFS.begin(false);
        if (storageReady) {
            preferences.putBool("fsReady", true);
        }
    }
    if (!storageReady) {
        lastStorageError = "LittleFS mount failed; existing data was preserved";
        Serial.println(lastStorageError);
        return;
    }
    preferences.putBool("fsReady", true);
    loadJobs();
    loadTelegramLog();
    loadHostSettings();
}

void beginWifi() {
    const uint16_t storedConfigRevision = preferences.getUShort("fwCfgRev", 0);
    if (storedConfigRevision < kFirmwareConfigRevision) {
        // Apply the first-boot AP default once when upgrading from firmware
        // that stored apEnabled=false but had no configuration revision.
        apEnabled = true;
        preferences.putBool("apEnabled", true);
        preferences.putUShort("fwCfgRev", kFirmwareConfigRevision);
    } else {
        apEnabled = preferences.getBool("apEnabled", true);
        if (!preferences.isKey("apEnabled")) preferences.putBool("apEnabled", true);
    }
    setupSsid = kSetupSsid;
    setupPassword = kSetupPassword;
    WiFi.setSleep(false);
    WiFi.setTxPower(WIFI_POWER_19_5dBm);

    String ssid = preferences.isKey("wifiSsid") ? preferences.getString("wifiSsid") : "";
    String password = preferences.isKey("wifiPass") ? preferences.getString("wifiPass") : "";
    if (ssid.isEmpty() && strlen(kDefaultWifiSsid) > 0) {
        ssid = kDefaultWifiSsid;
        password = kDefaultWifiPassword;
    }

    if (ssid.isEmpty()) {
        // First boot or no Wi-Fi credentials: start SoftAP cleanly in WIFI_AP mode
        apEnabled = true;
        wifiNextReconnectAtMs = 0;
        WiFi.setAutoReconnect(false);
        startSetupAccessPoint();
        Serial.println("=================================================");
        Serial.println("[AP] NO WI-FI CONFIG SAVED - FIRST BOOT PROVISIONING");
        Serial.printf("[AP] SoftAP Started: '%s' | Password: '%s'\n", setupSsid.c_str(), setupPassword.c_str());
        Serial.printf("[AP] Web Interface: http://%s/\n", WiFi.softAPIP().toString().c_str());
        Serial.println("=================================================");
        return;
    }

    WiFi.setAutoReconnect(true);
    if (apEnabled) {
        startSetupAccessPoint();
    } else {
        WiFi.mode(WIFI_STA);
    }
    Serial.printf("Setup AP %s (configuration revision %u).\n", apEnabled ? "enabled" : "disabled", kFirmwareConfigRevision);
    WiFi.setHostname(kHostname);
    WiFi.begin(ssid.c_str(), password.c_str());
    Serial.printf("Connecting to configured Wi-Fi SSID '%s'; setup AP remains available.\n", ssid.c_str());
}

bool ensureMdns() {
    if (mdnsStarted || WiFi.status() != WL_CONNECTED) {
        return mdnsStarted;
    }
    mdnsStarted = MDNS.begin(kHostname);
    if (mdnsStarted) {
        MDNS.addService("http", "tcp", 80);
        MDNS.addService("espdownload", "tcp", 80);
        Serial.printf("mDNS available at http://%s.local/\n", kHostname);
    }
    return mdnsStarted;
}

String localAddress() {
    if (WiFi.status() == WL_CONNECTED) {
        return WiFi.localIP().toString();
    }
    return WiFi.softAPIP().toString();
}

void handleWifiScan() {
    int16_t found = WiFi.scanComplete();
    if (found == WIFI_SCAN_RUNNING) {
        JsonDocument response;
        response["scanning"] = true;
        response["scanDurationMs"] = wifiScanStartedAt == 0 ? 0 : millis() - wifiScanStartedAt;
        response["wifiConnected"] = WiFi.status() == WL_CONNECTED;
        sendJson(202, response);
        return;
    }
    if (found == WIFI_SCAN_FAILED) {
        if (WiFi.status() == WL_IDLE_STATUS) {
            JsonDocument response;
            response["scanning"] = true;
            response["connecting"] = true;
            response["scanDurationMs"] = 0;
            response["wifiConnected"] = false;
            sendJson(202, response);
            return;
        }
        WiFi.scanDelete();
        wifiScanStartedAt = millis();
        const bool apActive = isSetupApActive();
        found = WiFi.scanNetworks(true, true, apActive, apActive ? 60 : 100, 0);
        if (found == WIFI_SCAN_RUNNING) {
            JsonDocument response;
            response["scanning"] = true;
            response["scanDurationMs"] = 0;
            response["wifiConnected"] = WiFi.status() == WL_CONNECTED;
            sendJson(202, response);
            return;
        }
        if (found < 0) {
            wifiScanStartedAt = 0;
            sendError(503, "WIFI_SCAN_FAILED", "Wi-Fi scan could not start. Retry in a moment.");
            return;
        }
    }

    JsonDocument response;
    JsonArray networks = response["networks"].to<JsonArray>();
    const String connectedSsid = WiFi.status() == WL_CONNECTED ? WiFi.SSID() : "";
    for (int16_t i = 0; i < found; ++i) {
        const String ssid = WiFi.SSID(i);
        if (ssid.isEmpty()) {
            continue;
        }
        JsonObject network = networks.add<JsonObject>();
        network["ssid"] = ssid;
        network["rssi"] = WiFi.RSSI(i);
        network["channel"] = WiFi.channel(i);
        network["secure"] = WiFi.encryptionType(i) != WIFI_AUTH_OPEN;
        network["connected"] = ssid == connectedSsid;
    }
    response["count"] = networks.size();
    response["scanning"] = false;
    response["scanDurationMs"] = wifiScanStartedAt == 0 ? 0 : millis() - wifiScanStartedAt;
    response["wifiConnected"] = WiFi.status() == WL_CONNECTED;
    WiFi.scanDelete();
    wifiScanStartedAt = 0;
    sendJson(200, response);
}

void handleSystem() {
    JsonDocument document;
    document["deviceId"] = WiFi.macAddress();
    document["firmwareVersion"] = kFirmwareVersion;
    document["boardProfile"] = kBoardProfile;
    document["chip"] = ESP.getChipModel();
    document["chipRevision"] = ESP.getChipRevision();
    document["flashBytes"] = ESP.getFlashChipSize();
    document["psramPresent"] = psramFound();
    document["freeHeapBytes"] = ESP.getFreeHeap();
    document["wifiStatus"] = wifiStatusName();
    document["wifiConnected"] = WiFi.status() == WL_CONNECTED;
    document["wifiMode"] = static_cast<uint8_t>(WiFi.getMode());
    document["resetReasonCode"] = static_cast<uint8_t>(esp_reset_reason());
    document["wifiSsid"] = WiFi.status() == WL_CONNECTED ? WiFi.SSID() : "";
    document["ipAddress"] = localAddress();
    document["hostname"] = kHostname;
    document["rssi"] = WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0;
    document["provisioningApActive"] = isSetupApActive();
    document["provisioningApEnabled"] = apEnabled;
    document["provisioningApRecoveryMode"] = apRecoveryMode;
    document["ddnsEnabled"] = ddnsEnabled;
    document["ddnsDomain"] = ddnsDomain;
    document["ddnsTokenConfigured"] = !ddnsToken.isEmpty();
    document["ddnsLastStatus"] = ddnsLastStatus;
    document["uptimeMs"] = millis();
    const uint32_t disconnectAtMs = wifiLastDisconnectAtMs.load(std::memory_order_relaxed);
    const uint32_t gotIpAtMs = wifiLastGotIpAtMs.load(std::memory_order_relaxed);
    document["wifiDisconnectCount"] = wifiDisconnectCount.load(std::memory_order_relaxed);
    document["wifiLastDisconnectReason"] = wifiLastDisconnectReason.load(std::memory_order_relaxed);
    document["wifiLastDisconnectMsAgo"] = disconnectAtMs == 0 ? 0 : millis() - disconnectAtMs;
    document["wifiGotIpCount"] = wifiGotIpCount.load(std::memory_order_relaxed);
    document["wifiLastGotIpMsAgo"] = gotIpAtMs == 0 ? 0 : millis() - gotIpAtMs;
    document["provisioningSsid"] = setupSsid;
    document["storageReady"] = storageReady;
    document["storageError"] = lastStorageError;
    document["jobCount"] = jobs.size();
    const bool runtimeFresh = runtimeOnline && runtimeOnlineSinceMs != 0 && millis() - runtimeOnlineSinceMs < 600000UL;
    document["runtimeOnline"] = runtimeFresh;
    document["runtimeReady"] = runtimeFresh && runtimeReady;
    document["runtimeAddressConfigured"] = !runtimeBaseUrl.isEmpty();
    document["lastRuntimeHeartbeatMsAgo"] = runtimeOnlineSinceMs == 0 ? 0 : millis() - runtimeOnlineSinceMs;
    const String telegramError = telegramLastErrorSnapshot();
    document["telegramConfigured"] = !telegramBotToken.isEmpty();
    document["telegramEnabled"] = telegramEnabled;
    document["telegramPollState"] = telegramBotToken.isEmpty() ? "NOT_CONFIGURED" : (telegramError.isEmpty() ? "POLLING" : "ERROR");
    document["telegramLastError"] = telegramError;

    JsonObject api = document["api"].to<JsonObject>();
    api["reachable"] = true;
    api["authenticated"] = true;

    JsonObject cpu = document["cpu"].to<JsonObject>();
    cpu["available"] = cpuLoadReady;
    cpu["busyPercent"] = cpuBusyPercent;
    cpu["core0BusyPercent"] = cpuCore0BusyPercent;
    cpu["core1BusyPercent"] = cpuCore1BusyPercent;
    cpu["frequencyMhz"] = ESP.getCpuFreqMHz();
    cpu["cores"] = 2;

    JsonObject memory = document["memory"].to<JsonObject>();
    memory["heapTotalBytes"] = ESP.getHeapSize();
    memory["heapFreeBytes"] = ESP.getFreeHeap();
    memory["heapMinFreeBytes"] = ESP.getMinFreeHeap();
    memory["heapMaxAllocBytes"] = ESP.getMaxAllocHeap();
    memory["psramTotalBytes"] = ESP.getPsramSize();
    memory["psramFreeBytes"] = ESP.getFreePsram();

    JsonObject storage = document["storage"].to<JsonObject>();
    storage["ready"] = storageReady;
    storage["backend"] = "LittleFS";
    storage["totalBytes"] = storageReady ? LittleFS.totalBytes() : 0;
    storage["usedBytes"] = storageReady ? LittleFS.usedBytes() : 0;
    storage["freeBytes"] = storageReady ? LittleFS.totalBytes() - LittleFS.usedBytes() : 0;
    storage["flashTotalBytes"] = ESP.getFlashChipSize();
    storage["appUsedBytes"] = ESP.getSketchSize();
    storage["appFreeBytes"] = ESP.getFreeSketchSpace();
    sendJson(200, document);
}

void handleSettings() {
    JsonDocument document;
    document["network"]["ssid"] = preferences.isKey("wifiSsid") ? preferences.getString("wifiSsid") : "";
    document["network"]["passwordConfigured"] = preferences.isKey("wifiPass") && !preferences.getString("wifiPass").isEmpty();
    document["network"]["connected"] = WiFi.status() == WL_CONNECTED;
    document["network"]["ipAddress"] = localAddress();
    document["network"]["hostname"] = kHostname;
    document["network"]["apEnabled"] = apEnabled;
    document["network"]["apActive"] = WiFi.getMode() == WIFI_AP || WiFi.getMode() == WIFI_AP_STA;
    document["network"]["apRecoveryMode"] = apRecoveryMode;
    document["network"]["ddnsEnabled"] = ddnsEnabled;
    document["network"]["ddnsDomain"] = ddnsDomain;
    document["network"]["ddnsTokenConfigured"] = !ddnsToken.isEmpty();
    document["network"]["ddnsToken"] = ddnsToken;
    document["network"]["ddnsLastStatus"] = ddnsLastStatus;
    document["security"]["apiTokenConfigured"] = !apiToken.isEmpty();
    document["integrations"]["telegramTokenConfigured"] = !telegramBotToken.isEmpty();
    document["integrations"]["telegramEnabled"] = telegramEnabled;
    document["integrations"]["telegramAllowedChatIds"] = telegramAllowedChatIds;
    document["integrations"]["runtimeBaseUrl"] = runtimeBaseUrl;
    const bool runtimeFresh = runtimeOnline && runtimeOnlineSinceMs != 0 && millis() - runtimeOnlineSinceMs < 600000UL;
    document["integrations"]["runtimeOnline"] = runtimeFresh;
    document["integrations"]["runtimeReady"] = runtimeFresh && runtimeReady;
    document["integrations"]["urlRetentionDays"] = urlRetentionDays;
    JsonArray allowedPins = document["integrations"]["allowedGpioPins"].to<JsonArray>();
    for (uint8_t pin : kAllowedGpioPins) allowedPins.add(pin);
    JsonArray gpio = document["integrations"]["gpioCommands"].to<JsonArray>();
    for (const GpioCommand &command : gpioCommands) {
        JsonObject item = gpio.add<JsonObject>();
        item["command"] = command.command;
        item["pin"] = command.pin;
        item["pulseMs"] = command.pulseMs;
        item["activeHigh"] = command.activeHigh;
    }
    document["storage"]["backend"] = "LittleFS";
    document["storage"]["ready"] = storageReady;
    sendJson(200, document);
}

bool isPrivateRuntimeUrl(const String &value);

void handleConfigBackup() {
    JsonDocument document;
    document["format"] = "esp-download-system-backup";
    document["schemaVersion"] = 1;
    document["chipModel"] = ESP.getChipModel();
    document["deviceId"] = WiFi.macAddress();
    document["ddnsEnabled"] = ddnsEnabled;
    document["ddnsDomain"] = ddnsDomain;
    document["ddnsToken"] = ddnsToken;

    JsonObject settings = document["settings"].to<JsonObject>();
    settings["wifiSsid"] = preferences.getString("wifiSsid", "");
    settings["wifiPass"] = preferences.getString("wifiPass", "");
    settings["apiToken"] = apiToken;
    settings["apEnabled"] = apEnabled;
    settings["telegramEnabled"] = telegramEnabled;
    settings["telegramBotToken"] = telegramBotToken;
    settings["telegramAllowedChatIds"] = telegramAllowedChatIds;
    settings["runtimeBaseUrl"] = runtimeBaseUrl;
    settings["urlRetentionDays"] = urlRetentionDays;
    settings["ddnsEnabled"] = ddnsEnabled;
    settings["ddnsDomain"] = ddnsDomain;
    settings["ddnsToken"] = ddnsToken;
    JsonArray commands = settings["gpioCommands"].to<JsonArray>();
    for (const GpioCommand &command : gpioCommands) {
        JsonObject item = commands.add<JsonObject>();
        item["command"] = command.command;
        item["trigger"] = command.trigger;
        item["replyMessage"] = command.replyMessage;
        item["pin"] = command.pin;
        item["pulseMs"] = command.pulseMs;
        item["activeHigh"] = command.activeHigh;
    }

    if (sharedHostSettingsInitialized) {
        document["sharedSettings"].set(sharedHostSettings.as<JsonVariantConst>());
        document["hostConfig"].set(sharedHostSettings.as<JsonVariantConst>());
    } else {
        document["sharedSettings"].to<JsonObject>();
        document["hostConfig"].to<JsonObject>();
    }

    JsonObject devCfg = document["deviceConfig"].to<JsonObject>();
    devCfg["schemaVersion"] = 1;
    devCfg["chipModel"] = ESP.getChipModel();
    devCfg["deviceId"] = WiFi.macAddress();
    devCfg["settings"] = settings;
    if (sharedHostSettingsInitialized) {
        devCfg["sharedSettings"].set(sharedHostSettings.as<JsonVariantConst>());
    } else {
        devCfg["sharedSettings"].to<JsonObject>();
    }

    sendJson(200, document);
}

void handleConfigRestore() {
    JsonDocument request;
    if (!readRequestJson(request)) return;
    if (!request["schemaVersion"].isNull() && (request["schemaVersion"] | 0) != 1) {
        sendError(400, "UNSUPPORTED_BACKUP_VERSION", "Configuration backup version is not supported.");
        return;
    }

    JsonObjectConst settings = request["settings"].as<JsonObjectConst>();
    JsonObjectConst sharedSettings = request["sharedSettings"].as<JsonObjectConst>();

    // Support full system backup structure: { format, hostConfig, deviceConfig: { settings, sharedSettings } }
    if (!request["deviceConfig"].isNull()) {
        JsonObjectConst devCfg = request["deviceConfig"].as<JsonObjectConst>();
        if (!devCfg.isNull()) {
            if (!devCfg["settings"].isNull()) {
                settings = devCfg["settings"].as<JsonObjectConst>();
            }
            if (!devCfg["sharedSettings"].isNull()) {
                sharedSettings = devCfg["sharedSettings"].as<JsonObjectConst>();
            }
        }
    }

    // Support flat settings directly at root
    if (settings.isNull() && (!request["wifiSsid"].isNull() || !request["apiToken"].isNull() || !request["telegramBotToken"].isNull())) {
        settings = request.as<JsonObjectConst>();
    }

    // Support hostConfig as sharedSettings fallback
    if (sharedSettings.isNull() && !request["hostConfig"].isNull()) {
        JsonObjectConst hostCfg = request["hostConfig"].as<JsonObjectConst>();
        if (!hostCfg.isNull()) {
            sharedSettings = hostCfg;
        }
    }

    if (settings.isNull()) {
        sendError(400, "INVALID_BACKUP", "Device settings object is required.");
        return;
    }

    const String ssid = settings["wifiSsid"] | "";
    const String wifiPassword = settings["wifiPass"] | "";
    const bool clearWifi = settings["clearWifi"] | false;
    String nextApiToken = settings["apiToken"] | "";
    String nextTelegramToken = settings["telegramBotToken"] | "";
    String nextChatIds = settings["telegramAllowedChatIds"] | "";
    String nextRuntimeUrl = settings["runtimeBaseUrl"] | "";
    const bool nextApEnabled = !settings["apEnabled"].isNull() ? (settings["apEnabled"] | true) : apEnabled;
    const bool nextTelegramEnabled = !settings["telegramEnabled"].isNull() ? (settings["telegramEnabled"] | true) : telegramEnabled;
    int requestedRetention = !settings["urlRetentionDays"].isNull() ? (settings["urlRetentionDays"] | 30) : 30;
    if (requestedRetention < 0 || requestedRetention > 3650) requestedRetention = 30;

    if (ssid.length() > 32 || wifiPassword.length() > 64) {
        sendError(400, "INVALID_BACKUP", "Wi-Fi settings are outside supported range.");
        return;
    }

    // Preserve existing API token if backup token is empty
    if (nextApiToken.isEmpty()) {
        nextApiToken = apiToken;
    } else if (nextApiToken.length() < 32 || nextApiToken.length() > 128) {
        sendError(400, "INVALID_BACKUP", "API token length must be between 32 and 128 characters.");
        return;
    } else {
        for (size_t i = 0; i < nextApiToken.length(); ++i) {
            const char c = nextApiToken[i];
            if (!(isAlphaNumeric(c) || c == '-' || c == '_')) {
                sendError(400, "INVALID_BACKUP", "API token contains unsupported characters.");
                return;
            }
        }
    }

    nextTelegramToken.trim();
    nextChatIds.trim();
    nextRuntimeUrl.trim();
    if (nextTelegramToken.length() > 128 || nextChatIds.length() > 256 ||
        nextRuntimeUrl.length() > 128 || (!nextRuntimeUrl.isEmpty() && !isPrivateRuntimeUrl(nextRuntimeUrl))) {
        sendError(400, "INVALID_BACKUP", "Telegram or runtime settings are invalid.");
        return;
    }
    for (size_t i = 0; i < nextChatIds.length(); ++i) {
        const char c = nextChatIds[i];
        if (!(isDigit(c) || c == '-' || c == ',' || c == ';' || c == ' ' || c == '\t')) {
            sendError(400, "INVALID_BACKUP", "Telegram chat IDs must be numeric and separated by commas.");
            return;
        }
    }
    nextChatIds.replace(';', ',');
    nextChatIds.replace(' ', ',');
    nextChatIds.replace('\t', ',');
    while (nextChatIds.indexOf(",,") >= 0) nextChatIds.replace(",,", ",");

    std::vector<GpioCommand> nextCommands;
    if (!settings["gpioCommands"].isNull()) {
        JsonArrayConst configuredCommands = settings["gpioCommands"].as<JsonArrayConst>();
        if (!configuredCommands.isNull() && configuredCommands.size() <= kMaxGpioCommands) {
            for (JsonObjectConst item : configuredCommands) {
                GpioCommand command;
                command.command = item["command"] | "";
                command.trigger = item["trigger"] | "";
                command.replyMessage = item["replyMessage"] | "";
                command.command.trim();
                command.command.toLowerCase();
                command.trigger.trim();
                command.replyMessage.trim();
                const int pin = item["pin"] | -1;
                const int pulseMs = item["pulseMs"] | -1;
                command.activeHigh = item["activeHigh"] | true;
                bool pinAllowed = false;
                for (uint8_t allowed : kAllowedGpioPins) if (pin == allowed) pinAllowed = true;
                if (!pinAllowed || command.command.length() > 32 || command.trigger.length() > 64 ||
                    command.replyMessage.length() > 160 || pulseMs < 0 || pulseMs > 10000) {
                    continue;
                }
                bool duplicate = false;
                for (const GpioCommand &existing : nextCommands) {
                    if (!command.command.isEmpty() && command.command == existing.command) { duplicate = true; break; }
                }
                if (duplicate) continue;
                command.pin = static_cast<uint8_t>(pin);
                command.pulseMs = static_cast<uint16_t>(pulseMs);
                nextCommands.push_back(command);
            }
        }
    } else {
        nextCommands = gpioCommands;
    }

    // Restore sharedHostSettings if provided and valid, without failing device restore if not
    if (!sharedSettings.isNull() && storageReady) {
        String hostErr;
        if (validateHostSettings(sharedSettings, hostErr)) {
            JsonDocument storedShared;
            storedShared["schemaVersion"] = 1;
            storedShared["revision"] = sharedHostSettingsRevision + 1;
            storedShared["settings"].set(sharedSettings);
            if (writeHostSettings(storedShared)) {
                sharedHostSettings.set(storedShared["settings"].as<JsonVariantConst>());
                sharedHostSettingsRevision = storedShared["revision"].as<uint32_t>();
                sharedHostSettingsInitialized = true;
                Serial.println("[RESTORE] Shared host settings saved to LittleFS.");
            }
        } else {
            Serial.printf("[RESTORE] Note: shared host settings skipped (%s)\n", hostErr.c_str());
        }
    }

    // Preserve existing Wi-Fi unless new Wi-Fi is provided or clearWifi is requested
    if (!ssid.isEmpty()) {
        preferences.putString("wifiSsid", ssid);
        if (!wifiPassword.isEmpty() || preferences.getString("wifiSsid", "") != ssid) {
            preferences.putString("wifiPass", wifiPassword);
        }
        Serial.printf("[RESTORE] Restored Wi-Fi SSID '%s'\n", ssid.c_str());
    } else if (clearWifi) {
        preferences.remove("wifiSsid");
        preferences.remove("wifiPass");
        Serial.println("[RESTORE] Wi-Fi credentials cleared as requested.");
    } else {
        Serial.println("[RESTORE] Backup had empty Wi-Fi SSID; preserved current Wi-Fi configuration.");
    }

    preferences.putBool("apEnabled", nextApEnabled);
    preferences.putBool("tgEnabled", nextTelegramEnabled);
    preferences.putString("tgToken", nextTelegramToken);
    preferences.putString("tgChats", nextChatIds);
    preferences.putString("runtimeUrl", nextRuntimeUrl);
    preferences.putUShort("retentionDays", static_cast<uint16_t>(requestedRetention));

    if (!settings["ddnsEnabled"].isNull()) {
        ddnsEnabled = settings["ddnsEnabled"] | false;
        preferences.putBool("ddnsEnabled", ddnsEnabled);
    } else if (!request["ddnsEnabled"].isNull()) {
        ddnsEnabled = request["ddnsEnabled"] | false;
        preferences.putBool("ddnsEnabled", ddnsEnabled);
    }
    if (!settings["ddnsDomain"].isNull()) {
        ddnsDomain = settings["ddnsDomain"] | "";
        preferences.putString("ddnsDomain", ddnsDomain);
    } else if (!request["ddnsDomain"].isNull()) {
        ddnsDomain = request["ddnsDomain"] | "";
        preferences.putString("ddnsDomain", ddnsDomain);
    }
    if (!settings["ddnsToken"].isNull() && !settings["ddnsToken"].as<String>().isEmpty()) {
        ddnsToken = settings["ddnsToken"].as<String>();
        preferences.putString("ddnsToken", ddnsToken);
    } else if (!request["ddnsToken"].isNull() && !request["ddnsToken"].as<String>().isEmpty()) {
        ddnsToken = request["ddnsToken"].as<String>();
        preferences.putString("ddnsToken", ddnsToken);
    }

    JsonDocument gpioDocument;
    JsonArray gpioArray = gpioDocument.to<JsonArray>();
    for (const GpioCommand &command : nextCommands) {
        JsonObject item = gpioArray.add<JsonObject>();
        item["command"] = command.command;
        item["trigger"] = command.trigger;
        item["replyMessage"] = command.replyMessage;
        item["pin"] = command.pin;
        item["pulseMs"] = command.pulseMs;
        item["activeHigh"] = command.activeHigh;
    }
    String gpioJson;
    serializeJson(gpioDocument, gpioJson);
    preferences.putString("gpioCommands", gpioJson);
    preferences.putUInt("tgOffset", 0);

    if (gpioPulseActive) {
        digitalWrite(activeGpioPulsePin, activeGpioPulseInactiveHigh ? HIGH : LOW);
        gpioPulseActive = false;
    }
    for (const GpioCommand &command : gpioCommands) {
        digitalWrite(command.pin, command.activeHigh ? LOW : HIGH);
        pinMode(command.pin, INPUT);
    }
    for (const GpioCommand &command : nextCommands) {
        digitalWrite(command.pin, command.activeHigh ? LOW : HIGH);
        pinMode(command.pin, OUTPUT);
    }
    gpioCommands = nextCommands;
    telegramBotToken = nextTelegramToken;
    telegramEnabled = nextTelegramEnabled;
    telegramAllowedChatIds = nextChatIds;
    runtimeBaseUrl = nextRuntimeUrl;
    urlRetentionDays = static_cast<uint16_t>(requestedRetention);
    apiToken = nextApiToken;
    preferences.putString("apiToken", apiToken);
    lastRuntimeHeartbeatMs = 0;

    JsonDocument response;
    response["restored"] = true;
    response["restarting"] = true;
    sendJson(200, response);
    server.client().flush();
    delay(1000);
    ESP.restart();
}

void handleIntegrations() {
    JsonDocument request;
    if (!readRequestJson(request)) {
        return;
    }
    String nextToken = request["telegramBotToken"] | "";
    const bool nextTelegramEnabled = request["telegramEnabled"].is<bool>() ? request["telegramEnabled"].as<bool>() : telegramEnabled;
    String nextChats = request["telegramAllowedChatIds"] | "";
    String nextRuntimeUrl = request["runtimeBaseUrl"] | "";
    nextToken.trim();
    nextChats.trim();
    nextRuntimeUrl.trim();
    if (request["clearTelegramToken"] | false) {
        nextToken = "";
    } else if (nextToken.isEmpty()) {
        nextToken = telegramBotToken;
    }
    if (nextChats.isEmpty() && request["telegramAllowedChatIds"].isNull()) {
        nextChats = telegramAllowedChatIds;
    }
    if (nextRuntimeUrl.isEmpty() && request["runtimeBaseUrl"].isNull()) {
        nextRuntimeUrl = runtimeBaseUrl;
    }
    if (!nextToken.isEmpty() && nextToken.length() > 128) {
        sendError(400, "INVALID_TELEGRAM_TOKEN", "Telegram bot token is too long.");
        return;
    }
    if (nextChats.length() > 256) {
        sendError(400, "INVALID_TELEGRAM_CHAT_IDS", "Telegram chat ID list is too long.");
        return;
    }
    for (size_t i = 0; i < nextChats.length(); ++i) {
        const char c = nextChats[i];
        if (!(isDigit(c) || c == '-' || c == ',' || c == ';' || c == ' ' || c == '\t')) {
            sendError(400, "INVALID_TELEGRAM_CHAT_IDS", "Telegram chat IDs must be numeric and separated by commas or semicolons.");
            return;
        }
    }
    nextChats.replace(';', ',');
    nextChats.replace(' ', ',');
    nextChats.replace('\t', ',');
    while (nextChats.indexOf(",,") >= 0) nextChats.replace(",,", ",");
    while (nextRuntimeUrl.endsWith("/")) nextRuntimeUrl.remove(nextRuntimeUrl.length() - 1);
    if (nextRuntimeUrl.length() > 128 || (!nextRuntimeUrl.isEmpty() && !isPrivateRuntimeUrl(nextRuntimeUrl))) {
        sendError(400, "INVALID_RUNTIME_URL", "Runtime URL must be http:// with a private IPv4 address and must not contain a path.");
        return;
    }

    uint16_t nextRetentionDays = urlRetentionDays;
    if (!request["urlRetentionDays"].isNull()) {
        const int requestedDays = request["urlRetentionDays"].as<int>();
        if (requestedDays < 0 || requestedDays > 3650) {
            sendError(400, "INVALID_RETENTION", "URL retention must be between 0 and 3650 days; 0 disables automatic deletion.");
            return;
        }
        nextRetentionDays = static_cast<uint16_t>(requestedDays);
    }

    std::vector<GpioCommand> nextGpioCommands = gpioCommands;
    if (!request["gpioCommands"].isNull()) {
        JsonArrayConst configuredCommands = request["gpioCommands"].as<JsonArrayConst>();
        if (configuredCommands.isNull() || configuredCommands.size() > kMaxGpioCommands) {
            sendError(400, "INVALID_GPIO_COMMANDS", "GPIO command map must be an array with no more than 8 entries.");
            return;
        }
        nextGpioCommands.clear();
        for (JsonObjectConst item : configuredCommands) {
            GpioCommand command;
            command.command = item["command"] | "";
            command.command.trim();
            command.command.toLowerCase();
            command.trigger = item["trigger"] | "";
            command.trigger.trim();
            command.replyMessage = item["replyMessage"] | "";
            command.replyMessage.trim();
            const int pin = item["pin"] | -1;
            const int pulseMs = item["pulseMs"] | 0;
            command.activeHigh = item["activeHigh"] | true;
            if (command.command.length() > 32 || pulseMs < 0 || pulseMs > 10000) {
                sendError(400, "INVALID_GPIO_COMMAND", "GPIO pulse duration must be between 0 (toggle) and 10000 ms.");
                return;
            }
            bool allowedPin = false;
            for (uint8_t allowed : kAllowedGpioPins) if (pin == allowed) allowedPin = true;
            if (!allowedPin) {
                sendError(400, "INVALID_GPIO_PIN", "GPIO pin is not in the allowed list.");
                return;
            }
            if (!command.command.isEmpty()) {
                for (const GpioCommand &existing : nextGpioCommands) {
                    if (existing.command == command.command) {
                        sendError(400, "DUPLICATE_GPIO_COMMAND", "Each Telegram command name must be unique.");
                        return;
                    }
                }
            }
            command.pin = static_cast<uint8_t>(pin);
            command.pulseMs = static_cast<uint16_t>(pulseMs);
            nextGpioCommands.push_back(command);
        }
    }

    const auto botIdentity = [](const String &token) {
        const int separator = token.indexOf(':');
        return separator > 0 ? token.substring(0, separator) : token;
    };
    const bool botIdentityChanged = botIdentity(nextToken) != botIdentity(telegramBotToken);
    if (gpioPulseActive) {
        digitalWrite(activeGpioPulsePin, activeGpioPulseInactiveHigh ? HIGH : LOW);
        gpioPulseActive = false;
    }
    for (const GpioCommand &command : gpioCommands) {
        digitalWrite(command.pin, command.activeHigh ? LOW : HIGH);
        pinMode(command.pin, INPUT);
    }
    for (const GpioCommand &command : nextGpioCommands) {
        digitalWrite(command.pin, command.activeHigh ? LOW : HIGH);
        pinMode(command.pin, OUTPUT);
    }
    gpioCommands = nextGpioCommands;
    urlRetentionDays = nextRetentionDays;
    if (telegramStateMutex) xSemaphoreTake(telegramStateMutex, portMAX_DELAY);
    telegramBotToken = nextToken;
    telegramEnabled = nextTelegramEnabled;
    if (botIdentityChanged) telegramUpdateOffset = 0;
    ++telegramConfigGeneration;
    telegramLastError = "";
    if (telegramStateMutex) xSemaphoreGive(telegramStateMutex);
    telegramAllowedChatIds = nextChats;
    runtimeBaseUrl = nextRuntimeUrl;
    preferences.putString("tgToken", telegramBotToken);
    preferences.putBool("tgEnabled", telegramEnabled);
    preferences.putString("tgChats", telegramAllowedChatIds);
    preferences.putString("runtimeUrl", runtimeBaseUrl);
    preferences.putUShort("retentionDays", urlRetentionDays);
    JsonDocument gpioDocument;
    JsonArray gpioArray = gpioDocument.to<JsonArray>();
    for (const GpioCommand &command : gpioCommands) {
        JsonObject item = gpioArray.add<JsonObject>();
        item["command"] = command.command;
        item["trigger"] = command.trigger;
        item["pin"] = command.pin;
        item["pulseMs"] = command.pulseMs;
        item["activeHigh"] = command.activeHigh;
        item["replyMessage"] = command.replyMessage;
    }
    String gpioJson;
    serializeJson(gpioDocument, gpioJson);
    preferences.putString("gpioCommands", gpioJson);
    if (botIdentityChanged) preferences.putUInt("tgOffset", 0);
    lastRuntimeHeartbeatMs = 0;

    JsonDocument response;
    response["saved"] = true;
    response["telegramTokenConfigured"] = !telegramBotToken.isEmpty();
    response["telegramEnabled"] = telegramEnabled;
    response["telegramAllowedChatIds"] = telegramAllowedChatIds;
    response["runtimeBaseUrl"] = runtimeBaseUrl;
    response["urlRetentionDays"] = urlRetentionDays;
    JsonArray savedGpio = response["gpioCommands"].to<JsonArray>();
    for (const GpioCommand &command : gpioCommands) {
        JsonObject item = savedGpio.add<JsonObject>();
        item["command"] = command.command;
        item["trigger"] = command.trigger;
        item["pin"] = command.pin;
        item["pulseMs"] = command.pulseMs;
        item["activeHigh"] = command.activeHigh;
        item["replyMessage"] = command.replyMessage;
    }
    sendJson(200, response);
}

void handleApSettings() {
    JsonDocument request;
    if (!readRequestJson(request)) return;
    if (!request["enabled"].is<bool>()) {
        sendError(400, "INVALID_AP_STATE", "The enabled field must be true or false.");
        return;
    }
    const bool enable = request["enabled"].as<bool>();
    if (!enable && WiFi.status() != WL_CONNECTED) {
        sendError(409, "WIFI_REQUIRED", "Connect the ESP32 to Wi-Fi before turning off its setup AP.");
        return;
    }
    apEnabled = enable;
    preferences.putBool("apEnabled", apEnabled);
    apRecoveryMode = false;
    wifiDisconnectedSinceMs = 0;
    apToggleTarget = enable;
    apTogglePending = true;
    JsonDocument response;
    response["saved"] = true;
    response["provisioningApEnabled"] = apEnabled;
    response["provisioningApActive"] = isSetupApActive();
    response["provisioningApRecoveryMode"] = apRecoveryMode;
    response["applying"] = true;
    sendJson(202, response);
}

void apToggleTick() {
    if (!apTogglePending) return;
    const bool enable = apToggleTarget;
    apTogglePending = false;
    if (enable) {
        startSetupAccessPoint();
        return;
    }
    if (WiFi.status() == WL_CONNECTED) {
        // Switching AP_STA -> STA keeps the associated station interface;
        // softAPdisconnect() clears/restarts AP config before changing mode.
        if (!WiFi.mode(WIFI_STA)) {
            apEnabled = true;
            apRecoveryMode = false;
            preferences.putBool("apEnabled", true);
            startSetupAccessPoint();
            Serial.println("Setup AP shutdown failed; restored AP to protect Wi-Fi access.");
            return;
        }
        Serial.println("Setup AP disabled; station Wi-Fi left connected.");
        return;
    }
    apEnabled = true;
    preferences.putBool("apEnabled", true);
    Serial.println("Wi-Fi disconnected before AP shutdown; keeping the recovery AP enabled.");
    startSetupAccessPoint();
}

void apRecoveryTick() {
    if (WiFi.status() == WL_CONNECTED) {
        wifiDisconnectedSinceMs = 0;
        if (wifiConnectedSinceMs == 0) wifiConnectedSinceMs = millis();
        if (apRecoveryMode && !apEnabled) {
            if (millis() - wifiConnectedSinceMs < kApRecoveryStableMs) return;
            // Keep recovery access available through short reconnect flaps. Only
            // change radio mode after the station has held an IP for 30 seconds.
            if (WiFi.mode(WIFI_STA)) {
                apRecoveryMode = false;
                Serial.println("Wi-Fi stable for 30 seconds; setup AP recovery ended.");
            } else {
                Serial.println("Wi-Fi recovered but setup AP could not be stopped; keeping recovery access.");
            }
        }
        return;
    }
    wifiConnectedSinceMs = 0;
    if (apEnabled) {
        wifiDisconnectedSinceMs = 0;
        return;
    }
    if (wifiDisconnectedSinceMs == 0) {
        wifiDisconnectedSinceMs = millis();
        return;
    }
    if (!apRecoveryMode && millis() - wifiDisconnectedSinceMs >= kApRecoveryDelayMs) {
        apRecoveryMode = true;
        Serial.println("Wi-Fi has been unavailable for 30 seconds; starting recovery AP.");
        startSetupAccessPoint();
    }
}

bool isPrivateRuntimeUrl(const String &url) {
    if (!url.startsWith("http://")) return false;
    String authority = url.substring(7);
    if (authority.isEmpty() || authority.indexOf('/') >= 0 || authority.indexOf('?') >= 0 || authority.indexOf('#') >= 0 || authority.indexOf('@') >= 0) return false;
    String host = authority;
    const int colon = authority.lastIndexOf(':');
    if (colon >= 0) {
        host = authority.substring(0, colon);
        const String port = authority.substring(colon + 1);
        if (port.isEmpty() || port.length() > 5) return false;
        for (size_t i = 0; i < port.length(); ++i) if (!isDigit(port[i])) return false;
        const long portNumber = port.toInt();
        if (portNumber < 1 || portNumber > 65535) return false;
    }
    IPAddress address;
    if (!address.fromString(host)) return false;
    const uint8_t first = address[0];
    const uint8_t second = address[1];
    return first == 10 || (first == 192 && second == 168) || (first == 172 && second >= 16 && second <= 31);
}

void handleSaveNetwork() {
    JsonDocument request;
    if (!readRequestJson(request)) {
        return;
    }
    const String ssid = request["ssid"] | "";
    const String password = request["password"] | "";
    if (ssid.isEmpty() || ssid.length() > 32 || password.length() > 64) {
        sendError(400, "INVALID_NETWORK_SETTINGS", "SSID is required (1-32 characters); password must be at most 64 characters.");
        return;
    }
    const bool alreadyConnectedWithSameCredentials = WiFi.status() == WL_CONNECTED
        && WiFi.SSID() == ssid
        && preferences.getString("wifiSsid", "") == ssid
        && preferences.getString("wifiPass", "") == password;
    preferences.putString("wifiSsid", ssid);
    preferences.putString("wifiPass", password);
    JsonDocument response;
    response["saved"] = true;
    response["connected"] = alreadyConnectedWithSameCredentials;
    response["message"] = alreadyConnectedWithSameCredentials
        ? "Credentials are unchanged; the current Wi-Fi connection was kept."
        : "Credentials saved. The device is attempting to join this Wi-Fi network.";
    if (alreadyConnectedWithSameCredentials) {
        sendJson(200, response);
        return;
    }
    sendJson(202, response);

    if (apEnabled || apRecoveryMode) {
        if (!isSetupApActive()) startSetupAccessPoint();
        else WiFi.mode(WIFI_AP_STA);
    } else {
        WiFi.mode(WIFI_STA);
    }
    WiFi.setAutoReconnect(true);
    WiFi.begin(ssid.c_str(), password.c_str());
}

void handleClearNetwork() {
    preferences.remove("wifiSsid");
    preferences.remove("wifiPass");
    apEnabled = true;
    preferences.putBool("apEnabled", true);
    WiFi.setAutoReconnect(false);
    wifiNextReconnectAtMs = 0;
    WiFi.disconnect(true, true);
    startSetupAccessPoint();
    JsonDocument response;
    response["saved"] = true;
    response["message"] = "Stored Wi-Fi credentials removed; setup AP is active.";
    sendJson(200, response);
}

Job *findJob(const String &id);

void handleGetJobs() {
    JsonDocument document;
    JsonArray array = document["jobs"].to<JsonArray>();
    for (const Job &job : jobs) {
        JsonObject item = array.add<JsonObject>();
        serializeJob(item, job);
    }
    document["count"] = jobs.size();
    sendJson(200, document);
}

void handleSetupJobs() {
    JsonDocument document;
    JsonArray array = document["jobs"].to<JsonArray>();
    for (const Job &job : jobs) {
        JsonObject item = array.add<JsonObject>();
        serializeJob(item, job);
    }
    document["count"] = jobs.size();
    sendJson(200, document);
}

void handleSetupDeleteJob(const String &path) {
    String prefix = "/setup/jobs/";
    String remainder = path.substring(prefix.length());
    remainder.replace("/delete", "");
    Job *job = findJob(remainder);
    if (job == nullptr) {
        sendError(404, "JOB_NOT_FOUND", "No job exists with this ID.");
        return;
    }
    if (job->status == "DOWNLOADING") {
        sendError(409, "JOB_DOWNLOADING", "Wait for the active download to stop before deleting it.");
        return;
    }
    const String jobUrl = job->url;
    const auto position = std::find_if(jobs.begin(), jobs.end(), [&remainder](const Job &candidate) {
        return candidate.id == remainder;
    });
    const Job removed = *position;
    jobs.erase(position);
    if (!saveJobs()) {
        jobs.push_back(removed);
        sendError(503, "QUEUE_PERSIST_FAILED", "Could not persist removal from the local queue.");
        return;
    }
    telegramLog.erase(std::remove_if(telegramLog.begin(), telegramLog.end(), [&](const TelegramLogEntry &entry) {
        return !jobUrl.isEmpty() && entry.url == jobUrl;
    }), telegramLog.end());
    saveTelegramLog();
    JsonDocument response;
    response["deleted"] = true;
    response["id"] = remainder;
    response["runtimeDeleted"] = false;
    if (!runtimeBaseUrl.isEmpty()) {
        String runtimeEndpoint = runtimeBaseUrl;
        if (!runtimeEndpoint.endsWith("/")) runtimeEndpoint += "/";
        runtimeEndpoint += "api/runtime/jobs/" + remainder;
        HTTPClient runtimeHttp;
        runtimeHttp.setTimeout(3000);
        if (runtimeHttp.begin(runtimeEndpoint)) {
            runtimeHttp.addHeader("X-Device-Token", apiToken);
            const int runtimeStatus = runtimeHttp.sendRequest("DELETE");
            response["runtimeDeleted"] = runtimeStatus >= 200 && runtimeStatus < 300;
            runtimeHttp.end();
        }
    }
    sendJson(200, response);
}

void handleTelegramLog() {
    JsonDocument document;
    JsonArray array = document["entries"].to<JsonArray>();
    for (const TelegramLogEntry &entry : telegramLog) {
        JsonObject item = array.add<JsonObject>();
        item["id"] = String(entry.id);
        item["url"] = entry.url;
        item["chatId"] = entry.chatId;
        item["result"] = entry.result;
        item["detail"] = entry.detail;
        item["receivedAt"] = entry.receivedAt;
        item["telegramUpdateId"] = entry.telegramUpdateId;
        item["urlStatus"] = telegramUrlStatus(entry.urlFingerprint);
    }
    document["count"] = telegramLog.size();
    sendJson(200, document);
}

void handleDeleteTelegramLog(const String &path) {
    const String prefix = "/api/v1/telegram/log";
    String rawId = path.substring(prefix.length());
    if (rawId.startsWith("/")) rawId = rawId.substring(1);
    const int slash = rawId.indexOf('/');
    if (slash >= 0) rawId = rawId.substring(0, slash);

    if (rawId.isEmpty()) {
        telegramLog.clear();
        saveTelegramLog();
        // Also clear all non-downloading jobs from LittleFS
        const size_t beforeJobs = jobs.size();
        jobs.erase(std::remove_if(jobs.begin(), jobs.end(), [](const Job &job) {
            return job.status != "DOWNLOADING";
        }), jobs.end());
        if (jobs.size() != beforeJobs) saveJobs();

        JsonDocument response;
        response["deleted"] = true;
        response["all"] = true;
        response["count"] = 0;
        sendJson(200, response);
        return;
    }
    if (rawId.length() > 10) { sendError(400, "INVALID_LOG_ID", "A numeric Telegram log ID is required."); return; }
    for (size_t i = 0; i < rawId.length(); ++i) if (!isDigit(rawId[i])) { sendError(400, "INVALID_LOG_ID", "A numeric Telegram log ID is required."); return; }
    const uint32_t id = static_cast<uint32_t>(strtoul(rawId.c_str(), nullptr, 10));
    const auto found = std::find_if(telegramLog.begin(), telegramLog.end(), [id](const TelegramLogEntry &entry) { return entry.id == id; });
    if (found == telegramLog.end()) { sendError(404, "LOG_ENTRY_NOT_FOUND", "No Telegram log entry has this ID."); return; }
    const TelegramLogEntry removed = *found;
    const String targetUrl = removed.url;
    const uint32_t targetUpdateId = removed.telegramUpdateId;
    telegramLog.erase(found);
    if (!saveTelegramLog()) {
        telegramLog.push_back(removed);
        sendError(503, "LOG_DELETE_FAILED", "Could not save the updated Telegram log.");
        return;
    }

    // Also remove matching non-downloading job from jobs list on LittleFS
    bool jobsChanged = false;
    jobs.erase(std::remove_if(jobs.begin(), jobs.end(), [&](const Job &job) {
        if (job.status == "DOWNLOADING") return false;
        bool match = false;
        if (targetUpdateId > 0 && job.telegramUpdateId == targetUpdateId) match = true;
        else if (!targetUrl.isEmpty() && job.url == targetUrl) match = true;
        if (match) jobsChanged = true;
        return match;
    }), jobs.end());
    if (jobsChanged) saveJobs();

    JsonDocument response; response["deleted"] = true; response["id"] = String(id); response["count"] = telegramLog.size(); sendJson(200, response);
}

bool isHttpUrl(const String &url) {
    return (url.startsWith("https://") || url.startsWith("http://")) &&
           url.length() <= kMaxUrlLength && url.indexOf('\r') < 0 && url.indexOf('\n') < 0;
}

bool isSupportedDownloadUrl(const String &url) {
    if (!isHttpUrl(url)) return false;
    const int schemeEnd = url.indexOf("://");
    const int hostStart = schemeEnd + 3;
    int hostEnd = url.indexOf('/', hostStart);
    if (hostEnd < 0) hostEnd = url.length();
    const int query = url.indexOf('?', hostStart);
    const int fragment = url.indexOf('#', hostStart);
    if (query >= 0 && query < hostEnd) hostEnd = query;
    if (fragment >= 0 && fragment < hostEnd) hostEnd = fragment;
    String host = url.substring(hostStart, hostEnd);
    host.toLowerCase();
    if (host.startsWith("www.")) host.remove(0, 4);
    const int portSeparator = host.indexOf(':');
    if (portSeparator >= 0) host = host.substring(0, portSeparator);
    String route = hostEnd < static_cast<int>(url.length()) ? url.substring(hostEnd) : "/";
    String routeLower = route;
    routeLower.toLowerCase();
    String mediaPath = routeLower;
    int queryAt = mediaPath.indexOf('?');
    if (queryAt >= 0) mediaPath = mediaPath.substring(0, queryAt);
    int fragmentAt = mediaPath.indexOf('#');
    if (fragmentAt >= 0) mediaPath = mediaPath.substring(0, fragmentAt);
    const auto hostMatches = [&host](const char *domain) {
        const String suffix = String(".") + domain;
        return host == domain || host.endsWith(suffix);
    };
    const bool allowImages = sharedHostSettings["downloadSettings"]["allowImageDownloads"] | false;
    if (allowImages && (mediaPath.endsWith(".jpg") || mediaPath.endsWith(".jpeg") || mediaPath.endsWith(".png") || mediaPath.endsWith(".gif") || mediaPath.endsWith(".webp") || mediaPath.endsWith(".bmp") || mediaPath.endsWith(".avif") || mediaPath.endsWith(".tif") || mediaPath.endsWith(".tiff"))) return true;
    const auto validToken = [](const String &value, size_t minimum, size_t maximum) {
        if (value.length() < minimum || value.length() > maximum) return false;
        for (size_t i = 0; i < value.length(); ++i) {
            const char c = value[i];
            if (!(isAlphaNumeric(c) || c == '_' || c == '-')) return false;
        }
        return true;
    };
    const auto valueAfter = [&route](const String &marker) {
        const int position = route.indexOf(marker);
        if (position < 0) return String();
        const int start = position + marker.length();
        int end = start;
        while (end < static_cast<int>(route.length()) && route[end] != '/' && route[end] != '?' && route[end] != '#' && route[end] != '&') ++end;
        return route.substring(start, end);
    };
    const auto idAfter = [&route, &validToken](const String &marker, size_t minimum) {
        const int position = route.indexOf(marker);
        if (position < 0) return false;
        const int start = position + marker.length();
        int end = start;
        while (end < static_cast<int>(route.length()) && route[end] != '/' && route[end] != '?' && route[end] != '#' && route[end] != '&') ++end;
        const String value = route.substring(start, end);
        return validToken(value, minimum, 128);
    };
    if (hostMatches("youtu.be")) {
        const int start = route.startsWith("/") ? 1 : 0;
        int end = route.indexOf('/', start);
        if (end < 0) end = route.indexOf('?', start);
        if (end < 0) end = route.length();
        const String id = route.substring(start, end);
        if (validToken(id, 6, 20)) return true;
    }
    if (hostMatches("youtube.com")) {
        if (idAfter("/shorts/", 6) || idAfter("/live/", 6) || idAfter("/embed/", 6) || idAfter("/v/", 6) || idAfter("/clip/", 6)) return true;
        const int v = routeLower.indexOf("v=");
        if (v >= 0) {
            const int start = v + 2;
            int end = route.indexOf('&', start);
            if (end < 0) end = route.indexOf('#', start);
            if (end < 0) end = route.length();
            const String id = route.substring(start, end);
            if (validToken(id, 6, 20)) return true;
        }
    }
    if (hostMatches("tiktok.com")) {
        if (allowImages && routeLower.indexOf("/@") == 0 && routeLower.indexOf("/photo/") > 2 && idAfter("/photo/", 8)) return true;
        const String videoId = valueAfter("/video/");
        if (routeLower.indexOf("/@") == 0 && videoId.length() >= 8) {
            bool numeric = true;
            for (size_t i = 0; i < videoId.length(); ++i) if (!isDigit(videoId[i])) numeric = false;
            if (numeric) return true;
        }
        if ((host.startsWith("vm.") || host.startsWith("vt.")) && idAfter("/", 4)) return true;
        if (idAfter("/t/", 4)) return true;
        // Link nhúng / bản mobile cũ: /embed/v2/<id>, /embed/<id>, /v/<id>.html
        if (idAfter("/embed/v2/", 8) || idAfter("/embed/", 8)) return true;
        if (mediaPath.startsWith("/v/")) {
            int digits = 0;
            while (3 + digits < static_cast<int>(mediaPath.length()) && isDigit(mediaPath[3 + digits])) ++digits;
            if (digits >= 8) return true;
        }
        // Trang profile / kênh: /@username (có thể kèm / hoặc ?_r=1&_t=...), /@user/playlist/..., /@user/collection/...
        if (mediaPath.startsWith("/@")) {
            const int nameEnd = mediaPath.indexOf('/', 2);
            const String handle = nameEnd < 0 ? mediaPath.substring(2) : mediaPath.substring(2, nameEnd);
            const String rest = nameEnd < 0 ? String("") : mediaPath.substring(nameEnd);
            bool validHandle = handle.length() >= 2 && handle.length() <= 64;
            for (size_t i = 0; validHandle && i < handle.length(); ++i) {
                const char c = handle[i];
                if (!(isAlphaNumeric(c) || c == '.' || c == '_' || c == '-')) validHandle = false;
            }
            if (validHandle && (rest.isEmpty() || rest == "/" || rest.startsWith("/playlist/") || rest.startsWith("/collection/"))) return true;
        }
    }
    if (hostMatches("fb.watch")) {
        if (idAfter("/", 4)) return true;
    }
    if (hostMatches("facebook.com") || hostMatches("fb.com")) {
        if (idAfter("/reel/", 4) || idAfter("/videos/", 4) || idAfter("/posts/", 4) || idAfter("/share/r/", 4) || idAfter("/share/v/", 4) || idAfter("/share/p/", 4)) return true;
        const int v = routeLower.indexOf("v=");
        const int story = routeLower.indexOf("story_fbid=");
        if (v >= 0 || story >= 0) {
            const int start = v >= 0 ? v + 2 : story + 11;
            int end = route.indexOf('&', start);
            if (end < 0) end = route.indexOf('#', start);
            if (end < 0) end = route.length();
            return validToken(route.substring(start, end), 4, 128);
        }
    }
    return false;
}

String telegramEventKey(uint32_t updateId) {
    if (updateId == 0) return "";
    const int separator = telegramBotToken.indexOf(':');
    const String botId = separator > 0 ? telegramBotToken.substring(0, separator) : String("unknown-bot");
    return botId + ":" + String(updateId);
}

bool telegramUpdateAlreadyProcessed(uint32_t updateId) {
    if (updateId == 0) return false;
    const String eventKey = telegramEventKey(updateId);
    if (std::find(processedTelegramUpdates.begin(), processedTelegramUpdates.end(), eventKey) != processedTelegramUpdates.end()) return true;
    if (std::any_of(jobs.begin(), jobs.end(), [&eventKey](const Job &job) { return !job.telegramEventKey.isEmpty() && job.telegramEventKey == eventKey; })) return true;
    return std::any_of(telegramLog.begin(), telegramLog.end(), [&eventKey](const TelegramLogEntry &entry) { return !entry.telegramEventKey.isEmpty() && entry.telegramEventKey == eventKey; });
}

void rememberTelegramUpdate(const String &eventKey) {
    if (eventKey.isEmpty() || std::find(processedTelegramUpdates.begin(), processedTelegramUpdates.end(), eventKey) != processedTelegramUpdates.end()) return;
    if (processedTelegramUpdates.size() >= kMaxProcessedTelegramUpdates) processedTelegramUpdates.erase(processedTelegramUpdates.begin());
    processedTelegramUpdates.push_back(eventKey);
}

void recordTelegramUrl(const String &url, const String &chatId, const char *result, const String &detail, uint32_t receivedAt, uint32_t updateId) {
    TelegramLogEntry entry;
    entry.id = esp_random();
    if (entry.id == 0) entry.id = 1;
    while (std::any_of(telegramLog.begin(), telegramLog.end(), [&entry](const TelegramLogEntry &old) { return old.id == entry.id; })) {
        ++entry.id;
        if (entry.id == 0) entry.id = 1;
    }
    entry.url = url.length() > kMaxUrlLength ? url.substring(0, kMaxUrlLength) + "…" : url;
    entry.chatId = chatId;
    entry.result = result;
    entry.detail = detail;
    entry.receivedAt = receivedAt;
    entry.telegramUpdateId = updateId;
    entry.telegramEventKey = telegramEventKey(updateId);
    entry.urlFingerprint = telegramUrlFingerprint(url);
    rememberTelegramUpdate(entry.telegramEventKey);
    if (telegramLog.size() >= kMaxTelegramLogs) telegramLog.erase(telegramLog.begin());
    telegramLog.push_back(entry);
    saveTelegramLog();
}

bool telegramChatAllowed(const String &chatId) {
    if (telegramAllowedChatIds.isEmpty() || chatId.isEmpty()) return false;
    String list = telegramAllowedChatIds;
    list.replace(';', ',');
    list.replace(' ', ',');
    list.replace('\t', ',');
    list = String(",") + list + ",";
    return list.indexOf(String(",") + chatId + ",") >= 0;
}

bool sendTelegramMessageNow(const String &token, const String &chatId, const String &message) {
    if (token.isEmpty() || WiFi.status() != WL_CONNECTED) return false;
    WiFiClientSecure client;
    client.setInsecure();
    client.setTimeout(10);
    HTTPClient http;
    const String endpoint = String("https://api.telegram.org/bot") + token + "/sendMessage";
    if (!http.begin(client, endpoint)) return false;
    http.setTimeout(8000);
    http.addHeader("Content-Type", "application/json");
    JsonDocument request;
    request["chat_id"] = chatId;
    request["text"] = message;
    String body;
    serializeJson(request, body);
    const int status = http.POST(body);
    const String responseBody = status > 0 ? http.getString() : "";
    http.end();
    if (status < 200 || status >= 300) return false;
    JsonDocument response;
    return !deserializeJson(response, responseBody) && (response["ok"] | false);
}

bool sendTelegramMessage(const String &chatId, const String &message) {
    if (!outgoingTelegramMessages || chatId.isEmpty() || message.isEmpty()) return false;
    OutgoingTelegramMessage outgoing;
    if (chatId.length() >= sizeof(outgoing.chatId) || message.length() >= sizeof(outgoing.text)) return false;
    strlcpy(outgoing.chatId, chatId.c_str(), sizeof(outgoing.chatId));
    strlcpy(outgoing.text, message.c_str(), sizeof(outgoing.text));
    return xQueueSend(outgoingTelegramMessages, &outgoing, 0) == pdTRUE;
}

bool persistTelegramJob(const String &url, const String &chatId, uint32_t updateId, String &id, String &reason) {
    if (!storageReady) {
        reason = "LittleFS không sẵn sàng";
        return false;
    }
    if (hasSeenTelegramUrl(url)) {
        reason = "URL này đã được Telegram nhận trước đó";
        return false;
    }
    for (const Job &existing : jobs) {
        if (existing.url == url && (existing.status == "QUEUED" || existing.status == "DISPATCHED" || existing.status == "DOWNLOADING")) {
            reason = "URL này đã có trong hàng đợi hoặc đang tải";
            return false;
        }
    }
    if (jobs.size() >= kMaxJobs) {
        reason = "Hàng đợi đã đủ 24 job";
        return false;
    }
    Job job;
    job.id = jobId();
    job.url = url;
    job.source = "telegram";
    job.telegramChatId = chatId;
    job.telegramUpdateId = updateId;
    job.telegramEventKey = telegramEventKey(updateId);
    job.urlFingerprint = telegramUrlFingerprint(url);
    job.status = "QUEUED";
    job.createdAt = String("unix:") + String(static_cast<uint32_t>(time(nullptr)));
    jobs.push_back(job);
    if (!saveJobs()) {
        jobs.pop_back();
        reason = "Không ghi được queue xuống LittleFS";
        return false;
    }
    id = job.id;
    return true;
}

bool extractFirstUrl(const String &text, String &candidate) {
    int start = text.indexOf("https://");
    int schemeLength = 8;
    const int httpStart = text.indexOf("http://");
    if (httpStart >= 0 && (start < 0 || httpStart < start)) {
        start = httpStart;
        schemeLength = 7;
    }
    if (start < 0) return false;
    int end = start;
    while (end < static_cast<int>(text.length())) {
        const char c = text[end];
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '<' || c == '>') break;
        ++end;
    }
    candidate = text.substring(start, end);
    while (!candidate.isEmpty() && String(".,!?;:)]}").indexOf(candidate[candidate.length() - 1]) >= 0) {
        candidate.remove(candidate.length() - 1);
    }
    return candidate.length() >= static_cast<size_t>(schemeLength + 3);
}

void processTelegramMessage(JsonObjectConst message, uint32_t updateId) {
    const String chatId = String(message["chat"]["id"] | 0LL);
    if (chatId == "0") return;
    const uint32_t receivedAt = message["date"] | static_cast<uint32_t>(time(nullptr));
    String text = message["text"] | "";
    if (text.isEmpty()) text = message["caption"] | "";
    if (text.isEmpty()) return;
    String lowered = text;
    lowered.toLowerCase();
    if (lowered.startsWith("/start") || lowered.startsWith("/id") || lowered.startsWith("/myid")) {
        if (telegramAllowedChatIds.isEmpty()) {
            sendTelegramMessage(chatId, String("Chat ID của bạn: ") + chatId + "\nNhập ID này vào dashboard host để cấp quyền, sau đó gửi /start lại.");
        } else if (telegramChatAllowed(chatId)) {
            sendTelegramMessage(chatId, "Bot đã sẵn sàng. Hãy chia sẻ một URL TikTok, Facebook hoặc YouTube.");
        } else {
            sendTelegramMessage(chatId, "Chat này chưa được cấp quyền sử dụng bot.");
        }
        return;
    }

    String commandText = lowered;
    commandText.trim();
    if (commandText.startsWith("/")) commandText.remove(0, 1);
    const int botSuffix = commandText.indexOf('@');
    if (botSuffix >= 0) commandText = commandText.substring(0, botSuffix);

    if (commandText == "info") {
        if (!telegramChatAllowed(chatId)) {
            sendTelegramMessage(chatId, "Chat này chưa được cấp quyền sử dụng bot.");
            return;
        }
        int pendingJobs = 0, runningJobs = 0, completedJobs = 0, failedJobs = 0;
        for (const Job &job : jobs) {
            if (job.status == "QUEUED" || job.status == "RETRY_WAIT") pendingJobs++;
            else if (job.status == "DOWNLOADING") runningJobs++;
            else if (job.status == "COMPLETED") completedJobs++;
            else if (job.status == "FAILED" || job.status == "CANCELLED") failedJobs++;
        }
        uint32_t uptimeSec = millis() / 1000;
        uint32_t days = uptimeSec / 86400;
        uint32_t hours = (uptimeSec % 86400) / 3600;
        uint32_t minutes = (uptimeSec % 3600) / 60;
        uint32_t seconds = uptimeSec % 60;

        String msg = "📊 ESP DOWNLOAD STATUS\n";
        msg += "━━━━━━━━━━━━━━━━━━━━\n";
        msg += "📶 Wi-Fi: " + (WiFi.status() == WL_CONNECTED ? (WiFi.SSID() + " (" + WiFi.localIP().toString() + ", " + String(WiFi.RSSI()) + " dBm)") : "Mất kết nối") + "\n";
        msg += "🖥 PC Worker: " + (runtimeOnline ? (runtimeReady ? String("Online (yt-dlp OK)") : String("Online (đang chuẩn bị)")) : String("Offline")) + "\n";
        if (!runtimeBaseUrl.isEmpty()) msg += "   URL: " + runtimeBaseUrl + "\n";
        msg += "⚙️ ESP32: Uptime " + (days > 0 ? (String(days) + "d ") : "") + String(hours) + "h " + String(minutes) + "m " + String(seconds) + "s\n";
        msg += "   RAM Heap: " + String(ESP.getFreeHeap() / 1024) + " KB\n";
        msg += "   LittleFS: " + String(LittleFS.usedBytes() / 1024) + " / " + String(LittleFS.totalBytes() / 1024) + " KB\n";
        msg += "📥 Jobs: " + String(pendingJobs) + " chờ | " + String(runningJobs) + " tải | " + String(completedJobs) + " xong | " + String(failedJobs) + " lỗi\n";
        msg += "━━━━━━━━━━━━━━━━━━━━\n";
        msg += "Lệnh: gửi link để tải, gửi 'info', hoặc 'reboot'";
        sendTelegramMessage(chatId, msg);
        return;
    }

    if (commandText == "reboot") {
        if (!telegramChatAllowed(chatId)) {
            sendTelegramMessage(chatId, "Chat này chưa được cấp quyền sử dụng bot.");
            return;
        }
        sendTelegramMessage(chatId, "🔄 ESP32 đang khởi động lại ngay bây giờ...");
        delay(600);
        ESP.restart();
        return;
    }

    for (const GpioCommand &command : gpioCommands) {
        bool matched = false;
        if (!command.command.isEmpty() && commandText == command.command) {
            matched = true;
        }
        if (!matched && !command.trigger.isEmpty()) {
            String trig = command.trigger;
            trig.toLowerCase();
            trig.trim();
            if (lowered == trig || (trig.length() >= 3 && lowered.indexOf(trig) >= 0)) {
                matched = true;
            }
        }
        if (matched) {
            if (!telegramChatAllowed(chatId)) {
                sendTelegramMessage(chatId, "Chat này chưa được cấp quyền sử dụng bot.");
            } else if (triggerGpioCommand(command)) {
                if (!command.replyMessage.isEmpty()) {
                    sendTelegramMessage(chatId, command.replyMessage);
                } else if (command.pulseMs == 0) {
                    const int state = digitalRead(command.pin);
                    sendTelegramMessage(chatId, String("✅ Đã đảo mức GPIO ") + String(command.pin) + " sang " + (state == HIGH ? "HIGH" : "LOW") + ".");
                } else {
                    sendTelegramMessage(chatId, String("✅ Đã kích hoạt lệnh ") + (command.command.isEmpty() ? command.trigger : command.command) + " trên GPIO " + String(command.pin) + " trong " + String(command.pulseMs) + " ms.");
                }
            } else {
                sendTelegramMessage(chatId, "GPIO đang bận phát xung; hãy thử lại sau.");
            }
            return;
        }
    }

    String url;
    bool hasUrl = extractFirstUrl(text, url);
    if (!hasUrl) {
        JsonArrayConst entities = message["entities"].as<JsonArrayConst>();
        if (entities.isNull()) entities = message["caption_entities"].as<JsonArrayConst>();
        for (JsonObjectConst entity : entities) {
            if ((entity["type"] | "") == "text_link") {
                url = entity["url"] | "";
                hasUrl = !url.isEmpty();
                if (hasUrl) break;
            }
        }
    }
    if (!hasUrl) {
        if (telegramChatAllowed(chatId)) sendTelegramMessage(chatId, "Tin nhắn không có URL http/https hợp lệ nên không được đưa vào hàng đợi.");
        return;
    }
    if (telegramUpdateAlreadyProcessed(updateId)) return;
    if (!telegramChatAllowed(chatId)) {
        recordTelegramUrl(url, chatId, "REJECTED", "Chat ID chưa được cấp quyền", receivedAt, updateId);
        sendTelegramMessage(chatId, "Chat này chưa được cấp quyền sử dụng bot.");
        return;
    }
    if (url.length() > kMaxUrlLength) {
        recordTelegramUrl(url, chatId, "REJECTED", "URL dài hơn giới hạn 1024 ký tự", receivedAt, updateId);
        sendTelegramMessage(chatId, "URL quá dài (giới hạn 1024 ký tự), không được đưa vào hàng đợi.");
        return;
    }
    if (!isSupportedDownloadUrl(url)) {
        recordTelegramUrl(url, chatId, "REJECTED", "Chỉ chấp nhận URL TikTok, Facebook hoặc YouTube", receivedAt, updateId);
        sendTelegramMessage(chatId, "URL bị từ chối. Chỉ nhận link TikTok, Facebook hoặc YouTube dạng http/https.");
        return;
    }
    if (hasSeenTelegramUrl(url)) {
        rememberTelegramUpdate(telegramEventKey(updateId));
        saveTelegramLog();
        sendTelegramMessage(chatId, "URL này đã được nhận trước đó. Không tạo lại job; nếu muốn tải lại, dùng Restart download hoặc Force download trên host.");
        return;
    }

    String id;
    String reason;
    if (persistTelegramJob(url, chatId, updateId, id, reason)) {
        rememberTelegramUrlState(url, updateId, "QUEUED", id, receivedAt);
        recordTelegramUrl(url, chatId, "QUEUED", String("Đã lưu job #") + id, receivedAt, updateId);
        const bool pcOnline = runtimeOnline && runtimeOnlineSinceMs != 0 && millis() - runtimeOnlineSinceMs < 600000UL;
        const String handoffState = !pcOnline ? "\nWindows runtime offline; URL đang pending trên ESP32."
            : (!runtimeReady ? "\nWindows đang online nhưng yt-dlp/thư mục chưa sẵn sàng; URL vẫn pending."
                             : "\nWindows runtime online; đang chờ chuyển job.");
        sendTelegramMessage(chatId, String("Đã nhận URL và lưu vào queue ESP32: #") + id + handoffState);
    } else {
        recordTelegramUrl(url, chatId, "REJECTED", reason, receivedAt, updateId);
        sendTelegramMessage(chatId, String("URL chưa được đưa vào hàng đợi: ") + reason + ".");
    }
}

void copyTelegramRuntimeSettings(String &token, bool &enabled, uint32_t &offset, uint32_t &generation) {
    if (!telegramStateMutex) {
        token = telegramBotToken;
        enabled = telegramEnabled;
        offset = telegramUpdateOffset;
        generation = telegramConfigGeneration;
        return;
    }
    xSemaphoreTake(telegramStateMutex, portMAX_DELAY);
    token = telegramBotToken;
    enabled = telegramEnabled;
    offset = telegramUpdateOffset;
    generation = telegramConfigGeneration;
    xSemaphoreGive(telegramStateMutex);
}

bool telegramPollOnce(const String &token, uint32_t offset, uint32_t &nextOffset) {
    WiFiClientSecure client;
    client.setInsecure();
    client.setTimeout(10);
    HTTPClient http;
    String endpoint = String("https://api.telegram.org/bot") + token + "/getUpdates?limit=1&timeout=0&allowed_updates=%5B%22message%22%5D";
    if (offset > 0) endpoint += String("&offset=") + offset;
    if (!http.begin(client, endpoint)) {
        setTelegramLastError("Không mở được kết nối HTTPS Telegram");
        return false;
    }
    http.setTimeout(9000);
    const int status = http.GET();
    const String body = status > 0 ? http.getString() : "";
    http.end();
    if (status < 200 || status >= 300) {
        JsonDocument errorResponse;
        if (!deserializeJson(errorResponse, body) && !String(errorResponse["description"] | "").isEmpty()) {
            setTelegramLastError(errorResponse["description"].as<String>());
        } else {
            setTelegramLastError(String("Telegram API HTTP ") + status);
        }
        return false;
    }
    JsonDocument response;
    if (deserializeJson(response, body) || !(response["ok"] | false)) {
        setTelegramLastError("Telegram API trả JSON không hợp lệ hoặc token sai");
        return false;
    }
    JsonArrayConst updates = response["result"].as<JsonArrayConst>();
    if (updates.size() > 0) {
        JsonObjectConst update = updates[0].as<JsonObjectConst>();
        static PendingTelegramUpdate pending;
        pending.updateId = update["update_id"] | 0U;
        JsonObjectConst message = update["message"].as<JsonObjectConst>();
        if (!message.isNull()) {
            if (measureJson(message) >= sizeof(pending.messageJson)) {
                setTelegramLastError("Telegram message exceeded the pending-message limit");
                return false;
            }
            serializeJson(message, pending.messageJson, sizeof(pending.messageJson));
        }
        if (!pendingTelegramUpdates || xQueueSend(pendingTelegramUpdates, &pending, 0) != pdTRUE) {
            setTelegramLastError("Telegram message queue is busy; update will be retried");
            return false;
        }
        nextOffset = pending.updateId + 1;
    }
    setTelegramLastError("");
    return true;
}

void telegramPollingTask(void *parameter) {
    (void)parameter;
    uint32_t localOffset = 0;
    uint32_t lastGeneration = UINT32_MAX;
    uint32_t nextPollAt = 0;
    uint8_t consecutiveFailures = 0;
    while (true) {
        OutgoingTelegramMessage outgoing;
        if (outgoingTelegramMessages && xQueueReceive(outgoingTelegramMessages, &outgoing, 0) == pdTRUE) {
            String token;
            bool enabled = false;
            uint32_t offset = 0;
            uint32_t generation = 0;
            copyTelegramRuntimeSettings(token, enabled, offset, generation);
            if (enabled && !token.isEmpty()) {
                if (!sendTelegramMessageNow(token, outgoing.chatId, outgoing.text)) {
                    setTelegramLastError("Telegram sendMessage failed; check network and bot token");
                }
            }
        }

        if (static_cast<int32_t>(millis() - nextPollAt) < 0) {
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        String token;
        bool enabled = false;
        uint32_t persistedOffset = 0;
        uint32_t generation = 0;
        copyTelegramRuntimeSettings(token, enabled, persistedOffset, generation);
        if (generation != lastGeneration) {
            localOffset = persistedOffset;
            lastGeneration = generation;
        } else if (persistedOffset > localOffset) {
            localOffset = persistedOffset;
        }
        if (!enabled || token.isEmpty() || WiFi.status() != WL_CONNECTED) {
            if (token.isEmpty() || !enabled) setTelegramLastError("");
            nextPollAt = millis() + 1000UL;
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        if (ESP.getFreeHeap() < 65000U) {
            setTelegramLastError("Đang chờ bộ nhớ trống trước khi gọi Telegram HTTPS");
            nextPollAt = millis() + 60000UL;
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        if (!clockSyncRequested) {
            configTime(0, 0, "pool.ntp.org", "time.cloudflare.com");
            clockSyncRequested = true;
        }
        if (time(nullptr) < 1760000000) {
            setTelegramLastError("Đang chờ đồng bộ giờ NTP để xác thực HTTPS");
            nextPollAt = millis() + 2000UL;
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        if (telegramPollOnce(token, localOffset, localOffset)) {
            consecutiveFailures = 0;
            nextPollAt = millis() + 30000UL;
        } else {
            consecutiveFailures = consecutiveFailures < 255U ? consecutiveFailures + 1U : 255U;
            nextPollAt = millis() + (consecutiveFailures >= 2U ? 120000UL : 60000UL);
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

void telegramProcessPendingTick() {
    if (!pendingTelegramUpdates) return;
    for (uint8_t handled = 0; handled < 2; ++handled) {
        static PendingTelegramUpdate pending;
        if (xQueueReceive(pendingTelegramUpdates, &pending, 0) != pdTRUE) return;
        if (pending.messageJson[0] != '\0') {
            JsonDocument messageDocument;
            if (!deserializeJson(messageDocument, pending.messageJson)) {
                JsonObjectConst message = messageDocument.as<JsonObjectConst>();
                if (!message.isNull()) processTelegramMessage(message, pending.updateId);
            } else {
                setTelegramLastError("Could not parse a queued Telegram message");
            }
        }
        if (telegramStateMutex) xSemaphoreTake(telegramStateMutex, portMAX_DELAY);
        telegramUpdateOffset = std::max(telegramUpdateOffset, pending.updateId + 1);
        preferences.putUInt("tgOffset", telegramUpdateOffset);
        if (telegramStateMutex) xSemaphoreGive(telegramStateMutex);
    }
}

void handleAddJob() {
    if (!storageReady) {
        sendError(503, "STORAGE_UNAVAILABLE", "Persistent queue storage is unavailable.");
        return;
    }
    JsonDocument request;
    if (!readRequestJson(request)) {
        return;
    }
    const String url = request["url"] | "";
    const String source = request["source"] | "local-host";
    const bool force = request["force"].is<bool>() && request["force"].as<bool>();
    if (!isHttpUrl(url)) {
        sendError(400, "INVALID_URL", "Only valid HTTP or HTTPS URLs up to 1024 characters are accepted.");
        return;
    }
    if (source == "telegram" && hasSeenTelegramUrl(url)) {
        sendError(409, "TELEGRAM_URL_ALREADY_SEEN", "This Telegram URL was already received. Use Restart/Force download from the host to retry it.");
        return;
    }
    for (const Job &existing : jobs) {
        if (!force && existing.url == url && (existing.status == "QUEUED" || existing.status == "DISPATCHED" || existing.status == "DOWNLOADING")) {
            sendError(409, "DUPLICATE_ACTIVE_JOB", "This URL already has an active job.");
            return;
        }
    }
    if (jobs.size() >= kMaxJobs) {
        cleanupExpiredRecords();
    }
    while (jobs.size() >= kMaxJobs) {
        const auto oldestTerminal = std::find_if(jobs.begin(), jobs.end(), [](const Job &job) {
            return isTerminalJobStatus(job.status);
        });
        if (oldestTerminal == jobs.end()) {
            break;
        }
        jobs.erase(oldestTerminal);
    }
    if (jobs.size() >= kMaxJobs) {
        sendError(507, "QUEUE_FULL", "The queue is full. Remove old history before adding another job.");
        return;
    }

    Job job;
    job.id = jobId();
    job.url = url;
    job.source = source;
    job.telegramChatId = request["telegramChatId"] | "";
    job.telegramUpdateId = request["telegramUpdateId"] | 0U;
    job.urlFingerprint = source == "telegram" ? telegramUrlFingerprint(url) : "";
    job.force = force;
    job.status = "QUEUED";
    job.createdAt = request["createdAt"] | "";
    if (job.createdAt.isEmpty()) {
        job.createdAt = String("uptime:") + String(millis());
    }
    jobs.push_back(job);
    if (!saveJobs()) {
        jobs.pop_back();
        sendError(503, "QUEUE_PERSIST_FAILED", "The job could not be saved to persistent storage.");
        return;
    }
    if (source == "telegram") {
        rememberTelegramUrlFingerprint(job.urlFingerprint, job.telegramUpdateId, job.status, job.id, static_cast<uint32_t>(time(nullptr)));
        saveTelegramLog();
    }
    JsonDocument response;
    JsonObject result = response["job"].to<JsonObject>();
    serializeJob(result, job);
    server.sendHeader("Location", String("/api/v1/jobs/") + job.id);
    sendJson(201, response);
}

Job *findJob(const String &id) {
    auto found = std::find_if(jobs.begin(), jobs.end(), [&id](const Job &job) { return job.id == id; });
    return found == jobs.end() ? nullptr : &(*found);
}

void handleJobRoute(const String &path) {
    const String prefix = "/api/v1/jobs/";
    String remainder = path.substring(prefix.length());
    const int slash = remainder.indexOf('/');
    const String id = slash >= 0 ? remainder.substring(0, slash) : remainder;
    const String action = slash >= 0 ? remainder.substring(slash + 1) : "";
    Job *job = findJob(id);
    if (job == nullptr) {
        sendError(404, "JOB_NOT_FOUND", "No job exists with this ID.");
        return;
    }

    if (server.method() == HTTP_GET && action.isEmpty()) {
        JsonDocument response;
        serializeJob(response["job"].to<JsonObject>(), *job);
        sendJson(200, response);
        return;
    }
    if (server.method() == HTTP_POST && action == "cancel") {
        if (job->status == "QUEUED") {
            job->status = "CANCELLED";
            job->terminalAt = static_cast<uint32_t>(time(nullptr));
        } else if (job->status == "DISPATCHED" || job->status == "DOWNLOADING") {
            job->status = "CANCEL_REQUESTED";
        } else {
            sendError(409, "JOB_NOT_ACTIVE", "This job cannot be cancelled in its current state.");
            return;
        }
        if (!saveJobs()) {
            sendError(503, "QUEUE_PERSIST_FAILED", "Could not persist the cancellation request.");
            return;
        }
        JsonDocument response;
        serializeJob(response["job"].to<JsonObject>(), *job);
        sendJson(200, response);
        return;
    }
    if (server.method() == HTTP_POST && action == "retry") {
        if (job->status != "FAILED" && job->status != "CANCELLED" && job->status != "RECOVERY_REQUIRED") {
            sendError(409, "JOB_NOT_RETRYABLE", "Only failed, cancelled, or recovery-required jobs can be retried.");
            return;
        }
        job->status = "QUEUED";
        job->progress = 0.0F;
        job->speed = "";
        job->eta = "";
        job->error = "";
        job->outputPath = "";
        job->terminalAt = 0;
        if (!saveJobs()) {
            sendError(503, "QUEUE_PERSIST_FAILED", "Could not persist the retry request.");
            return;
        }
        JsonDocument response;
        serializeJob(response["job"].to<JsonObject>(), *job);
        sendJson(200, response);
        return;
    }
    if ((server.method() == HTTP_DELETE && (action == "delete" || action.isEmpty())) ||
        (server.method() == HTTP_POST && action == "delete")) {
        if (job->status == "DOWNLOADING") {
            sendError(409, "JOB_DOWNLOADING", "Stop or cancel active download before removing from history.");
            return;
        }
        const String jobUrl = job->url;
        const uint32_t tgUpdateId = job->telegramUpdateId;
        const auto position = std::find_if(jobs.begin(), jobs.end(), [&id](const Job &candidate) { return candidate.id == id; });
        const size_t index = static_cast<size_t>(std::distance(jobs.begin(), position));
        Job removed = *position;
        jobs.erase(position);
        if (!saveJobs()) {
            jobs.insert(jobs.begin() + index, removed);
            sendError(503, "QUEUE_PERSIST_FAILED", "Could not persist removal from queue history.");
            return;
        }

        // Also remove matching Telegram log entry on LittleFS so it doesn't linger as "JOB_REMOVED"
        bool tgLogChanged = false;
        telegramLog.erase(std::remove_if(telegramLog.begin(), telegramLog.end(), [&](const TelegramLogEntry &entry) {
            bool match = false;
            if (tgUpdateId > 0 && entry.telegramUpdateId == tgUpdateId) match = true;
            else if (!jobUrl.isEmpty() && entry.url == jobUrl) match = true;
            if (match) tgLogChanged = true;
            return match;
        }), telegramLog.end());
        if (tgLogChanged) saveTelegramLog();

        JsonDocument response;
        response["deleted"] = true;
        response["id"] = id;
        sendJson(200, response);
        return;
    }
    sendError(405, "METHOD_NOT_ALLOWED", "This method is not supported for the job route.");
}

void handleClearJobs() {
    const size_t before = jobs.size();
    jobs.erase(std::remove_if(jobs.begin(), jobs.end(), [](const Job &job) {
        return job.status != "DOWNLOADING";
    }), jobs.end());
    const size_t removedCount = before - jobs.size();
    if (removedCount > 0) saveJobs();
    JsonDocument response;
    response["deleted"] = true;
    response["all"] = true;
    response["count"] = removedCount;
    response["remaining"] = jobs.size();
    sendJson(200, response);
}

void handleWorkerClaim() {
    JsonDocument request;
    if (!readRequestJson(request)) {
        return;
    }
    const String workerId = request["workerId"] | "local-host";
    for (Job &job : jobs) {
        if (job.status != "QUEUED") {
            continue;
        }
        job.status = "DISPATCHED";
        job.workerId = workerId;
        job.attempts++;
        job.progress = 0.0F;
        if (!saveJobs()) {
            job.status = "QUEUED";
            if (job.attempts > 0) job.attempts--;
            sendError(503, "QUEUE_PERSIST_FAILED", "Could not persist worker claim.");
            return;
        }
        JsonDocument response;
        serializeJob(response["job"].to<JsonObject>(), job);
        sendJson(200, response);
        return;
    }
    JsonDocument response;
    response["job"] = nullptr;
    sendJson(200, response);
}

void handleWorkerRoute(const String &path) {
    const String jobsPrefix = "/api/v1/worker/jobs/";
    if (!path.startsWith(jobsPrefix)) {
        sendError(404, "ROUTE_NOT_FOUND", "Unknown worker route.");
        return;
    }
    String remainder = path.substring(jobsPrefix.length());
    const int slash = remainder.indexOf('/');
    if (slash < 0) {
        sendError(404, "ROUTE_NOT_FOUND", "Unknown worker action.");
        return;
    }
    const String id = remainder.substring(0, slash);
    const String action = remainder.substring(slash + 1);
    Job *job = findJob(id);
    if (job == nullptr) {
        sendError(404, "JOB_NOT_FOUND", "No job exists with this ID.");
        return;
    }
    JsonDocument request;
    if (!readRequestJson(request)) {
        return;
    }

    if (action == "event") {
        const String nextStatus = request["status"] | job->status;
        if (nextStatus == "DOWNLOADING" || nextStatus == "VERIFYING") {
            job->status = nextStatus;
        }
        const float nextProgress = request["progress"] | job->progress;
        job->progress = std::max(0.0F, std::min(100.0F, nextProgress));
        job->speed = request["speed"] | job->speed;
        job->eta = request["eta"] | job->eta;
        JsonDocument response;
        response["accepted"] = true;
        response["persisted"] = false;
        sendJson(200, response);
        return;
    }
    if (action == "result") {
        const bool success = request["success"] | false;
        const bool cancelled = request["cancelled"] | false;
        job->status = cancelled ? "CANCELLED" : (success ? "COMPLETED" : "FAILED");
        job->terminalAt = static_cast<uint32_t>(time(nullptr));
        job->progress = success ? 100.0F : job->progress;
        job->outputPath = request["outputPath"] | "";
        job->outputBytes = request["outputBytes"] | 0ULL;
        job->error = request["error"] | "";
        job->speed = "";
        job->eta = "";
        for (TelegramLogEntry &entry : telegramLog) {
            if ((!job->urlFingerprint.isEmpty() && entry.urlFingerprint == job->urlFingerprint) ||
                (job->telegramUpdateId > 0 && entry.telegramUpdateId == job->telegramUpdateId) ||
                (entry.url == job->url)) {
                entry.result = job->status;
                if (!job->outputPath.isEmpty()) {
                    entry.detail = String("Đã lưu: ") + job->outputPath;
                } else if (!job->error.isEmpty()) {
                    entry.detail = job->error;
                }
            }
        }
        if (job->source == "telegram" && !job->urlFingerprint.isEmpty()) {
            rememberTelegramUrlFingerprint(job->urlFingerprint, job->telegramUpdateId, job->status, job->id, job->terminalAt);
        }
        saveTelegramLog();
        Job completedJob = *job;
        if (!job->telegramChatId.isEmpty()) {
            sendTelegramMessage(job->telegramChatId, job->status == "COMPLETED"
                ? String("✅ Tải URL hoàn tất: #") + job->id
                : String("❌ URL tải lỗi: #") + job->id + " — " + (job->error.isEmpty() ? job->status : job->error));
        }
        const auto position = std::find_if(jobs.begin(), jobs.end(), [&id](const Job &candidate) { return candidate.id == id; });
        if (position != jobs.end()) {
            jobs.erase(position);
        }
        if (!saveJobs()) {
            sendError(503, "QUEUE_PERSIST_FAILED", "Could not persist removal of completed worker job.");
            return;
        }
        JsonDocument response;
        serializeJob(response["job"].to<JsonObject>(), completedJob);
        sendJson(200, response);
        return;
    }
    sendError(404, "ROUTE_NOT_FOUND", "Unknown worker action.");
}

void runtimeHeartbeatTick() {
    const uint32_t intervalMs = runtimeOnline ? 60000UL : 15000UL;
    if (lastRuntimeHeartbeatMs != 0 && millis() - lastRuntimeHeartbeatMs < intervalMs) return;
    lastRuntimeHeartbeatMs = millis();
    if (runtimeBaseUrl.isEmpty() || WiFi.status() != WL_CONNECTED) {
        runtimeOnline = false;
        runtimeReady = false;
        return;
    }
    WiFiClient client;
    HTTPClient http;
    const String endpoint = runtimeBaseUrl + "/api/runtime/heartbeat";
    if (!http.begin(client, endpoint)) {
        runtimeOnline = false;
        runtimeReady = false;
        return;
    }
    http.setTimeout(2500);
    http.addHeader("X-Device-Token", apiToken);
    const int status = http.GET();
    const String body = status > 0 ? http.getString() : "";
    http.end();
    JsonDocument response;
    if (status >= 200 && status < 300 && !deserializeJson(response, body) && (response["online"] | false)) {
        runtimeOnline = true;
        runtimeReady = response["workerReady"] | false;
        runtimeOnlineSinceMs = millis();
    } else {
        runtimeOnline = false;
        runtimeReady = false;
    }
}

void runtimeDeliveryTick() {
    if (!runtimeOnline || !runtimeReady || runtimeBaseUrl.isEmpty() || WiFi.status() != WL_CONNECTED || millis() - lastRuntimeDeliveryMs < 3000UL) return;
    Job *next = nullptr;
    for (Job &job : jobs) {
        if (job.status == "QUEUED") {
            next = &job;
            break;
        }
    }
    if (next == nullptr) return;
    lastRuntimeDeliveryMs = millis();

    JsonDocument payload;
    serializeJob(payload["job"].to<JsonObject>(), *next);
    String body;
    serializeJson(payload, body);
    WiFiClient client;
    HTTPClient http;
    if (!http.begin(client, runtimeBaseUrl + "/api/runtime/jobs")) {
        runtimeOnline = false;
        runtimeReady = false;
        return;
    }
    http.setTimeout(4000);
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-Device-Token", apiToken);
    const int status = http.POST(body);
    const String responseBody = status > 0 ? http.getString() : "";
    http.end();
    JsonDocument response;
    if ((status == 200 || status == 202) && !deserializeJson(response, responseBody) && (response["accepted"] | false)) {
        if (response["alreadyCompleted"] | false) {
            next->status = response["status"] | "COMPLETED";
            next->outputPath = response["outputPath"] | next->outputPath;
            next->outputBytes = response["outputBytes"] | next->outputBytes;
            next->progress = 100.0F;
            next->terminalAt = static_cast<uint32_t>(time(nullptr));
            saveJobs();
            for (TelegramLogEntry &entry : telegramLog) {
                if ((!next->urlFingerprint.isEmpty() && entry.urlFingerprint == next->urlFingerprint) ||
                    (next->telegramUpdateId > 0 && entry.telegramUpdateId == next->telegramUpdateId) ||
                    (entry.url == next->url)) {
                    entry.result = next->status;
                    if (!next->outputPath.isEmpty()) entry.detail = String("Đã lưu: ") + next->outputPath;
                }
            }
            if (next->source == "telegram" && !next->urlFingerprint.isEmpty()) {
                rememberTelegramUrlFingerprint(next->urlFingerprint, next->telegramUpdateId, next->status, next->id, next->terminalAt);
            }
            saveTelegramLog();
        } else {
            next->status = "DISPATCHED";
            next->workerId = "windows-runtime";
            next->attempts++;
            next->progress = 0.0F;
            if (!saveJobs()) {
                next->status = "QUEUED";
                if (next->attempts > 0) next->attempts--;
                runtimeOnline = false;
                runtimeReady = false;
            }
        }
    } else if (status < 200 || status >= 300) {
        runtimeOnline = false;
        runtimeReady = false;
    }
}

void handleReboot() {
    JsonDocument response;
    response["success"] = true;
    response["message"] = "ESP32 đang khởi động lại...";
    sendJson(200, response);
    server.close();
    delay(500);
    ESP.restart();
}

void handleIdentify() {
    JsonDocument document;
    document["app"] = "esp-download";
    document["device"] = "controller";
    document["board"] = kBoardProfile;
    document["chip"] = ESP.getChipModel();
    document["version"] = kFirmwareVersion;
    document["port"] = 80;
    document["mac"] = WiFi.macAddress();
    document["ip"] = localAddress();
    document["runtimeBaseUrl"] = runtimeBaseUrl;
    document["apSsid"] = setupSsid;
    document["wifiConnected"] = WiFi.status() == WL_CONNECTED;
    document["ddnsEnabled"] = ddnsEnabled;
    document["ddnsDomain"] = ddnsDomain;
    sendJson(200, document);
}

void handleSaveDdns() {
    JsonDocument request;
    if (!readRequestJson(request)) return;
    ddnsEnabled = request["enabled"] | false;
    String domain = request["domain"] | "";
    domain.trim();
    if (domain.endsWith(".duckdns.org")) {
        domain = domain.substring(0, domain.length() - 12);
    }
    ddnsDomain = domain;
    if (request["token"].is<const char *>()) {
        const char *tok = request["token"].as<const char *>();
        if (strlen(tok) > 0) {
            ddnsToken = tok;
            ddnsToken.trim();
            preferences.putString("ddnsToken", ddnsToken);
        }
    }
    preferences.putBool("ddnsEnabled", ddnsEnabled);
    preferences.putString("ddnsDomain", ddnsDomain);
    lastDdnsUpdateMs = 0;
    
    JsonDocument response;
    response["saved"] = true;
    response["ddnsEnabled"] = ddnsEnabled;
    response["ddnsDomain"] = ddnsDomain;
    response["tokenConfigured"] = !ddnsToken.isEmpty();
    sendJson(200, response);
}

void ddnsTick() {
    if (!ddnsEnabled || ddnsDomain.isEmpty() || ddnsToken.isEmpty()) return;
    if (WiFi.status() != WL_CONNECTED) return;
    const uint32_t now = millis();
    if (lastDdnsUpdateMs != 0 && now - lastDdnsUpdateMs < 900000UL) return;
    lastDdnsUpdateMs = now == 0 ? 1 : now;

    WiFiClient client;
    HTTPClient http;
    String cleanDomain = ddnsDomain;
    cleanDomain.trim();
    if (cleanDomain.endsWith(".duckdns.org")) {
        cleanDomain = cleanDomain.substring(0, cleanDomain.length() - 12);
    }
    const String endpoint = "http://www.duckdns.org/update?domains=" + cleanDomain + "&token=" + ddnsToken + "&ip=";
    if (http.begin(client, endpoint)) {
        http.setTimeout(8000);
        const int code = http.GET();
        if (code == 200) {
            const String resp = http.getString();
            ddnsLastStatus = (resp.indexOf("OK") >= 0) ? "Cập nhật thành công (OK)" : "DuckDNS: " + resp;
            Serial.printf("DDNS: %s\n", ddnsLastStatus.c_str());
        } else {
            ddnsLastStatus = "Lỗi HTTP " + String(code);
            Serial.printf("DDNS update failed: %s\n", ddnsLastStatus.c_str());
        }
        http.end();
    }
}

void handleMobileLogin() {
    JsonDocument req;
    if (!readRequestJson(req)) return;
    const String user = req["username"] | "";
    const String pass = req["password"] | "";
    if ((user == "admin" && (pass == "admin" || pass == apiToken)) || pass == apiToken) {
        JsonDocument res;
        res["success"] = true;
        res["username"] = "admin";
        res["sessionToken"] = apiToken;
        res["deviceToken"] = apiToken;
        res["deviceApiUrl"] = "http://" + localAddress();
        res["isDirectEsp"] = true;
        sendJson(200, res);
    } else {
        sendError(401, "INVALID_CREDENTIALS", "Tài khoản hoặc mật khẩu không đúng.");
    }
}

void handleLocalStatus() {
    JsonDocument doc;
    JsonObject host = doc["host"].to<JsonObject>();
    host["address"] = "http://" + localAddress();
    JsonArray lanAddrs = host["lanAddresses"].to<JsonArray>();
    lanAddrs.add(localAddress());
    host["runtimeBaseUrl"] = runtimeBaseUrl;
    host["workerId"] = "esp32-direct-runtime";
    host["activeJobs"] = 0;
    host["pendingJobs"] = jobs.size();

    JsonObject dev = doc["device"].to<JsonObject>();
    dev["connected"] = true;
    dev["apiReachable"] = true;
    dev["authenticated"] = true;
    dev["deviceId"] = WiFi.macAddress();
    dev["firmwareVersion"] = kFirmwareVersion;
    dev["boardProfile"] = kBoardProfile;
    dev["chip"] = ESP.getChipModel();
    dev["chipRevision"] = ESP.getChipRevision();
    dev["flashBytes"] = ESP.getFlashChipSize();
    dev["freeHeapBytes"] = ESP.getFreeHeap();
    dev["wifiStatus"] = wifiStatusName();
    dev["wifiConnected"] = WiFi.status() == WL_CONNECTED;
    dev["wifiSsid"] = WiFi.status() == WL_CONNECTED ? WiFi.SSID() : "";
    dev["ipAddress"] = localAddress();
    dev["rssi"] = WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0;
    dev["provisioningApActive"] = isSetupApActive();
    dev["provisioningApEnabled"] = apEnabled;
    dev["provisioningApRecoveryMode"] = apRecoveryMode;
    dev["ddnsEnabled"] = ddnsEnabled;
    dev["ddnsDomain"] = ddnsDomain;
    dev["ddnsTokenConfigured"] = !ddnsToken.isEmpty();
    dev["ddnsLastStatus"] = ddnsLastStatus;
    dev["telegramConfigured"] = !telegramBotToken.isEmpty();
    dev["telegramEnabled"] = telegramEnabled;
    dev["jobCount"] = jobs.size();
    dev["storageReady"] = storageReady;

    JsonObject tg = doc["telegram"].to<JsonObject>();
    tg["configured"] = !telegramBotToken.isEmpty();
    tg["tokenValid"] = !telegramBotToken.isEmpty();
    tg["botName"] = "ESP32 Telegram Bot";
    tg["pollingState"] = telegramBotToken.isEmpty() ? "not_configured" : "device_managed";

    sendJson(200, doc);
}

void handleInstallRuntimeScript() {
    String ip = WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : WiFi.softAPIP().toString();
    String script =
        "# ESP Download Windows Runtime Auto-Installer & Auto-Pair\r\n"
        "$espIp = \"" + ip + "\";\r\n"
        "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8;\r\n"
        "Write-Host \"=== CAI DAT VA KET NOI ESP DOWNLOAD WINDOWS RUNTIME ===\" -ForegroundColor Cyan;\r\n"
        "Write-Host \"Dia chi ESP32 phat hien: $espIp\" -ForegroundColor Green;\r\n"
        "\r\n"
        "# 1. Xac dinh thu muc runtime tren PC\r\n"
        "$runtimeDir = \"C:\\ESP-Download-Runtime\";\r\n"
        "if (Test-Path \".\\server.mjs\") { $runtimeDir = (Get-Location).Path }\r\n"
        "elseif (Test-Path \".\\local-host\\server.mjs\") { $runtimeDir = Join-Path (Get-Location).Path \"local-host\" }\r\n"
        "Set-Location $runtimeDir;\r\n"
        "\r\n"
        "# 2. Chay Install-TrayRuntime voi tham so -EspIp\r\n"
        "if (Test-Path \".\\Install-TrayRuntime.ps1\") {\r\n"
        "    & .\\Install-TrayRuntime.ps1 -EspIp $espIp;\r\n"
        "} else {\r\n"
        "    Write-Host \"Khong tim thay Install-TrayRuntime.ps1 tai $runtimeDir.\" -ForegroundColor Red;\r\n"
        "    Write-Host \"Vui long mo PowerShell ben trong thu muc local-host va chay lai lenh.\" -ForegroundColor Yellow;\r\n"
        "}\r\n";
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send(200, "text/plain; charset=utf-8", script);
}

void handleInstallRuntimeLinuxScript() {
    String ip = WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : WiFi.softAPIP().toString();
    String script =
        "#!/usr/bin/env bash\n"
        "# ESP Download Linux / Ubuntu Server Auto-Installer\n"
        "set -e\n"
        "ESP_IP=\"" + ip + "\"\n"
        "echo -e \"\\033[1;36m=== CAI DAT VA KET NOI ESP DOWNLOAD HOST TREN LINUX ===\\033[0m\"\n"
        "echo -e \"\\033[1;32mDia chi ESP32 phat hien: $ESP_IP\\033[0m\"\n"
        "if [ -f \"./install.sh\" ]; then\n"
        "    chmod +x ./install.sh\n"
        "    ./install.sh \"$ESP_IP\"\n"
        "elif [ -f \"./local-host/install.sh\" ]; then\n"
        "    cd ./local-host\n"
        "    chmod +x ./install.sh\n"
        "    ./install.sh \"$ESP_IP\"\n"
        "else\n"
        "    echo -e \"\\033[1;33mKhong tim thay install.sh trong thu muc hien tai.\\033[0m\"\n"
        "    echo \"Dang tai ma nguon tu GitHub ve...\"\n"
        "    git clone https://github.com/neopro90/Esp32-Download.git /tmp/esp32-download || true\n"
        "    cd /tmp/esp32-download/local-host\n"
        "    chmod +x ./install.sh\n"
        "    ./install.sh \"$ESP_IP\"\n"
        "fi\n";
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send(200, "text/plain; charset=utf-8", script);
}

void handleDockerComposeFile() {
    String ip = WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : WiFi.softAPIP().toString();
    String compose =
        "version: '3.8'\n"
        "services:\n"
        "  esp-download-host:\n"
        "    image: node:20-bookworm-slim\n"
        "    container_name: esp-download-host\n"
        "    restart: unless-stopped\n"
        "    network_mode: host\n"
        "    environment:\n"
        "      - ESP_DEVICE_BASE_URL=http://" + ip + "\n"
        "      - PORT=8787\n"
        "    volumes:\n"
        "      - ./data:/app/data\n"
        "      - ./downloads:/app/downloads\n"
        "      - .:/app\n"
        "    working_dir: /app\n"
        "    command: sh -c \"apt-get update && apt-get install -y python3 python3-pip ffmpeg && pip install --break-system-packages --upgrade yt-dlp gallery-dl && node server.mjs\"\n";
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send(200, "text/yaml; charset=utf-8", compose);
}

void routeRequest() {
    Serial.printf("[HTTP] %s %s (Host: %s)\n", server.method() == HTTP_GET ? "GET" : server.method() == HTTP_POST ? "POST" : "OTHER", server.uri().c_str(), server.hostHeader().c_str());

    if (setupApStarted) {
        const String rawPath = server.uri();
        if (rawPath == "/generate_204" || rawPath == "/gen_204" || rawPath == "/hotspot-detect.html" ||
            rawPath == "/canonical.html" || rawPath == "/connecttest.txt" || rawPath == "/ncsi.txt") {
            server.sendHeader("Location", "http://192.168.4.1/");
            server.send(302, "text/plain", "");
            return;
        }
    }

    if (server.method() == HTTP_OPTIONS) {
        server.sendHeader("Access-Control-Allow-Origin", "*");
        server.sendHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
        server.sendHeader("Access-Control-Allow-Headers", "Content-Type, X-Device-Token, X-Local-Session, Authorization");
        server.send(204);
        return;
    }
    String path = server.uri();
    // Hỗ trợ alias khi Android kết nối trực tiếp vào IP ESP32 gọi /api/device/api/v1/...
    if (path.startsWith("/api/device/api/v1/")) {
        path = path.substring(11); // chuyển thành /api/v1/...
    }

    if (server.method() == HTTP_GET && (path == "/install-runtime.ps1" || path == "/install-runtime")) {
        handleInstallRuntimeScript();
        return;
    }
    if (server.method() == HTTP_GET && (path == "/install-runtime.sh" || path == "/install-linux")) {
        handleInstallRuntimeLinuxScript();
        return;
    }
    if (server.method() == HTTP_GET && (path == "/docker-compose.yml" || path == "/docker-compose")) {
        handleDockerComposeFile();
        return;
    }

    if (server.method() == HTTP_POST && path == "/api/mobile/auth/login") {
        handleMobileLogin();
        return;
    }
    if (server.method() == HTTP_GET && path == "/api/mobile/health") {
        JsonDocument doc;
        doc["service"] = "esp-download-controller";
        doc["apiVersion"] = 1;
        doc["hostName"] = "ESP32";
        doc["port"] = 80;
        doc["isDirectEsp"] = true;
        sendJson(200, doc);
        return;
    }
    if (server.method() == HTTP_GET && path == "/api/auth/session") {
        JsonDocument doc;
        doc["authenticated"] = true;
        doc["username"] = "admin";
        doc["defaultPassword"] = false;
        doc["isDirectEsp"] = true;
        sendJson(200, doc);
        return;
    }
    if (server.method() == HTTP_GET && path == "/api/local/status") {
        handleLocalStatus();
        return;
    }
    if ((server.method() == HTTP_GET || server.method() == HTTP_PUT) && path == "/api/local/config") {
        if (server.method() == HTTP_GET) handleSettings();
        else handleSharedSettings();
        return;
    }
    if (server.method() == HTTP_POST && path == "/api/local/config/backup") {
        handleConfigBackup();
        return;
    }
    if (server.method() == HTTP_POST && path == "/api/local/config/restore") {
        handleConfigRestore();
        return;
    }
    if (server.method() == HTTP_POST && path == "/api/local/device/reboot") {
        handleReboot();
        return;
    }

    if (path == "/" || path == "/setup") {
        if (server.method() != HTTP_GET) {
            sendError(405, "METHOD_NOT_ALLOWED", "Only GET is allowed for this route.");
            return;
        }
        // Truy cập qua IP LAN (ví dụ http://192.168.1.5/): chỉ chuyển sang dashboard của PC
        // KHI VÀ CHỈ KHI Windows runtime thực sự đang online (runtimeFresh) và KHÔNG phải truy cập từ mạng ngoài (DuckDNS / NAT port).
        // Nếu PC đang tắt hoặc không kết nối, hoặc truy cập từ xa (DuckDNS / port ngoài), hiển thị trang cài đặt độc lập của ESP32.
        String hostHdr = server.hostHeader();
        hostHdr.toLowerCase();
        const bool isExternalAccess = (hostHdr.indexOf("duckdns.org") >= 0) ||
                                      (hostHdr.indexOf(":") >= 0 && hostHdr.indexOf(":80") < 0) ||
                                      (!hostHdr.startsWith("192.168.") && !hostHdr.startsWith("10.") && !hostHdr.startsWith("172.") && !hostHdr.startsWith("esp32") && !hostHdr.startsWith("localhost"));

        const bool runtimeFresh = runtimeOnline && runtimeOnlineSinceMs != 0 && millis() - runtimeOnlineSinceMs < 600000UL;
        if (path == "/" && !server.hasArg("setup") && !isExternalAccess && !runtimeBaseUrl.isEmpty() && WiFi.status() == WL_CONNECTED && runtimeFresh) {
            server.sendHeader("Location", runtimeBaseUrl + "/");
            server.sendHeader("Cache-Control", "no-store");
            server.send(302, "text/plain", "Redirecting to dashboard");
            return;
        }
        server.send_P(200, "text/html; charset=utf-8", kSetupPage);
        return;
    }
    if (path.startsWith("/setup/")) {
        if (server.method() == HTTP_GET && path == "/setup/status") {
            handleSetupStatus();
        } else if (server.method() == HTTP_GET && path == "/setup/jobs") {
            handleSetupJobs();
        } else if (server.method() == HTTP_DELETE && path.startsWith("/setup/jobs/") && path.endsWith("/delete")) {
            handleSetupDeleteJob(path);
        } else if (server.method() == HTTP_GET && path == "/setup/scan") {
            handleWifiScan();
        } else if (server.method() == HTTP_GET && path == "/setup/settings") {
            handleSettings();
        } else if (server.method() == HTTP_POST && path == "/setup/network") {
            handleSaveNetwork();
        } else if (server.method() == HTTP_POST && path == "/setup/integrations") {
            handleIntegrations();
        } else if (server.method() == HTTP_POST && path == "/setup/reboot") {
            handleReboot();
        } else if (server.method() == HTTP_GET && path == "/setup/identify") {
            handleIdentify();
        } else if (server.method() == HTTP_POST && path == "/setup/ddns") {
            handleSaveDdns();
        } else if (server.method() == HTTP_GET && path == "/setup/backup") {
            handleConfigBackup();
        } else if (server.method() == HTTP_POST && path == "/setup/restore") {
            handleConfigRestore();
        } else {
            sendError(404, "SETUP_ROUTE_NOT_FOUND", "Unknown setup route.");
        }
        return;
    }
    if (server.method() == HTTP_GET && path == "/api/v1/identify") {
        handleIdentify();
        return;
    }
    if (!path.startsWith("/api/v1/")) {
        sendError(404, "ROUTE_NOT_FOUND", "Unknown API route.");
        return;
    }
    if (!authorized()) {
        sendError(401, "UNAUTHORIZED", "Send the device API token in X-Device-Token.");
        return;
    }

    if (server.method() == HTTP_GET && path == "/api/v1/system") {
        handleSystem();
    } else if (server.method() == HTTP_POST && path == "/api/v1/system/reboot") {
        handleReboot();
    } else if ((server.method() == HTTP_GET || server.method() == HTTP_PUT) && path == "/api/v1/settings/shared") {
        handleSharedSettings();
    } else if (server.method() == HTTP_GET && path == "/api/v1/wifi/scan") {
        handleWifiScan();
    } else if (server.method() == HTTP_GET && path == "/api/v1/settings") {
        handleSettings();
    } else if (server.method() == HTTP_GET && path == "/api/v1/config/backup") {
        handleConfigBackup();
    } else if (server.method() == HTTP_POST && path == "/api/v1/config/restore") {
        handleConfigRestore();
    } else if (server.method() == HTTP_POST && path == "/api/v1/settings/network") {
        handleSaveNetwork();
    } else if ((server.method() == HTTP_POST || server.method() == HTTP_PUT) && path == "/api/v1/settings/ddns") {
        handleSaveDdns();
    } else if (server.method() == HTTP_GET && path == "/api/v1/settings/ddns") {
        JsonDocument response;
        response["ddnsEnabled"] = ddnsEnabled;
        response["ddnsDomain"] = ddnsDomain;
        response["tokenConfigured"] = !ddnsToken.isEmpty();
        response["ddnsToken"] = ddnsToken;
        response["token"] = ddnsToken;
        response["ddnsLastStatus"] = ddnsLastStatus;
        sendJson(200, response);
    } else if ((server.method() == HTTP_POST || server.method() == HTTP_PUT) && path == "/api/v1/settings/integrations") {
        handleIntegrations();
    } else if (server.method() == HTTP_POST && path == "/api/v1/settings/network/reset") {
        handleClearNetwork();
    } else if (server.method() == HTTP_POST && path == "/api/v1/settings/ap") {
        handleApSettings();
    } else if (server.method() == HTTP_GET && path == "/api/v1/jobs") {
        handleGetJobs();
    } else if (server.method() == HTTP_DELETE && (path == "/api/v1/jobs" || path == "/api/v1/jobs/all")) {
        handleClearJobs();
    } else if (server.method() == HTTP_GET && path == "/api/v1/telegram/log") {
        handleTelegramLog();
    } else if ((server.method() == HTTP_DELETE && (path == "/api/v1/telegram/log" || path.startsWith("/api/v1/telegram/log/"))) ||
               (server.method() == HTTP_POST && path.startsWith("/api/v1/telegram/log/") && path.endsWith("/delete"))) {
        handleDeleteTelegramLog(path);
    } else if (server.method() == HTTP_POST && path == "/api/v1/jobs") {
        handleAddJob();
    } else if (server.method() == HTTP_POST && path == "/api/v1/worker/claim") {
        handleWorkerClaim();
    } else if (path.startsWith("/api/v1/worker/jobs/")) {
        handleWorkerRoute(path);
    } else if (path.startsWith("/api/v1/jobs/")) {
        handleJobRoute(path);
    } else {
        sendError(404, "ROUTE_NOT_FOUND", "Unknown API route.");
    }
}

void startApiToken() {
    apiToken = preferences.isKey("apiToken") ? preferences.getString("apiToken") : "";
    if (apiToken.length() < 32) {
        apiToken = randomHex(24);
        preferences.putString("apiToken", apiToken);
    }
    Serial.printf("Device API token (save in localhost settings): %s\n", apiToken.c_str());
}

void loadIntegrationSettings() {
    telegramEnabled = preferences.getBool("tgEnabled", true);
    telegramBotToken = preferences.isKey("tgToken") ? preferences.getString("tgToken") : "";
    telegramAllowedChatIds = preferences.isKey("tgChats") ? preferences.getString("tgChats") : "";
    runtimeBaseUrl = preferences.isKey("runtimeUrl") ? preferences.getString("runtimeUrl") : "";
    telegramUpdateOffset = preferences.getUInt("tgOffset", 0);
    urlRetentionDays = preferences.getUShort("retentionDays", 30);
    ddnsEnabled = preferences.getBool("ddnsEnabled", false);
    ddnsDomain = preferences.isKey("ddnsDomain") ? preferences.getString("ddnsDomain") : "";
    ddnsToken = preferences.isKey("ddnsToken") ? preferences.getString("ddnsToken") : "";
    gpioCommands.clear();
    if (preferences.isKey("gpioCommands")) {
        JsonDocument document;
        const String saved = preferences.getString("gpioCommands");
        if (!deserializeJson(document, saved)) {
            JsonArrayConst commands = document.as<JsonArrayConst>();
            for (JsonObjectConst item : commands) {
                GpioCommand command;
                command.command = item["command"] | "";
                command.trigger = item["trigger"] | "";
                command.replyMessage = item["replyMessage"] | "";
                command.pin = item["pin"] | 22;
                command.pulseMs = item["pulseMs"] | 0;
                command.activeHigh = item["activeHigh"] | true;
                bool allowedPin = false;
                for (uint8_t allowed : kAllowedGpioPins) if (command.pin == allowed) allowedPin = true;
                if ((!command.command.isEmpty() || !command.trigger.isEmpty()) && allowedPin && command.pulseMs <= 10000 && gpioCommands.size() < kMaxGpioCommands) {
                    command.command.toLowerCase();
                    gpioCommands.push_back(command);
                    digitalWrite(command.pin, command.activeHigh ? LOW : HIGH);
                    pinMode(command.pin, OUTPUT);
                }
            }
        }
    }
    Serial.printf("Telegram receiver: %s\n", telegramBotToken.isEmpty() ? "not configured" : "configured");
    Serial.printf("Windows runtime endpoint: %s\n", runtimeBaseUrl.isEmpty() ? "not configured" : runtimeBaseUrl.c_str());
}

bool isSetupApClient() {
    const wifi_mode_t mode = WiFi.getMode();
    if (mode != WIFI_AP && mode != WIFI_AP_STA) {
        return false;
    }
    const IPAddress remote = server.client().remoteIP();
    return remote[0] == 192 && remote[1] == 168 && remote[2] == 4;
}

void handleSetupStatus() {
    JsonDocument document;
    document["wifiConnected"] = WiFi.status() == WL_CONNECTED;
    document["wifiStatus"] = wifiStatusName();
    document["wifiSsid"] = WiFi.status() == WL_CONNECTED ? WiFi.SSID() : "";
    document["wifiIp"] = WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : "";
    document["rssi"] = WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0;
    document["apActive"] = isSetupApActive();
    document["apEnabled"] = apEnabled;
    document["apRecoveryMode"] = apRecoveryMode;
    document["apSsid"] = setupSsid;
    document["apIp"] = WiFi.softAPIP().toString();
    document["wifiDisconnectCount"] = wifiDisconnectCount.load(std::memory_order_relaxed);
    document["wifiLastDisconnectReason"] = wifiLastDisconnectReason.load(std::memory_order_relaxed);
    const bool runtimeFresh = runtimeOnline && runtimeOnlineSinceMs != 0 && millis() - runtimeOnlineSinceMs < 600000UL;
    document["runtimeOnline"] = runtimeFresh;
    document["runtimeReady"] = runtimeFresh && runtimeReady;
    document["telegramConfigured"] = !telegramBotToken.isEmpty();
    sendJson(200, document);
}

}  // namespace

void setup() {
    Serial.begin(115200);
    delay(300);
    Serial.println();
    Serial.println("ESP Download headless controller booting");
    Serial.printf("Reset reason code: %u\n", static_cast<unsigned>(esp_reset_reason()));
    Serial.printf("Chip: %s, Flash: %u bytes, PSRAM: %s\n",
                  ESP.getChipModel(), ESP.getFlashChipSize(), psramFound() ? "present" : "none");
    registerWifiEvents();

    telegramStateMutex = xSemaphoreCreateMutex();
    pendingTelegramUpdates = xQueueCreate(2, sizeof(PendingTelegramUpdate));
    outgoingTelegramMessages = xQueueCreate(6, sizeof(OutgoingTelegramMessage));
    if (!telegramStateMutex || !pendingTelegramUpdates || !outgoingTelegramMessages) {
        Serial.println("ERROR: could not allocate Telegram background queues; Telegram processing is unavailable.");
    }

    preferences.begin(kPreferencesNamespace, false);
    initializeStorage();
    startApiToken();
    loadIntegrationSettings();
    beginWifi();

    const char *headerNames[] = {"X-Device-Token"};
    server.collectHeaders(headerNames, 1);
    server.onNotFound(routeRequest);
    server.begin();
    Serial.println("Headless API listening on port 80");
    if (telegramStateMutex && pendingTelegramUpdates && outgoingTelegramMessages &&
        xTaskCreatePinnedToCore(telegramPollingTask, "telegram-poller", 12288, nullptr, 1, &telegramTaskHandle, 0) == pdPASS) {
        Serial.println("Telegram fallback polling enabled on ESP32.");
    } else {
        Serial.println("ERROR: Telegram background task could not start.");
    }
}

void loop() {
    if (setupApStarted) {
        dnsServer.processNextRequest();
    }
    server.handleClient();
    ensureMdns();
    apToggleTick();
    telegramProcessPendingTick();
    apRecoveryTick();
    if (WiFi.status() != WL_CONNECTED && wifiNextReconnectAtMs != 0 && millis() >= wifiNextReconnectAtMs) {
        const String savedSsid = preferences.getString("wifiSsid", "");
        if (!savedSsid.isEmpty()) {
            if (WiFi.scanComplete() != WIFI_SCAN_RUNNING) {
                wifiNextReconnectAtMs = millis() + 8000UL;
                WiFi.reconnect();
            }
        } else {
            wifiNextReconnectAtMs = 0;
        }
    }
    if (millis() - lastRuntimeHeartbeatMs >= 60000UL || lastRuntimeHeartbeatMs == 0) runtimeHeartbeatTick();
    runtimeDeliveryTick();
    ddnsTick();
    gpioPulseTick();
    retentionCleanupTick();
    static uint32_t lastCpuSample = 0;
    if (millis() - lastCpuSample >= 1000) {
        lastCpuSample = millis();
        updateCpuLoad();
    }
    delay(2);
}
