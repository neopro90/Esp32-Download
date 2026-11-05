ESP32-C5 firmware source package

This standalone project includes its own platformio.ini, board-specific build flags, partition table, and complete src directory.
Build from this directory with PlatformIO:
  pio run -e esp32_c5_4mb

The generated firmware.bin is an application image intended for flash offset 0x10000.
The source files and partition table are copied from the build that produced the adjacent firmware image.
Set the upload port for your board when flashing from this source package.
