#!/usr/bin/env python3
"""Read ESP32 ROM bootloader Flash JEDEC ID and capacity without writing Flash."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


def find_esptool() -> Path:
    candidate = Path.home() / ".platformio" / "penv" / "Scripts" / "esptool.exe"
    if candidate.is_file():
        return candidate
    raise FileNotFoundError(
        f"PlatformIO esptool executable not found: {candidate}. Install PlatformIO Core first."
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default="COM6", help="ESP32 serial port (default: COM6)")
    parser.add_argument("--baud", type=int, default=115200, help="UART speed (default: 115200)")
    args = parser.parse_args()

    command = [
        str(find_esptool()),
        "--chip", "esp32",
        "--port", args.port,
        "--baud", str(args.baud),
        "--before", "default-reset",
        "--after", "no-reset",
        "--no-stub",
        "flash-id",
    ]
    print("Read-only ESP32 Flash probe; no erase/write/eFuse commands will be run.")
    print(f"Port: {args.port} @ {args.baud} baud")

    try:
        result = subprocess.run(command, check=False, capture_output=True, text=True, errors="replace")
    except FileNotFoundError as error:
        print(error, file=sys.stderr)
        return 2

    output = result.stdout + result.stderr
    print(output, end="" if output.endswith("\n") else "\n")
    if result.returncode != 0:
        print("Could not query the ESP32 ROM bootloader. Check COM port and BOOT/RESET state.", file=sys.stderr)
        return result.returncode or 2

    manufacturer = re.search(r"Manufacturer:\s*(\S+)", output, re.IGNORECASE)
    device = re.search(r"Device:\s*(\S+)", output, re.IGNORECASE)
    size = re.search(r"Detected flash size:\s*(.+)", output, re.IGNORECASE)
    voltage = re.search(r"Flash voltage set by a strapping pin:\s*(.+)", output, re.IGNORECASE)

    manufacturer_text = manufacturer.group(1) if manufacturer else "unknown"
    device_text = device.group(1) if device else "unknown"
    size_text = size.group(1).strip() if size else "unknown"
    voltage_text = voltage.group(1).strip() if voltage else "not reported"
    print(f"\nSummary: manufacturer={manufacturer_text}, device={device_text}, size={size_text}, VDD_SDIO={voltage_text}")

    if manufacturer_text.lower() in {"ff", "0xff"} or device_text.lower() in {"ffff", "0xffff"} or size_text.lower() == "unknown":
        print("FLASH STATUS: FAIL / unreadable. Check module variant, GPIO12 strap, and Flash power/wiring.")
        return 2

    print("FLASH STATUS: JEDEC ID detected.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
