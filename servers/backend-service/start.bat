@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"
set "PROJECT_ROOT=%~dp0..\.."
set "BACKEND_PORT=8090"
set "STT_SERVICE_URL=http://127.0.0.1:8888"

if "%WEB_BACKEND_PORT%"=="" set "WEB_BACKEND_PORT=%BACKEND_PORT%"
if "%STT_SERVICE_URL%"=="" set "STT_SERVICE_URL=http://127.0.0.1:8888"

echo [BACKEND] MeetingNotesApp Backend Service
echo [BACKEND] Port: %WEB_BACKEND_PORT%
echo [BACKEND] STT Service: %STT_SERVICE_URL%
echo [BACKEND] Manual startup window will close after this script finishes.

if not exist "logs" mkdir "logs"
if not exist "runtime" (
    where python >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] python not found
        exit /b 1
    )
    echo [BACKEND] Creating Python virtual environment...
    python -m venv runtime
)

call runtime\Scripts\activate.bat
set "HOME=%USERPROFILE%"
set "USERPROFILE=%USERPROFILE%"
echo [BACKEND] Python runtime ready. Skipping dependency install on restart.

echo [BACKEND] Stopping any existing backend process on port %WEB_BACKEND_PORT%...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%WEB_BACKEND_PORT% ^| findstr LISTENING') do (
    echo [BACKEND] Closing previous backend process PID %%a
    taskkill /PID %%a /F >nul 2>&1
)

set "LOG_SUFFIX=%RANDOM%%RANDOM%"
set "BACKEND_LOG=%~dp0logs\backend_%LOG_SUFFIX%.log"
set "BACKEND_ERR_LOG=%~dp0logs\backend_%LOG_SUFFIX%.err.log"
set "WEB_BACKEND_DB_PATH=%PROJECT_ROOT%\servers\shared\data\meeting_notes.db"
echo [BACKEND] Starting backend server in background on port %WEB_BACKEND_PORT%...
echo [BACKEND] Output log: %BACKEND_LOG%
echo [BACKEND] Error log: %BACKEND_ERR_LOG%
powershell -NoProfile -ExecutionPolicy Bypass -Command "$env:HOME='%HOME%'; $env:USERPROFILE='%USERPROFILE%'; $env:STT_SERVICE_BASE_URL='%STT_SERVICE_URL%'; $env:WEB_BACKEND_PORT='%WEB_BACKEND_PORT%'; $env:WEB_BACKEND_DB_PATH='%WEB_BACKEND_DB_PATH%'; Start-Process -FilePath '%~dp0runtime\Scripts\python.exe' -ArgumentList @('-u','web_backend.py') -WorkingDirectory '%~dp0' -WindowStyle Hidden -RedirectStandardOutput '%BACKEND_LOG%' -RedirectStandardError '%BACKEND_ERR_LOG%'"

timeout /t 3 /nobreak >nul
powershell -NoProfile -Command "try { $h=Invoke-RestMethod -Uri http://127.0.0.1:%WEB_BACKEND_PORT%/health -TimeoutSec 5; Write-Host '[BACKEND] Health OK' } catch { Write-Host '[BACKEND] Not ready yet, check the log files printed above.' }"

echo [BACKEND] Done. Backend Service: http://localhost:%WEB_BACKEND_PORT%
echo [BACKEND] Logs: %~dp0logs\
endlocal
exit /b 0
