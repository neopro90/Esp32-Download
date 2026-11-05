@echo off
chcp 65001 >nul
title ESP32 Download - All-in-One Installer
cls
echo ==============================================================
echo        ESP32 DOWNLOADER - TRINH CAI DAT TU DONG VA NAP FW
echo ==============================================================
echo.
echo Dang khoi dong PowerShell voi quyen thuc thi...
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\install.ps1"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [!] Trinh cai dat da thoat voi ma loi %ERRORLEVEL%.
)
echo.
echo Nhan phim bat ky de ket thuc...
pause >nul
