@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title Chromecast Hub

where python >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Python is not installed or not in PATH. Install Python 3.10+ from python.org and tick "Add to PATH".
  pause
  exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
  echo [1/3] Creating virtual environment...
  python -m venv .venv
)

echo [2/3] Installing requirements...
".venv\Scripts\python.exe" -m pip install -q -r requirements.txt
if errorlevel 1 (
  echo [ERROR] Failed to install requirements.
  pause
  exit /b 1
)

if not exist "platform-tools\adb.exe" (
  echo [3/3] Downloading Android platform-tools - ADB...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile 'pt.zip'; Expand-Archive -Force 'pt.zip' '.'; Remove-Item 'pt.zip'"
  if not exist "platform-tools\adb.exe" (
    echo [ERROR] Could not download ADB. Download platform-tools manually and extract it next to this file.
    pause
    exit /b 1
  )
)

echo Freeing port 8765 if an old copy is still running...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { $p = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue; if ($p -and $p.Name -like 'python*') { Stop-Process -Id $p.Id -Force } }"

echo.
echo Chromecast Hub running at http://127.0.0.1:8765
start "" "http://127.0.0.1:8765"
".venv\Scripts\python.exe" -m uvicorn app:app --host 127.0.0.1 --port 8765
pause
