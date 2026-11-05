#pragma once

// Defaults are for the common ESP32-3248S035R board. Check the actual PCB and
// the WROVER modification before changing these values or using another board.
#define CYD_WIDTH 320
#define CYD_HEIGHT 480

// Select 1 for ST7796 (standard 3248S035R), 2 for ILI9488-compatible panels.
#define CYD_TFT_DRIVER 1
#define CYD_DRIVER_ST7796 1
#define CYD_DRIVER_ILI9488 2

#define CYD_SPI_HOST SPI3_HOST
#define CYD_SPI_SCLK 14
#define CYD_SPI_MISO 12
#define CYD_SPI_MOSI 13
#define CYD_TFT_CS 15
#define CYD_TFT_DC 2
#define CYD_TFT_RST -1
#define CYD_TFT_BL 27

#define CYD_TOUCH_CS 33
#define CYD_TOUCH_IRQ 36
#define CYD_TOUCH_SPI_HZ 1000000

#define CYD_TFT_SPI_WRITE_HZ 20000000
#define CYD_TFT_SPI_READ_HZ 10000000
#define CYD_ROTATION 0
