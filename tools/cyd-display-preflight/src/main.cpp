#include <Arduino.h>
#include <Preferences.h>
#include <LovyanGFX.hpp>
#include <lvgl.h>

#include "board_config.h"

#if CYD_TFT_DRIVER == CYD_DRIVER_ST7796
    #define CYD_PANEL_TYPE lgfx::Panel_ST7796
#elif CYD_TFT_DRIVER == CYD_DRIVER_ILI9488
    #define CYD_PANEL_TYPE lgfx::Panel_ILI9488
#else
    #error "Unsupported CYD_TFT_DRIVER. Select ST7796 or ILI9488 in board_config.h."
#endif

class CydDisplay final : public lgfx::LGFX_Device {
    lgfx::Bus_SPI _bus;
    CYD_PANEL_TYPE _panel;
    lgfx::Light_PWM _backlight;
    lgfx::Touch_XPT2046 _touch;

public:
    CydDisplay() {
        auto busCfg = _bus.config();
        busCfg.spi_host = CYD_SPI_HOST;
        busCfg.spi_mode = 0;
        busCfg.freq_write = CYD_TFT_SPI_WRITE_HZ;
        busCfg.freq_read = CYD_TFT_SPI_READ_HZ;
        busCfg.spi_3wire = false;
        busCfg.use_lock = true;
        busCfg.dma_channel = SPI_DMA_CH_AUTO;
        busCfg.pin_sclk = CYD_SPI_SCLK;
        busCfg.pin_mosi = CYD_SPI_MOSI;
        busCfg.pin_miso = CYD_SPI_MISO;
        busCfg.pin_dc = CYD_TFT_DC;
        _bus.config(busCfg);
        _panel.setBus(&_bus);

        auto panelCfg = _panel.config();
        panelCfg.pin_cs = CYD_TFT_CS;
        panelCfg.pin_rst = CYD_TFT_RST;
        panelCfg.pin_busy = -1;
        panelCfg.panel_width = CYD_WIDTH;
        panelCfg.panel_height = CYD_HEIGHT;
        panelCfg.memory_width = CYD_WIDTH;
        panelCfg.memory_height = CYD_HEIGHT;
        panelCfg.offset_x = 0;
        panelCfg.offset_y = 0;
        panelCfg.offset_rotation = 0;
        panelCfg.readable = false;
        panelCfg.invert = false;
        panelCfg.rgb_order = false;
        panelCfg.bus_shared = true;
        _panel.config(panelCfg);

        auto lightCfg = _backlight.config();
        lightCfg.pin_bl = CYD_TFT_BL;
        lightCfg.invert = false;
        lightCfg.freq = 44100;
        lightCfg.pwm_channel = 7;
        _backlight.config(lightCfg);
        _panel.setLight(&_backlight);

        auto touchCfg = _touch.config();
        touchCfg.x_min = 300;
        touchCfg.x_max = 3900;
        touchCfg.y_min = 300;
        touchCfg.y_max = 3900;
        touchCfg.pin_int = CYD_TOUCH_IRQ;
        touchCfg.bus_shared = true;
        touchCfg.offset_rotation = 0;
        touchCfg.spi_host = CYD_SPI_HOST;
        touchCfg.freq = CYD_TOUCH_SPI_HZ;
        touchCfg.pin_sclk = CYD_SPI_SCLK;
        touchCfg.pin_mosi = CYD_SPI_MOSI;
        touchCfg.pin_miso = CYD_SPI_MISO;
        touchCfg.pin_cs = CYD_TOUCH_CS;
        _touch.config(touchCfg);
        _panel.setTouch(&_touch);

        setPanel(&_panel);
    }
};

static CydDisplay display;
static Preferences preferences;

static constexpr uint32_t kLvglBufferLines = 10;
static lv_color_t drawBufferA[CYD_WIDTH * kLvglBufferLines];
static lv_color_t drawBufferB[CYD_WIDTH * kLvglBufferLines];
static lv_disp_draw_buf_t drawBuffer;
static lv_disp_drv_t displayDriver;
static lv_indev_drv_t inputDriver;
static lv_indev_t *touchInput = nullptr;

static lv_obj_t *touchStatusLabel = nullptr;
static lv_obj_t *calibrationStatusLabel = nullptr;
static bool calibrationRequested = false;
static bool calibrationLoaded = false;
static bool rawTouchLogging = false;
static bool lvglReady = false;
static uint32_t lastLvglTick = 0;
static uint32_t lastTouchUiUpdate = 0;
static uint32_t touchReadCount = 0;
static int32_t lastTouchX = -1;
static int32_t lastTouchY = -1;

static void printHardwareReport() {
    Serial.println();
    Serial.println(F("=== CYD DISPLAY PREFLIGHT ==="));
    Serial.printf("Chip: %s rev %u, %u core(s)\n", ESP.getChipModel(), ESP.getChipRevision(), ESP.getChipCores());
    Serial.printf("Flash: %u MB; PSRAM detected: %s (%u MB)\n",
                  static_cast<unsigned>(ESP.getFlashChipSize() / (1024U * 1024U)),
                  psramFound() ? "yes" : "no",
                  static_cast<unsigned>(ESP.getPsramSize() / (1024U * 1024U)));
    Serial.printf("Free heap: %u bytes; free PSRAM: %u bytes\n",
                  static_cast<unsigned>(ESP.getFreeHeap()),
                  static_cast<unsigned>(ESP.getFreePsram()));
    Serial.printf("LCD config: %ux%u, driver=%s, rotation=%u\n",
                  CYD_WIDTH, CYD_HEIGHT,
#if CYD_TFT_DRIVER == CYD_DRIVER_ST7796
                  "ST7796",
#else
                  "ILI9488",
#endif
                  CYD_ROTATION);
    Serial.printf("TFT GPIO: SCLK=%d MISO=%d MOSI=%d CS=%d DC=%d RST=%d BL=%d\n",
                  CYD_SPI_SCLK, CYD_SPI_MISO, CYD_SPI_MOSI, CYD_TFT_CS,
                  CYD_TFT_DC, CYD_TFT_RST, CYD_TFT_BL);
    Serial.printf("Touch: XPT2046, CS=%d IRQ=%d, shared SPI=%d/%d/%d @ %u Hz\n",
                  CYD_TOUCH_CS, CYD_TOUCH_IRQ, CYD_SPI_SCLK, CYD_SPI_MISO,
                  CYD_SPI_MOSI, CYD_TOUCH_SPI_HZ);
    Serial.printf("LVGL: %u.%u.%u; panel reports %ux%u\n",
                  lv_version_major(), lv_version_minor(), lv_version_patch(),
                  display.width(), display.height());
    Serial.println(F("Serial commands: i=report, c=calibrate, r=toggle raw-touch log"));
    Serial.println(F("=============================="));
}

static bool readCalibration(uint16_t values[8]) {
    preferences.begin("cyd-preflight", true);
    const size_t storedLength = preferences.getBytesLength("touchcal");
    if (storedLength == sizeof(uint16_t) * 8) {
        preferences.getBytes("touchcal", values, sizeof(uint16_t) * 8);
    }
    preferences.end();
    return storedLength == sizeof(uint16_t) * 8;
}

static void writeCalibration(const uint16_t values[8]) {
    preferences.begin("cyd-preflight", false);
    const size_t saved = preferences.putBytes("touchcal", values, sizeof(uint16_t) * 8);
    preferences.end();
    Serial.printf("Touch calibration saved to NVS: %s\n", saved == sizeof(uint16_t) * 8 ? "yes" : "no");
}

static void runTouchCalibration() {
    rawTouchLogging = false;
    if (touchInput != nullptr) {
        lv_indev_reset(touchInput, nullptr);
    }

    Serial.println(F("Touch calibration started. Tap the four arrow tips in order."));
    uint16_t calibration[8] = {};
    display.fillScreen(TFT_BLACK);
    display.setTextColor(TFT_WHITE, TFT_BLACK);
    display.setTextDatum(lgfx::textdatum_t::middle_center);
    display.drawString("Touch each arrow tip in order", display.width() / 2, display.height() / 2);
    display.setTextDatum(lgfx::textdatum_t::top_left);
    display.calibrateTouch(calibration, TFT_WHITE, TFT_BLACK, 22);
    display.setTouchCalibrate(calibration);
    writeCalibration(calibration);

    Serial.print(F("Touch calibration values: {"));
    for (size_t i = 0; i < 8; ++i) {
        Serial.printf("%u%s", calibration[i], i == 7 ? "}\n" : ", ");
    }

    if (calibrationStatusLabel != nullptr) {
        lv_label_set_text(calibrationStatusLabel, "Calibration saved to ESP32 NVS");
        lv_obj_set_style_text_color(calibrationStatusLabel, lv_color_hex(0x60D394), LV_PART_MAIN);
    }
    if (lvglReady) {
        lv_obj_invalidate(lv_scr_act());
        lv_refr_now(nullptr);
    }
}

static void flushDisplay(lv_disp_drv_t *driver, const lv_area_t *area, lv_color_t *pixels) {
    const uint32_t width = static_cast<uint32_t>(area->x2 - area->x1 + 1);
    const uint32_t height = static_cast<uint32_t>(area->y2 - area->y1 + 1);
    display.startWrite();
    display.pushImage(area->x1, area->y1, width, height, reinterpret_cast<uint16_t *>(pixels));
    display.endWrite();
    lv_disp_flush_ready(driver);
}

static void readTouch(lv_indev_drv_t *, lv_indev_data_t *data) {
    if (rawTouchLogging || calibrationRequested) {
        data->state = LV_INDEV_STATE_RELEASED;
        return;
    }

    int32_t x = 0;
    int32_t y = 0;
    if (display.getTouch(&x, &y)) {
        data->state = LV_INDEV_STATE_PRESSED;
        data->point.x = x;
        data->point.y = y;
        ++touchReadCount;
        lastTouchX = x;
        lastTouchY = y;

        if (touchStatusLabel != nullptr && millis() - lastTouchUiUpdate > 100) {
            lv_label_set_text_fmt(touchStatusLabel, "Touch OK  x=%ld  y=%ld  samples=%lu",
                                  static_cast<long>(x), static_cast<long>(y),
                                  static_cast<unsigned long>(touchReadCount));
            lastTouchUiUpdate = millis();
        }
    } else {
        data->state = LV_INDEV_STATE_RELEASED;
        data->point.x = lastTouchX;
        data->point.y = lastTouchY;
    }
}

static void calibrationButtonEvent(lv_event_t *event) {
    if (lv_event_get_code(event) == LV_EVENT_CLICKED) {
        calibrationRequested = true;
    }
}

static lv_obj_t *addLabel(lv_obj_t *parent, const char *text, lv_coord_t x, lv_coord_t y,
                          lv_color_t color = lv_color_white()) {
    lv_obj_t *label = lv_label_create(parent);
    lv_label_set_text(label, text);
    lv_obj_set_pos(label, x, y);
    lv_obj_set_style_text_color(label, color, LV_PART_MAIN);
    return label;
}

static void addColorSwatch(lv_obj_t *parent, const char *caption, uint32_t color,
                           lv_coord_t x, lv_coord_t y) {
    lv_obj_t *swatch = lv_obj_create(parent);
    lv_obj_set_size(swatch, 62, 40);
    lv_obj_set_pos(swatch, x, y);
    lv_obj_set_style_bg_color(swatch, lv_color_hex(color), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(swatch, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_color(swatch, lv_color_white(), LV_PART_MAIN);
    lv_obj_set_style_border_width(swatch, 1, LV_PART_MAIN);
    lv_obj_set_style_radius(swatch, 3, LV_PART_MAIN);
    lv_obj_t *label = lv_label_create(swatch);
    lv_label_set_text(label, caption);
    lv_obj_center(label);
    lv_obj_set_style_text_color(label, color == 0xFFFFFF ? lv_color_black() : lv_color_white(), LV_PART_MAIN);
}

static void buildDiagnosticsScreen() {
    lv_obj_t *screen = lv_scr_act();
    lv_obj_set_style_bg_color(screen, lv_color_hex(0x101820), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_pad_all(screen, 0, LV_PART_MAIN);

    addLabel(screen, "CYD DISPLAY PREFLIGHT", 12, 12, lv_color_hex(0x70D6FF));
    addLabel(screen, "ESP32-3248S035R | ST7796 | XPT2046", 12, 39);
    addLabel(screen, "LVGL 8 | portrait 320 x 480", 12, 62);
    addLabel(screen, "TFT: 14/12/13  CS15 DC2 BL27", 12, 88, lv_color_hex(0xD7E2E8));
    addLabel(screen, "Touch: shared SPI  CS33  IRQ36", 12, 110, lv_color_hex(0xD7E2E8));

    addColorSwatch(screen, "RED", 0xFF0000, 12, 145);
    addColorSwatch(screen, "GREEN", 0x00FF00, 86, 145);
    addColorSwatch(screen, "BLUE", 0x0000FF, 160, 145);
    addColorSwatch(screen, "WHITE", 0xFFFFFF, 234, 145);

    lv_obj_t *outline = lv_obj_create(screen);
    lv_obj_set_size(outline, 280, 100);
    lv_obj_set_pos(outline, 20, 198);
    lv_obj_set_style_bg_color(outline, lv_color_hex(0x172A3A), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(outline, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_color(outline, lv_color_hex(0x70D6FF), LV_PART_MAIN);
    lv_obj_set_style_border_width(outline, 2, LV_PART_MAIN);
    lv_obj_set_style_radius(outline, 0, LV_PART_MAIN);
    lv_obj_clear_flag(outline, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_t *centerLabel = lv_label_create(outline);
    lv_label_set_text(centerLabel, "CENTER / GEOMETRY CHECK");
    lv_obj_center(centerLabel);

    touchStatusLabel = addLabel(screen, "Touch: waiting", 12, 317, lv_color_hex(0xFFD166));
    calibrationStatusLabel = addLabel(
        screen,
        calibrationLoaded ? "Saved calibration loaded; tap to check" : "No calibration; send c in Serial to start",
        12, 341, calibrationLoaded ? lv_color_hex(0x60D394) : lv_color_hex(0xFFD166));

    lv_obj_t *calButton = lv_btn_create(screen);
    lv_obj_set_size(calButton, 190, 48);
    lv_obj_align(calButton, LV_ALIGN_TOP_MID, 0, 374);
    lv_obj_add_event_cb(calButton, calibrationButtonEvent, LV_EVENT_CLICKED, nullptr);
    lv_obj_t *buttonLabel = lv_label_create(calButton);
    lv_label_set_text(buttonLabel, "RECALIBRATE TOUCH");
    lv_obj_center(buttonLabel);

    addLabel(screen, "Serial: i=info  c=calibrate  r=raw", 12, 442, lv_color_hex(0xA8B8C4));
}

static void initializeLvgl() {
    lv_init();
    lv_disp_draw_buf_init(&drawBuffer, drawBufferA, drawBufferB,
                          CYD_WIDTH * kLvglBufferLines);

    lv_disp_drv_init(&displayDriver);
    displayDriver.hor_res = display.width();
    displayDriver.ver_res = display.height();
    displayDriver.flush_cb = flushDisplay;
    displayDriver.draw_buf = &drawBuffer;
    lv_disp_drv_register(&displayDriver);

    lv_indev_drv_init(&inputDriver);
    inputDriver.type = LV_INDEV_TYPE_POINTER;
    inputDriver.read_cb = readTouch;
    touchInput = lv_indev_drv_register(&inputDriver);
    buildDiagnosticsScreen();
    lvglReady = true;
}

static void handleSerialCommands() {
    while (Serial.available() > 0) {
        const char command = static_cast<char>(Serial.read());
        if (command == 'i' || command == 'I') {
            printHardwareReport();
        } else if (command == 'c' || command == 'C') {
            calibrationRequested = true;
        } else if (command == 'r' || command == 'R') {
            rawTouchLogging = !rawTouchLogging;
            Serial.printf("Raw touch logging: %s\n", rawTouchLogging ? "on" : "off");
        }
    }
}

static void serviceRawTouchLog() {
    if (!rawTouchLogging || calibrationRequested) {
        return;
    }
    static uint32_t lastRawRead = 0;
    if (millis() - lastRawRead < 80) {
        return;
    }
    lastRawRead = millis();
    lgfx::touch_point_t point;
    if (display.getTouchRaw(&point, 1) > 0) {
        Serial.printf("raw x=%d y=%d pressure=%u\n", point.x, point.y, point.size);
    }
}

void setup() {
    Serial.begin(115200);
    delay(250);
    Serial.println(F("Starting CYD 3.5-inch display/touch preflight"));

    display.init();
    display.setColorDepth(16);
    display.setRotation(CYD_ROTATION);
    display.setBrightness(255);

    uint16_t calibration[8] = {};
    if (readCalibration(calibration)) {
        display.setTouchCalibrate(calibration);
        calibrationLoaded = true;
        Serial.println(F("Loaded touch calibration from NVS"));
    } else {
        Serial.println(F("No saved touch calibration. Send 'c' over Serial to start calibration."));
    }

    printHardwareReport();
    initializeLvgl();
    Serial.println(F("LVGL diagnostic screen ready. Touch the colored targets and calibration button."));
}

void loop() {
    handleSerialCommands();
    serviceRawTouchLog();

    const uint32_t now = millis();
    if (lastLvglTick == 0) {
        lastLvglTick = now;
    } else {
        lv_tick_inc(now - lastLvglTick);
        lastLvglTick = now;
    }
    lv_timer_handler();

    if (calibrationRequested) {
        calibrationRequested = false;
        runTouchCalibration();
    }
    delay(5);
}
