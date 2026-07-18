@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"
set "BACKEND_PORT=8090"

echo [BACKEND] Stopping backend service on port %BACKEND_PORT%...

for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%BACKEND_PORT% ^| findstr LISTENING') do (
    echo [BACKEND] Killing process %%a
    taskkill /PID %%a /F >nul 2>&1
)

for /f "tokens=2" %%a in ('tasklist /FI "WINDOWTITLE eq Backend*" ^| findstr python.exe') do (
    echo [BACKEND] Killing %%a
    taskkill /PID %%a /F >nul 2>&1
)

echo [BACKEND] Stopped.
endlocal
exit /b 0
