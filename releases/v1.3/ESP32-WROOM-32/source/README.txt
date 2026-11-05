ESP32-WROOM-32 firmware source package

This standalone project includes its own platformio.ini, board-specific build flags, partition table, and complete src directory.
Build from this directory with PlatformIO:
  pio run -e cyd_wroom32_4mb

The generated firmware.bin is an application image intended for flash offset 0x10000.
The source files and partition table are copied from the build that produced the adjacent firmware image.
This WROOM-32 configuration uses COM3 for upload and serial monitoring.
