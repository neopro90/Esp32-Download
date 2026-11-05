import http from "node:http";
import os from "node:os";
import path from "node:path";
import fs from "node:fs/promises";
import fsSync from "node:fs";
import { spawn, execFile, execSync } from "node:child_process";
import { constants as fsConstants } from "node:fs";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import QRCode from "qrcode";

const execFileAsync = promisify(execFile);
const here = path.dirname(fileURLToPath(import.meta.url));
const publicRoot = path.resolve(here, "public");
const firmwareBuildRoot = path.resolve(here, "..", "firmware-cyd", "releases");
const androidApkRoot = path.resolve(here, "..", "mobile", "android", "app", "build", "outputs", "apk");
const dataRoot = path.resolve(here, "data");
const configPath = path.join(dataRoot, "local-config.json");
const runtimeQueuePath = path.join(dataRoot, "runtime-queue.json");
const scheduledDownloadsPath = path.join(dataRoot, "scheduled-downloads.json");
const port = Number.parseInt(process.env.ESP_DOWNLOAD_PORT ?? "8787", 10);
// Số video mới nhất tối đa khi nhận link profile/kênh/playlist (0 = không giới hạn).
const PROFILE_VIDEO_LIMIT = Math.max(0, Math.min(200, Number.parseInt(process.env.ESP_DOWNLOAD_PROFILE_LIMIT ?? "10", 10) || 0));
const listenAddress = "0.0.0.0";
const uiAddress = "127.0.0.1";
let workerId = `${os.hostname()}-windows-runtime`;
const activeJobs = new Map();
const sessions = new Map();
const loginFailures = new Map();
const SESSION_COOKIE = "espdownload_session";
let runtimeQueue = [];
let runtimeQueueWrite = Promise.resolve();
let scheduledDownloads = [];
let scheduledDownloadsWrite = Promise.resolve();
let scheduledDownloadsBusy = false;
let pollBusy = false;
let integrationsSyncPending = false;
let hostSettingsSyncPending = false;
let deviceSettingsRevision = 0;
let lastHostSettingsSyncAt = 0;
let lastIntegrationSyncAt = 0;
let telegramTokenSyncRequested = false;
let deviceTelegramTokenConfigured = false;
let toolVersionCache = { checkedAt: 0, path: null, version: null, error: null };
const toolVersionChecks = new Map();
let telegramStatusCache = { checkedAt: 0, value: null };
let telegramCheckInFlight = null;
let telegramPollerRunning = false;
let telegramPollController = null;
let telegramWebhookCheckedAt = 0;
let telegramDeviceCheck = { checkedAt: 0, deviceManaged: false, error: "" };
let telegramDeviceCheckInFlight = null;
let pcCheckInFlight = null;
let recentDownloadOutcomes = [];
let outputStorageCache = { checkedAt: 0, value: null };
const telegramRuntime = {
  state: "not_configured",
  botUsername: "",
  lastUpdateAt: null,
  lastError: "",
  webhookConfigured: false,
};

const defaultConfig = {
  schemaVersion: 2,
  deviceBaseUrl: "http://192.168.4.1",
  runtimeBaseUrl: "",
  deviceToken: "",
  telegramBotToken: "",
  telegramEnabled: true,
  telegramAllowedChatId: "",
  telegramUpdateOffset: 0,
  ytDlpPath: "yt-dlp",
  ffmpegPath: "",
  denoPath: "",
  outputDirectory: path.join(os.homedir(), "Downloads", "ESP-Downloads"),
  urlRetentionDays: 30,
  ddnsEnabled: false,
  ddnsDomain: "",
  ddnsToken: "",
  gpioCommands: [],
  workerName: `${os.hostname()}-windows-runtime`,
  downloadSettings: {
    videoQuality: "best",
    thumbnailFormat: "off",
    outputStructure: "flat",
    filenameTemplate: "%(title).180B [%(id)s].%(ext)s",
    mergeAudioVideo: true,
    mergeOutputFormat: "mp4",
    allowImageDownloads: true,
    maxConcurrentJobs: 1,
    fragmentConcurrency: 4,
    bandwidthLimit: "",
    retryLimit: 5,
    retryBackoffSeconds: 2,
    socketTimeoutSeconds: 30,
    duplicateUrlPolicy: "skip_pending",
    existingFilePolicy: "skip",
    jobTimeoutMinutes: 0,
    verifyOutput: true,
    adaptiveMode: false,
    maxOutputDirectoryGb: 0,
    minimumFreeDiskGb: 1,
    runtimeHistoryLimit: 100,
  },
  sessionTimeoutMinutes: 720,
  maxLoginAttempts: 5,
  loginWindowMinutes: 15,
  authUsername: "",
  authSalt: "",
  authPasswordHash: "",
};

function normalizeOutputDirectory(value) {
  const raw = String(value ?? "").trim();
  if (!raw || raw.length > 512 || raw.includes("\0")) {
    throw new Error("Nhập đường dẫn thư mục lưu hợp lệ (tối đa 512 ký tự).");
  }
  if (/^(?:ftp|ftps):\/\//i.test(raw)) throw new Error("FTP URL chưa được hỗ trợ; nhập đường dẫn thư mục trên PC hoặc đường dẫn mạng Windows.");
  if (/^smb:\/\//i.test(raw)) {
    let url;
    try {
      url = new URL(raw);
    } catch {
      throw new Error("Địa chỉ SMB không hợp lệ.");
    }
    if (url.protocol !== "smb:" || !url.hostname || url.port || url.username || url.password || url.search || url.hash) {
      throw new Error("SMB dùng dạng smb://server/share/path; thông tin xác thực hãy cấu hình trong Windows.");
    }
    const segments = url.pathname.split("/").filter(Boolean).map((segment) => decodeURIComponent(segment));
    if (!segments.length || segments.some((segment) => segment === "." || segment === "..")) {
      throw new Error("Địa chỉ SMB phải gồm ít nhất một tên share.");
    }
    return `\\\\${url.hostname}\\${segments.join("\\")}`;
  }
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(raw) && !/^[a-z]:\\/i.test(raw)) {
    throw new Error("Dùng đường dẫn Windows hoặc đường dẫn mạng UNC (\\\\server\\share).");
  }
  if (/^(?:\\\\|\/\/)[^\\/]+[\\/][^\\/]+/.test(raw)) return raw.replace(/\//g, "\\");
  if (/^[a-z]:[\\/]/i.test(raw)) {
    if (/[a-z]:[\\/]/i.test(raw.slice(3))) throw new Error("Đường dẫn chứa hai ổ đĩa; hãy thay toàn bộ nội dung bằng một đường dẫn duy nhất.");
    return path.win32.normalize(raw);
  }
  return path.resolve(raw);
}

let config = { ...defaultConfig };
let lastKnownDeviceBaseUrl = "";
let deviceAllowedGpioPins = [2, 4, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33];

function validateDownloadSettings(input, base = defaultConfig.downloadSettings) {
  const value = { ...base, ...(input && typeof input === "object" && !Array.isArray(input) ? input : {}) };
  const enums = {
    videoQuality: ["best", "1080p", "720p", "audio"],
    thumbnailFormat: ["off", "original", "jpg", "png", "webp"],
    outputStructure: ["flat", "date", "extractor", "extractor_date"],
    mergeOutputFormat: ["best", "mp4", "flv", "avi", "mkv", "ts", "webm"],
    duplicateUrlPolicy: ["skip_pending", "allow"],
    existingFilePolicy: ["skip", "overwrite", "rename"],
  };
  for (const [key, allowed] of Object.entries(enums)) {
    if (!allowed.includes(value[key])) throw new Error(`Cài đặt tải xuống không hợp lệ: ${key}.`);
  }
  const integerRanges = {
    maxConcurrentJobs: [1, 4],
    fragmentConcurrency: [1, 16],
    retryLimit: [0, 20],
    retryBackoffSeconds: [1, 60],
    socketTimeoutSeconds: [5, 600],
    jobTimeoutMinutes: [0, 1440],
    runtimeHistoryLimit: [20, 1000],
  };
  for (const [key, [minimum, maximum]] of Object.entries(integerRanges)) {
    const number = Number(value[key]);
    if (!Number.isInteger(number) || number < minimum || number > maximum) {
      throw new Error(`${key} phải là số nguyên từ ${minimum} đến ${maximum}.`);
    }
    value[key] = number;
  }
  for (const key of ["maxOutputDirectoryGb", "minimumFreeDiskGb"]) {
    const number = Number(value[key]);
    if (!Number.isFinite(number) || number < 0 || number > 10000) throw new Error(`${key} phải nằm trong khoảng 0–10000 GB.`);
    value[key] = number;
  }
  if (typeof value.bandwidthLimit !== "string" || value.bandwidthLimit.length > 16 || value.bandwidthLimit && !/^\d+(?:\.\d+)?[KMG]?$/i.test(value.bandwidthLimit)) {
    throw new Error("Giới hạn băng thông dùng số byte/giây, có thể kèm K, M hoặc G; để trống nếu không giới hạn.");
  }
  value.bandwidthLimit = value.bandwidthLimit.toUpperCase();
  if (typeof value.allowImageDownloads !== "boolean") throw new Error("allowImageDownloads phải là true hoặc false.");
  if (typeof value.filenameTemplate !== "string") throw new Error("Quy tắc tên file không hợp lệ.");
  value.filenameTemplate = value.filenameTemplate.trim();
  if (!value.filenameTemplate || value.filenameTemplate.length > 180 || /[\\/]|\.\./.test(value.filenameTemplate) || !value.filenameTemplate.includes("%(ext)s")) {
    throw new Error("Tên file phải dài tối đa 180 ký tự, chỉ là tên file và có chứa %(ext)s.");
  }
  for (const key of ["mergeAudioVideo", "verifyOutput", "adaptiveMode"]) {
    if (typeof value[key] !== "boolean") throw new Error(`${key} phải là true hoặc false.`);
  }
  return value;
}

function normalizePersistedSettings() {
  try { config.downloadSettings = validateDownloadSettings(config.downloadSettings); }
  catch { config.downloadSettings = { ...defaultConfig.downloadSettings }; }
  config.sessionTimeoutMinutes = Number.isInteger(Number(config.sessionTimeoutMinutes)) && Number(config.sessionTimeoutMinutes) >= 15 && Number(config.sessionTimeoutMinutes) <= 1440 ? Number(config.sessionTimeoutMinutes) : defaultConfig.sessionTimeoutMinutes;
  config.maxLoginAttempts = Number.isInteger(Number(config.maxLoginAttempts)) && Number(config.maxLoginAttempts) >= 3 && Number(config.maxLoginAttempts) <= 20 ? Number(config.maxLoginAttempts) : defaultConfig.maxLoginAttempts;
  config.loginWindowMinutes = Number.isInteger(Number(config.loginWindowMinutes)) && Number(config.loginWindowMinutes) >= 1 && Number(config.loginWindowMinutes) <= 60 ? Number(config.loginWindowMinutes) : defaultConfig.loginWindowMinutes;
  config.schemaVersion = 2;
  config.workerName = typeof config.workerName === "string" && /^[A-Za-z0-9._ -]{1,48}$/.test(config.workerName.trim())
    ? config.workerName.trim() : defaultConfig.workerName;
  workerId = config.workerName;
}

function isPrivateOrLocalHost(hostname) {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (host === "localhost" || host === "::1" || host.endsWith(".local")) return true;
  if (/^127\./.test(host) || /^10\./.test(host) || /^192\.168\./.test(host)) return true;
  const match = host.match(/^172\.(\d{1,3})\./);
  return Boolean(match && Number(match[1]) >= 16 && Number(match[1]) <= 31);
}

function validateDeviceBaseUrl(value) {
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error("ESP32 API URL must be a valid URL, for example http://192.168.4.1");
  }
  if (parsed.protocol !== "http:" || parsed.username || parsed.password || !isPrivateOrLocalHost(parsed.hostname)) {
    throw new Error("ESP32 URL must be plain HTTP on localhost, a private LAN address, or a .local host");
  }
  return parsed.origin;
}

function isPrivateIpv4Address(hostname) {
  const octets = hostname.split(".").map(Number);
  return octets.length === 4 && octets.every((part) => Number.isInteger(part) && part >= 0 && part <= 255)
    && (octets[0] === 10 || octets[0] === 192 && octets[1] === 168 || octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31);
}

function validateRuntimeBaseUrl(value) {
  if (!String(value ?? "").trim()) return "";
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error("Windows runtime URL must be an address on the private Wi-Fi network, for example http://192.168.1.25:8787");
  }
  if (parsed.protocol !== "http:" || parsed.username || parsed.password || !isPrivateIpv4Address(parsed.hostname)) {
    throw new Error("Windows runtime URL must use http:// and a private LAN address, not localhost");
  }
  return parsed.origin;
}

function getLanAddresses() {
  const addresses = [];
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.family !== "IPv4" || entry.internal || !isPrivateIpv4Address(entry.address)) continue;
      addresses.push(entry.address);
    }
  }
  return [...new Set(addresses)];
}

async function loadConfig() {
  await fs.mkdir(dataRoot, { recursive: true });
  try {
    const loaded = JSON.parse(await fs.readFile(configPath, "utf8"));
    config = { ...defaultConfig, ...loaded };
    if (!config.systemId) {
      config.systemId = "esp-host-" + randomBytes(4).toString("hex");
    }
    if (process.platform !== "win32" && /^[a-zA-Z]:[\\/]/.test(config.outputDirectory)) {
      config.outputDirectory = path.resolve(here, "downloads");
    }
    config.deviceBaseUrl = validateDeviceBaseUrl(config.deviceBaseUrl);
    config.runtimeBaseUrl = validateRuntimeBaseUrl(config.runtimeBaseUrl);
    if (/[\\/](?:ftp|ftps):[\\/]/i.test(String(config.outputDirectory ?? ""))) {
      config.outputDirectory = "D:\\ESP-Downloads";
      config.hostSettingsSyncPending = true;
      console.warn("Repaired legacy FTP URL that had been stored as a Windows output path.");
    } else {
      try {
        config.outputDirectory = normalizeOutputDirectory(config.outputDirectory);
      } catch (error) {
        config.outputDirectory = defaultConfig.outputDirectory;
        config.hostSettingsSyncPending = true;
        console.error(`Repaired invalid output directory: ${error.message}`);
      }
    }
    config.urlRetentionDays = Number.isInteger(Number(config.urlRetentionDays)) && Number(config.urlRetentionDays) >= 0 && Number(config.urlRetentionDays) <= 3650
      ? Number(config.urlRetentionDays) : defaultConfig.urlRetentionDays;
    config.gpioCommands = Array.isArray(config.gpioCommands) ? config.gpioCommands : [];
  } catch (error) {
    if (error.code !== "ENOENT") {
      console.error(`Ignoring invalid local config: ${error.message}`);
    }
    config = { ...defaultConfig };
  }
  normalizePersistedSettings();
  deviceSettingsRevision = Number.isInteger(Number(config.settingsRevision)) && Number(config.settingsRevision) >= 0 ? Number(config.settingsRevision) : 0;
  hostSettingsSyncPending = config.hostSettingsSyncPending === true;
  const lanIps = getLanAddresses();
  let currentRuntimeHost = "";
  try {
    currentRuntimeHost = new URL(config.runtimeBaseUrl).hostname;
  } catch {}
  if (!config.runtimeBaseUrl || !lanIps.includes(currentRuntimeHost) || !config.runtimeBaseUrl.includes(`:${port}`)) {
    const hostAddress = lanIps[0];
    if (hostAddress) {
      config.runtimeBaseUrl = `http://${hostAddress}:${port}`;
      integrationsSyncPending = true;
    }
  }
  ensureAccountCredentials();
  await saveConfig();
  await loadRuntimeQueue();
  await loadScheduledDownloads();
}

function hashPassword(password, salt) {
  return scryptSync(password, salt, 64).toString("hex");
}

function ensureAccountCredentials() {
  const validUsername = typeof config.authUsername === "string" && /^[A-Za-z0-9._-]{1,32}$/.test(config.authUsername);
  const validSalt = typeof config.authSalt === "string" && /^[a-f0-9]{32}$/i.test(config.authSalt);
  const validHash = typeof config.authPasswordHash === "string" && /^[a-f0-9]{128}$/i.test(config.authPasswordHash);
  if (validUsername && validSalt && validHash) return;
  config.authUsername = "admin";
  config.authSalt = randomBytes(16).toString("hex");
  config.authPasswordHash = hashPassword("admin", config.authSalt);
}

function isLoopbackPeerAddress(address) {
  const ip = String(address ?? "").toLowerCase().replace(/^::ffff:/, "");
  return ip === "::1" || /^127\./.test(ip);
}

function getSession(request) {
  const cookie = String(request.headers.cookie ?? "").split(";").map((part) => part.trim()).find((part) => part.startsWith(`${SESSION_COOKIE}=`));
  const bearer = String(request.headers["x-local-session"] ?? "");
  const token = cookie?.slice(SESSION_COOKIE.length + 1) ?? bearer;
  if (!/^[a-f0-9]{64}$/i.test(token)) return null;
  const session = sessions.get(token);
  if (!session) return null;
  if (session.expiresAt <= Date.now()) {
    sessions.delete(token);
    return null;
  }
  return { token, ...session };
}

function setSessionCookie(response, token) {
  const sessionTtlMs = config.sessionTimeoutMinutes * 60 * 1000;
  response.setHeader("Set-Cookie", `${SESSION_COOKIE}=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${Math.floor(sessionTtlMs / 1000)}`);
}

function clearSessionCookie(response) {
  response.setHeader("Set-Cookie", `${SESSION_COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0`);
}

function loginFailureState(request) {
  const key = request.socket.remoteAddress ?? "unknown";
  const now = Date.now();
  let state = loginFailures.get(key);
  const loginWindowMs = config.loginWindowMinutes * 60 * 1000;
  if (!state || state.windowStartedAt + loginWindowMs <= now) {
    state = { windowStartedAt: now, count: 0 };
    loginFailures.set(key, state);
  }
  return { key, state };
}

function passwordMatches(password) {
  const actual = Buffer.from(hashPassword(password, config.authSalt), "hex");
  const expected = Buffer.from(config.authPasswordHash, "hex");
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

async function handleLogin(request, response, { includeSessionToken = false } = {}) {
  const { key, state } = loginFailureState(request);
  if (state.count >= config.maxLoginAttempts) {
    jsonResponse(response, 429, { error: `Quá nhiều lần đăng nhập sai. Hãy thử lại sau ${config.loginWindowMinutes} phút.`, code: "LOGIN_RATE_LIMIT" });
    return;
  }
  try {
    const body = await readJsonBody(request, 4096);
    const username = String(body.username ?? "");
    const password = String(body.password ?? "");
    const suppliedUsername = Buffer.from(username);
    const expectedUsername = Buffer.from(config.authUsername);
    const usernameMatches = suppliedUsername.length === expectedUsername.length && timingSafeEqual(suppliedUsername, expectedUsername);
    const qrSession = /^[a-f0-9]{64}$/i.test(password) ? sessions.get(password) : null;
    const qrBootstrapValid = Boolean(qrSession
      && qrSession.qrBootstrap === true
      && qrSession.username === username
      && qrSession.expiresAt > Date.now());
    const credentialsMatch = password.length <= 1024 && (passwordMatches(password) || qrBootstrapValid);
    if (!usernameMatches || !credentialsMatch) {
      state.count++;
      jsonResponse(response, 401, { error: "Tài khoản hoặc mật khẩu không đúng.", code: "INVALID_CREDENTIALS" });
      return;
    }
    loginFailures.delete(key);
    if (qrBootstrapValid) sessions.delete(password);
    const token = randomBytes(32).toString("hex");
    const expiresAt = Date.now() + config.sessionTimeoutMinutes * 60 * 1000;
    sessions.set(token, { username: config.authUsername, expiresAt });
    setSessionCookie(response, token);
    jsonResponse(response, 200, { authenticated: true, username: config.authUsername, ...(includeSessionToken ? { sessionToken: token, expiresAt } : {}) });
  } catch (error) {
    jsonResponse(response, 400, { error: error.message, code: "INVALID_LOGIN_REQUEST" });
  }
}

async function handleAccountUpdate(request, response) {
  try {
    const body = await readJsonBody(request, 8192);
    const currentPassword = String(body.currentPassword ?? "");
    if (currentPassword.length > 1024 || !passwordMatches(currentPassword)) {
      jsonResponse(response, 401, { error: "Mật khẩu hiện tại không đúng.", code: "INVALID_CURRENT_PASSWORD" });
      return;
    }

    const requestedUsername = String(body.username ?? config.authUsername).trim();
    const requestedPassword = String(body.newPassword ?? "");
    if (!/^[A-Za-z0-9._-]{1,32}$/.test(requestedUsername)) {
      throw new Error("Tên đăng nhập phải dài 1–32 ký tự và chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.");
    }
    if (requestedPassword && (requestedPassword.length < 8 || requestedPassword.length > 128)) {
      throw new Error("Mật khẩu mới phải dài từ 8 đến 128 ký tự.");
    }
    if (requestedUsername === config.authUsername && !requestedPassword) {
      throw new Error("Hãy nhập tên đăng nhập mới hoặc mật khẩu mới.");
    }

    const previousCredentials = {
      authUsername: config.authUsername,
      authSalt: config.authSalt,
      authPasswordHash: config.authPasswordHash,
    };
    config.authUsername = requestedUsername;
    if (requestedPassword) {
      config.authSalt = randomBytes(16).toString("hex");
      config.authPasswordHash = hashPassword(requestedPassword, config.authSalt);
    }
    try {
      await saveConfig();
    } catch (error) {
      Object.assign(config, previousCredentials);
      throw error;
    }

    sessions.clear();
    const token = randomBytes(32).toString("hex");
    const expiresAt = Date.now() + config.sessionTimeoutMinutes * 60 * 1000;
    sessions.set(token, { username: config.authUsername, expiresAt });
    setSessionCookie(response, token);
    jsonResponse(response, 200, { saved: true, username: config.authUsername });
  } catch (error) {
    jsonResponse(response, 400, { error: error.message, code: "INVALID_ACCOUNT_UPDATE" });
  }
}

function isTerminalRuntimeJob(job) {
  return ["COMPLETED", "FAILED", "ERROR1", "ERROR2", "CANCELLED"].includes(job.runtimeStatus);
}

function trimRuntimeHistory() {
  const retentionDays = Number(config.urlRetentionDays);
  const cutoff = retentionDays > 0 ? Date.now() - retentionDays * 86400_000 : 0;
  const terminal = runtimeQueue.filter(isTerminalRuntimeJob)
    .sort((a, b) => Date.parse(b.finishedAt ?? b.receivedAt ?? 0) - Date.parse(a.finishedAt ?? a.receivedAt ?? 0));
  const keepIds = new Set();
  for (const job of terminal) {
    const timestamp = Date.parse(job.finishedAt ?? job.receivedAt ?? "") || 0;
    if (cutoff && timestamp && timestamp < cutoff) continue;
    if (keepIds.size < config.downloadSettings.runtimeHistoryLimit) keepIds.add(job.id);
  }
  runtimeQueue = runtimeQueue.filter((job) => !isTerminalRuntimeJob(job) || keepIds.has(job.id));
}

function saveRuntimeQueue() {
  trimRuntimeHistory();
  const snapshot = `${JSON.stringify(runtimeQueue, null, 2)}\n`;
  runtimeQueueWrite = runtimeQueueWrite.catch(() => {}).then(async () => {
    await fs.mkdir(dataRoot, { recursive: true });
    const tempPath = `${runtimeQueuePath}.tmp`;
    await fs.writeFile(tempPath, snapshot, { mode: 0o600 });
    await fs.rename(tempPath, runtimeQueuePath);
  });
  return runtimeQueueWrite;
}

async function loadRuntimeQueue() {
  try {
    const loaded = JSON.parse(await fs.readFile(runtimeQueuePath, "utf8"));
    runtimeQueue = Array.isArray(loaded) ? loaded : [];
  } catch (error) {
    runtimeQueue = [];
    if (error.code !== "ENOENT") console.error(`Ignoring invalid runtime queue: ${error.message}`);
  }
  for (const job of runtimeQueue) {
    if (job.runtimeStatus === "RUNNING") job.runtimeStatus = "QUEUED";
  }
  await saveRuntimeQueue();
}

function nextScheduledAt(schedule, afterMs = Date.now()) {
  const [hour, minute] = schedule.time.split(":").map(Number);
  const after = new Date(afterMs);
  if (schedule.recurrence === "daily") {
    const candidate = new Date(afterMs);
    candidate.setHours(hour, minute, 0, 0);
    if (candidate.getTime() <= afterMs) candidate.setDate(candidate.getDate() + 1);
    return candidate.getTime();
  }
  if (schedule.recurrence === "weekly") {
    for (let offset = 0; offset <= 7; offset++) {
      const candidate = new Date(after.getFullYear(), after.getMonth(), after.getDate() + offset, hour, minute, 0, 0);
      if (candidate.getDay() === schedule.weekday && candidate.getTime() > afterMs) return candidate.getTime();
    }
    return afterMs + 7 * 86400_000;
  }
  for (let offset = 0; offset <= 13; offset++) {
    const month = after.getMonth() + offset;
    const year = after.getFullYear() + Math.floor(month / 12);
    const normalizedMonth = month % 12;
    const lastDay = new Date(year, normalizedMonth + 1, 0).getDate();
    const candidate = new Date(year, normalizedMonth, Math.min(schedule.dayOfMonth, lastDay), hour, minute, 0, 0);
    if (candidate.getTime() > afterMs) return candidate.getTime();
  }
  return afterMs + 31 * 86400_000;
}

function saveScheduledDownloads() {
  const snapshot = `${JSON.stringify(scheduledDownloads, null, 2)}\n`;
  scheduledDownloadsWrite = scheduledDownloadsWrite.catch(() => {}).then(async () => {
    await fs.mkdir(dataRoot, { recursive: true });
    const tempPath = `${scheduledDownloadsPath}.tmp`;
    await fs.writeFile(tempPath, snapshot, { mode: 0o600 });
    await fs.rename(tempPath, scheduledDownloadsPath);
  });
  return scheduledDownloadsWrite;
}

async function loadScheduledDownloads() {
  try {
    const loaded = JSON.parse(await fs.readFile(scheduledDownloadsPath, "utf8"));
    scheduledDownloads = Array.isArray(loaded) ? loaded.filter((item) => item && typeof item.id === "string" && validHttpUrl(String(item.url ?? ""))
      && typeof item.outputDirectory === "string" && ["daily", "weekly", "monthly"].includes(item.recurrence)
      && /^([01]\d|2[0-3]):[0-5]\d$/.test(String(item.time ?? ""))) : [];
  } catch (error) {
    scheduledDownloads = [];
    if (error.code !== "ENOENT") console.error(`Ignoring invalid scheduled-download file: ${error.message}`);
  }
  scheduledDownloads = scheduledDownloads.slice(0, 50).map((item) => ({
    ...item,
    weekday: Math.max(0, Math.min(6, Number(item.weekday) || 0)),
    dayOfMonth: Math.max(1, Math.min(31, Number(item.dayOfMonth) || 1)),
    enabled: item.enabled !== false,
    nextRunAt: Number.isFinite(Number(item.nextRunAt)) ? Number(item.nextRunAt) : null,
    lastRunStatus: String(item.lastRunStatus ?? ""),
  }));
  await saveScheduledDownloads();
}

async function normalizeScheduledDownload(body, existing = null) {
  const url = String(body.url ?? existing?.url ?? "").trim();
  if (!validHttpUrl(url)) throw new Error("Nhập URL http/https hợp lệ (tối đa 1024 ký tự).");
  const recurrence = String(body.recurrence ?? existing?.recurrence ?? "daily");
  if (!["daily", "weekly", "monthly"].includes(recurrence)) throw new Error("Lịch phải là hàng ngày, hàng tuần hoặc hàng tháng.");
  const time = String(body.time ?? existing?.time ?? "09:00").trim();
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(time)) throw new Error("Chọn giờ theo định dạng 24 giờ HH:MM.");
  const weekday = Number(body.weekday ?? existing?.weekday ?? 1);
  const dayOfMonth = Number(body.dayOfMonth ?? existing?.dayOfMonth ?? 1);
  if (!Number.isInteger(weekday) || weekday < 0 || weekday > 6) throw new Error("Ngày trong tuần không hợp lệ.");
  if (!Number.isInteger(dayOfMonth) || dayOfMonth < 1 || dayOfMonth > 31) throw new Error("Ngày trong tháng phải từ 1 đến 31.");
  const rawOutputDirectory = String(body.outputDirectory ?? existing?.outputDirectory ?? config.outputDirectory).trim();
  const outputDirectory = normalizeOutputDirectory(rawOutputDirectory);
  const enabled = typeof body.enabled === "boolean" ? body.enabled : (existing?.enabled !== false);
  const next = {
    id: existing?.id ?? randomBytes(8).toString("hex"),
    url,
    outputDirectory,
    recurrence,
    time,
    weekday,
    dayOfMonth,
    enabled,
    createdAt: existing?.createdAt ?? new Date().toISOString(),
    lastRunAt: existing?.lastRunAt ?? "",
    lastRunStatus: existing?.lastRunStatus ?? "",
    lastJobId: existing?.lastJobId ?? "",
    nextRunAt: existing?.nextRunAt ?? null,
  };
  if (body.recurrence !== undefined || body.time !== undefined || body.weekday !== undefined || body.dayOfMonth !== undefined || body.enabled !== undefined || !next.nextRunAt) {
    next.nextRunAt = next.enabled ? nextScheduledAt(next) : null;
  }
  await fs.mkdir(outputDirectory, { recursive: true });
  await fs.access(outputDirectory, fsConstants.W_OK);
  return next;
}

async function runScheduledDownload(schedule) {
  const id = `sched_${Date.now().toString(36)}_${randomBytes(3).toString("hex")}`;
  const job = {
    id,
    scheduleId: schedule.id,
    url: schedule.url,
    source: "schedule",
    outputDirectory: schedule.outputDirectory || config.outputDirectory,
    deviceAttempts: 0,
    attempts: 0,
    createdAt: new Date().toISOString(),
    runtimeStatus: "QUEUED",
    force: false,
    outputPath: "",
    outputPaths: [],
    outputBytes: 0,
    error: "",
    deviceReported: true,
    receivedAt: new Date().toISOString(),
  };
  runtimeQueue.push(job);
  await saveRuntimeQueue();
  schedule.lastJobId = id;
  schedule.lastRunAt = new Date().toISOString();
  schedule.lastRunStatus = "QUEUED";
  await saveScheduledDownloads();
  return job;
}

async function checkScheduledDownloads() {
  if (scheduledDownloadsBusy) return;
  scheduledDownloadsBusy = true;
  try {
    const now = Date.now();
    for (const schedule of scheduledDownloads) {
      if (!schedule.enabled) continue;
      if (!schedule.nextRunAt) {
        schedule.nextRunAt = nextScheduledAt(schedule, now);
        await saveScheduledDownloads();
        continue;
      }
      if (now >= schedule.nextRunAt) {
        console.log(`Executing scheduled download: ${schedule.url} (ID: ${schedule.id})`);
        schedule.nextRunAt = nextScheduledAt(schedule, now + 60000);
        await runScheduledDownload(schedule);
      }
    }
  } catch (error) {
    console.error("Scheduled downloads check error:", error.message);
  } finally {
    scheduledDownloadsBusy = false;
  }
}

async function saveConfig() {
  await fs.mkdir(dataRoot, { recursive: true });
  const tempPath = `${configPath}.tmp`;
  await fs.writeFile(tempPath, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
  await fs.rename(tempPath, configPath);
}

function jsonResponse(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Content-Length": Buffer.byteLength(body),
  });
  response.end(body);
}

function textResponse(response, status, value) {
  response.writeHead(status, { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" });
  response.end(value);
}

const allowedFirmwareModels = new Set(["ESP32-WROOM-32", "ESP32-S2", "ESP32-S3", "ESP32-C3", "ESP32-C5", "ESP32-C6"]);

async function listBuiltFirmware() {
  const models = await fs.readdir(firmwareBuildRoot, { withFileTypes: true });
  const files = [];
  for (const model of models) {
    if (!model.isDirectory() || !allowedFirmwareModels.has(model.name)) continue;
    const environmentPath = path.join(firmwareBuildRoot, model.name);
    const entries = await fs.readdir(environmentPath, { withFileTypes: true });
    for (const entry of entries) {
      if (!entry.isFile() || entry.name !== `${model.name}-firmware.bin`) continue;
      const filePath = path.join(environmentPath, entry.name);
      const info = await fs.stat(filePath);
      files.push({
        id: `${model.name}/${entry.name}`,
        name: entry.name,
        environment: model.name,
        size: info.size,
        modifiedAt: info.mtime.toISOString(),
      });
    }
  }
  files.sort((a, b) => a.environment.localeCompare(b.environment) || a.name.localeCompare(b.name));
  return files;
}

async function sendBuiltFirmware(response, fileId) {
  if (!fileId || fileId.includes("\\") || fileId.startsWith("/")) {
    jsonResponse(response, 400, { error: "Invalid firmware file identifier." });
    return;
  }
  const parts = fileId.split("/");
  if (parts.length !== 2 || parts.some((part) => !part || part === "." || part === "..")) {
    jsonResponse(response, 400, { error: "Invalid firmware file identifier." });
    return;
  }
  const [model, filename] = parts;
  if (!allowedFirmwareModels.has(model) || filename !== `${model}-firmware.bin`) {
    jsonResponse(response, 400, { error: "Firmware file is not in the allowed build output list." });
    return;
  }
  const root = await fs.realpath(firmwareBuildRoot);
  const filePath = path.resolve(root, model, filename);
  const realFilePath = await fs.realpath(filePath);
  const relative = path.relative(root, realFilePath);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    jsonResponse(response, 403, { error: "Firmware file is outside the build output directory." });
    return;
  }
  const info = await fs.stat(realFilePath);
  if (!info.isFile() || info.size === 0 || info.size > 32 * 1024 * 1024) {
    jsonResponse(response, 409, { error: "Firmware file is empty or exceeds the 32 MB limit." });
    return;
  }
  response.writeHead(200, {
    "Content-Type": "application/octet-stream",
    "Content-Length": info.size,
    "Content-Disposition": `attachment; filename="${filename}"`,
    "Cache-Control": "no-store",
  });
  const stream = fsSync.createReadStream(realFilePath);
  stream.on("error", (error) => {
    console.error("Could not stream firmware file:", error.message);
    if (response.headersSent) response.destroy(error);
    else jsonResponse(response, 500, { error: "Could not read the selected firmware file." });
  });
  stream.pipe(response);
}

async function readJsonBody(request, maxBytes = 1024 * 1024) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > maxBytes) throw new Error("Request body is too large");
    chunks.push(chunk);
  }
  if (length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new Error("Request body must be valid JSON");
  }
}

function tryResolveEsp32IpFromArp() {
  try {
    const out = execSync("arp -a", { timeout: 1200 }).toString();
    const lines = out.split("\n");
    const prefixes = ["88-13-bf", "24-6f-28", "30-ae-a4", "ac-67-b2", "c4-4f-33", "b4-e6-2d", "d8-bc-38", "34-b7-da", "ec-94-cb", "3c-61-05", "70-b8-f6", "78-21-84", "94-b9-7e", "a0-20-a6", "a4-cf-12", "cc-50-e3", "dc-54-75"];
    for (const line of lines) {
      for (const prefix of prefixes) {
        if (line.toLowerCase().includes(prefix.toLowerCase())) {
          const match = line.trim().match(/^([0-9.]+)\s+/);
          if (match && isPrivateIpv4Address(match[1])) return `http://${match[1]}`;
        }
      }
    }
  } catch {}
  return null;
}

async function requestDevice(apiPath, method = "GET", body = undefined, timeoutMs = 6500) {
  const configuredBase = config.deviceBaseUrl;
  const candidateBases = [];
  if (lastKnownDeviceBaseUrl && lastKnownDeviceBaseUrl !== configuredBase) {
    candidateBases.push(lastKnownDeviceBaseUrl);
  }
  candidateBases.push(configuredBase);
  const arpIp = tryResolveEsp32IpFromArp();
  if (arpIp) candidateBases.push(arpIp);
  const bases = Array.from(new Set(candidateBases.filter(Boolean)));
  let lastError;
  let lastResult;
  for (const base of bases) {
    const target = new URL(apiPath, `${base}/`);
    if (!target.pathname.startsWith("/api/v1/")) throw new Error("Only device API v1 routes are allowed");
    const controller = new AbortController();
    const effectiveTimeoutMs = target.hostname.toLowerCase().endsWith(".local") ? Math.max(timeoutMs, 3500) : timeoutMs;
    const timeout = setTimeout(() => controller.abort(), effectiveTimeoutMs);
    try {
      const headers = {};
      if (config.deviceToken) headers["X-Device-Token"] = config.deviceToken;
      if (body !== undefined) headers["Content-Type"] = "application/json";
      const response = await fetch(target, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
        redirect: "error",
      });
      const contentType = response.headers.get("content-type") ?? "";
      const payload = contentType.includes("application/json") ? await response.json() : await response.text();
      lastResult = { status: response.status, payload };
      if (response.status >= 200 && response.status < 300) {
        lastKnownDeviceBaseUrl = base;
        if (config.deviceBaseUrl !== base) {
          config.deviceBaseUrl = base;
          void saveConfig();
        }
      }
      if (apiPath === "/api/v1/system" && response.status >= 200 && response.status < 300) {
        if (payload?.wifiConnected === true && isPrivateIpv4Address(String(payload.ipAddress ?? ""))) {
          const configuredUrl = new URL(configuredBase);
          lastKnownDeviceBaseUrl = `http://${payload.ipAddress}${configuredUrl.port ? `:${configuredUrl.port}` : ""}`;
          if (config.deviceBaseUrl !== lastKnownDeviceBaseUrl) {
            config.deviceBaseUrl = lastKnownDeviceBaseUrl;
            void saveConfig();
          }
        }
      }
      if (response.ok || bases.length === 1) return lastResult;
      lastError = new Error(`Device returned HTTP ${response.status}`);
    } catch (error) {
      lastError = error;
    } finally {
      clearTimeout(timeout);
    }
  }
  if (lastResult) return lastResult;
  throw lastError ?? new Error("Could not reach the ESP32 API");
}

function findFileInDir(dir, filename, maxDepth = 2) {
  if (maxDepth < 0 || !fsSync.existsSync(dir)) return null;
  try {
    const entries = fsSync.readdirSync(dir, { withFileTypes: true });
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isFile() && entry.name.toLowerCase() === filename.toLowerCase()) return full;
      if (entry.isDirectory() && maxDepth > 0) {
        const found = findFileInDir(full, filename, maxDepth - 1);
        if (found) return found;
      }
    }
  } catch {}
  return null;
}

const KNOWN_TOOL_SEARCH_DIRS = [
  path.join(os.homedir(), "AppData", "Local", "Programs", "Python", "Python311", "Scripts"),
  path.join(os.homedir(), "AppData", "Local", "Programs", "Python", "Python312", "Scripts"),
  path.join(os.homedir(), "AppData", "Local", "Programs", "Python", "Python310", "Scripts"),
  path.join(os.homedir(), ".deno", "bin"),
  "C:\\ffmpeg\\bin",
  "C:\\Program Files\\ffmpeg\\bin",
  path.join(os.homedir(), "AppData", "Local", "Microsoft", "WinGet", "Links"),
  path.join(os.homedir(), "scoop", "shims"),
  "C:\\ProgramData\\chocolatey\\bin",
];

function resolveExecutable(toolName, explicitPath) {
  if (explicitPath) {
    const trimmed = String(explicitPath).trim();
    if (trimmed && fsSync.existsSync(trimmed)) return trimmed;
  }
  const exeName = toolName.endsWith(".exe") ? toolName : `${toolName}.exe`;
  for (const dir of KNOWN_TOOL_SEARCH_DIRS) {
    const candidate = path.join(dir, exeName);
    if (fsSync.existsSync(candidate)) return candidate;
  }
  try {
    const wingetDir = path.join(os.homedir(), "AppData", "Local", "Microsoft", "WinGet", "Packages");
    const match = findFileInDir(wingetDir, exeName, 3);
    if (match) return match;
  } catch {}
  return toolName;
}

async function getYtDlpVersion() {
  const executable = resolveExecutable("yt-dlp", config.ytDlpPath);
  if (toolVersionCache.path === executable && Date.now() - toolVersionCache.checkedAt < 30_000) return toolVersionCache;
  if (toolVersionChecks.has(executable)) return toolVersionChecks.get(executable);

  const check = (async () => {
    const result = { checkedAt: Date.now(), path: executable, version: null, error: null };
    try {
      const { stdout } = await execFileAsync(executable, ["--version"], { timeout: 5000, windowsHide: true });
      result.version = stdout.trim().split(/\r?\n/)[0] || "available";
    } catch (error) {
      result.error = error.code === "ENOENT" ? "yt-dlp not found" : error.message;
    }
    toolVersionCache = result;
    return result;
  })();
  toolVersionChecks.set(executable, check);
  try {
    return await check;
  } finally {
    toolVersionChecks.delete(executable);
  }
}

async function checkSystemPackages() {
  const toolDefinitions = [
    { id: "yt-dlp", name: "yt-dlp", binary: "yt-dlp", explicit: config.ytDlpPath, args: ["--version"], required: true, description: "Công cụ tải video cốt lõi cho YouTube, TikTok, Facebook..." },
    { id: "ffmpeg", name: "FFmpeg", binary: "ffmpeg", explicit: config.ffmpegPath, args: ["-version"], required: true, description: "Ghép nối âm thanh, video và xử lý media" },
    { id: "aria2c", name: "aria2c", binary: "aria2c", explicit: "", args: ["--version"], required: false, description: "Bộ tăng tốc tải phân đoạn đa luồng" },
    { id: "deno", name: "Deno", binary: "deno", explicit: config.denoPath, args: ["--version"], required: false, description: "JavaScript/TypeScript runtime phụ trợ yt-dlp" },
    { id: "python", name: "Python", binary: "python", explicit: "", args: ["--version"], required: false, description: "Môi trường thực thi script và các module" },
    { id: "node", name: "Node.js", binary: "node", explicit: process.execPath, args: ["--version"], required: true, description: "Môi trường chạy Windows Runtime host và server API" },
    { id: "powershell", name: "PowerShell", binary: "powershell", explicit: "", args: ["-Command", "$PSVersionTable.PSVersion.ToString()"], required: process.platform === "win32", description: "Bộ tự động hóa hệ thống Windows" },
    { id: "gallery-dl", name: "gallery-dl", binary: "gallery-dl", explicit: "", args: ["--version"], required: false, description: "Tải ảnh và album photo TikTok / Instagram" },
  ];

  const results = [];
  for (const def of toolDefinitions) {
    const resolvedPath = resolveExecutable(def.binary, def.explicit);
    let installed = false;
    let version = "";
    let detectedPath = resolvedPath;
    try {
      let invocation = { executable: resolvedPath, args: def.args };
      if (def.id === "gallery-dl") {
        const userPythonCandidates = [
          path.join(os.homedir(), "AppData", "Local", "Python", "pythoncore-3.14-64", "python.exe"),
          path.join(os.homedir(), "AppData", "Local", "Python", "bin", "python.exe"),
          "C:\\Users\\neopr\\AppData\\Local\\Python\\pythoncore-3.14-64\\python.exe",
          "C:\\Users\\neopr\\AppData\\Local\\Python\\bin\\python.exe",
        ];
        const galleryPython = userPythonCandidates.find((candidate) => fsSync.existsSync(candidate));
        if (galleryPython) invocation = { executable: galleryPython, args: ["-m", "gallery_dl", "--version"] };
        else invocation = { executable: resolvedPath, args: def.args };
      }
      const { stdout } = await execFileAsync(invocation.executable, invocation.args, { timeout: 4000, windowsHide: true });
      const line = stdout.trim().split(/\r?\n/)[0] || "";
      version = line.replace(/^(ffmpeg version|Python|v)/i, "").trim();
      installed = true;
      detectedPath = invocation.executable;
    } catch {
      installed = false;
    }
    results.push({
      id: def.id,
      name: def.name,
      installed,
      version: installed ? version : "",
      path: installed ? detectedPath : "",
      required: def.required,
      description: def.description,
    });
  }
  return results;
}

function installSystemPackages(response) {
  response.writeHead(200, {
    "Content-Type": "text/plain; charset=utf-8",
    "Transfer-Encoding": "chunked",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });

  const send = (msg) => {
    try { response.write(msg + "\n"); } catch {}
  };

  send("=== BẮT ĐẦU KIỂM TRA VÀ CÀI ĐẶT CÁC GÓI THIẾU ===");
  send(`Thời gian: ${new Date().toLocaleString()}`);

  const psScript = `
    $ErrorActionPreference = 'Continue'
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    Write-Host "1. Cập nhật / Cài đặt yt-dlp..."
    try {
        winget install --id yt-dlp.yt-dlp --accept-package-agreements --accept-source-agreements --silent
    } catch {
        Write-Host "Winget không thành công, thử pip..."
        pip install --upgrade yt-dlp
    }

    Write-Host "2. Kiểm tra FFmpeg..."
    if (-not (Get-Command ffmpeg.exe -ErrorAction SilentlyContinue)) {
        Write-Host "Đang cài FFmpeg qua winget..."
        winget install --id Gyan.FFmpeg --accept-package-agreements --accept-source-agreements --silent
    } else {
        Write-Host "FFmpeg đã sẵn sàng."
    }

    Write-Host "3. Kiểm tra aria2c..."
    if (-not (Get-Command aria2c.exe -ErrorAction SilentlyContinue)) {
        Write-Host "Đang cài aria2 qua winget..."
        winget install --id aria2.aria2 --accept-package-agreements --accept-source-agreements --silent
    } else {
        Write-Host "aria2c đã sẵn sàng."
    }

    Write-Host "4. Kiểm tra Deno..."
    if (-not (Get-Command deno.exe -ErrorAction SilentlyContinue)) {
        Write-Host "Đang cài Deno qua winget..."
        winget install --id DenoLand.Deno --accept-package-agreements --accept-source-agreements --silent
    } else {
        Write-Host "Deno đã sẵn sàng."
    }

    Write-Host "5. Cài đặt gallery-dl..."
    try {
        pip install --upgrade gallery-dl
    } catch {
        winget install --id mikf.gallery-dl --accept-package-agreements --accept-source-agreements --silent
    }
    Write-Host "=== HOÀN TẤT TIẾN TRÌNH CÀI ĐẶT ==="
  `;

  const child = spawn("powershell.exe", ["-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psScript], {
    windowsHide: true,
  });

  child.stdout.on("data", (data) => { try { response.write(data); } catch {} });
  child.stderr.on("data", (data) => { try { response.write(data); } catch {} });
  child.on("close", (code) => {
    toolVersionCache.checkedAt = 0;
    send(`\n[Tiến trình kết thúc với mã: ${code}]`);
    response.end();
  });
  child.on("error", (err) => {
    send(`\nLỗi chạy tiến trình: ${err.message}`);
    response.end();
  });
}

async function checkTelegramToken(token) {
  const checkedAt = new Date().toISOString();
  if (!token) return { configured: false, reachable: null, tokenValid: null, checkedAt, message: "Chưa cấu hình token Telegram." };
  if (!/^\d+:[A-Za-z0-9_-]+$/.test(token)) {
    return { configured: true, reachable: false, tokenValid: false, checkedAt, message: "Token sai định dạng." };
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch(`https://api.telegram.org/bot${token}/getMe`, {
      method: "GET",
      signal: controller.signal,
      redirect: "error",
      headers: { Accept: "application/json" },
    });
    const payload = await response.json().catch(() => ({}));
    if (response.ok && payload.ok === true && payload.result?.is_bot === true) {
      return {
        configured: true,
        reachable: true,
        tokenValid: true,
        botUsername: payload.result.username ?? "",
        botName: payload.result.first_name ?? "",
        checkedAt,
        message: "Telegram API và bot token hoạt động.",
      };
    }
    return {
      configured: true,
      reachable: true,
      tokenValid: false,
      checkedAt,
      message: payload.description ?? `Telegram trả HTTP ${response.status}.`,
    };
  } catch (error) {
    return {
      configured: true,
      reachable: false,
      tokenValid: null,
      checkedAt,
      message: error.name === "AbortError" ? "Hết thời gian chờ Telegram API." : `Không tới được Telegram API: ${error.message}`,
    };
  } finally {
    clearTimeout(timeout);
  }
}

async function getTelegramStatus(force = false) {
  if (!config.telegramBotToken) {
    telegramStatusCache = { checkedAt: Date.now(), value: await checkTelegramToken("") };
    return telegramStatusCache.value;
  }
  if (!force && Date.now() - telegramStatusCache.checkedAt < 60_000 && telegramStatusCache.value) {
    return telegramStatusCache.value;
  }
  if (!telegramCheckInFlight) {
    telegramCheckInFlight = checkTelegramToken(config.telegramBotToken).then((value) => {
      telegramStatusCache = { checkedAt: Date.now(), value };
      return value;
    }).finally(() => {
      telegramCheckInFlight = null;
    });
  }
  return telegramCheckInFlight;
}

async function telegramApiRequest(method, parameters = {}, timeoutMs = 5000, externalSignal = undefined) {
  if (!config.telegramBotToken) throw new Error("Telegram bot token chưa được cấu hình.");
  const url = new URL(`https://api.telegram.org/bot${config.telegramBotToken}/${method}`);
  const isPost = method === "sendMessage";
  if (!isPost) {
    for (const [key, value] of Object.entries(parameters)) url.searchParams.set(key, String(value));
  }
  const controller = new AbortController();
  const abortFromExternal = () => controller.abort();
  if (externalSignal?.aborted) controller.abort();
  else externalSignal?.addEventListener("abort", abortFromExternal, { once: true });
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      method: isPost ? "POST" : "GET",
      headers: isPost ? { "Content-Type": "application/json", Accept: "application/json" } : { Accept: "application/json" },
      body: isPost ? JSON.stringify(parameters) : undefined,
      signal: controller.signal,
      redirect: "error",
    });
    const payload = await response.json().catch(() => ({}));
    return { httpStatus: response.status, payload };
  } finally {
    clearTimeout(timeout);
    externalSignal?.removeEventListener("abort", abortFromExternal);
  }
}

async function sendTelegramMessage(chatId, message) {
  const result = await telegramApiRequest("sendMessage", { chat_id: chatId, text: message }, 8000);
  if (!result.payload.ok) throw new Error(result.payload.description ?? `Telegram trả HTTP ${result.httpStatus}.`);
  return result.payload.result;
}

function telegramAllowedChats() {
  return new Set(String(config.telegramAllowedChatId ?? "").split(/[\s,;]+/).map((value) => value.trim()).filter(Boolean));
}

function supportedDownloadUrl(value) {
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return false;
    const host = parsed.hostname.toLowerCase().replace(/^www\./, "");
    const path = parsed.pathname.toLowerCase();
    const hasHost = (domain) => host === domain || host.endsWith(`.${domain}`);
    if (config.downloadSettings.allowImageDownloads && /\.(?:avif|bmp|gif|jpe?g|png|webp|tiff?)$/i.test(path)) return true;
    const hasIdAfter = (marker, minimum = 4) => {
      const position = path.indexOf(marker);
      if (position < 0) return false;
      const rest = parsed.pathname.slice(position + marker.length).split(/[/?#&]/, 1)[0];
      return /^[A-Za-z0-9_-]+$/.test(rest) && rest.length >= minimum;
    };

    if (hasHost("youtu.be")) return /^[A-Za-z0-9_-]{6,}$/.test(path.split("/").filter(Boolean)[0] ?? "");
    if (hasHost("youtube.com")) {
      const videoId = parsed.searchParams.get("v") ?? "";
      return /^[A-Za-z0-9_-]{6,}$/.test(videoId) || /^\/(shorts|live|embed|v)\/[A-Za-z0-9_-]{6,}/.test(path);
    }
    if (hasHost("tiktok.com")) {
      if (/^\/@[^/]+\/video\/\d{8,}/.test(path)) return true;
      if (config.downloadSettings.allowImageDownloads && /^\/@[^/]+\/photo\/\d{8,}/.test(path)) return true;
      return (host.startsWith("vm.") || host.startsWith("vt.") || path.startsWith("/t/")) && hasIdAfter(host.startsWith("vm.") || host.startsWith("vt.") ? "/" : "/t/", 4);
    }
    if (hasHost("fb.watch")) return /^[A-Za-z0-9_-]{4,}$/.test(path.split("/").filter(Boolean)[0] ?? "");
    if (hasHost("facebook.com") || hasHost("fb.com")) {
      const videoId = parsed.searchParams.get("v") ?? parsed.searchParams.get("story_fbid") ?? "";
      return /^[A-Za-z0-9_-]{4,}$/.test(videoId)
        || hasIdAfter("/reel/", 4)
        || hasIdAfter("/videos/", 4)
        || hasIdAfter("/posts/", 4)
        || hasIdAfter("/share/r/", 4)
        || hasIdAfter("/share/v/", 4)
        || hasIdAfter("/share/p/", 4);
    }
    return false;
  } catch {
    return false;
  }
}

function validHttpUrl(value) {
  try {
    const parsed = new URL(value);
    return value.length <= 1024 && (parsed.protocol === "http:" || parsed.protocol === "https:") && !parsed.username && !parsed.password;
  } catch {
    return false;
  }
}

function canonicalDownloadUrl(value) {
  try {
    const parsed = new URL(value);
    parsed.hash = "";
    return parsed.toString();
  } catch {
    return String(value);
  }
}

function extractTelegramUrls(message) {
  const text = message.text ?? message.caption ?? "";
  const entities = message.entities ?? message.caption_entities ?? [];
  const candidates = [...text.matchAll(/https?:\/\/[^\s<>"']+/gi)].map((match) => match[0]);
  for (const entity of entities) {
    if (entity.type === "text_link" && entity.url) candidates.push(entity.url);
    if (entity.type === "url" && Number.isInteger(entity.offset) && Number.isInteger(entity.length)) {
      candidates.push(text.slice(entity.offset, entity.offset + entity.length));
    }
  }
  const unique = [...new Set(candidates.map((value) => value.replace(/[.,!?;:]+$/, "").replace(/[)\]}]+$/, "")).trim())];
  return unique.filter(supportedDownloadUrl).slice(0, 5);
}

async function processTelegramUpdate(update) {
  const message = update.message ?? update.channel_post;
  if (!message?.chat?.id) return;
  const chatId = String(message.chat.id);
  const text = message.text ?? message.caption ?? "";
  const command = text.match(/^\/(start|id|myid)(?:@\w+)?\b/i)?.[1]?.toLowerCase();
  const allowed = telegramAllowedChats();

  if (command === "start" || command === "id" || command === "myid") {
    if (allowed.size === 0) {
      telegramRuntime.state = "awaiting_chat_id";
      await sendTelegramMessage(chatId, `Chat ID của bạn: ${chatId}\nNhập ID này vào mục Telegram trên dashboard local để cấp quyền. Sau đó gửi /start lại để bật nhận URL.`);
    } else if (allowed.has(chatId)) {
      await sendTelegramMessage(chatId, "Bot đã sẵn sàng. Hãy chia sẻ URL TikTok, Facebook hoặc YouTube trong chat này.");
    } else {
      await sendTelegramMessage(chatId, "Chat này chưa được cấp quyền sử dụng bot.");
    }
    return;
  }

  if (!allowed.has(chatId)) {
    if (extractTelegramUrls(message).length > 0) {
      await sendTelegramMessage(chatId, allowed.size === 0
        ? `Chưa ghép nối chat này. Chat ID: ${chatId}. Nhập ID vào dashboard local trước khi gửi URL.`
        : "Chat này chưa được cấp quyền sử dụng bot.");
    }
    return;
  }

  const urls = extractTelegramUrls(message);
  if (urls.length === 0) return;
  const results = [];
  for (const url of urls) {
    const result = await requestDevice("/api/v1/jobs", "POST", {
      url,
      source: "telegram",
      telegramChatId: chatId,
      createdAt: new Date().toISOString(),
    }, 8000);
    if (result.status === 201) {
      results.push(`Đã đưa vào queue ESP32: #${result.payload.job?.id ?? "mới"}`);
    } else if (result.status === 409) {
      results.push("URL này đã có job đang chạy hoặc đang chờ.");
    } else {
      const reason = result.payload?.error?.message ?? `ESP32 trả HTTP ${result.status}`;
      results.push(`Không thêm được URL: ${reason}`);
    }
  }
  await sendTelegramMessage(chatId, results.join("\n"));
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function refreshTelegramDeviceOwner(force = false) {
  if (!force && Date.now() - telegramDeviceCheck.checkedAt < 5000) return telegramDeviceCheck;
  if (telegramDeviceCheckInFlight) return telegramDeviceCheckInFlight;

  telegramDeviceCheckInFlight = (async () => {
    try {
      const result = await requestDevice("/api/v1/system", "GET", undefined, 5000);
      const device = result.status >= 200 && result.status < 300 ? result.payload : null;
      telegramDeviceCheck = {
        checkedAt: Date.now(),
        deviceManaged: Boolean(device?.wifiConnected && device?.telegramConfigured),
        error: device ? "" : (result.payload?.error?.message ?? `ESP32 trả HTTP ${result.status}`),
      };
    } catch (error) {
      telegramDeviceCheck = {
        checkedAt: Date.now(),
        deviceManaged: false,
        error: error.name === "AbortError" ? "Không kết nối được ESP32 API" : error.message,
      };
    }
    if (telegramDeviceCheck.deviceManaged) telegramPollController?.abort();
    return telegramDeviceCheck;
  })().finally(() => {
    telegramDeviceCheckInFlight = null;
  });
  return telegramDeviceCheckInFlight;
}

async function hostShouldPollTelegram() {
  if (!config.telegramEnabled) return false;
  if (telegramAllowedChats().size > 0) return false;
  await refreshTelegramDeviceOwner();
  return !telegramDeviceCheck.deviceManaged;
}

async function runTelegramPoller() {
  if (telegramPollerRunning) return;
  telegramPollerRunning = true;
  while (true) {
    if (!config.telegramEnabled) {
      telegramRuntime.state = "disabled";
      telegramRuntime.lastError = "Nhận lệnh Telegram đang tắt trong cài đặt.";
      await wait(2000);
      continue;
    }
    if (!config.telegramBotToken) {
      telegramRuntime.state = "not_configured";
      telegramRuntime.lastError = "";
      await wait(2000);
      continue;
    }

    try {
      if (!await hostShouldPollTelegram()) {
        telegramRuntime.state = "device_managed";
        telegramRuntime.lastError = "ESP32 đang giữ quyền polling Telegram.";
        await wait(2500);
        continue;
      }

      const tokenStatus = await getTelegramStatus();
      if (!tokenStatus.tokenValid) throw new Error(tokenStatus.message || "Telegram token/API chưa sẵn sàng.");
      telegramRuntime.botUsername = tokenStatus.botUsername ?? telegramRuntime.botUsername;

      if (Date.now() - telegramWebhookCheckedAt > 60_000) {
        const webhook = await telegramApiRequest("getWebhookInfo");
        if (!webhook.payload.ok) throw new Error(webhook.payload.description ?? "Không đọc được webhook status.");
        telegramRuntime.webhookConfigured = Boolean(webhook.payload.result?.url);
        telegramWebhookCheckedAt = Date.now();
      }
      if (telegramRuntime.webhookConfigured) {
        telegramRuntime.state = "webhook_conflict";
        telegramRuntime.lastError = "Webhook đang cấu hình; hãy tắt webhook để dùng long polling.";
        await wait(10_000);
        continue;
      }
      telegramRuntime.state = telegramAllowedChats().size ? "polling" : "awaiting_chat_id";
      const query = {
        timeout: 20,
        limit: 20,
        offset: Math.max(0, Number(config.telegramUpdateOffset) || 0),
        allowed_updates: JSON.stringify(["message"]),
      };
      telegramPollController = new AbortController();
      const updates = await telegramApiRequest("getUpdates", query, 27_000, telegramPollController.signal);
      if (!config.telegramBotToken) continue;
      if (!updates.payload.ok || !Array.isArray(updates.payload.result)) {
        if (String(updates.payload.description ?? "").toLowerCase().includes("webhook")) {
          telegramRuntime.webhookConfigured = true;
          telegramRuntime.state = "webhook_conflict";
        }
        throw new Error(updates.payload.description ?? `getUpdates trả HTTP ${updates.httpStatus}.`);
      }
      telegramRuntime.lastError = "";
      for (const update of updates.payload.result) {
        try {
          await processTelegramUpdate(update);
        } catch (error) {
          telegramRuntime.lastError = error.message;
          telegramRuntime.state = "error";
          break;
        }
        config.telegramUpdateOffset = Number(update.update_id) + 1;
        telegramRuntime.lastUpdateAt = new Date().toISOString();
        await saveConfig();
      }
      if (!telegramRuntime.lastError) telegramRuntime.state = telegramAllowedChats().size ? "polling" : "awaiting_chat_id";
    } catch (error) {
      if (telegramPollController?.signal.aborted || !config.telegramBotToken) {
        telegramRuntime.state = config.telegramBotToken ? "checking" : "not_configured";
        telegramRuntime.lastError = "";
        continue;
      }
      telegramRuntime.lastError = error.message;
      if (!telegramRuntime.webhookConfigured) telegramRuntime.state = "error";
      await wait(3000);
    } finally {
      telegramPollController = null;
    }
  }
}

async function localStatus() {
  let device = { connected: false, apiReachable: false, authenticated: null, error: "Device has not responded" };
  try {
    const result = await requestDevice("/api/v1/system", "GET", undefined, 3500);
    if (result.status >= 200 && result.status < 300) {
      device = { connected: true, apiReachable: true, authenticated: true, ...result.payload };
    } else {
      const detail = result.payload?.error?.message ?? `Device returned HTTP ${result.status}`;
      device = {
        connected: false,
        apiReachable: true,
        authenticated: result.status === 401 ? false : null,
        apiStatus: result.status,
        error: result.status === 401 ? "Device API token không hợp lệ." : detail,
      };
    }
  } catch (error) {
    device = { connected: false, apiReachable: false, authenticated: null, error: error.name === "AbortError" ? "Device request timed out" : error.message };
  }
  if (device.connected) await pullDeviceSettingsToHost();
  const tool = await getYtDlpVersion();
  const outputStorage = await getOutputStorageStatus();
  const dashboardHistory = await getDashboardRuntimeHistory();
  return {
    host: { address: `http://${uiAddress}:${port}`, lanAddresses: getLanAddresses(), runtimeBaseUrl: config.runtimeBaseUrl, workerId, activeJobs: activeJobs.size, pendingJobs: runtimeQueue.filter((job) => ["QUEUED", "RETRY_WAIT"].includes(job.runtimeStatus)).length, settingsRevision: deviceSettingsRevision, settingsSyncPending: hostSettingsSyncPending || integrationsSyncPending },
    device: {
      ...device,
      apiTokenLast4: config.deviceToken.length >= 4 ? config.deviceToken.slice(-4) : "",
    },
    telegram: {
      ...(await getTelegramStatus()),
      tokenLast4: config.telegramBotToken.length >= 4 ? config.telegramBotToken.slice(-4) : "",
      configured: Boolean(config.telegramBotToken) || device.telegramConfigured === true,
      tokenValid: config.telegramBotToken ? telegramStatusCache.value?.tokenValid : (device.telegramConfigured ? true : undefined),
      botName: telegramStatusCache.value?.botName ?? (device.telegramConfigured ? "ESP32" : ""),
      pollingState: !config.telegramEnabled ? "disabled" : (device.telegramConfigured ? (device.telegramPollState === "ERROR" ? "error" : "device_managed") : (config.telegramBotToken ? telegramRuntime.state : "not_configured")),
      ownerChatConfigured: telegramAllowedChats().size > 0,
      lastUpdateAt: telegramRuntime.lastUpdateAt,
      lastError: telegramRuntime.lastError || (device.telegramLastError && device.telegramPollState === "ERROR" ? device.telegramLastError : ""),
    },
    runtime: { address: config.runtimeBaseUrl, online: Boolean(device.runtimeOnline), heartbeatIntervalMinutes: 5, queueSyncPending: hostSettingsSyncPending || integrationsSyncPending },
    downloader: { path: config.ytDlpPath, version: tool.version, error: tool.error, ffmpegPath: config.ffmpegPath, denoPath: config.denoPath, adaptive: getAdaptiveDownloadLimits(), settings: config.downloadSettings },
    output: { directory: config.outputDirectory, ...outputStorage },
    dashboardHistory,
    urlRetentionDays: config.urlRetentionDays,
  };
}

async function getDashboardRuntimeHistory() {
  const cutoff = Date.now() - 30 * 86400_000;
  const jobs = runtimeQueue.filter((job) => {
    if (!isTerminalRuntimeJob(job)) return false;
    const finishedAt = Date.parse(job.finishedAt ?? "") || 0;
    return finishedAt >= cutoff;
  });
  let changed = false;
  let completedCount = 0;
  let failedCount = 0;
  let completedBytes = 0;
  for (const job of jobs) {
    if (job.runtimeStatus === "COMPLETED") {
      completedCount++;
      if (!Number.isFinite(Number(job.outputBytes)) || job.outputBytesMeasured !== true) {
        const paths = [...new Set([...(Array.isArray(job.outputPaths) ? job.outputPaths : []), job.outputPath].filter((value) => typeof value === "string" && value.trim()))];
        let bytes = 0;
        for (const outputPath of paths) {
          try {
            const info = await fs.stat(outputPath);
            if (info.isFile()) bytes += info.size;
          } catch {}
        }
        job.outputBytes = bytes;
        job.outputBytesMeasured = true;
        changed = true;
      }
      completedBytes += Math.max(0, Number(job.outputBytes) || 0);
    } else if (job.runtimeStatus === "FAILED") {
      failedCount++;
    }
  }
  if (changed) {
    try { await saveRuntimeQueue(); } catch (error) { console.error(`Could not save measured output sizes: ${error.message}`); }
  }
  return {
    windowDays: 30,
    completedCount,
    failedCount,
    completedBytes,
    jobs: runtimeQueue.map((job) => ({
      id: job.id,
      url: job.url,
      source: job.source,
      status: job.runtimeStatus === "RUNNING" ? "DOWNLOADING" : (job.runtimeStatus === "RETRY_WAIT" ? "QUEUED" : job.runtimeStatus),
      outputPath: job.outputPath ?? "",
      outputBytes: job.runtimeStatus === "COMPLETED" ? Math.max(0, Number(job.outputBytes) || 0) : 0,
      error: job.error ?? "",
      createdAt: job.createdAt ?? "",
      finishedAt: job.finishedAt ?? "",
      terminalAt: Math.floor((Date.parse(job.finishedAt ?? "") || 0) / 1000),
    })),
  };
}

async function measureOutputDirectoryBytes(root, maxEntries = 100000) {
  const pending = [root];
  let bytes = 0;
  let visited = 0;
  while (pending.length && visited < maxEntries) {
    const current = pending.pop();
    let entries;
    try { entries = await fs.readdir(current, { withFileTypes: true }); } catch { continue; }
    for (const entry of entries) {
      visited++;
      if (visited >= maxEntries) break;
      const entryPath = path.join(current, entry.name);
      if (entry.isDirectory()) pending.push(entryPath);
      else if (entry.isFile()) {
        try { bytes += (await fs.stat(entryPath)).size; } catch {}
      }
    }
  }
  return { bytes, complete: visited < maxEntries };
}

async function getOutputStorageStatus(force = false, directory = config.outputDirectory) {
  const configuredDirectory = normalizeOutputDirectory(directory || config.outputDirectory);
  const cacheable = configuredDirectory === config.outputDirectory;
  if (cacheable && !force && outputStorageCache.value && Date.now() - outputStorageCache.checkedAt < 30_000) return outputStorageCache.value;
  const value = { writable: false, usedBytes: null, freeBytes: null, totalBytes: null, limitReached: false, lowDisk: false, error: "" };
  const outputDirectory = configuredDirectory;
  try {
    await fs.mkdir(outputDirectory, { recursive: true });
    await fs.access(outputDirectory, fsConstants.W_OK);
    value.writable = true;
    try {
      const stats = await fs.statfs(outputDirectory);
      value.totalBytes = Number(stats.blocks) * Number(stats.bsize);
      value.freeBytes = Number(stats.bavail) * Number(stats.bsize);
    } catch {}
    if (config.downloadSettings.maxOutputDirectoryGb > 0) {
      const measured = await measureOutputDirectoryBytes(outputDirectory);
      value.usedBytes = measured.bytes;
      value.usageEstimate = measured.complete ? "complete" : "partial";
      value.limitReached = measured.bytes >= config.downloadSettings.maxOutputDirectoryGb * 1024 ** 3;
    }
    const minimumFreeBytes = config.downloadSettings.minimumFreeDiskGb * 1024 ** 3;
    value.lowDisk = value.freeBytes !== null && config.downloadSettings.minimumFreeDiskGb > 0 && value.freeBytes < minimumFreeBytes;
  } catch (error) {
    value.error = error.message;
  }
  if (cacheable) outputStorageCache = { checkedAt: Date.now(), value };
  return value;
}

function publicConfig() {
  return {
    systemId: config.systemId,
    platform: process.platform,
    schemaVersion: config.schemaVersion,
    settingsRevision: deviceSettingsRevision,
    settingsSyncPending: hostSettingsSyncPending || integrationsSyncPending,
    deviceBaseUrl: config.deviceBaseUrl,
    workerName: config.workerName,
    runtimeBaseUrl: config.runtimeBaseUrl,
    deviceTokenConfigured: Boolean(config.deviceToken),
    deviceTokenLast4: config.deviceToken.length >= 4 ? config.deviceToken.slice(-4) : "",
    telegramTokenConfigured: Boolean(config.telegramBotToken) || deviceTelegramTokenConfigured,
    telegramTokenLast4: config.telegramBotToken.length >= 4 ? config.telegramBotToken.slice(-4) : "",
    telegramEnabled: config.telegramEnabled !== false,
    telegramChatIdConfigured: Boolean(config.telegramAllowedChatId),
    telegramAllowedChatIdLast4: config.telegramAllowedChatId.length >= 4 ? config.telegramAllowedChatId.slice(-4) : "",
    integrationsSyncPending,
    ytDlpPath: config.ytDlpPath,
    ffmpegPath: config.ffmpegPath,
    denoPath: config.denoPath,
    outputDirectory: config.outputDirectory,
    urlRetentionDays: config.urlRetentionDays,
    ddnsEnabled: Boolean(config.ddnsEnabled),
    ddnsDomain: config.ddnsDomain || "",
    ddnsToken: config.ddnsToken || "",
    ddnsTokenConfigured: Boolean(config.ddnsToken),
    gpioCommands: config.gpioCommands,
    allowedGpioPins: deviceAllowedGpioPins,
    downloadSettings: config.downloadSettings,
    securitySettings: {
      sessionTimeoutMinutes: config.sessionTimeoutMinutes,
      maxLoginAttempts: config.maxLoginAttempts,
      loginWindowMinutes: config.loginWindowMinutes,
    },
  };
}

async function runPcDependencyCheck() {
  if (pcCheckInFlight) return pcCheckInFlight;
  pcCheckInFlight = (async () => {
    const scriptPath = path.join(here, "Check-Install-Dependencies.ps1");
    const powershell = process.env.ESP_DOWNLOAD_POWERSHELL ?? "powershell.exe";
    const args = [
      "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
      "-File", scriptPath,
      "-YtDlpPath", config.ytDlpPath || "yt-dlp",
      "-OutputDirectory", config.outputDirectory,
      "-ToolsDirectory", path.join(dataRoot, "tools"),
    ];
    const { stdout } = await execFileAsync(powershell, args, {
      windowsHide: true,
      timeout: 15 * 60 * 1000,
      maxBuffer: 4 * 1024 * 1024,
    });
    let result;
    try {
      const jsonLine = stdout.trim().split(/\r?\n/).filter(Boolean).at(-1);
      result = JSON.parse(jsonLine ?? "{}");
    } catch {
      throw new Error("PowerShell không trả về kết quả kiểm tra hợp lệ.");
    }
    if (result.ytDlpPath) config.ytDlpPath = result.ytDlpPath;
    if (result.ffmpegPath) config.ffmpegPath = result.ffmpegPath;
    if (result.denoPath) config.denoPath = result.denoPath;
    toolVersionCache.checkedAt = 0;
    await saveConfig();
    return result;
  })();
  try {
    return await pcCheckInFlight;
  } finally {
    pcCheckInFlight = null;
  }
}

async function syncIntegrationsToDevice() {
  if (!config.deviceToken) {
    integrationsSyncPending = true;
    return false;
  }
  lastIntegrationSyncAt = Date.now();
  try {
    const payload = {
      telegramEnabled: config.telegramEnabled !== false,
      telegramAllowedChatIds: config.telegramAllowedChatId,
      runtimeBaseUrl: config.runtimeBaseUrl,
      urlRetentionDays: config.urlRetentionDays,
      gpioCommands: config.gpioCommands,
    };
    if (telegramTokenSyncRequested) {
      payload.telegramBotToken = config.telegramBotToken;
      payload.clearTelegramToken = !config.telegramBotToken;
    }
    const result = await requestDevice("/api/v1/settings/integrations", "POST", payload, 3000);
    integrationsSyncPending = result.status !== 200 || result.payload?.saved !== true;
    if (!integrationsSyncPending) {
      if (telegramTokenSyncRequested) telegramTokenSyncRequested = false;
      deviceTelegramTokenConfigured = result.payload?.telegramTokenConfigured === true;
    }
  } catch {
    integrationsSyncPending = true;
  }
  return !integrationsSyncPending;
}

function hostSettingsForDevice() {
  return {
    workerName: config.workerName,
    ytDlpPath: config.ytDlpPath,
    ffmpegPath: config.ffmpegPath,
    denoPath: config.denoPath,
    outputDirectory: config.outputDirectory,
    urlRetentionDays: config.urlRetentionDays,
    downloadSettings: config.downloadSettings,
    securitySettings: {
      sessionTimeoutMinutes: config.sessionTimeoutMinutes,
      maxLoginAttempts: config.maxLoginAttempts,
      loginWindowMinutes: config.loginWindowMinutes,
    },
  };
}

async function syncHostSettingsToDevice() {
  lastHostSettingsSyncAt = Date.now();
  if (!config.deviceToken) {
    hostSettingsSyncPending = true;
    config.hostSettingsSyncPending = true;
    await saveConfig();
    return false;
  }
  try {
    const result = await requestDevice("/api/v1/settings/shared", "PUT", {
      schemaVersion: 1,
      revision: deviceSettingsRevision,
      settings: hostSettingsForDevice(),
    }, 3000);
    hostSettingsSyncPending = result.status !== 200 || result.payload?.saved !== true;
    if (!hostSettingsSyncPending) {
      deviceSettingsRevision = Number(result.payload.revision ?? deviceSettingsRevision + 1);
      config.settingsRevision = deviceSettingsRevision;
    }
  } catch {
    hostSettingsSyncPending = true;
  }
  config.hostSettingsSyncPending = hostSettingsSyncPending;
  await saveConfig();
  return !hostSettingsSyncPending;
}

async function pullDeviceSettingsToHost() {
  if (!config.deviceToken || integrationsSyncPending || hostSettingsSyncPending) return false;
  try {
    const shared = await requestDevice("/api/v1/settings/shared", "GET", undefined, 1800);
    if (shared.status >= 200 && shared.status < 300) {
      const data = shared.payload ?? {};
      const remoteSettings = data.settings;
      if (data.initialized === true && remoteSettings && typeof remoteSettings === "object") {
        deviceSettingsRevision = Number(data.revision ?? 0);
        config.settingsRevision = deviceSettingsRevision;
        const remoteDownload = validateDownloadSettings(remoteSettings.downloadSettings, defaultConfig.downloadSettings);
        const remoteWorkerName = String(remoteSettings.workerName ?? "").trim();
        if (!/^[A-Za-z0-9._ -]{1,48}$/.test(remoteWorkerName)) throw new Error("Invalid worker name in ESP32 settings database.");
        const remoteYtDlpPath = String(remoteSettings.ytDlpPath ?? "yt-dlp").trim() || "yt-dlp";
        const remoteFfmpegPath = String(remoteSettings.ffmpegPath ?? "").trim();
        const remoteDenoPath = String(remoteSettings.denoPath ?? "").trim();
        const remoteOutputDirectory = normalizeOutputDirectory(String(remoteSettings.outputDirectory ?? config.outputDirectory));
        const remoteRetentionDays = Number(remoteSettings.urlRetentionDays ?? config.urlRetentionDays);
        if (!Number.isInteger(remoteRetentionDays) || remoteRetentionDays < 0 || remoteRetentionDays > 3650) throw new Error("Invalid URL retention in ESP32 settings database.");
        const remoteSecurity = remoteSettings.securitySettings ?? {};
        const sessionTimeoutMinutes = Number(remoteSecurity.sessionTimeoutMinutes ?? config.sessionTimeoutMinutes);
        const maxLoginAttempts = Number(remoteSecurity.maxLoginAttempts ?? config.maxLoginAttempts);
        const loginWindowMinutes = Number(remoteSecurity.loginWindowMinutes ?? config.loginWindowMinutes);
        if (!Number.isInteger(sessionTimeoutMinutes) || sessionTimeoutMinutes < 15 || sessionTimeoutMinutes > 1440 || !Number.isInteger(maxLoginAttempts) || maxLoginAttempts < 3 || maxLoginAttempts > 20 || !Number.isInteger(loginWindowMinutes) || loginWindowMinutes < 1 || loginWindowMinutes > 60) throw new Error("Invalid security policy in ESP32 settings database.");
        const outputDirectoryChanged = config.outputDirectory.toLowerCase() !== remoteOutputDirectory.toLowerCase();
        if (config.workerName !== remoteWorkerName || config.ytDlpPath !== remoteYtDlpPath || config.ffmpegPath !== remoteFfmpegPath || outputDirectoryChanged || config.urlRetentionDays !== remoteRetentionDays || config.sessionTimeoutMinutes !== sessionTimeoutMinutes || config.maxLoginAttempts !== maxLoginAttempts || config.loginWindowMinutes !== loginWindowMinutes || JSON.stringify(config.downloadSettings) !== JSON.stringify(remoteDownload)) {
          config.workerName = remoteWorkerName;
          workerId = remoteWorkerName;
          config.ytDlpPath = remoteYtDlpPath;
          config.ffmpegPath = remoteFfmpegPath;
          config.denoPath = remoteDenoPath;
          if (outputDirectoryChanged) config.outputDirectory = remoteOutputDirectory;
          config.urlRetentionDays = remoteRetentionDays;
          config.sessionTimeoutMinutes = sessionTimeoutMinutes;
          config.maxLoginAttempts = maxLoginAttempts;
          config.loginWindowMinutes = loginWindowMinutes;
          config.downloadSettings = remoteDownload;
          toolVersionCache.checkedAt = 0;
          outputStorageCache.checkedAt = 0;
          await saveConfig();
        }
      } else if (data.initialized !== true) {
        hostSettingsSyncPending = true;
        await syncHostSettingsToDevice();
      }
    } else if (shared.status === 404) {
      hostSettingsSyncPending = true;
      config.hostSettingsSyncPending = true;
      await saveConfig();
    }
    const result = await requestDevice("/api/v1/settings", "GET", undefined, 1800);
    if (result.status < 200 || result.status >= 300) return false;
    const integrations = result.payload?.integrations ?? {};
    if (Array.isArray(integrations.allowedGpioPins) &&
        integrations.allowedGpioPins.every((pin) => Number.isInteger(pin) && pin >= 0 && pin <= 48)) {
      deviceAllowedGpioPins = [...new Set(integrations.allowedGpioPins)];
    }
    deviceTelegramTokenConfigured = integrations.telegramTokenConfigured === true;
    let changed = false;
    const runtimeUrl = String(integrations.runtimeBaseUrl ?? "").trim();
    if (runtimeUrl) {
      const validated = validateRuntimeBaseUrl(runtimeUrl);
      if (config.runtimeBaseUrl !== validated) { config.runtimeBaseUrl = validated; changed = true; }
    }
    const chatIds = String(integrations.telegramAllowedChatIds ?? "").trim();
    if (config.telegramAllowedChatId !== chatIds) { config.telegramAllowedChatId = chatIds; changed = true; }
    const telegramEnabled = integrations.telegramEnabled !== false;
    if (config.telegramEnabled !== telegramEnabled) { config.telegramEnabled = telegramEnabled; changed = true; }
    const retention = Number(integrations.urlRetentionDays);
    if (Number.isInteger(retention) && retention >= 0 && retention <= 3650 && config.urlRetentionDays !== retention) {
      config.urlRetentionDays = retention;
      changed = true;
    }
    if (Array.isArray(integrations.gpioCommands)) {
      const deviceCommands = integrations.gpioCommands.map((entry) => ({
        command: String(entry.command ?? ""),
        pin: Number(entry.pin),
        pulseMs: Number(entry.pulseMs ?? 500),
        activeHigh: entry.activeHigh !== false,
      }));
      if (JSON.stringify(config.gpioCommands) !== JSON.stringify(deviceCommands)) {
        config.gpioCommands = deviceCommands;
        changed = true;
      }
    }
    if (changed) await saveConfig();
    const seedTelegramToken = !deviceTelegramTokenConfigured && Boolean(config.telegramBotToken);
    if (seedTelegramToken) {
      telegramTokenSyncRequested = true;
    }
    integrationsSyncPending = seedTelegramToken;
    return true;
  } catch {
    return false;
  }
}

async function updateProgress(jobId, progress, speed, eta) {
  const runtimeJob = runtimeQueue.find((job) => job.id === jobId);
  if (runtimeJob?.source === "schedule") {
    runtimeJob.progress = progress;
    runtimeJob.speed = speed;
    runtimeJob.eta = eta;
    return;
  }
  const body = { status: "DOWNLOADING", progress, speed, eta };
  try {
    await requestDevice(`/api/v1/worker/jobs/${encodeURIComponent(jobId)}/event`, "POST", body, 3000);
  } catch (error) {
    console.error(`Progress update failed for ${jobId}: ${error.message}`);
  }
}

async function persistScheduledRunState(job) {
  if (job?.source !== "schedule" || !job.scheduleId) return;
  const schedule = scheduledDownloads.find((item) => item.id === job.scheduleId);
  if (!schedule) return;
  schedule.lastJobId = job.id;
  schedule.lastRunStatus = job.runtimeStatus;
  try { await saveScheduledDownloads(); }
  catch (error) { console.error(`Scheduled download state save failed for ${job.id}: ${error.message}`); }
}

async function finishJob(job, child, state) {
  const active = activeJobs.get(job.id);
  if (active?.finished) return;
  if (active) {
    active.finished = true;
    if (active.timeoutTimer) clearTimeout(active.timeoutTimer);
  }
  if (active?.child === child) activeJobs.delete(job.id);
  recentDownloadOutcomes.push(Boolean(state.success));
  recentDownloadOutcomes = recentDownloadOutcomes.slice(-16);
  const queuedJob = runtimeQueue.find((candidate) => candidate.id === job.id);
  if (queuedJob && !state.success && !state.cancelled && Number(queuedJob.attempts ?? 0) < 2) {
    queuedJob.runtimeStatus = "RETRY_WAIT";
    queuedJob.errorStage = "ERROR1";
    queuedJob.error = state.error ?? "Download failed; scheduled for hourly retry.";
    queuedJob.retryAt = Date.now() + 3600_000;
    queuedJob.error = state.error ?? "Download failed; scheduled for retry.";
    try { await saveRuntimeQueue(); } catch (error) { console.error(`Retry state save failed for ${job.id}: ${error.message}`); }
    await persistScheduledRunState(queuedJob);
    return;
  }
  if (queuedJob) {
    queuedJob.runtimeStatus = state.cancelled ? "CANCELLED" : (state.success ? "COMPLETED" : (Number(queuedJob.attempts ?? 0) >= 2 ? "ERROR2" : "FAILED"));
    queuedJob.outputPath = state.outputPath ?? job.outputPath ?? "";
    queuedJob.outputPaths = [...new Set([...(Array.isArray(state.outputPaths) ? state.outputPaths : []), queuedJob.outputPath].filter((value) => typeof value === "string" && value.trim()))];
    if (state.success) {
      let outputBytes = Number(state.outputBytes);
      if (state.outputBytesMeasured !== true || !Number.isFinite(outputBytes)) {
        outputBytes = 0;
        for (const outputPath of queuedJob.outputPaths) {
          try {
            const outputStat = await fs.stat(outputPath);
            if (outputStat.isFile()) outputBytes += outputStat.size;
          } catch {}
        }
      }
      queuedJob.outputBytes = outputBytes;
      queuedJob.outputBytesMeasured = true;
    } else {
      queuedJob.outputBytes = 0;
      queuedJob.outputBytesMeasured = true;
    }
    queuedJob.error = state.error ?? "";
    queuedJob.finishedAt = new Date().toISOString();
    delete queuedJob.retryAt;
    try { await saveRuntimeQueue(); } catch (error) { console.error(`Runtime queue save failed for ${job.id}: ${error.message}`); }
    await persistScheduledRunState(queuedJob);
  }
  outputStorageCache.checkedAt = 0;
  if (job.source !== "schedule") {
    try {
      const result = await requestDevice(`/api/v1/worker/jobs/${encodeURIComponent(job.id)}/result`, "POST", { ...state, outputPaths: queuedJob?.outputPaths ?? state.outputPaths ?? [], outputBytes: queuedJob?.outputBytes ?? 0 }, 5000);
      if (result.status < 200 || result.status >= 300) throw new Error(`Device returned HTTP ${result.status}`);
      if (queuedJob) {
        queuedJob.deviceReported = true;
        await saveRuntimeQueue();
      }
    } catch (error) {
      console.error(`Result update failed for ${job.id}: ${error.message}`);
    }
  }
  if (job.telegramChatId) {
    const resultText = state.success
      ? `✅ Tải xong\n${job.outputPath ? path.basename(job.outputPath) : job.url}`
      : (state.cancelled ? `⏹ Đã dừng job ${job.id}.` : `❌ Job ${job.id} thất bại: ${state.error ?? "Không rõ lỗi"}`);
    try {
      await sendTelegramMessage(job.telegramChatId, resultText);
    } catch (error) {
      console.error(`Telegram result message failed for ${job.id}: ${error.message}`);
    }
  }
}

function parseProgress(line) {
  const match = line.match(/\[download\]\s+([\d.]+)%.*?(?:\s+at\s+(\S+))?(?:\s+ETA\s+(\S+))?/);
  if (!match) return null;
  return { progress: Math.max(0, Math.min(100, Number.parseFloat(match[1]))), speed: match[2] ?? "", eta: match[3] ?? "" };
}

function getAdaptiveDownloadLimits() {
  const settings = config.downloadSettings;
  if (!settings.adaptiveMode) return { jobs: settings.maxConcurrentJobs, fragments: settings.fragmentConcurrency, state: "static" };
  const recent = recentDownloadOutcomes.slice(-8);
  if (recent.length < 5) return { jobs: 1, fragments: Math.min(2, settings.fragmentConcurrency), state: "insufficient_data" };
  const failures = recent.filter((success) => !success).length;
  if (failures >= 2) return { jobs: 1, fragments: 1, state: "backoff" };
  if (recent.length >= 8 && failures === 0) return { jobs: settings.maxConcurrentJobs, fragments: settings.fragmentConcurrency, state: "stable" };
  return { jobs: Math.min(2, settings.maxConcurrentJobs), fragments: Math.min(4, settings.fragmentConcurrency), state: "ramping" };
}

function buildOutputTemplate(outputDirectory = config.outputDirectory) {
  const settings = config.downloadSettings;
  const folders = {
    flat: "",
    date: "%(upload_date>%Y-%m-%d)s",
    extractor: "%(extractor_key)s",
    extractor_date: path.join("%(extractor_key)s", "%(upload_date>%Y-%m-%d)s"),
  };
  let template = settings.filenameTemplate;
  if (settings.existingFilePolicy === "rename") template = template.replace("%(ext)s", "[%(epoch)s].%(ext)s");
  return path.join(outputDirectory, folders[settings.outputStructure], template);
}

function terminateProcessTree(child) {
  if (!child?.pid) return;
  if (process.platform === "win32") {
    const killer = spawn("taskkill.exe", ["/PID", String(child.pid), "/T", "/F"], { windowsHide: true, stdio: "ignore" });
    killer.on("error", () => child.kill("SIGTERM"));
  } else {
    child.kill("SIGTERM");
  }
}

const MEDIA_EXTENSIONS = new Set([".mp4", ".mkv", ".webm", ".flv", ".avi", ".ts", ".mov", ".m4a", ".mp3", ".opus", ".aac", ".wav", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp", ".tif", ".tiff"]);

async function statNonEmptyFile(filePath) {
  try {
    const stat = await fs.stat(filePath);
    return stat.isFile() && stat.size > 0 ? stat : null;
  } catch {
    return null;
  }
}

async function collectRecentFiles(directory, sinceMs, depth = 0, found = []) {
  if (depth > 3) return found;
  let entries;
  try { entries = await fs.readdir(directory, { withFileTypes: true }); } catch { return found; }
  for (const entry of entries) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      await collectRecentFiles(fullPath, sinceMs, depth + 1, found);
    } else if (entry.isFile() && MEDIA_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) {
      const stat = await statNonEmptyFile(fullPath);
      if (stat && stat.mtimeMs >= sinceMs) found.push({ path: fullPath, mtimeMs: stat.mtimeMs });
    }
  }
  return found;
}

// Xác minh file output. Nếu đường dẫn do yt-dlp báo không khớp (lệch mã hóa, đổi tên sau khi remux),
// quét thư mục đích tìm file media mới tạo trong lúc job chạy.
async function resolveJobOutputs(state, outputDirectory) {
  const verified = [];
  for (const reported of [...new Set(state.outputPaths ?? [])]) {
    if (await statNonEmptyFile(reported)) verified.push(reported);
  }
  if (verified.length) return verified;
  const since = (state.startedMs ?? Date.now()) - 5000;
  const recent = await collectRecentFiles(outputDirectory, since);
  recent.sort((a, b) => b.mtimeMs - a.mtimeMs);
  return recent.map((item) => item.path);
}

const NON_RETRYABLE_ERROR = /OUTPUT_VERIFY_FAILED|Cannot parse data|Unsupported URL|Video unavailable|Private video|Sign in to confirm|disabled in Download settings|requires gallery-dl|must be true\/false|Only HTTP and HTTPS/i;

function startDownload(job) {
  const tool = config.ytDlpPath || "yt-dlp";
  const scriptPath = path.join(here, "Invoke-YtDlp.ps1");
  const powershell = process.env.ESP_DOWNLOAD_POWERSHELL ?? "powershell.exe";
  const settings = config.downloadSettings;
  const outputDirectory = normalizeOutputDirectory(job.outputDirectory || config.outputDirectory);
  const adaptive = getAdaptiveDownloadLimits();
  const args = [
    "-NoLogo",
    "-NoProfile",
    "-NonInteractive",
    "-ExecutionPolicy",
    "Bypass",
    "-File",
    scriptPath,
    "-YtDlpPath",
    tool,
    "-FfmpegPath",
    config.ffmpegPath ?? "",
    "-DenoPath",
    config.denoPath ?? "",
    "-VideoQuality",
    settings.videoQuality,
    "-ThumbnailFormat",
    settings.thumbnailFormat,
    "-OutputTemplate",
    buildOutputTemplate(outputDirectory),
    "-MergeAudioVideo",
    String(settings.mergeAudioVideo),
    "-MergeOutputFormat",
    settings.mergeOutputFormat,
    "-FragmentConcurrency",
    String(adaptive.fragments),
    "-BandwidthLimit",
    settings.bandwidthLimit,
    "-PlaylistEnd",
    String(PROFILE_VIDEO_LIMIT),
    "-RetryLimit",
    String(settings.retryLimit),
    "-RetryBackoffSeconds",
    String(settings.retryBackoffSeconds),
    "-SocketTimeoutSeconds",
    String(settings.socketTimeoutSeconds),
    "-ExistingFilePolicy",
    settings.existingFilePolicy,
    "-AllowImageDownloads",
    String(settings.allowImageDownloads),
    "-ForceDownload",
    String(job.force === true),
    "-OutputDirectory",
    outputDirectory,
    "-Url",
    job.url,
  ];
  const state = {
    child: null,
    starting: true,
    cancelRequested: false,
    deviceManaged: job.source !== "schedule",
    lastProgressAt: 0,
    lastProgress: -5,
    outputPath: "",
    outputPaths: [],
    outputBytes: 0,
    outputBytesMeasured: false,
    stderr: "",
    outputLines: "",
    timedOut: false,
    finished: false,
    timeoutTimer: null,
    lineBuffers: { stdout: "", stderr: "" },
  };
  activeJobs.set(job.id, state);

  fs.mkdir(outputDirectory, { recursive: true })
    .then(() => {
      if (state.cancelRequested) {
        return finishJob(job, null, {
          success: false,
          cancelled: true,
          error: "Cancelled by user",
        });
      }
      let child;
      if (process.platform === "win32" && fsSync.existsSync(scriptPath)) {
        child = spawn(powershell, args, { cwd: outputDirectory, windowsHide: true, shell: false });
      } else {
        const binary = resolveExecutable("yt-dlp", config.ytDlpPath);
        const nativeArgs = [
          "--newline", "--no-warnings", "--progress",
          "-o", buildOutputTemplate(outputDirectory),
          "--print", "after_move:__OUTPUT__%(filepath)s"
        ];
        const formatSelector = settings.videoQuality === "1080p"
          ? (settings.mergeAudioVideo ? "bv*[height<=1080]+ba/b[height<=1080]" : "best[height<=1080]/best")
          : settings.videoQuality === "720p"
          ? (settings.mergeAudioVideo ? "bv*[height<=720]+ba/b[height<=720]" : "best[height<=720]/best")
          : settings.videoQuality === "audio"
          ? "bestaudio/best"
          : (settings.mergeAudioVideo ? "bv*+ba/b" : "best");
        nativeArgs.push("--format", formatSelector);
        if (settings.videoQuality === "audio") {
          nativeArgs.push("--extract-audio", "--audio-format", "mp3");
        } else if (settings.mergeAudioVideo && ["mp4", "flv", "avi", "mkv", "ts", "webm"].includes(settings.mergeOutputFormat)) {
          nativeArgs.push("--remux-video", settings.mergeOutputFormat);
        }
        if (settings.thumbnailFormat !== "off") {
          nativeArgs.push("--write-thumbnail");
          if (["jpg", "png", "webp"].includes(settings.thumbnailFormat)) {
            nativeArgs.push("--convert-thumbnails", settings.thumbnailFormat);
          }
        }
        nativeArgs.push(
          "--concurrent-fragments", String(adaptive.fragments),
          "--retries", String(settings.retryLimit),
          "--fragment-retries", String(settings.retryLimit),
          "--file-access-retries", String(settings.retryLimit),
          "--socket-timeout", String(settings.socketTimeoutSeconds),
          "--retry-sleep", `http:exp=${settings.retryBackoffSeconds}:60`,
          "--retry-sleep", `fragment:exp=${settings.retryBackoffSeconds}:60`
        );
        if (settings.bandwidthLimit && settings.bandwidthLimit.trim()) {
          nativeArgs.push("--limit-rate", settings.bandwidthLimit.trim());
        }
        if (PROFILE_VIDEO_LIMIT > 0) {
          nativeArgs.push("--playlist-end", String(PROFILE_VIDEO_LIMIT));
        }
        if (job.force === true || settings.existingFilePolicy === "overwrite") {
          nativeArgs.push("--force-overwrites");
        } else {
          nativeArgs.push("--no-overwrites");
        }
        if (config.ffmpegPath) nativeArgs.push("--ffmpeg-location", config.ffmpegPath);
        if (config.denoPath) nativeArgs.push("--js-runtimes", `deno:${config.denoPath}`);
        nativeArgs.push(job.url);

        child = spawn(binary, nativeArgs, { cwd: outputDirectory, shell: false });
      }
      state.child = child;
      state.startedMs = Date.now();
      state.starting = false;
      if (settings.jobTimeoutMinutes > 0) {
        state.timeoutTimer = setTimeout(() => {
          state.timedOut = true;
          terminateProcessTree(state.child);
        }, settings.jobTimeoutMinutes * 60 * 1000);
        state.timeoutTimer.unref?.();
      }

      const consume = (data, isError = false) => {
        const text = data.toString("utf8");
        const streamName = isError ? "stderr" : "stdout";
        if (isError) state.stderr = (state.stderr + text).slice(-4000);
        const buffered = state.lineBuffers[streamName] + text;
        const lines = buffered.split(/\r?\n/);
        state.lineBuffers[streamName] = lines.pop() ?? "";
        for (const line of lines) {
          state.outputLines = `${state.outputLines}${line}\n`.slice(-4000);
          if (line.startsWith("__OUTPUT__")) {
            state.outputPath = line.slice("__OUTPUT__".length).trim();
            if (state.outputPath) state.outputPaths.push(state.outputPath);
          }
          const parsed = parseProgress(line);
          if (!parsed) continue;
          const now = Date.now();
          if (parsed.progress - state.lastProgress >= 5 || now - state.lastProgressAt >= 4000) {
            state.lastProgress = parsed.progress;
            state.lastProgressAt = now;
            void updateProgress(job.id, parsed.progress, parsed.speed, parsed.eta);
          }
        }
      };

      child.stdout.on("data", (data) => consume(data));
      child.stderr.on("data", (data) => consume(data, true));
      child.on("error", (error) => {
        void finishJob(job, child, {
          success: false,
          error: state.timedOut ? "DOWNLOAD_TIMEOUT: Job exceeded the configured time limit." : (error.code === "ENOENT" ? "POWERSHELL_NOT_FOUND: Windows PowerShell was not found" : error.message),
        });
      });
      child.on("close", (code) => {
        if (activeJobs.get(job.id)?.child !== child) return;
        for (const streamName of ["stdout", "stderr"]) {
          const trailingLine = state.lineBuffers[streamName];
          if (trailingLine.startsWith("__OUTPUT__")) {
            state.outputPath = trailingLine.slice("__OUTPUT__".length).trim();
            if (state.outputPath) state.outputPaths.push(state.outputPath);
          }
          state.lineBuffers[streamName] = "";
        }
        if (state.cancelRequested) {
          void finishJob(job, child, { success: false, cancelled: true, error: "Cancelled by user" });
        } else if (state.timedOut) {
          void finishJob(job, child, { success: false, error: "DOWNLOAD_TIMEOUT: Job exceeded the configured time limit." });
        } else if (code === 0) {
          void (async () => {
            let result;
            try {
              if (settings.verifyOutput) {
                const resolved = await resolveJobOutputs(state, outputDirectory);
                if (!resolved.length) throw new Error(`Output file not found: ${state.outputPath || "(no path reported)"}`);
                state.outputPath = resolved[0];
                state.outputPaths = resolved;
              }
              if (!result) result = { success: true, outputPath: state.outputPath, outputPaths: state.outputPaths };
            } catch (error) {
              result = {
                success: false,
                cancelled: state.cancelRequested,
                error: state.cancelRequested
                  ? "Cancelled by user"
                  : (state.timedOut
                    ? "DOWNLOAD_TIMEOUT: Job exceeded the configured time limit."
                    : `OUTPUT_VERIFY_FAILED: ${error.message}`),
                outputPath: state.outputPath,
              };
            }
            await finishJob(job, state.child, result);
          })();
        } else {
          const detail = (state.stderr || state.outputLines).trim().split(/\r?\n/).slice(-3).join(" | ");
          void finishJob(job, child, { success: false, error: detail || `PowerShell/yt-dlp exited with code ${code}` });
        }
      });
    })
    .catch((error) => {
      void finishJob(job, null, {
        success: false,
        error: `OUTPUT_PATH_ERROR: ${error.message}`,
      });
    });
}

async function workerTick() {
  if (pollBusy) return;
  pollBusy = true;
  try {
    if (config.deviceToken && (integrationsSyncPending || hostSettingsSyncPending) && Date.now() - Math.max(lastIntegrationSyncAt, lastHostSettingsSyncAt) >= 30_000) {
      if (integrationsSyncPending) await syncIntegrationsToDevice();
      if (hostSettingsSyncPending) await syncHostSettingsToDevice();
    }
    if (config.deviceToken) {
      try {
        const deviceJobs = await requestDevice("/api/v1/jobs", "GET", undefined, 5000);
        if (deviceJobs.status === 200 && Array.isArray(deviceJobs.payload.jobs)) {
          const authoritativeIds = new Set(deviceJobs.payload.jobs.map((job) => String(job.id)));
          for (const [id, active] of activeJobs) {
            if (authoritativeIds.has(id)) continue;
            active.cancelRequested = true;
            terminateProcessTree(active.child);
          }
          const beforeCount = runtimeQueue.length;
          runtimeQueue = runtimeQueue.filter((job) => {
            if (isTerminalRuntimeJob(job)) return true;
            if (activeJobs.has(job.id)) return authoritativeIds.has(job.id);
            return authoritativeIds.has(job.id);
          });
          for (const dJob of deviceJobs.payload.jobs) {
            const dId = String(dJob.id);
            if (["QUEUED", "DISPATCHED"].includes(dJob.status) && !runtimeQueue.some((q) => q.id === dId)) {
              runtimeQueue.push({
                id: dId,
                url: String(dJob.url || ""),
                source: String(dJob.source || "telegram"),
                telegramChatId: String(dJob.telegramChatId || ""),
                deviceAttempts: Number(dJob.attempts || 0),
                createdAt: String(dJob.createdAt || new Date().toISOString()),
                runtimeStatus: "QUEUED",
                force: Boolean(dJob.force),
                outputPath: "",
                error: "",
                deviceReported: false,
                receivedAt: new Date().toISOString(),
              });
              console.log(`Pulled pending ESP32 job ${dId} into Windows runtime queue.`);
            }
          }
          if (runtimeQueue.length !== beforeCount) await saveRuntimeQueue();
        }
      } catch {
        // An unavailable ESP32 must not cause runtime execution copies to be
        // mistaken for deletions. Reconciliation is retried on the next tick.
      }
    }
    for (const [id, active] of activeJobs) {
      if (active.deviceManaged === false || !config.deviceToken) continue;
      try {
        const result = await requestDevice(`/api/v1/jobs/${encodeURIComponent(id)}`, "GET", undefined, 5000);
        if (result.status === 200 && result.payload.job?.status === "CANCEL_REQUESTED") {
          active.cancelRequested = true;
          terminateProcessTree(active.child);
        }
      } catch {
        // The next poll retries; active downloader processes remain alive during short LAN outages.
      }
    }
    for (const job of runtimeQueue) {
      if (config.deviceToken && !job.deviceReported && ["COMPLETED", "FAILED", "ERROR2", "CANCELLED"].includes(job.runtimeStatus)) {
        try {
          const result = await requestDevice(`/api/v1/worker/jobs/${encodeURIComponent(job.id)}/result`, "POST", {
            success: job.runtimeStatus === "COMPLETED",
            cancelled: job.runtimeStatus === "CANCELLED",
            outputPath: job.outputPath ?? "",
            outputBytes: job.outputBytes ?? 0,
            error: job.error ?? "",
          }, 5000);
          if (result.status === 200) {
            job.deviceReported = true;
            await saveRuntimeQueue();
          }
        } catch {
          // A result remains in the runtime's durable queue and is retried after the device returns.
          break;
        }
      }
    }
    const now = Date.now();
    let retryPromoted = false;
    for (const job of runtimeQueue) {
      if (job.runtimeStatus === "RETRY_WAIT" && Number(job.retryAt ?? 0) <= now) {
        job.runtimeStatus = "QUEUED";
        delete job.retryAt;
        retryPromoted = true;
      }
    }
    if (retryPromoted) await saveRuntimeQueue();
    const adaptiveLimits = getAdaptiveDownloadLimits();
    if (activeJobs.size >= adaptiveLimits.jobs) return;
    const tool = await getYtDlpVersion();
    if (!tool.version) return;
    const next = runtimeQueue.find((job) => job.runtimeStatus === "QUEUED" && !activeJobs.has(job.id));
    if (!next) return;
    const storageStatus = await getOutputStorageStatus(false, next.outputDirectory || config.outputDirectory);
    if (!storageStatus.writable || storageStatus.limitReached || storageStatus.lowDisk) return;
    if (next.source !== "schedule" && config.deviceToken) {
      try {
        const deviceJob = await requestDevice(`/api/v1/jobs/${encodeURIComponent(next.id)}`, "GET", undefined, 1800);
        if (deviceJob.status === 200 && ["CANCEL_REQUESTED", "CANCELLED"].includes(deviceJob.payload.job?.status)) {
          next.runtimeStatus = "CANCELLED";
          next.error = "Cancelled before the runtime started the download";
          next.finishedAt = new Date().toISOString();
          await saveRuntimeQueue();
          await requestDevice(`/api/v1/worker/jobs/${encodeURIComponent(next.id)}/result`, "POST", { success: false, cancelled: true, error: next.error }, 3000).catch(() => {});
          next.deviceReported = true;
          await saveRuntimeQueue();
          return;
        }
      } catch {
        // Best-effort cancellation check; proceed with download if device is temporarily busy
      }
    }
    next.runtimeStatus = "RUNNING";
    next.workerId = workerId;
    next.attempts = Number(next.attempts ?? 0) + 1;
    next.startedAt = new Date().toISOString();
    try {
      await saveRuntimeQueue();
    } catch {
      next.runtimeStatus = "QUEUED";
      return;
    }
    startDownload(next);
  } catch {
    // A later tick retries persisted work and device synchronization.
  } finally {
    pollBusy = false;
  }
}

function contentType(filePath) {
  const extension = path.extname(filePath).toLowerCase();
  return ({
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".ico": "image/x-icon",
  })[extension] ?? "application/octet-stream";
}

async function serveStatic(request, response, pathOverride = null) {
  const pathname = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
  const wanted = pathOverride ?? (pathname === "/" ? "/index.html" : pathname);
  const filePath = path.resolve(publicRoot, `.${wanted}`);
  if (!filePath.startsWith(`${publicRoot}${path.sep}`) && filePath !== publicRoot) {
    textResponse(response, 403, "Forbidden");
    return;
  }
  try {
    const content = await fs.readFile(filePath);
    response.writeHead(200, { "Content-Type": contentType(filePath), "Cache-Control": "no-store" });
    response.end(content);
  } catch {
    textResponse(response, 404, "Not found");
  }
}

function isPrivatePeerAddress(address) {
  const ip = String(address ?? "").replace(/^::ffff:/i, "");
  const parts = ip.split(".").map(Number);
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) return false;
  return parts[0] === 10 || parts[0] === 192 && parts[1] === 168 || parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31;
}

function hasValidDeviceToken(request) {
  const supplied = Buffer.from(String(request.headers["x-device-token"] ?? ""));
  const expected = Buffer.from(String(config.deviceToken ?? ""));
  return expected.length > 0 && supplied.length === expected.length && timingSafeEqual(supplied, expected);
}

async function handleRuntimeRequest(request, response, pathname) {
  if (!isPrivatePeerAddress(request.socket.remoteAddress)) {
    jsonResponse(response, 403, { error: "Runtime endpoint accepts private LAN clients only." });
    return;
  }
  if (!hasValidDeviceToken(request)) {
    jsonResponse(response, 401, { error: "A valid X-Device-Token is required." });
    return;
  }
  const incomingRemote = request.socket.remoteAddress;
  const incomingIp = incomingRemote?.startsWith("::ffff:") ? incomingRemote.slice(7) : incomingRemote;
  if (incomingIp && isPrivateIpv4Address(incomingIp)) {
    const incomingBase = `http://${incomingIp}`;
    lastKnownDeviceBaseUrl = incomingBase;
    if (config.deviceBaseUrl !== incomingBase) {
      config.deviceBaseUrl = incomingBase;
      void saveConfig();
    }
  }
  if (pathname === "/api/runtime/heartbeat" && request.method === "GET") {
    const tool = await getYtDlpVersion();
    const outputWritable = (await getOutputStorageStatus()).writable;
    jsonResponse(response, 200, {
      online: true,
      workerReady: Boolean(tool.version && outputWritable),
      pendingJobs: runtimeQueue.filter((job) => job.runtimeStatus === "QUEUED").length,
      activeJobs: activeJobs.size,
      workerId,
    });
    return;
  }
  if (pathname.startsWith("/api/runtime/jobs/") && request.method === "DELETE") {
    const id = decodeURIComponent(pathname.slice("/api/runtime/jobs/".length));
    if (!id) {
      jsonResponse(response, 400, { error: "Job ID is required." });
      return;
    }
    const active = activeJobs.get(id);
    if (active) {
      active.cancelRequested = true;
      terminateProcessTree(active.child);
    }
    const beforeCount = runtimeQueue.length;
    runtimeQueue = runtimeQueue.filter((job) => job.id !== id);
    await saveRuntimeQueue();
    jsonResponse(response, 200, { deleted: true, id, count: beforeCount - runtimeQueue.length });
    return;
  }
  if (pathname === "/api/runtime/jobs" && request.method === "POST") {
    try {
      const body = await readJsonBody(request, 32 * 1024);
      const source = body.job ?? body;
      const id = String(source.id ?? "").trim();
      const url = String(source.url ?? "").trim();
      const sourceName = String(source.source ?? "telegram");
      if (!/^[A-Za-z0-9_-]{1,40}$/.test(id)) throw new Error("Invalid job ID.");
      if (!validHttpUrl(url) || sourceName === "telegram" && !supportedDownloadUrl(url)) throw new Error("URL is not valid for this job source.");
      const existing = runtimeQueue.find((job) => job.id === id);
      if (existing) {
        const deviceAttempts = Number(source.attempts ?? 0);
        if (["FAILED", "CANCELLED"].includes(existing.runtimeStatus) && deviceAttempts > Number(existing.deviceAttempts ?? 0)) {
          existing.runtimeStatus = "QUEUED";
          existing.deviceAttempts = deviceAttempts;
          existing.outputPath = "";
          existing.outputPaths = [];
          existing.outputBytes = 0;
          existing.outputBytesMeasured = false;
          existing.error = "";
          existing.deviceReported = false;
          existing.receivedAt = new Date().toISOString();
          delete existing.finishedAt;
          await saveRuntimeQueue();
          jsonResponse(response, 202, { accepted: true, duplicate: false, retry: true, jobId: id });
          return;
        }
        if (existing.runtimeStatus === "COMPLETED") {
          jsonResponse(response, 200, {
            accepted: true,
            duplicate: true,
            alreadyCompleted: true,
            status: "COMPLETED",
            outputPath: existing.outputPath ?? "",
            outputBytes: existing.outputBytes ?? 0,
            jobId: id,
          });
          return;
        }
        jsonResponse(response, 200, { accepted: true, duplicate: true, jobId: id });
        return;
      }
      if (config.downloadSettings.duplicateUrlPolicy === "skip_pending" && source.force !== true) {
        const canonical = canonicalDownloadUrl(url);
        const duplicate = runtimeQueue.find((job) => canonicalDownloadUrl(job.url) === canonical
          && !["FAILED", "CANCELLED"].includes(job.runtimeStatus));
        if (duplicate) {
          const skipped = {
            id,
            url,
            source: sourceName,
            telegramChatId: String(source.telegramChatId ?? ""),
            deviceAttempts: Number(source.attempts ?? 0),
            attempts: 0,
            createdAt: String(source.createdAt ?? new Date().toISOString()),
            runtimeStatus: "FAILED",
            outputPath: "",
            error: `DUPLICATE_URL: Skipped because job ${duplicate.id} already contains this URL.`,
            deviceReported: false,
            receivedAt: new Date().toISOString(),
            finishedAt: new Date().toISOString(),
          };
          runtimeQueue.push(skipped);
          await saveRuntimeQueue();
          jsonResponse(response, 202, { accepted: true, duplicate: true, skipped: true, duplicateJobId: duplicate.id, jobId: id });
          return;
        }
      }
      while (runtimeQueue.length >= 90) {
        const expiredIndex = runtimeQueue.findIndex((job) => job.deviceReported && ["COMPLETED", "FAILED", "CANCELLED"].includes(job.runtimeStatus));
        if (expiredIndex < 0) break;
        runtimeQueue.splice(expiredIndex, 1);
      }
      if (runtimeQueue.length >= 100) {
        jsonResponse(response, 507, { error: "Windows runtime queue is full." });
        return;
      }
      const job = {
        id,
        url,
        source: sourceName,
        telegramChatId: String(source.telegramChatId ?? ""),
        deviceAttempts: Number(source.attempts ?? 0),
        createdAt: String(source.createdAt ?? new Date().toISOString()),
        runtimeStatus: "QUEUED",
        force: source.force === true,
        outputPath: "",
        error: "",
        deviceReported: false,
        receivedAt: new Date().toISOString(),
      };
      runtimeQueue.push(job);
      try {
        await saveRuntimeQueue();
      } catch (error) {
        runtimeQueue.pop();
        throw new Error(`Could not persist runtime job: ${error.message}`);
      }
      console.log(`Accepted ESP32 job ${id} into the persistent Windows queue.`);
      jsonResponse(response, 202, { accepted: true, duplicate: false, jobId: id });
    } catch (error) {
      jsonResponse(response, 400, { error: error.message });
    }
    return;
  }
  jsonResponse(response, 404, { error: "Unknown runtime endpoint." });
}

async function handleRequest(request, response) {
  const pathname = new URL(request.url, "http://localhost").pathname;
  if (pathname.startsWith("/api/runtime/")) {
    await handleRuntimeRequest(request, response, pathname);
    return;
  }
  if (pathname === "/api/mobile/health" && request.method === "GET") {
    if (!isLoopbackPeerAddress(request.socket.remoteAddress) && !isPrivatePeerAddress(request.socket.remoteAddress)) {
      jsonResponse(response, 403, { error: "Mobile pairing is available on the private LAN only." });
      return;
    }
    jsonResponse(response, 200, { service: "esp-download-local-host", apiVersion: 1, hostName: os.hostname(), port });
    return;
  }
  const loopbackPeer = isLoopbackPeerAddress(request.socket.remoteAddress);
  const isPrivateOrLoopback = loopbackPeer || isPrivatePeerAddress(request.socket.remoteAddress);
  // Cho phép truy cập từ xa (khi NAT port / Dynamic DNS) với điều kiện bắt buộc phải đăng nhập tài khoản/mật khẩu
  // để xác thực phiên (session). Các route bên dưới đều được bảo vệ bởi getSession() và rate limit.
  const mobileLogin = pathname === "/api/mobile/auth/login" && request.method === "POST";
  if (mobileLogin) {
    await handleLogin(request, response, { includeSessionToken: true });
    return;
  }
  if (pathname === "/api/auth/login" && request.method === "POST") {
    await handleLogin(request, response);
    return;
  }
  if (pathname === "/api/auth/session" && request.method === "GET") {
    const session = getSession(request);
    jsonResponse(response, 200, { authenticated: Boolean(session), username: session?.username ?? "", defaultPassword: Boolean(session) && config.authUsername === "admin" && passwordMatches("admin") });
    return;
  }
  if (pathname === "/login" || pathname === "/login.html") {
    if (getSession(request)) {
      response.writeHead(302, { Location: "/", "Cache-Control": "no-store" });
      response.end();
    } else {
      await serveStatic(request, response, "/login.html");
    }
    return;
  }
  if (["/login.css", "/login.js"].includes(pathname)) {
    await serveStatic(request, response);
    return;
  }
  if (pathname === "/esp-download.apk" || pathname === "/esp-download-clean-test.apk") {
    const isClean = pathname.includes("clean-test");
    const targetPath = isClean
      ? path.join(androidApkRoot, "freshTest", "debug", "app-freshTest-debug.apk")
      : path.join(androidApkRoot, "standard", "debug", "app-standard-debug.apk");
    try {
      const stats = await fs.stat(targetPath);
      response.writeHead(200, {
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Length": stats.size,
        "Content-Disposition": `attachment; filename="${isClean ? "esp-download-clean-test.apk" : "esp-download.apk"}"`,
        "Cache-Control": "no-store",
      });
      const stream = fsSync.createReadStream(targetPath);
      stream.pipe(response);
      return;
    } catch {
      textResponse(response, 404, "Chưa build file APK. Hãy chạy build_apk.ps1 trong mobile/android.");
      return;
    }
  }

  const session = getSession(request);
  if (!session) {
    if (pathname.startsWith("/api/")) {
      jsonResponse(response, 401, { error: "Đăng nhập để tiếp tục.", code: "LOGIN_REQUIRED" });
    } else if (request.method === "GET" || request.method === "HEAD") {
      await serveStatic(request, response, "/login.html");
    } else {
      jsonResponse(response, 401, { error: "Đăng nhập để tiếp tục.", code: "LOGIN_REQUIRED" });
    }
    return;
  }
  if (pathname === "/api/auth/logout" && request.method === "POST") {
    sessions.delete(session.token);
    clearSessionCookie(response);
    jsonResponse(response, 200, { loggedOut: true });
    return;
  }
  if (pathname === "/api/local/account" && request.method === "PUT") {
    await handleAccountUpdate(request, response);
    return;
  }
  if (pathname === "/api/local/config" && request.method === "GET") {
    await pullDeviceSettingsToHost();
    jsonResponse(response, 200, publicConfig());
    return;
  }
  if (pathname === "/api/local/device-pairing-qr.png" && request.method === "GET") {
    if (!config.deviceToken) {
      jsonResponse(response, 409, { error: "Hãy lưu Device API token trước khi tạo mã ghép nối." });
      return;
    }
    let deviceApiUrl = config.deviceBaseUrl;
    try {
      const system = await requestDevice("/api/v1/system", "GET", undefined, 2500);
      const payload = system.status === 200 ? system.payload : null;
      const deviceIp = String(payload?.wifiIp || payload?.ipAddress || "").trim();
      if (payload?.wifiConnected === true && isPrivateIpv4Address(deviceIp)) {
        deviceApiUrl = `http://${deviceIp}`;
      }
    } catch {}
    const hostAddress = getLanAddresses()[0] ?? "127.0.0.1";
    const hostUrl = config.runtimeBaseUrl || `http://${hostAddress}:${port}`;
    const hostSessionToken = randomBytes(32).toString("hex");
    sessions.set(hostSessionToken, {
      username: session?.username || config.authUsername,
      expiresAt: Date.now() + config.sessionTimeoutMinutes * 60 * 1000,
      qrBootstrap: true,
    });
    const pairing = {
      type: "esp-download-device-pairing",
      version: 1,
      deviceApiUrl,
      deviceApiToken: config.deviceToken,
      apUrl: "http://192.168.4.1",
      hostUrl,
      hostUsername: session?.username || config.authUsername,
      hostSessionToken,
      hostPassword: hostSessionToken,
    };
    try {
      const png = await QRCode.toBuffer(JSON.stringify(pairing), {
        type: "png",
        errorCorrectionLevel: "M",
        margin: 4,
        width: 640,
        color: { dark: "#17392e", light: "#ffffff" },
      });
      response.writeHead(200, {
        "Content-Type": "image/png",
        "Content-Length": png.length,
        "Content-Disposition": "attachment; filename=esp-download-device-api.png",
        "Cache-Control": "no-store, private",
        "X-Content-Type-Options": "nosniff",
      });
      response.end(png);
    } catch (error) {
      jsonResponse(response, 500, { error: `Không tạo được QR pairing: ${error.message}` });
    }
    return;
  }
  if (pathname === "/api/local/config" && request.method === "PUT") {
    try {
      const body = await readJsonBody(request);
      const nextDeviceBaseUrl = validateDeviceBaseUrl(String(body.deviceBaseUrl ?? config.deviceBaseUrl));
      const nextRuntimeBaseUrl = validateRuntimeBaseUrl(String(body.runtimeBaseUrl ?? config.runtimeBaseUrl));
      const nextYtDlpPath = String(body.ytDlpPath ?? config.ytDlpPath).trim() || "yt-dlp";
      const nextFfmpegPath = String(body.ffmpegPath ?? config.ffmpegPath).trim();
      const nextDenoPath = String(body.denoPath ?? config.denoPath).trim();
      const nextOutputDirectory = normalizeOutputDirectory(String(body.outputDirectory ?? config.outputDirectory));
      const nextWorkerName = String(body.workerName ?? config.workerName).trim();
      if (!/^[A-Za-z0-9._ -]{1,48}$/.test(nextWorkerName)) throw new Error("Tên Windows worker dài tối đa 48 ký tự và chỉ dùng chữ, số, khoảng trắng, dấu chấm, gạch dưới hoặc gạch ngang.");
      const nextTelegramEnabled = typeof body.telegramEnabled === "boolean" ? body.telegramEnabled : config.telegramEnabled !== false;
      const nextRetentionDays = Number(body.urlRetentionDays ?? config.urlRetentionDays);
      if (!Number.isInteger(nextRetentionDays) || nextRetentionDays < 0 || nextRetentionDays > 3650) {
        throw new Error("Thời hạn tự xóa phải là số nguyên từ 0 đến 3650 ngày; 0 nghĩa là không tự xóa.");
      }
      let nextGpioCommands = config.gpioCommands;
      if (body.gpioCommands !== undefined) {
        if (!Array.isArray(body.gpioCommands) || body.gpioCommands.length > 8) throw new Error("Có thể cấu hình tối đa 8 lệnh GPIO.");
        const seenCommands = new Set();
        const validPins = deviceAllowedGpioPins;
        nextGpioCommands = body.gpioCommands.map((entry) => {
          const command = String(entry.command ?? "").trim().toLowerCase();
          const trigger = String(entry.trigger ?? "").trim();
          const replyMessage = String(entry.replyMessage ?? "").trim();
          const pin = Number(entry.pin);
          const pulseMs = Number(entry.pulseMs ?? 0);
          if (command && !/^[a-z0-9_-]{1,32}$/i.test(command)) throw new Error("Tên lệnh GPIO chỉ chứa chữ, số, gạch ngang hoặc gạch dưới.");
          if (command && seenCommands.has(command)) throw new Error(`Lệnh GPIO bị lặp: ${command}.`);
          if (command) seenCommands.add(command);
          if (!validPins.includes(pin)) throw new Error(`Chân GPIO ${pin} không nằm trong danh sách hỗ trợ.`);
          if (!Number.isInteger(pulseMs) || pulseMs < 0 || pulseMs > 10000) throw new Error("Thời gian xung GPIO phải từ 0 (toggle) đến 10000 ms.");
          return { command, trigger, pin, pulseMs, activeHigh: entry.activeHigh !== false, replyMessage };
        });
      }
      let nextDeviceToken = config.deviceToken;
      if (typeof body.deviceToken === "string" && body.deviceToken.trim()) nextDeviceToken = body.deviceToken.trim();
      if (body.clearDeviceToken === true) nextDeviceToken = "";
      const previousTelegramToken = config.telegramBotToken;
      let nextTelegramToken = config.telegramBotToken;
      if (typeof body.telegramBotToken === "string" && body.telegramBotToken.trim()) nextTelegramToken = body.telegramBotToken.trim();
      if (body.clearTelegramToken === true) nextTelegramToken = "";
      if ((typeof body.telegramBotToken === "string" && body.telegramBotToken.trim()) || body.clearTelegramToken === true) {
        telegramTokenSyncRequested = true;
      }
      let nextAllowedChatId = config.telegramAllowedChatId;
      if (typeof body.telegramAllowedChatId === "string") {
        nextAllowedChatId = body.telegramAllowedChatId.trim();
        if (nextAllowedChatId && !nextAllowedChatId.split(/[\s,;]+/).every((id) => /^-?\d{1,20}$/.test(id))) {
          throw new Error("Telegram chat ID phải là số; có thể nhập nhiều ID, ngăn cách bằng dấu phẩy.");
        }
      }

      if (nextDeviceBaseUrl !== config.deviceBaseUrl) lastKnownDeviceBaseUrl = "";
      config.deviceBaseUrl = nextDeviceBaseUrl;
      config.runtimeBaseUrl = nextRuntimeBaseUrl;
      config.ytDlpPath = nextYtDlpPath;
      config.ffmpegPath = nextFfmpegPath;
      config.denoPath = nextDenoPath;
      config.outputDirectory = nextOutputDirectory;
      config.workerName = nextWorkerName;
      workerId = nextWorkerName;
      config.telegramEnabled = nextTelegramEnabled;
      config.urlRetentionDays = nextRetentionDays;
      config.gpioCommands = nextGpioCommands;
      config.deviceToken = nextDeviceToken;
      config.telegramBotToken = nextTelegramToken;
      config.telegramAllowedChatId = nextAllowedChatId;
      toolVersionCache.checkedAt = 0;
      telegramStatusCache = { checkedAt: 0, value: null };
      if (nextTelegramToken !== previousTelegramToken) {
        config.telegramUpdateOffset = 0;
        telegramWebhookCheckedAt = 0;
        telegramRuntime.state = config.telegramBotToken ? "checking" : "not_configured";
        telegramRuntime.lastError = "";
        telegramPollController?.abort();
      }
      if (telegramAllowedChats().size > 0) {
        telegramPollController?.abort();
      }
      if (typeof body.ddnsDomain === "string") config.ddnsDomain = body.ddnsDomain.trim();
      if (typeof body.ddnsToken === "string" && body.ddnsToken.trim()) config.ddnsToken = body.ddnsToken.trim();
      if (body.clearDdnsToken === true) config.ddnsToken = "";
      if (typeof body.ddnsEnabled === "boolean") config.ddnsEnabled = body.ddnsEnabled;
      if (config.deviceToken && (body.ddnsDomain !== undefined || body.ddnsToken !== undefined || body.ddnsEnabled !== undefined)) {
        try {
          await requestDevice("/api/v1/settings/ddns", "POST", {
            enabled: config.ddnsEnabled,
            domain: config.ddnsDomain,
            token: config.ddnsToken,
          }, 3000);
        } catch {}
      }
      integrationsSyncPending = true;
      await saveConfig();
      if (config.deviceToken) {
        await syncIntegrationsToDevice();
        await syncHostSettingsToDevice();
      }
      jsonResponse(response, 200, publicConfig());
    } catch (error) {
      jsonResponse(response, 400, { error: error.message });
    }
    return;
  }
  if (pathname === "/api/local/download-settings" && request.method === "GET") {
    await pullDeviceSettingsToHost();
    jsonResponse(response, 200, { downloadSettings: config.downloadSettings, schemaVersion: config.schemaVersion });
    return;
  }
  if (pathname === "/api/local/download-settings" && request.method === "PUT") {
    try {
      const body = await readJsonBody(request, 32 * 1024);
      const next = validateDownloadSettings(body.downloadSettings ?? body, config.downloadSettings);
      config.downloadSettings = next;
      config.schemaVersion = 2;
      outputStorageCache.checkedAt = 0;
      await saveConfig();
      if (config.deviceToken) await syncHostSettingsToDevice();
      jsonResponse(response, 200, { saved: true, downloadSettings: config.downloadSettings, schemaVersion: config.schemaVersion, settingsSyncPending: hostSettingsSyncPending });
    } catch (error) {
      jsonResponse(response, 400, { error: error.message, code: "INVALID_DOWNLOAD_SETTINGS" });
    }
    return;
  }
  if (pathname === "/api/local/security-settings" && request.method === "PUT") {
    try {
      const body = await readJsonBody(request, 8192);
      const sessionTimeoutMinutes = Number(body.sessionTimeoutMinutes ?? config.sessionTimeoutMinutes);
      const maxLoginAttempts = Number(body.maxLoginAttempts ?? config.maxLoginAttempts);
      const loginWindowMinutes = Number(body.loginWindowMinutes ?? config.loginWindowMinutes);
      if (!Number.isInteger(sessionTimeoutMinutes) || sessionTimeoutMinutes < 15 || sessionTimeoutMinutes > 1440) throw new Error("Thời hạn phiên phải từ 15 đến 1440 phút.");
      if (!Number.isInteger(maxLoginAttempts) || maxLoginAttempts < 3 || maxLoginAttempts > 20) throw new Error("Số lần đăng nhập sai phải từ 3 đến 20.");
      if (!Number.isInteger(loginWindowMinutes) || loginWindowMinutes < 1 || loginWindowMinutes > 60) throw new Error("Cửa sổ giới hạn đăng nhập phải từ 1 đến 60 phút.");
      config.sessionTimeoutMinutes = sessionTimeoutMinutes;
      config.maxLoginAttempts = maxLoginAttempts;
      config.loginWindowMinutes = loginWindowMinutes;
      const newExpiry = Date.now() + sessionTimeoutMinutes * 60 * 1000;
      for (const session of sessions.values()) session.expiresAt = Math.min(session.expiresAt, newExpiry);
      await saveConfig();
      if (config.deviceToken) await syncHostSettingsToDevice();
      jsonResponse(response, 200, { saved: true, securitySettings: publicConfig().securitySettings, settingsSyncPending: hostSettingsSyncPending });
    } catch (error) {
      jsonResponse(response, 400, { error: error.message, code: "INVALID_SECURITY_SETTINGS" });
    }
    return;
  }
  if (pathname === "/api/local/pc/check" && request.method === "POST") {
    try {
      const result = await runPcDependencyCheck();
      jsonResponse(response, result.ok ? 200 : 500, result);
    } catch (error) {
      jsonResponse(response, 500, { ok: false, error: error.message });
    }
    return;
  }
  if (pathname === "/api/local/telegram/check" && request.method === "POST") {
    try {
      const body = await readJsonBody(request);
      const submittedToken = typeof body.token === "string" ? body.token.trim() : "";
      const result = await checkTelegramToken(submittedToken || config.telegramBotToken);
      if (!submittedToken) telegramStatusCache = { checkedAt: Date.now(), value: result };
      jsonResponse(response, 200, { telegram: result });
    } catch (error) {
      jsonResponse(response, 400, { error: error.message });
    }
    return;
  }
  if (pathname === "/api/local/telegram/test" && request.method === "POST") {
    try {
      const body = await readJsonBody(request);
      if (!config.telegramBotToken) throw new Error("Hãy lưu Telegram bot token trước khi gửi tin nhắn thử.");
      const savedChats = telegramAllowedChats();
      const enteredChats = String(body.chatId ?? "").trim().split(/[\s,;]+/).filter(Boolean);
      const chatId = enteredChats[0] || savedChats.values().next().value;
      if (!chatId || !/^-?\d{1,20}$/.test(chatId)) {
        throw new Error("Nhập một chat ID số và lưu cấu hình trước khi gửi tin nhắn thử.");
      }
      const result = await telegramApiRequest("sendMessage", {
        chat_id: chatId,
        text: "✅ Tin nhắn thử từ ESP Download host. Telegram bot có thể gửi tin nhắn tới chat này.",
      }, 10000);
      if (!result.payload.ok) throw new Error(result.payload.description ?? `Telegram trả HTTP ${result.httpStatus}.`);
      jsonResponse(response, 200, { sent: true, chatId });
    } catch (error) {
      jsonResponse(response, 400, { error: error.message });
    }
    return;
  }
  if (pathname === "/api/local/scheduled-downloads" && request.method === "GET") {
    jsonResponse(response, 200, { schedules: scheduledDownloads });
    return;
  }
  if (pathname === "/api/local/scheduled-downloads" && request.method === "POST") {
    try {
      const body = await readJsonBody(request);
      const existing = body.id ? scheduledDownloads.find((s) => s.id === body.id) : null;
      const normalized = await normalizeScheduledDownload(body, existing);
      if (existing) {
        Object.assign(existing, normalized);
      } else {
        scheduledDownloads.push(normalized);
      }
      await saveScheduledDownloads();
      jsonResponse(response, 200, { saved: true, schedule: normalized });
    } catch (error) {
      jsonResponse(response, 400, { error: error.message });
    }
    return;
  }
  if (pathname.startsWith("/api/local/scheduled-downloads/") && request.method === "DELETE") {
    const id = decodeURIComponent(pathname.slice("/api/local/scheduled-downloads/".length));
    const index = scheduledDownloads.findIndex((s) => s.id === id);
    if (index >= 0) {
      scheduledDownloads.splice(index, 1);
      await saveScheduledDownloads();
      jsonResponse(response, 200, { deleted: true, id });
    } else {
      jsonResponse(response, 404, { error: "Không tìm thấy lịch tải." });
    }
    return;
  }
  if (pathname.startsWith("/api/local/scheduled-downloads/") && pathname.endsWith("/run") && request.method === "POST") {
    const id = decodeURIComponent(pathname.slice("/api/local/scheduled-downloads/".length, -"/run".length));
    const schedule = scheduledDownloads.find((s) => s.id === id);
    if (!schedule) {
      jsonResponse(response, 404, { error: "Không tìm thấy lịch tải." });
      return;
    }
    try {
      const job = await runScheduledDownload(schedule);
      jsonResponse(response, 200, { triggered: true, jobId: job.id });
    } catch (error) {
      jsonResponse(response, 500, { error: error.message });
    }
    return;
  }
  if (pathname.startsWith("/api/local/scheduled-downloads/") && pathname.endsWith("/toggle") && request.method === "POST") {
    const id = decodeURIComponent(pathname.slice("/api/local/scheduled-downloads/".length, -"/toggle".length));
    const schedule = scheduledDownloads.find((s) => s.id === id);
    if (!schedule) {
      jsonResponse(response, 404, { error: "Không tìm thấy lịch tải." });
      return;
    }
    schedule.enabled = !schedule.enabled;
    schedule.nextRunAt = schedule.enabled ? nextScheduledAt(schedule) : null;
    await saveScheduledDownloads();
    jsonResponse(response, 200, { enabled: schedule.enabled, schedule });
    return;
  }
  if (pathname === "/api/local/status" && request.method === "GET") {
    jsonResponse(response, 200, await localStatus());
    return;
  }
  if (pathname === "/api/local/runtime/jobs" && request.method === "DELETE") {
    const beforeCount = runtimeQueue.length;
    runtimeQueue = runtimeQueue.filter((job) => !isTerminalRuntimeJob(job));
    const deletedCount = beforeCount - runtimeQueue.length;
    try {
      await saveRuntimeQueue();
      jsonResponse(response, 200, { deleted: true, all: true, count: deletedCount });
    } catch (error) {
      jsonResponse(response, 500, { error: "Could not persist runtime history clearing." });
    }
    return;
  }
  if (pathname === "/api/local/runtime/jobs/by-url" && request.method === "DELETE") {
    const targetUrl = url.searchParams.get("url") || "";
    if (!targetUrl) {
      jsonResponse(response, 400, { error: "URL is required." });
      return;
    }
    const beforeCount = runtimeQueue.length;
    runtimeQueue = runtimeQueue.filter((job) => {
      if (job.url !== targetUrl) return true;
      if (job.runtimeStatus === "RUNNING" || activeJobs.has(job.id)) return true;
      return false;
    });
    const deletedCount = beforeCount - runtimeQueue.length;
    try {
      await saveRuntimeQueue();
      jsonResponse(response, 200, { deleted: true, url: targetUrl, count: deletedCount });
    } catch (error) {
      jsonResponse(response, 500, { error: "Could not persist the runtime URL deletion." });
    }
    return;
  }
  if (pathname.startsWith("/api/local/runtime/jobs/") && request.method === "DELETE") {
    const id = decodeURIComponent(pathname.slice("/api/local/runtime/jobs/".length));
    const targetUrl = url.searchParams.get("url") || "";
    const beforeCount = runtimeQueue.length;
    runtimeQueue = runtimeQueue.filter((job) => {
      if (job.runtimeStatus === "RUNNING" || activeJobs.has(job.id)) return true;
      if (job.id === id) return false;
      if (targetUrl && job.url === targetUrl) return false;
      return true;
    });
    const deletedCount = beforeCount - runtimeQueue.length;
    try {
      await saveRuntimeQueue();
      jsonResponse(response, 200, { deleted: true, id, count: deletedCount });
    } catch (error) {
      jsonResponse(response, 500, { error: "Could not persist the runtime history deletion." });
    }
    return;
  }
  if (pathname === "/api/local/system/check" && request.method === "GET") {
    try {
      const tools = await checkSystemPackages();
      jsonResponse(response, 200, { ok: true, tools });
    } catch (error) {
      jsonResponse(response, 500, { ok: false, error: error.message });
    }
    return;
  }
  async function collectSystemBackup() {
    if (!config.deviceToken) throw new Error("ESP32 API token is not configured on this host.");
    const deviceBackup = await requestDevice("/api/v1/config/backup", "GET", undefined, 8000);
    if (deviceBackup.status < 200 || deviceBackup.status >= 300) {
      throw new Error(deviceBackup.payload?.error?.message || `ESP32 returned HTTP ${deviceBackup.status}.`);
    }
    return {
      format: "esp-download-system-backup",
      schemaVersion: 1,
      createdAt: new Date().toISOString(),
      hostConfig: config,
      deviceConfig: deviceBackup.payload,
    };
  }
  async function restoreHostAndDeviceBackup(backup) {
    const host = backup.hostConfig;
    const device = backup.deviceConfig;
    const deviceSettings = device?.settings;
    if (backup.format !== "esp-download-system-backup" || backup.schemaVersion !== 1 ||
        !host || typeof host !== "object" || Array.isArray(host) ||
        device?.schemaVersion !== 1 || !deviceSettings || typeof deviceSettings !== "object") {
      throw new Error("Unsupported or incomplete encrypted system backup.");
    }
    const deviceToken = String(deviceSettings.apiToken ?? "");
    const telegramBotToken = String(deviceSettings.telegramBotToken ?? "");
    const telegramAllowedChatId = String(deviceSettings.telegramAllowedChatIds ?? "").trim();
    const runtimeBaseUrl = validateRuntimeBaseUrl(String(deviceSettings.runtimeBaseUrl ?? host.runtimeBaseUrl ?? ""));
    if (deviceToken.length < 32 || deviceToken.length > 128 || !/^[A-Za-z0-9_-]+$/.test(deviceToken)) {
      throw new Error("Backup contains an invalid ESP32 API token.");
    }
    if (telegramBotToken.length > 128 || (telegramAllowedChatId &&
        !telegramAllowedChatId.split(/[\s,;]+/).every((id) => /^-?\d{1,20}$/.test(id)))) {
      throw new Error("Backup contains invalid Telegram settings.");
    }
    const authUsername = String(host.authUsername ?? "");
    const authSalt = String(host.authSalt ?? "");
    const authPasswordHash = String(host.authPasswordHash ?? "");
    if (!/^[A-Za-z0-9._-]{1,32}$/.test(authUsername) ||
        !/^[a-f0-9]{32}$/i.test(authSalt) || !/^[a-f0-9]{128}$/i.test(authPasswordHash)) {
      throw new Error("Backup does not contain valid host account credentials.");
    }
    const workerName = String(host.workerName ?? "").trim();
    if (!/^[A-Za-z0-9._ -]{1,48}$/.test(workerName)) throw new Error("Backup contains an invalid worker name.");
    const sessionTimeoutMinutes = Number(host.sessionTimeoutMinutes);
    const maxLoginAttempts = Number(host.maxLoginAttempts);
    const loginWindowMinutes = Number(host.loginWindowMinutes);
    if (!Number.isInteger(sessionTimeoutMinutes) || sessionTimeoutMinutes < 15 || sessionTimeoutMinutes > 1440 ||
        !Number.isInteger(maxLoginAttempts) || maxLoginAttempts < 3 || maxLoginAttempts > 20 ||
        !Number.isInteger(loginWindowMinutes) || loginWindowMinutes < 1 || loginWindowMinutes > 60) {
      throw new Error("Backup contains invalid host security settings.");
    }
    const downloadSettings = validateDownloadSettings(host.downloadSettings);
    const retention = Number(deviceSettings.urlRetentionDays);
    if (!Number.isInteger(retention) || retention < 0 || retention > 3650) {
      throw new Error("Backup contains an invalid URL retention value.");
    }
    const deviceRestore = await requestDevice("/api/v1/config/restore", "POST", device, 10000);
    if (deviceRestore.status < 200 || deviceRestore.status >= 300 || deviceRestore.payload?.restored !== true) {
      throw new Error(deviceRestore.payload?.error?.message || `ESP32 returned HTTP ${deviceRestore.status}.`);
    }
    const previousTelegramToken = config.telegramBotToken;
    const targetDeviceBaseUrl = config.deviceBaseUrl;
    config = {
      ...defaultConfig,
      ...host,
      schemaVersion: 2,
      deviceBaseUrl: targetDeviceBaseUrl,
      runtimeBaseUrl,
      deviceToken,
      telegramBotToken,
      telegramAllowedChatId,
      telegramEnabled: deviceSettings.telegramEnabled !== false,
      ddnsEnabled: deviceSettings.ddnsEnabled !== undefined ? Boolean(deviceSettings.ddnsEnabled) : Boolean(host.ddnsEnabled),
      ddnsDomain: String(deviceSettings.ddnsDomain ?? host.ddnsDomain ?? "").trim(),
      ddnsToken: String(deviceSettings.ddnsToken ?? host.ddnsToken ?? "").trim(),
      urlRetentionDays: retention,
      gpioCommands: Array.isArray(deviceSettings.gpioCommands) ? deviceSettings.gpioCommands : [],
      workerName,
      downloadSettings,
      sessionTimeoutMinutes,
      maxLoginAttempts,
      loginWindowMinutes,
      authUsername,
      authSalt,
      authPasswordHash,
      hostSettingsSyncPending: false,
    };
    workerId = workerName;
    deviceTelegramTokenConfigured = Boolean(telegramBotToken);
    integrationsSyncPending = false;
    hostSettingsSyncPending = false;
    telegramTokenSyncRequested = false;
    lastKnownDeviceBaseUrl = "";
    toolVersionCache.checkedAt = 0;
    telegramStatusCache = { checkedAt: 0, value: null };
    if (telegramBotToken !== previousTelegramToken) {
      telegramWebhookCheckedAt = 0;
      telegramRuntime.state = telegramBotToken ? "checking" : "not_configured";
      telegramRuntime.lastError = "";
      telegramPollController?.abort();
    }
    const newExpiry = Date.now() + sessionTimeoutMinutes * 60 * 1000;
    for (const session of sessions.values()) session.expiresAt = Math.min(session.expiresAt, newExpiry);
    await saveConfig();
    return { restored: true, deviceRestarting: deviceRestore.payload?.restarting === true };
  }
  if (pathname === "/api/local/config/backup" && request.method === "POST") {
    try {
      jsonResponse(response, 200, await collectSystemBackup());
    } catch (error) {
      jsonResponse(response, 502, { error: `Could not back up host and ESP32 settings: ${error.message}` });
    }
    return;
  }
  if (pathname === "/api/local/config/restore" && request.method === "POST") {
    try {
      const backup = await readJsonBody(request, 1024 * 1024);
      jsonResponse(response, 200, await restoreHostAndDeviceBackup(backup));
    } catch (error) {
      jsonResponse(response, 400, { error: `Could not restore host and ESP32 settings: ${error.message}` });
    }
    return;
  }
  if (pathname === "/api/local/firmware" && request.method === "GET") {
    try {
      jsonResponse(response, 200, { files: await listBuiltFirmware() });
    } catch (error) {
      const status = error.code === "ENOENT" ? 404 : 500;
      jsonResponse(response, status, { error: `Could not list built firmware: ${error.message}` });
    }
    return;
  }
  if (pathname === "/api/local/firmware/download" && request.method === "GET") {
    try {
      const fileId = new URL(request.url, "http://localhost").searchParams.get("file");
      await sendBuiltFirmware(response, fileId);
    } catch (error) {
      console.error("Could not serve firmware file:", error.message);
      if (!response.headersSent) jsonResponse(response, 404, { error: "Firmware file was not found or is no longer available." });
      else response.destroy(error);
    }
    return;
  }
  if (pathname === "/api/local/system/install" && request.method === "POST") {
    installSystemPackages(response);
    return;
  }
  if (pathname === "/api/local/device/reboot" && request.method === "POST") {
    try {
      const result = await requestDevice("/api/v1/system/reboot", "POST", {}, 4000);
      jsonResponse(response, 200, { success: true, message: result.payload?.message || "ESP32 đang khởi động lại..." });
    } catch (error) {
      jsonResponse(response, 502, { error: `Không gửi được lệnh reboot tới ESP32: ${error.message}` });
    }
    return;
  }
  if (pathname === "/api/local/runtime/restart" && request.method === "POST") {
    jsonResponse(response, 200, { restarting: true });
    setTimeout(() => { process.exit(0); }, 500);
    return;
  }
  // Endpoint: DELETE log entry với retry mechanism khi LittleFS busy
  if (pathname.startsWith("/api/local/log/delete/") && request.method === "DELETE") {
    const entryId = decodeURIComponent(pathname.slice("/api/local/log/delete/".length));
    if (!entryId) { jsonResponse(response, 400, { error: "Missing log entry id" }); return; }
    // Retry up to 3 times with 600ms delay to handle LittleFS busy state
    let lastError = null;
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        if (attempt > 0) await new Promise(r => setTimeout(r, 600));
        const result = await requestDevice(`/api/v1/telegram/log/${encodeURIComponent(entryId)}`, "DELETE", undefined, 6000);
        jsonResponse(response, result.status, result.payload);
        return;
      } catch (error) {
        lastError = error;
        if (error.name !== "AbortError") continue;
        break;
      }
    }
    jsonResponse(response, 502, { error: `Delete failed after 3 attempts: ${lastError?.message}` });
    return;
  }
  // Endpoint: DELETE all log entries with retry
  if (pathname === "/api/local/log/delete-all" && request.method === "DELETE") {
    try {
      const result = await requestDevice("/api/v1/telegram/log", "DELETE", undefined, 8000);
      jsonResponse(response, result.status, result.payload);
    } catch (error) {
      jsonResponse(response, 502, { error: error.message });
    }
    return;
  }
  if (pathname.startsWith("/api/device/")) {
    const apiPath = pathname.slice("/api/device".length);
    try {
      if (!apiPath.startsWith("/api/v1/") || apiPath.includes("..")) throw new Error("Invalid device API path");
      const body = ["POST", "PUT", "PATCH"].includes(request.method) ? await readJsonBody(request) : undefined;
      const result = await requestDevice(apiPath, request.method, body, 10000);
      jsonResponse(response, result.status, result.payload);
    } catch (error) {
      jsonResponse(response, 502, { error: error.name === "AbortError" ? "Device request timed out" : error.message });
    }
    return;
  }
  if (request.method !== "GET" && request.method !== "HEAD") {
    textResponse(response, 405, "Method not allowed");
    return;
  }
  await serveStatic(request, response);
}

await loadConfig();
void runTelegramPoller();
const server = http.createServer((request, response) => {
  handleRequest(request, response).catch((error) => {
    console.error(error);
    jsonResponse(response, 500, { error: "Internal local-host error" });
  });
});

server.listen(port, listenAddress, () => {
  console.log(`ESP Download dashboard: http://${uiAddress}:${port}`);
  console.log(`Windows runtime listener: ${listenAddress}:${port} (private LAN requests require the device token)`);
  console.log(`Suggested ESP32 runtime URL: ${config.runtimeBaseUrl || "not configured"}`);
  console.log(`Configured ESP32 API: ${config.deviceBaseUrl}`);
  console.log("PowerShell/yt-dlp worker consumes the durable Windows queue in FIFO order.");
});

// Active downloads do not need cancellation checks more often than this.
// Polling the ESP32 every 2 seconds on congested Wi-Fi adds avoidable radio
// and HTTP-server load and can make downloads appear to disconnect the device.
setInterval(() => void workerTick(), 5000).unref();
setInterval(() => void checkScheduledDownloads(), 5000).unref();
setInterval(() => {
  if (config.telegramBotToken && telegramAllowedChats().size === 0) void refreshTelegramDeviceOwner(true);
}, 5000).unref();
setInterval(() => {
  const now = Date.now();
  for (const [token, session] of sessions) {
    if (session.expiresAt <= now) sessions.delete(token);
  }
}, 60_000).unref();
