# Flash and PSRAM preflight (CYD-3248S035R / ESP32-WROVER-N16R8)

This diagnostic is read-only for Flash. It does not erase/write Flash or alter eFuses.

## Check Flash now from the ROM bootloader

Even while the application is in a reset loop, the ROM bootloader can answer the Flash JEDEC query:

```powershell
python .\probe_flash_rom.py --port COM6
```

The script uses the PlatformIO-installed `esptool.exe`, issues only `flash-id`, and leaves the ESP32 in download mode. Expected fields are manufacturer, device, detected capacity, and VDD_SDIO selected from the latched GPIO12 strap.

## Firmware diagnostic

The PlatformIO sketch reads the runtime Flash JEDEC ID, physical capacity, image/header capacity, GPIO12 strap, PSRAM driver-reported size, heap-visible PSRAM size, and performs a 64 KiB PSRAM read/write test.

```powershell
pio run -e cyd_wrover_n16r8_flash_probe
pio run -e cyd_wrover_n16r8_flash_probe -t upload
pio device monitor -p COM6 -b 115200
```

The firmware upload can only run after the ROM bootloader can communicate with Flash. The build uses the generic ESP32/WROOM-compatible CYD profile, keeps Flash in DIO mode at 40 MHz, sets Flash capacity to 16 MiB, and enables QSPI PSRAM. It uses `cyd_16mb.csv`, adapted from the supplied firmware's 16 MiB table; the app begins at `0x10000`, and the table spans the full 16 MiB. The driver detects the physical 8 MiB PSRAM; the report shows its detected size separately from the 4 MiB directly mapped/heap-visible region.
