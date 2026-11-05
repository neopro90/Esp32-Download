# Windows tray runtime

The Node.js controller serves the dashboard at `http://127.0.0.1:8787`. It also binds a private-LAN runtime endpoint so the ESP32 can heartbeat and hand off jobs while Windows is online. Requests to runtime endpoints require the ESP32 API token; dashboard requests require a loopback client and a signed-in account.

The dashboard login defaults to `admin` / `admin` on first run. Change the username or password from **Đổi tài khoản truy cập** after signing in. Passwords are stored as salted hashes in the local-only configuration file. Dashboard sessions expire after 12 hours and are cleared when the runtime restarts.

The dashboard pipeline has six nodes. Desktop arranges them as three columns by two rows; mobile and tablet layouts use two columns by three rows. The Storage node shows the Windows output folder and only lights the route when both the worker and folder are ready.

Run manually with:

```powershell
cd .\local-host
npm start
```

`Install-TrayRuntime.ps1` creates a Startup shortcut and starts the tray icon. Use the tray menu to open the local dashboard, start/stop the Node host, and open runtime logs. To allow delivery from the ESP32, run the installer once as Administrator with `-FirewallOnly`; it adds an inbound TCP 8787 rule restricted to the local subnet and Windows Private profile.

## Job handoff

Telegram updates are polled by the ESP32 over HTTPS. The ESP32 stores accepted URLs in its LittleFS queue and stores the latest 30 URL decisions in its Telegram inbox log. It keys each update by Telegram `update_id` and also keeps canonical URL fingerprints for the latest 512 Telegram URLs. On boot it migrates fingerprints from existing jobs and old log rows, including records that predate `update_id`; the ledger survives log and job deletion. Sending the same URL again from Telegram therefore does not create another job, while explicit Restart/Force actions on the host remain available for intentional retries. Changing a bot token secret while keeping the same bot ID does not reset the Telegram offset and replay its backlog. The inbox displays the linked job's current state, including queued, downloading, completed, failed, and cancelled. The ESP32 checks the configured Windows runtime URL every five minutes. If the host does not answer ready, jobs remain queued on the ESP32. When ready, the ESP32 sends jobs in queue order. The runtime persists each job to `data/runtime-queue.json` before acknowledging it, then runs one PowerShell/yt-dlp job at a time. Boolean worker options are parsed explicitly from true/false strings, avoiding PowerShell parameter binder errors. Progress and results are posted back to the device.

Set the ESP32 API URL, device API token, Windows runtime LAN URL, Telegram bot token, allowed chat IDs, yt-dlp path, and output folder in the dashboard. The token and allowed IDs are synchronized to the ESP32 settings API; the dashboard never returns the saved bot token.

The **Wi‑Fi / AP** panel shows the station SSID, IP, RSSI, and setup AP state. SSID and password inputs share a single aligned row with their scan/save buttons on desktop and stack into compact rows on mobile. The scan result cards appear below the inputs; selecting one fills the SSID field without changing the saved Wi‑Fi until **Lưu Wi‑Fi** is pressed. The AP switch is disabled unless the device connection is confirmed and Wi‑Fi is available to recover from.

Under **Host & Telegram**, **Tạo mã QR** calls `GET /api/local/device-pairing-qr.png`; it returns a PNG containing the local host URL, dashboard username, a one-time login bootstrap, ESP32 Device API URL and Device API token. Android uses the bootstrap to authenticate automatically, saves the device pairing, and submits it through `PUT /api/local/config`; the host then synchronizes shared settings and integrations to the ESP32 firmware. Leaving the Host & Telegram tab revokes and removes the displayed QR and its temporary download URL. This route requires the saved dashboard session and Device API token. Restart the tray runtime after updating its source so newly added API routes are loaded.

The Telegram **Kiểm tra token** action calls `getMe`. **Gửi tin nhắn thử** sends an actual test message to the entered chat ID using Telegram `sendMessage`. When no chat ID has been authorized and the ESP32 is offline or has not joined Wi-Fi, the host polls Telegram to answer `/start` with the numeric chat ID. Once a chat ID is saved, or the connected ESP32 has its own Telegram connection, the host stops polling so the ESP32 remains the sole update consumer. URL inbox entries can be reviewed in the dashboard; ordinary text without a URL is not queued. Video links must belong to TikTok/Facebook/YouTube; enabling image downloads also allows direct image links and TikTok photo posts.

`data/local-config.json` and `data/runtime-queue.json` are local-only files and must not be committed. Media files are written only on Windows. Direct image downloads are opt-in. TikTok photo posts need `gallery-dl` (install with `py -m pip install --user gallery-dl`) and are handled separately from the audio attempt. Windows needs outbound Internet for media downloads and host-originated Telegram test messages; the ESP32 needs outbound Internet to receive Telegram messages while Windows is offline.

Some TikTok posts are gated behind a logged-in account. In that case yt-dlp will request cookies even when the worker command is correct. The runtime does not access browser profiles or copy cookies automatically; configure an authorized yt-dlp cookie source locally before retrying such a URL. Treat cookie files like account credentials.
