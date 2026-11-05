@echo off
chcp 65001 >nul
title Day code len GitHub - Esp32-Download
cd /d "%~dp0"
echo ==============================================================
echo           DANG DAY CODE LEN GITHUB: Esp32-Download
echo ==============================================================
echo.
echo Neu trinh duyet Coc Coc mo ra, vui long bam "Authorize" de xac thuc.
echo.
git push -u origin main
echo.
if %ERRORLEVEL% EQU 0 (
    echo ==============================================================
    echo [THANH CONG] Da upload toan bo ma nguon len GitHub thanh cong!
    echo ==============================================================
) else (
    echo ==============================================================
    echo [!] Gap loi trong qua trinh push len GitHub.
    echo ==============================================================
)
echo.
pause
