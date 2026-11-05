#include <Arduino.h>

#include <esp_err.h>
#include <esp_flash.h>
#include <esp_heap_caps.h>
#include <esp_psram.h>
#include <soc/gpio_reg.h>
#include <soc/soc.h>

static constexpr uint32_t kGpio12StrapBit = 5;
static constexpr size_t kPsramProbeBytes = 64 * 1024;

static void printResult(const char *name, esp_err_t result) {
    Serial.printf("%s: %s (%d)\n", name, esp_err_to_name(result), static_cast<int>(result));
}

static void checkFlash() {
    uint32_t jedecId = 0xFFFFFFFF;
    uint32_t imageSize = 0;
    uint32_t physicalSize = 0;

    const esp_err_t idResult = esp_flash_read_id(esp_flash_default_chip, &jedecId);
    printResult("Flash JEDEC read", idResult);
    if (idResult == ESP_OK) {
        Serial.printf("Flash JEDEC ID: 0x%06lX (manufacturer 0x%02lX, device 0x%04lX)\n",
                      static_cast<unsigned long>(jedecId & 0xFFFFFF),
                      static_cast<unsigned long>((jedecId >> 16) & 0xFF),
                      static_cast<unsigned long>(jedecId & 0xFFFF));
        if ((jedecId & 0xFFFFFF) == 0xFFFFFF || (jedecId & 0xFFFFFF) == 0) {
            Serial.println("FLASH RESULT: invalid JEDEC ID; check power, flash wiring, and VDD_SDIO strap.");
        }
    }

    const esp_err_t imageResult = esp_flash_get_size(esp_flash_default_chip, &imageSize);
    printResult("Flash image/header size", imageResult);
    if (imageResult == ESP_OK) {
        Serial.printf("Flash size from detected ID: %lu bytes (%.2f MiB)\n",
                      static_cast<unsigned long>(imageSize), imageSize / 1048576.0);
    }

    const esp_err_t physicalResult = esp_flash_get_physical_size(esp_flash_default_chip, &physicalSize);
    printResult("Flash physical size", physicalResult);
    if (physicalResult == ESP_OK) {
        Serial.printf("Physical Flash: %lu bytes (%.2f MiB)\n",
                      static_cast<unsigned long>(physicalSize), physicalSize / 1048576.0);
    }

    Serial.printf("Arduino image-configured Flash: %lu bytes (%.2f MiB)\n",
                  static_cast<unsigned long>(ESP.getFlashChipSize()), ESP.getFlashChipSize() / 1048576.0);

    const uint32_t strap = REG_READ(GPIO_STRAP_REG);
    const bool gpio12HighAtReset = ((strap >> kGpio12StrapBit) & 1U) != 0;
    Serial.printf("Latched GPIO12/MTDI strap: %s; VDD_SDIO selected as %s\n",
                  gpio12HighAtReset ? "HIGH" : "LOW",
                  gpio12HighAtReset ? "1.8 V" : "3.3 V");

    if (idResult != ESP_OK || (jedecId & 0xFFFFFF) == 0xFFFFFF || physicalSize == 0) {
        Serial.println("FLASH STATUS: FAIL / unreadable. This firmware does not erase or write Flash.");
    } else {
        Serial.println("FLASH STATUS: JEDEC ID and physical capacity are readable.");
    }
}

static void checkPsram() {
    const bool initialized = esp_psram_is_initialized();
    const size_t detectedSize = initialized ? esp_psram_get_size() : 0;
    const size_t totalSpiramHeap = heap_caps_get_total_size(MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    const size_t freeSpiramHeap = heap_caps_get_free_size(MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    const size_t largestBlock = heap_caps_get_largest_free_block(MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);

    Serial.println();
    Serial.println("--- PSRAM ---");
    Serial.printf("PSRAM initialized: %s\n", initialized ? "yes" : "no");
    Serial.printf("PSRAM size reported by driver: %lu bytes (%.2f MiB)\n",
                  static_cast<unsigned long>(detectedSize), detectedSize / 1048576.0);
    Serial.printf("PSRAM heap visible to firmware: %lu bytes (%.2f MiB)\n",
                  static_cast<unsigned long>(totalSpiramHeap), totalSpiramHeap / 1048576.0);
    Serial.printf("PSRAM heap free: %lu bytes; largest free block: %lu bytes\n",
                  static_cast<unsigned long>(freeSpiramHeap), static_cast<unsigned long>(largestBlock));

    const size_t probeSize = freeSpiramHeap < kPsramProbeBytes ? freeSpiramHeap / 2 : kPsramProbeBytes;
    if (probeSize < 1024) {
        Serial.println("PSRAM read/write probe: skipped (not enough mapped PSRAM heap).");
        return;
    }

    uint8_t *probe = static_cast<uint8_t *>(heap_caps_malloc(probeSize, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
    if (probe == nullptr) {
        Serial.println("PSRAM read/write probe: allocation failed.");
        return;
    }

    for (size_t i = 0; i < probeSize; ++i) {
        probe[i] = static_cast<uint8_t>((i * 73U + 0x5AU) & 0xFFU);
    }
    bool passed = true;
    for (size_t i = 0; i < probeSize; ++i) {
        if (probe[i] != static_cast<uint8_t>((i * 73U + 0x5AU) & 0xFFU)) {
            passed = false;
            break;
        }
    }
    heap_caps_free(probe);
    Serial.printf("PSRAM read/write probe: %s (%lu bytes tested)\n",
                  passed ? "PASS" : "FAIL", static_cast<unsigned long>(probeSize));
}

static void printReport() {
    Serial.println();
    Serial.println("========================================");
    Serial.println("CYD-3248S035R / ESP32-WROVER-N16R8 CHECK");
    Serial.printf("Chip: %s rev %u, %u core(s)\n", ESP.getChipModel(), ESP.getChipRevision(), ESP.getChipCores());
    Serial.printf("Reset reason CPU0: %d\n", static_cast<int>(esp_reset_reason()));
    Serial.printf("Free internal heap: %lu bytes\n", static_cast<unsigned long>(heap_caps_get_free_size(MALLOC_CAP_INTERNAL)));
    Serial.printf("Configured board Flash: 16 MiB; configured PSRAM interface: QSPI\n");
    checkFlash();
    checkPsram();
    Serial.println("========================================");
}

void setup() {
    Serial.begin(115200);
    delay(500);
    printReport();
    Serial.println("Type 'r' to print the report again.");
}

void loop() {
    if (Serial.available() > 0) {
        const char command = static_cast<char>(Serial.read());
        if (command == 'r' || command == 'R') {
            printReport();
        }
    }
    delay(10);
}
