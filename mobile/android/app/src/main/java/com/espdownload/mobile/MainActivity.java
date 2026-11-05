package com.espdownload.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.Manifest;
import android.content.pm.PackageManager;
import android.app.PendingIntent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import androidx.core.content.FileProvider;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PathEffect;
import android.graphics.DashPathEffect;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.net.Uri;
import android.content.ContentValues;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.provider.MediaStore;
import android.os.Environment;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import io.github.dzarda7.espflasher.Flasher;
import io.github.dzarda7.espflasher.UsbSerialBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;

/** Native companion UI for the authenticated Windows local host. */
public class MainActivity extends Activity {
    private static final String SYSTEM_BACKUP_MIME_TYPE = "application/vnd.esp-download.system-backup+json";
    private static final int REQUEST_QR_IMAGE = 8432;
    private static final int REQUEST_CAMERA_PERMISSION = 8433;
    private static final int REQUEST_IMAGE_PERMISSION = 8434;
    private static final int REQUEST_QR_CAMERA = 8435;
    private static final int REQUEST_FIRMWARE_FILES = 8436;
    private static final int REQUEST_SYSTEM_BACKUP_OPEN = 8438;
    private static final int REQUEST_LEGACY_BACKUP_PERMISSION = 8439;
    private static final int REQUEST_WIFI_SCAN_PERMISSION = 8440;
    private ConnectivityManager.NetworkCallback activeNetworkCallback;
    private static int INK = Color.rgb(23, 33, 30);
    private static int MUTED = Color.rgb(112, 126, 116);
    private static int GREEN = Color.rgb(36, 109, 82);
    private static int SURFACE = Color.WHITE;
    private static int PAGE = Color.rgb(244, 245, 239);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private SharedPreferences prefs;
    private String host = "http://192.168.1.2:8787";
    private String sessionToken = "";
    private String accountUsername = "admin";
    private JSONObject devicePairing = new JSONObject();
    private String deviceApiUrl = "";
    private String deviceApiToken = "";
    private String activePage = "dashboard";
    private boolean directSetup;
    private boolean darkTheme;
    private String uiLanguage = "en";
    private final Map<String, String> translationCache = new HashMap<>();
    private LinearLayout root, body, tabs;
    private UsbManager usbManager;
    private UsbSerialDriver selectedUsbDriver;
    private volatile UsbSerialBridge usbBridge;
    private volatile Flasher activeFlasher;
    private TextView usbStatus;
    private TextView firmwareSelection;
    private LinearLayout gpioRowsContainer;
    private Spinner chipModelSpinner;
    private Spinner flashSizeSpinner;
    private Spinner flashModeSpinner;
    private Spinner flashFrequencySpinner;
    private LinearLayout firmwareRows;
    private Button flashButton;
    private ProgressBar flashProgressBar;
    private TextView flashProgressText;
    private EditText flashBaudField;
    private String detectedChip = "";
    private int detectedFlashSize;
    private boolean flashInProgress;
    private final List<FirmwareImage> firmwareImages = new ArrayList<>();
    private byte[] pendingSystemBackup;
    private String pendingSystemBackupName;
    private static final String USB_PERMISSION_ACTION = "com.espdownload.mobile.USB_PERMISSION";
    private static final class FirmwareImage {
        final String name;
        final Uri uri;
        final byte[] bytes;
        EditText offsetField;
        FirmwareImage(String name, Uri uri, byte[] bytes) { this.name = name; this.uri = uri; this.bytes = bytes; }
    }
    private static final class FlashPayload {
        final String name;
        final byte[] data;
        final int offset;
        FlashPayload(String name, byte[] data, int offset) { this.name = name; this.data = data; this.offset = offset; }
    }
    private final Map<String, View> fields = new HashMap<>();
    private JSONObject config = new JSONObject();
    private JSONObject status = new JSONObject();
    private JSONObject device = new JSONObject();
    private JSONObject telegram = new JSONObject();
    private JSONArray dashboardJobs = new JSONArray();
    private String dashboardJobFilter = "QUEUED";
    private boolean settingsDirty, downloadsDirty, securityDirty, rendering;
    private final AtomicBoolean statusBusy = new AtomicBoolean(false);
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (directSetup) refreshDirectSetup();
            else if (!sessionToken.isEmpty()) {
                refreshStatus(false);
                if (activePage.equals("queue") || activePage.equals("logs") || activePage.equals("dashboard")) loadDeviceData(activePage);
                if (System.currentTimeMillis() % 15000 < 5000 && (activePage.equals("settings") || activePage.equals("downloads") || activePage.equals("security")) && !pageDirty() && !anyFieldFocused()) loadConfigForPage(activePage);
            }
            main.postDelayed(this, 5000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        cleanupTempFiles();
        prefs = getSharedPreferences("esp-download", MODE_PRIVATE);
        darkTheme = prefs.getBoolean("dark-theme", false);
        uiLanguage = prefs.getString("ui-language", "en");
        if (!uiLanguage.equals("en") && !uiLanguage.equals("vi") && !uiLanguage.equals("zh")) uiLanguage = "en";
        applyThemePalette();
        host = prefs.getString("host", BuildConfig.FRESH_INSTALL_TEST ? "" : host);
        accountUsername = prefs.getString("username", "admin");
        sessionToken = decrypt(prefs.getString("session", ""));
        String cachedConfigStr = prefs.getString("cached-config", "");
        if (!cachedConfigStr.isEmpty()) {
            try { config = new JSONObject(cachedConfigStr); } catch (Exception ignored) {}
        }
        if (config == null) config = new JSONObject();
        String savedPairing = decrypt(prefs.getString("device-pairing", ""));
        if (!savedPairing.isEmpty()) {
            try {
                devicePairing = new JSONObject(savedPairing);
                deviceApiUrl = devicePairing.optString("deviceApiUrl", "");
                deviceApiToken = devicePairing.optString("deviceApiToken", "");
            } catch (Exception ignored) { devicePairing = new JSONObject(); }
        }
        if (sessionToken.isEmpty()) {
            if (BuildConfig.FRESH_INSTALL_TEST) showLogin();
            else autoLoginDefaults();
        } else {
            validateSession();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(ticker);
        main.post(ticker);
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbDeviceReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(usbDeviceReceiver, filter);
        usbDeviceReceiverRegistered = true;
        if (activePage.equals("system")) main.postDelayed(() -> {
            if (activePage.equals("system") && activeFlasher == null) scanUsbSerialDevices();
        }, 350);
    }
    @Override protected void onPause() {
        main.removeCallbacks(ticker);
        if (usbDeviceReceiverRegistered) {
            unregisterReceiver(usbDeviceReceiver);
            usbDeviceReceiverRegistered = false;
        }
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (usbPermissionReceiverRegistered) {
            unregisterReceiver(usbPermissionReceiver);
            usbPermissionReceiverRegistered = false;
        }
        closeUsbSerial();
        super.onDestroy();
    }
    @Override public void onBackPressed() { if (directSetup) { showLogin(); return; } if (!sessionToken.isEmpty() && !activePage.equals("dashboard")) { openPage("dashboard"); return; } super.onBackPressed(); }

    private void applyThemePalette() {
        INK = darkTheme ? Color.rgb(231, 238, 233) : Color.rgb(23, 33, 30);
        MUTED = darkTheme ? Color.rgb(164, 178, 168) : Color.rgb(112, 126, 116);
        GREEN = darkTheme ? Color.rgb(85, 174, 134) : Color.rgb(36, 109, 82);
        SURFACE = darkTheme ? Color.rgb(32, 43, 37) : Color.WHITE;
        PAGE = darkTheme ? Color.rgb(21, 28, 24) : Color.rgb(244, 245, 239);
        if (getWindow() != null) {
            getWindow().setStatusBarColor(PAGE); getWindow().setNavigationBarColor(PAGE);
            getWindow().getDecorView().setSystemUiVisibility(darkTheme ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private Button themeButton() {
        Button button = button(darkTheme ? "☀" : "☾", false);
        button.setTextSize(17);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(darkTheme ? Color.rgb(231, 238, 233) : Color.rgb(27, 61, 47));
        button.setContentDescription(darkTheme ? "Chuyển sang giao diện sáng" : "Chuyển sang giao diện tối");
        button.setOnClickListener(v -> {
            darkTheme = !darkTheme; prefs.edit().putBoolean("dark-theme", darkTheme).apply(); applyThemePalette();
            if (!sessionToken.isEmpty() && !directSetup) showMain(); else if (directSetup) showDirectSetup(); else showLogin();
        });
        return button;
    }

    private Button languageButton() {
        Button button = button(uiLanguage.equals("zh") ? "CH" : uiLanguage.toUpperCase(Locale.ROOT), false);
        button.setTextSize(10);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(darkTheme ? Color.rgb(231, 238, 233) : Color.rgb(27, 61, 47));
        button.setContentDescription(tr("Change interface language"));
        button.setOnClickListener(v -> cycleLanguage());
        return button;
    }

    private void cycleLanguage() {
        Map<String, String> textValues = new HashMap<>();
        Map<String, Boolean> checks = new HashMap<>();
        Map<String, Integer> selections = new HashMap<>();
        for (Map.Entry<String, View> entry : fields.entrySet()) {
            View field = entry.getValue();
            if (field instanceof EditText) textValues.put(entry.getKey(), ((EditText) field).getText().toString());
            else if (field instanceof CheckBox) checks.put(entry.getKey(), ((CheckBox) field).isChecked());
            else if (field instanceof Spinner) selections.put(entry.getKey(), ((Spinner) field).getSelectedItemPosition());
        }
        uiLanguage = uiLanguage.equals("en") ? "vi" : uiLanguage.equals("vi") ? "zh" : "en";
        translationCache.clear();
        prefs.edit().putString("ui-language", uiLanguage).apply();
        if (!sessionToken.isEmpty() && !directSetup) showMain();
        else if (directSetup) showDirectSetup();
        else showLogin();
        for (Map.Entry<String, String> entry : textValues.entrySet()) setValue(entry.getKey(), entry.getValue());
        for (Map.Entry<String, Boolean> entry : checks.entrySet()) {
            View field = fields.get(entry.getKey());
            if (field instanceof CheckBox) ((CheckBox) field).setChecked(entry.getValue());
        }
        for (Map.Entry<String, Integer> entry : selections.entrySet()) {
            View field = fields.get(entry.getKey());
            if (field instanceof Spinner) ((Spinner) field).setSelection(entry.getValue());
        }
    }

    private void validateSession() {
        runIo(() -> api("/api/auth/session", "GET", null), result -> {
            accountUsername = result.optString("username", accountUsername);
            showMain();
        }, error -> {
            // Khi máy chủ offline, không đá người dùng ra màn hình login nếu phiên đã tồn tại
            if (sessionToken.startsWith("offline-") || !sessionToken.isEmpty()) {
                showMain();
            } else {
                clearSession();
                showLogin();
            }
        });
    }

    private void autoLoginDefaults() {
        if (!sessionToken.isEmpty()) {
            showMain();
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", "admin");
            payload.put("password", "admin");
        } catch (Exception ignored) {
            showLogin();
            return;
        }
        runIo(() -> request(host, "/api/mobile/auth/login", "POST", payload, ""), result -> {
            String token = result.optString("sessionToken", "");
            if (token.isEmpty()) throw new IllegalStateException("Host không trả về phiên đăng nhập.");
            sessionToken = token;
            accountUsername = result.optString("username", "admin");
            prefs.edit().putString("session", encrypt(token)).putString("username", accountUsername).apply();
            showMain();
            syncSavedPairingToHost();
        }, error -> {
            showLogin();
        });
    }

    private final java.util.concurrent.atomic.AtomicBoolean upgradingSession = new java.util.concurrent.atomic.AtomicBoolean(false);
    private void attemptSeamlessUpgrade() {
        if (!sessionToken.startsWith("offline-") || !upgradingSession.compareAndSet(false, true)) return;
        String u = prefs.getString("cached-login-user", "admin");
        String p = decrypt(prefs.getString("cached-login-pass", ""));
        if (p == null || p.isEmpty()) p = "admin";
        final String user = u;
        final String pass = p;
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("username", user);
                payload.put("password", pass);
                JSONObject res = null;
                String targetHost = host;
                try {
                    res = request(targetHost, "/api/mobile/auth/login", "POST", payload, "");
                } catch (Exception hostErr) {
                    if (!deviceApiUrl.isEmpty() && !deviceApiUrl.equals(host)) {
                        targetHost = deviceApiUrl;
                        res = request(targetHost, "/api/mobile/auth/login", "POST", payload, "");
                    } else {
                        throw hostErr;
                    }
                }
                String token = res != null ? res.optString("sessionToken", "") : "";
                if (!token.isEmpty()) {
                    final String upgradedHost = targetHost;
                    final boolean isEsp = res.optBoolean("isDirectEsp", false);
                    main.post(() -> {
                        host = upgradedHost;
                        prefs.edit().putString("host", host).putString("session", encrypt(token)).apply();
                        sessionToken = token;
                        toast(isEsp ? "Đã phát hiện ESP32 trực tuyến! Đã tự động kết nối trực tiếp." : "Máy chủ đã Online trở lại! Đã tự động kết nối và đồng bộ.");
                        statusBusy.set(false);
                        refreshStatus(false);
                    });
                }
            } catch (Exception ignored) {
            } finally {
                upgradingSession.set(false);
            }
        });
    }

    private void showLogin() {
        directSetup = false;
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(PAGE);
        root.setPadding(dp(20), dp(28), dp(20), dp(20)); setContentView(root);
        LinearLayout appearance = horizontal(root);
        appearance.addView(themeButton(), new LinearLayout.LayoutParams(0, dp(42), 1));
        appearance.addView(languageButton(), new LinearLayout.LayoutParams(0, dp(42), 1));
        label(root, "ESP DOWNLOAD", 12, GREEN, true);
        label(root, "Kết nối local host", 26, INK, true);
        label(root, "App native · dùng chung cấu hình và hàng đợi với dashboard trên PC", 14, MUTED, false);
        label(root, "", 8, MUTED, false);
        label(root, "Host URL (3 Chế độ: Nhập LAN / Quét Wi‑Fi / USB OTG)", 12, MUTED, true);
        EditText hostInput = input("host", "Địa chỉ host", host, "http://192.168.1.x:8787 hoặc http://192.168.1.5", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        root.addView(hostInput, full());

        LinearLayout modeRow = horizontal(root);
        Button modeManual = button("1. Nhập LAN", false);
        Button modeScan = button("2. Scan LAN", false);
        Button modeOtg = button("3. USB OTG", false);
        modeRow.addView(modeManual, weight());
        modeRow.addView(modeScan, weight());
        modeRow.addView(modeOtg, weight());

        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (um != null) {
            try {
                if (!com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProber().findAllDrivers(um).isEmpty()) {
                    Button otgAlert = button("⚡ Đã cắm cáp OTG: Bấm để chọn 127.0.0.1:8787", false);
                    otgAlert.setTextColor(GREEN);
                    root.addView(otgAlert, full());
                    otgAlert.setOnClickListener(v -> hostInput.setText("http://127.0.0.1:8787"));
                }
            } catch (Exception ignored) {}
        }

        addPairingActions(root);
        label(root, "Tài khoản", 12, MUTED, true);
        EditText username = input("login-user", "Tài khoản", accountUsername, "Mặc định lần đầu: admin", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME, false);
        root.addView(username, full());
        label(root, "Mật khẩu", 12, MUTED, true);
        EditText password = input("login-pass", "Mật khẩu", "admin", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
        root.addView(password, full());
        LinearLayout row = horizontal(root);
        Button discover = button("Tìm host trong Wi‑Fi", false);
        Button login = button("Đăng nhập", true);
        row.addView(discover, weight()); row.addView(login, weight());
        TextView feedback = label(root, "Kết nối điện thoại và PC vào cùng Wi‑Fi. Nếu đang cấu hình ESP32 qua AP espsystem, hãy chuyển về Wi‑Fi có máy host.", 12, MUTED, false);
        feedback.setPadding(0, dp(14), 0, 0);
        discover.setOnClickListener(v -> discoverHost(discover, hostInput, feedback));
        modeManual.setOnClickListener(v -> {
            hostInput.requestFocus();
            feedback.setText(tr("Chế độ 1: Nhập IP trực tiếp của ESP32 (vd: http://192.168.1.5) hoặc PC Host (vd: http://192.168.1.2:8787)."));
        });
        modeScan.setOnClickListener(v -> discoverHost(discover, hostInput, feedback));
        modeOtg.setOnClickListener(v -> {
            hostInput.setText("http://127.0.0.1:8787");
            feedback.setText(tr("Chế độ 3: Đã chọn host USB OTG (127.0.0.1:8787)."));
        });
        Button setup = button("Thiết lập ESP32 qua AP", false); root.addView(setup, full());
        setup.setOnClickListener(v -> showDirectSetup());
        login.setOnClickListener(v -> {
            host = normalizeHost(hostInput.getText().toString());
            prefs.edit().putString("host", host).apply();
            login.setEnabled(false); feedback.setText(tr("Đang kết nối…"));
            final String u = username.getText().toString().trim();
            final String p = password.getText().toString();
            JSONObject payload = new JSONObject();
            try { payload.put("username", u); payload.put("password", p); }
            catch (Exception ignored) {}
            runIo(() -> request(host, "/api/mobile/auth/login", "POST", payload, ""), result -> {
                String token = result.optString("sessionToken", "");
                if (token.isEmpty()) throw new IllegalStateException("Host không trả về phiên đăng nhập.");
                sessionToken = token;
                accountUsername = result.optString("username", u);
                prefs.edit().putString("session", encrypt(token))
                    .putString("username", accountUsername)
                    .putString("cached-login-user", u)
                    .putString("cached-login-pass", encrypt(p))
                    .apply();
                showMain();
                syncSavedPairingToHost();
            }, error -> {
                // Xác thực Offline: nếu là admin/admin hoặc khớp thông tin đã lưu trước đó, cho phép vào ngay
                String savedUser = prefs.getString("cached-login-user", "admin");
                String savedPass = decrypt(prefs.getString("cached-login-pass", ""));
                boolean matchesDefault = u.equals("admin") && p.equals("admin");
                boolean matchesCached = !savedPass.isEmpty() && u.equals(savedUser) && p.equals(savedPass);
                if (matchesDefault || matchesCached) {
                    sessionToken = "offline-" + System.currentTimeMillis();
                    accountUsername = u;
                    prefs.edit().putString("session", encrypt(sessionToken))
                        .putString("username", accountUsername)
                        .putString("cached-login-user", u)
                        .putString("cached-login-pass", encrypt(p))
                        .apply();
                    toast(tr("Đã đăng nhập ở chế độ Offline (máy chủ host chưa kết nối)."));
                    showMain();
                } else {
                    login.setEnabled(true);
                    feedback.setText(tr("Sai tài khoản hoặc mật khẩu: ") + error.getMessage());
                }
            });
        });
    }

    private void addPairingActions(ViewGroup parent) {
        label(parent, "Ghép nối ESP32 từ QR", 12, MUTED, true);
        LinearLayout row = horizontal(parent);
        Button camera = button("Mở camera quét QR", false);
        Button image = button("Nhập ảnh PNG", false);
        row.addView(camera, weight()); row.addView(image, weight());
        camera.setOnClickListener(v -> startQrCameraScan());
        image.setOnClickListener(v -> chooseQrImage());
        label(parent, "QR ghép thiết bị; tài khoản và mật khẩu vẫn dùng để đăng nhập local host.", 11, MUTED, false);
    }

    private void startQrCameraScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        try {
            Intent intent = new Intent(this, QrCaptureActivity.class);
            intent.putExtra("SCAN_FORMATS", IntentIntegrator.QR_CODE);
            intent.putExtra("PROMPT_MESSAGE", "Quét QR ghép nối ESP32");
            intent.putExtra("BEEP_ENABLED", false);
            intent.setPackage(getPackageName());
            intent.setClassName(getPackageName(), "com.espdownload.mobile.QrCaptureActivity");
            startActivityForResult(intent, REQUEST_QR_CAMERA);
        } catch (Exception error) {
            toast("Không mở được camera quét QR: " + error.getMessage());
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startQrCameraScan();
            else toast("Cần cấp quyền Camera để quét mã QR ghép nối.");
        } else if (requestCode == REQUEST_IMAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) chooseQrImage();
            else toast("Cần cấp quyền truy cập ảnh để tải mã QR từ bộ nhớ.");
        } else if (requestCode == REQUEST_LEGACY_BACKUP_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) savePendingLegacySystemBackup();
            else {
                pendingSystemBackup = null;
                pendingSystemBackupName = null;
                toast(tr("Cần cấp quyền ghi bộ nhớ để lưu backup trong Download/Esp32 System."));
            }
        } else if (requestCode == REQUEST_WIFI_SCAN_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanNearbyEspAccessPoints();
            } else {
                toast(tr("Cần cấp quyền vị trí hoặc thiết bị gần đây để quét AP ESP32."));
            }
        }
    }

    private void chooseQrImage() {
        // ACTION_OPEN_DOCUMENT grants a temporary URI to the selected image.
        // Android does not need READ_MEDIA_IMAGES for this picker flow.
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                startActivityForResult(intent, REQUEST_QR_IMAGE);
                return;
            } catch (Exception error) {
                toast("Không mở được trình chọn ảnh: " + error.getMessage());
                return;
            }
        }
        String imagePermission = Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_IMAGES
            : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(imagePermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{imagePermission}, REQUEST_IMAGE_PERMISSION);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            startActivityForResult(intent, REQUEST_QR_IMAGE);
        } catch (Exception error) {
            toast("Không mở được trình chọn ảnh: " + error.getMessage());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_SYSTEM_BACKUP_OPEN) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) restoreSystemConfiguration(data.getData());
            return;
        }
        if (requestCode == REQUEST_FIRMWARE_FILES) {
            if (resultCode == RESULT_OK && data != null) {
                firmwareImages.clear();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        firmwareImages.add(new FirmwareImage(getFirmwareDisplayName(uri), uri, null));
                    }
                } else if (data.getData() != null) {
                    Uri uri = data.getData();
                    firmwareImages.add(new FirmwareImage(getFirmwareDisplayName(uri), uri, null));
                }
                for (FirmwareImage image : firmwareImages) {
                    if (!image.name.toLowerCase(Locale.ROOT).endsWith(".bin")) {
                        firmwareImages.clear();
                        toast("Chỉ chấp nhận firmware có phần mở rộng .bin.");
                        renderFirmwareRows();
                        refreshFlashButtonState();
                        return;
                    }
                }
                for (FirmwareImage image : firmwareImages) {
                    Uri uri = image.uri;
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (SecurityException ignored) {}
                }
                renderFirmwareRows();
                refreshFlashButtonState();
            }
            return;
        }
        if (requestCode == REQUEST_QR_IMAGE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                runIo(() -> json("content", decodeQrImage(uri)), result -> acceptDevicePairing(result.optString("content")), error -> toast("Không đọc được QR trong ảnh: " + error.getMessage()));
            }
            return;
        }
        if (requestCode == REQUEST_QR_CAMERA) {
            if (resultCode == RESULT_OK && data != null) {
                String contents = data.getStringExtra("SCAN_RESULT");
                if (contents != null && !contents.isEmpty()) acceptDevicePairing(contents);
                else toast("Không đọc được mã QR.");
            } else {
                String error = data == null ? "" : data.getStringExtra("SCAN_ERROR");
                toast(error == null || error.isEmpty() ? "Đã hủy camera quét QR." : "Camera quét QR lỗi: " + error);
            }
            return;
        }
        IntentResult scanned = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scanned != null) {
            if (scanned.getContents() != null) acceptDevicePairing(scanned.getContents());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private String getFirmwareDisplayName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        } catch (Exception error) {
            android.util.Log.w("EspDownload", "Unable to read firmware filename", error);
        }
        return name == null ? uri.toString() : name;
    }

    private String decodeQrImage(Uri uri) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Không mở được ảnh đã chọn.");
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IllegalArgumentException("Ảnh không hợp lệ.");
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();
        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        Result result = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels))), hints);
        return result.getText();
    }

    private void acceptDevicePairing(String contents) {
        try {
            JSONObject pairing = new JSONObject(contents);
            if (!"esp-download-device-pairing".equals(pairing.optString("type")) || pairing.optInt("version") != 1) {
                throw new IllegalArgumentException("QR này không phải mã ghép nối ESP Download.");
            }
            String apiUrl = validateLocalHttpUrl(pairing.optString("deviceApiUrl", ""));
            String token = pairing.optString("deviceApiToken", "").trim();
            if (apiUrl.isEmpty() || !token.matches("(?i)[a-f0-9]{48}")) {
                throw new IllegalArgumentException("QR thiếu URL hoặc Device API token hợp lệ.");
            }
            pairing.put("deviceApiUrl", apiUrl);
            pairing.put("deviceApiToken", token);
            devicePairing = pairing;
            deviceApiUrl = apiUrl;
            deviceApiToken = token;
            prefs.edit().putString("device-pairing", encrypt(pairing.toString())).apply();
            String hostFromQr = pairing.optString("hostUrl", "").trim();
            if (!hostFromQr.isEmpty()) {
                try {
                    host = validateLocalHttpUrl(hostFromQr);
                    prefs.edit().putString("host", host).apply();
                    setValue("host", host);
                } catch (Exception ignored) {}
            }
            setValue("deviceBaseUrl", apiUrl);
            setValue("deviceToken", "");
            String hostSessionToken = pairing.optString("hostSessionToken", "").trim();
            String hostUsername = pairing.optString("hostUsername", "").trim();
            String hostPassword = pairing.optString("hostPassword", "").trim();
            setValue("login-user", hostUsername.isEmpty() ? accountUsername : hostUsername);
            if (hostSessionToken.matches("(?i)[a-f0-9]{64}") && !host.isEmpty()) {
                autoLoginFromQr(hostSessionToken, hostUsername.isEmpty() ? accountUsername : hostUsername);
                return;
            }
            if (!hostUsername.isEmpty() && !hostPassword.isEmpty() && !host.isEmpty()) {
                setValue("login-pass", hostPassword);
                autoLoginFromQrCredentials(hostUsername, hostPassword);
                return;
            }

            if (sessionToken.isEmpty()) {
                toast("QR đã lưu cấu hình ESP32 và host. Nhập tài khoản/mật khẩu local host để đăng nhập; sau đó thiết bị sẽ đồng bộ.");
            } else {
                toast("Đã đọc QR. Đang xác thực và đồng bộ thiết bị với host…");
                syncSavedPairingToHost();
            }
        } catch (Exception error) {
            toast(error.getMessage() == null ? "QR không hợp lệ." : error.getMessage());
        }
    }

    private void autoLoginFromQrCredentials(String qrUsername, String qrPassword) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", qrUsername);
            payload.put("password", qrPassword);
        } catch (Exception ignored) {}
        runIo(() -> request(host, "/api/mobile/auth/login", "POST", payload, ""), result -> {
            String token = result.optString("sessionToken", "");
            if (token.isEmpty()) throw new IllegalStateException("Host không trả về phiên đăng nhập.");
            sessionToken = token;
            accountUsername = result.optString("username", qrUsername);
            prefs.edit().putString("session", encrypt(sessionToken)).putString("username", accountUsername).apply();
            toast("Đã import QR và đăng nhập local host tự động.");
            showMain();
            syncSavedPairingToHost();
        }, error -> toast("Không đăng nhập tự động bằng QR: " + error.getMessage()));
    }

    private void autoLoginFromQr(String qrSessionToken, String qrUsername) {
        runIo(() -> request(host, "/api/auth/session", "GET", null, qrSessionToken), result -> {
            if (!result.optBoolean("authenticated")) {
                toast("Phiên đăng nhập trong QR đã hết hạn. Tạo lại QR rồi quét.");
                return;
            }
            sessionToken = qrSessionToken;
            accountUsername = result.optString("username", qrUsername);
            prefs.edit().putString("session", encrypt(sessionToken)).putString("username", accountUsername).apply();
            toast("Đã import QR và đăng nhập local host tự động.");
            showMain();
            syncSavedPairingToHost();
        }, error -> toast("Không tự đăng nhập được local host: " + error.getMessage()));
    }

    private void syncSavedPairingToHost() {
        if (sessionToken.isEmpty() || deviceApiToken.isEmpty() || deviceApiUrl.isEmpty()) return;
        JSONObject payload = new JSONObject();
        try { payload.put("deviceBaseUrl", deviceApiUrl); payload.put("deviceToken", deviceApiToken); }
        catch (Exception ignored) { return; }
        runIo(() -> api("/api/local/config", "PUT", payload), result -> {
            config = result;
            settingsDirty = false;
            setValue("deviceBaseUrl", deviceApiUrl);
            setValue("deviceToken", "");
            toast(result.optBoolean("settingsSyncPending")
                ? "Đăng nhập thành công; host đang chờ đồng bộ cấu hình xuống firmware ESP32."
                : "Đăng nhập thành công; QR đã đồng bộ Device API và cài đặt với firmware ESP32.");
            refreshStatus(false);
        }, error -> toast("Android đã lưu QR, nhưng chưa đồng bộ firmware ESP32: " + error.getMessage()));
    }

    private String validateLocalHttpUrl(String raw) throws Exception {
        String normalized = normalizeHost(raw);
        URL url = new URL(normalized);
        String target = url.getHost().toLowerCase(Locale.US);
        if (!"http".equals(url.getProtocol()) || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null || !(url.getPath().isEmpty() || url.getPath().equals("/"))) {
            throw new IllegalArgumentException("QR phải trỏ tới API HTTP trong mạng nội bộ.");
        }
        boolean privateAddress = target.endsWith(".local") || target.equals("espdownload.local") || target.matches("^10(?:\\.\\d{1,3}){3}$") || target.matches("^192\\.168(?:\\.\\d{1,3}){2}$") || target.matches("^172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2}$");
        if (!privateAddress) throw new IllegalArgumentException("QR không trỏ tới địa chỉ LAN riêng.");
        return normalized;
    }

    private void scanNearbyEspAccessPoints() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_WIFI_SCAN_PERMISSION);
                return;
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_WIFI_SCAN_PERMISSION);
            return;
        }

        View containerView = (directSetup ? root : body).findViewWithTag("esp-ap-results");
        if (containerView instanceof LinearLayout) {
            LinearLayout results = (LinearLayout) containerView;
            results.removeAllViews();
            label(results, "Đang quét sóng AP từ các thiết bị ESP32 gần bạn…", 13, MUTED, false);
        }

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            toast("Không tìm thấy dịch vụ Wi-Fi trên thiết bị.");
            return;
        }

        try {
            BroadcastReceiver scanReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    try { unregisterReceiver(this); } catch (Exception ignored) {}
                    try {
                        List<ScanResult> scanList = wm.getScanResults();
                        renderDiscoveredEspAccessPoints(scanList);
                    } catch (SecurityException se) {
                        toast("Chưa được cấp quyền quét Wi-Fi.");
                    }
                }
            };
            registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            boolean started = wm.startScan();
            if (!started) {
                List<ScanResult> cached = wm.getScanResults();
                renderDiscoveredEspAccessPoints(cached);
            }
        } catch (Exception e) {
            try {
                renderDiscoveredEspAccessPoints(wm.getScanResults());
            } catch (Exception ignored) {}
        }
    }

    private void renderDiscoveredEspAccessPoints(List<ScanResult> scanResults) {
        View containerView = (directSetup ? root : body).findViewWithTag("esp-ap-results");
        if (!(containerView instanceof LinearLayout)) return;
        LinearLayout container = (LinearLayout) containerView;
        container.removeAllViews();

        List<ScanResult> espAps = new ArrayList<>();
        if (scanResults != null) {
            for (ScanResult r : scanResults) {
                if (r.SSID != null && (r.SSID.equalsIgnoreCase("espsystem") || r.SSID.toLowerCase(Locale.ROOT).startsWith("espsystem") || r.SSID.toLowerCase(Locale.ROOT).startsWith("espdownload"))) {
                    boolean exists = false;
                    for (ScanResult existing : espAps) {
                        if (existing.SSID.equalsIgnoreCase(r.SSID)) { exists = true; break; }
                    }
                    if (!exists) espAps.add(r);
                }
            }
        }

        if (espAps.isEmpty()) {
            LinearLayout emptyCard = cardStart(container);
            label(emptyCard, "Chưa thấy phát sóng 'espsystem' trong tầm quét", 13, INK, true);
            label(emptyCard, "Nếu board vừa cắm điện, vui lòng đợi 5-10s hoặc thử kết nối trực tiếp đến AP mặc định bên dưới:", 12, MUTED, false);
            Button connectDefault = button("⚡ Kết nối AP mặc định (espsystem)", true);
            emptyCard.addView(connectDefault, full());
            connectDefault.setOnClickListener(v -> connectToEspAccessPoint("espsystem", "espsystem"));
            Button manualWifi = button("Mở cài đặt Wi‑Fi trên điện thoại", false);
            emptyCard.addView(manualWifi, full());
            manualWifi.setOnClickListener(v -> openSystemWifiSettings());
            return;
        }

        label(container, "Tìm thấy " + espAps.size() + " trạm AP của ESP32:", 13, INK, true);
        for (ScanResult ap : espAps) {
            LinearLayout item = cardStart(container);
            LinearLayout titleRow = horizontal(item);
            label(titleRow, "📡 " + ap.SSID, 14, GREEN, true);
            titleRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            label(titleRow, ap.level + " dBm", 12, MUTED, true);
            label(item, "Tần số: " + ap.frequency + " MHz · BSSID: " + ap.BSSID, 11, MUTED, false);
            Button connectBtn = button("Xác thực & Kết nối AP này", true);
            item.addView(connectBtn, full());
            final String targetSsid = ap.SSID;
            connectBtn.setOnClickListener(v -> connectToEspAccessPoint(targetSsid, "espsystem"));
        }
    }

    private void connectToEspAccessPoint(String ssid, String password) {
        toast("Đang yêu cầu kết nối tới AP " + ssid + "…");
        View statusView = (directSetup ? root : body).findViewWithTag("ap-connect-feedback");
        if (statusView instanceof TextView) {
            ((TextView) statusView).setText(tr("Đang xác thực kết nối với AP " + ssid + " (192.168.4.1)…"));
            ((TextView) statusView).setTextColor(INK);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiNetworkSpecifier.Builder builder = new WifiNetworkSpecifier.Builder().setSsid(ssid);
            if (password != null && password.length() >= 8) {
                builder.setWpa2Passphrase(password);
            }
            NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(builder.build())
                .build();
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (activeNetworkCallback != null) {
                    try { cm.unregisterNetworkCallback(activeNetworkCallback); } catch (Exception ignored) {}
                }
                activeNetworkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        try { cm.bindProcessToNetwork(network); } catch (Exception ignored) {}
                        main.post(() -> verifyAndShowSetupSuccess(ssid));
                    }
                    @Override
                    public void onUnavailable() {
                        main.post(() -> onApConnectFailure("Không thể kết nối vào AP " + ssid + ". Bạn có thể thử kết nối thủ công trong cài đặt Wi-Fi."));
                    }
                };
                try {
                    cm.requestNetwork(request, activeNetworkCallback);
                    return;
                } catch (Exception e) {
                    // Fallback to manual/legacy
                }
            }
        }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiConfiguration conf = new WifiConfiguration();
                conf.SSID = "\"" + ssid + "\"";
                conf.preSharedKey = "\"" + password + "\"";
                int netId = wm.addNetwork(conf);
                wm.disconnect();
                wm.enableNetwork(netId, true);
                wm.reconnect();
                main.postDelayed(() -> verifyAndShowSetupSuccess(ssid), 3500);
            } else {
                openSystemWifiSettings();
            }
        } catch (Exception e) {
            openSystemWifiSettings();
        }
    }

    private void verifyAndShowSetupSuccess(String ssid) {
        runIo(() -> {
            try {
                return request("http://192.168.4.1", "/setup/status", "GET", null, "");
            } catch (Exception e) {
                throw new RuntimeException("Chưa nhận được phản hồi HTTP từ 192.168.4.1: " + e.getMessage());
            }
        }, result -> {
            toast("Đã xác thực và kết nối thành công với ESP32!");
            showApSetupActionControls(ssid, result);
        }, error -> {
            main.postDelayed(() -> {
                runIo(() -> request("http://192.168.4.1", "/setup/status", "GET", null, ""), result -> {
                    toast("Đã xác thực và kết nối thành công với ESP32!");
                    showApSetupActionControls(ssid, result);
                }, err2 -> {
                    onApConnectFailure("Đã kết nối Wi-Fi nhưng chưa nhận phản hồi từ 192.168.4.1. Hãy kiểm tra điện thoại đã giữ kết nối AP.");
                });
            }, 2000);
        });
    }

    private void showApSetupActionControls(String ssid, JSONObject status) {
        View feedbackView = (directSetup ? root : body).findViewWithTag("ap-connect-feedback");
        if (feedbackView instanceof TextView) {
            ((TextView) feedbackView).setText(tr("✅ Đã kết nối và xác thực với ESP32 qua AP!"));
            ((TextView) feedbackView).setTextColor(GREEN);
        }
        View panelView = (directSetup ? root : body).findViewWithTag("ap-action-panel");
        if (!(panelView instanceof LinearLayout)) return;
        LinearLayout panel = (LinearLayout) panelView;
        panel.removeAllViews();

        LinearLayout card = cardStart(panel);
        label(card, "✅ Đã xác thực kết nối AP thành công!", 15, GREEN, true);
        label(card, "ESP32 AP: " + ssid + " · IP: 192.168.4.1\n" + tr("Trạng thái: Sẵn sàng cấu hình"), 12, INK, false);

        Button openSettingBtn = button("⚙️ Truy cập http://192.168.4.1 để cài đặt", true);
        openSettingBtn.setTextSize(14);
        openSettingBtn.setPadding(0, dp(14), 0, dp(14));
        card.addView(openSettingBtn, full());
        openSettingBtn.setOnClickListener(v -> {
            if (!directSetup) {
                showDirectSetup();
            } else {
                refreshDirectSetup();
            }
        });
    }

    private void onApConnectFailure(String message) {
        View feedbackView = (directSetup ? root : body).findViewWithTag("ap-connect-feedback");
        if (feedbackView instanceof TextView) {
            ((TextView) feedbackView).setText(tr(message));
            ((TextView) feedbackView).setTextColor(Color.rgb(190, 40, 40));
        }
        View panelView = (directSetup ? root : body).findViewWithTag("ap-action-panel");
        if (panelView instanceof LinearLayout) {
            LinearLayout panel = (LinearLayout) panelView;
            panel.removeAllViews();
            Button manualBtn = button("Mở cài đặt Wi‑Fi trên điện thoại", false);
            panel.addView(manualBtn, full());
            manualBtn.setOnClickListener(v -> openSystemWifiSettings());
        }
        toast(message);
    }

    private void openSystemWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            } else {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception ignored) {}
        }
    }

    private void showDirectSetup() {
        directSetup = true;
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(PAGE); root.setPadding(dp(18),dp(22),dp(18),dp(18));
        scroll.addView(root);
        setContentView(scroll);
        LinearLayout appearance = horizontal(root);
        appearance.addView(themeButton(), new LinearLayout.LayoutParams(0, dp(42), 1));
        appearance.addView(languageButton(), new LinearLayout.LayoutParams(0, dp(42), 1));
        Button back=button("← Quay lại đăng nhập",false); root.addView(back,full()); back.setOnClickListener(v->showLogin());
        label(root,"Thiết lập Wi‑Fi ESP32",24,INK,true);
        label(root,"Kết nối điện thoại vào AP espsystem (mật khẩu espsystem). Trang này giao tiếp trực tiếp với 192.168.4.1 để cấu hình Wi-Fi cho ESP32.",13,MUTED,false);

        LinearLayout apScanCard = cardStart(root);
        label(apScanCard, "Tìm & Kết nối AP ESP32", 15, INK, true);
        label(apScanCard, "Quét tìm AP phát từ ESP32 (mặc định 'espsystem'), xác thực kết nối và mở giao diện cài đặt qua 192.168.4.1.", 12, MUTED, false);
        Button scanApDirect = button("🔍 Quét tìm AP ESP32", false);
        apScanCard.addView(scanApDirect, full());
        scanApDirect.setOnClickListener(v -> scanNearbyEspAccessPoints());
        LinearLayout apResultsDirect = column(apScanCard);
        apResultsDirect.setTag("esp-ap-results");
        TextView apFeedbackDirect = label(apScanCard, "", 12, MUTED, false);
        apFeedbackDirect.setTag("ap-connect-feedback");
        LinearLayout apActionDirect = column(apScanCard);
        apActionDirect.setTag("ap-action-panel");

        TextView state=label(root,"Đang đọc trạng thái AP…",13,INK,true);state.setTag("direct-ap-status");
        Button scan=button("Quét Wi‑Fi quanh ESP32",false);root.addView(scan,full());scan.setOnClickListener(v->scanWifi());
        LinearLayout networks=column(root);networks.setTag("wifi-networks");
        label(root,"Wi‑Fi SSID",12,MUTED,true);EditText ssid=input("wifi-ssid","Wi‑Fi SSID","","Chọn mạng hoặc nhập SSID",InputType.TYPE_CLASS_TEXT,false);root.addView(ssid,full());
        label(root,"Mật khẩu Wi‑Fi",12,MUTED,true);EditText pass=input("wifi-pass","Mật khẩu Wi‑Fi","","Để trống nếu mạng mở",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,false);root.addView(pass,full());
        
        Button save = button("Lưu Wi‑Fi vào ESP32", true); root.addView(save, full());
        TextView wifiSaveFeedback = label(root, "", 12, MUTED, false);
        wifiSaveFeedback.setTag("wifi-save-feedback");
        LinearLayout gatewayResultBox = column(root);
        gatewayResultBox.setTag("gateway-result-box");

        save.setOnClickListener(v -> {
            final String chosenSsid = value("wifi-ssid");
            final String chosenPass = value("wifi-pass");
            if (chosenSsid.isEmpty()) {
                toast(tr("Vui lòng chọn hoặc nhập Wi-Fi SSID."));
                return;
            }
            save.setEnabled(false);
            wifiSaveFeedback.setText(tr("Đang gửi thông tin Wi-Fi tới ESP32…"));
            wifiSaveFeedback.setTextColor(INK);

            JSONObject p = new JSONObject();
            try {
                p.put("ssid", chosenSsid);
                p.put("password", chosenPass);
            } catch (Exception ignored) {}

            runIo(() -> request("http://192.168.4.1", "/setup/network", "POST", p, ""), r -> {
                setValue("wifi-pass", "");
                wifiSaveFeedback.setText(tr("Đã gửi Wi-Fi! Đang đợi ESP32 kết nối vào Gateway và nhận IP mới…"));
                wifiSaveFeedback.setTextColor(INK);
                pollEsp32GatewayIp(save, wifiSaveFeedback, gatewayResultBox, chosenSsid, 1);
            }, e -> {
                save.setEnabled(true);
                wifiSaveFeedback.setText(tr("Lỗi gửi Wi-Fi: ") + e.getMessage());
                wifiSaveFeedback.setTextColor(Color.rgb(190, 40, 40));
            });
        });
        refreshDirectSetup();
    }

    private void pollEsp32GatewayIp(Button saveBtn, TextView feedback, LinearLayout resultContainer, String targetSsid, int attempt) {
        if (!directSetup) return;
        if (attempt > 20) {
            saveBtn.setEnabled(true);
            feedback.setText(tr("Chưa nhận được phản hồi IP từ ESP32 sau 30s. Thử kiểm tra lại mật khẩu Wi-Fi hoặc quét tìm thiết bị trong mạng LAN."));
            feedback.setTextColor(Color.rgb(190, 40, 40));
            return;
        }

        feedback.setText(tr("Đang đợi ESP32 nhận IP từ Gateway router (lần thử ") + attempt + "/20)…");
        feedback.setTextColor(INK);

        main.postDelayed(() -> {
            if (!directSetup) return;
            runIo(() -> {
                try {
                    return request("http://192.168.4.1", "/setup/status", "GET", null, "");
                } catch (Exception e) {
                    return null;
                }
            }, status -> {
                if (!directSetup) return;
                if (status != null && status.optBoolean("wifiConnected") && !status.optString("wifiIp").isEmpty() && !"0.0.0.0".equals(status.optString("wifiIp"))) {
                    final String newIp = status.optString("wifiIp");
                    displayGatewaySuccessAndHandover(resultContainer, feedback, status.optString("wifiSsid", targetSsid), newIp);
                } else {
                    pollEsp32GatewayIp(saveBtn, feedback, resultContainer, targetSsid, attempt + 1);
                }
            }, error -> {
                if (!directSetup) return;
                pollEsp32GatewayIp(saveBtn, feedback, resultContainer, targetSsid, attempt + 1);
            });
        }, 1500);
    }

    private void displayGatewaySuccessAndHandover(LinearLayout container, TextView feedback, String ssid, String newIp) {
        feedback.setText(tr("✅ ESP32 đã kết nối thành công vào Wi-Fi Gateway!"));
        feedback.setTextColor(GREEN);

        container.removeAllViews();
        LinearLayout card = cardStart(container);
        label(card, "🎉 ESP32 đã kết nối vào Gateway!", 16, GREEN, true);
        label(card, tr("Wi-Fi: ") + ssid + "\n" + tr("Địa chỉ IP do Gateway cấp: http://") + newIp, 13, INK, false);

        Button goToEsp32Btn = button("🚀 Chuyển sang cấu hình ESP32 (http://" + newIp + ")", true);
        goToEsp32Btn.setTextSize(14);
        goToEsp32Btn.setPadding(0, dp(14), 0, dp(14));
        card.addView(goToEsp32Btn, full());

        goToEsp32Btn.setOnClickListener(v -> {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.bindProcessToNetwork(null);
                    if (activeNetworkCallback != null) {
                        try { cm.unregisterNetworkCallback(activeNetworkCallback); } catch (Exception ignored) {}
                        activeNetworkCallback = null;
                    }
                }
            } catch (Exception ignored) {}

            final String newHost = "http://" + newIp;
            host = newHost;
            prefs.edit().putString("host", host).apply();
            directSetup = false;
            toast(tr("Đã chuyển kết nối sang ESP32 tại ") + newHost);
            showMain();
            openPage("settings");
        });
    }

    private void refreshDirectSetup() {
        if(!directSetup)return;
        runIo(()->request("http://192.168.4.1","/setup/status","GET",null,""),result->{if(!directSetup)return;View v=root.findViewWithTag("direct-ap-status");if(v instanceof TextView)((TextView)v).setText(tr("Wi‑Fi: "+(result.optBoolean("wifiConnected")?result.optString("wifiSsid")+" · "+result.optString("wifiIp")+" · "+result.optInt("rssi")+" dBm":"chưa kết nối")+"\nAP: "+result.optString("apSsid")+" · "+result.optString("apIp")+" · "+(result.optBoolean("apActive")?"đang phát":"tắt")));},error->{if(directSetup){View v=root.findViewWithTag("direct-ap-status");if(v instanceof TextView)((TextView)v).setText(tr("Không truy cập được 192.168.4.1. Hãy kiểm tra điện thoại đã nối AP espsystem chưa."));}});
    }

    private void showMain() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(PAGE); setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout top = horizontal(root); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(16), dp(8), dp(12), dp(8));
        TextView badge = new TextView(this);
        badge.setText("ED");
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(haSurface(GREEN, 8, GREEN));
        top.addView(badge, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout titleBox = new LinearLayout(this); titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(9), 0, 0, 0);
        label(titleBox, "ESP Download", 15, INK, true); label(titleBox, "LOCAL CONTROL", 8, MUTED, true);
        top.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        Button language = languageButton();
        language.setMinWidth(dp(38));
        top.addView(language, new LinearLayout.LayoutParams(dp(42), dp(42)));
        Button theme = themeButton(); top.addView(theme, new LinearLayout.LayoutParams(dp(44), dp(42)));
        Button refresh = button("↻", false);
        refresh.setTextSize(18);
        refresh.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        refresh.setTextColor(darkTheme ? Color.rgb(231, 238, 233) : Color.rgb(27, 61, 47));
        refresh.setContentDescription(tr("Làm mới trạng thái"));
        top.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(42)));
        refresh.setOnClickListener(v -> refreshStatus(true));
        Button reboot = button("⟲", false);
        reboot.setTextSize(18);
        reboot.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        reboot.setContentDescription(tr("Khởi động lại ESP32"));
        top.addView(reboot, new LinearLayout.LayoutParams(dp(44), dp(42)));
        reboot.setTextColor(darkTheme ? Color.rgb(255, 125, 110) : Color.rgb(190, 45, 35));
        reboot.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                .setTitle(tr("Khởi động lại ESP32"))
                .setMessage(tr("Bạn có chắc chắn muốn khởi động lại ESP32? Thiết bị sẽ ngắt kết nối trong vài giây."))
                .setPositiveButton(tr("Khởi động lại"), (dialog, which) -> {
                    runIo(() -> request(host, "/api/local/device/reboot", "POST", new JSONObject(), sessionToken),
                        r -> toast(r.optString("message", "ESP32 đang khởi động lại...")),
                        e -> toast("Lỗi: " + e.getMessage()));
                })
                .setNegativeButton(tr("Hủy"), null)
                .show();
        });
        HorizontalScrollView tabScroll = new HorizontalScrollView(this); tabScroll.setHorizontalScrollBarEnabled(false); tabScroll.setFillViewport(false);
        tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL); tabs.setPadding(dp(10), dp(5), dp(10), dp(8)); tabScroll.addView(tabs); root.addView(tabScroll);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(14), dp(8), dp(14), dp(20));
        ScrollView scroller = new ScrollView(this); scroller.setFillViewport(true); scroller.addView(body); root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        String[][] nav = {{"dashboard", "Tổng quan"}, {"queue", "Hàng đợi"}, {"wifi", "Wi‑Fi / AP"}, {"settings", "Local host"}, {"downloads", "Tải xuống"}, {"telegram", "Telegram"}, {"logs", "Log"}, {"system", "Check System"}, {"security", "Bảo mật"}};
        for (String[] item : nav) {
            Button tab = button(item[1], false); tab.setTextSize(11); tab.setPadding(dp(10),0,dp(10),0); tabs.addView(tab, new LinearLayout.LayoutParams(-2, dp(38)));
            tab.setBackground(null);
            tab.setOnClickListener(v -> openPage(item[0]));
        }
        openPage(activePage);
        refreshStatus(false);
    }

    private void openPage(String page) { openPage(page, true); }

    private void openPage(String page, boolean fetchConfig) {
        activePage = page; fields.clear(); rendering = true;
        settingsDirty = downloadsDirty = securityDirty = false;
        body.removeAllViews();
        switch (page) {
            case "queue": renderQueue(); break;
            case "wifi": renderWifi(); break;
            case "settings": renderSettings(); break;
            case "downloads": renderDownloads(); break;
            case "telegram": renderTelegram(); break;
            case "logs": renderLogs(); break;
            case "system": renderSystemCheck(); break;
            case "security": renderSecurity(); break;
            default: renderDashboard(); break;
        }
        rendering = false;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View child = tabs.getChildAt(i);
            if ("language-toggle".equals(child.getTag())) continue;
            if (child instanceof Button) {
                boolean selected = ((Button) child).getText().toString().equals(tr(navLabel(page)));
                child.setAlpha(selected ? 1f : .78f);
                ((Button) child).setBackground(null);
                ((Button) child).setTextColor(selected ? GREEN : MUTED);
                ((Button) child).setPaintFlags(selected
                    ? ((Button) child).getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    : ((Button) child).getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            }
        }
        if (fetchConfig && (page.equals("settings") || page.equals("downloads") || page.equals("security"))) loadConfigForPage(page);
        if (page.equals("dashboard")) {
            refreshStatus(false);
            loadDeviceData("dashboard");
        }
        if (page.equals("queue") || page.equals("logs")) loadDeviceData(page);
        if (page.equals("wifi")) refreshWifiState();
        if (page.equals("telegram")) refreshStatus(false);
    }

    private String navLabel(String page) {
        switch (page) { case "queue": return "Hàng đợi"; case "wifi": return "Wi‑Fi / AP"; case "settings": return "Local host"; case "downloads": return "Tải xuống"; case "telegram": return "Telegram"; case "logs": return "Log"; case "system": return "Check System"; case "security": return "Bảo mật"; default: return "Tổng quan"; }
    }

    private void renderSystemCheck() {
        heading("Kiểm tra hệ thống", "Quét tình trạng các gói và môi trường thực thi trên máy tính Windows host.");
        LinearLayout actions = horizontal(body);
        actions.setPadding(0, 0, 0, dp(14));
        Button btnCheck = button("↻ Quét lại", false);
        actions.addView(btnCheck, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button btnInstall = button("⚡ Cài gói thiếu", true);
        actions.addView(btnInstall, new LinearLayout.LayoutParams(0, dp(40), 1.2f));
        Button btnAppUpdate = button("🔄 Cập nhật APK", false);
        actions.addView(btnAppUpdate, new LinearLayout.LayoutParams(0, dp(40), 1.2f));
        btnAppUpdate.setOnClickListener(v -> checkForAppUpdate(true));

        LinearLayout cards = column(body);
        cards.setTag("system-tools-list");
        label(cards, "Tình trạng gói trên Windows", 12, GREEN, true);
        addInfoCard(cards, "Đang tải", "system-loading", "Đang quét môi trường host...");
        renderOtgFlasher(body);

        btnCheck.setOnClickListener(v -> loadSystemTools(cards));
        btnInstall.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                .setTitle(tr("Cài đặt gói thiếu"))
                .setMessage(tr("Chạy lệnh cài đặt các gói cần thiết (yt-dlp, FFmpeg, aria2c, Deno, gallery-dl) qua PowerShell trên máy tính?"))
                .setPositiveButton(tr("Bắt đầu cài"), (dialog, which) -> {
                    toast("Đã gửi lệnh cài đặt tới Windows Host...");
                    runIo(() -> request(host, "/api/local/system/install", "POST", new JSONObject(), sessionToken),
                        r -> {
                            toast("Tiến trình cài đặt hoàn tất.");
                            loadSystemTools(cards);
                        },
                        e -> toast("Lỗi cài đặt: " + e.getMessage()));
                })
                .setNegativeButton(tr("Hủy"), null)
                .show();
        });

        loadSystemTools(cards);
        main.postDelayed(() -> {
            if (activePage.equals("system") && activeFlasher == null) scanUsbSerialDevices();
        }, 350);
    }

    private void renderOtgFlasher(LinearLayout parent) {
        LinearLayout panel = cardStart(parent);
        label(panel, "Kết nối và nạp firmware ESP32", 14, GREEN, true);
        label(panel, "Cắm board qua cáp USB OTG để quét cổng nối tiếp và nhận dạng chip.", 11, MUTED, false);

        LinearLayout connectRow = horizontal(panel);
        Button scan = button("🔌 Quét / kết nối OTG", true);
        connectRow.addView(scan, new LinearLayout.LayoutParams(0, dp(44), 1));
        scan.setOnClickListener(v -> scanUsbSerialDevices());

        usbStatus = label(panel, "Chưa kết nối USB OTG.", 11, MUTED, false);
        usbStatus.setTag("usb-flash-status");
        String[] models = {"Tự nhận dạng", "ESP32", "ESP32-S2", "ESP32-S3", "ESP32-C3", "ESP32-C5", "ESP32-C6"};
        chipModelSpinner = spinner(models);
        panel.addView(chipModelSpinner, full());
        chipModelSpinner.setSelection(Math.max(0, Math.min(models.length - 1,
            prefs.getInt("flash-selected-model", 0))));
        chipModelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parentView, View view, int position, long id) {
                prefs.edit().putInt("flash-selected-model", position).apply();
                updateFlashConfigFields(panel, models[position]);
                refreshFlashButtonState();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parentView) {}
        });

        LinearLayout configRow = horizontal(panel);
        addFlashConfigSpinner(configRow, "flash-size", "Flash size", new String[]{"Auto", "2 MB", "4 MB", "8 MB", "16 MB"});
        addFlashConfigSpinner(configRow, "flash-mode", "Mode", new String[]{"DIO", "QIO", "DOUT", "QOUT"});
        addFlashConfigSpinner(configRow, "flash-frequency", "Frequency", new String[]{"40 MHz", "26.7 MHz", "20 MHz", "80 MHz"});
        label(panel, "Profile lưu riêng theo model; Mode/Frequency dùng để đối chiếu header .bin, không sửa firmware.", 10, MUTED, false);
        label(panel, "Baud rate", 10, MUTED, true);
        flashBaudField = input("flash-baud", "Baud rate", String.valueOf(prefs.getInt(flashConfigKey(currentFlashModel(), "baud"), 460800)),
            "460800", InputType.TYPE_CLASS_NUMBER, false);
        panel.addView(flashBaudField, full());
        flashBaudField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                try { prefs.edit().putInt(flashConfigKey(currentFlashModel(), "baud"), Integer.parseInt(s.toString())).apply(); }
                catch (NumberFormatException ignored) {}
            }
            public void afterTextChanged(Editable e) {}
        });

        LinearLayout firmwareSources = horizontal(panel);
        Button hostFirmware = button("Từ thư mục firmware trên PC", false);
        Button chooseFirmware = button("Chọn file .bin từ điện thoại", false);
        firmwareSources.addView(hostFirmware, new LinearLayout.LayoutParams(0, dp(60), 1));
        firmwareSources.addView(chooseFirmware, new LinearLayout.LayoutParams(0, dp(60), 1));
        firmwareSelection = label(panel, "Chưa chọn firmware.", 10, MUTED, false);
        firmwareSelection.setTag("firmware-selection");
        hostFirmware.setOnClickListener(v -> loadHostFirmwareFiles());
        chooseFirmware.setOnClickListener(v -> chooseFirmwareFiles());
        firmwareRows = column(panel);

        String defaultRepoUrl = "https://github.com/neopro90/Esp32-Download";
        String savedGithubUrl = prefs.getString("flash-github-url", defaultRepoUrl);
        if (savedGithubUrl.contains("raw.githubusercontent.com")) savedGithubUrl = defaultRepoUrl;
        EditText githubUrl = input("flash-github-url", "URL GitHub firmware (để trống sẽ tự lấy theo chip)", savedGithubUrl, defaultRepoUrl,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        panel.addView(githubUrl, full());
        githubUrl.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                prefs.edit().putString("flash-github-url", s.toString()).apply();
            }
            public void afterTextChanged(Editable e) {}
        });
        Button downloadGithub = button("Lấy firmware từ URL", false);
        panel.addView(downloadGithub, full());
        downloadGithub.setOnClickListener(v -> downloadGithubFirmware(githubUrl.getText().toString().trim()));
        label(panel, "Tự động nhận diện phần cứng ESP32 qua OTG/chọn thủ công và tải đúng bản .bin vào Download/Esp System.", 10, MUTED, false);
        label(panel, "Với nhiều file, chọn từng .bin và nhập địa chỉ flash tương ứng trước khi nạp.", 10, MUTED, false);
        flashButton = button("Nạp firmware", true);
        panel.addView(flashButton, full());
        flashButton.setOnClickListener(v -> confirmAndFlashFirmware());
        flashProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        flashProgressBar.setMax(100);
        flashProgressBar.setProgress(0);
        flashProgressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        LinearLayout.LayoutParams progressParams = full();
        progressParams.topMargin = dp(8);
        panel.addView(flashProgressBar, progressParams);
        flashProgressBar.setVisibility(View.GONE);
        flashProgressText = label(panel, "", 10, MUTED, false);
        flashProgressText.setVisibility(View.GONE);
        renderFirmwareRows();
        refreshFlashButtonState();
    }

    private void addFlashConfigSpinner(LinearLayout row, String key, String title, String[] choices) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        label(box, title, 9, MUTED, true);
        Spinner spinner = spinner(choices);
        box.addView(spinner, full());
        if (key.equals("flash-size")) flashSizeSpinner = spinner;
        else if (key.equals("flash-mode")) flashModeSpinner = spinner;
        else if (key.equals("flash-frequency")) flashFrequencySpinner = spinner;
        row.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
        String model = currentFlashModel();
        int selection = prefs.getInt(flashConfigKey(model, key), 0);
        spinner.setSelection(Math.max(0, Math.min(choices.length - 1, selection)));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt(flashConfigKey(currentFlashModel(), key), position).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        spinner.setTag("flash-config-" + key);
    }

    private void updateFlashConfigFields(LinearLayout panel, String model) {
        // The spinner values are indexed per model; rebuild only when a model is changed.
        View configRow = panel.getChildAt(5);
        if (!(configRow instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) configRow;
        String[] keys = {"flash-size", "flash-mode", "flash-frequency"};
        for (int i = 0; i < row.getChildCount() && i < keys.length; i++) {
            View box = row.getChildAt(i);
            if (!(box instanceof LinearLayout) || ((LinearLayout) box).getChildCount() < 2) continue;
            View control = ((LinearLayout) box).getChildAt(1);
            if (control instanceof Spinner) {
                Spinner spinner = (Spinner) control;
                spinner.setSelection(prefs.getInt(flashConfigKey(model, keys[i]), 0));
            }
            if (flashBaudField != null) {
                flashBaudField.setText(String.valueOf(prefs.getInt(flashConfigKey(model, "baud"), 460800)));
            }
        }
    }

    private String currentFlashModel() {
        if (chipModelSpinner == null || chipModelSpinner.getSelectedItemPosition() == 0) return "auto";
        return chipModelSpinner.getSelectedItem().toString().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private String flashConfigKey(String model, String key) {
        String normalizedModel = model.equals("auto") ? "auto" : model.toLowerCase(Locale.ROOT).replace('-', '_');
        return "flash-config-" + normalizedModel + "-" + key;
    }

    private Spinner spinner(String[] choices) {
        Spinner control = new Spinner(this);
        control.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        return control;
    }

    private void loadHostFirmwareFiles() {
        setUsbStatus("Đang quét file firmware trong thư mục ESP32 trên PC…", false);
        runIo(() -> request(host, "/api/local/firmware", "GET", null, sessionToken), result -> {
            JSONArray files = result.optJSONArray("files");
            if (files == null || files.length() == 0) {
                toast("Không tìm thấy file .bin trong thư mục firmware trên PC.");
                return;
            }
            List<JSONObject> available = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null) continue;
                available.add(file);
                labels.add(file.optString("environment") + " · " + file.optString("name")
                    + " · " + bytes(file.optLong("size", 0)));
            }
            if (available.isEmpty()) {
                toast("Không tìm thấy file .bin trong thư mục firmware trên PC.");
                return;
            }
            new android.app.AlertDialog.Builder(this)
                .setTitle(tr("Firmware trong thư mục ESP32 trên PC"))
                .setItems(labels.toArray(new String[0]), (dialog, which) -> downloadHostFirmware(available.get(which)))
                .setNegativeButton(tr("Hủy"), null)
                .show();
        }, error -> {
            setUsbStatus("Không đọc được firmware từ PC: " + error.getMessage(), false);
            toast(error.getMessage());
        });
    }

    private void downloadHostFirmware(JSONObject file) {
        String fileId = file.optString("id", "");
        if (fileId.isEmpty()) {
            toast("Firmware file identifier is missing.");
            return;
        }
        final byte[][] downloaded = new byte[1][];
        setUsbStatus("Đang tải " + file.optString("name") + " từ PC…", false);
        runIo(() -> {
            downloaded[0] = requestBinary(host, "/api/local/firmware/download?file=" + encode(fileId), sessionToken);
            JSONObject result = new JSONObject();
            result.put("name", file.optString("name"));
            return result;
        }, result -> {
            byte[] data = downloaded[0];
            if (data == null || data.length == 0) {
                toast("Firmware tải từ PC đang rỗng.");
                return;
            }
            firmwareImages.add(new FirmwareImage(result.optString("name", "firmware.bin"), null, data));
            renderFirmwareRows();
            refreshFlashButtonState();
            toast("Đã tải firmware từ PC (" + data.length + " bytes).");
        }, error -> {
            setUsbStatus("Tải firmware từ PC thất bại: " + error.getMessage(), false);
            toast(error.getMessage());
        });
    }

    private void chooseFirmwareFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/x-binary", "application/macbinary"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_FIRMWARE_FILES);
        } catch (Exception error) {
            toast("Không mở được trình chọn firmware: " + error.getMessage());
        }
    }

    private void renderFirmwareRows() {
        if (firmwareSelection != null) {
            firmwareSelection.setText(firmwareImages.isEmpty()
                ? tr("Chưa chọn firmware.")
                : tr("Đã chọn ") + firmwareImages.size() + tr(" file firmware."));
        }
        if (firmwareRows == null) return;
        firmwareRows.removeAllViews();
        for (FirmwareImage image : firmwareImages) {
            LinearLayout row = horizontal(firmwareRows);
            TextView name = label(row, image.name, 10, INK, true);
            name.setMaxLines(2);
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, .2f));
            EditText offset = input("flash-offset-" + firmwareImages.indexOf(image), "Địa chỉ", guessFlashOffset(image.name),
                "0x0", InputType.TYPE_CLASS_TEXT, false);
            offset.setSingleLine(true);
            offset.setTextSize(11);
            row.addView(offset, new LinearLayout.LayoutParams(dp(96), dp(42)));
            image.offsetField = offset;
        }
    }

    private String guessFlashOffset(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.contains("factory") || lower.contains("merged")) return "0x0";
        if (lower.contains("bootloader")) return "0x1000";
        if (lower.contains("partition")) return "0x8000";
        if (lower.contains("boot_app0")) return "0xe000";
        if (lower.contains("firmware") || lower.contains("application") || lower.contains("app.bin")) return "0x10000";
        return "0x0";
    }

    private String resolveTargetChipModel() {
        if (detectedChip != null && !detectedChip.trim().isEmpty() && !"auto".equalsIgnoreCase(detectedChip)) {
            return detectedChip.trim();
        }
        if (chipModelSpinner != null && chipModelSpinner.getSelectedItemPosition() > 0) {
            return chipModelSpinner.getSelectedItem().toString().trim();
        }
        return "ESP32";
    }

    private String[] getFirmwareReleaseInfo(String chipModel) {
        String m = (chipModel == null ? "" : chipModel).toUpperCase(Locale.ROOT);
        if (m.contains("S2")) return new String[]{"ESP32-S2", "ESP32-S2-firmware.bin", "0x10000"};
        if (m.contains("S3")) return new String[]{"ESP32-S3", "ESP32-S3-firmware.bin", "0x10000"};
        if (m.contains("C3")) return new String[]{"ESP32-C3", "ESP32-C3-firmware.bin", "0x10000"};
        if (m.contains("C5")) return new String[]{"ESP32-C5", "ESP32-C5-firmware.bin", "0x10000"};
        if (m.contains("C6")) return new String[]{"ESP32-C6", "ESP32-C6-firmware.bin", "0x10000"};
        return new String[]{"ESP32-WROOM-32", "ESP32-WROOM-32-firmware.bin", "0x10000"};
    }

    private Uri saveFirmwareToDownloads(String filename, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Esp System");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Không tạo được file trong Download/Esp System.");
            try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IllegalStateException("Không mở được file để ghi.");
                output.write(data);
                output.flush();
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Esp System");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Không tạo được thư mục Download/Esp System.");
            File file = new File(dir, filename);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
                fos.flush();
            }
            return Uri.fromFile(file);
        }
    }

    private void cleanupFirmwareTempFiles() {
        try {
            File cacheFw = new File(getCacheDir(), "esp_firmware_tmp.bin");
            if (cacheFw.exists()) cacheFw.delete();
            File[] files = getCacheDir().listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".bin") || f.getName().startsWith("esp_firmware")) {
                        f.delete();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void cleanupApkTempFiles() {
        try {
            File cacheApk = new File(getCacheDir(), "esp-download-update.apk");
            if (cacheApk.exists()) cacheApk.delete();
            File[] files = getCacheDir().listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".apk") || f.getName().startsWith("esp-download-update")) {
                        f.delete();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void cleanupTempFiles() {
        cleanupFirmwareTempFiles();
        cleanupApkTempFiles();
    }

    private void checkForAppUpdate(boolean userInitiated) {
        if (userInitiated) {
            toast("Đang kiểm tra bản cập nhật mới từ GitHub…");
        }
        runIo(() -> {
            URL url = new URL("https://raw.githubusercontent.com/neopro90/Esp32-Download/main/version.json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "EspDownload-Android-App/" + BuildConfig.VERSION_NAME);
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("Máy chủ phản hồi HTTP " + code);
            }
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            } finally {
                conn.disconnect();
            }
        }, json -> {
            try {
                int remoteVersionCode = json.optInt("versionCode", 0);
                String remoteVersionName = json.optString("version", "");
                String apkUrl = json.optString("apkUrl", "");
                String releaseNotes = json.optString("releaseNotes", "");

                int currentVersionCode = BuildConfig.VERSION_CODE;
                String currentVersionName = BuildConfig.VERSION_NAME;

                boolean hasNewVersion = remoteVersionCode > currentVersionCode;
                if (!hasNewVersion && !remoteVersionName.isEmpty() && !currentVersionName.equalsIgnoreCase(remoteVersionName)) {
                    hasNewVersion = true;
                }

                if (hasNewVersion && !apkUrl.isEmpty()) {
                    String msg = "Đã có phiên bản mới: v" + remoteVersionName + " (hiện tại: v" + currentVersionName + ")\n\n"
                            + (releaseNotes.isEmpty() ? "Bấm Cập nhật để tải về và cài đặt đè lên phiên bản hiện tại." : "Nội dung cập nhật:\n" + releaseNotes);
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(tr("Có bản cập nhật mới!"))
                            .setMessage(tr(msg))
                            .setPositiveButton(tr("Cập nhật ngay"), (dialog, which) -> downloadAndInstallApk(apkUrl, remoteVersionName))
                            .setNegativeButton(tr("Để sau"), null)
                            .show();
                } else {
                    if (userInitiated) {
                        toast("Ứng dụng đang ở phiên bản mới nhất (v" + currentVersionName + ").");
                    }
                }
            } catch (Exception e) {
                if (userInitiated) toast("Không thể xử lý thông tin cập nhật: " + e.getMessage());
            }
        }, error -> {
            if (userInitiated) toast("Kiểm tra cập nhật thất bại: " + error.getMessage());
        });
    }

    private void downloadAndInstallApk(String apkUrl, String versionName) {
        toast("Đang tải bản cập nhật APK v" + versionName + "…");
        cleanupApkTempFiles();
        File apkFile = new File(getCacheDir(), "esp-download-update.apk");

        runIo(() -> {
            URL current = new URL(apkUrl);
            for (int redirects = 0; redirects <= 5; redirects++) {
                HttpURLConnection conn = (HttpURLConnection) current.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(false);
                int code = conn.getResponseCode();
                if (code >= 300 && code <= 399) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || redirects == 5) throw new IllegalStateException("Redirect không hợp lệ");
                    current = new URL(current, loc);
                    continue;
                }
                if (code != 200) {
                    conn.disconnect();
                    throw new IllegalStateException("HTTP " + code);
                }
                try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(apkFile)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                    fos.flush();
                } finally {
                    conn.disconnect();
                }
                break;
            }
            if (!apkFile.exists() || apkFile.length() < 1000) {
                throw new IllegalStateException("File APK tải về không hoàn chỉnh");
            }
            return new JSONObject();
        }, success -> {
            toast("Tải APK thành công! Đang mở trình cài đặt…");
            installApkFile(apkFile);
        }, error -> {
            cleanupApkTempFiles();
            toast("Lỗi tải bản cập nhật APK: " + error.getMessage());
        });
    }

    private void installApkFile(File apkFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(tr("Quyền cài đặt ứng dụng"))
                            .setMessage(tr("Để cài đặt cập nhật tự động, bạn cần cấp quyền 'Cài đặt ứng dụng không rõ nguồn' cho ESP Download trong Cài đặt."))
                            .setPositiveButton(tr("Đi tới Cài đặt"), (d, w) -> {
                                Intent reqIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(reqIntent);
                            })
                            .setNegativeButton(tr("Hủy"), null)
                            .show();
                    return;
                }
            }
            Uri apkUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    apkFile
            );
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("Không thể mở trình cài đặt: " + e.getMessage());
        }
    }

    private void downloadGithubFirmware(String source) {
        String targetModel = resolveTargetChipModel();
        String[] fwInfo = getFirmwareReleaseInfo(targetModel);
        String targetFolder = fwInfo[0];
        String targetFilename = fwInfo[1];
        String targetOffset = fwInfo[2];

        String address = source == null ? "" : source.trim();
        String defaultBase = "https://raw.githubusercontent.com/neopro90/Esp32-Download/main/releases/v1.3/";

        if (address.isEmpty() || address.equalsIgnoreCase("https://github.com/neopro90/Esp32-Download")
            || address.equalsIgnoreCase("https://github.com/neopro90/Esp32-Download/")) {
            address = defaultBase + targetFilename;
        } else if (address.startsWith("https://github.com/") && address.contains("/blob/")) {
            address = "https://raw.githubusercontent.com/" + address.substring("https://github.com/".length()).replaceFirst("/blob/", "/");
        } else if (!address.toLowerCase(Locale.ROOT).endsWith(".bin")) {
            if (!address.endsWith("/")) address += "/";
            address += targetFilename;
        }

        if (!address.startsWith("https://")) {
            toast("Chỉ chấp nhận URL HTTPS.");
            return;
        }

        final String firmwareUrl = address;
        final String finalTargetName = Uri.parse(firmwareUrl).getLastPathSegment() != null ? Uri.parse(firmwareUrl).getLastPathSegment() : targetFilename;

        setUsbStatus("Đang tải firmware " + targetModel + " (" + finalTargetName + ") từ HTTPS…", false);
        cleanupFirmwareTempFiles();
        final byte[][] downloaded = new byte[1][];
        final Uri[] savedUri = new Uri[1];

        runIo(() -> {
            byte[] data = downloadFirmwareBytes(firmwareUrl);
            downloaded[0] = data;
            File tempFwFile = new File(getCacheDir(), "esp_firmware_tmp.bin");
            try (FileOutputStream fos = new FileOutputStream(tempFwFile)) {
                fos.write(data);
                fos.flush();
            }
            savedUri[0] = Uri.fromFile(tempFwFile);
            JSONObject result = new JSONObject();
            result.put("name", finalTargetName);
            result.put("size", data.length);
            result.put("model", targetModel);
            result.put("offset", targetOffset);
            return result;
        }, result -> {
            try {
                byte[] bytes = downloaded[0];
                if (bytes == null || bytes.length == 0) throw new IllegalStateException("Firmware tải về đang rỗng.");
                firmwareImages.clear();
                FirmwareImage fwImg = new FirmwareImage(result.optString("name", finalTargetName), savedUri[0], bytes);
                firmwareImages.add(fwImg);
                renderFirmwareRows();
                refreshFlashButtonState();
                String msg = "Đã tải " + result.optString("name") + " (" + (bytes.length / 1024) + " KB). Sẵn sàng nạp!";
                setUsbStatus(msg, true);
                toast(msg);
            } catch (Exception error) {
                toast("Không xử lý được firmware đã tải: " + error.getMessage());
            }
        }, error -> {
            cleanupFirmwareTempFiles();
            setUsbStatus("Lỗi tải firmware: " + error.getMessage(), false);
            toast("Lỗi tải firmware: " + error.getMessage());
        });
    }

    private byte[] downloadFirmwareBytes(String source) throws Exception {
        URL current = new URL(source);
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())) throw new IllegalArgumentException("Redirect không an toàn: cần HTTPS.");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code >= 300 && code <= 399) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == 5) throw new IllegalStateException("URL redirect không hợp lệ.");
                current = new URL(current, location);
                continue;
            }
            if (code < 200 || code >= 300) {
                connection.disconnect();
                throw new IllegalStateException("Máy chủ trả HTTP " + code + ".");
            }
            int declaredLength = connection.getContentLength();
            if (declaredLength > 32 * 1024 * 1024) {
                connection.disconnect();
                throw new IllegalArgumentException("Firmware lớn hơn giới hạn 32 MB.");
            }
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[32768];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 32 * 1024 * 1024) throw new IllegalArgumentException("Firmware lớn hơn giới hạn 32 MB.");
                    output.write(buffer, 0, count);
                }
                if (total == 0) throw new IllegalArgumentException("Firmware tải về đang rỗng.");
                return output.toByteArray();
            } finally {
                connection.disconnect();
            }
        }
        throw new IllegalStateException("Quá nhiều lần chuyển hướng.");
    }

    private int parseFlashAddress(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) throw new IllegalArgumentException("Thiếu địa chỉ flash.");
        long offset = value.startsWith("0x") ? Long.parseLong(value.substring(2), 16) : Long.parseLong(value);
        if (offset < 0 || offset > Integer.MAX_VALUE) throw new IllegalArgumentException("Địa chỉ flash không hợp lệ: " + text);
        return (int) offset;
    }

    private byte[] readFirmwareImage(FirmwareImage image) throws Exception {
        if (image.bytes != null) return image.bytes;
        try (InputStream input = getContentResolver().openInputStream(image.uri);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalArgumentException("Không đọc được file " + image.name);
            byte[] buffer = new byte[32768];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 32 * 1024 * 1024) throw new IllegalArgumentException(image.name + " vượt quá 32 MB.");
                output.write(buffer, 0, count);
            }
            if (total == 0) throw new IllegalArgumentException(image.name + " đang rỗng.");
            return output.toByteArray();
        }
    }

    private void confirmAndFlashFirmware() {
        if (activeFlasher == null || detectedChip.isEmpty()) {
            toast("Chưa kết nối và nhận dạng chip ESP.");
            return;
        }
        if (firmwareImages.isEmpty()) {
            toast("Chọn file firmware trước khi nạp.");
            return;
        }
        if (!currentFlashModel().equals("auto") && !chipModelSpinner.getSelectedItem().toString().equals(detectedChip)) {
            toast("Model đã chọn không khớp chip vừa nhận dạng.");
            return;
        }
        if (flashInProgress) return;
        String profileModel = currentFlashModel();
        String targetChip = detectedChip;
        int targetFlashSize = detectedFlashSize;
        List<FirmwareImage> imagesToFlash = new ArrayList<>(firmwareImages);
        List<String> names = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        try {
            for (FirmwareImage image : imagesToFlash) {
                names.add(image.name);
                offsets.add(parseFlashAddress(image.offsetField == null ? guessFlashOffset(image.name) : image.offsetField.getText().toString()));
            }
        } catch (Exception error) {
            toast("Địa chỉ firmware không hợp lệ: " + error.getMessage());
            return;
        }
        List<FlashPayload> prepared = new ArrayList<>();
        flashInProgress = true;
        flashButton.setEnabled(false);
        setUsbStatus("Đang kiểm tra kích thước và vùng địa chỉ firmware…", false);
        runIo(() -> {
            long flashCapacity = targetFlashSize;
            int sizeProfile = prefs.getInt(flashConfigKey(profileModel, "flash-size"), 0);
            int modeProfile = prefs.getInt(flashConfigKey(profileModel, "flash-mode"), 0);
            int frequencyProfile = prefs.getInt(flashConfigKey(profileModel, "flash-frequency"), 0);
            long[] sizes = {0, 2L << 20, 4L << 20, 8L << 20, 16L << 20};
            int[] flashModes = {2, 0, 3, 1};
            int[] flashFrequencies = {0, 1, 2, 0xF};
            if (sizeProfile > 0) {
                long configured = sizes[sizeProfile];
                if (configured > targetFlashSize) throw new IllegalArgumentException("Cấu hình flash lớn hơn dung lượng phần cứng đã dò.");
                flashCapacity = configured;
            }
            for (int i = 0; i < imagesToFlash.size(); i++) {
                FirmwareImage image = imagesToFlash.get(i);
                byte[] bytes = readFirmwareImage(image);
                if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0xE9) {
                    int imageMode = bytes[2] & 0xFF;
                    int imageFrequency = bytes[3] & 0x0F;
                    if (imageMode != flashModes[modeProfile] || imageFrequency != flashFrequencies[frequencyProfile])
                        throw new IllegalArgumentException(image.name + " header mode/frequency does not match the selected chip profile.");
                }
                long end = (long) offsets.get(i) + bytes.length;
                if (end > flashCapacity) throw new IllegalArgumentException(image.name + " vượt quá dung lượng flash.");
                prepared.add(new FlashPayload(image.name, bytes, offsets.get(i)));
            }
            List<FlashPayload> ordered = new ArrayList<>(prepared);
            Collections.sort(ordered, (a, b) -> Integer.compare(a.offset, b.offset));
            for (int i = 1; i < ordered.size(); i++) {
                long previousEnd = (long) ordered.get(i - 1).offset + ordered.get(i - 1).data.length;
                if (previousEnd > ordered.get(i).offset)
                    throw new IllegalArgumentException("Các file firmware bị chồng lấn vùng flash.");
            }
            JSONObject result = new JSONObject();
            result.put("flashCapacity", flashCapacity);
            result.put("chip", targetChip);
            return result;
        }, result -> {
            flashInProgress = false;
            refreshFlashButtonState();
            StringBuilder details = new StringBuilder(result.optString("chip")).append("\n");
            for (FlashPayload payload : prepared)
                details.append(payload.name).append(" → 0x").append(Integer.toHexString(payload.offset).toUpperCase(Locale.ROOT))
                    .append(" (").append(payload.data.length).append(" bytes)\n");
            new android.app.AlertDialog.Builder(this)
                .setTitle(tr("Xác nhận nạp firmware"))
                .setMessage(tr(details + "\nThao tác sẽ ghi đè các vùng flash đã chọn. Tiếp tục?"))
                .setPositiveButton(tr("Nạp"), (dialog, which) -> flashPreparedImages(prepared))
                .setNegativeButton(tr("Hủy"), null)
                .show();
        }, error -> {
            flashInProgress = false;
            refreshFlashButtonState();
            setUsbStatus("Firmware chưa được nạp: " + error.getMessage(), false);
            toast(error.getMessage());
        });
    }

    private void flashPreparedImages(List<FlashPayload> payloads) {
        Flasher flasher = activeFlasher;
        if (flasher == null || flashInProgress) return;
        int baud;
        try {
            baud = Integer.parseInt(flashBaudField.getText().toString().trim());
            if (baud < 115200 || baud > 2000000) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            toast("Baud rate phải nằm trong khoảng 115200–2000000.");
            return;
        }
        flashInProgress = true;
        refreshFlashButtonState();
        long totalBytes = 0;
        for (FlashPayload payload : payloads) totalBytes += payload.data.length;
        final long totalFlashBytes = totalBytes;
        final long[] completedFlashBytes = {0};
        if (flashProgressBar != null) {
            flashProgressBar.setIndeterminate(false);
            flashProgressBar.setProgress(0);
            flashProgressBar.setVisibility(View.VISIBLE);
        }
        if (flashProgressText != null) {
            flashProgressText.setText(tr("Đang nạp firmware: 0%"));
            flashProgressText.setVisibility(View.VISIBLE);
        }
        runIo(() -> {
            flasher.changeBaudRate(baud);
            for (FlashPayload payload : payloads) {
                flasher.flash(payload.data, payload.offset, (done, total) -> {
                    long currentFileDone = Math.max(0, Math.min(done, total));
                    long overallDone = completedFlashBytes[0] + currentFileDone;
                    int percent = totalFlashBytes == 0 ? 100 : (int) Math.min(100, overallDone * 100 / totalFlashBytes);
                    main.post(() -> {
                        if (flashProgressBar != null) flashProgressBar.setProgress(percent);
                        if (flashProgressText != null) flashProgressText.setText(tr("Đang nạp firmware: ") + percent + "%");
                        setUsbStatus("Đang nạp " + payload.name + ": " + percent + "%", false);
                    });
                });
                completedFlashBytes[0] += payload.data.length;
            }
            flasher.reset();
            return new JSONObject();
        }, result -> {
            cleanupFirmwareTempFiles();
            flashInProgress = false;
            if (flashProgressBar != null) flashProgressBar.setProgress(100);
            if (flashProgressText != null) flashProgressText.setText(tr("Nạp firmware hoàn tất: 100%"));
            if (selectedUsbDriver == null) closeUsbSerial();
            if (selectedUsbDriver == null) {
                detectedChip = "";
                detectedFlashSize = 0;
                setUsbStatus("USB bị ngắt; kết quả nạp không được xác nhận.", false);
                if (flashProgressText != null) flashProgressText.setText(tr("USB bị ngắt; kết quả nạp không được xác nhận."));
            } else {
                setUsbStatus("Nạp và xác minh firmware hoàn tất · " + detectedChip + ".", true);
            }
            refreshFlashButtonState();
        }, error -> {
            cleanupFirmwareTempFiles();
            flashInProgress = false;
            if (flashProgressText != null) flashProgressText.setText(tr("Nạp firmware thất bại: ") + error.getMessage());
            if (selectedUsbDriver == null) {
                closeUsbSerial();
                detectedChip = "";
                detectedFlashSize = 0;
            }
            setUsbStatus("Nạp firmware thất bại: " + error.getMessage(), false);
            refreshFlashButtonState();
        });
    }

    private void scanUsbSerialDevices() {
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            setUsbStatus("Android không hỗ trợ USB Host trên thiết bị này.", false);
            return;
        }
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            setUsbStatus("Không tìm thấy USB serial. Kiểm tra OTG, nguồn board và cáp dữ liệu.", false);
            return;
        }
        if (drivers.size() == 1) {
            requestUsbPermission(drivers.get(0));
            return;
        }
        String[] deviceNames = new String[drivers.size()];
        for (int i = 0; i < drivers.size(); i++) {
            UsbDevice device = drivers.get(i).getDevice();
            deviceNames[i] = usbDeviceDescription(device);
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle(tr("Chọn thiết bị USB"))
            .setItems(deviceNames, (dialog, which) -> requestUsbPermission(drivers.get(which)))
            .setNegativeButton(tr("Hủy"), null)
            .show();
    }

    private String usbDeviceDescription(UsbDevice device) {
        String name = device.getProductName();
        if (name == null || name.trim().isEmpty()) name = "USB serial device";
        return name + " · VID " + String.format(Locale.US, "%04X", device.getVendorId())
            + " / PID " + String.format(Locale.US, "%04X", device.getProductId());
    }

    private void requestUsbPermission(UsbSerialDriver driver) {
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            setUsbStatus("Không thể khởi tạo USB Host.", false);
            return;
        }
        selectedUsbDriver = driver;
        if (usbManager.hasPermission(driver.getDevice())) {
            openUsbSerial(driver);
            return;
        }
        Intent intent = new Intent(USB_PERMISSION_ACTION).setPackage(getPackageName());
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        registerUsbPermissionReceiver();
        usbManager.requestPermission(driver.getDevice(), permissionIntent);
        setUsbStatus("Đang yêu cầu quyền truy cập USB…", false);
    }

    private void registerUsbPermissionReceiver() {
        if (usbPermissionReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(USB_PERMISSION_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbPermissionReceiver, filter);
        usbPermissionReceiverRegistered = true;
    }

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!USB_PERMISSION_ACTION.equals(intent.getAction())) return;
            if (usbPermissionReceiverRegistered) {
                unregisterReceiver(this);
                usbPermissionReceiverRegistered = false;
            }
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && selectedUsbDriver != null) openUsbSerial(selectedUsbDriver);
            else setUsbStatus("Quyền USB bị từ chối.", false);
        }
    };
    private boolean usbPermissionReceiverRegistered;
    private boolean usbDeviceReceiverRegistered;
    private final BroadcastReceiver usbDeviceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= 33) device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            else device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                if (activePage.equals("system")) main.postDelayed(() -> scanUsbSerialDevices(), 350);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())
                    && device != null && selectedUsbDriver != null
                    && selectedUsbDriver.getDevice().getDeviceId() == device.getDeviceId()) {
                selectedUsbDriver = null;
                if (flashInProgress) {
                    setUsbStatus("USB bị rút trong khi nạp; chờ giao dịch kết thúc…", false);
                } else {
                    closeUsbSerial();
                    detectedChip = "";
                    detectedFlashSize = 0;
                    setUsbStatus("USB đã bị rút.", false);
                    refreshFlashButtonState();
                }
            }
        }
    };

    private void openUsbSerial(UsbSerialDriver driver) {
        closeUsbSerial();
        selectedUsbDriver = driver;
        detectedChip = "";
        detectedFlashSize = 0;
        setUsbStatus("Đang kết nối bootloader và nhận dạng chip…", false);
        runIo(() -> {
            UsbSerialBridge bridge = new UsbSerialBridge(usbManager, driver,
                new kotlin.jvm.functions.Function2<Integer, String, kotlin.Unit>() {
                    @Override public kotlin.Unit invoke(Integer level, String message) {
                        main.post(() -> {
                            if (activePage.equals("system") && message != null && !message.trim().isEmpty())
                                setUsbStatus("ESP loader: " + message, false);
                        });
                        return kotlin.Unit.INSTANCE;
                    }
                });
            Flasher flasher = new Flasher(bridge);
            try {
                flasher.connect();
                String chip = flasher.chip();
                String mac = flasher.readMac();
                int flashSize = flasher.detectFlashSize();
                int revision = flasher.chipRevision();
                JSONObject result = new JSONObject();
                result.put("chip", chip);
                result.put("mac", mac);
                result.put("flashSize", flashSize);
                result.put("revision", revision);
                usbBridge = bridge;
                activeFlasher = flasher;
                return result;
            } catch (Exception error) {
                flasher.close();
                throw error;
            }
        }, result -> {
            detectedChip = result.optString("chip", "");
            detectedFlashSize = result.optInt("flashSize", 0);
            if (!supportedFlashChip(detectedChip)) {
                closeUsbSerial();
                detectedChip = "";
                setUsbStatus("Chip nhận dạng được nhưng model này chưa được bật trong cấu hình nạp.", false);
                refreshFlashButtonState();
                return;
            }
            if (chipModelSpinner != null) {
                int modelIndex = flashModelIndex(detectedChip);
                if (modelIndex > 0) chipModelSpinner.setSelection(modelIndex);
            }
            String size = detectedFlashSize > 0 ? String.format(Locale.US, "%.1f MB", detectedFlashSize / 1048576.0) : "unknown";
            int revision = result.optInt("revision");
            setUsbStatus("Đã nhận dạng " + detectedChip + " · rev " + revision / 100 + "." + revision % 100
                + " · flash " + size + " · MAC " + result.optString("mac"), true);
            refreshFlashButtonState();
        }, error -> {
            closeUsbSerial();
            setUsbStatus("Không nhận dạng được chip. Kiểm tra BOOT/RESET, cáp OTG và nguồn; " + error.getMessage(), false);
            refreshFlashButtonState();
        });
    }

    private void closeUsbSerial() {
        Flasher flasher = activeFlasher;
        UsbSerialBridge bridge = usbBridge;
        activeFlasher = null;
        usbBridge = null;
        if (flasher != null) {
            try { flasher.close(); } catch (Exception error) { android.util.Log.w("EspDownload", "Unable to close flasher", error); }
        } else if (bridge != null) {
            try { bridge.close(); } catch (Exception error) { android.util.Log.w("EspDownload", "Unable to close USB bridge", error); }
        }
    }

    private boolean supportedFlashChip(String chip) {
        return "ESP32".equals(chip) || "ESP32-S2".equals(chip) || "ESP32-S3".equals(chip)
            || "ESP32-C3".equals(chip) || "ESP32-C5".equals(chip) || "ESP32-C6".equals(chip);
    }

    private int flashModelIndex(String chip) {
        switch (chip) {
            case "ESP32": return 1;
            case "ESP32-S2": return 2;
            case "ESP32-S3": return 3;
            case "ESP32-C3": return 4;
            case "ESP32-C5": return 5;
            case "ESP32-C6": return 6;
            default: return 0;
        }
    }

    private void refreshFlashButtonState() {
        if (flashButton == null) return;
        boolean matchingModel = chipModelSpinner == null || chipModelSpinner.getSelectedItemPosition() == 0
            || chipModelSpinner.getSelectedItem().toString().equals(detectedChip);
        boolean ready = activeFlasher != null && !detectedChip.isEmpty() && matchingModel
            && !firmwareImages.isEmpty() && !flashInProgress;
        flashButton.setEnabled(ready);
        flashButton.setAlpha(ready ? 1f : .55f);
    }

    private void setUsbStatus(String message, boolean connected) {
        if (usbStatus != null) {
            usbStatus.setText(tr(message));
            usbStatus.setTextColor(connected ? GREEN : Color.rgb(190, 75, 60));
        }
    }

    private void loadSystemTools(LinearLayout container) {
        runIo(() -> request(host, "/api/local/system/check", "GET", null, sessionToken),
            res -> {
                if (!activePage.equals("system") || container == null) return;
                container.removeAllViews();
                JSONArray tools = res.optJSONArray("tools");
                if (tools == null || tools.length() == 0) {
                    addInfoCard(container, "Không có dữ liệu", "empty-sys", "Không nhận được danh sách gói.");
                    return;
                }
                LinearLayout row = null;
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject tool = tools.optJSONObject(i);
                    if (tool == null) continue;
                    String name = tool.optString("name", "Tool");
                    String id = tool.optString("id", name);
                    boolean installed = tool.optBoolean("installed", false);
                    String ver = tool.optString("version", "");
                    String path = tool.optString("path", "");
                    String desc = tool.optString("description", "");
                    if (i % 4 == 0) {
                        row = horizontal(container);
                        row.setGravity(Gravity.TOP);
                    }
                    LinearLayout tile = new LinearLayout(this);
                    tile.setOrientation(LinearLayout.VERTICAL);
                    tile.setGravity(Gravity.CENTER);
                    tile.setPadding(dp(3), dp(7), dp(3), dp(7));
                    tile.setBackground(haSurface(SURFACE, 12, installed
                        ? (darkTheme ? Color.rgb(48, 76, 60) : Color.rgb(210, 233, 217))
                        : (darkTheme ? Color.rgb(87, 50, 45) : Color.rgb(245, 220, 214))));
                    LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, dp(78), 1);
                    tileParams.setMargins(dp(2), dp(2), dp(2), dp(2));
                    row.addView(tile, tileParams);
                    TextView icon = new TextView(this);
                    icon.setText(systemToolIcon(id));
                    icon.setTextSize(20);
                    icon.setGravity(Gravity.CENTER);
                    tile.addView(icon, new LinearLayout.LayoutParams(-1, dp(26)));
                    TextView title = label(tile, name, 9, INK, true);
                    title.setGravity(Gravity.CENTER);
                    TextView state = label(tile, installed ? "● OK" : "● Missing", 8,
                        installed ? GREEN : Color.rgb(190, 75, 60), true);
                    state.setGravity(Gravity.CENTER);
                    String statusText = (installed ? "✅ Đã cài đặt" + (ver.isEmpty() ? "" : (" · " + ver)) : "❌ Chưa cài đặt")
                        + "\n" + (path.isEmpty() ? desc : ("Đường dẫn: " + path));
                    tile.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                        .setTitle(tr(name + (installed ? " (Sẵn sàng)" : " (Thiếu)")))
                        .setMessage(tr(statusText))
                        .setPositiveButton(tr("Đóng"), null)
                        .show());
                }
            },
            err -> {
                if (!activePage.equals("system") || container == null) return;
                container.removeAllViews();
                addInfoCard(container, "Lỗi kiểm tra hệ thống", "err-sys", err.getMessage());
            });
    }

    private String systemToolIcon(String id) {
        switch (id.toLowerCase(Locale.ROOT)) {
            case "yt-dlp": return "⬇";
            case "ffmpeg": return "🎞";
            case "aria2c": return "⚡";
            case "deno": return "🦕";
            case "python": return "🐍";
            case "node": return "⬢";
            case "powershell": return ">_";
            case "gallery-dl": return "▧";
            default: return "●";
        }
    }

    private void renderDashboard() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(10), dp(8), dp(10), dp(7));
        hero.setBackground(haSurface(darkTheme ? Color.rgb(35, 50, 42) : Color.rgb(231, 239, 234), 16,
            darkTheme ? Color.rgb(55, 75, 64) : Color.rgb(214, 227, 219)));
        TextView eyebrow = label(hero, "CONTROL PLANE · WINDOWS LOCAL HOST", 7, Color.rgb(107, 114, 128), true);
        eyebrow.setPadding(0, 0, 0, 0);
        TextView title = label(hero, "ESP32 Chatbot Auto Downloader.", 16, darkTheme ? Color.WHITE : Color.rgb(27, 61, 47), true);
        title.setPadding(0, 0, 0, 0);
        TextView subtitle = label(hero, "ESP32 là trung tâm điều phối; Gateway nối Wi-Fi, Telegram và Windows worker.", 9, darkTheme ? Color.rgb(210, 218, 213) : Color.rgb(55, 65, 81), false);
        subtitle.setPadding(0, 0, 0, 0);
        TextView live = label(hero, "●  8/8 tuyến hoạt động · cập nhật trực tiếp", 8, Color.rgb(16, 185, 129), true);
        live.setPadding(0, 0, 0, 0);
        live.setTag("dashboard-live-status");
        PipelineFlowView pipeline = new PipelineFlowView(this);
        pipeline.setTag("dashboard-pipeline");
        pipeline.addStage(createPipelineGridStage("pipeline-telegram", "Telegram API"));
        pipeline.addStage(createPipelineGridStage("pipeline-domain", "Domain"));
        pipeline.addStage(createPipelineGridStage("pipeline-gateway", "Wi-Fi Gateway", true));
        pipeline.addStage(createPipelineGridStage("pipeline-worker", "Windows PC"));
        pipeline.addStage(createPipelineGridStage("pipeline-host", "Local host"));
        pipeline.addStage(createPipelineGridStage("pipeline-device", "Esp32"));
        pipeline.addStage(createPipelineGridStage("pipeline-storage", "Storage"));
        LinearLayout.LayoutParams pipelineParams = full();
        pipelineParams.leftMargin = dp(5);
        pipelineParams.rightMargin = dp(5);
        pipelineParams.topMargin = dp(5);
        hero.addView(pipeline, pipelineParams);
        body.addView(hero, full());

        LinearLayout cards = column(body);
        cards.setTag("dashboard-cards");
        addDashboardTile(cards, "ESP32 / Wi-Fi", "device-card");
        addDashboardTile(cards, "Local worker", "worker-card");
        addDashboardTile(cards, "Thư mục đầu ra", "storage-card");
        addDashboardTile(cards, "Hàng đợi", "queue-card");
        addDashboardTile(cards, "Device API", "device-api-card");
        addDashboardTile(cards, "Settings DB", "settings-db-card");
        addDashboardTile(cards, "CPU · 2 core", "cpu-card");
        addDashboardTile(cards, "RAM · heap", "memory-card");
        addDashboardTile(cards, "LittleFS", "filesystem-card");
        addDashboardTile(cards, "Telegram API", "telegram-card");
        addDashboardTile(cards, "Hoàn tất (30 ngày)", "dashboard-completed");
        addDashboardTile(cards, "Lỗi (30 ngày)", "dashboard-failed");
        compactDashboardGrid(cards);

        LinearLayout monitorHeading = horizontal(body);
        TextView monitorTitle = label(monitorHeading, "URL MONITOR", 8, MUTED, true);
        monitorTitle.setPadding(0, 0, 0, 0);
        monitorHeading.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView jobCount = label(monitorHeading, "0 JOBS", 7, MUTED, true);
        jobCount.setTag("dashboard-job-count");
        jobCount.setGravity(Gravity.END);
        jobCount.setPadding(0, 0, 0, 0);
        LinearLayout monitor = column(body);
        monitor.setTag("dashboard-job-monitor");
        monitor.setPadding(dp(6), dp(6), dp(6), dp(8));
        monitor.setBackground(haSurface(SURFACE, 14, darkTheme ? Color.rgb(64, 78, 68) : Color.rgb(196, 207, 197)));
        LinearLayout monitorTabs = horizontal(monitor);
        addDashboardJobFilter(monitorTabs, "Đang chờ", "QUEUED");
        addDashboardJobFilter(monitorTabs, "Đã hoàn tất", "COMPLETED");
        addDashboardJobFilter(monitorTabs, "Lỗi", "FAILED");
        LinearLayout entries = column(monitor);
        entries.setTag("dashboard-job-entries");
        TextView empty = label(entries, "Không có URL đang chờ hoặc tải.", 8, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(18), 0, dp(18));
    }

    private void addDashboardJobFilter(LinearLayout parent, String title, String filter) {
        boolean selected = dashboardJobFilter.equals(filter);
        Button tab = button(title, false);
        tab.setTag("dashboard-filter-" + filter);
        styleDashboardJobFilter(tab, selected);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
        params.setMargins(dp(2), 0, dp(2), dp(4));
        parent.addView(tab, params);
        tab.setOnClickListener(v -> {
            dashboardJobFilter = filter;
            updateDashboardJobFilters();
            renderDashboardJobMonitor();
        });
    }

    private void styleDashboardJobFilter(Button tab, boolean selected) {
        int background = darkTheme
            ? (selected ? Color.rgb(85, 174, 134) : Color.rgb(43, 58, 49))
            : (selected ? Color.rgb(36, 109, 82) : Color.rgb(232, 240, 234));
        int foreground = darkTheme
            ? (selected ? Color.rgb(15, 33, 24) : Color.rgb(231, 238, 233))
            : (selected ? Color.WHITE : Color.rgb(27, 61, 47));
        int border = darkTheme
            ? (selected ? Color.rgb(115, 199, 157) : Color.rgb(75, 96, 82))
            : (selected ? Color.rgb(36, 109, 82) : Color.rgb(184, 207, 190));
        tab.setTextSize(12);
        tab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tab.setMinHeight(dp(42));
        tab.setPadding(dp(6), 0, dp(6), 0);
        tab.setBackgroundTintList(null);
        tab.setBackground(haSurface(background, 8, border));
        tab.setTextColor(foreground);
        tab.setStateListAnimator(null);
    }

    private void updateDashboardJobFilters() {
        if (body == null) return;
        for (String filter : new String[]{"QUEUED", "COMPLETED", "FAILED"}) {
            View view = body.findViewWithTag("dashboard-filter-" + filter);
            if (view instanceof Button) {
                boolean selected = dashboardJobFilter.equals(filter);
                styleDashboardJobFilter((Button) view, selected);
            }
        }
    }

    private void renderDashboardJobMonitor() {
        if (body == null) return;
        View listView = body.findViewWithTag("dashboard-job-entries");
        if (!(listView instanceof LinearLayout)) return;
        LinearLayout list = (LinearLayout) listView;
        list.removeAllViews();
        int count = 0;
        for (int i = dashboardJobs.length() - 1; i >= 0; i--) {
            JSONObject job = dashboardJobs.optJSONObject(i);
            if (job == null || !matchesDashboardJobFilter(job.optString("status"))) continue;
            count++;
            if (count > 8) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(4), dp(5), dp(4), dp(5));
            TextView url = label(row, job.optString("url", "URL"), 9, INK, true);
            url.setMaxLines(1);
            url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            url.setPadding(0, 0, 0, 0);
            String state = job.optString("status");
            String detail = state.equals("DOWNLOADING")
                ? "Đang tải · " + String.format(Locale.US, "%.1f%%", job.optDouble("progress"))
                : state.equals("COMPLETED") ? "Đã hoàn tất" : job.optString("error", state);
            TextView statusText = label(row, detail, 7, MUTED, false);
            statusText.setMaxLines(1);
            statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            statusText.setPadding(0, 0, 0, 0);
            list.addView(row, full());
        }
        View countView = body.findViewWithTag("dashboard-job-count");
        int total = dashboardJobs.length();
        if (countView instanceof TextView) ((TextView) countView).setText(total + (total == 1 ? " JOB" : " JOBS"));
        if (count == 0) {
            String emptyText = dashboardJobFilter.equals("QUEUED") ? "Không có URL đang chờ hoặc tải."
                : dashboardJobFilter.equals("COMPLETED") ? "Chưa có URL tải hoàn tất." : "Chưa có URL lỗi.";
            TextView empty = label(list, emptyText, 8, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(18), 0, dp(18));
        }
    }

    private boolean matchesDashboardJobFilter(String state) {
        if (dashboardJobFilter.equals("QUEUED"))
            return state.equals("QUEUED") || state.equals("DISPATCHED") || state.equals("DOWNLOADING") || state.equals("CANCEL_REQUESTED");
        if (dashboardJobFilter.equals("COMPLETED")) return state.equals("COMPLETED");
        return state.equals("FAILED") || state.equals("ERROR") || state.equals("CANCELLED") || state.equals("RECOVERY_REQUIRED");
    }

    private void addPipelineGridStage(LinearLayout parent, String tag, String title, String stage, String state) {
        addPipelineGridStage(parent, tag, title, stage, state, false);
    }

    private void addPipelineGridStage(LinearLayout parent, String tag, String title, String stage, String state, boolean core) {
        LinearLayout card = cardStart(parent);
        card.setTag(tag);
        if (core) card.setBackgroundColor(GREEN);
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) card.getLayoutParams();
        p.width = 0; p.weight = 1; p.leftMargin = dp(3); p.rightMargin = dp(3);
        p.bottomMargin = dp(6);
        card.setBackground(haSurface(core ? GREEN : Color.rgb(35, 43, 40), 14, core ? Color.rgb(100, 196, 155) : Color.rgb(74, 111, 94)));
        card.setElevation(dp(3));
        label(card, stage, 7, core ? Color.WHITE : Color.rgb(131, 210, 174), true);
        label(card, title, 10, Color.WHITE, true);
        label(card, state, 9, core ? Color.rgb(235, 245, 240) : MUTED, false).setTag(tag + "-state");
        TextView pulse = label(card, "●", 12, Color.rgb(75, 210, 145), true);
        pulse.setGravity(Gravity.END);
        ObjectAnimator animation = ObjectAnimator.ofFloat(pulse, "alpha", 0.25f, 1f);
        animation.setDuration(1100);
        animation.setRepeatMode(ValueAnimator.REVERSE);
        animation.setRepeatCount(ValueAnimator.INFINITE);
        animation.start();
    }

    private LinearLayout createPipelineGridStage(String tag, String title) {
        return createPipelineGridStage(tag, title, false);
    }

    private LinearLayout createPipelineGridStage(String tag, String title, boolean core) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setTag(tag);
        card.setPadding(dp(3), dp(2), dp(3), dp(1));
        card.setBackground(haSurface(core ? GREEN : Color.rgb(35, 43, 40), 6,
            core ? Color.rgb(100, 196, 155) : Color.rgb(74, 111, 94)));
        TextView titleView = label(card, title, 7, Color.WHITE, true);
        titleView.setTextSize(7.2f);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, 0);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return card;
    }

    private class PipelineFlowView extends FrameLayout {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<View> nodes = new ArrayList<>();
        private final java.util.Set<String> offlineNodes = new java.util.HashSet<>();
        private float flowPhase;

        PipelineFlowView(Context context) {
            super(context);
            setBackground(haSurface(Color.rgb(24, 31, 29), 16, Color.rgb(50, 82, 69)));
            setPadding(dp(8), dp(8), dp(8), dp(8));
            linePaint.setColor(Color.rgb(72, 113, 96));
            linePaint.setStrokeWidth(dp(1));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            flowPaint.setColor(Color.rgb(91, 226, 169));
            flowPaint.setStyle(Paint.Style.FILL);
            ObjectAnimator animator = ObjectAnimator.ofFloat(this, "flowPhase", 0f, 1f);
            animator.setDuration(1600);
            animator.setInterpolator(new LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.start();
        }

        void addStage(View stage) {
            nodes.add(stage);
            addView(stage);
        }

        void setNodeOnline(String nodeTag, boolean online) {
            View node = findViewWithTag(nodeTag);
            if (node == null) return;
            if (online) offlineNodes.remove(nodeTag);
            else offlineNodes.add(nodeTag);
            int fill = online ? Color.rgb(35, 43, 40) : Color.rgb(254, 226, 226);
            int stroke = online ? Color.rgb(74, 111, 94) : Color.rgb(248, 113, 113);
            int text = online ? Color.WHITE : Color.rgb(153, 27, 27);
            if ("pipeline-gateway".equals(nodeTag) && online) {
                fill = GREEN;
                stroke = Color.rgb(100, 196, 155);
            }
            node.setBackground(haSurface(fill, 6, stroke));
            if (node instanceof ViewGroup && ((ViewGroup) node).getChildAt(0) instanceof TextView) {
                ((TextView) ((ViewGroup) node).getChildAt(0)).setTextColor(text);
            }
            invalidate();
        }

        public void setFlowPhase(float phase) {
            flowPhase = phase;
            invalidate();
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = dp(152);
            setMeasuredDimension(width, height);
            for (int i = 0; i < getChildCount(); i++) {
                measureChild(getChildAt(i), MeasureSpec.makeMeasureSpec((int) (width * 0.23f), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(30), MeasureSpec.EXACTLY));
            }
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int nodeWidth = (int) (width * 0.23f);
            int rowHeight = dp(30);
            int col0 = (int) (width * 0.05f);
            int col1 = (width - nodeWidth) / 2;
            int col2 = (int) (width * 0.95f) - nodeWidth;

            View tg = findViewWithTag("pipeline-telegram");
            View domain = findViewWithTag("pipeline-domain");
            View host = findViewWithTag("pipeline-host");
            View gw = findViewWithTag("pipeline-gateway");
            View esp = findViewWithTag("pipeline-device");
            View pc = findViewWithTag("pipeline-worker");
            View store = findViewWithTag("pipeline-storage");

            int y0 = dp(10);
            int y1 = dp(56);
            int y2 = dp(102);

            if (tg != null) tg.layout(col0, y0, col0 + nodeWidth, y0 + rowHeight);
            if (domain != null) domain.layout(col0, y1, col0 + nodeWidth, y1 + rowHeight);
            if (host != null) host.layout(col0, y2, col0 + nodeWidth, y2 + rowHeight);

            if (gw != null) gw.layout(col1, y0, col1 + nodeWidth, y0 + rowHeight);
            if (esp != null) esp.layout(col1, y2, col1 + nodeWidth, y2 + rowHeight);

            if (pc != null) pc.layout(col2, y0, col2 + nodeWidth, y0 + rowHeight);
            if (store != null) store.layout(col2, y2, col2 + nodeWidth, y2 + rowHeight);
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            View tg = findViewWithTag("pipeline-telegram");
            View domain = findViewWithTag("pipeline-domain");
            View host = findViewWithTag("pipeline-host");
            View gw = findViewWithTag("pipeline-gateway");
            View esp = findViewWithTag("pipeline-device");
            View pc = findViewWithTag("pipeline-worker");
            View store = findViewWithTag("pipeline-storage");

            if (tg != null && gw != null && pc != null && host != null && esp != null && store != null) {
                drawStraightLink(canvas, tg, gw, false, 0.00f);
                if (domain != null) {
                    drawDiagonalLink(canvas, domain, gw, 0.12f);
                }
                drawStraightLink(canvas, gw, pc, false, 0.18f);
                drawVerticalLink(canvas, gw, esp, 0.34f);
                drawVerticalLink(canvas, pc, store, 0.50f);
                drawStraightLink(canvas, host, esp, false, 0.66f);
                drawStraightLink(canvas, esp, store, false, 0.82f);
                drawDiagonalLink(canvas, host, gw, 0.24f);
                drawDiagonalLink(canvas, esp, pc, 0.42f);
            }
            super.dispatchDraw(canvas);
        }

        private void drawStraightLink(Canvas canvas, View from, View to, boolean vertical, float phaseOffset) {
            drawStraightLink(canvas, from, to, vertical, phaseOffset, false);
        }

        private void drawStraightLink(Canvas canvas, View from, View to, boolean vertical, float phaseOffset, boolean reverseFlow) {
            float x1, y1, x2, y2;
            if (vertical) {
                x1 = from.getLeft() + from.getWidth() * 0.5f;
                y1 = from.getBottom();
                x2 = to.getLeft() + to.getWidth() * 0.5f;
                y2 = to.getTop();
            } else {
                boolean reverse = from.getLeft() > to.getLeft();
                x1 = reverse ? from.getLeft() : from.getRight();
                y1 = from.getTop() + from.getHeight() * 0.5f;
                x2 = reverse ? to.getRight() : to.getLeft();
                y2 = to.getTop() + to.getHeight() * 0.5f;
            }
            boolean online = setLinkState(from, to);
            canvas.drawLine(x1, y1, x2, y2, linePaint);
            if (!online) return;
            for (int i = 0; i < 3; i++) {
                float t = (flowPhase + phaseOffset + i / 3f) % 1f;
                if (reverseFlow) t = 1f - t;
                canvas.drawCircle(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, dp(1.8f), flowPaint);
            }
        }

        private void drawVerticalLink(Canvas canvas, View from, View to, float phaseOffset) {
            boolean deviceToGateway = "pipeline-device".equals(String.valueOf(to.getTag()))
                && "pipeline-gateway".equals(String.valueOf(from.getTag()));
            drawStraightLink(canvas, from, to, true, phaseOffset, deviceToGateway);
        }

        private void drawDiagonalLink(Canvas canvas, View from, View to, float phaseOffset) {
            float x1 = from.getRight();
            float y1 = from.getTop() + from.getHeight() * 0.5f;
            float x2 = to.getLeft();
            float y2 = to.getTop() + to.getHeight() * 0.5f;
            float span = Math.max(0, x2 - x1);
            float tangent = Math.min(dp(28), span * 0.38f);
            boolean online = setLinkState(from, to);
            Path path = new Path();
            path.moveTo(x1, y1);
            path.cubicTo(x1 + tangent, y1, x2 - tangent, y2, x2, y2);
            canvas.drawPath(path, linePaint);
            if (!online) return;

            PathMeasure measure = new PathMeasure(path, false);
            float[] position = new float[2];
            float length = measure.getLength();
            for (int i = 0; i < 4; i++) {
                float t = (flowPhase + phaseOffset + i / 4f) % 1f;
                measure.getPosTan(length * t, position, null);
                canvas.drawCircle(position[0], position[1], dp(1.8f), flowPaint);
            }
        }

        private boolean setLinkState(View from, View to) {
            boolean online = !offlineNodes.contains(String.valueOf(from.getTag()))
                && !offlineNodes.contains(String.valueOf(to.getTag()));
            linePaint.setColor(online ? Color.rgb(72, 113, 96) : Color.rgb(220, 38, 38));
            linePaint.setPathEffect(online ? null : new DashPathEffect(new float[]{dp(3), dp(4)}, 0));
            flowPaint.setColor(Color.rgb(91, 226, 169));
            return online;
        }
    }

    private void addDashboardTile(LinearLayout parent, String title, String tag) {
        LinearLayout card = cardStart(parent);
        card.setTag("tile-" + tag);
        card.setMinimumHeight(dp(48));
        card.setPadding(dp(7), dp(4), dp(7), dp(4));
        card.setBackground(haSurface(SURFACE, 12, Color.rgb(229, 231, 235)));
        card.setElevation(dp(1));
        LinearLayout headingRow = horizontal(card);
        headingRow.setPadding(0, 0, 0, 0);
        TextView icon = label(headingRow, dashboardTileIcon(tag), 9, dashboardTileTint(tag), true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(haSurface(dashboardTileTintSurface(tag), 6, dashboardTileTintSurface(tag)));
        icon.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams iconParams = (LinearLayout.LayoutParams) icon.getLayoutParams();
        iconParams.width = dp(20);
        iconParams.height = dp(20);
        iconParams.rightMargin = dp(5);
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        headingRow.addView(meta, new LinearLayout.LayoutParams(0, dp(30), 1));
        TextView labelText = label(meta, title.toUpperCase(Locale.US), 7, Color.rgb(156, 163, 175), true);
        labelText.setPadding(0, 0, 0, 0);
        TextView value = label(meta, "Đang đọc…", 9, INK, true);
        value.setTag(tag + "-value");
        value.setMaxLines(1);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setPadding(0, 0, 0, 0);
        TextView detail = label(card, "Đang đồng bộ trạng thái", 7, MUTED, false);
        detail.setTag(tag);
        detail.setPadding(0, 0, 0, 0);
        detail.setMaxLines(2);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    private String dashboardTileIcon(String tag) {
        switch (tag) {
            case "device-card": return "⌁";
            case "worker-card": return "⚙";
            case "storage-card": return "▱";
            case "queue-card": return "▦";
            case "device-api-card": return "ϟ";
            case "telegram-card": return "➤";
            case "settings-db-card": return "▤";
            case "cpu-card": return "◴";
            case "memory-card": return "▰";
            case "filesystem-card": return "▣";
            case "dashboard-failed": return "!";
            default: return "◫";
        }
    }

    private int dashboardTileTint(String tag) {
        switch (tag) {
            case "worker-card": case "cpu-card": return Color.rgb(124, 58, 237);
            case "memory-card": case "filesystem-card": case "dashboard-completed": return Color.rgb(22, 163, 74);
            case "storage-card": return Color.rgb(22, 163, 74);
            case "queue-card": case "dashboard-failed": return Color.rgb(217, 119, 6);
            default: return Color.rgb(2, 132, 199);
        }
    }

    private int dashboardTileTintSurface(String tag) {
        int tint = dashboardTileTint(tag);
        if (tint == Color.rgb(124, 58, 237)) return Color.rgb(243, 232, 255);
        if (tint == Color.rgb(22, 163, 74)) return Color.rgb(220, 252, 231);
        if (tint == Color.rgb(217, 119, 6)) return Color.rgb(254, 243, 199);
        return Color.rgb(224, 242, 254);
    }

    private void compactDashboardGrid(LinearLayout cards) {
        List<LinearLayout> tiles = new ArrayList<>();
        for (int i = 0; i < cards.getChildCount(); i++) if (cards.getChildAt(i) instanceof LinearLayout) tiles.add((LinearLayout) cards.getChildAt(i));
        cards.removeAllViews();
        for (int row = 0; row < tiles.size(); row += 2) {
            LinearLayout gridRow = new LinearLayout(this);
            gridRow.setOrientation(LinearLayout.HORIZONTAL);
            cards.addView(gridRow, full());
            for (int col = row; col < Math.min(row + 2, tiles.size()); col++) {
                LinearLayout tile = tiles.get(col);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
                p.leftMargin = dp(3); p.rightMargin = dp(3);
                p.height = LinearLayout.LayoutParams.MATCH_PARENT;
                gridRow.addView(tile, p);
            }
        }
    }

    private GradientDrawable haSurface(int background, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void addPipelineStage(LinearLayout parent, String tag, String title, String stage, String state) {
        addPipelineStage(parent, tag, title, stage, state, false);
    }

    private void addPipelineStage(LinearLayout parent, String tag, String title, String stage, String state, boolean core) {
        LinearLayout stageCard = cardStart(parent);
        stageCard.setTag(tag);
        if (core) stageCard.setBackgroundColor(GREEN);
        label(stageCard, stage, 10, core ? Color.WHITE : GREEN, true);
        label(stageCard, title, 16, core ? Color.WHITE : INK, true);
        label(stageCard, state, 12, core ? Color.rgb(235, 245, 240) : MUTED, false).setTag(tag + "-state");
    }

    private void addPipelineConnector(LinearLayout parent) {
        TextView connector = label(parent, "│\n▼", 16, GREEN, true);
        connector.setGravity(Gravity.CENTER);
    }

    private boolean isNasStoragePath(String value) {
        String directory = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return directory.startsWith("smb://")
            || directory.startsWith("\\\\")
            || directory.startsWith("//");
    }

    private void applyDashboard(JSONObject data) {
        if (!activePage.equals("dashboard") || body == null) return;
        JSONObject d = data.optJSONObject("device"); if (d == null) d = new JSONObject();
        JSONObject h = data.optJSONObject("host"); if (h == null) h = new JSONObject();
        JSONObject dl = data.optJSONObject("downloader"); if (dl == null) dl = new JSONObject();
        JSONObject out = data.optJSONObject("output"); if (out == null) out = new JSONObject();
        JSONObject tg = data.optJSONObject("telegram"); if (tg == null) tg = new JSONObject();
        boolean deviceConnected = d.optBoolean("connected");
        String outputDirectory = out.optString("directory", "");
        if (d.has("ddnsDomain") || d.has("ddnsToken")) {
            String dDomain = d.optString("ddnsDomain", "");
            String dToken = d.optString("ddnsToken", "");
            boolean dEnabled = d.optBoolean("ddnsEnabled", false);
            if (!dDomain.isEmpty() && prefs != null) prefs.edit().putString("ddns_domain_raw", dDomain).apply();
            if (!dToken.isEmpty() && prefs != null) prefs.edit().putString("ddns_token", dToken).apply();
            if (prefs != null) prefs.edit().putBoolean("ddns_enabled", dEnabled).apply();
        }
        PipelineFlowView pipeline = body.findViewWithTag("dashboard-pipeline") instanceof PipelineFlowView
            ? (PipelineFlowView) body.findViewWithTag("dashboard-pipeline") : null;
        if (pipeline != null) {
            pipeline.setNodeOnline("pipeline-telegram", tg.optBoolean("configured") && tg.optBoolean("tokenValid"));
            pipeline.setNodeOnline("pipeline-gateway", deviceConnected && d.optBoolean("wifiConnected"));
            pipeline.setNodeOnline("pipeline-worker", !dl.optString("version").isEmpty());
            pipeline.setNodeOnline("pipeline-host", data.optBoolean("authenticated", true));
            pipeline.setNodeOnline("pipeline-device", deviceConnected);
            pipeline.setNodeOnline("pipeline-storage", out.optBoolean("writable"));
            String ddnsDomain = d.optString("ddnsDomain", "");
            if (ddnsDomain.isEmpty() && config != null) ddnsDomain = config.optString("ddnsDomain", "");
            if (ddnsDomain.isEmpty() && prefs != null) ddnsDomain = prefs.getString("ddns_domain_raw", "");
            boolean ddnsOnline = !ddnsDomain.trim().isEmpty() && deviceConnected && d.optBoolean("ddnsEnabled", true);
            pipeline.setNodeOnline("pipeline-domain", ddnsOnline);
            View domainNode = pipeline.findViewWithTag("pipeline-domain");
            if (domainNode instanceof ViewGroup && ((ViewGroup) domainNode).getChildAt(0) instanceof TextView) {
                ((TextView) ((ViewGroup) domainNode).getChildAt(0)).setText(!ddnsDomain.isEmpty() ? ddnsDomain : "Domain");
            }
            View storageNode = pipeline.findViewWithTag("pipeline-storage");
            if (storageNode instanceof ViewGroup && ((ViewGroup) storageNode).getChildAt(0) instanceof TextView) {
                ((TextView) ((ViewGroup) storageNode).getChildAt(0)).setText(isNasStoragePath(outputDirectory) ? "NAS" : "Storage");
            }
        }
        setCard("device-card", d.optBoolean("connected") ? d.optString("chip") + " · " + d.optString("boardProfile") + " · " + (d.optLong("flashBytes") / 1048576) + " MB flash\nWi‑Fi: " + (d.optBoolean("wifiConnected") ? d.optString("wifiSsid") + " · " + d.optString("ipAddress") + " · " + d.optInt("rssi") + " dBm · đã kết nối" : "chưa kết nối") + "\nAP: " + (d.optBoolean("provisioningApActive") ? "đang phát" : "tắt") + " · cấu hình " + (d.optBoolean("provisioningApEnabled") ? "bật" : "tắt") : d.optString("error", "ESP32 chưa kết nối"));
        setCard("device-api-card", (d.optBoolean("connected") ? "API online · token được chấp nhận" : (d.optBoolean("apiReachable") ? "API trả lời · token bị từ chối" : "API offline")) + "\nDevice API token ****..." + (d.optString("apiTokenLast4").isEmpty() ? "chưa lưu" : d.optString("apiTokenLast4")));
        setCard("worker-card", "Windows worker " + (dl.optString("version").isEmpty() ? "offline" : "online") + " · yt‑dlp " + dl.optString("version", "chưa sẵn sàng") + "\n" + h.optInt("activeJobs") + " đang chạy · " + h.optInt("pendingJobs") + " chờ trên Windows\nESP heartbeat: " + (d.optBoolean("runtimeOnline") ? (d.optBoolean("runtimeReady") ? "worker sẵn sàng" : "online, downloader chưa sẵn sàng") : "chưa nhận được"));
        setCard("storage-card", outputDirectory + "\n" + (out.optBoolean("writable") ? "Ghi được" : "Không ghi được") + " · dùng " + bytes(out.optLong("usedBytes")) + " · trống " + bytes(out.optLong("freeBytes")) + (out.optBoolean("lowDisk") ? " · gần hết dung lượng" : ""));
        setCard("queue-card", d.optInt("jobCount") + " job · LittleFS " + (d.optBoolean("storageReady") ? "sẵn sàng" : d.optString("storageError", "lỗi")) + "\nWindows: " + h.optInt("pendingJobs") + " job đang chờ");
        setCard("telegram-card", "Telegram API " + (tg.optBoolean("tokenValid") ? "online" : (tg.optBoolean("configured") ? "lỗi/chưa xác thực" : "chưa cấu hình")) + " · token ****..." + (tg.optString("tokenLast4").isEmpty() ? "—" : tg.optString("tokenLast4")) + "\n" + tg.optString("pollingState", "chưa cấu hình") + " · " + tg.optString("botName", tg.optString("botUsername", "")) + " · " + (tg.optBoolean("ownerChatConfigured") ? "chat đã cấp quyền" : "chưa cấp quyền chat") + (tg.optString("lastError").isEmpty() ? "" : "\n" + tg.optString("lastError")));
        setCard("settings-db-card", d.optBoolean("storageReady") ? "ESP32 LittleFS + NVS · revision " + h.optInt("settingsRevision") + "\n" + (h.optBoolean("settingsSyncPending") ? "Host đang chờ đồng bộ cấu hình" : "Host và app đã đọc cùng cấu hình ESP32") : "Không đọc được cơ sở dữ liệu ESP32: " + d.optString("storageError", "LittleFS chưa sẵn sàng"));
        JSONObject cpu = d.optJSONObject("cpu"), mem = d.optJSONObject("memory"), fs = d.optJSONObject("storage");
        int cpuLoad = cpu == null ? 0 : cpu.optInt("busyPercent");
        int cpuMhz = cpu == null ? 0 : cpu.optInt("frequencyMhz");
        setCard("cpu-card", (cpu == null ? "Chưa có dữ liệu" : cpuLoad + "% tải\n" + cpuMhz + " MHz · " + d.optString("chip", "ESP32")));
        long heapFree = mem == null ? -1 : mem.optLong("heapFreeBytes", -1);
        long heapTotal = mem == null ? -1 : mem.optLong("heapTotalBytes", -1);
        int heapUsedPercent = heapTotal > 0 && heapFree >= 0 ? (int) Math.round((heapTotal - heapFree) * 100.0 / heapTotal) : 0;
        setCard("memory-card", mem == null ? "Chưa có dữ liệu" : heapUsedPercent + "% heap đang dùng\n" + bytes(heapFree) + " trống / " + bytes(heapTotal));
        long fsUsed = fs == null ? -1 : fs.optLong("usedBytes", -1);
        long fsTotal = fs == null ? -1 : fs.optLong("totalBytes", -1);
        int fsUsedPercent = fsTotal > 0 && fsUsed >= 0 ? (int) Math.round(fsUsed * 100.0 / fsTotal) : 0;
        setCard("filesystem-card", fs == null ? "Chưa có dữ liệu" : fsUsedPercent + "% LittleFS\n" + bytes(fsUsed) + " dùng · " + bytes(fsTotal));
        displayDashboardJobs(dashboardJobs);
    }

    private void renderQueue() {
        heading("Hàng đợi tải", "Job lưu bền vững trên ESP32; worker Windows tải xuống theo thứ tự.");
        label(body, "URL cần tải", 12, MUTED, true);
        EditText url = input("job-url", "URL cần tải", "", "https://…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false); body.addView(url, full());
        buttonAction(body, "＋ Thêm vào hàng đợi", true, () -> {
            JSONObject payload = new JSONObject(); try { payload.put("url", value("job-url")); payload.put("source", "android"); payload.put("createdAt", java.time.Instant.now().toString()); } catch (Exception ignored) {}
            callAction("/api/device/api/v1/jobs", "POST", payload, "Job đã được lưu trên ESP32.");
        });
        LinearLayout list = column(body); list.setTag("job-list"); label(list, "Đang tải hàng đợi…", 13, MUTED, false);
        buttonAction(body, "Làm mới hàng đợi", false, () -> loadDeviceData("queue"));
    }

    private void renderWifi() {
        heading("Kết nối Wi‑Fi", "Quét Wi‑Fi từ ESP32, chọn SSID, nhập mật khẩu và lưu trực tiếp vào thiết bị.");

        LinearLayout apScanSection = cardStart(body);
        label(apScanSection, "Tìm & Kết nối AP ESP32", 15, INK, true);
        label(apScanSection, "Quét tìm AP phát từ ESP32 (mặc định 'espsystem'), xác thực kết nối và mở giao diện cài đặt qua 192.168.4.1.", 12, MUTED, false);
        Button scanApBtn = button("🔍 Quét tìm AP ESP32", false);
        apScanSection.addView(scanApBtn, full());
        scanApBtn.setOnClickListener(v -> scanNearbyEspAccessPoints());
        LinearLayout apResults = column(apScanSection);
        apResults.setTag("esp-ap-results");
        TextView apConnectFeedback = label(apScanSection, "", 12, MUTED, false);
        apConnectFeedback.setTag("ap-connect-feedback");
        LinearLayout apActionPanel = column(apScanSection);
        apActionPanel.setTag("ap-action-panel");

        LinearLayout ap = cardStart(body); TextView apState = label(ap, "Đang đọc trạng thái AP…", 14, INK, true); apState.setTag("ap-state");
        TextView wifiState = label(body, "Wi‑Fi: đang đọc trạng thái…", 13, INK, true); wifiState.setTag("wifi-state");
        Switch apToggle = new Switch(this); apToggle.setText(tr("Bật AP thiết lập")); apToggle.setTextColor(INK); apToggle.setTag("ap-toggle"); ap.addView(apToggle, full());
        apToggle.setOnCheckedChangeListener((button, enable) -> setApEnabled((Switch) button, enable));
        buttonAction(body, "Quét Wi‑Fi quanh ESP32", false, () -> scanWifi());
        LinearLayout networks = column(body); networks.setTag("wifi-networks");
        label(body, "Wi‑Fi SSID", 12, MUTED, true);
        EditText ssid = input("wifi-ssid", "Wi‑Fi SSID", "", "Chọn mạng hoặc nhập SSID", InputType.TYPE_CLASS_TEXT, false); body.addView(ssid, full());
        label(body, "Mật khẩu Wi‑Fi", 12, MUTED, true);
        EditText pass = input("wifi-pass", "Mật khẩu Wi‑Fi", "", "Để trống nếu mạng mở", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false); body.addView(pass, full());
        buttonAction(body, "Lưu Wi‑Fi vào ESP32", true, () -> {
            JSONObject p = new JSONObject(); try { p.put("ssid", value("wifi-ssid")); p.put("password", value("wifi-pass")); } catch (Exception ignored) {}
            runIo(() -> {
                try { return api("/api/device/api/v1/settings/network", "POST", p); }
                catch (Exception hostError) { return request("http://192.168.4.1", "/setup/network", "POST", p, ""); }
            }, result -> { setValue("wifi-pass", ""); toast(result.optString("message", "Đã gửi thông tin Wi‑Fi cho ESP32.")); refreshStatus(false); }, error -> toast(error.getMessage()));
        });
        updateApCard();
        label(body, "Lưu Wi‑Fi có thể làm ESP32 chuyển sang địa chỉ LAN mới. AP khôi phục tự bật nếu Wi‑Fi không kết nối được.", 12, MUTED, false);
        section("Truy cập từ xa (DuckDNS / Dynamic DNS)");
        addCheck("ddnsEnabled", "Bật tự động cập nhật DuckDNS", config.optBoolean("ddnsEnabled", false));

        final String[] providerList = {
            "DuckDNS (.duckdns.org)",
            "No-IP (.ddns.net)",
            "DynDNS (.dyndns.org)",
            "FreeDNS (.mooo.com)",
            "Thủ công / Khác (Nhập đầy đủ tên miền)"
        };

        String rawDomain = config.optString("ddnsDomain", "");
        if (rawDomain.isEmpty() && prefs != null) {
            rawDomain = prefs.getString("ddns_domain_raw", "");
        }

        int defaultProviderPos = 0;
        String defaultSubdomain = rawDomain;
        if (!rawDomain.isEmpty()) {
            if (rawDomain.endsWith(".duckdns.org")) {
                defaultProviderPos = 0;
                defaultSubdomain = rawDomain.substring(0, rawDomain.length() - ".duckdns.org".length());
            } else if (rawDomain.endsWith(".ddns.net")) {
                defaultProviderPos = 1;
                defaultSubdomain = rawDomain.substring(0, rawDomain.length() - ".ddns.net".length());
            } else if (rawDomain.endsWith(".dyndns.org")) {
                defaultProviderPos = 2;
                defaultSubdomain = rawDomain.substring(0, rawDomain.length() - ".dyndns.org".length());
            } else if (rawDomain.endsWith(".mooo.com")) {
                defaultProviderPos = 3;
                defaultSubdomain = rawDomain.substring(0, rawDomain.length() - ".mooo.com".length());
            } else if (rawDomain.contains(".")) {
                defaultProviderPos = 4;
                defaultSubdomain = rawDomain;
            }
        } else if (prefs != null) {
            defaultProviderPos = prefs.getInt("ddns_provider_pos", 0);
        }

        String defaultPort = prefs != null ? prefs.getString("ddns_ext_port", "80") : "80";

        label(body, "Nhà cung cấp tên miền", 12, MUTED, true);
        final Spinner providerSpinner = new Spinner(this);
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, providerList) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(INK);
                view.setTextSize(13);
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(Color.rgb(30, 40, 35));
                view.setPadding(dp(12), dp(10), dp(12), dp(10));
                return view;
            }
        };
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(providerAdapter);
        if (defaultProviderPos >= 0 && defaultProviderPos < providerList.length) {
            providerSpinner.setSelection(defaultProviderPos);
        }
        body.addView(providerSpinner, full());

        final TextView domainTitle = label(body, defaultProviderPos == 4 ? "Tên miền đầy đủ" : "Tên miền con (Subdomain)", 12, MUTED, true);
        final EditText domainEdit = input("ddnsDomain", "Tên miền", defaultSubdomain, defaultProviderPos == 4 ? "ví dụ: esp32downloaderbot.com" : "ví dụ: esp32downloaderbot", InputType.TYPE_CLASS_TEXT, false);
        body.addView(domainEdit, full());

        label(body, "Cổng truy cập ngoài (Port ngoài trên Router/Modem)", 12, MUTED, true);
        final EditText portEdit = input("ddnsPort", "Cổng ngoài", defaultPort, "80 (chuẩn web) hoặc 8080", InputType.TYPE_CLASS_NUMBER, false);
        body.addView(portEdit, full());
        label(body, "Mặc định mở thẳng cổng 80 (không cần gõ port khi truy cập).", 11, MUTED, false);

        LinearLayout previewCard = cardStart(body);
        previewCard.setBackgroundColor(darkTheme ? Color.rgb(24, 34, 28) : Color.rgb(235, 245, 238));
        label(previewCard, "LIÊN KẾT TRUY CẬP TỪ XA XEM TRƯỚC", 10, GREEN, true);
        final TextView previewUrlText = label(previewCard, "http://...", 13, INK, true);
        previewUrlText.setPadding(0, dp(2), 0, 0);

        final Runnable refreshDdnsPreview = () -> {
            int pPos = providerSpinner.getSelectedItemPosition();
            String sub = domainEdit.getText().toString().trim();
            String port = portEdit.getText().toString().trim();
            String fullUrl = computeDdnsFullUrl(pPos, sub, port);
            if (fullUrl.isEmpty()) {
                previewUrlText.setText(tr("Chưa nhập tên miền"));
                previewUrlText.setTextColor(MUTED);
            } else {
                previewUrlText.setText(fullUrl);
                previewUrlText.setTextColor(GREEN);
            }
            if (pPos == 4) {
                domainTitle.setText(tr("Tên miền đầy đủ (Domain)"));
                domainEdit.setHint(tr("ví dụ: esp32downloaderbot.com"));
            } else {
                domainTitle.setText(tr("Tên miền con (Subdomain)"));
                domainEdit.setHint(tr("ví dụ: esp32downloaderbot"));
            }
        };

        providerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                refreshDdnsPreview.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        domainEdit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { refreshDdnsPreview.run(); }
            public void afterTextChanged(Editable s) {}
        });

        portEdit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { refreshDdnsPreview.run(); }
            public void afterTextChanged(Editable s) {}
        });

        refreshDdnsPreview.run();

        String defaultToken = config.optString("ddnsToken", "");
        if (defaultToken.isEmpty() && device != null) {
            defaultToken = device.optString("ddnsToken", "");
        }
        if (defaultToken.isEmpty() && prefs != null) {
            defaultToken = prefs.getString("ddns_token", "");
        }
        addField("ddnsToken", "DuckDNS Token", defaultToken, "Dán token từ duckdns.org (nếu dùng DuckDNS)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);

        buttonAction(body, "🌐 Mở Web ESP32 từ xa trên Trình duyệt", true, () -> {
            int pPos = providerSpinner.getSelectedItemPosition();
            String sub = domainEdit.getText().toString().trim();
            String port = portEdit.getText().toString().trim();
            String fullUrl = computeDdnsFullUrl(pPos, sub, port);
            if (fullUrl.isEmpty()) {
                toast(tr("Vui lòng nhập tên miền trước khi mở!"));
                return;
            }
            if (prefs != null) {
                prefs.edit().putInt("ddns_provider_pos", pPos)
                           .putString("ddns_ext_port", port.isEmpty() ? "80" : port)
                           .putString("ddns_domain_raw", sub)
                           .apply();
            }
            toast(tr("Đang mở trình duyệt: ") + fullUrl);
            openInBrowser(fullUrl);
        });

        buttonAction(body, "Lưu cấu hình Dynamic DNS lên ESP32", false, () -> {
            int pPos = providerSpinner.getSelectedItemPosition();
            String sub = domainEdit.getText().toString().trim();
            String port = portEdit.getText().toString().trim();
            String fullDomain = computeDdnsFullDomain(pPos, sub);
            JSONObject ddnsPayload = new JSONObject();
            try {
                ddnsPayload.put("enabled", checked("ddnsEnabled"));
                ddnsPayload.put("domain", fullDomain);
                ddnsPayload.put("token", value("ddnsToken"));
            } catch (Exception ignored) {}
            if (prefs != null) {
                prefs.edit().putInt("ddns_provider_pos", pPos)
                           .putString("ddns_ext_port", port.isEmpty() ? "80" : port)
                           .putString("ddns_domain_raw", sub)
                           .putString("ddns_token", value("ddnsToken"))
                           .putBoolean("ddns_enabled", checked("ddnsEnabled"))
                           .apply();
            }
            try {
                config.put("ddnsDomain", fullDomain);
                config.put("ddnsToken", value("ddnsToken"));
                config.put("ddnsEnabled", checked("ddnsEnabled"));
            } catch (Exception ignored) {}
            runIo(() -> {
                try {
                    return request(host, "/api/v1/settings/ddns", "POST", ddnsPayload, "");
                } catch (Exception e) {
                    return request("http://192.168.4.1", "/api/v1/settings/ddns", "POST", ddnsPayload, "");
                }
            }, result -> toast(tr("Đã lưu cấu hình Dynamic DNS lên thiết bị!")), error -> toast(error.getMessage()));
        });
    }

    private void renderSettings() {
        heading("Thiết lập local host", "Đồng bộ cấu hình chung với dashboard web trên PC.");
        section("Kết nối thiết bị và worker");
        addField("deviceBaseUrl", "ESP32 API URL", config.optString("deviceBaseUrl", "http://192.168.4.1"), "http://192.168.4.1", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        addField("runtimeBaseUrl", "Runtime Service URL (LAN)", config.optString("runtimeBaseUrl", ""), "http://192.168.1.25:8787", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        
        Button scanRuntimeBtn = button("🔍 Quét tìm Runtime Service trong LAN", false);
        body.addView(scanRuntimeBtn, full());
        TextView runtimeScanFeedback = label(body, "", 12, MUTED, false);
        scanRuntimeBtn.setOnClickListener(v -> scanAndLinkRuntimeService(scanRuntimeBtn, runtimeScanFeedback));

        addField("workerName", "Tên Runtime Service", config.optString("workerName", "runtime-service"), "runtime-service", InputType.TYPE_CLASS_TEXT, false);
        addField("deviceToken", "Device API token", "", config.optBoolean("deviceTokenConfigured") ? "Đã lưu ****..." + config.optString("deviceTokenLast4", "????") : "Dán token từ Serial Monitor", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
        addPairingActions(body);
        section("Downloader và nơi lưu file");
        addField("ytDlpPath", "Đường dẫn yt-dlp", config.optString("ytDlpPath", "yt-dlp"), "yt-dlp", InputType.TYPE_CLASS_TEXT, false);
        addField("ffmpegPath", "Đường dẫn FFmpeg", config.optString("ffmpegPath", ""), "Tự tìm trên PATH", InputType.TYPE_CLASS_TEXT, false);
        addField("denoPath", "Đường dẫn Deno", config.optString("denoPath", ""), "Tùy chọn", InputType.TYPE_CLASS_TEXT, false);
        addField("outputDirectory", "Thư mục lưu file", config.optString("outputDirectory", ""), "D:\\Download hoặc \\\\NAS\\Share", InputType.TYPE_CLASS_TEXT, false);
        addField("urlRetentionDays", "Thời hạn lưu job và log URL (ngày)", String.valueOf(config.optInt("urlRetentionDays", 30)), "0 = tắt tự xóa", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, false);
        section("Telegram nhận URL và lệnh GPIO");
        addCheck("telegramEnabled", "Bật nhận lệnh Telegram", config.optBoolean("telegramEnabled", true));
        addField("telegramBotToken", "Telegram bot token", "", config.optBoolean("telegramTokenConfigured") ? "Đã lưu ****..." + config.optString("telegramTokenLast4", "????") : "Token từ BotFather", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
        addField("telegramAllowedChatId", "Allowed Telegram chat ID", "", config.optBoolean("telegramChatIdConfigured") ? "Đã lưu ****..." + config.optString("telegramAllowedChatIdLast4", "????") : "ID dạng số; nhiều ID cách nhau bằng dấu phẩy", InputType.TYPE_CLASS_TEXT, false);
        addCheck("clearTelegramChatId", "Xóa Allowed Telegram chat ID đã lưu", false);
        section("Lệnh Telegram điều khiển GPIO");
        gpioRowsContainer = column(body);
        gpioRowsContainer.setTag("gpio-command-rows");
        fields.put("gpioCommands", gpioRowsContainer);
        renderGpioEditor(config.optJSONArray("gpioCommands"));
        Button addGpio = button("＋ Thêm lệnh GPIO", false);
        body.addView(addGpio, full());
        addGpio.setOnClickListener(v -> {
            if (gpioRowsContainer.getChildCount() >= 8) {
                toast("Có thể cấu hình tối đa 8 lệnh GPIO.");
                return;
            }
            addGpioCommandRow(gpioRowsContainer, new JSONObject());
            settingsDirty = true;
        });
        label(body, "Tối đa 8 lệnh. Chọn chân theo board; xung 0 ms đảo trạng thái. Mỗi lệnh có thể khớp tên lệnh hoặc tin nhắn kích hoạt.", 11, MUTED, false);
        buttonAction(body, "Kiểm tra token Telegram", false, () -> callAction("/api/local/telegram/check", "POST", json("token", value("telegramBotToken")), "Đã kiểm tra Telegram token."));
        buttonAction(body, "Gửi tin nhắn thử", false, () -> callAction("/api/local/telegram/test", "POST", json("chatId", value("telegramAllowedChatId")), "Đã gửi tin nhắn thử."));
        buttonAction(body, "Lưu cấu hình local", true, () -> saveHostConfig());
        buttonAction(body, "Tải lại cấu hình đã lưu", false, () -> loadConfigForPage("settings"));
        buttonAction(body, "Xóa Telegram token", false, () -> {
            JSONObject p = collectHostConfig(); try { p.put("clearTelegramToken", true); } catch (Exception ignored) {}
            saveConfigPayload(p, "Đã xóa Telegram token.");
        });
        section("Cập nhật ứng dụng APK");
        Button btnCheckUpdate = button("🔄 Kiểm tra cập nhật APK (v" + BuildConfig.VERSION_NAME + ")", false);
        body.addView(btnCheckUpdate, full());
        btnCheckUpdate.setOnClickListener(v -> checkForAppUpdate(true));
    }

    private void renderGpioEditor(JSONArray commands) {
        if (gpioRowsContainer == null) return;
        gpioRowsContainer.removeAllViews();
        if (commands == null) return;
        for (int i = 0; i < commands.length() && i < 8; i++) {
            JSONObject command = commands.optJSONObject(i);
            if (command != null) addGpioCommandRow(gpioRowsContainer, command);
        }
    }

    private void addGpioCommandRow(LinearLayout container, JSONObject command) {
        LinearLayout card = cardStart(container);
        card.setTag("gpio-command-row");

        LinearLayout commandRow = horizontal(card);
        EditText name = gpioTextInput("command", command.optString("command", ""), "Lệnh (/relay_1)", 32);
        commandRow.addView(name, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button remove = button("Xóa", false);
        remove.setTextColor(Color.rgb(179, 57, 48));
        commandRow.addView(remove, new LinearLayout.LayoutParams(-2, dp(42)));
        remove.setOnClickListener(v -> {
            container.removeView(card);
            settingsDirty = true;
        });

        EditText trigger = gpioTextInput("trigger", command.optString("trigger", ""), "Tin nhắn kích hoạt (ví dụ: bật đèn)", 64);
        card.addView(trigger, full());

        LinearLayout controlRow = horizontal(card);
        JSONArray configuredPins = config.optJSONArray("allowedGpioPins");
        ArrayList<Integer> pins = new ArrayList<>();
        if (configuredPins != null) {
            for (int i = 0; i < configuredPins.length(); i++) {
                int pin = configuredPins.optInt(i, -1);
                if (pin >= 0 && !pins.contains(pin)) pins.add(pin);
            }
        }
        if (pins.isEmpty()) pins.addAll(Arrays.asList(2, 4, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33));
        int savedPin = command.optInt("pin", pins.get(0));
        if (!pins.contains(savedPin)) pins.add(savedPin);
        ArrayList<String> pinLabels = new ArrayList<>();
        for (int pin : pins) {
            pinLabels.add(pin == savedPin && configuredPins != null && !containsJsonInt(configuredPins, pin)
                ? "GPIO " + pin + " (không hỗ trợ)" : "GPIO " + pin);
        }
        Spinner pinSpinner = gpioSpinner(pinLabels);
        pinSpinner.setTag("pin");
        pinSpinner.setSelection(pins.indexOf(savedPin));
        controlRow.addView(pinSpinner, new LinearLayout.LayoutParams(0, dp(48), 1.15f));

        EditText pulse = gpioTextInput("pulseMs", String.valueOf(command.optInt("pulseMs", 500)), "Xung ms (0 = toggle)", 5);
        pulse.setInputType(InputType.TYPE_CLASS_NUMBER);
        pulse.setSingleLine(true);
        controlRow.addView(pulse, new LinearLayout.LayoutParams(0, dp(48), 0.85f));

        ArrayList<String> polarityLabels = new ArrayList<>();
        polarityLabels.add(tr("Mức cao (HIGH)"));
        polarityLabels.add(tr("Mức thấp (LOW)"));
        Spinner polarity = gpioSpinner(polarityLabels);
        polarity.setTag("activeHigh");
        polarity.setSelection(command.optBoolean("activeHigh", true) ? 0 : 1);
        LinearLayout polarityRow = horizontal(card);
        polarityRow.addView(polarity, full());

        EditText reply = gpioTextInput("replyMessage", command.optString("replyMessage", ""), "Tin nhắn phản hồi Telegram", 100);
        card.addView(reply, full());
    }

    private boolean containsJsonInt(JSONArray values, int expected) {
        for (int i = 0; i < values.length(); i++) if (values.optInt(i, Integer.MIN_VALUE) == expected) return true;
        return false;
    }

    private Spinner gpioSpinner(List<String> labels) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(INK);
                view.setTextSize(12);
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(INK);
                view.setBackgroundColor(SURFACE);
                view.setTextSize(14);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setPopupBackgroundDrawable(haSurface(SURFACE, 8, darkTheme ? Color.rgb(75, 96, 82) : Color.rgb(184, 207, 190)));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean first = true;
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (first) first = false;
                else if (!rendering) settingsDirty = true;
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        return spinner;
    }

    private EditText gpioTextInput(String tag, String initial, String hint, int maxLength) {
        EditText input = new EditText(this);
        input.setTag(tag);
        input.setText(initial);
        input.setHint(tr(hint));
        input.setTextSize(13);
        input.setSingleLine(true);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setPadding(dp(10), dp(6), dp(10), dp(6));
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(darkTheme ? Color.rgb(112, 174, 137) : Color.rgb(100, 150, 120)));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLength)});
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!rendering) settingsDirty = true;
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        return input;
    }

    private void renderDownloads() {
        heading("Cài đặt tải xuống", "Các tùy chọn này được lưu trên host và áp dụng trực tiếp cho các job yt‑dlp mới.");
        addSelect("videoQuality", "Chất lượng video", new String[]{"best", "1080p", "720p", "audio"}, new String[]{"Tốt nhất", "Tối đa 1080p", "Tối đa 720p", "Chỉ âm thanh (MP3)"}, "best");
        addSelect("thumbnailFormat", "Ảnh thumbnail", new String[]{"off", "original", "jpg", "png", "webp"}, new String[]{"Không tải", "Giữ định dạng gốc", "JPG", "PNG", "WebP"}, "off");
        addSelect("outputStructure", "Cấu trúc thư mục", new String[]{"flat", "date", "extractor", "extractor_date"}, new String[]{"Tất cả trong thư mục gốc", "Theo ngày tải lên", "Theo nền tảng", "Nền tảng / ngày"}, "flat");
        JSONObject d = config.optJSONObject("downloadSettings"); if (d == null) d = new JSONObject();
        addField("filenameTemplate", "Quy tắc đặt tên file", d.optString("filenameTemplate", "%(title).180B [%(id)s].%(ext)s"), "%(title)s [%(id)s].%(ext)s", InputType.TYPE_CLASS_TEXT, false);
        addSelect("mergeOutputFormat", "Định dạng file tải/ghép", new String[]{"best", "mp4", "flv", "avi", "mkv", "ts", "webm"}, new String[]{"Auto", "MP4", "FLV", "AVI", "MKV", "TS", "WebM"}, "mp4");
        addCheck("allowImageDownloads", "Cho phép tải ảnh trực tiếp và bài TikTok photo", d.optBoolean("allowImageDownloads", false));
        label(body, "TikTok photo dùng gallery-dl; worker cần cài gallery-dl bằng py -m pip install --user gallery-dl. Âm thanh của bài photo sẽ được lấy riêng thành MP3.", 11, MUTED, false);
        addCheck("mergeAudioVideo", "Ghép audio và video bằng FFmpeg", d.optBoolean("mergeAudioVideo", true));
        addField("maxConcurrentJobs", "Số job đồng thời (1–4)", String.valueOf(d.optInt("maxConcurrentJobs", 1)), "1", InputType.TYPE_CLASS_NUMBER, false);
        addField("fragmentConcurrency", "Số fragment mỗi job (1–16)", String.valueOf(d.optInt("fragmentConcurrency", 4)), "4", InputType.TYPE_CLASS_NUMBER, false);
        addField("bandwidthLimit", "Giới hạn băng thông", d.optString("bandwidthLimit", ""), "500K, 2M hoặc trống", InputType.TYPE_CLASS_TEXT, false);
        addField("retryLimit", "Số lần retry", String.valueOf(d.optInt("retryLimit", 5)), "5", InputType.TYPE_CLASS_NUMBER, false);
        addField("retryBackoffSeconds", "Backoff retry (giây)", String.valueOf(d.optInt("retryBackoffSeconds", 2)), "2", InputType.TYPE_CLASS_NUMBER, false);
        addField("socketTimeoutSeconds", "Timeout kết nối (giây)", String.valueOf(d.optInt("socketTimeoutSeconds", 30)), "30", InputType.TYPE_CLASS_NUMBER, false);
        addField("jobTimeoutMinutes", "Timeout toàn job (phút; 0 = vô hạn)", String.valueOf(d.optInt("jobTimeoutMinutes", 0)), "0", InputType.TYPE_CLASS_NUMBER, false);
        addSelect("duplicateUrlPolicy", "URL trùng trong queue", new String[]{"skip_pending", "allow"}, new String[]{"Bỏ qua URL đang chờ/chạy/đã tải", "Cho phép tải lại"}, "skip_pending");
        addSelect("existingFilePolicy", "File đã tồn tại", new String[]{"skip", "overwrite", "rename"}, new String[]{"Bỏ qua", "Ghi đè", "Tạo tên mới"}, "skip");
        addField("maxOutputDirectoryGb", "Giới hạn thư mục tải (GB; 0 = tắt)", String.valueOf(d.optDouble("maxOutputDirectoryGb", 0)), "0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, false);
        addField("minimumFreeDiskGb", "Dung lượng đĩa trống tối thiểu (GB)", String.valueOf(d.optDouble("minimumFreeDiskGb", 1)), "1", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, false);
        addField("runtimeHistoryLimit", "Lịch sử runtime tối đa", String.valueOf(d.optInt("runtimeHistoryLimit", 100)), "100", InputType.TYPE_CLASS_NUMBER, false);
        addCheck("verifyOutput", "Xác minh file đầu ra tồn tại và không rỗng", d.optBoolean("verifyOutput", true));
        addCheck("adaptiveMode", "Adaptive mode: giảm tải khi worker có lỗi", d.optBoolean("adaptiveMode", false));
        buttonAction(body, "Lưu cài đặt tải xuống", true, () -> saveDownloadSettings());
        buttonAction(body, "Tải lại cài đặt đã lưu", false, () -> loadConfigForPage("downloads"));
    }

    private void renderTelegram() {
        heading("Telegram", "Bot polling trên ESP32 để vẫn nhận URL khi Windows tắt.");
        addInfoCard(body, "Trạng thái Telegram", "telegram-status", "Đang đọc…");
        addField("telegram-test-chat", "Chat ID nhận tin thử", "", config.optBoolean("telegramChatIdConfigured") ? "Đã lưu ****..." + config.optString("telegramAllowedChatIdLast4", "????") : "Dạng số; có thể nhiều ID phân cách dấu phẩy", InputType.TYPE_CLASS_TEXT, false);
        buttonAction(body, "Kiểm tra Telegram token đã lưu", false, () -> callAction("/api/local/telegram/check", "POST", new JSONObject(), "Đã kiểm tra token."));
        buttonAction(body, "Gửi tin nhắn thử", true, () -> callAction("/api/local/telegram/test", "POST", json("chatId", value("telegram-test-chat")), "Đã gửi tin nhắn thử."));
        buttonAction(body, "Chỉnh token và danh sách chat", false, () -> openPage("settings"));
        buttonAction(body, "Mở log URL Telegram", false, () -> openPage("logs"));
        refreshTelegramCard();
    }

    private void renderLogs() {
        heading("Log URL ESP32 nhận được", "Log Telegram được lưu trên ESP32 và dùng chung với dashboard Host.");
        LinearLayout list = column(body); list.setTag("telegram-log-list"); label(list, "Đang tải log…", 13, MUTED, false);
        buttonAction(body, "Làm mới log", false, () -> loadDeviceData("logs"));
    }

    private void renderSecurity() {
        heading("Tài khoản và bảo mật", "Tài khoản dùng chung cho dashboard trên PC và app Android.");
        section("Đổi tài khoản truy cập");
        addField("currentPassword", "Mật khẩu hiện tại", "", "Nhập mật khẩu hiện tại", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
        addField("newUsername", "Tên đăng nhập mới", accountUsername, "1–32 ký tự", InputType.TYPE_CLASS_TEXT, false);
        addField("newPassword", "Mật khẩu mới", "", "Để trống nếu chỉ đổi tên", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
        addField("confirmPassword", "Nhập lại mật khẩu mới", "", "Tối thiểu 8 ký tự", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
        buttonAction(body, "Lưu tài khoản", true, () -> updateAccount());
        section("Phiên và giới hạn đăng nhập");
        JSONObject s = config.optJSONObject("securitySettings"); if (s == null) s = new JSONObject();
        addField("sessionTimeoutMinutes", "Thời hạn phiên (phút)", String.valueOf(s.optInt("sessionTimeoutMinutes", 720)), "15–1440", InputType.TYPE_CLASS_NUMBER, false);
        addField("maxLoginAttempts", "Số lần đăng nhập sai tối đa", String.valueOf(s.optInt("maxLoginAttempts", 5)), "3–20", InputType.TYPE_CLASS_NUMBER, false);
        addField("loginWindowMinutes", "Cửa sổ giới hạn đăng nhập (phút)", String.valueOf(s.optInt("loginWindowMinutes", 15)), "1–60", InputType.TYPE_CLASS_NUMBER, false);
        buttonAction(body, "Lưu cài đặt bảo mật", true, () -> saveSecurity());
        section("Sao lưu và khôi phục hệ thống");
        label(body, tr("Bản sao lưu gồm cấu hình host và ESP32 cùng các thông tin đăng nhập. File không được mã hóa; hãy lưu an toàn trong Download/Esp32 System."), 11, MUTED, false);
        LinearLayout backupActions = horizontal(body);
        Button backup = button("Backup toàn bộ config", false);
        Button restore = button("Restore toàn bộ config", false);
        backupActions.addView(backup, new LinearLayout.LayoutParams(0, dp(52), 1));
        backupActions.addView(restore, new LinearLayout.LayoutParams(0, dp(52), 1));
        backup.setOnClickListener(v -> backupSystemConfiguration());
        restore.setOnClickListener(v -> chooseSystemBackup());
        Button syncConfig = button("Sync Config", true);
        body.addView(syncConfig, full());
        syncConfig.setOnClickListener(v -> syncSystemConfiguration());
        buttonAction(body, "Đăng xuất khỏi app", false, () -> logout());
    }

    private void syncSystemConfiguration() {
        toast(tr("Đang trích xuất cấu hình và đồng bộ…"));
        runIo(() -> {
            JSONObject backup;
            String savedDomain = prefs != null ? prefs.getString("ddns_domain_raw", "") : "";
            String savedToken = prefs != null ? prefs.getString("ddns_token", "") : "";
            boolean savedEnabled = prefs != null && prefs.getBoolean("ddns_enabled", false);
            try {
                backup = api("/api/local/config/backup", "POST", new JSONObject());
                JSONObject hostConfig = backup.optJSONObject("hostConfig");
                if (hostConfig != null) {
                    if (!savedDomain.isEmpty() && hostConfig.optString("ddnsDomain").isEmpty()) hostConfig.put("ddnsDomain", savedDomain);
                    if (!savedToken.isEmpty() && hostConfig.optString("ddnsToken").isEmpty()) hostConfig.put("ddnsToken", savedToken);
                    if (!hostConfig.has("ddnsEnabled")) hostConfig.put("ddnsEnabled", savedEnabled);
                }
                JSONObject devConfig = backup.optJSONObject("deviceConfig");
                if (devConfig != null) {
                    JSONObject devSettings = devConfig.optJSONObject("settings");
                    if (devSettings != null) {
                        if (!savedDomain.isEmpty() && devSettings.optString("ddnsDomain").isEmpty()) devSettings.put("ddnsDomain", savedDomain);
                        if (!savedToken.isEmpty() && devSettings.optString("ddnsToken").isEmpty()) devSettings.put("ddnsToken", savedToken);
                        if (!devSettings.has("ddnsEnabled")) devSettings.put("ddnsEnabled", savedEnabled);
                    }
                    if (!savedDomain.isEmpty() && devConfig.optString("ddnsDomain").isEmpty()) devConfig.put("ddnsDomain", savedDomain);
                    if (!savedToken.isEmpty() && devConfig.optString("ddnsToken").isEmpty()) devConfig.put("ddnsToken", savedToken);
                    if (!devConfig.has("ddnsEnabled")) devConfig.put("ddnsEnabled", savedEnabled);
                }
            } catch (Exception hostErr) {
                backup = new JSONObject();
                backup.put("format", "esp-download-system-backup");
                backup.put("schemaVersion", 1);
                backup.put("exportedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new java.util.Date()));
                backup.put("exportedBy", "android-companion-v1.3");
                if (config != null) {
                    if (!savedDomain.isEmpty()) config.put("ddnsDomain", savedDomain);
                    if (!savedToken.isEmpty()) config.put("ddnsToken", savedToken);
                    config.put("ddnsEnabled", savedEnabled);
                }
                backup.put("hostConfig", config != null ? config : new JSONObject());
                JSONObject devConfig = new JSONObject();
                devConfig.put("apiToken", deviceApiToken);
                devConfig.put("runtimeBaseUrl", host);
                devConfig.put("ddnsDomain", savedDomain);
                devConfig.put("ddnsToken", savedToken);
                devConfig.put("ddnsEnabled", savedEnabled);
                JSONObject devSettings = new JSONObject();
                devSettings.put("apiToken", deviceApiToken);
                devSettings.put("runtimeBaseUrl", host);
                devSettings.put("ddnsDomain", savedDomain);
                devSettings.put("ddnsToken", savedToken);
                devSettings.put("ddnsEnabled", savedEnabled);
                devConfig.put("settings", devSettings);
                backup.put("deviceConfig", devConfig);
            }
            return backup;
        }, backup -> {
            byte[] data = backup.toString().getBytes(StandardCharsets.UTF_8);
            String filename = "Esp32-system-sync-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date()) + ".espbackup";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runIo(() -> {
                    saveSystemBackupToDownloads(filename, data);
                    return new JSONObject();
                }, s -> {}, err -> {});
            } else {
                pendingSystemBackup = data;
                pendingSystemBackupName = filename;
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    savePendingLegacySystemBackup();
                }
            }
            runIo(() -> {
                try {
                    return api("/api/local/config/restore", "POST", backup);
                } catch (Exception hostErr) {
                    JSONObject deviceConfig = backup.optJSONObject("deviceConfig");
                    if (deviceConfig == null) deviceConfig = backup;
                    return request(host, "/api/v1/config/restore", "POST", deviceConfig, deviceApiToken);
                }
            }, res -> {
                toast(tr("Đã tạo backup vào Download/Esp32 System và đồng bộ thành công sang thiết bị!"));
                refreshStatus(false);
            }, err -> {
                toast(tr("Đã lưu backup vào Download/Esp32 System; đồng bộ báo: ") + err.getMessage());
            });
        }, error -> toast(tr("Không trích xuất được cấu hình: ") + error.getMessage()));
    }

    private void backupSystemConfiguration() {
        runIo(() -> {
            JSONObject backup = api("/api/local/config/backup", "POST", new JSONObject());
            return json("backup", backup.toString());
        }, result -> {
            byte[] data = result.getString("backup").getBytes(StandardCharsets.UTF_8);
            String filename = "Esp32-system-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                .format(new java.util.Date()) + ".espbackup";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runIo(() -> {
                    saveSystemBackupToDownloads(filename, data);
                    return new JSONObject();
                }, saved -> toast(tr("Đã lưu backup không mã hóa trong Download/Esp32 System.")),
                    error -> toast(tr("Không lưu được file backup: ") + error.getMessage()));
            } else {
                pendingSystemBackup = data;
                pendingSystemBackupName = filename;
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    savePendingLegacySystemBackup();
                } else {
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_LEGACY_BACKUP_PERMISSION);
                }
            }
        }, error -> toast(tr("Không tạo được backup: ") + error.getMessage()));
    }

    private void savePendingLegacySystemBackup() {
        byte[] contents = pendingSystemBackup;
        String filename = pendingSystemBackupName;
        pendingSystemBackup = null;
        pendingSystemBackupName = null;
        if (contents == null || filename == null) {
            toast(tr("Không có dữ liệu backup để lưu."));
            return;
        }
        runIo(() -> {
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Esp32 System");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Could not create Download/Esp32 System.");
            }
            File destination = new File(directory, filename);
            try (OutputStream output = new FileOutputStream(destination)) {
                output.write(contents);
                output.flush();
            }
            return new JSONObject();
        }, saved -> toast(tr("Đã lưu backup không mã hóa trong Download/Esp32 System.")),
            error -> toast(tr("Không lưu được file backup: ") + error.getMessage()));
    }

    private JSONObject parseSystemBackup(byte[] data) throws Exception {
        if (data.length == 0 || data.length > 1024 * 1024) throw new IllegalArgumentException(tr("File backup rỗng hoặc vượt quá 1 MB."));
        JSONObject backup = new JSONObject(new String(data, StandardCharsets.UTF_8));
        if ("esp-download-encrypted-backup".equals(backup.optString("format"))) {
            throw new IllegalArgumentException(tr("Backup cũ được mã hóa bằng mật khẩu; hãy dùng bản app cũ để khôi phục."));
        }
        if ("esp-download-system-backup".equals(backup.optString("format")) &&
            backup.optInt("schemaVersion") == 1 &&
            (backup.opt("hostConfig") instanceof JSONObject) &&
            (backup.opt("deviceConfig") instanceof JSONObject)) {
            return backup;
        }
        if (backup.has("settings") || backup.has("deviceConfig")) {
            JSONObject systemWrapper = new JSONObject();
            systemWrapper.put("format", "esp-download-system-backup");
            systemWrapper.put("schemaVersion", 1);
            systemWrapper.put("createdAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new java.util.Date()));
            systemWrapper.put("deviceConfig", backup.has("deviceConfig") ? backup.getJSONObject("deviceConfig") : backup);
            systemWrapper.put("hostConfig", backup.has("hostConfig") ? backup.getJSONObject("hostConfig") : new JSONObject());
            return systemWrapper;
        }
        throw new IllegalArgumentException(tr("Nội dung backup không đầy đủ hoặc không được hỗ trợ."));
    }

    private void saveSystemBackupToDownloads(String filename, byte[] data) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, SYSTEM_BACKUP_MIME_TYPE);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Esp32 System");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Android không tạo được file trong Download/Esp32 System.");
        try {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IllegalStateException("Không mở được file backup để ghi.");
                output.write(data);
                output.flush();
            }
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.Downloads.IS_PENDING, 0);
            if (getContentResolver().update(uri, complete, null, null) != 1) {
                throw new IllegalStateException("Không thể hoàn tất lưu file backup.");
            }
        } catch (Exception error) {
            getContentResolver().delete(uri, null, null);
            throw error;
        }
    }

    private void chooseSystemBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{SYSTEM_BACKUP_MIME_TYPE, "application/json", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_SYSTEM_BACKUP_OPEN);
        } catch (Exception error) {
            toast(tr("Không mở được trình chọn file backup: ") + error.getMessage());
        }
    }

    private byte[] readSystemBackup(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalArgumentException("Không mở được file backup.");
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 1024 * 1024) throw new IllegalArgumentException("File backup vượt quá giới hạn 1 MB.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void restoreSystemConfiguration(Uri uri) {
        runIo(() -> {
            JSONObject backup = parseSystemBackup(readSystemBackup(uri));
            return json("backup", backup.toString());
        }, result -> {
            JSONObject backup = new JSONObject(result.getString("backup"));
            JSONObject devCfg = backup.optJSONObject("deviceConfig");
            JSONObject devSet = devCfg != null ? devCfg.optJSONObject("settings") : null;
            JSONObject hostCfg = backup.optJSONObject("hostConfig");
            String rDomain = "";
            String rToken = "";
            boolean rEnabled = false;
            boolean hasDdns = false;
            if (devSet != null && (devSet.has("ddnsDomain") || devSet.has("ddnsToken"))) {
                rDomain = devSet.optString("ddnsDomain", "");
                rToken = devSet.optString("ddnsToken", "");
                rEnabled = devSet.optBoolean("ddnsEnabled", false);
                hasDdns = true;
            } else if (hostCfg != null && (hostCfg.has("ddnsDomain") || hostCfg.has("ddnsToken"))) {
                rDomain = hostCfg.optString("ddnsDomain", "");
                rToken = hostCfg.optString("ddnsToken", "");
                rEnabled = hostCfg.optBoolean("ddnsEnabled", false);
                hasDdns = true;
            } else if (devCfg != null && (devCfg.has("ddnsDomain") || devCfg.has("ddnsToken"))) {
                rDomain = devCfg.optString("ddnsDomain", "");
                rToken = devCfg.optString("ddnsToken", "");
                rEnabled = devCfg.optBoolean("ddnsEnabled", false);
                hasDdns = true;
            } else if (backup.has("ddnsDomain") || backup.has("ddnsToken")) {
                rDomain = backup.optString("ddnsDomain", "");
                rToken = backup.optString("ddnsToken", "");
                rEnabled = backup.optBoolean("ddnsEnabled", false);
                hasDdns = true;
            }
            if (hasDdns && prefs != null) {
                prefs.edit().putString("ddns_domain_raw", rDomain)
                           .putString("ddns_token", rToken)
                           .putBoolean("ddns_enabled", rEnabled)
                           .apply();
            }
            if (hasDdns && config != null) {
                try {
                    config.put("ddnsDomain", rDomain);
                    config.put("ddnsToken", rToken);
                    config.put("ddnsEnabled", rEnabled);
                } catch (Exception ignored) {}
            }
            String chip = backup.optJSONObject("deviceConfig") == null ? "unknown"
                : backup.optJSONObject("deviceConfig").optString("chipModel", "unknown");
            new android.app.AlertDialog.Builder(this)
                .setTitle(tr("Xác nhận khôi phục cấu hình"))
                .setMessage(tr("Thao tác sẽ thay thế cấu hình host và ESP32, gồm thông tin đăng nhập và Wi-Fi. ESP32 sẽ khởi động lại; cấu hình GPIO chỉ được nhận nếu tương thích với board đang chạy. Tài khoản host sau khôi phục sẽ theo bản backup.\nNguồn backup: ") + chip + "\n\n" + tr("Tiếp tục?"))
                .setPositiveButton(tr("Khôi phục"), (dialog, which) -> runIo(() -> {
                    try {
                        return api("/api/local/config/restore", "POST", backup);
                    } catch (Exception hostErr) {
                        JSONObject deviceConfig = backup.optJSONObject("deviceConfig");
                        if (deviceConfig == null) deviceConfig = backup;
                        return request(host, "/api/v1/config/restore", "POST", deviceConfig, deviceApiToken);
                    }
                }, response -> {
                    toast(tr("Đã khôi phục cấu hình host và ESP32. ESP32 đang khởi động lại."));
                    refreshStatus(false);
                }, error -> toast(tr("Khôi phục cấu hình thất bại: ") + error.getMessage())))
                .setNegativeButton(tr("Hủy"), null)
                .show();
        }, error -> toast(tr("Không mở được backup: ") + error.getMessage()));
    }

    private void loadConfigForPage(String page) {
        runIo(() -> api("/api/local/config", "GET", null), result -> {
            config = result;
            if (result.has("ddnsDomain") || result.has("ddnsToken")) {
                String dDomain = result.optString("ddnsDomain", "");
                String dToken = result.optString("ddnsToken", "");
                boolean dEnabled = result.optBoolean("ddnsEnabled", false);
                if (!dDomain.isEmpty() && prefs != null) prefs.edit().putString("ddns_domain_raw", dDomain).apply();
                if (!dToken.isEmpty() && prefs != null) prefs.edit().putString("ddns_token", dToken).apply();
                if (prefs != null) prefs.edit().putBoolean("ddns_enabled", dEnabled).apply();
            }
            prefs.edit().putString("cached-config", result.toString()).apply();
            if (!page.equals(activePage)) return;
            if (pageDirty()) return;
            applyConfigToPage(page);
        }, error -> {
            if (config == null) {
                String cached = prefs.getString("cached-config", "");
                try { if (!cached.isEmpty()) config = new JSONObject(cached); } catch (Exception ignored) {}
                if (config == null) config = new JSONObject();
            }
            if (page.equals(activePage) && !pageDirty()) applyConfigToPage(page);
        });
    }

    private boolean pageDirty() {
        return activePage.equals("settings") ? settingsDirty : activePage.equals("downloads") ? downloadsDirty : activePage.equals("security") && securityDirty;
    }

    private boolean anyFieldFocused() { for (View view : fields.values()) if (view.hasFocus()) return true; return false; }

    private void applyConfigToPage(String page) {
        rendering = true;
        if (page.equals("settings")) {
            setIfIdle("deviceBaseUrl", config.optString("deviceBaseUrl", "")); setIfIdle("runtimeBaseUrl", config.optString("runtimeBaseUrl", "")); setIfIdle("workerName", config.optString("workerName", ""));
            setIfIdle("ytDlpPath", config.optString("ytDlpPath", "yt-dlp")); setIfIdle("ffmpegPath", config.optString("ffmpegPath", "")); setIfIdle("denoPath", config.optString("denoPath", "")); setIfIdle("outputDirectory", config.optString("outputDirectory", ""));
            setIfIdle("urlRetentionDays", String.valueOf(config.optInt("urlRetentionDays", 30))); setCheckIfIdle("telegramEnabled", config.optBoolean("telegramEnabled", true)); setIfIdle("telegramAllowedChatId", ""); setCheckIfIdle("clearTelegramChatId", false);
            setHint("deviceToken", config.optBoolean("deviceTokenConfigured") ? "Đã lưu ****..." + config.optString("deviceTokenLast4", "????") : "Dán token từ Serial Monitor");
            setHint("telegramBotToken", config.optBoolean("telegramTokenConfigured") ? "Đã lưu ****..." + config.optString("telegramTokenLast4", "????") : "Token từ BotFather");
            setHint("telegramAllowedChatId", config.optBoolean("telegramChatIdConfigured") ? "Đã lưu ****..." + config.optString("telegramAllowedChatIdLast4", "????") : "ID dạng số; nhiều ID cách nhau bằng dấu phẩy");
            if (!settingsDirty && !anyFieldFocused()) renderGpioEditor(config.optJSONArray("gpioCommands"));
        } else if (page.equals("downloads")) {
            JSONObject d=config.optJSONObject("downloadSettings"); if(d==null)d=new JSONObject();
            setSpinnerIfIdle("videoQuality",d.optString("videoQuality","best")); setSpinnerIfIdle("thumbnailFormat",d.optString("thumbnailFormat","off")); setSpinnerIfIdle("outputStructure",d.optString("outputStructure","flat")); setSpinnerIfIdle("mergeOutputFormat",d.optString("mergeOutputFormat","mp4"));
            String[] keys={"filenameTemplate","maxConcurrentJobs","fragmentConcurrency","bandwidthLimit","retryLimit","retryBackoffSeconds","socketTimeoutSeconds","jobTimeoutMinutes","maxOutputDirectoryGb","minimumFreeDiskGb","runtimeHistoryLimit"};
            for(String key:keys) { View field=fields.get(key); String current=field instanceof EditText?((EditText)field).getText().toString():""; setIfIdle(key,d.optString(key,current)); }
            setSpinnerIfIdle("duplicateUrlPolicy",d.optString("duplicateUrlPolicy","skip_pending")); setSpinnerIfIdle("existingFilePolicy",d.optString("existingFilePolicy","skip"));
            setCheckIfIdle("mergeAudioVideo",d.optBoolean("mergeAudioVideo",true)); setCheckIfIdle("allowImageDownloads",d.optBoolean("allowImageDownloads",false)); setCheckIfIdle("verifyOutput",d.optBoolean("verifyOutput",true)); setCheckIfIdle("adaptiveMode",d.optBoolean("adaptiveMode",false));
        } else if (page.equals("security")) {
            JSONObject s=config.optJSONObject("securitySettings"); if(s==null)s=new JSONObject();
            setIfIdle("newUsername",accountUsername); setIfIdle("sessionTimeoutMinutes",String.valueOf(s.optInt("sessionTimeoutMinutes",720))); setIfIdle("maxLoginAttempts",String.valueOf(s.optInt("maxLoginAttempts",5))); setIfIdle("loginWindowMinutes",String.valueOf(s.optInt("loginWindowMinutes",15)));
        }
        rendering = false;
    }

    private void setIfIdle(String key,String value) { View v=fields.get(key); if(v instanceof EditText&&!v.hasFocus()) setValue(key,value); }
    private void setCheckIfIdle(String key,boolean value) { View v=fields.get(key); if(v instanceof CheckBox&&!v.hasFocus()) ((CheckBox)v).setChecked(value); }
    private void setSpinnerIfIdle(String key,String value) {
        View v=fields.get(key); if(!(v instanceof Spinner)||v.hasFocus())return; Spinner spinner=(Spinner)v; String tag=String.valueOf(spinner.getTag()); int split=tag.indexOf('|'); if(split<0)return;
        String[] values=tag.substring(split+1).split("\u001f",-1); for(int i=0;i<values.length;i++) if(values[i].equals(value)){spinner.setSelection(i);break;}
    }

    private void refreshStatus(boolean notify) {
        if (sessionToken.isEmpty() || !statusBusy.compareAndSet(false, true)) return;
        if (sessionToken.startsWith("offline-")) {
            attemptSeamlessUpgrade();
        }
        runIo(() -> api("/api/local/status", "GET", null), result -> {
            statusBusy.set(false);
            status = result; device = result.optJSONObject("device") == null ? new JSONObject() : result.optJSONObject("device");
            telegram = result.optJSONObject("telegram") == null ? new JSONObject() : result.optJSONObject("telegram");
            if (activePage.equals("dashboard")) applyDashboard(result);
            if (activePage.equals("telegram")) refreshTelegramCard();
            if (activePage.equals("wifi")) updateApCard();
            if (activePage.equals("dashboard")) loadDeviceData("dashboard");
            if (notify) toast("Đã làm mới trạng thái.");
        }, error -> { statusBusy.set(false); if (notify) toast(error.getMessage()); });
    }

    private void refreshWifiState() { refreshStatus(false); }
    private void updateApCard() {
        View state = body.findViewWithTag("ap-state"), toggle = body.findViewWithTag("ap-toggle"), wifi = body.findViewWithTag("wifi-state");
        if (state instanceof TextView) ((TextView) state).setText(tr(device.optBoolean("connected") ? "Setup AP " + (device.optBoolean("provisioningApActive") ? "đang phát" : "đang tắt") + " · chế độ " + (device.optBoolean("provisioningApRecoveryMode") ? "khôi phục" : "bình thường") : "ESP32 chưa kết nối: " + device.optString("error", "kiểm tra URL và API token")));
        if (wifi instanceof TextView) ((TextView) wifi).setText(tr(device.optBoolean("connected") ? "Wi‑Fi: " + (device.optBoolean("wifiConnected") ? "ĐÃ KẾT NỐI · " + device.optString("wifiSsid") + " · " + device.optString("ipAddress") + " · RSSI " + device.optInt("rssi") + " dBm" : device.optString("wifiStatus", "CHƯA KẾT NỐI") + " · IP " + device.optString("ipAddress")) : "Wi‑Fi: chưa đọc được trạng thái ESP32"));
        if (toggle instanceof Switch) { ((Switch) toggle).setOnCheckedChangeListener(null); ((Switch) toggle).setChecked(device.optBoolean("provisioningApEnabled")); toggle.setEnabled(device.optBoolean("connected") && (!device.optBoolean("provisioningApEnabled") || device.optBoolean("wifiConnected"))); ((Switch) toggle).setOnCheckedChangeListener((button, enabled) -> setApEnabled((Switch) button, enabled)); }
    }

    private void setApEnabled(Switch toggle, boolean enable) {
        JSONObject payload = new JSONObject(); try { payload.put("enabled", enable); } catch (Exception ignored) {}
        toggle.setEnabled(false);
        runIo(() -> api("/api/device/api/v1/settings/ap", "POST", payload), result -> {
            if (result.optBoolean("provisioningApEnabled") != enable) throw new IllegalStateException("ESP32 chưa xác nhận trạng thái AP.");
            toast(enable ? "ESP32 xác nhận AP đã bật." : "ESP32 xác nhận AP đã tắt; khôi phục tự bật nếu Wi‑Fi mất.");
            refreshStatus(false);
        }, error -> { toggle.setOnCheckedChangeListener(null); toggle.setChecked(device.optBoolean("provisioningApEnabled")); toast(error.getMessage()); refreshStatus(false); });
    }

    private void refreshTelegramCard() {
        View v = body == null ? null : body.findViewWithTag("telegram-status");
        if (v instanceof TextView) ((TextView) v).setText(tr("Telegram API: " + (telegram.optBoolean("tokenValid") ? "online" : (telegram.optBoolean("configured") ? "lỗi/chưa xác thực" : "chưa cấu hình")) + " · token ****..." + (telegram.optString("tokenLast4").isEmpty() ? "—" : telegram.optString("tokenLast4")) + "\n" + telegram.optString("pollingState", "Chưa kiểm tra") + " · " + telegram.optString("botName", telegram.optString("botUsername", "")) + "\n" + telegram.optString("lastError", telegram.optString("message", ""))));
    }

    private void loadDeviceData(String page) {
        final String route = page.equals("logs")
            ? "/api/device/api/v1/telegram/log"
            : "/api/device/api/v1/jobs";
        runIo(() -> api(route, "GET", null), result -> {
            if (!activePage.equals(page)) return;
            if (page.equals("queue")) displayJobs(result.optJSONArray("jobs"));
            else if (page.equals("logs")) displayLogs(result.optJSONArray("entries"));
            else displayDashboardJobs(result.optJSONArray("jobs"));
        }, error -> {
            if (!activePage.equals(page)) return;
            View list = body.findViewWithTag(page.equals("queue") ? "job-list" : page.equals("logs") ? "telegram-log-list" : "dashboard-job-entries");
            if (list instanceof LinearLayout) { ((LinearLayout) list).removeAllViews(); label((LinearLayout) list, error.getMessage(), 13, Color.rgb(170, 75, 60), false); }
            else if (list instanceof TextView) ((TextView) list).setText(error.getMessage());
        });
    }

    private void displayDashboardJobs(JSONArray jobs) {
        if (!activePage.equals("dashboard")) return;
        dashboardJobs = jobs == null ? new JSONArray() : jobs;
        int completedCount = 0, failedCount = 0;
        for (int i = dashboardJobs.length() - 1; i >= 0; i--) {
            JSONObject job = dashboardJobs.optJSONObject(i); if (job == null) continue;
            String state = job.optString("status");
            if (state.equals("COMPLETED")) {
                completedCount++;
            } else if (state.equals("FAILED") || state.equals("ERROR") || state.equals("CANCELLED") || state.equals("RECOVERY_REQUIRED")) {
                failedCount++;
            }
        }
        setCard("dashboard-completed", completedCount + " URL\nHoàn tất · 30 ngày gần nhất");
        setCard("dashboard-failed", failedCount + " URL\nTải lỗi hoặc cần khôi phục");
        renderDashboardJobMonitor();
    }

    private void displayJobs(JSONArray jobs) {
        View v = body.findViewWithTag("job-list"); if (!(v instanceof LinearLayout)) return;
        LinearLayout list = (LinearLayout) v; list.removeAllViews();
        if (jobs == null || jobs.length() == 0) { label(list, "Hàng đợi trống.", 13, MUTED, false); return; }
        for (int i = jobs.length() - 1; i >= 0; i--) {
            JSONObject job = jobs.optJSONObject(i); if (job == null) continue;
            LinearLayout item = cardStart(list);
            label(item, job.optString("url"), 13, INK, true);
            label(item, "#" + job.optString("id") + " · " + job.optString("status") + " · " + String.format(Locale.US, "%.1f%%", job.optDouble("progress")) + "\n" + job.optString("speed", job.optString("eta", "")) + "\n" + job.optString("error", ""), 11, MUTED, false);
            String id = job.optString("id"), state = job.optString("status");
            if (state.equals("QUEUED") || state.equals("DISPATCHED") || state.equals("DOWNLOADING")) buttonAction(item, "Dừng job", false, () -> jobAction(id, "cancel", "POST"));
            else if (state.equals("FAILED") || state.equals("CANCELLED") || state.equals("RECOVERY_REQUIRED")) buttonAction(item, "Thử lại", false, () -> jobAction(id, "retry", "POST"));
            if (state.equals("QUEUED") || state.equals("COMPLETED") || state.equals("FAILED") || state.equals("ERROR") || state.equals("CANCELLED") || state.equals("RECOVERY_REQUIRED")) {
                buttonAction(item, "Xóa khỏi hàng đợi", false, () -> jobAction(id, "delete", "DELETE"));
            }
        }
    }

    private void displayLogs(JSONArray entries) {
        View v = body.findViewWithTag("telegram-log-list"); if (!(v instanceof LinearLayout)) return;
        LinearLayout list = (LinearLayout) v; list.removeAllViews();
        if (entries == null || entries.length() == 0) { label(list, "Chưa có URL Telegram nào được nhận.", 13, MUTED, false); return; }
        for (int i = entries.length() - 1; i >= 0; i--) {
            JSONObject entry = entries.optJSONObject(i); if (entry == null) continue;
            LinearLayout item = cardStart(list);
            label(item, entry.optString("url"), 13, INK, true);
            long received = entry.optLong("receivedAt", 0);
            label(item, (entry.optString("result").equals("QUEUED") ? "ĐƯA VÀO QUEUE" : "TỪ CHỐI") + " · chat " + entry.optString("chatId", "—") + "\n" + (received > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new java.util.Date(received * 1000L)) : "Thời gian chưa đồng bộ") + "\n" + entry.optString("detail", ""), 11, MUTED, false);
            LinearLayout actions = horizontal(item);
            Button copy = button("Copy", false), force = button("Force download", false), restart = button("Restart", false), delete = button("Delete", false);
            for (Button action : new Button[]{copy, force, restart, delete}) actions.addView(action, new LinearLayout.LayoutParams(0, dp(42), 1));
            copy.setOnClickListener(clicked -> { ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); if (manager != null) { manager.setPrimaryClip(ClipData.newPlainText("URL", entry.optString("url"))); toast("Đã copy URL."); } });
            force.setOnClickListener(clicked -> createLogJob(entry.optString("url"), true));
            restart.setOnClickListener(clicked -> restartLogJob(entry.optString("url")));
            delete.setOnClickListener(clicked -> deleteTelegramLog(entry.optString("id", "")));
        }
    }

    private void createLogJob(String url, boolean force) {
        JSONObject payload = new JSONObject(); try { payload.put("url", url); payload.put("source", "android-log"); payload.put("force", force); payload.put("createdAt", java.time.Instant.now().toString()); } catch (Exception ignored) {}
        runIo(() -> api("/api/device/api/v1/jobs", "POST", payload), result -> { toast(force ? "Đã ép URL vào hàng đợi tải." : "Đã thêm URL vào hàng đợi."); loadDeviceData(activePage.equals("dashboard") ? "dashboard" : "logs"); }, error -> toast(error.getMessage()));
    }

    private void restartLogJob(String url) {
        for (int i = dashboardJobs.length() - 1; i >= 0; i--) {
            JSONObject job = dashboardJobs.optJSONObject(i); if (job == null || !job.optString("url").equals(url)) continue;
            String state = job.optString("status");
            if (state.equals("FAILED") || state.equals("CANCELLED") || state.equals("RECOVERY_REQUIRED")) { jobAction(job.optString("id"), "retry", "POST"); return; }
            if (state.equals("QUEUED") || state.equals("DISPATCHED") || state.equals("DOWNLOADING") || state.equals("CANCEL_REQUESTED")) { toast("URL này đã có job đang chờ hoặc đang tải."); return; }
        }
        createLogJob(url, true);
    }

    private void deleteTelegramLog(String id) {
        if (id.isEmpty()) { toast("Firmware chưa cung cấp ID log. Hãy cập nhật firmware mới."); return; }
        runIo(() -> api("/api/device/api/v1/telegram/log/" + encode(id), "DELETE", null), result -> { toast("Đã xóa log URL."); loadDeviceData("logs"); }, error -> toast(error.getMessage()));
    }

    private void scanWifi() {
        buttonActionBusy("Đang quét Wi‑Fi…");
        runIo(() -> {
            if(directSetup)return request("http://192.168.4.1","/setup/scan","GET",null,"");
            try { return api("/api/device/api/v1/wifi/scan", "GET", null); }
            catch (Exception hostError) { return request("http://192.168.4.1", "/setup/scan", "GET", null, ""); }
        }, result -> {
            buttonActionDone(); View v = (directSetup ? root : body).findViewWithTag("wifi-networks"); if (!(v instanceof LinearLayout)) return;
            LinearLayout list = (LinearLayout) v; list.removeAllViews(); JSONArray arr = result.optJSONArray("networks");
            if (arr == null || arr.length() == 0) label(list, "Không tìm thấy SSID. Thử quét lại gần ESP32.", 12, MUTED, false);
            else for (int i = 0; i < arr.length(); i++) {
                JSONObject n = arr.optJSONObject(i); if (n == null) continue;
                LinearLayout item = cardStart(list);
                LinearLayout titleRow = horizontal(item);
                label(titleRow, n.optString("ssid"), 14, n.optBoolean("connected") ? GREEN : INK, true);
                titleRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
                label(titleRow, n.optInt("rssi") + " dBm", 11, MUTED, true);
                label(item, "Kênh " + n.optInt("channel") + " · " + (n.optBoolean("secure") ? "Có mật khẩu" : "Mạng mở") + (n.optBoolean("connected") ? " · ĐANG KẾT NỐI" : ""), 11, MUTED, false);
                item.setClickable(true); item.setFocusable(true);
                item.setOnClickListener(w -> { setValue("wifi-ssid", n.optString("ssid")); toast("Đã chọn Wi‑Fi " + n.optString("ssid")); });
            }
            toast(arr == null ? "Quét xong." : arr.length() + " mạng Wi‑Fi tìm thấy.");
        }, error -> { buttonActionDone(); toast(error.getMessage()); });
    }

    private void saveHostConfig() {
        JSONObject p = collectHostConfig();
        JSONArray gpio = new JSONArray();
        if (gpioRowsContainer != null) {
            for (int i = 0; i < gpioRowsContainer.getChildCount(); i++) {
                View child = gpioRowsContainer.getChildAt(i);
                if (!(child instanceof LinearLayout)) continue;
                LinearLayout row = (LinearLayout) child;
                EditText command = row.findViewWithTag("command");
                EditText trigger = row.findViewWithTag("trigger");
                EditText pulse = row.findViewWithTag("pulseMs");
                EditText reply = row.findViewWithTag("replyMessage");
                Spinner pin = row.findViewWithTag("pin");
                Spinner activeHigh = row.findViewWithTag("activeHigh");
                if (command == null || trigger == null || pulse == null || reply == null || pin == null || activeHigh == null) {
                    toast("Một dòng GPIO chưa được điền đầy đủ.");
                    return;
                }
                int pinPosition = pin.getSelectedItemPosition();
                int pulseMs;
                try {
                    pulseMs = Integer.parseInt(pulse.getText().toString().trim());
                } catch (NumberFormatException error) {
                    toast("Thời gian xung GPIO phải là số từ 0 đến 10000 ms.");
                    return;
                }
                if (pinPosition < 0 || pulseMs < 0 || pulseMs > 10000) {
                    toast("Kiểm tra chân GPIO và thời gian xung (0–10000 ms).");
                    return;
                }
                Object pinValue = pin.getItemAtPosition(pinPosition);
                String pinLabel = String.valueOf(pinValue);
                java.util.regex.Matcher pinMatch = java.util.regex.Pattern.compile("GPIO\\s+(\\d+)").matcher(pinLabel);
                if (!pinMatch.find()) {
                    toast("Không xác định được chân GPIO.");
                    return;
                }
                JSONObject entry = new JSONObject();
                try {
                    entry.put("command", command.getText().toString().trim());
                    entry.put("trigger", trigger.getText().toString().trim());
                    entry.put("pin", Integer.parseInt(pinMatch.group(1)));
                    entry.put("pulseMs", pulseMs);
                    entry.put("activeHigh", activeHigh.getSelectedItemPosition() == 0);
                    entry.put("replyMessage", reply.getText().toString().trim());
                    gpio.put(entry);
                } catch (Exception error) {
                    toast("Không thể đọc cấu hình GPIO: " + error.getMessage());
                    return;
                }
            }
        }
        try { p.put("gpioCommands", gpio); } catch (Exception ignored) {}
        saveConfigPayload(p, "Đã lưu cấu hình local.");
    }

    private JSONObject collectHostConfig() {
        JSONObject p = new JSONObject();
        try {
            p.put("deviceBaseUrl", value("deviceBaseUrl")); p.put("runtimeBaseUrl", value("runtimeBaseUrl")); p.put("workerName", value("workerName"));
            p.put("ytDlpPath", value("ytDlpPath")); p.put("ffmpegPath", value("ffmpegPath")); p.put("denoPath", value("denoPath")); p.put("outputDirectory", value("outputDirectory"));
            p.put("urlRetentionDays", number("urlRetentionDays", 30)); p.put("telegramEnabled", checked("telegramEnabled"));
            String chatId = value("telegramAllowedChatId"); if (!chatId.isEmpty() || checked("clearTelegramChatId")) p.put("telegramAllowedChatId", chatId);
            String deviceToken = value("deviceToken"); if (!deviceToken.isEmpty()) p.put("deviceToken", deviceToken);
            String tgToken = value("telegramBotToken"); if (!tgToken.isEmpty()) p.put("telegramBotToken", tgToken);
        } catch (Exception ignored) {}
        return p;
    }

    private void saveConfigPayload(JSONObject payload, String success) {
        runIo(() -> api("/api/local/config", "PUT", payload), result -> {
            config = result; settingsDirty = false; clearText("deviceToken"); clearText("telegramBotToken"); clearText("telegramAllowedChatId");
            if (fields.get("clearTelegramChatId") instanceof CheckBox) ((CheckBox) fields.get("clearTelegramChatId")).setChecked(false);
            setHint("deviceToken", result.optBoolean("deviceTokenConfigured") ? "Đã lưu ****..." + result.optString("deviceTokenLast4", "????") : "Dán token từ Serial Monitor");
            setHint("telegramBotToken", result.optBoolean("telegramTokenConfigured") ? "Đã lưu ****..." + result.optString("telegramTokenLast4", "????") : "Token từ BotFather");
            setHint("telegramAllowedChatId", result.optBoolean("telegramChatIdConfigured") ? "Đã lưu ****..." + result.optString("telegramAllowedChatIdLast4", "????") : "ID dạng số; nhiều ID cách nhau bằng dấu phẩy");
            toast(result.optBoolean("integrationsSyncPending") ? "Đã lưu trên host; sẽ đồng bộ ESP32 khi thiết bị sẵn sàng." : success);
            refreshStatus(false);
        }, error -> toast(error.getMessage()));
    }

    private void saveDownloadSettings() {
        JSONObject d = new JSONObject();
        try {
            d.put("videoQuality", selectValue("videoQuality")); d.put("thumbnailFormat", selectValue("thumbnailFormat")); d.put("outputStructure", selectValue("outputStructure"));
            d.put("filenameTemplate", value("filenameTemplate")); d.put("mergeOutputFormat", selectValue("mergeOutputFormat")); d.put("mergeAudioVideo", checked("mergeAudioVideo")); d.put("allowImageDownloads", checked("allowImageDownloads"));
            d.put("maxConcurrentJobs", number("maxConcurrentJobs", 1)); d.put("fragmentConcurrency", number("fragmentConcurrency", 4)); d.put("bandwidthLimit", value("bandwidthLimit"));
            d.put("retryLimit", number("retryLimit", 5)); d.put("retryBackoffSeconds", number("retryBackoffSeconds", 2)); d.put("socketTimeoutSeconds", number("socketTimeoutSeconds", 30));
            d.put("jobTimeoutMinutes", number("jobTimeoutMinutes", 0)); d.put("duplicateUrlPolicy", selectValue("duplicateUrlPolicy")); d.put("existingFilePolicy", selectValue("existingFilePolicy"));
            d.put("maxOutputDirectoryGb", decimal("maxOutputDirectoryGb", 0)); d.put("minimumFreeDiskGb", decimal("minimumFreeDiskGb", 1)); d.put("runtimeHistoryLimit", number("runtimeHistoryLimit", 100));
            d.put("verifyOutput", checked("verifyOutput")); d.put("adaptiveMode", checked("adaptiveMode"));
        } catch (Exception ignored) {}
        runIo(() -> api("/api/local/download-settings", "PUT", json("downloadSettings", d)), result -> { downloadsDirty = false; toast("Đã lưu cài đặt tải xuống. Job mới sẽ dùng cấu hình này."); }, error -> toast(error.getMessage()));
    }

    private void saveSecurity() {
        JSONObject p = new JSONObject(); try { p.put("sessionTimeoutMinutes", number("sessionTimeoutMinutes", 720)); p.put("maxLoginAttempts", number("maxLoginAttempts", 5)); p.put("loginWindowMinutes", number("loginWindowMinutes", 15)); } catch (Exception ignored) {}
        runIo(() -> api("/api/local/security-settings", "PUT", p), result -> { securityDirty = false; toast("Đã lưu cài đặt bảo mật."); }, error -> toast(error.getMessage()));
    }

    private void updateAccount() {
        String current = value("currentPassword"), name = value("newUsername"), next = value("newPassword");
        if (!next.equals(value("confirmPassword"))) { toast("Mật khẩu mới nhập lại không khớp."); return; }
        JSONObject p = new JSONObject(); try { p.put("currentPassword", current); p.put("username", name); p.put("newPassword", next); } catch (Exception ignored) {}
        runIo(() -> api("/api/local/account", "PUT", p), result -> {
            String loginPassword = next.isEmpty() ? current : next;
            JSONObject login = new JSONObject(); try { login.put("username", name); login.put("password", loginPassword); } catch (Exception ignored) {}
            runIo(() -> request(host, "/api/mobile/auth/login", "POST", login, ""), auth -> {
                sessionToken = auth.optString("sessionToken", ""); accountUsername = name; prefs.edit().putString("session", encrypt(sessionToken)).putString("username", accountUsername).apply();
                toast("Đã đổi tài khoản và cập nhật phiên đăng nhập app."); openPage("dashboard");
            }, error -> { clearSession(); showLogin(); toast("Đã đổi tài khoản. Hãy đăng nhập lại bằng thông tin mới."); });
        }, error -> toast(error.getMessage()));
    }

    private void logout() {
        runIo(() -> api("/api/auth/logout", "POST", new JSONObject()), result -> { clearSession(); showLogin(); }, error -> { clearSession(); showLogin(); });
    }

    private void jobAction(String id, String action, String method) {
        runIo(() -> api("/api/device/api/v1/jobs/" + encode(id) + "/" + action, method, method.equals("POST") ? new JSONObject() : null), result -> { toast("Đã cập nhật job."); loadDeviceData("queue"); }, error -> toast(error.getMessage()));
    }

    private void callAction(String path, String method, JSONObject payload, String success) {
        runIo(() -> api(path, method, payload), result -> { toast(success); refreshStatus(false); }, error -> toast(error.getMessage()));
    }

    private JSONObject api(String path, String method, JSONObject payload) throws Exception { return request(host, path, method, payload, sessionToken); }

    private JSONObject request(String base, String path, String method, JSONObject payload, String token) throws Exception {
        URL url = new URL(normalizeHost(base) + path); HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method); conn.setConnectTimeout(5000); conn.setReadTimeout(8000); conn.setUseCaches(false); conn.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) conn.setRequestProperty("X-Local-Session", token);
        if (!deviceApiToken.isEmpty()) conn.setRequestProperty("X-Device-Token", deviceApiToken);
        else if (!token.isEmpty()) conn.setRequestProperty("X-Device-Token", token);
        if (payload != null) { conn.setDoOutput(true); conn.setRequestProperty("Content-Type", "application/json; charset=utf-8"); try (OutputStream out = conn.getOutputStream()) { out.write(payload.toString().getBytes(StandardCharsets.UTF_8)); } }
        int code = conn.getResponseCode(); InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); if (stream != null) { byte[] buffer = new byte[4096]; int n; while ((n = stream.read(buffer)) > 0) bytes.write(buffer, 0, n); stream.close(); }
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8); JSONObject result;
        try { result = text.isEmpty() ? new JSONObject() : new JSONObject(text); } catch (Exception e) { result = new JSONObject(); }
        conn.disconnect();
        if (code < 200 || code >= 300) {
            JSONObject err = result.optJSONObject("error"); String message = err == null ? result.optString("error", "HTTP " + code) : err.optString("message", "HTTP " + code);
            if (code == 401 && result.optString("code").equals("LOGIN_REQUIRED")) {
                if (sessionToken.startsWith("offline-")) {
                    attemptSeamlessUpgrade();
                } else {
                    main.post(() -> { clearSession(); showLogin(); });
                }
            }
            throw new ApiException(message, code, result.optString("code"));
        }
        return result;
    }

    private byte[] requestBinary(String base, String path, String token) throws Exception {
        URL url = new URL(normalizeHost(base) + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/octet-stream");
        if (!token.isEmpty()) connection.setRequestProperty("X-Local-Session", token);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                InputStream errorStream = connection.getErrorStream();
                String errorText = "";
                if (errorStream != null) {
                    try (InputStream stream = errorStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[2048];
                        int count;
                        while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
                        errorText = new String(output.toByteArray(), StandardCharsets.UTF_8);
                    }
                }
                String message = "Firmware request failed with HTTP " + code + ".";
                try { message = new JSONObject(errorText).optString("error", message); }
                catch (Exception ignored) {}
                throw new ApiException(message, code, "");
            }
            int contentLength = connection.getContentLength();
            if (contentLength < 0 || contentLength > 32 * 1024 * 1024)
                throw new IllegalArgumentException("Invalid firmware file size.");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream(contentLength)) {
                byte[] buffer = new byte[32768];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 32 * 1024 * 1024) throw new IllegalArgumentException("Firmware exceeds the 32 MB limit.");
                    output.write(buffer, 0, count);
                }
                if (total == 0) throw new IllegalArgumentException("Firmware file is empty.");
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static final class DiscoveredHost {
        final String url;
        final String label;
        final boolean isEsp32;
        DiscoveredHost(String url, String label, boolean isEsp32) {
            this.url = url;
            this.label = label;
            this.isEsp32 = isEsp32;
        }
    }

    private void discoverHost(Button button, EditText hostInput, TextView feedback) {
        button.setEnabled(false);
        feedback.setText(tr("Đang quét mạng Wi‑Fi để tìm ESP32 và PC Host…"));
        ExecutorService scan = Executors.newFixedThreadPool(28);
        List<String> ipCandidates = new ArrayList<>();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo dhcp = wm == null ? null : wm.getDhcpInfo();
            int ip = dhcp == null ? 0 : dhcp.ipAddress, mask = dhcp == null ? 0 : dhcp.netmask;
            if (ip != 0) {
                if (mask == 0) mask = 0x00ffffff;
                int network = ip & mask, broadcast = network | ~mask;
                int first = Math.max(network + 1, ip - 128), last = Math.min(broadcast - 1, ip + 128);
                for (int address = first; address <= last && ipCandidates.size() < 512; address++) {
                    if (address != ip) ipCandidates.add(ipv4(address));
                }
            }
        } catch (Exception ignored) {}
        if (ipCandidates.isEmpty()) {
            try {
                Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
                while (nets.hasMoreElements()) for (InetAddress address : Collections.list(nets.nextElement().getInetAddresses())) if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    byte[] b = address.getAddress(); String prefix = (b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + ".";
                    for (int i = 1; i < 255; i++) ipCandidates.add(prefix + i);
                }
            } catch (Exception ignored) {}
        }

        List<Future<DiscoveredHost>> futures = new ArrayList<>();
        for (String candidateIp : ipCandidates) futures.add(scan.submit(() -> probeHost(candidateIp)));
        scan.shutdown();
        new Thread(() -> {
            List<DiscoveredHost> foundList = new ArrayList<>();
            for (Future<DiscoveredHost> future : futures) {
                try {
                    DiscoveredHost value = future.get(12, TimeUnit.SECONDS);
                    if (value != null) foundList.add(value);
                } catch (Exception ignored) {}
            }
            scan.shutdownNow();
            main.post(() -> {
                button.setEnabled(true);
                if (foundList.isEmpty()) {
                    feedback.setText(tr("Không tìm thấy thiết bị nào. Vui lòng nhập IP thủ công, ví dụ http://192.168.1.5 hoặc :8787."));
                } else if (foundList.size() == 1) {
                    DiscoveredHost d = foundList.get(0);
                    hostInput.setText(d.url); host = d.url;
                    feedback.setText(tr("Tìm thấy: ") + d.label);
                } else {
                    String[] labels = new String[foundList.size()];
                    for (int i = 0; i < foundList.size(); i++) labels[i] = foundList.get(i).label;
                    new android.app.AlertDialog.Builder(this)
                        .setTitle(tr("Chọn thiết bị tìm thấy trong LAN"))
                        .setItems(labels, (dialog, which) -> {
                            DiscoveredHost chosen = foundList.get(which);
                            hostInput.setText(chosen.url);
                            host = chosen.url;
                            feedback.setText(tr("Đã chọn: ") + chosen.label);
                        })
                        .setNegativeButton(tr("Hủy"), null)
                        .show();
                }
            });
        }).start();
    }

    private DiscoveredHost probeHost(String ipAddress) {
        try {
            URL url = new URL("http://" + ipAddress + "/api/v1/identify");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(350); c.setReadTimeout(500); c.setRequestMethod("GET");
            if (c.getResponseCode() == 200) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[1024]; int n;
                    while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                }
                c.disconnect();
                JSONObject json = new JSONObject(baos.toString("UTF-8"));
                if ("esp-download".equals(json.optString("app"))) {
                    String chip = json.optString("chip", "ESP32");
                    return new DiscoveredHost("http://" + ipAddress, "ESP32 (" + chip + ") tại " + ipAddress + " (Port 80)", true);
                }
            } else {
                c.disconnect();
            }
        } catch (Exception ignored) {}

        try {
            URL url = new URL("http://" + ipAddress + ":8787/api/mobile/health");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(350); c.setReadTimeout(500); c.setRequestMethod("GET");
            int code = c.getResponseCode(); c.disconnect();
            if (code == 200) {
                return new DiscoveredHost("http://" + ipAddress + ":8787", "Runtime Service tại " + ipAddress + ":8787", false);
            }
        } catch (Exception ignored) {}

        return null;
    }

    private void scanAndLinkRuntimeService(Button button, TextView feedback) {
        button.setEnabled(false);
        feedback.setText(tr("Đang quét mạng LAN tìm Runtime Service (Port 8787)…"));
        feedback.setTextColor(INK);

        ExecutorService scan = Executors.newFixedThreadPool(28);
        List<String> ipCandidates = new ArrayList<>();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo dhcp = wm == null ? null : wm.getDhcpInfo();
            int ip = dhcp == null ? 0 : dhcp.ipAddress, mask = dhcp == null ? 0 : dhcp.netmask;
            if (ip != 0) {
                if (mask == 0) mask = 0x00ffffff;
                int network = ip & mask, broadcast = network | ~mask;
                int first = Math.max(network + 1, ip - 128), last = Math.min(broadcast - 1, ip + 128);
                for (int address = first; address <= last && ipCandidates.size() < 512; address++) {
                    if (address != ip) ipCandidates.add(ipv4(address));
                }
            }
        } catch (Exception ignored) {}
        if (ipCandidates.isEmpty()) {
            try {
                Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
                while (nets.hasMoreElements()) for (InetAddress address : Collections.list(nets.nextElement().getInetAddresses())) if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    byte[] b = address.getAddress(); String prefix = (b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + ".";
                    for (int i = 1; i < 255; i++) ipCandidates.add(prefix + i);
                }
            } catch (Exception ignored) {}
        }

        List<Future<String>> futures = new ArrayList<>();
        for (String candidateIp : ipCandidates) {
            futures.add(scan.submit(() -> {
                try {
                    URL url = new URL("http://" + candidateIp + ":8787/api/mobile/health");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(350); c.setReadTimeout(500); c.setRequestMethod("GET");
                    int code = c.getResponseCode(); c.disconnect();
                    if (code == 200) {
                        return "http://" + candidateIp + ":8787";
                    }
                } catch (Exception ignored) {}
                return null;
            }));
        }
        scan.shutdown();

        new Thread(() -> {
            List<String> foundList = new ArrayList<>();
            for (Future<String> future : futures) {
                try {
                    String value = future.get(12, TimeUnit.SECONDS);
                    if (value != null && !foundList.contains(value)) foundList.add(value);
                } catch (Exception ignored) {}
            }
            scan.shutdownNow();
            main.post(() -> {
                button.setEnabled(true);
                if (foundList.isEmpty()) {
                    feedback.setText(tr("Không tìm thấy Runtime Service trên cổng 8787. Hãy kiểm tra máy chủ đã khởi động `node server.mjs` chưa."));
                    feedback.setTextColor(Color.rgb(190, 40, 40));
                } else if (foundList.size() == 1) {
                    String foundUrl = foundList.get(0);
                    setValue("runtimeBaseUrl", foundUrl);
                    setValue("workerName", "runtime-service");
                    settingsDirty = true;
                    feedback.setText(tr("✅ Đã tìm thấy & liên kết Runtime Service: ") + foundUrl);
                    feedback.setTextColor(GREEN);
                    toast(tr("Đã liên kết: ") + foundUrl);
                } else {
                    String[] items = foundList.toArray(new String[0]);
                    new android.app.AlertDialog.Builder(this)
                        .setTitle(tr("Chọn Runtime Service tìm thấy trong LAN"))
                        .setItems(items, (dialog, which) -> {
                            String chosen = items[which];
                            setValue("runtimeBaseUrl", chosen);
                            setValue("workerName", "runtime-service");
                            settingsDirty = true;
                            feedback.setText(tr("✅ Đã liên kết: ") + chosen);
                            feedback.setTextColor(GREEN);
                            toast(tr("Đã liên kết: ") + chosen);
                        })
                        .setNegativeButton(tr("Hủy"), null)
                        .show();
                }
            });
        }).start();
    }

    private String ipv4(int value) { return (value & 255) + "." + ((value >> 8) & 255) + "." + ((value >> 16) & 255) + "." + ((value >> 24) & 255); }
    private void runIo(Work work, Done ok, Fail fail) {
        io.execute(() -> {
            try {
                JSONObject result = work.run();
                main.post(() -> {
                    try {
                        ok.run(result);
                    } catch (Exception e) {
                        android.util.Log.e("ESP_DOWNLOAD_UI", "Dashboard callback failed", e);
                        toast(e.getMessage());
                    }
                });
            } catch (Exception e) {
                main.post(() -> fail.run(e));
            }
        });
    }
    private interface Work { JSONObject run() throws Exception; }
    private interface Done { void run(JSONObject result) throws Exception; }
    private interface Fail { void run(Exception error); }

    private EditText addField(String key, String title, String value, String hint, int inputType, boolean multiline) {
        label(body, title, 12, MUTED, true);
        EditText edit = input(key, title, value, hint, inputType, multiline); body.addView(edit, full());
        edit.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int before,int count){ if (rendering) return; if (activePage.equals("settings")) settingsDirty=true; if (activePage.equals("downloads")) downloadsDirty=true; if (activePage.equals("security")) securityDirty=true; } public void afterTextChanged(Editable e){} });
        return edit;
    }

    private EditText input(String key, String title, String value, String hint, int type, boolean multiline) {
        EditText edit = new EditText(this); edit.setTextSize(14); edit.setSingleLine(!multiline); edit.setInputType(type); edit.setText(value); edit.setHint(tr(hint)); edit.setPadding(dp(12), dp(8), dp(12), dp(8)); edit.setTextColor(INK); edit.setHintTextColor(MUTED); edit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(darkTheme ? Color.rgb(112, 174, 137) : Color.rgb(100, 150, 120)));
        fields.put(key, edit); edit.setTag("input-" + key);
        // Return the editor itself; label is added by addField/renderers where needed.
        edit.setContentDescription(tr(title));
        return edit;
    }

    private void addSelect(String key, String title, String[] values, String[] labels, String defaultValue) {
        section(title);
        Spinner spinner = new Spinner(this);
        String[] translatedLabels = new String[labels.length];
        for (int i = 0; i < labels.length; i++) translatedLabels[i] = tr(labels[i]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, translatedLabels); spinner.setAdapter(adapter);
        JSONObject d = config.optJSONObject("downloadSettings"); if (d == null) d = new JSONObject();
        String selected = d.optString(key, defaultValue); for (int i=0;i<values.length;i++) if (values[i].equals(selected)) spinner.setSelection(i);
        spinner.setTag("select-" + key); fields.put(key, spinner); body.addView(spinner, full());
        final boolean[] firstSelection = {true};
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() { public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id){ if (firstSelection[0]) { firstSelection[0] = false; return; } if(!rendering) downloadsDirty=true;} public void onNothingSelected(android.widget.AdapterView<?> p){} });
        spinner.setTag("select-" + key + "|" + String.join("\u001f", values));
    }

    private String selectValue(String key) {
        View view=fields.get(key); if (!(view instanceof Spinner)) return ""; Spinner spinner=(Spinner)view; Object tag=spinner.getTag(); String meta=String.valueOf(tag); int split=meta.indexOf('|'); if(split<0) return ""; String[] vals=meta.substring(split+1).split("\u001f",-1); int pos=spinner.getSelectedItemPosition(); return pos>=0&&pos<vals.length?vals[pos]:"";
    }

    private void addCheck(String key, String title, boolean value) {
        CheckBox check = new CheckBox(this); check.setText(tr(title)); check.setTextColor(INK); check.setTextSize(13); check.setChecked(value); fields.put(key, check); body.addView(check, full());
        check.setOnCheckedChangeListener((buttonView, isChecked) -> { if (!rendering && activePage.equals("downloads")) downloadsDirty=true; if (!rendering && activePage.equals("settings")) settingsDirty=true; });
    }

    private String value(String key) { View view=fields.get(key); if(view instanceof EditText) return ((EditText)view).getText().toString().trim(); return ""; }
    private void setValue(String key,String val){View view=fields.get(key);if(view instanceof EditText)((EditText)view).setText(val);}
    private void setHint(String key,String hint){View view=fields.get(key);if(view instanceof EditText)((EditText)view).setHint(tr(hint));}
    private boolean checked(String key){View v=fields.get(key);return v instanceof CheckBox&&((CheckBox)v).isChecked();}
    private int number(String key,int fallback){try{return Integer.parseInt(value(key));}catch(Exception e){return fallback;}}
    private double decimal(String key,double fallback){try{return Double.parseDouble(value(key));}catch(Exception e){return fallback;}}
    private void clearText(String key){View v=fields.get(key);if(v instanceof EditText)((EditText)v).setText("");}

    private void heading(String title,String subtitle){label(body,title,23,INK,true);TextView sub=label(body,subtitle,13,MUTED,false);sub.setPadding(0,dp(4),0,dp(14));}
    private void section(String title){TextView t=label(body,title,14,GREEN,true);t.setPadding(0,dp(13),0,dp(5));}
    private void addInfoCard(LinearLayout parent,String title,String tag,String content){LinearLayout box=cardStart(parent);label(box,title,12,GREEN,true);TextView detail=label(box,content,13,INK,false);detail.setTag(tag);detail.setPadding(0,dp(6),0,0);}
    private void setCard(String tag,String content){
        if(body==null)return;
        View valueView=body.findViewWithTag(tag+"-value");
        View detailView=body.findViewWithTag(tag);
        if(valueView instanceof TextView && detailView instanceof TextView){
            int newline=content.indexOf('\n');
            String value=newline>=0 ? content.substring(0,newline) : dashboardTileSummary(tag,content);
            String detail=newline>=0 ? content.substring(newline+1) : content;
            ((TextView)valueView).setText(tr(value));
            ((TextView)detailView).setText(tr(detail.replace('\n','\n')));
            return;
        }
        if(detailView instanceof TextView)((TextView)detailView).setText(tr(content));
    }

    private String dashboardTileSummary(String tag,String content){
        if(tag.equals("device-card")) return "ESP32";
        if(tag.equals("worker-card")) return content.startsWith("Windows worker") ? content.substring("Windows worker ".length()).split(" · ")[0] : "Worker";
        if(tag.equals("storage-card")) return content.contains("Ghi được") ? "Sẵn sàng ghi" : "Thư mục đầu ra";
        if(tag.equals("queue-card")) return content.split(" · ")[0];
        if(tag.equals("device-api-card")) return content.split("\\n")[0];
        if(tag.equals("telegram-card")) return content.contains("online") ? "Đang hoạt động" : "Telegram";
        if(tag.equals("settings-db-card")) return "Đồng bộ";
        if(tag.equals("device-metrics")) return "ESP32";
        if(tag.equals("runtime-card")) return content.contains("online") ? "Online" : "Offline";
        if(tag.startsWith("dashboard-")) return content.split("\\n")[0];
        return content;
    }
    private LinearLayout cardStart(LinearLayout parent){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(12),dp(14),dp(12));box.setBackgroundColor(SURFACE);LinearLayout.LayoutParams p=full();p.bottomMargin=dp(9);parent.addView(box,p);return box;}
    private LinearLayout column(ViewGroup parent){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);parent.addView(l,full());return l;}
    private LinearLayout horizontal(ViewGroup parent){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);parent.addView(l,full());return l;}
    private TextView label(ViewGroup parent,String text,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(tr(text));t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(0,dp(3),dp(5),dp(3));int width=parent instanceof LinearLayout&&((LinearLayout)parent).getOrientation()==LinearLayout.HORIZONTAL?-2:-1;parent.addView(t,new LinearLayout.LayoutParams(width,-2));return t;}
    private Button button(String text,boolean primary){Button b=new Button(this);b.setText(tr(text));b.setAllCaps(false);b.setTextSize(12);b.setTextColor(primary?Color.WHITE:GREEN);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?GREEN:(darkTheme?Color.rgb(43,60,49):Color.rgb(230,240,232))));b.setMinHeight(dp(42));return b;}
    private void buttonAction(LinearLayout parent,String text,boolean primary,Runnable action){Button b=button(text,primary);LinearLayout.LayoutParams p=full();p.topMargin=dp(5);p.bottomMargin=dp(6);parent.addView(b,p);b.setOnClickListener(v->{b.setEnabled(false);action.run();main.postDelayed(()->b.setEnabled(true),1000);});}
    private void buttonActionBusy(String message){toast(message);}
    private void buttonActionDone(){}
    private LinearLayout.LayoutParams full(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(44),1);}
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private String computeDdnsFullDomain(int providerPos, String inputDomain) {
        if (inputDomain == null) return "";
        String domain = inputDomain.trim().toLowerCase();
        if (domain.startsWith("http://")) domain = domain.substring(7);
        if (domain.startsWith("https://")) domain = domain.substring(8);
        while (domain.endsWith("/")) domain = domain.substring(0, domain.length() - 1);
        if (domain.isEmpty()) return "";

        switch (providerPos) {
            case 0: // DuckDNS
                if (!domain.contains(".")) return domain + ".duckdns.org";
                return domain;
            case 1: // No-IP
                if (!domain.contains(".")) return domain + ".ddns.net";
                return domain;
            case 2: // DynDNS
                if (!domain.contains(".")) return domain + ".dyndns.org";
                return domain;
            case 3: // FreeDNS
                if (!domain.contains(".")) return domain + ".mooo.com";
                return domain;
            default: // Manual / Custom
                return domain;
        }
    }
    private String computeDdnsFullUrl(int providerPos, String inputDomain, String portStr) {
        String fullDomain = computeDdnsFullDomain(providerPos, inputDomain);
        if (fullDomain.isEmpty()) return "";
        String port = portStr == null ? "" : portStr.trim();
        if (port.isEmpty() || port.equals("80")) {
            return "http://" + fullDomain;
        } else {
            return "http://" + fullDomain + ":" + port;
        }
    }
    private void openInBrowser(String url) {
        if (url == null || url.trim().isEmpty()) {
            toast(tr("Chưa có liên kết hợp lệ để mở."));
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast(tr("Không mở được trình duyệt: ") + e.getMessage());
        }
    }
    private String normalizeHost(String value){String result=value==null?"":value.trim();while(result.endsWith("/"))result=result.substring(0,result.length()-1);if(!result.startsWith("http://")&&!result.startsWith("https://"))result="http://"+result;return result;}
    private JSONObject json(String key,String value){JSONObject j=new JSONObject();try{j.put(key,value);}catch(Exception ignored){}return j;}
    private JSONObject json(String key,JSONObject value){JSONObject j=new JSONObject();try{j.put(key,value);}catch(Exception ignored){}return j;}
    private String bytes(long n){if(n<0)return "—";if(n<1024)return n+" B";if(n<1024*1024)return String.format(Locale.US,"%.1f KB",n/1024.0);if(n<1024L*1024*1024)return String.format(Locale.US,"%.1f MB",n/1048576.0);return String.format(Locale.US,"%.2f GB",n/1073741824.0);}
    private String encode(String s){try{return java.net.URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
    private String tr(String text) {
        if (text == null || text.isEmpty() || uiLanguage.equals("vi")) return text;
        String cached = translationCache.get(text);
        if (cached != null) return cached;
        String[][] entries = {
            {"Kết nối và nạp firmware ESP32", "Connect and flash ESP32 firmware", "连接并烧录 ESP32 固件"},
            {"Cắm board qua cáp USB OTG để quét cổng nối tiếp và nhận dạng chip.", "Plug the board in via USB OTG to scan serial ports and identify the chip.", "通过 USB OTG 连接开发板以扫描串口并识别芯片。"},
            {"🔌 Quét / kết nối OTG", "🔌 Scan / connect OTG", "🔌 扫描 / 连接 OTG"},
            {"Chưa kết nối USB OTG.", "USB OTG not connected.", "USB OTG 未连接。"},
            {"Tự nhận dạng", "Auto-detect", "自动识别"},
            {"Chọn file .bin (một hoặc nhiều)", "Select .bin file(s)", "选择 .bin 文件"},
            {"Từ thư mục firmware trên PC", "From the PC firmware folder", "从 PC 固件文件夹选择"},
            {"Chọn file .bin từ điện thoại", "Select .bin file(s) from phone", "从手机选择 .bin 文件"},
            {"Firmware trong thư mục ESP32 trên PC", "Firmware in the PC ESP32 folder", "PC ESP32 文件夹中的固件"},
            {"Chưa chọn firmware.", "No firmware selected.", "未选择固件。"},
            {"URL GitHub firmware (có thể nhập sau)", "GitHub firmware URL (can be added later)", "GitHub 固件链接（可稍后填写）"},
            {"Lấy firmware từ URL", "Fetch firmware from URL", "从链接获取固件"},
            {"Với nhiều file, chọn từng .bin và nhập địa chỉ flash tương ứng trước khi nạp.", "For multiple files, select each .bin and enter its flash address before writing.", "使用多个文件时，请为每个 .bin 选择文件并在烧录前输入对应的 Flash 地址。"},
            {"Profile lưu riêng theo model; Mode/Frequency dùng để đối chiếu header .bin, không sửa firmware.", "Profiles are saved per model. Mode/frequency are checked against the .bin header; firmware is not modified.", "配置按型号分别保存。烧录模式/频率会与 .bin 头部核对，不会修改固件。"},
            {"Đã chọn ", "Selected ", "已选择 "}, {" file firmware.", " firmware file(s).", " 个固件文件。"},
            {"Địa chỉ", "Address", "地址"}, {"Chọn thiết bị USB", "Select USB device", "选择 USB 设备"},
            {"Xác nhận nạp firmware", "Confirm firmware flash", "确认烧录固件"},
            {"Nạp và xác minh firmware hoàn tất · ", "Firmware flashed and verified · ", "固件烧录并验证完成 · "},
            {"Nạp firmware thất bại: ", "Firmware flash failed: ", "固件烧录失败："},
            {"Không tìm thấy USB serial. Kiểm tra OTG, nguồn board và cáp dữ liệu.", "No USB serial device found. Check OTG, board power, and the data cable.", "未找到 USB 串口设备。请检查 OTG、开发板电源和数据线。"},
            {"Kiểm tra hệ thống", "System Check", "系统检查"},
            {"Quét tình trạng các gói và môi trường thực thi trên máy tính Windows host.", "Scan packages and the runtime environment on the Windows host.", "扫描 Windows 主机上的软件包和运行环境。"},
            {"Thiết lập local host", "Local host settings", "本地主机设置"},
            {"Đồng bộ cấu hình chung với dashboard web trên PC.", "Sync shared settings with the PC web dashboard.", "与 PC 网页仪表板同步通用配置。"},
            {"Kết nối thiết bị và worker", "Device and worker connection", "设备与工作进程连接"},
            {"Runtime Service URL (LAN)", "Runtime Service URL (LAN)", "Runtime Service URL（局域网）"},
            {"🔍 Quét tìm Runtime Service trong LAN", "🔍 Scan for Runtime Service in LAN", "🔍 在局域网中扫描 Runtime Service"},
            {"Downloader và nơi lưu file", "Downloader and file storage", "下载器和文件存储"},
            {"Windows downloader và nơi lưu file", "Downloader and file storage", "下载器和文件存储"},
            {"Telegram nhận URL và lệnh GPIO", "Telegram URL and GPIO commands", "Telegram URL 和 GPIO 命令"},
            {"Cài đặt tải xuống", "Download settings", "下载设置"},
            {"Các tùy chọn này được lưu trên host và áp dụng trực tiếp cho các job yt‑dlp mới.", "These options are saved on the host and applied to new yt-dlp jobs.", "这些选项保存在主机上，并直接应用于新的 yt-dlp 任务。"},
            {"Tổng quan", "Dashboard", "仪表板"}, {"Hàng đợi", "Queue", "队列"},
            {"Tải xuống", "Downloads", "下载"}, {"Bảo mật", "Security", "安全"},
            {"Đăng nhập", "Sign in", "登录"}, {"Quay lại đăng nhập", "Back to sign in", "返回登录"},
            {"Thiết lập Wi‑Fi ESP32", "ESP32 Wi-Fi setup", "ESP32 Wi-Fi 设置"},
            {"Kết nối điện thoại vào AP espsystem (mật khẩu espsystem). Trang này giao tiếp trực tiếp với 192.168.4.1, không cần Windows host.", "Connect your phone to the espsystem AP (password: espsystem). This page communicates directly with 192.168.4.1 and does not require the Windows host.", "将手机连接到 espsystem AP（密码：espsystem）。此页面直接与 192.168.4.1 通信，无需 Windows 主机。"},
            {"Quét lại", "Rescan", "重新扫描"}, {"Đang tải", "Loading", "正在加载"},
            {"Đang quét môi trường host...", "Scanning the host environment...", "正在扫描主机环境..."},
            {"Không có dữ liệu", "No data", "无数据"}, {"Không nhận được danh sách gói.", "Package list was not received.", "未收到软件包列表。"},
            {"Đã cài đặt", "Installed", "已安装"}, {"Chưa cài đặt", "Not installed", "未安装"},
            {"Sẵn sàng", "Ready", "就绪"}, {"Thiếu", "Missing", "缺少"},
            {"Đường dẫn:", "Path:", "路径："}, {"Chất lượng video", "Video quality", "视频质量"},
            {"Tốt nhất", "Best", "最佳"}, {"Tối đa 1080p", "Up to 1080p", "最高 1080p"},
            {"Tối đa 720p", "Up to 720p", "最高 720p"}, {"Chỉ âm thanh (MP3)", "Audio only (MP3)", "仅音频 (MP3)"},
            {"Ảnh thumbnail", "Thumbnail image", "缩略图"}, {"Không tải", "Do not download", "不下载"},
            {"Giữ định dạng gốc", "Keep original format", "保留原始格式"},
            {"Cấu trúc thư mục", "Folder structure", "文件夹结构"},
            {"Tất cả trong thư mục gốc", "All files in the root folder", "全部放在根文件夹"},
            {"Theo ngày tải lên", "By upload date", "按上传日期"}, {"Theo nền tảng", "By platform", "按平台"},
            {"Nền tảng / ngày", "Platform / date", "平台 / 日期"},
            {"Cho phép tải ảnh trực tiếp và bài TikTok photo", "Allow direct image and TikTok photo downloads", "允许直接下载图片和 TikTok 照片帖子"},
            {"Ghép audio và video bằng FFmpeg", "Merge audio and video with FFmpeg", "使用 FFmpeg 合并音频和视频"},
            {"Bỏ qua", "Skip", "跳过"}, {"Ghi đè", "Overwrite", "覆盖"},
            {"Tạo tên mới", "Rename", "重命名"}, {"Cho phép tải lại", "Allow redownload", "允许重新下载"},
            {"Bật nhận lệnh Telegram", "Enable Telegram commands", "启用 Telegram 命令"},
            {"Lưu cấu hình local", "Save local configuration", "保存本地配置"},
            {"Tải lại cấu hình đã lưu", "Reload saved configuration", "重新加载已保存的配置"},
            {"Kiểm tra token Telegram", "Check Telegram token", "检查 Telegram 令牌"},
            {"Gửi tin nhắn thử", "Send test message", "发送测试消息"},
            {"Lưu cài đặt bảo mật", "Save security settings", "保存安全设置"},
            {"Đăng xuất khỏi app", "Sign out", "退出应用"},
            {"ESP32 online", "ESP32 online", "ESP32 在线"}, {"ESP32 offline", "ESP32 offline", "ESP32 离线"},
            {"đang phát", "broadcasting", "正在广播"}, {"chưa kết nối", "not connected", "未连接"},
            {"Đang kết nối…", "Connecting…", "正在连接…"},
            {"Đang đọc trạng thái AP…", "Reading AP status…", "正在读取 AP 状态…"},
            {"Quét Wi‑Fi quanh ESP32", "Scan Wi-Fi near ESP32", "扫描 ESP32 附近的 Wi-Fi"},
            {"Wi‑Fi SSID", "Wi-Fi SSID", "Wi-Fi 名称"}, {"Mật khẩu Wi‑Fi", "Wi-Fi password", "Wi-Fi 密码"},
            {"Chọn mạng hoặc nhập SSID", "Select a network or enter its SSID", "选择网络或输入其名称"},
            {"Để trống nếu mạng mở", "Leave blank for an open network", "开放网络请留空"},
            {"Lưu Wi‑Fi vào ESP32", "Save Wi-Fi to ESP32", "将 Wi-Fi 保存到 ESP32"},
            {"Thời hạn lưu job và log URL (ngày)", "Job and URL log retention (days)", "任务和 URL 日志保留期限（天）"},
            {"Số job đồng thời", "Concurrent jobs", "并发任务数"}, {"Giới hạn băng thông", "Bandwidth limit", "带宽限制"},
            {"Số lần retry", "Retry attempts", "重试次数"}, {"Timeout kết nối", "Connection timeout", "连接超时"},
            {"Timeout toàn job", "Job timeout", "任务超时"}, {"File đã tồn tại", "Existing file", "文件已存在"},
            {"URL trùng trong queue", "Duplicate URL in queue", "队列中的重复 URL"},
            {"Thông báo", "Notifications", "通知"}, {"Đã lưu", "Saved", "已保存"},
            {"Hủy", "Cancel", "取消"}, {"Bắt đầu cài", "Start installation", "开始安装"},
            {"Lỗi:", "Error:", "错误："}, {"Lỗi cài đặt:", "Installation error:", "安装错误："},
            {"Kết nối local host", "Connect to local host", "连接本地主机"},
            {"App native · dùng chung cấu hình và hàng đợi với dashboard trên PC", "Native app · shares settings and queue with the PC dashboard", "原生应用 · 与 PC 仪表板共用配置和队列"},
            {"Địa chỉ host", "Host address", "主机地址"}, {"Tài khoản", "Username", "用户名"},
            {"Mật khẩu", "Password", "密码"}, {"Mặc định lần đầu: admin", "Default on first use: admin", "首次使用默认值：admin"},
            {"Tìm host trong Wi‑Fi", "Find host on Wi-Fi", "在 Wi-Fi 中查找主机"},
            {"Kết nối điện thoại và PC vào cùng Wi‑Fi. Nếu đang cấu hình ESP32 qua AP espsystem, hãy chuyển về Wi‑Fi có máy host.", "Connect your phone and PC to the same Wi-Fi. If configuring the ESP32 through the espsystem AP, switch back to the Wi-Fi used by the host.", "请将手机和 PC 连接到同一 Wi-Fi。如果正在通过 espsystem AP 配置 ESP32，请切换回主机所在的 Wi-Fi。"},
            {"Thiết lập ESP32 qua AP", "Set up ESP32 via AP", "通过 AP 设置 ESP32"},
            {"Ghép nối ESP32 từ QR", "Pair ESP32 using QR", "通过二维码配对 ESP32"},
            {"Mở camera quét QR", "Scan QR with camera", "使用相机扫描二维码"},
            {"Nhập ảnh PNG", "Import PNG image", "导入 PNG 图片"},
            {"QR ghép thiết bị; tài khoản và mật khẩu vẫn dùng để đăng nhập local host.", "The QR pairs the device; use your username and password to sign in to the local host.", "二维码用于配对设备；仍需使用用户名和密码登录本地主机。"},
            {"Bật AP thiết lập", "Enable setup AP", "启用设置 AP"},
            {"Làm mới trạng thái", "Refresh status", "刷新状态"},
            {"Khởi động lại ESP32", "Reboot ESP32", "重启 ESP32"},
            {"Bạn có chắc chắn muốn khởi động lại ESP32? Thiết bị sẽ ngắt kết nối trong vài giây.", "Are you sure you want to reboot the ESP32? It will disconnect for a few seconds.", "确定要重启 ESP32 吗？设备将断开连接几秒钟。"},
            {"Cài đặt gói thiếu", "Install missing packages", "安装缺少的软件包"},
            {"Chạy lệnh cài đặt các gói cần thiết (yt-dlp, FFmpeg, aria2c, Deno, gallery-dl) qua PowerShell trên máy tính?", "Run PowerShell on the PC to install required packages (yt-dlp, FFmpeg, aria2c, Deno, gallery-dl)?", "是否在 PC 上通过 PowerShell 安装所需软件包（yt-dlp、FFmpeg、aria2c、Deno、gallery-dl）？"},
            {"ESP32 chưa kết nối:", "ESP32 not connected:", "ESP32 未连接："},
            {"Không tìm thấy host. Nhập URL LAN của PC, ví dụ http://192.168.1.25:8787.", "Host not found. Enter the PC's LAN URL, for example http://192.168.1.25:8787.", "未找到主机。请输入 PC 的局域网 URL，例如 http://192.168.1.25:8787。"},
            {"Đang quét mạng Wi‑Fi hiện tại để tìm local host…", "Scanning the current Wi-Fi network for the local host…", "正在当前 Wi-Fi 网络中搜索本地主机…"},
            {"Tìm thấy host:", "Host found:", "已找到主机："},
            {"Tìm host", "Find host", "查找主机"},
            {"Tên Runtime Service", "Runtime Service name", "Runtime Service 名称"},
            {"Tên Windows worker", "Runtime Service name", "Runtime Service 名称"},
            {"Đường dẫn yt-dlp", "yt-dlp path", "yt-dlp 路径"},
            {"Đường dẫn FFmpeg", "FFmpeg path", "FFmpeg 路径"},
            {"Đường dẫn Deno", "Deno path", "Deno 路径"},
            {"Thư mục lưu file", "Download folder", "下载文件夹"},
            {"Bật nhận lệnh Telegram", "Enable Telegram commands", "启用 Telegram 命令"},
            {"Xóa Allowed Telegram chat ID đã lưu", "Delete saved allowed Telegram chat ID", "删除已保存的 Telegram 允许聊天 ID"},
            {"Telegram bot token", "Telegram bot token", "Telegram 机器人令牌"},
            {"Allowed Telegram chat ID", "Allowed Telegram chat ID", "允许的 Telegram 聊天 ID"},
            {"Tùy chọn", "Optional", "可选"}, {"Tự tìm trên PATH", "Find automatically on PATH", "在 PATH 中自动查找"},
            {"Dán token từ Serial Monitor", "Paste token from Serial Monitor", "粘贴来自串口监视器的令牌"},
            {"Token từ BotFather", "Token from BotFather", "来自 BotFather 的令牌"},
            {"ID dạng số; nhiều ID cách nhau bằng dấu phẩy", "Numeric IDs; separate multiple IDs with commas", "数字 ID；多个 ID 请用逗号分隔"},
            {"Lệnh Telegram điều khiển GPIO", "Telegram GPIO control commands", "Telegram GPIO 控制命令"},
            {"＋ Thêm lệnh GPIO", "＋ Add GPIO command", "＋ 添加 GPIO 命令"},
            {"Xóa", "Remove", "删除"},
            {"Lệnh (/relay_1)", "Command (/relay_1)", "命令（/relay_1）"},
            {"Tin nhắn kích hoạt (ví dụ: bật đèn)", "Trigger message (for example: turn on light)", "触发消息（例如：开灯）"},
            {"Xung ms (0 = toggle)", "Pulse ms (0 = toggle)", "脉冲毫秒（0 = 切换）"},
            {"Mức cao (HIGH)", "Active high (HIGH)", "高电平（HIGH）"},
            {"Mức thấp (LOW)", "Active low (LOW)", "低电平（LOW）"},
            {"Tin nhắn phản hồi Telegram", "Telegram reply message", "Telegram 回复消息"},
            {"Tối đa 8 lệnh. Chọn chân theo board; xung 0 ms đảo trạng thái. Mỗi lệnh có thể khớp tên lệnh hoặc tin nhắn kích hoạt.", "Up to 8 commands. Choose a board-supported pin; a 0 ms pulse toggles its state. Each command can match a command name or trigger message.", "最多 8 条命令。请选择开发板支持的引脚；0 毫秒脉冲会切换状态。每条命令可匹配命令名称或触发消息。"},
            {"Log Telegram được lưu trên ESP32 và dùng chung với dashboard Host.", "Telegram logs are stored on the ESP32 and shared with the Host dashboard.", "Telegram 日志存储在 ESP32 上，并与主机仪表板共享。"},
            {"Lệnh GPIO (mỗi dòng: tên,pin,ms,high/low)", "GPIO commands (one per line: name,pin,ms,high/low)", "GPIO 命令（每行：名称、引脚、毫秒、高/低）"},
            {"Số fragment mỗi job", "Fragments per job", "每个任务的分段数"},
            {"Cho phép tải lại", "Allow redownload", "允许重新下载"},
            {"Không tải ảnh", "Do not download images", "不下载图片"},
            {"Ghép audio và video bằng FFmpeg", "Merge audio and video with FFmpeg", "使用 FFmpeg 合并音频和视频"},
            {"Xóa Telegram token", "Delete Telegram token", "删除 Telegram 令牌"},
            {"Thời hạn lưu job và log URL", "Job and URL log retention", "任务和 URL 日志保留期限"},
            {"Thiết lập local host", "Local host settings", "本地主机设置"},
            {"Windows PC", "Windows PC", "Windows PC"},
            {"Storaged", "Storage", "存储"},
            {"CONTROL PLANE · WINDOWS LOCAL HOST", "CONTROL PLANE · WINDOWS LOCAL HOST", "控制平面 · WINDOWS 本地主机"},
            {"Thư mục đầu ra", "Output folder", "输出文件夹"},
            {"ESP32 / Wi-Fi", "ESP32 / Wi-Fi", "ESP32 / Wi-Fi"},
            {"Local worker", "Local worker", "本地工作进程"},
            {"Device API", "Device API", "设备 API"}, {"Settings DB", "Settings DB", "设置数据库"},
            {"LittleFS", "LittleFS", "LittleFS"}, {"Telegram API", "Telegram API", "Telegram API"},
            {"Hàng đợi", "Queue", "队列"}, {"Log", "Logs", "日志"},
            {"Queue", "Queue", "队列"}, {"Wi‑Fi / AP", "Wi-Fi / AP", "Wi-Fi / AP"},
            {"Local host", "Local host", "本地主机"}, {"Telegram", "Telegram", "Telegram"},
            {"Check System", "System Check", "系统检查"},
            {"Lưu cấu hình local", "Save local configuration", "保存本地配置"},
            {"Quy tắc đặt tên file", "Filename template", "文件命名模板"},
            {"Định dạng file tải/ghép", "Download/merge file format", "下载/合并文件格式"},
            {"TikTok photo dùng gallery-dl; worker cần cài gallery-dl bằng py -m pip install --user gallery-dl. Âm thanh của bài photo sẽ được lấy riêng thành MP3.", "TikTok photo posts use gallery-dl; install it on the worker with py -m pip install --user gallery-dl. Audio from photo posts is downloaded separately as MP3.", "TikTok 照片帖子使用 gallery-dl；请在工作进程上通过 py -m pip install --user gallery-dl 安装。照片帖子的音频将单独下载为 MP3。"},
            {"Số job đồng thời (1–4)", "Concurrent jobs (1–4)", "并发任务数（1–4）"},
            {"Số fragment mỗi job (1–16)", "Fragments per job (1–16)", "每个任务的分段数（1–16）"},
            {"Backoff retry (giây)", "Retry backoff (seconds)", "重试退避（秒）"},
            {"Giới hạn thư mục tải (GB; 0 = tắt)", "Download folder limit (GB; 0 = off)", "下载文件夹限制（GB；0 =关闭）"},
            {"Dung lượng đĩa trống tối thiểu (GB)", "Minimum free disk space (GB)", "最小可用磁盘空间（GB）"},
            {"Lịch sử runtime tối đa", "Maximum runtime history", "最大运行历史记录"},
            {"Xác minh file đầu ra tồn tại và không rỗng", "Verify output file exists and is not empty", "验证输出文件存在且不为空"},
            {"Adaptive mode: giảm tải khi worker có lỗi", "Adaptive mode: reduce load when the worker has errors", "自适应模式：工作进程出错时降低负载"},
            {"Lưu cài đặt tải xuống", "Save download settings", "保存下载设置"},
            {"Tải lại cài đặt đã lưu", "Reload saved settings", "重新加载已保存的设置"},
            {"Bot polling trên ESP32 để vẫn nhận URL khi Windows tắt.", "The ESP32 polls the bot so it can still receive URLs while Windows is off.", "ESP32 轮询机器人，以便在 Windows 关闭时仍能接收 URL。"},
            {"Trạng thái Telegram", "Telegram status", "Telegram 状态"},
            {"Chat ID nhận tin thử", "Chat ID for test messages", "接收测试消息的聊天 ID"},
            {"Kiểm tra Telegram token đã lưu", "Check saved Telegram token", "检查已保存的 Telegram 令牌"},
            {"Chỉnh token và danh sách chat", "Edit token and chat list", "编辑令牌和聊天列表"},
            {"Mở log URL Telegram", "Open Telegram URL log", "打开 Telegram URL 日志"},
            {"Log URL ESP32 nhận được", "Log of URLs received by ESP32", "ESP32 收到的 URL 日志"},
            {"Quyết định nhận/từ chối và URL do Telegram gửi tới ESP32.", "Accepted/rejected decisions and URLs sent to ESP32 by Telegram.", "Telegram 发送到 ESP32 的 URL 及接收/拒绝结果。"},
            {"Đang tải log…", "Loading log…", "正在加载日志…"}, {"Làm mới log", "Refresh log", "刷新日志"},
            {"Chưa có URL Telegram nào được nhận.", "No Telegram URLs have been received.", "尚未收到 Telegram URL。"},
            {"Tài khoản và bảo mật", "Account and security", "账户和安全"},
            {"Tài khoản dùng chung cho dashboard trên PC và app Android.", "The account is shared by the PC dashboard and Android app.", "PC 仪表板和 Android 应用共用此账户。"},
            {"Đổi tài khoản truy cập", "Change account credentials", "更改账户凭据"},
            {"Mật khẩu hiện tại", "Current password", "当前密码"},
            {"Nhập mật khẩu hiện tại", "Enter current password", "输入当前密码"},
            {"Tên đăng nhập mới", "New username", "新用户名"},
            {"Mật khẩu mới", "New password", "新密码"},
            {"Để trống nếu chỉ đổi tên", "Leave blank to change only the username", "仅更改用户名时请留空"},
            {"Nhập lại mật khẩu mới", "Confirm new password", "再次输入新密码"},
            {"Tối thiểu 8 ký tự", "At least 8 characters", "至少 8 个字符"},
            {"Lưu tài khoản", "Save account", "保存账户"},
            {"Phiên và giới hạn đăng nhập", "Session and login limits", "会话和登录限制"},
            {"Thời hạn phiên (phút)", "Session duration (minutes)", "会话时长（分钟）"},
            {"Số lần đăng nhập sai tối đa", "Maximum failed login attempts", "最大登录失败次数"},
            {"Cửa sổ giới hạn đăng nhập (phút)", "Login limit window (minutes)", "登录限制窗口（分钟）"},
            {"Lưu cài đặt bảo mật", "Save security settings", "保存安全设置"},
            {"ESP32 là trung tâm điều phối; Gateway nối Wi-Fi, Telegram và Windows worker.", "ESP32 coordinates the system; the Gateway connects Wi-Fi, Telegram, and the Windows worker.", "ESP32 负责协调系统；网关连接 Wi-Fi、Telegram 和 Windows 工作进程。"},
            {"●  7/7 tuyến hoạt động · cập nhật trực tiếp", "●  7/7 routes active · live updates", "●  7/7 条线路运行中 · 实时更新"},
            {"Thư mục đầu ra", "Output folder", "输出文件夹"}, {"Hoàn tất (30 ngày)", "Completed (30 days)", "已完成（30 天）"},
            {"Lỗi (30 ngày)", "Failed (30 days)", "失败（30 天）"}, {"CPU · 2 core", "CPU · 2 cores", "CPU · 2 核"},
            {"RAM · heap", "RAM · heap", "RAM · 堆"}, {"Đang chờ", "Queued", "等待中"},
            {"Đã hoàn tất", "Completed", "已完成"}, {"Lỗi", "Error", "错误"}, {"URL MONITOR", "URL MONITOR", "URL 监控"},
            {"Không có URL đang chờ hoặc tải.", "No queued or downloading URLs.", "没有等待或正在下载的 URL。"},
            {"Hàng đợi tải", "Download queue", "下载队列"},
            {"Job lưu bền vững trên ESP32; worker Windows tải xuống theo thứ tự.", "Jobs are persisted on the ESP32; the Windows worker downloads them in order.", "任务持久保存在 ESP32 上；Windows 工作进程按顺序下载。"},
            {"URL cần tải", "URL to download", "要下载的 URL"},
            {"＋ Thêm vào hàng đợi", "＋ Add to queue", "＋ 添加到队列"},
            {"Job đã được lưu trên ESP32.", "Job saved on ESP32.", "任务已保存到 ESP32。"},
            {"Đang tải hàng đợi…", "Loading queue…", "正在加载队列…"},
            {"Làm mới hàng đợi", "Refresh queue", "刷新队列"},
            {"Kết nối Wi‑Fi", "Wi-Fi connection", "Wi-Fi 连接"},
            {"Quét Wi‑Fi từ ESP32, chọn SSID, nhập mật khẩu và lưu trực tiếp vào thiết bị.", "Scan for Wi-Fi networks from the ESP32, select an SSID, enter the password, and save it directly to the device.", "从 ESP32 扫描 Wi-Fi，选择 SSID，输入密码并直接保存到设备。"},
            {"Đang đọc trạng thái AP…", "Reading AP status…", "正在读取 AP 状态…"},
            {"Wi‑Fi: đang đọc trạng thái…", "Wi-Fi: reading status…", "Wi-Fi：正在读取状态…"},
            {"Lưu Wi‑Fi có thể làm ESP32 chuyển sang địa chỉ LAN mới. AP khôi phục tự bật nếu Wi‑Fi không kết nối được.", "Saving Wi-Fi may change the ESP32 LAN address. The recovery AP turns on automatically if Wi-Fi cannot connect.", "保存 Wi-Fi 可能会更改 ESP32 的局域网地址。如果 Wi-Fi 无法连接，恢复 AP 将自动开启。"},
            {"Wi‑Fi: ", "Wi-Fi: ", "Wi-Fi："}, {"ĐÃ KẾT NỐI", "CONNECTED", "已连接"},
            {"CHƯA KẾT NỐI", "NOT CONNECTED", "未连接"}, {"chưa đọc được trạng thái ESP32", "ESP32 status unavailable", "无法读取 ESP32 状态"},
            {"đang phát", "broadcasting", "正在广播"}, {"đang tắt", "off", "已关闭"},
            {"chế độ", "mode", "模式"}, {"khôi phục", "recovery", "恢复"},
            {"bình thường", "normal", "正常"}, {"Đang hoạt động", "Active", "运行中"},
            {"Đang đồng bộ", "Syncing", "正在同步"}, {"Đang chờ trên Windows", "Waiting on Windows", "正在 Windows 上等待"},
            {"đang chạy", "running", "正在运行"}, {"đang chờ", "queued", "等待中"},
            {"sẵn sàng", "ready", "就绪"}, {"đã kết nối", "connected", "已连接"},
            {"Ghi được", "Writable", "可写"}, {"dùng", "used", "已用"},
            {"trống", "free", "可用"}, {"tải lỗi hoặc cần khôi phục", "download failed or needs recovery", "下载失败或需要恢复"},
            {"Lưu Wi‑Fi có thể", "Saving Wi-Fi may", "保存 Wi-Fi 可能"},
            {"Setup AP", "Setup AP", "设置 AP"}, {"Hoàn tất · 30 ngày gần nhất", "Completed · last 30 days", "已完成 · 最近 30 天"},
            {"Tải lỗi hoặc cần khôi phục", "Failed download or needs recovery", "下载失败或需要恢复"},
            {"Kiểm tra URL và API token", "check URL and API token", "检查 URL 和 API 令牌"},
            {"Đã làm mới trạng thái.", "Status refreshed.", "状态已刷新。"},
            {"ESP32 xác nhận AP đã bật.", "ESP32 confirmed the AP is enabled.", "ESP32 已确认 AP 已启用。"},
            {"ESP32 xác nhận AP đã tắt; khôi phục tự bật nếu Wi‑Fi mất.", "ESP32 confirmed the AP is off; recovery will enable it if Wi-Fi is lost.", "ESP32 已确认 AP 已关闭；如果 Wi-Fi 断开，恢复功能会自动启用 AP。"},
            {"Đã gửi thông tin Wi‑Fi cho ESP32.", "Wi-Fi details sent to ESP32.", "Wi-Fi 信息已发送到 ESP32。"},
            {"Không có dữ liệu", "No data", "无数据"}, {"Chưa có dữ liệu", "No data yet", "暂无数据"},
            {"Hàng đợi trống.", "Queue is empty.", "队列为空。"}, {"Dừng job", "Stop job", "停止任务"},
            {"Thử lại", "Retry", "重试"}, {"Xóa khỏi hàng đợi", "Remove from queue", "从队列中移除"},
            {"Sao lưu và khôi phục hệ thống", "System backup and restore", "系统备份与恢复"},
            {"Backup toàn bộ config", "Back up all configuration", "备份所有配置"},
            {"Restore toàn bộ config", "Restore all configuration", "恢复所有配置"},
            {"Tiếp tục", "Continue", "继续"},
            {"Xác nhận khôi phục cấu hình", "Confirm configuration restore", "确认恢复配置"},
            {"Khôi phục", "Restore", "恢复"},
            {"Bản sao lưu gồm cấu hình host và ESP32 cùng các thông tin đăng nhập. File không được mã hóa; hãy lưu an toàn trong Download/Esp32 System.", "The backup includes host and ESP32 settings, including credentials. The file is unencrypted; store it safely under Download/Esp32 System.", "备份包含主机和 ESP32 设置及凭据。文件未加密；请安全保存到 Download/Esp32 System。"},
            {"Đã lưu backup không mã hóa trong Download/Esp32 System.", "Unencrypted backup saved in Download/Esp32 System.", "未加密备份已保存到 Download/Esp32 System。"},
            {"Không lưu được file backup: ", "Could not save backup file: ", "无法保存备份文件："},
            {"Không tạo được backup: ", "Could not create backup: ", "无法创建备份："},
            {"Không mở được backup: ", "Could not open backup: ", "无法打开备份："},
            {"Backup cũ được mã hóa bằng mật khẩu; hãy dùng bản app cũ để khôi phục.", "This legacy backup is password-encrypted; use the previous app version to restore it.", "此旧版备份使用密码加密；请使用旧版应用恢复。"},
            {"Không mở được trình chọn file backup: ", "Could not open the backup file picker: ", "无法打开备份文件选择器："},
            {"Cần cấp quyền ghi bộ nhớ để lưu backup trong Download/Esp32 System.", "Storage write permission is required to save backups under Download/Esp32 System.", "需要存储写入权限才能将备份保存到 Download/Esp32 System。"},
            {"Không có dữ liệu backup để lưu.", "There is no backup data to save.", "没有可保存的备份数据。"},
            {"Đã khôi phục cấu hình host và ESP32. ESP32 đang khởi động lại.", "Host and ESP32 settings restored. The ESP32 is restarting.", "主机和 ESP32 配置已恢复。ESP32 正在重启。"},
            {"Khôi phục cấu hình thất bại: ", "Could not restore configuration: ", "无法恢复配置："},
            {"File backup rỗng hoặc vượt quá 1 MB.", "Backup file is empty or exceeds 1 MB.", "备份文件为空或超过 1 MB。"},
            {"Nội dung backup không đầy đủ hoặc không được hỗ trợ.", "Backup contents are incomplete or unsupported.", "备份内容不完整或不受支持。"},
            {"Thao tác sẽ thay thế cấu hình host và ESP32, gồm thông tin đăng nhập và Wi-Fi. ESP32 sẽ khởi động lại; cấu hình GPIO chỉ được nhận nếu tương thích với board đang chạy. Tài khoản host sau khôi phục sẽ theo bản backup.\nNguồn backup: ", "This replaces host and ESP32 settings, including credentials and Wi-Fi, then restarts the ESP32. GPIO settings are applied only if supported by the target board. The restored host account will use the backup credentials.\nBackup source: ", "此操作将替换主机和 ESP32 配置（包括凭据和 Wi-Fi），然后重启 ESP32。GPIO 配置仅在目标板支持时应用。恢复后主机账户将使用备份中的凭据。\n备份来源："},
            {"Tiếp tục?", "Continue?", "继续吗？"},
            {"CPU", "CPU", "CPU"}, {"online", "online", "在线"}, {"offline", "offline", "离线"},
            {"URL", "URL", "URL"}, {"Wi‑Fi", "Wi-Fi", "Wi-Fi"},
            {"Không truy cập được 192.168.4.1. Hãy kiểm tra điện thoại đã nối AP espsystem chưa.", "Cannot reach 192.168.4.1. Check that the phone is connected to the espsystem AP.", "无法访问 192.168.4.1。请确认手机已连接到 espsystem AP。"},
            {"Đã lưu Wi‑Fi; ESP32 đang kết nối.", "Wi-Fi saved; ESP32 is connecting.", "Wi-Fi 已保存；ESP32 正在连接。"},
            {"Change interface language", "Change interface language", "更改界面语言"},
            {"Tìm & Kết nối AP ESP32", "Find & Connect ESP32 AP", "查找并连接 ESP32 AP"},
            {"Quét tìm AP phát từ ESP32 (mặc định 'espsystem'), xác thực kết nối và mở giao diện cài đặt qua 192.168.4.1.", "Scan for AP broadcast from ESP32 (default 'espsystem'), authenticate connection and open setup UI via 192.168.4.1.", "扫描 ESP32 发出的 AP（默认 'espsystem'），验证连接并通过 192.168.4.1 打开设置界面。"},
            {"🔍 Quét tìm AP ESP32", "🔍 Scan for ESP32 AP", "🔍 扫描 ESP32 AP"},
            {"Đang quét sóng AP từ các thiết bị ESP32 gần bạn…", "Scanning for nearby ESP32 APs…", "正在扫描附近的 ESP32 AP…"},
            {"Chưa thấy phát sóng 'espsystem' trong tầm quét", "No 'espsystem' broadcast found in range", "扫描范围内未发现 'espsystem' 广播"},
            {"Nếu board vừa cắm điện, vui lòng đợi 5-10s hoặc thử kết nối trực tiếp đến AP mặc định bên dưới:", "If the board was just plugged in, please wait 5-10s or try connecting directly to the default AP below:", "如果开发板刚上电，请等待 5-10 秒或尝试直接连接下方默认 AP："},
            {"⚡ Kết nối AP mặc định (espsystem)", "⚡ Connect to default AP (espsystem)", "⚡ 连接默认 AP (espsystem)"},
            {"Mở cài đặt Wi‑Fi trên điện thoại", "Open Wi-Fi settings on phone", "打开手机 Wi-Fi 设置"},
            {"Xác thực & Kết nối AP này", "Authenticate & Connect to this AP", "验证并连接此 AP"},
            {"✅ Đã xác thực kết nối AP thành công!", "✅ Authenticated and connected to AP successfully!", "✅ AP 验证并连接成功！"},
            {"Trạng thái: Sẵn sàng cấu hình", "Status: Ready for setup", "状态：准备好进行配置"},
            {"⚙️ Mở giao diện cài đặt (192.168.4.1)", "⚙️ Open setup interface (192.168.4.1)", "⚙️ 打开设置界面 (192.168.4.1)"},
            {"🌐 Mở trên trình duyệt web (192.168.4.1)", "🌐 Open in web browser (192.168.4.1)", "🌐 在网页浏览器中打开 (192.168.4.1)"},
            {"✅ Đã kết nối và xác thực với ESP32 qua AP!", "✅ Connected and authenticated with ESP32 via AP!", "✅ 已通过 AP 成功连接并验证 ESP32！"},
            {"Đang yêu cầu kết nối tới AP ", "Requesting connection to AP ", "正在请求连接到 AP "},
            {"Đang xác thực kết nối với AP ", "Authenticating connection to AP ", "正在验证连接到 AP "}
        };
        int languageIndex = uiLanguage.equals("zh") ? 2 : 1;
        for (String[] entry : entries) if (entry[0].equals(text)) {
            translationCache.put(text, entry[languageIndex]);
            return entry[languageIndex];
        }
        List<String[]> ordered = new ArrayList<>(Arrays.asList(entries));
        Collections.sort(ordered, (a, b) -> Integer.compare(b[0].length(), a[0].length()));
        String translated = text;
        for (String[] entry : ordered) translated = translated.replace(entry[0], entry[languageIndex]);
        translationCache.put(text, translated);
        return translated;
    }
    private void toast(String message){if(message==null||message.isEmpty())return;android.widget.Toast.makeText(this,tr(message),android.widget.Toast.LENGTH_LONG).show();}
    private void clearSession(){sessionToken="";prefs.edit().remove("session").apply();}

    private String encrypt(String plain){try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(!ks.containsAlias("esp-download-session")){KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");kg.init(new KeyGenParameterSpec.Builder("esp-download-session",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());kg.generateKey();}SecretKey key=(SecretKey)ks.getKey("esp-download-session",null);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);byte[] iv=cipher.getIV(),data=cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));byte[] all=new byte[iv.length+data.length];System.arraycopy(iv,0,all,0,iv.length);System.arraycopy(data,0,all,iv.length,data.length);return Base64.getEncoder().encodeToString(all);}catch(Exception e){return "";}}
    private String decrypt(String encoded){try{if(encoded.isEmpty())return "";byte[] all=Base64.getDecoder().decode(encoded);byte[] iv=java.util.Arrays.copyOfRange(all,0,12),data=java.util.Arrays.copyOfRange(all,12,all.length);KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);SecretKey key=(SecretKey)ks.getKey("esp-download-session",null);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));return new String(cipher.doFinal(data),StandardCharsets.UTF_8);}catch(Exception e){return "";}}

    private static final class ApiException extends Exception { final int status; final String code; ApiException(String m,int s,String c){super(m);status=s;code=c;} }
}
