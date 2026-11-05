# CYD 3.5-inch display preflight

Standalone firmware for checking the TFT, LVGL, GPIO mapping, and XPT2046 touch calibration before building the main firmware.

The defaults in `include/board_config.h` match the commonly documented ESP32-3248S035R/WROOM-compatible GPIO profile: ST7796 at 320 x 480, SPI pins 14/12/13, TFT CS 15, DC 2, backlight 27, and XPT2046 CS 33 / IRQ 36. The build uses the generic `esp32dev` profile, retains DIO Flash at 40 MHz and QSPI PSRAM, overrides Flash capacity to 16 MiB, and uses `cyd_16mb.csv` adapted from the supplied firmware's 16 MiB layout. The modified board has a WROVER N16R8 module with 8 MiB physical PSRAM; the ESP32 classic direct mapping is 4 MiB. Adjust the header only if the actual PCB wiring differs.

## Build and upload to COM6

Run these commands from this directory:

```powershell
pio run -e cyd3248s035r_wrover_n16r8
pio run -e cyd3248s035r_wrover_n16r8 -t upload
pio device monitor -p COM6 -b 115200
```

On first boot, open the serial monitor and send `c`. Tap the four arrow tips shown on the display in order. The touch calibration is saved in NVS. The LVGL screen shows color swatches, geometry, resolved configuration, and live touch coordinates.

Serial monitor commands:

- `i`: print chip, flash/PSRAM, GPIO, driver, resolution, and LVGL report.
- `c`: run touch calibration again and save the results to NVS.
- `r`: toggle raw XPT2046 coordinate logging for diagnosis.

If the panel stays blank, verify the actual panel controller and pin wiring before changing `CYD_TFT_DRIVER`, SPI pins, or backlight polarity. The listed defaults are a starting profile, not confirmation of the modified board's physical wiring.
