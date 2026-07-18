@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"
set "STT_PORT=8888"

echo [STT] Stopping STT service on port %STT_PORT%...

for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%STT_PORT% ^| findstr LISTENING') do (
    echo [STT] Killing process %%a
    taskkill /PID %%a /F >nul 2>&1
)

echo [STT] Cleaning stale stt_server.py processes from this service directory...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process -Filter \"Name = 'python.exe'\" | Where-Object { $_.CommandLine -like '*stt_server.py*' } | ForEach-Object { Write-Host ('[STT] Killing stale process ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force }"

for /f "tokens=2" %%a in ('tasklist /FI "WINDOWTITLE eq STT*" ^| findstr python.exe') do (
    echo [STT] Killing %%a
    taskkill /PID %%a /F >nul 2>&1
)

echo [STT] Stopped.
endlocal
exit /b 0
