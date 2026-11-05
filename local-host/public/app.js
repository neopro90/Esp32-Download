const $ = (selector) => document.querySelector(selector);
const jobsBody = $("#jobs-body");
const globalMessage = $("#global-message");
let refreshBusy = false;
let refreshQueued = false;
let cachedJobs = [];
let latestTelegramEntries = [];
let dashboardJobFilter = "queued";
let allowedGpioPins = [2, 4, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33];
let dashboardHistory = { jobs: [], completedBytes: 0 };
let latestDeviceState = {};
let devicePairingQrUrl = "";

function applyTheme(theme) {
  const selected = theme === "dark" ? "dark" : "light";
  document.documentElement.dataset.theme = selected;
  const button = $("#theme-toggle");
  if (button) { button.textContent = selected === "dark" ? "☀" : "☾"; button.setAttribute("aria-label", selected === "dark" ? "Chuyển sang giao diện sáng" : "Chuyển sang giao diện tối"); }
}
applyTheme(window.localStorage.getItem("esp-download-theme") || "light");
$("#theme-toggle").addEventListener("click", () => {
  const theme = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  window.localStorage.setItem("esp-download-theme", theme);
  applyTheme(theme);
});

// --- i18n Multi-Language System (VI / EN / CN) ---
const languageCodes = ["vi", "en", "zh"];
const languageLabels = { vi: "VI", en: "EN", zh: "CN" };
let currentLang = window.localStorage.getItem("esp-download-lang") || "vi";
if (!languageCodes.includes(currentLang)) currentLang = "vi";

const hostTranslations = {
  "Dashboard": ["Dashboard", "仪表盘"],
  "Hàng đợi": ["Queue", "队列"],
  "Wi‑Fi / AP": ["Wi-Fi / AP", "Wi-Fi / 热点"],
  "Host & Telegram": ["Host & Telegram", "主机与 Telegram"],
  "Cài đặt tải xuống": ["Download Settings", "下载设置"],
  "Bảo mật": ["Security", "安全"],
  "TÀI KHOẢN": ["ACCOUNT", "账户"],
  "Đổi tài khoản truy cập": ["Change Access Account", "修改访问账户"],
  "BACKUP · ESP32 + LOCAL HOST": ["BACKUP · ESP32 + LOCAL HOST", "备份 · ESP32 + 本地主机"],
  "Sao lưu và khôi phục hệ thống": ["System Backup & Restore", "系统备份与恢复"],
  "Sao lưu cấu hình": ["Backup Configuration", "备份配置"],
  "Khôi phục hệ thống": ["Restore System", "恢复系统"],
  "Tải file sao lưu": ["Download Backup", "下载备份文件"],
  "Khôi phục": ["Restore", "恢复"],
  "Chọn tệp": ["Choose File", "选择文件"],
  "Chưa có tệp nào": ["No file selected", "未选择任何文件"],
  "Lưu tài khoản": ["Save Account", "保存账户"],
  "Đăng xuất": ["Log Out", "退出登录"],
  "Mật khẩu hiện tại": ["Current Password", "当前密码"],
  "Tên đăng nhập mới": ["New Username", "新用户名"],
  "Mật khẩu mới": ["New Password", "新密码"],
  "Nhập lại mật khẩu mới": ["Confirm New Password", "确认新密码"],
  "Phiên đăng nhập và giới hạn truy cập": ["Session & Rate Limits", "登录会话与访问限制"],
  "Thời hạn phiên (phút)": ["Session Timeout (mins)", "会话超时（分钟）"],
  "Số lần đăng nhập sai tối đa": ["Max Failed Attempts", "最大重试次数"],
  "Cửa sổ giới hạn đăng nhập (phút)": ["Rate Limit Window (mins)", "限制窗口（分钟）"],
  "Lưu bảo mật": ["Save Security Settings", "保存安全设置"],
  "Kết nối Wi‑Fi": ["Wi-Fi Connection", "Wi-Fi 连接"],
  "Wi‑Fi SSID": ["Wi-Fi SSID", "Wi-Fi 名称 (SSID)"],
  "Mật khẩu Wi‑Fi": ["Wi-Fi Password", "Wi-Fi 密码"],
  "Quét Wi‑Fi": ["Scan Wi-Fi", "扫描 Wi-Fi"],
  "Lưu Wi‑Fi": ["Save Wi-Fi", "保存 Wi-Fi"],
  "Chất lượng video": ["Video Quality", "视频画质"],
  "Tốt nhất": ["Best Available", "最佳可用"],
  "Tối đa 1080p": ["Up to 1080p", "最高 1080p"],
  "Tối đa 720p": ["Up to 720p", "最高 720p"],
  "Chỉ âm thanh (MP3)": ["Audio Only (MP3)", "仅音频 (MP3)"],
  "Ảnh thumbnail": ["Thumbnail Image", "封面缩略图"],
  "Không tải thumbnail": ["Don't download", "不下载封面"],
  "Giữ định dạng gốc": ["Keep original format", "保留原始格式"],
  "Cấu trúc thư mục": ["Folder Structure", "目录结构"],
  "Tất cả trong thư mục gốc": ["Flat (root directory)", "平铺（全部存放在根目录）"],
  "Theo ngày tải lên": ["By upload date", "按上传日期分类"],
  "Theo nền tảng": ["By platform", "按平台分类"],
  "Nền tảng / ngày tải lên": ["Platform / upload date", "平台 / 上传日期分类"],
  "Số job chạy đồng thời": ["Concurrent Jobs", "并发任务数"],
  "Số fragment mỗi job": ["Fragments per Job", "单任务并发分片数"],
  "Lưu cài đặt tải xuống": ["Save Download Settings", "保存下载设置"],
  "Lưu cấu hình local": ["Save Local Config", "保存本地配置"],
  "Khởi động lại ESP32": ["Reboot ESP32", "重启 ESP32"],
  "Làm mới": ["Refresh", "刷新"],
  "Đổi giao diện sáng/tối": ["Toggle Light/Dark Theme", "切换明暗主题"],
  "Đổi ngôn ngữ": ["Change Language", "切换语言"],
  "Đang kiểm tra…": ["Checking…", "正在检查…"],
  "Đang chờ": ["Queued", "等待中"],
  "Hoàn tất": ["Completed", "已完成"],
  "Thất bại": ["Failed", "失败"],
  "Đang tải…": ["Downloading…", "下载中…"]
};

const reverseHostTranslations = new Map();
for (const [canonical, valArr] of Object.entries(hostTranslations)) {
  reverseHostTranslations.set(canonical, canonical);
  valArr.forEach((trans) => reverseHostTranslations.set(trans, canonical));
}

function translateDOM(root = document.body) {
  const isVi = currentLang === "vi";
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) {
    const node = walker.currentNode;
    const text = node.nodeValue;
    if (!text) continue;
    const trimmed = text.trim();
    if (!trimmed) continue;
    const canonical = reverseHostTranslations.get(trimmed);
    if (isVi) {
      if (canonical && canonical !== trimmed) {
        node.nodeValue = text.replace(trimmed, canonical);
      }
    } else {
      const target = canonical || trimmed;
      const pair = hostTranslations[target];
      if (pair) {
        const replacement = currentLang === "zh" ? pair[1] : pair[0];
        node.nodeValue = text.replace(trimmed, replacement);
      }
    }
  }
}

function applyLanguage(lang) {
  currentLang = lang;
  window.localStorage.setItem("esp-download-lang", lang);
  document.documentElement.lang = lang === "zh" ? "zh-CN" : lang;
  const btn = $("#lang-toggle");
  if (btn) btn.textContent = languageLabels[lang];
  translateDOM();
}

const langBtn = $("#lang-toggle");
if (langBtn) {
  langBtn.textContent = languageLabels[currentLang];
  langBtn.addEventListener("click", () => {
    const nextIdx = (languageCodes.indexOf(currentLang) + 1) % languageCodes.length;
    applyLanguage(languageCodes[nextIdx]);
  });
}
if (currentLang !== "vi") {
  window.setTimeout(() => applyLanguage(currentLang), 100);
}

async function requestJson(url, options = {}) {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 15000);
  const response = await fetch(url, {
    ...options,
    signal: controller.signal,
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) },
  });
  window.clearTimeout(timeout);
  const payload = await response.json().catch(() => ({}));
  if (response.status === 401 && payload.code === "LOGIN_REQUIRED") {
    window.location.replace("/login");
    throw new Error("Phiên đăng nhập đã hết hạn.");
  }
  if (!response.ok) {
    const error = new Error(payload.error?.message ?? payload.error ?? `HTTP ${response.status}`);
    error.status = response.status;
    error.code = payload.error?.code ?? payload.code;
    throw error;
  }
  return payload;
}

function showMessage(message, error = false) {
  globalMessage.textContent = message;
  globalMessage.classList.toggle("error", error);
  globalMessage.classList.remove("hidden");
  window.setTimeout(() => globalMessage.classList.add("hidden"), 6000);
}

function setPill(element, text, state) {
  element.className = `pill ${state}`;
  element.lastChild.textContent = ` ${text}`;
}

function setFeedback(element, text, error = false) {
  element.textContent = text;
  element.style.color = error ? "#a14b3c" : "#568267";
}

function selectPage(page) {
  if (page !== "settings") clearDevicePairingQr();
  document.querySelectorAll("[data-page]").forEach((panel) => panel.classList.toggle("hidden", panel.dataset.page !== page));
  document.querySelectorAll("[data-page-target]").forEach((tab) => {
    const selected = tab.dataset.pageTarget === page;
    tab.classList.toggle("active", selected);
    tab.setAttribute("aria-selected", String(selected));
  });
  window.localStorage.setItem("esp-download-page", page);
  if (page === "system-check") {
    void loadSystemCheck();
  }
}

function clearDevicePairingQr() {
  if (devicePairingQrUrl) URL.revokeObjectURL(devicePairingQrUrl);
  devicePairingQrUrl = "";
  const image = $("#device-pairing-image");
  const download = $("#device-pairing-download");
  const feedback = $("#device-pairing-feedback");
  if (image) { image.removeAttribute("src"); image.classList.add("hidden"); }
  if (download) { download.removeAttribute("href"); download.classList.add("hidden"); }
  if (feedback) feedback.textContent = "";
}

document.querySelectorAll("[data-page-target]").forEach((tab) => {
  tab.addEventListener("click", () => selectPage(tab.dataset.pageTarget));
});
selectPage(window.localStorage.getItem("esp-download-page") || "dashboard");

function formatBytes(value) {
  const bytes = Math.max(0, Number(value) || 0);
  if (bytes >= 1024 * 1024 * 1024 * 1024) return (bytes / (1024 * 1024 * 1024 * 1024)).toFixed(2) + " TB";
  if (bytes >= 1024 * 1024 * 1024) return (bytes / (1024 * 1024 * 1024)).toFixed(2) + " GB";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function updateMetricBar(id, percent) {
  const bar = $(id);
  const clamped = Math.max(0, Math.min(100, Number(percent) || 0));
  bar.style.width = `${clamped}%`;
  bar.parentElement.classList.toggle("warning", clamped >= 75 && clamped < 90);
  bar.parentElement.classList.toggle("danger", clamped >= 90);
}

function updatePipelineGeometry() {
  const map = $(".pipeline-map");
  if (!map) return;
  const mapRect = map.getBoundingClientRect();
  if (mapRect.width < 1 || mapRect.height < 1) return;
  const width = mapRect.width;
  const height = mapRect.height;
  const routes = [
    ["telegram", "pipeline-telegram", "pipeline-gateway"],
    ["gateway", "pipeline-gateway", "pipeline-esp32"],
    ["device-api", "pipeline-device-api", "pipeline-esp32"],
    ["worker", "pipeline-esp32", "pipeline-worker"],
    ["storage", "pipeline-worker", "pipeline-storage"],
    ["gateway-worker", "pipeline-gateway", "pipeline-worker"],
    ["device-api-gateway", "pipeline-device-api", "pipeline-gateway"],
    ["domain", "pipeline-domain", "pipeline-gateway"],
    ["gateway-pc", "pipeline-gateway", "pipeline-worker"],
    ["localapi-gateway", "pipeline-device-api", "pipeline-gateway"],
  ];
  const fmt = (value) => Number(value.toFixed(1));

  for (const [name, sourceId, targetId] of routes) {
    const sEl = document.getElementById(sourceId);
    const tEl = document.getElementById(targetId);
    if (!sEl || !tEl) continue;
    const source = sEl.getBoundingClientRect();
    const target = tEl.getBoundingClientRect();
    if (source.width < 1 || target.width < 1) continue;

    const sBox = {
      left: source.left - mapRect.left,
      right: source.right - mapRect.left,
      top: source.top - mapRect.top,
      bottom: source.bottom - mapRect.top,
      cx: source.left + source.width / 2 - mapRect.left,
      cy: source.top + source.height / 2 - mapRect.top,
    };
    const tBox = {
      left: target.left - mapRect.left,
      right: target.right - mapRect.left,
      top: target.top - mapRect.top,
      bottom: target.bottom - mapRect.top,
      cx: target.left + target.width / 2 - mapRect.left,
      cy: target.top + target.height / 2 - mapRect.top,
    };

    let startX;
    let startY;
    let endX;
    let endY;
    let cp1x;
    let cp1y;
    let cp2x;
    let cp2y;

    if (name === "gateway") {
      // ESP32 -> Gateway: reverse the existing Gateway-to-ESP32 path.
      startX = tBox.cx;
      startY = tBox.top;
      endX = sBox.cx;
      endY = sBox.bottom;
      const vOffset = Math.max(30, (startY - endY) * 0.5);
      cp1x = startX;
      cp1y = startY - vOffset;
      cp2x = endX;
      cp2y = endY + vOffset;
    } else if (name === "worker") {
      // ESP32 (bottom-middle) -> Worker (top-right): diagonal flow upwards
      startX = sBox.right;
      startY = sBox.cy;
      endX = tBox.left;
      endY = tBox.cy;
      const hOffset = Math.max(40, (endX - startX) * 0.5);
      cp1x = startX + hOffset;
      cp1y = startY;
      cp2x = endX - hOffset;
      cp2y = endY;
    } else if (name === "storage") {
      // Worker (top-right) -> Storage (bottom-right): vertical flow downwards
      startX = sBox.cx;
      startY = sBox.bottom;
      endX = tBox.cx;
      endY = tBox.top;
      const vOffset = Math.max(25, (endY - startY) * 0.5);
      cp1x = startX;
      cp1y = startY + vOffset;
      cp2x = endX;
      cp2y = endY - vOffset;
    } else if (name === "gateway-worker") {
      // Wi-Fi Gateway -> Windows Runtime: clean horizontal flow
      startX = sBox.right;
      startY = sBox.cy;
      endX = tBox.left;
      endY = tBox.cy;
      const hOffset = Math.max(30, (endX - startX) * 0.5);
      cp1x = startX + hOffset;
      cp1y = startY;
      cp2x = endX - hOffset;
      cp2y = endY;
    } else if (name === "gateway-pc") {
      // Gateway -> PC: curved line from gateway bottom to worker top
      startX = sBox.cx;
      startY = sBox.bottom;
      endX = tBox.left;
      endY = tBox.cy;
      const dx = Math.max(40, Math.abs(endX - startX) * 0.45);
      const dy = Math.max(25, Math.abs(endY - startY) * 0.45);
      cp1x = startX + dx * 0.4;
      cp1y = startY + dy;
      cp2x = endX - dx;
      cp2y = endY - dy * 0.4;
    } else if (name === "localapi-gateway") {
      // LocalHost -> Gateway: curved line from device-api top to gateway left
      startX = sBox.right;
      startY = sBox.cy;
      endX = tBox.cx;
      endY = tBox.bottom;
      const dx = Math.max(35, Math.abs(endX - startX) * 0.4);
      const dy = Math.max(25, Math.abs(endY - startY) * 0.4);
      cp1x = startX + dx;
      cp1y = startY - dy * 0.3;
      cp2x = endX + dx * 0.3;
      cp2y = endY + dy;
    } else if (name === "device-api-gateway") {
      // Local host API (bottom-left) -> Wi-Fi Gateway (top-middle): smooth S-curve upwards
      startX = sBox.right;
      startY = sBox.cy;
      endX = tBox.left;
      endY = tBox.cy;
      const hOffset = Math.max(30, (endX - startX) * 0.5);
      cp1x = startX + hOffset;
      cp1y = startY;
      cp2x = endX - hOffset;
      cp2y = endY;
    } else {
      // telegram -> gateway or device-api -> esp32: clean horizontal flow
      startX = sBox.right;
      startY = sBox.cy;
      endX = tBox.left;
      endY = tBox.cy;
      const hOffset = Math.max(35, (endX - startX) * 0.5);
      cp1x = startX + hOffset;
      cp1y = startY;
      cp2x = endX - hOffset;
      cp2y = endY;
    }

    const pathData = `M ${fmt(startX)} ${fmt(startY)} C ${fmt(cp1x)} ${fmt(cp1y)}, ${fmt(cp2x)} ${fmt(cp2y)}, ${fmt(endX)} ${fmt(endY)}`;
    for (const suffix of ["", "-mobile"]) {
      const svg = document.querySelector(`.pipeline-routes${suffix ? ".pipeline-routes-mobile" : ".pipeline-routes-desktop"}`);
      const route = document.getElementById(`route-${name}${suffix}`);
      if (!svg || !route) continue;
      svg.setAttribute("viewBox", `0 0 ${fmt(width)} ${fmt(height)}`);
      if (!route.dataset.particlesExpanded) {
        const original = route.querySelector(".route-particle");
        const originalMotion = original?.querySelector("animateMotion");
        const duration = Number.parseFloat(originalMotion?.getAttribute("dur") ?? "2") || 2;
        for (let index = 1; index < 3; index += 1) {
          const particle = original?.cloneNode(true);
          const motion = particle?.querySelector("animateMotion");
          if (!particle || !motion) continue;
          motion.setAttribute("begin", `${(duration * index / 3).toFixed(2)}s`);
          route.append(particle);
        }
        route.dataset.particlesExpanded = "true";
      }
      route.querySelector(".route-line")?.setAttribute("d", pathData);
      route.querySelectorAll("animateMotion").forEach((motion) => motion.setAttribute("path", pathData));
    }
  }
}

function setPipelineLink(name, connected, detail, routeConnected = connected) {
  const node = $(`#pipeline-${name}`);
  const route = $(`#route-${name}`);
  const mobileRoute = $(`#route-${name}-mobile`);
  const state = $(`#pipeline-${name}-state`);
  if (node) node.dataset.state = connected ? "up" : "down";
  if (route) route.classList.toggle("is-up", routeConnected), route.classList.toggle("is-down", !routeConnected);
  if (mobileRoute) mobileRoute.classList.toggle("is-up", routeConnected), mobileRoute.classList.toggle("is-down", !routeConnected);
  if (state) state.textContent = detail;
}

function isNasStoragePath(value) {
  const directory = String(value ?? "").trim();
  return /^(?:smb:\/\/|\\\\|\/\/)/i.test(directory);
}

function updatePipelineStorageLabel(directory) {
  const label = $("#pipeline-storage .pipeline-node-copy strong");
  if (label) label.textContent = isNasStoragePath(directory) ? "NAS" : "Storage · Windows";
}

function renderPipeline(status) {
  const device = status.device ?? {};
  const telegram = status.telegram ?? {};
  const deviceUp = device.connected === true;
  const apiUp = deviceUp && device.authenticated === true;
  const wifiUp = device.wifiConnected === true;
  const apUp = device.provisioningApActive === true;
  const gatewayUp = deviceUp && (wifiUp || apUp);
  const telegramConfigured = device.telegramConfigured === true && device.telegramEnabled !== false;
  const telegramPollingOk = !["ERROR", "AUTH_FAILED", "DISABLED"].includes(String(device.telegramPollState ?? "").toUpperCase());
  const telegramUp = telegramConfigured && telegram.tokenValid === true && telegramPollingOk && wifiUp;
  const workerUp = deviceUp && device.runtimeOnline === true;
  const workerDetail = !deviceUp || !device.runtimeOnline
    ? "Chưa có heartbeat"
    : (device.runtimeReady ? (status.downloader?.version ? "Runtime · yt-dlp sẵn sàng" : "Runtime · thiếu yt-dlp") : "Runtime đang khởi tạo");
  setPipelineLink("device-api", apiUp, apiUp ? "Token xác thực · online" : (deviceUp ? "Token bị từ chối" : "API offline"));
  setPipelineLink("gateway", gatewayUp, wifiUp ? `${device.wifiSsid || "Wi‑Fi"} · ${device.ipAddress || "online"}` : (apUp ? "Setup AP · espsystem" : (deviceUp ? "Chưa có mạng" : "ESP32 offline")));
  setPipelineLink("worker", workerUp, workerDetail);
  setPipelineLink("telegram", telegramUp, !telegramConfigured ? "Chưa cấu hình bot" : (!telegram.tokenValid ? "Bot API / token lỗi" : (!wifiUp ? "ESP32 chưa có Internet" : (telegramPollingOk ? "Bot API · polling hoạt động" : "ESP polling lỗi"))));
  const output = status.output ?? {};
  const outputWritable = output.writable === true;
  const outputPath = String(output.directory ?? "").trim();
  const outputDetail = outputPath || (output.error ? "Không đọc được thư mục lưu" : "Chưa cấu hình thư mục lưu");
  const storageNode = $("#pipeline-storage");
  if (storageNode) storageNode.title = outputPath;
  updatePipelineStorageLabel(outputPath);
  const gatewayWorkerUp = gatewayUp && workerUp;
  const deviceApiGatewayUp = apiUp && gatewayUp;
  const storageUp = workerUp && outputWritable;
  setPipelineLink("gateway-worker", gatewayWorkerUp, "", gatewayWorkerUp);
  setPipelineLink("device-api-gateway", deviceApiGatewayUp, "", deviceApiGatewayUp);
  const ddnsDomainRaw = String(device.ddnsDomain || status.host?.ddnsDomain || "").trim();
  const domainConfigured = Boolean(ddnsDomainRaw);
  const ddnsLastStatus = String(device.ddnsLastStatus || "");
  const ddnsFailed = Boolean(ddnsLastStatus && (ddnsLastStatus.includes("KO") || ddnsLastStatus.toLowerCase().includes("lỗi") || ddnsLastStatus.toLowerCase().includes("fail")));
  const domainUp = domainConfigured && gatewayUp && device.ddnsEnabled !== false && !ddnsFailed;
  const domainTitle = $("#pipeline-domain-title");
  if (domainTitle) domainTitle.textContent = "DDNS";
  const domainNode = $("#pipeline-domain");
  if (domainNode) domainNode.title = domainConfigured ? (ddnsDomainRaw.includes(".") ? ddnsDomainRaw : `${ddnsDomainRaw}.duckdns.org`) : "DDNS chưa cấu hình";
  const domainDetail = !domainConfigured
    ? "Chưa cấu hình"
    : (domainUp
        ? (ddnsLastStatus || (ddnsDomainRaw.includes(".") ? ddnsDomainRaw : `${ddnsDomainRaw}.duckdns.org`) || "Đang hoạt động")
        : (ddnsFailed ? ddnsLastStatus : (!device.ddnsEnabled ? "DDNS đang tắt" : (deviceUp ? "Chưa kết nối" : "ESP32 offline"))));
  setPipelineLink("domain", domainUp, domainDetail, domainUp);
  setPipelineLink("storage", outputWritable, outputDetail, storageUp);
  const core = $("#pipeline-esp32");
  if (core) core.dataset.state = deviceUp ? "up" : "down";
  $("#pipeline-esp32-state").textContent = deviceUp
    ? `Điều phối · ${device.storageReady ? "LittleFS sẵn sàng" : "kiểm tra LittleFS"}`
    : (device.error || "ESP32 chưa kết nối");
  const activeModules = [apiUp, domainUp, gatewayUp, deviceUp, workerUp, telegramUp, outputWritable].filter(Boolean).length;
  const overall = $(".hero-live-state");
  if (overall) {
    overall.dataset.state = activeModules === 7 ? "up" : (activeModules >= 4 ? "warn" : "down");
  }
  const overallState = $("#pipeline-overall-state");
  if (overallState) {
    overallState.textContent = deviceUp
      ? `${activeModules}/7 module trực tuyến · cập nhật trực tiếp`
      : "Chờ kết nối tới ESP32";
  }
  updatePipelineGeometry();
}

window.addEventListener("resize", updatePipelineGeometry);
if ("ResizeObserver" in window) {
  const pipelineResizeObserver = new ResizeObserver(updatePipelineGeometry);
  pipelineResizeObserver.observe($(".pipeline-map"));
}

function renderTelegramStatus(telegram = {}) {
  $("#telegram-token-tail").textContent = telegram.tokenLast4
    ? `Telegram token ****...${telegram.tokenLast4}`
    : "Telegram token: chưa cấu hình";
  if (!telegram.configured) {
    $("#telegram-state").textContent = "Chưa cấu hình";
    $("#telegram-meta").textContent = telegram.message ?? "Token chưa được kiểm tra";
    return;
  }
  if (telegram.tokenValid) {
    const runtimeLabels = {
      polling: "Bot đang chờ URL",
      awaiting_chat_id: "Chờ ghép nối chat",
      device_managed: "ESP32 đang nhận Telegram",
      disabled: "Đã tắt Telegram",
      webhook_conflict: "Webhook đang bật",
      error: "Bot runtime có lỗi",
      checking: "Đang kiểm tra runtime",
    };
    $("#telegram-state").textContent = runtimeLabels[telegram.pollingState] ?? "Token hoạt động";
    const botName = telegram.botUsername ? `@${telegram.botUsername}` : telegram.botName;
    const owner = telegram.ownerChatConfigured ? "chat đã cấp quyền" : "chưa cấp quyền chat";
    const update = telegram.lastUpdateAt ? ` · update ${new Date(telegram.lastUpdateAt).toLocaleTimeString()}` : "";
    $("#telegram-meta").textContent = telegram.lastError || `${botName} · ${owner}${update}`;
    return;
  }
  $("#telegram-state").textContent = telegram.tokenValid === false && !telegram.reachable
    ? "Token sai định dạng"
    : (telegram.reachable ? "Token/API lỗi" : "Telegram offline");
  $("#telegram-meta").textContent = telegram.message ?? "Chưa kiểm tra được Telegram API";
}

async function loadConfig() {
  const config = await requestJson("/api/local/config");
  if (Array.isArray(config.allowedGpioPins) && config.allowedGpioPins.length) {
    allowedGpioPins = config.allowedGpioPins;
  }
  $("#device-url").value = config.deviceBaseUrl;
  $("#runtime-url").value = config.runtimeBaseUrl ?? "";
  $("#worker-name").value = config.workerName ?? "windows-runtime";
  $("#yt-dlp-path").value = config.ytDlpPath;
  $("#ffmpeg-path").value = config.ffmpegPath ?? "";
  $("#deno-path").value = config.denoPath ?? "";
  $("#output-directory").value = config.outputDirectory;
  updatePipelineStorageLabel(config.outputDirectory);
  $("#url-retention-days").value = config.urlRetentionDays ?? 30;
  $("#telegram-enabled").checked = config.telegramEnabled !== false;
  renderGpioCommands(config.gpioCommands ?? []);
  $("#telegram-chat-id").value = "";
  const download = config.downloadSettings ?? {};
  $("#download-quality").value = download.videoQuality ?? "best";
  $("#thumbnail-format").value = download.thumbnailFormat ?? "off";
  $("#output-structure").value = download.outputStructure ?? "flat";
  $("#filename-template").value = download.filenameTemplate ?? "%(title).180B [%(id)s].%(ext)s";
  $("#merge-audio-video").checked = download.mergeAudioVideo !== false;
  $("#merge-format").value = download.mergeOutputFormat ?? "mp4";
  $("#allow-image-downloads").checked = download.allowImageDownloads === true;
  $("#max-concurrent-jobs").value = download.maxConcurrentJobs ?? 1;
  $("#fragment-concurrency").value = download.fragmentConcurrency ?? 4;
  $("#bandwidth-limit").value = download.bandwidthLimit ?? "";
  $("#retry-limit").value = download.retryLimit ?? 5;
  $("#retry-backoff").value = download.retryBackoffSeconds ?? 2;
  $("#socket-timeout").value = download.socketTimeoutSeconds ?? 30;
  $("#job-timeout").value = download.jobTimeoutMinutes ?? 0;
  $("#duplicate-url-policy").value = download.duplicateUrlPolicy ?? "skip_pending";
  $("#existing-file-policy").value = download.existingFilePolicy ?? "skip";
  $("#max-output-gb").value = download.maxOutputDirectoryGb ?? 0;
  $("#minimum-free-gb").value = download.minimumFreeDiskGb ?? 1;
  $("#runtime-history-limit").value = download.runtimeHistoryLimit ?? 100;
  $("#verify-output").checked = download.verifyOutput !== false;
  $("#adaptive-mode").checked = download.adaptiveMode === true;
  const security = config.securitySettings ?? {};
  $("#session-timeout").value = security.sessionTimeoutMinutes ?? 720;
  $("#login-max-attempts").value = security.maxLoginAttempts ?? 5;
  $("#login-window").value = security.loginWindowMinutes ?? 15;
  $("#device-token").placeholder = config.deviceTokenConfigured
    ? `Đã lưu ****...${config.deviceTokenLast4 || "????"} (nhập token mới để thay đổi)`
    : "Dán token từ Serial Monitor";
  $("#telegram-token").placeholder = config.telegramTokenConfigured
    ? `Đã lưu ****...${config.telegramTokenLast4 || "????"} (nhập token mới để thay đổi)`
    : "Token từ BotFather (tùy chọn)";
  const chatIdPlaceholder = config.telegramChatIdConfigured
    ? `Đã lưu ****...${config.telegramAllowedChatIdLast4 || "????"} (nhập ID mới để thay đổi)`
    : "Dán chat ID dạng số, ví dụ 123456789";
  $("#telegram-chat-id").placeholder = chatIdPlaceholder;
  $("#telegram-chat-id-help").textContent = config.telegramChatIdConfigured
    ? "Chat ID đã lưu; ô trống giữ nguyên cấu hình. Nhập ID mới để thay đổi hoặc bật tùy chọn xóa."
    : "Gửi /start trong chat Telegram với bot để bot trả lời chat ID dạng số. Không nhập chữ /start; có thể nhập nhiều ID, phân cách bằng dấu phẩy.";
  $("#clear-telegram-chat-id").checked = false;
}

function renderGpioCommands(commands = []) {
  const container = $("#gpio-commands");
  if (!container) return;
  container.replaceChildren();
  const availablePins = allowedGpioPins;
  for (const command of commands) {
    const row = document.createElement("div");
    row.className = "gpio-command-row";

    const name = document.createElement("input");
    name.type = "text";
    name.maxLength = 32;
    name.dataset.field = "command";
    name.placeholder = "Lệnh (/relay_1)";
    name.value = command.command ?? "";
    name.setAttribute("aria-label", "Tên lệnh");

    const trigger = document.createElement("input");
    trigger.type = "text";
    trigger.maxLength = 64;
    trigger.dataset.field = "trigger";
    trigger.placeholder = "Tin nhắn kích hoạt ('bật đèn')";
    trigger.value = command.trigger ?? "";
    trigger.setAttribute("aria-label", "Tin nhắn Telegram kích hoạt");

    const pin = document.createElement("select");
    pin.dataset.field = "pin";
    pin.setAttribute("aria-label", "GPIO Pin");
    for (const number of availablePins) {
      const option = document.createElement("option");
      option.value = String(number);
      option.textContent = `GPIO ${number}`;
      pin.append(option);
    }
    pin.value = String(command.pin ?? 22);

    const duration = document.createElement("input");
    duration.type = "number";
    duration.min = "0";
    duration.max = "10000";
    duration.step = "50";
    duration.dataset.field = "pulseMs";
    duration.value = String(command.pulseMs ?? 500);
    duration.placeholder = "0=Toggle";
    duration.title = "0 = Toggle đảo mức, >0 = Thời gian xung (ms)";
    duration.setAttribute("aria-label", "Xung (ms) hoặc 0 = Toggle");

    const polarity = document.createElement("select");
    polarity.dataset.field = "polarity";
    polarity.setAttribute("aria-label", "Mức kích hoạt");
    polarity.innerHTML = '<option value="high">Mức cao (HIGH)</option><option value="low">Mức thấp (LOW)</option>';
    polarity.value = command.activeHigh === false ? "low" : "high";

    const reply = document.createElement("input");
    reply.type = "text";
    reply.maxLength = 100;
    reply.dataset.field = "replyMessage";
    reply.placeholder = "Tin nhắn phản hồi Telegram";
    reply.value = command.replyMessage ?? "";
    reply.setAttribute("aria-label", "Tin nhắn phản hồi");

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "small-button danger-button";
    remove.textContent = "✕";
    remove.title = "Xóa lệnh này";
    remove.addEventListener("click", () => row.remove());

    row.append(name, trigger, pin, duration, polarity, reply, remove);
    container.append(row);
  }
}

function renderApControl(device = {}) {
  latestDeviceState = device;
  const toggle = $("#ap-toggle");
  const state = $("#ap-toggle-state");
  const detail = $("#ap-toggle-detail");
  const enabled = device.provisioningApEnabled === true;
  const active = device.provisioningApActive === true;
  const recovery = device.provisioningApRecoveryMode === true;
  toggle.checked = enabled;
  toggle.disabled = device.connected !== true || (enabled && device.wifiConnected !== true) || toggle.dataset.busy === "true";
  const wifiState = $("#wifi-connection-state");
  const wifiSsid = $("#wifi-connected-ssid");
  const wifiIp = $("#wifi-connected-ip");
  const wifiRssi = $("#wifi-connected-rssi");
  const apState = $("#wifi-ap-state");
  const apDetail = $("#wifi-ap-detail");
  wifiState.textContent = device.connected !== true ? "ESP32 chưa kết nối" : (device.wifiConnected ? "Đã kết nối" : (device.wifiStatus ?? "Chưa kết nối"));
  wifiSsid.textContent = `SSID: ${device.wifiConnected ? (device.wifiSsid || "—") : "—"}`;
  wifiIp.textContent = device.connected ? (device.wifiConnected ? (device.ipAddress ?? "—") : "Chưa có IP LAN") : "—";
  const disconnects = Number(device.wifiDisconnectCount) || 0;
  const reconnectMeta = disconnects > 0 ? ` · rớt ${disconnects} lần (mã ${device.wifiLastDisconnectReason ?? "?"})` : " · ổn định từ lúc boot";
  wifiRssi.textContent = `Tín hiệu: ${device.wifiConnected ? `${device.rssi ?? "—"} dBm` : "—"}${device.connected ? reconnectMeta : ""}`;
  apState.textContent = !device.connected ? "Chưa đọc được" : (active ? "Đang phát" : "Đang tắt");
  apDetail.textContent = !device.connected ? "ESP32 chưa trả trạng thái AP" : `${device.provisioningApRecoveryMode ? "Chế độ khôi phục" : "Bình thường"} · cấu hình ${enabled ? "bật" : "tắt"}`;
  if (device.connected !== true) {
    state.textContent = "Chưa đọc được trạng thái AP";
    detail.textContent = "Kết nối ESP32 để thay đổi AP.";
  } else if (recovery) {
    state.textContent = "AP khôi phục đang phát tạm thời";
    detail.textContent = "Wi‑Fi bị mất; AP sẽ tắt lại khi kết nối Wi‑Fi trở lại.";
  } else {
    state.textContent = `AP ${active ? "đang phát" : "đang tắt"} · ${enabled ? "tự bật sau khi khởi động" : "chỉ bật khi khôi phục"}`;
    detail.textContent = enabled && !device.wifiConnected
      ? "Không thể tắt AP khi ESP32 chưa kết nối Wi‑Fi; thao tác bị khóa để tránh mất quyền truy cập."
      : "AP bật mặc định sau khi flash; khi tắt, AP khôi phục tự bật nếu Wi‑Fi mất 30 giây.";
  }
  renderDdnsControl(device);
}

// --- DDNS provider suffix map ---
const DDNS_PROVIDER_SUFFIXES = {
  duckdns: ".duckdns.org",
  noip:    ".ddns.net",
  dyndns:  ".dyndns.org",
  freedns: ".mooo.com",
  manual:  "",
};

function computeDdnsFullDomain() {
  const provider = ($("#ddns-provider")?.value || "duckdns").trim();
  const domainRaw = ($("#ddns-domain")?.value || "").trim();
  if (!domainRaw) return "";
  if (provider === "manual") return domainRaw;
  const suffix = DDNS_PROVIDER_SUFFIXES[provider] || ".duckdns.org";
  // Avoid double-appending suffix
  return domainRaw.endsWith(suffix) ? domainRaw : domainRaw + suffix;
}

function computeDdnsFullUrl() {
  const full = computeDdnsFullDomain();
  if (!full) return "";
  const port = parseInt($("#ddns-port")?.value || "80", 10);
  return port === 80 ? `http://${full}` : `http://${full}:${port}`;
}

function updateDdnsPreview() {
  const previewEl = $("#ddns-preview-url");
  const domainLabel = $("#ddns-domain-label");
  const tokenLabel  = $("#ddns-token-label");
  const domainInput = $("#ddns-domain");
  const provider = ($("#ddns-provider")?.value || "duckdns").trim();
  const isManual = provider === "manual";

  // Cập nhật label & placeholder theo provider
  if (domainLabel) {
    domainLabel.textContent = isManual ? "Tên miền đầy đủ (vd: myesp.com)" : "Tên miền con (Subdomain)";
  }
  if (domainInput) {
    domainInput.placeholder = isManual ? "vd: esp32downloaderbot.com" : "vd: esp32downloaderbot";
  }
  if (tokenLabel) {
    tokenLabel.textContent = isManual ? "DDNS Token (nếu có)" : "DuckDNS Token";
  }

  if (!previewEl) return;
  const url = computeDdnsFullUrl();
  previewEl.textContent = url || "http://...";
  previewEl.style.color = url ? "#55ae86" : "";
}

function renderDdnsControl(device = {}) {
  const toggle = $("#ddns-toggle");
  const domainInput = $("#ddns-domain");
  const tokenInput = $("#ddns-token");
  const statusLabel = $("#ddns-status-label");
  if (!toggle) return;

  if (device.connected) {
    toggle.disabled = false;
    toggle.checked = Boolean(device.ddnsEnabled);
    if (!domainInput.value || document.activeElement !== domainInput) {
      // Store raw subdomain or full domain from device
      domainInput.value = device.ddnsDomain || "";
    }
    tokenInput.placeholder = device.ddnsTokenConfigured ? "Đã lưu token (nhập mới để đổi)" : "Nhập Token bí mật từ duckdns.org";
    statusLabel.textContent = `Trạng thái: ${device.ddnsLastStatus || (device.ddnsEnabled ? "Đang chờ cập nhật" : "Tắt cập nhật")}`;
    if (device.ddnsLastStatus && device.ddnsLastStatus.includes("OK")) {
      statusLabel.style.color = "#55ae86";
    } else if (device.ddnsLastStatus && device.ddnsLastStatus.includes("Lỗi")) {
      statusLabel.style.color = "#e26d5c";
    } else {
      statusLabel.style.color = "";
    }
  } else {
    toggle.disabled = true;
    statusLabel.textContent = "Trạng thái: ESP32 offline";
    statusLabel.style.color = "";
  }
  updateDdnsPreview();
}

function mergeDashboardJobs(deviceJobs = [], runtimeJobs = []) {
  const merged = new Map();
  for (const job of deviceJobs) {
    const id = String(job.id ?? "");
    if (id) merged.set(id, { ...job });
  }
  for (const runtimeJob of runtimeJobs) {
    const id = String(runtimeJob.id ?? "");
    if (!id) continue;
    const existing = merged.get(id);
    if (!existing) {
      if (["COMPLETED", "FAILED", "CANCELLED", "RECOVERY_REQUIRED"].includes(runtimeJob.status)) {
        merged.set(id, { ...runtimeJob });
      }
      continue;
    }
    const deviceTerminal = ["COMPLETED", "FAILED", "CANCELLED", "RECOVERY_REQUIRED"].includes(existing.status);
    const runtimeTerminal = ["COMPLETED", "FAILED", "CANCELLED", "RECOVERY_REQUIRED"].includes(runtimeJob.status);
    if (runtimeTerminal && !deviceTerminal) Object.assign(existing, runtimeJob);
    else Object.assign(existing, {
      outputPath: runtimeJob.outputPath || existing.outputPath || "",
      outputBytes: Number(runtimeJob.outputBytes) || Number(existing.outputBytes) || 0,
      finishedAt: runtimeJob.finishedAt || existing.finishedAt || "",
    });
    if (!deviceTerminal && runtimeJob.status === "DOWNLOADING") existing.status = "DOWNLOADING";
  }
  return [...merged.values()];
}

function dashboardJobTimestamp(job) {
  const finishedAt = Date.parse(job.finishedAt ?? "");
  if (Number.isFinite(finishedAt) && finishedAt > 0) return finishedAt;
  const terminalAt = Number(job.terminalAt ?? 0);
  if (Number.isFinite(terminalAt) && terminalAt > 0) return terminalAt < 10_000_000_000 ? terminalAt * 1000 : terminalAt;
  const created = String(job.createdAt ?? "");
  if (created.startsWith("unix:")) return (Number(created.slice(5)) || 0) * 1000;
  const createdAt = Date.parse(created);
  return Number.isFinite(createdAt) && createdAt > 0 ? createdAt : Date.now();
}

function updateDashboardStats() {
  const cutoff = Date.now() - 30 * 86400_000;
  const recentTerminal = cachedJobs.filter((job) => dashboardJobTimestamp(job) >= cutoff);
  const completedJobs = recentTerminal.filter((job) => job.status === "COMPLETED");
  const failedJobs = recentTerminal.filter((job) => ["FAILED", "CANCELLED", "RECOVERY_REQUIRED"].includes(job.status));
  const measuredBytes = completedJobs.reduce((sum, job) => sum + (Number(job.outputBytes) || 0), 0);
  const totalCompletedBytes = Math.max(measuredBytes, Number(dashboardHistory.completedBytes) || 0);
  const countCompleted = Math.max(completedJobs.length, Number(dashboardHistory.completedCount) || 0);
  const countFailed = Math.max(failedJobs.length, Number(dashboardHistory.failedCount) || 0);
  $("#completed-url-count").textContent = countCompleted + " URL";
  $("#failed-url-count").textContent = countFailed + " URL";
  $("#completed-download-bytes").textContent = `Dung lượng: ${formatBytes(totalCompletedBytes)} · 30 ngày`;
}

function renderDashboardJobs(jobs = cachedJobs) {
  cachedJobs = Array.isArray(jobs) ? jobs : [];
  const list = $("#dashboard-jobs-list");
  if (!list) return;
  list.replaceChildren();
  const grouped = {
    queued: cachedJobs.filter((job) => ["QUEUED", "DISPATCHED", "DOWNLOADING", "CANCEL_REQUESTED"].includes(job.status)),
    completed: cachedJobs.filter((job) => job.status === "COMPLETED"),
    failed: cachedJobs.filter((job) => ["FAILED", "CANCELLED", "RECOVERY_REQUIRED"].includes(job.status)),
  };
  const completedTotalBytes = grouped.completed.reduce((acc, job) => acc + (Number(job.outputBytes) || 0), 0);
  const queuedTab = $('[data-dashboard-jobs="queued"]');
  if (queuedTab) queuedTab.textContent = `Đang chờ (${grouped.queued.length})`;
  const completedTab = $('[data-dashboard-jobs="completed"]');
  if (completedTab) {
    const sizeStr = completedTotalBytes > 0 ? ` · ${formatBytes(completedTotalBytes)}` : "";
    completedTab.textContent = `Đã hoàn tất (${grouped.completed.length}${sizeStr})`;
  }
  const failedTab = $('[data-dashboard-jobs="failed"]');
  if (failedTab) failedTab.textContent = `Lỗi (${grouped.failed.length})`;
  const items = [...(grouped[dashboardJobFilter] ?? [])].reverse().slice(0, 12);
  for (const job of items) {
    const row = el("article", "dashboard-job");
    row.append(el("div", "dashboard-job-url", job.url));
    const sizeStr = Number(job.outputBytes) > 0 ? ` · ${formatBytes(job.outputBytes)}` : "";
    const meta = job.status === "DOWNLOADING"
      ? `${Number(job.progress ?? 0).toFixed(1)}% · ${job.speed || job.eta || "đang tải"}`
      : `#${job.id} · ${job.source || "—"}${sizeStr}`;
    row.append(el("div", "dashboard-job-meta", meta));
    row.append(el("span", `state ${String(job.status).toLowerCase()}`, job.status));
    const actions = el("div", "dashboard-job-actions");
    if (dashboardJobFilter === "failed" && !job.runtimeOnly) {
      const retry = el("button", "small-button", "Tải lại");
      retry.type = "button";
      retry.addEventListener("click", () => actOnJob(job.id, "retry"));
      actions.append(retry);
    }
    const remove = el("button", "small-button danger-button", "Xóa");
    remove.type = "button";
    remove.disabled = job.status === "DOWNLOADING" || job.status === "VERIFYING";
    remove.title = remove.disabled ? "Đợi tải kết thúc trước khi xóa." : "";
    remove.addEventListener("click", () => deleteProcessedUrl(job, null));
    actions.append(remove);
    row.append(actions);
    list.append(row);
  }
  if (!items.length) list.append(el("div", "empty-cell", dashboardJobFilter === "queued" ? "Không có URL đang chờ hoặc tải." : dashboardJobFilter === "completed" ? "Chưa có URL tải hoàn tất." : "Chưa có URL lỗi."));
  document.querySelectorAll("[data-dashboard-jobs]").forEach((tab) => {
    const selected = tab.dataset.dashboardJobs === dashboardJobFilter;
    tab.classList.toggle("active", selected);
    tab.setAttribute("aria-selected", String(selected));
  });
  updateDashboardStats();
}

function collectGpioCommands() {
  return [...$("#gpio-commands").querySelectorAll(".gpio-command-row")].map((row) => ({
    command: row.querySelector('input[data-field="command"]').value.trim(),
    trigger: row.querySelector('input[data-field="trigger"]').value.trim(),
    pin: Number(row.querySelector('select[data-field="pin"]').value),
    pulseMs: Math.max(0, Number(row.querySelector('input[data-field="pulseMs"]').value || 0)),
    activeHigh: row.querySelector('select[data-field="polarity"]').value === "high",
    replyMessage: row.querySelector('input[data-field="replyMessage"]').value.trim(),
  }));
}

async function loadAccount() {
  const session = await requestJson("/api/auth/session");
  if (!session.authenticated) {
    window.location.replace("/login");
    return;
  }
  $("#account-current").textContent = `Đang đăng nhập: ${session.username}`;
  $("#account-new-username").value = session.username;
  if (session.defaultPassword) {
    globalMessage.textContent = "⚠ Bạn đang dùng mật khẩu mặc định admin/admin. Hãy vào mục “Đổi tài khoản truy cập” để đổi mật khẩu ngay, vì mọi thiết bị trong mạng LAN đều có thể đăng nhập.";
    globalMessage.className = "notice error";
  }
}

function el(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}

function renderJobs(jobs) {
  jobsBody.replaceChildren();
  if (!Array.isArray(jobs)) {
    const row = el("tr");
    const cell = el("td", "empty-cell", "Chưa đọc được hàng đợi. Kiểm tra kết nối ESP32 và API token.");
    cell.colSpan = 4;
    row.append(cell);
    jobsBody.append(row);
    $("#queue-count").textContent = "—";
    $("#queue-meta").textContent = "ESP32 chưa xác thực";
    return;
  }
  if (jobs.length === 0) {
    const row = el("tr");
    const cell = el("td", "empty-cell", "Chưa có job nào. Thêm URL phía trên để bắt đầu.");
    cell.colSpan = 4;
    row.append(cell);
    jobsBody.append(row);
    $("#queue-count").textContent = "0 jobs";
    return;
  }

  const active = jobs.filter((job) => ["QUEUED", "DISPATCHED", "DOWNLOADING", "VERIFYING", "CANCEL_REQUESTED"].includes(job.status)).length;
  $("#queue-count").textContent = `${jobs.length} jobs`;
  $("#queue-meta").textContent = `${active} đang chờ hoặc chạy`;
  for (const job of [...jobs].reverse()) {
    const row = el("tr");
    const content = el("td");
    content.append(el("div", "job-url", job.url));
    content.append(el("div", "job-id", `#${job.id} · ${job.createdAt ?? ""}`));

    const stateCell = el("td");
    const state = el("span", `state ${String(job.status).toLowerCase()}`, job.status);
    stateCell.append(state);
    if (job.error) stateCell.append(el("div", "job-id", job.error));

    const progressCell = el("td");
    const bar = el("div", "progress-line");
    const fill = el("span");
    fill.style.width = `${Math.max(0, Math.min(100, Number(job.progress) || 0))}%`;
    bar.append(fill);
    progressCell.append(bar, el("span", "progress-text", `${Number(job.progress ?? 0).toFixed(1)}% · ${job.speed || job.eta || ""}`));

    const actions = el("td", "row-actions");
    if (["QUEUED", "DISPATCHED", "DOWNLOADING"].includes(job.status)) {
      const cancel = el("button", "small-button", "Dừng");
      cancel.type = "button";
      cancel.addEventListener("click", () => actOnJob(job.id, "cancel"));
      actions.append(cancel);
    } else if (["FAILED", "CANCELLED", "RECOVERY_REQUIRED"].includes(job.status)) {
      const retry = el("button", "small-button", "Thử lại");
      retry.type = "button";
      retry.addEventListener("click", () => actOnJob(job.id, "retry"));
      actions.append(retry);
    }
    if (["COMPLETED", "FAILED", "CANCELLED", "RECOVERY_REQUIRED"].includes(job.status)) {
      const remove = el("button", "small-button", "Xóa");
      remove.type = "button";
      remove.addEventListener("click", () => deleteProcessedUrl(job, null));
      actions.append(remove);
    }
    if (!actions.childElementCount) actions.textContent = "—";
    row.append(content, stateCell, progressCell, actions);
    jobsBody.append(row);
  }
}

function renderTelegramLog(entries, jobs = []) {
  const container = $("#telegram-log-body");
  container.replaceChildren();
  const logEntries = Array.isArray(entries) ? entries : [];
  const jobList = Array.isArray(jobs) ? jobs : [];
  if (logEntries.length === 0 && jobList.length === 0) {
    container.append(el("div", "empty-cell", Array.isArray(entries) ? "Chưa có URL nào được xử lý." : "ESP32 chưa kết nối; đang hiển thị lịch sử Windows worker nếu có."));
    return;
  }

  const usedEntries = new Set();
  const rows = [];
  const timestampFor = (job, entry) => {
    const finished = Date.parse(job?.finishedAt ?? "");
    if (Number.isFinite(finished) && finished > 0) return finished;
    const terminal = Number(job?.terminalAt ?? 0);
    if (Number.isFinite(terminal) && terminal > 0) return terminal < 10000000000 ? terminal * 1000 : terminal;
    const received = Number(entry?.receivedAt ?? 0);
    if (received > 0) return received * 1000;
    const created = String(job?.createdAt ?? "");
    if (created.startsWith("unix:")) return (Number(created.slice(5)) || 0) * 1000;
    return Date.parse(created) || 0;
  };
  for (const job of jobList) {
    const matchingEntry = logEntries.find((entry) => !usedEntries.has(String(entry.id)) && Number(job.telegramUpdateId) > 0
      && Number(entry.telegramUpdateId) === Number(job.telegramUpdateId)
      && String(entry.chatId ?? "") === String(job.telegramChatId ?? ""))
      ?? (job.source === "telegram" ? [...logEntries].reverse().find((entry) => !usedEntries.has(String(entry.id)) && String(entry.url ?? "") === String(job.url ?? "")) : null);
    if (matchingEntry) usedEntries.add(String(matchingEntry.id));
    rows.push({ job, entry: matchingEntry ?? null, time: timestampFor(job, matchingEntry) });
  }
  for (const entry of logEntries) {
    if (!usedEntries.has(String(entry.id))) rows.push({ job: null, entry, time: timestampFor(null, entry) });
  }
  rows.sort((a, b) => b.time - a.time);

  const stateLabels = {
    QUEUED: "ĐANG CHỜ",
    DISPATCHED: "ĐÃ CHUYỂN WORKER",
    DOWNLOADING: "ĐANG TẢI",
    COMPLETED: "HOÀN TẤT",
    FAILED: "LỖI TẢI",
    ERROR1: "ERROR1 · CHỜ THỬ LẠI 1 GIỜ",
    ERROR2: "ERROR2 · ĐÃ THỬ 2 LẦN",
    CANCELLED: "ĐÃ HỦY",
    CANCEL_REQUESTED: "ĐANG HỦY",
    RECOVERY_REQUIRED: "CẦN TẢI LẠI",
    RETRY_WAIT: "CHỜ THỬ LẠI",
    REJECTED: "BỊ TỪ CHỐI",
    LEGACY_SEEN: "ĐÃ NHẬN TRƯỚC ĐÂY",
    SEEN: "ĐÃ NHẬN TRƯỚC ĐÂY",
    LOG_DELETED: "ĐÃ XÓA LOG · ĐÃ NHẬN",
    JOB_REMOVED: "JOB ĐÃ XÓA",
  };
  const activeStates = ["DOWNLOADING", "VERIFYING", "CANCEL_REQUESTED"];
  for (const item of rows) {
    const { job, entry } = item;
    const url = String(job?.url ?? entry?.url ?? "");
    const state = String(job?.status || entry?.urlStatus || (entry?.result === "QUEUED" ? "JOB_REMOVED" : entry?.result) || "SEEN");
    const row = el("article", "telegram-log-entry");
    row.append(el("div", "telegram-log-url", url));
    row.append(el("div", "telegram-log-result " + state.toLowerCase().replaceAll("_", "-"), stateLabels[state] ?? state));
    const date = item.time > 0 ? new Date(item.time).toLocaleString() : "Thời gian chưa đồng bộ";
    const metaParts = [];
    if (job?.id) metaParts.push("#" + job.id);
    if (job?.source || entry) metaParts.push(job?.source || "telegram");
    if (entry?.chatId) metaParts.push("chat " + entry.chatId);
    if (Number(entry?.telegramUpdateId) > 0) metaParts.push("Telegram #" + entry.telegramUpdateId);
    metaParts.push(date);
    row.append(el("div", "telegram-log-meta", metaParts.join(" · ")));
    const outputDetails = [];
    if (job?.outputPath) outputDetails.push("Đã lưu: " + job.outputPath);
    if (Number(job?.outputBytes) > 0) outputDetails.push("Dung lượng: " + formatBytes(job.outputBytes));
    const detail = job?.error || (outputDetails.length ? outputDetails.join(" · ") : (entry?.detail || ""));
    row.append(el("div", "telegram-log-detail", detail));

    const actions = el("div", "telegram-log-actions");
    const addAction = (label, handler, danger = false) => {
      const button = el("button", "small-button" + (danger ? " danger-button" : ""), label);
      button.type = "button";
      button.addEventListener("click", handler);
      actions.append(button);
      return button;
    };
    addAction("Copy", async () => {
      try { await navigator.clipboard.writeText(url); showMessage("Đã copy URL."); }
      catch { showMessage("Trình duyệt không cho phép copy URL.", true); }
    });
    addAction("Force download", () => createJobFromLog(url, true));
    addAction("Restart download", () => restartLogDownload(url, job?.id));
    const remove = addAction("Delete", () => deleteProcessedUrl(job, entry), true);
    remove.disabled = Boolean(job && activeStates.includes(job.status));
    if (remove.disabled) remove.title = "Dừng job trước khi xóa lịch sử.";
    row.append(actions);
    container.append(row);
  }
}

async function deleteProcessedUrl(job, entry) {
  if (job?.status === "DOWNLOADING") {
    showMessage("Dừng hoặc đợi tải xong trước khi xóa URL này.", true);
    return;
  }
  const targetUrl = String(job?.url || entry?.url || "");
  const ignoreMissing = async (operation) => {
    try { await operation(); }
    catch (error) {
      if (error.status === 404 || error.status === 409) return;
      console.warn("Delete warning:", error);
    }
  };
  try {
    const promises = [];
    // 1. Delete matching ESP32 jobs by ID
    if (job?.id && !job.runtimeOnly) {
      promises.push(ignoreMissing(() => requestJson("/api/device/api/v1/jobs/" + encodeURIComponent(job.id) + "/delete", { method: "DELETE" })));
    }
    // Also delete any other matching jobs with the same URL from ESP32
    if (targetUrl) {
      for (const j of cachedJobs) {
        if (j.url === targetUrl && j.id && j.id !== job?.id && !j.runtimeOnly && j.status !== "DOWNLOADING") {
          promises.push(ignoreMissing(() => requestJson("/api/device/api/v1/jobs/" + encodeURIComponent(j.id) + "/delete", { method: "DELETE" })));
        }
      }
    }
    // 2. Delete matching Windows runtime queue entries by ID and by URL
    if (job?.id) {
      const q = targetUrl ? `?url=${encodeURIComponent(targetUrl)}` : "";
      promises.push(ignoreMissing(() => requestJson(`/api/local/runtime/jobs/${encodeURIComponent(job.id)}${q}`, { method: "DELETE" })));
    } else if (targetUrl) {
      promises.push(ignoreMissing(() => requestJson(`/api/local/runtime/jobs/by-url?url=${encodeURIComponent(targetUrl)}`, { method: "DELETE" })));
    }
    // 3. Delete matching Telegram log entries by ID (via retry-enabled endpoint)
    if (entry?.id) {
      promises.push(ignoreMissing(() => requestJson("/api/local/log/delete/" + encodeURIComponent(entry.id), { method: "DELETE" })));
    }
    // Also delete any other Telegram log entries with matching URL
    if (targetUrl && Array.isArray(latestTelegramEntries)) {
      for (const e of latestTelegramEntries) {
        if (e.url === targetUrl && String(e.id) !== String(entry?.id)) {
          promises.push(ignoreMissing(() => requestJson("/api/local/log/delete/" + encodeURIComponent(e.id), { method: "DELETE" })));
        }
      }
    }
    await Promise.allSettled(promises);
    showMessage("Đã xóa URL khỏi lịch sử.");
    await refreshAll();
  } catch (error) {
    showMessage(error.message, true);
    await refreshAll();
  }
}
async function createJobFromLog(url, force) {
  try {
    await requestJson("/api/device/api/v1/jobs", { method: "POST", body: JSON.stringify({ url, source: "local-host", force, createdAt: new Date().toISOString() }) });
    showMessage(force ? "Đã ép URL vào queue; sẽ bỏ qua URL trùng và ghi đè file hiện có." : "Đã thêm URL vào queue.");
    await refreshAll();
  } catch (error) { showMessage(error.message, true); }
}

async function restartLogDownload(url, jobId) {
  const related = jobId ? cachedJobs.find((job) => String(job.id) === String(jobId)) : [...cachedJobs].reverse().find((job) => job.url === url);
  if (related?.runtimeOnly && ["FAILED", "CANCELLED", "RECOVERY_REQUIRED"].includes(related.status)) {
    await createJobFromLog(url, true);
    return;
  }
  if (related && ["FAILED", "CANCELLED", "RECOVERY_REQUIRED"].includes(related.status)) {
    await actOnJob(related.id, "retry");
    return;
  }
  if (related && ["QUEUED", "DISPATCHED", "DOWNLOADING", "CANCEL_REQUESTED"].includes(related.status)) {
    showMessage("URL này đã có job đang chờ hoặc đang tải.");
    return;
  }
  await createJobFromLog(url, true);
}

async function deleteTelegramLog(id) {
  if (!id) { showMessage("Firmware cần cập nhật để xóa log theo ID.", true); return; }
  try {
    // Dùng endpoint có retry để xử lý LittleFS busy
    await requestJson(`/api/local/log/delete/${encodeURIComponent(id)}`, { method: "DELETE" });
    showMessage("Đã xóa log.");
    await refreshAll();
  } catch (error) {
    if (error.status === 404) {
      showMessage("Log này đã được xóa hoặc danh sách vừa cập nhật.");
      await refreshAll();
      return;
    }
    showMessage(`Xóa log thất bại: ${error.message}`, true);
  }
}

async function refreshAll() {
  if (refreshBusy) { refreshQueued = true; return; }
  refreshBusy = true;
  try {
    const status = await requestJson("/api/local/status");
    dashboardHistory = status.dashboardHistory ?? { jobs: [], completedBytes: 0 };
    renderPipeline(status);
    if (status.device.connected) {
      setPill($("#device-pill"), `ESP32: ${status.device.ipAddress ?? "online"}`, "ok");
      $("#device-name").textContent = status.device.chip ?? "ESP32 online";
      $("#device-meta").textContent = `${status.device.boardProfile ?? ""} · ${status.device.flashBytes ? `${(status.device.flashBytes / 1048576).toFixed(0)} MB Flash` : ""}`;
      const wifiState = status.device.wifiConnected
        ? `Đã nối ${status.device.wifiSsid || "Wi‑Fi"} · ${status.device.ipAddress ?? ""} · ${status.device.rssi ?? 0} dBm`
        : `Wi‑Fi: ${status.device.wifiStatus ?? "DISCONNECTED"} · AP ${status.device.provisioningApActive ? "đang phát" : "tắt"}`;
      $("#device-wifi-meta").textContent = wifiState;
      renderApControl(status.device);
      $("#queue-storage").textContent = status.device.storageReady ? "Hàng đợi bền vững trên LittleFS" : `Lỗi lưu trữ: ${status.device.storageError ?? "không rõ"}`;
    } else {
      setPill($("#device-pill"), status.device.authenticated === false ? "ESP32: token lỗi" : (status.device.apiReachable ? "ESP32: API lỗi" : "ESP32: offline"), "bad");
      $("#device-name").textContent = status.device.apiReachable ? "API có phản hồi" : "Chưa kết nối";
      $("#device-meta").textContent = status.device.error ?? "Kiểm tra URL, token và Wi‑Fi";
      $("#device-wifi-meta").textContent = "Wi‑Fi: chưa đọc được";
      renderApControl(status.device);
    }
    $("#api-state").textContent = status.device.authenticated === true
      ? "API + token OK"
      : (status.device.authenticated === false ? "Token bị từ chối" : (status.device.apiReachable ? `API HTTP ${status.device.apiStatus}` : "API không tới được"));
    $("#api-meta").textContent = status.device.authenticated
      ? `ESP32 API đã xác thực · ${status.device.ipAddress ?? ""}`
      : (status.device.authenticated === false
        ? "ESP32 trả lời nhưng token không hợp lệ"
        : (status.device.apiReachable ? (status.device.error ?? "API có phản hồi nhưng trả lỗi") : (status.device.error ?? "Kiểm tra Wi‑Fi, URL và thiết bị")));
    $("#api-token-tail").textContent = status.device.apiTokenLast4
      ? `Device API token ****...${status.device.apiTokenLast4}`
      : "Device API token: chưa lưu";
    $("#settings-db-state").textContent = !status.device.connected
      ? "Chưa đọc được"
      : (status.host.settingsSyncPending ? "Đang chờ đồng bộ" : `Đồng bộ · revision ${status.host.settingsRevision ?? 0}`);
    $("#settings-db-meta").textContent = status.device.connected
      ? `Nguồn cấu hình chung trên ESP32 · ${status.device.storageReady ? "LittleFS sẵn sàng" : "LittleFS lỗi"}`
      : "Kết nối ESP32 để đọc cấu hình dùng chung";

    const cpu = status.device.cpu;
    if (status.device.connected && cpu?.available) {
      $("#cpu-state").textContent = `${cpu.busyPercent}% tải`;
      $("#cpu-meta").textContent = `Core 0: ${cpu.core0BusyPercent}% · Core 1: ${cpu.core1BusyPercent}% · ${cpu.frequencyMhz} MHz`;
      updateMetricBar("#cpu-bar", cpu.busyPercent);
    } else {
      $("#cpu-state").textContent = "—";
      $("#cpu-meta").textContent = status.device.connected ? "Đang lấy mẫu CPU…" : "ESP32 offline";
      updateMetricBar("#cpu-bar", 0);
    }

    const memory = status.device.memory;
    if (status.device.connected && memory?.heapTotalBytes) {
      const used = memory.heapTotalBytes - memory.heapFreeBytes;
      const usedPercent = (used * 100) / memory.heapTotalBytes;
      $("#ram-state").textContent = `${usedPercent.toFixed(0)}% heap dùng`;
      $("#ram-meta").textContent = `${formatBytes(memory.heapFreeBytes)} trống / ${formatBytes(memory.heapTotalBytes)} · PSRAM ${formatBytes(memory.psramFreeBytes)} trống`;
      updateMetricBar("#ram-bar", usedPercent);
    } else {
      $("#ram-state").textContent = "—";
      $("#ram-meta").textContent = status.device.connected ? "Chưa có dữ liệu RAM" : "ESP32 offline";
      updateMetricBar("#ram-bar", 0);
    }

    const storage = status.device.storage;
    if (status.device.connected && storage?.ready && storage.totalBytes) {
      const usedPercent = (storage.usedBytes * 100) / storage.totalBytes;
      $("#storage-state").textContent = `${usedPercent.toFixed(0)}% LittleFS`;
      $("#storage-meta").textContent = `${formatBytes(storage.usedBytes)} dùng · ${formatBytes(storage.freeBytes)} trống / ${formatBytes(storage.totalBytes)}`;
      updateMetricBar("#storage-bar", usedPercent);
    } else {
      $("#storage-state").textContent = status.device.connected ? "LittleFS lỗi" : "—";
      $("#storage-meta").textContent = status.device.storageError ?? (status.device.connected ? "Phân vùng chưa sẵn sàng" : "ESP32 offline");
      updateMetricBar("#storage-bar", 0);
    }
    renderTelegramStatus(status.telegram);

    const toolReady = Boolean(status.downloader.version);
    setPill($("#worker-pill"), `Worker: ${toolReady ? "online" : "thiếu yt-dlp"}`, toolReady ? "ok" : "bad");
    $("#worker-state").textContent = toolReady ? `yt-dlp ${status.downloader.version}` : "Chưa sẵn sàng";
    const pcLink = status.device.runtimeOnline
      ? (status.device.runtimeReady ? "Windows online · runtime sẵn sàng" : "Windows online · downloader chưa sẵn sàng")
      : "ESP32 chưa nhận heartbeat Windows";
    const backlog = Number(status.host.pendingJobs) > 0 ? ` · ${status.host.pendingJobs} job trong queue Windows` : "";
    const adaptive = status.downloader.adaptive;
    const adaptiveText = adaptive?.state === "insufficient_data" ? "Adaptive: chưa đủ dữ liệu · giới hạn an toàn" : (adaptive?.state === "backoff" ? "Adaptive: giảm tải sau lỗi" : (adaptive?.state === "stable" ? "Adaptive: mức ổn định" : "Cấu hình tải tĩnh"));
    $("#worker-meta").textContent = toolReady ? `${status.host.workerId} · ${status.host.activeJobs}/${adaptive?.jobs ?? 1} job · ${pcLink}${backlog} · ${adaptiveText}` : (status.downloader.error ?? "Cấu hình đường dẫn yt-dlp");
    $("#output-state").textContent = !status.output.writable ? "Không ghi được" : (status.output.lowDisk ? "Đĩa gần đầy" : (status.output.limitReached ? "Đạt giới hạn thư mục" : "Sẵn sàng ghi"));
    const storageDetails = [status.output.directory];
    if (status.output.usedBytes !== null && status.output.usedBytes !== undefined) storageDetails.push(`dùng ${formatBytes(status.output.usedBytes)}`);
    if (status.output.freeBytes !== null && status.output.freeBytes !== undefined) storageDetails.push(`trống ${formatBytes(status.output.freeBytes)}`);
    $("#output-meta").textContent = status.output.error || storageDetails.join(" · ");

    if (status.device.connected) {
      const [jobs, inbox] = await Promise.all([
        requestJson("/api/device/api/v1/jobs"),
        requestJson("/api/device/api/v1/telegram/log"),
      ]);
      const deviceJobs = jobs.jobs ?? [];
      latestTelegramEntries = inbox.entries ?? [];
      cachedJobs = mergeDashboardJobs(deviceJobs, dashboardHistory.jobs ?? []);
      renderJobs(deviceJobs);
      renderDashboardJobs(cachedJobs);
      renderTelegramLog(inbox.entries, cachedJobs);
    } else {
      cachedJobs = mergeDashboardJobs([], dashboardHistory.jobs ?? []);
      renderJobs(null);
      renderDashboardJobs(cachedJobs);
      renderTelegramLog(null, cachedJobs);
    }
    await loadScheduledDownloads();
  } catch (error) {
    renderPipeline({ device: { connected: false, error: error.message } });
    setPill($("#device-pill"), "ESP32: lỗi local host", "bad");
    $("#device-name").textContent = "Lỗi kết nối";
    $("#device-meta").textContent = error.message;
  } finally {
    refreshBusy = false;
    if (refreshQueued) {
      refreshQueued = false;
      window.setTimeout(() => { void refreshAll(); }, 0);
    }
  }
}

async function actOnJob(id, action) {
  try {
    const method = action === "delete" ? "DELETE" : "POST";
    await requestJson(`/api/device/api/v1/jobs/${encodeURIComponent(id)}/${action}`, { method, body: method === "POST" ? "{}" : undefined });
    await refreshAll();
  } catch (error) {
    showMessage(error.message, true);
  }
}

$("#settings-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const feedback = $("#settings-feedback");
  setFeedback(feedback, "Đang lưu…");
  try {
    const payload = {
      deviceBaseUrl: $("#device-url").value.trim(),
      runtimeBaseUrl: $("#runtime-url").value.trim(),
      workerName: $("#worker-name").value.trim(),
      deviceToken: $("#device-token").value,
      ffmpegPath: $("#ffmpeg-path").value.trim(),
      denoPath: $("#deno-path").value.trim(),
      telegramBotToken: $("#telegram-token").value,
      telegramEnabled: $("#telegram-enabled").checked,
      clearTelegramToken: $("#clear-telegram-token").checked,
      ytDlpPath: $("#yt-dlp-path").value.trim() || "yt-dlp",
      outputDirectory: $("#output-directory").value.trim(),
      urlRetentionDays: Number($("#url-retention-days").value),
      gpioCommands: collectGpioCommands(),
    };
    const chatId = $("#telegram-chat-id").value.trim();
    if (chatId || $("#clear-telegram-chat-id").checked) payload.telegramAllowedChatId = chatId;
    const saved = await requestJson("/api/local/config", { method: "PUT", body: JSON.stringify(payload) });
    $("#device-token").value = "";
    $("#telegram-token").value = "";
    $("#clear-telegram-token").checked = false;
    $("#telegram-chat-id").value = "";
    $("#clear-telegram-chat-id").checked = false;
    $("#device-token").placeholder = saved.deviceTokenConfigured ? `Đã lưu ****...${saved.deviceTokenLast4 || "????"} (nhập token mới để thay đổi)` : "Dán token từ Serial Monitor";
    $("#telegram-token").placeholder = saved.telegramTokenConfigured ? `Đã lưu ****...${saved.telegramTokenLast4 || "????"} (nhập token mới để thay đổi)` : "Token từ BotFather (tùy chọn)";
    $("#telegram-chat-id").placeholder = saved.telegramChatIdConfigured ? `Đã lưu ****...${saved.telegramAllowedChatIdLast4 || "????"} (nhập ID mới để thay đổi)` : "Dán chat ID dạng số, ví dụ 123456789";
    $("#telegram-chat-id-help").textContent = saved.telegramChatIdConfigured
      ? "Chat ID đã lưu; ô trống giữ nguyên cấu hình. Nhập ID mới để thay đổi hoặc bật tùy chọn xóa."
      : "Gửi /start trong chat Telegram với bot để bot trả lời chat ID dạng số. Không nhập chữ /start; có thể nhập nhiều ID, phân cách bằng dấu phẩy.";
    setFeedback(feedback, saved.settingsSyncPending
      ? "Đã lưu trên host; đang chờ ESP32 để đồng bộ cơ sở dữ liệu cấu hình. Host sẽ thử lại."
      : "Đã lưu và đồng bộ cài đặt dùng chung vào cơ sở dữ liệu ESP32.");
    await refreshAll();
  } catch (error) {
    setFeedback(feedback, error.message, true);
  }
});

$("#download-settings-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const feedback = $("#download-settings-feedback");
  setFeedback(feedback, "Đang lưu cài đặt tải xuống…");
  const downloadSettings = {
    videoQuality: $("#download-quality").value,
    thumbnailFormat: $("#thumbnail-format").value,
    outputStructure: $("#output-structure").value,
    filenameTemplate: $("#filename-template").value.trim(),
    mergeAudioVideo: $("#merge-audio-video").checked,
    mergeOutputFormat: $("#merge-format").value,
    allowImageDownloads: $("#allow-image-downloads").checked,
    maxConcurrentJobs: Number($("#max-concurrent-jobs").value),
    fragmentConcurrency: Number($("#fragment-concurrency").value),
    bandwidthLimit: $("#bandwidth-limit").value.trim(),
    retryLimit: Number($("#retry-limit").value),
    retryBackoffSeconds: Number($("#retry-backoff").value),
    socketTimeoutSeconds: Number($("#socket-timeout").value),
    duplicateUrlPolicy: $("#duplicate-url-policy").value,
    existingFilePolicy: $("#existing-file-policy").value,
    jobTimeoutMinutes: Number($("#job-timeout").value),
    maxOutputDirectoryGb: Number($("#max-output-gb").value),
    minimumFreeDiskGb: Number($("#minimum-free-gb").value),
    runtimeHistoryLimit: Number($("#runtime-history-limit").value),
    verifyOutput: $("#verify-output").checked,
    adaptiveMode: $("#adaptive-mode").checked,
  };
  try {
    const result = await requestJson("/api/local/download-settings", { method: "PUT", body: JSON.stringify({ downloadSettings }) });
    setFeedback(feedback, result.settingsSyncPending ? "Đã lưu trên host; đang chờ ESP32 để đồng bộ." : "Đã lưu vào cơ sở dữ liệu ESP32. Các job mới sẽ dùng cài đặt này.");
  } catch (error) {
    setFeedback(feedback, error.message, true);
  }
});

$("#security-settings-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const feedback = $("#security-settings-feedback");
  setFeedback(feedback, "Đang lưu cài đặt bảo mật…");
  try {
    const result = await requestJson("/api/local/security-settings", {
      method: "PUT",
      body: JSON.stringify({
        sessionTimeoutMinutes: Number($("#session-timeout").value),
        maxLoginAttempts: Number($("#login-max-attempts").value),
        loginWindowMinutes: Number($("#login-window").value),
      }),
    });
    setFeedback(feedback, "Đã lưu cài đặt bảo mật.");
    $("#session-timeout").value = result.securitySettings.sessionTimeoutMinutes;
  } catch (error) {
    setFeedback(feedback, error.message, true);
  }
});

$("#system-backup-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const button = form.querySelector('button[type="submit"]');
  const feedback = $("#system-backup-feedback");
  button.disabled = true;
  setFeedback(feedback, "Đang lấy cấu hình host và ESP32…");
  try {
    const backup = await requestJson("/api/local/config/backup", {
      method: "POST",
      body: "{}",
    });
    const blob = new Blob([JSON.stringify(backup, null, 2)], { type: "application/vnd.esp-download.system-backup+json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `Esp32-system-${new Date().toISOString().replace(/[:.]/g, "-")}.espbackup`;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    form.reset();
    setFeedback(feedback, "Đã tạo file backup không mã hóa. Hãy lưu file ở nơi an toàn.");
  } catch (error) {
    setFeedback(feedback, error.message, true);
  } finally {
    button.disabled = false;
  }
});

// --- Backup file selection UI ---
(function initBackupFilePicker() {
  const fileInput = $("#system-restore-file");
  const control = document.querySelector(".file-picker-control");
  const label = $("#file-picker-label");
  if (!fileInput || !control || !label) return;

  fileInput.addEventListener("change", () => {
    const file = fileInput.files[0];
    if (file) {
      const sizeKb = (file.size / 1024).toFixed(1);
      label.textContent = `${file.name} (${sizeKb} KB)`;
      control.classList.add("has-file");
    } else {
      label.textContent = "Chưa có tệp nào";
      control.classList.remove("has-file");
    }
  });
})();

$("#system-restore-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const button = form.querySelector('button[type="submit"]');
  const feedback = $("#system-restore-feedback");
  const file = $("#system-restore-file").files[0];
  if (!file) {
    setFeedback(feedback, "Chọn file backup trước khi khôi phục.", true);
    return;
  }
  if (file.size === 0 || file.size > 1024 * 1024) {
    setFeedback(feedback, "File backup rỗng hoặc vượt quá 1 MB.", true);
    return;
  }
  if (!window.confirm("File backup không được mã hóa. Chỉ tiếp tục nếu bạn tin cậy file này. Khôi phục sẽ thay cấu hình host và ESP32, gồm tài khoản, Wi-Fi và token; ESP32 sẽ khởi động lại. Tiếp tục?")) return;
  button.disabled = true;
  setFeedback(feedback, "Đang xác thực file backup…");
  try {
    const backup = JSON.parse(await file.text());
    const result = await requestJson("/api/local/config/restore", {
      method: "POST",
      body: JSON.stringify(backup),
    });
    form.reset();
    const control = document.querySelector(".file-picker-control");
    const label = $("#file-picker-label");
    if (control) control.classList.remove("has-file");
    if (label) label.textContent = "Chưa có tệp nào";
    setFeedback(feedback, result.deviceRestarting
      ? "Đã khôi phục cấu hình. ESP32 đang khởi động lại."
      : "Đã khôi phục cấu hình host và ESP32.");
  } catch (error) {
    setFeedback(feedback, error.message, true);
  } finally {
    button.disabled = false;
  }
});

$("#add-gpio-command").addEventListener("click", () => {
  const existing = collectGpioCommands();
  if (existing.length >= 8) {
    showMessage("Có thể cấu hình tối đa 8 lệnh GPIO.", true);
    return;
  }
  existing.push({ command: "", trigger: "", pin: allowedGpioPins[0], pulseMs: 500, activeHigh: true, replyMessage: "" });
  renderGpioCommands(existing);
});

$("#account-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const feedback = $("#account-feedback");
  const newPassword = $("#account-new-password").value;
  const confirmPassword = $("#account-confirm-password").value;
  if (newPassword !== confirmPassword) {
    setFeedback(feedback, "Mật khẩu mới nhập lại không khớp.", true);
    return;
  }
  setFeedback(feedback, "Đang cập nhật tài khoản…");
  try {
    const result = await requestJson("/api/local/account", {
      method: "PUT",
      body: JSON.stringify({
        currentPassword: $("#account-current-password").value,
        username: $("#account-new-username").value.trim(),
        newPassword,
      }),
    });
    $("#account-current-password").value = "";
    $("#account-new-password").value = "";
    $("#account-confirm-password").value = "";
    $("#account-current").textContent = `Đang đăng nhập: ${result.username}`;
    setFeedback(feedback, "Đã lưu tài khoản và cập nhật phiên đăng nhập.");
  } catch (error) {
    setFeedback(feedback, error.message, true);
  }
});

$("#logout-button").addEventListener("click", async () => {
  try {
    await requestJson("/api/auth/logout", { method: "POST", body: "{}" });
  } finally {
    window.location.replace("/login");
  }
});

$("#telegram-check").addEventListener("click", async () => {
  const feedback = $("#telegram-feedback");
  setFeedback(feedback, "Đang kiểm tra HTTPS tới Telegram API…");
  try {
    const response = await requestJson("/api/local/telegram/check", {
      method: "POST",
      body: JSON.stringify({ token: $("#telegram-token").value.trim() }),
    });
    renderTelegramStatus(response.telegram);
    setFeedback(feedback, response.telegram.message, !response.telegram.tokenValid);
  } catch (error) {
    setFeedback(feedback, error.message, true);
  }
});

$("#telegram-test").addEventListener("click", async () => {
  const feedback = $("#telegram-feedback");
  setFeedback(feedback, "Đang gửi tin nhắn thử tới Telegram…");
  try {
    const response = await requestJson("/api/local/telegram/test", {
      method: "POST",
      body: JSON.stringify({ chatId: $("#telegram-chat-id").value.trim() }),
    });
    setFeedback(feedback, `Đã gửi tin nhắn thử tới chat ${response.chatId}. Telegram đã chấp nhận tin nhắn.`);
  } catch (error) {
    setFeedback(feedback, error.message, true);
  }
});

$("#scan-wifi-button").addEventListener("click", async () => {
  const button = $("#scan-wifi-button");
  const feedback = $("#scan-feedback");
  button.disabled = true;
  setFeedback(feedback, "Đang quét các mạng Wi‑Fi quanh ESP32…");
  try {
    let result = await requestJson("/api/device/api/v1/wifi/scan");
    const scanDeadline = Date.now() + 25000;
    while (result.scanning === true && Date.now() < scanDeadline) {
      setFeedback(feedback, "Đang quét nền · kết nối Wi‑Fi ESP32 vẫn được duy trì…");
      await new Promise((resolve) => window.setTimeout(resolve, 500));
      result = await requestJson("/api/device/api/v1/wifi/scan");
    }
    if (result.scanning === true) throw new Error("Quét Wi‑Fi quá thời gian. Kết nối ESP32 vẫn được giữ; hãy thử quét lại.");
    const list = $("#wifi-networks");
    list.replaceChildren();
    for (const network of result.networks ?? []) {
      const option = document.createElement("button");
      option.type = "button";
      option.className = "wifi-network-option";
      const name = document.createElement("strong");
      name.textContent = network.ssid;
      const detail = document.createElement("span");
      detail.textContent = `${network.rssi} dBm · kênh ${network.channel} · ${network.secure ? "có mật khẩu" : "mở"}${network.connected ? " · đang dùng" : ""}`;
      const signal = el("span", "wifi-signal", `${network.rssi} dBm`);
      option.append(name, signal, detail);
      option.addEventListener("click", () => {
        $("#wifi-ssid").value = network.ssid;
        list.querySelectorAll(".wifi-network-option").forEach((item) => item.classList.remove("selected"));
        option.classList.add("selected");
      });
      list.append(option);
    }
    const connected = result.networks?.find((network) => network.connected);
    if (connected) {
      $("#wifi-ssid").value = connected.ssid;
      list.querySelectorAll(".wifi-network-option").forEach((item) => {
        if (item.querySelector("strong")?.textContent === connected.ssid) item.classList.add("selected");
      });
      $("#wifi-connection-state").textContent = "Đã kết nối";
      $("#wifi-connected-ssid").textContent = `SSID: ${connected.ssid}`;
    }
    setFeedback(feedback, `${result.count} mạng tìm thấy · ${result.scanDurationMs} ms`);
  } catch (error) {
    setFeedback(feedback, error.message, true);
  } finally {
    button.disabled = false;
  }
});

$("#wifi-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const feedback = $("#wifi-feedback");
  setFeedback(feedback, "Đang gửi tới ESP32…");
  try {
    const response = await requestJson("/api/device/api/v1/settings/network", {
      method: "POST",
      body: JSON.stringify({ ssid: $("#wifi-ssid").value.trim(), password: $("#wifi-password").value }),
    });
    $("#wifi-password").value = "";
    setFeedback(feedback, response.message ?? "Đã lưu cấu hình Wi‑Fi.");
    window.setTimeout(refreshAll, 1000);
  } catch (error) {
    setFeedback(feedback, error.message, true);
  }
});

$("#ap-toggle").addEventListener("change", async () => {
  const toggle = $("#ap-toggle");
  const nextEnabled = toggle.checked;
  toggle.dataset.busy = "true";
  toggle.disabled = true;
  setFeedback($("#wifi-feedback"), nextEnabled ? "Đang bật AP thiết lập…" : "Đang tắt AP thiết lập…");
  try {
    const result = await requestJson("/api/device/api/v1/settings/ap", {
      method: "POST",
      body: JSON.stringify({ enabled: nextEnabled }),
    });
    if (result.provisioningApEnabled !== nextEnabled) throw new Error("ESP32 chưa xác nhận trạng thái AP mới.");
    setFeedback($("#wifi-feedback"), nextEnabled ? "ESP32 xác nhận AP đang bật." : "ESP32 xác nhận AP đã tắt; khôi phục tự bật nếu Wi‑Fi mất 30 giây.");
    await refreshAll();
  } catch (error) {
    toggle.checked = !nextEnabled;
    setFeedback($("#wifi-feedback"), error.message, true);
    await refreshAll();
  } finally {
    toggle.dataset.busy = "false";
    toggle.disabled = latestDeviceState.connected !== true || (toggle.checked && latestDeviceState.wifiConnected !== true);
  }
});

// --- DDNS provider/domain event listeners ---
(function initDdnsListeners() {
  const providerSel = $("#ddns-provider");
  const domainInput = $("#ddns-domain");
  const portInput   = $("#ddns-port");
  if (providerSel) providerSel.addEventListener("change", updateDdnsPreview);
  if (domainInput) domainInput.addEventListener("input", updateDdnsPreview);
  if (portInput)   portInput.addEventListener("input", updateDdnsPreview);

  const openBtn = $("#open-ddns-browser-btn");
  if (openBtn) {
    openBtn.addEventListener("click", () => {
      const url = computeDdnsFullUrl();
      if (!url) {
        alert("Chưa nhập tên miền. Vui lòng nhập subdomain hoặc domain đầy đủ trước.");
        return;
      }
      window.open(url, "_blank");
    });
  }
})();

$("#ddns-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const feedback = $("#ddns-feedback");
  const saveBtn = $("#save-ddns-button");
  saveBtn.disabled = true;
  setFeedback(feedback, "Đang gửi cấu hình Dynamic DNS tới ESP32…");
  try {
    // Compute full domain for ESP32 (strips suffix for DuckDNS — ESP32 firmware stores subdomain only)
    const provider  = ($("#ddns-provider")?.value || "duckdns").trim();
    const domainRaw = ($("#ddns-domain")?.value || "").trim();
    const suffix    = DDNS_PROVIDER_SUFFIXES[provider] || "";
    // For non-manual providers: strip suffix if user typed it accidentally
    const domainForEsp32 = (provider !== "manual" && domainRaw.endsWith(suffix))
      ? domainRaw.slice(0, -suffix.length)
      : domainRaw;
    const payload = {
      enabled: $("#ddns-toggle").checked,
      domain:  domainForEsp32,
      token:   ($("#ddns-token")?.value || "").trim(),
    };
    await requestJson("/api/device/api/v1/settings/ddns", {
      method: "POST",
      body: JSON.stringify(payload),
    });
    // Also persist provider + externalPort to local config
    await requestJson("/api/local/config", {
      method: "PUT",
      body: JSON.stringify({
        ddnsProvider: provider,
        ddnsExternalPort: parseInt($("#ddns-port")?.value || "80", 10),
        ddnsDomain: domainForEsp32,
        ddnsEnabled: $("#ddns-toggle").checked,
      }),
    }).catch(() => {}); // best-effort
    $("#ddns-token").value = "";
    setFeedback(feedback, "Đã lưu thành công cấu hình Dynamic DNS!");
    window.setTimeout(refreshAll, 1000);
  } catch (error) {
    setFeedback(feedback, error.message, true);
  } finally {
    saveBtn.disabled = false;
  }
});

$("#ddns-toggle").addEventListener("change", async () => {
  const toggle = $("#ddns-toggle");
  const feedback = $("#ddns-feedback");
  setFeedback(feedback, "Đang cập nhật trạng thái Dynamic DNS…");
  try {
    const provider  = ($("#ddns-provider")?.value || "duckdns").trim();
    const domainRaw = ($("#ddns-domain")?.value || "").trim();
    const suffix    = DDNS_PROVIDER_SUFFIXES[provider] || "";
    const domainForEsp32 = (provider !== "manual" && domainRaw.endsWith(suffix))
      ? domainRaw.slice(0, -suffix.length)
      : domainRaw;
    await requestJson("/api/device/api/v1/settings/ddns", {
      method: "POST",
      body: JSON.stringify({
        enabled: toggle.checked,
        domain: domainForEsp32,
      }),
    });
    setFeedback(feedback, toggle.checked ? "Đã bật cập nhật Dynamic DNS." : "Đã tắt cập nhật Dynamic DNS.");
    window.setTimeout(refreshAll, 1000);
  } catch (error) {
    toggle.checked = !toggle.checked;
    setFeedback(feedback, error.message, true);
  }
});

document.querySelectorAll("[data-dashboard-jobs]").forEach((tab) => tab.addEventListener("click", () => {
  dashboardJobFilter = tab.dataset.dashboardJobs;
  renderDashboardJobs();
}));

$("#add-job-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await requestJson("/api/device/api/v1/jobs", {
      method: "POST",
      body: JSON.stringify({ url: $("#job-url").value.trim(), source: "localhost", createdAt: new Date().toISOString() }),
    });
    $("#job-url").value = "";
    showMessage("Job đã được lưu trên ESP32.");
    await refreshAll();
  } catch (error) {
    showMessage(error.message, true);
  }
});

$("#device-pairing-show").addEventListener("click", async (event) => {
  const button = event.currentTarget;
  const feedback = $("#device-pairing-feedback");
  clearDevicePairingQr();
  button.disabled = true;
  setFeedback(feedback, "Đang tạo QR ghép nối…");
  try {
    const response = await fetch("/api/local/device-pairing-qr.png", { cache: "no-store" });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(payload.error?.message ?? payload.error ?? `HTTP ${response.status}`);
    }
    const blob = await response.blob();
    if (devicePairingQrUrl) URL.revokeObjectURL(devicePairingQrUrl);
    devicePairingQrUrl = URL.createObjectURL(blob);
    const image = $("#device-pairing-image");
    image.src = devicePairingQrUrl;
    image.classList.remove("hidden");
    const download = $("#device-pairing-download");
    download.href = devicePairingQrUrl;
    download.classList.remove("hidden");
    setFeedback(feedback, "Đã tạo QR. Quét trực tiếp trong APK hoặc tải PNG để nhập sau.");
  } catch (error) {
    setFeedback(feedback, error.message, true);
  } finally {
    button.disabled = false;
  }
});

function renderScheduledDownloads(schedules = []) {
  const body = $("#schedules-body");
  if (!body) return;
  body.replaceChildren();
  if (!Array.isArray(schedules) || schedules.length === 0) {
    const row = el("tr");
    const cell = el("td", "empty-cell", "Chưa có lịch tải định kỳ nào. Điền form phía trên để thêm kênh/playlist.");
    cell.colSpan = 5;
    row.append(cell);
    body.append(row);
    return;
  }
  const recurrenceLabels = {
    daily: "Hàng ngày",
    weekly: "Hàng tuần",
    monthly: "Hàng tháng",
  };
  for (const item of schedules) {
    const row = el("tr");
    const urlCell = el("td");
    urlCell.append(el("div", "job-url", item.url));
    if (item.outputDirectory) urlCell.append(el("div", "job-id", `Lưu: ${item.outputDirectory}`));

    const scheduleCell = el("td");
    const recText = recurrenceLabels[item.recurrence] || item.recurrence;
    scheduleCell.append(el("div", "", `${recText} lúc ${item.time}`));

    const nextCell = el("td");
    const nextDate = item.nextRunAt ? new Date(item.nextRunAt).toLocaleString() : (item.enabled ? "Đang tính…" : "Đang tắt");
    nextCell.append(el("div", "subtle", nextDate));

    const statusCell = el("td");
    const statusPill = el("span", `state ${item.enabled ? "queued" : "cancelled"}`, item.enabled ? (item.lastRunStatus || "SẴN SÀNG") : "ĐÃ TẮT");
    statusCell.append(statusPill);

    const actionCell = el("td", "row-actions");
    const runBtn = el("button", "small-button", "Chạy ngay");
    runBtn.type = "button";
    runBtn.addEventListener("click", async () => {
      runBtn.disabled = true;
      try {
        await requestJson(`/api/local/scheduled-downloads/${encodeURIComponent(item.id)}/run`, { method: "POST" });
        showMessage("Đã kích hoạt tải kênh theo lịch.");
        await refreshAll();
      } catch (e) { showMessage(e.message, true); }
      finally { runBtn.disabled = false; }
    });

    const toggleBtn = el("button", "small-button", item.enabled ? "Tắt" : "Bật");
    toggleBtn.type = "button";
    toggleBtn.addEventListener("click", async () => {
      try {
        await requestJson(`/api/local/scheduled-downloads/${encodeURIComponent(item.id)}/toggle`, { method: "POST" });
        await refreshAll();
      } catch (e) { showMessage(e.message, true); }
    });

    const delBtn = el("button", "small-button danger-button", "Xóa");
    delBtn.type = "button";
    delBtn.addEventListener("click", async () => {
      if (!confirm("Bạn có chắc muốn xóa lịch tải này?")) return;
      try {
        await requestJson(`/api/local/scheduled-downloads/${encodeURIComponent(item.id)}`, { method: "DELETE" });
        showMessage("Đã xóa lịch tải.");
        await refreshAll();
      } catch (e) { showMessage(e.message, true); }
    });

    actionCell.append(runBtn, toggleBtn, delBtn);
    row.append(urlCell, scheduleCell, nextCell, statusCell, actionCell);
    body.append(row);
  }
}

async function loadScheduledDownloads() {
  try {
    const data = await requestJson("/api/local/scheduled-downloads");
    renderScheduledDownloads(data.schedules || []);
  } catch {}
}

const scheduleForm = $("#add-schedule-form");
if (scheduleForm) {
  scheduleForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const url = $("#schedule-url").value.trim();
    const recurrence = $("#schedule-recurrence").value;
    const time = $("#schedule-time").value;
    try {
      await requestJson("/api/local/scheduled-downloads", {
        method: "POST",
        body: JSON.stringify({ url, recurrence, time }),
      });
      $("#schedule-url").value = "";
      showMessage("Đã lưu lịch tải định kỳ mới.");
      await refreshAll();
    } catch (error) {
      showMessage(error.message, true);
    }
  });
}

const clearAllLogsBtn = $("#clear-all-telegram-logs");
if (clearAllLogsBtn) {
  clearAllLogsBtn.addEventListener("click", async () => {
    if (!confirm("Bạn có chắc chắn muốn xóa toàn bộ lịch sử URL đã xử lý và các job đã hoàn tất?")) return;
    clearAllLogsBtn.disabled = true;
    try {
      const deletePromises = [
        requestJson("/api/local/log/delete-all", { method: "DELETE" }).catch(() => {}),
        requestJson("/api/device/api/v1/jobs", { method: "DELETE" }).catch(() => {}),
        requestJson("/api/local/runtime/jobs", { method: "DELETE" }).catch(() => {}),
      ];
      for (const job of cachedJobs) {
        if (job.id && job.status !== "DOWNLOADING") {
          deletePromises.push(requestJson(`/api/local/runtime/jobs/${encodeURIComponent(job.id)}`, { method: "DELETE" }).catch(() => {}));
          deletePromises.push(requestJson(`/api/device/api/v1/jobs/${encodeURIComponent(job.id)}/delete`, { method: "DELETE" }).catch(() => {}));
        }
      }
      if (Array.isArray(latestTelegramEntries)) {
        for (const entry of latestTelegramEntries) {
          if (entry.id) {
            deletePromises.push(requestJson(`/api/local/log/delete/${encodeURIComponent(entry.id)}`, { method: "DELETE" }).catch(() => {}));
          }
        }
      }
      await Promise.allSettled(deletePromises);
      showMessage("Đã xóa sạch toàn bộ lịch sử URL và hàng đợi hoàn tất.");
      await refreshAll();
    } catch (e) {
      showMessage(e.message, true);
    } finally {
      clearAllLogsBtn.disabled = false;
    }
  });
}

const rebootBtn = $("#reboot-button");
if (rebootBtn) {
  rebootBtn.addEventListener("click", async () => {
    if (!confirm("Khởi động lại ESP32 ngay bây giờ? Thiết bị sẽ ngắt kết nối và kết nối lại trong vài giây.")) return;
    rebootBtn.disabled = true;
    showMessage("Đang gửi lệnh khởi động lại tới ESP32...");
    try {
      const res = await requestJson("/api/local/device/reboot", { method: "POST" });
      showMessage(res.message || "ESP32 đang khởi động lại...", false);
    } catch (err) {
      showMessage(`Không thể khởi động lại ESP32: ${err.message}`, true);
    } finally {
      setTimeout(() => { if (rebootBtn) rebootBtn.disabled = false; }, 8000);
    }
  });
}

async function loadSystemCheck() {
  const grid = $("#system-tools-grid");
  if (!grid) return;
  grid.innerHTML = '<div class="empty-cell">Đang quét môi trường hệ thống…</div>';
  try {
    const res = await requestJson("/api/local/system/check");
    renderSystemTools(res.tools || []);
  } catch (e) {
    grid.innerHTML = `<div class="notice error">Không kiểm tra được hệ thống: ${e.message}</div>`;
  }
}

function renderSystemTools(tools = []) {
  const grid = $("#system-tools-grid");
  if (!grid) return;
  grid.replaceChildren();
  if (tools.length === 0) {
    grid.innerHTML = '<div class="empty-cell">Không có thông tin công cụ.</div>';
    return;
  }
  for (const tool of tools) {
    const card = document.createElement("div");
    card.className = "tool-card";
    const header = document.createElement("div");
    header.className = "tool-card-header";
    const name = document.createElement("span");
    name.className = "tool-name";
    name.textContent = tool.name;
    const badge = document.createElement("span");
    badge.className = `tool-badge ${tool.installed ? "installed" : "missing"}`;
    badge.textContent = tool.installed ? "Sẵn sàng" : "Chưa cài";
    header.append(name, badge);

    const version = document.createElement("div");
    version.className = "tool-version";
    version.textContent = tool.installed ? (tool.version || "Đã cài đặt") : (tool.required ? "Bắt buộc thiếu" : "Tùy chọn chưa cài");

    const path = document.createElement("div");
    path.className = "tool-path";
    path.textContent = tool.installed ? (tool.path || "Trên hệ thống PATH") : tool.description;
    path.title = path.textContent;

    card.append(header, version, path);
    grid.append(card);
  }
}

async function installMissingPackages() {
  const btn = $("#btn-install-missing");
  const logBox = $("#system-install-progress");
  const logPre = $("#system-install-log");
  if (btn) btn.disabled = true;
  if (logBox) logBox.classList.remove("hidden");
  if (logPre) logPre.textContent = "Bắt đầu tải và cấu hình các gói thiếu qua PowerShell...\n";

  try {
    const res = await fetch("/api/local/system/install", { method: "POST" });
    if (!res.body) {
      const data = await res.json();
      if (logPre) logPre.textContent += data.output || data.message || "Hoàn tất.";
    } else {
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        const chunk = decoder.decode(value, { stream: true });
        if (logPre) {
          logPre.textContent += chunk;
          logPre.scrollTop = logPre.scrollHeight;
        }
      }
    }
    await loadSystemCheck();
  } catch (err) {
    if (logPre) logPre.textContent += `\nLỗi cài đặt: ${err.message}\n`;
  } finally {
    if (btn) btn.disabled = false;
  }
}

const checkSysBtn = $("#btn-check-system");
if (checkSysBtn) checkSysBtn.addEventListener("click", () => void loadSystemCheck());

const installMissingBtn = $("#btn-install-missing");
if (installMissingBtn) installMissingBtn.addEventListener("click", () => void installMissingPackages());

$("#output-directory").addEventListener("input", (event) => updatePipelineStorageLabel(event.currentTarget.value));
$("#refresh-button").addEventListener("click", () => void refreshAll());
await loadConfig().catch((error) => showMessage(error.message, true));
await loadAccount().catch((error) => showMessage(error.message, true));
await refreshAll();
window.setInterval(() => {
  if (!document.hidden) void refreshAll();
}, 10000);
