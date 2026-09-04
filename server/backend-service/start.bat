@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"
set "SERVER_ROOT=%~dp0.."
set "BACKEND_PORT=8090"
set "STT_SERVICE_URL=http://127.0.0.1:8888"

rem Keep Backend and the local STT process on the same private auth snapshot.
if not defined BACKEND_AUTH_ENV_FILE if exist "%SERVER_ROOT%\.env.remote" set "BACKEND_AUTH_ENV_FILE=%SERVER_ROOT%\.env.remote"
if defined BACKEND_AUTH_ENV_FILE if exist "%BACKEND_AUTH_ENV_FILE%" call "%SERVER_ROOT%\load-env.bat" "%BACKEND_AUTH_ENV_FILE%"

rem The shared snapshot is also used by Ubuntu. Restore Windows-owned paths
rem so local startup never writes logs, databases, or OTA files to Linux paths.
set "APP_UPDATE_CONFIG_PATH=%SERVER_ROOT%\config\app-update.json"
set "APP_UPDATE_ANDROID_APK_PATH=%SERVER_ROOT%\downloads\ZhiWuBen-Android.apk"
set "APP_UPDATE_LIGHT_CONFIG_PATH=%SERVER_ROOT%\config\app-update-light.json"
set "APP_UPDATE_LIGHT_ANDROID_APK_PATH=%SERVER_ROOT%\downloads-light\ZhiWuBen-Android.apk"
set "ACCOUNT_MEDIA_DIR=%SERVER_ROOT%\shared\data\account-media"
set "TENCENT_ASR_USAGE_LEDGER_PATH=%SERVER_ROOT%\stt-service\data\tencent-asr-usage.db"
set "STT_LOG_PATH=%SERVER_ROOT%\stt-service\logs\stt.log"

if "%WEB_BACKEND_PORT%"=="" set "WEB_BACKEND_PORT=%BACKEND_PORT%"
if "%STT_SERVICE_URL%"=="" set "STT_SERVICE_URL=http://127.0.0.1:8888"

echo [BACKEND] MeetingNotesApp Backend Service
echo [BACKEND] Port: %WEB_BACKEND_PORT%
echo [BACKEND] STT Service: %STT_SERVICE_URL%

if not exist "logs" mkdir "logs"

set "PYTHON_EXE=%~dp0runtime\Scripts\python.exe"
if not exist "%PYTHON_EXE%" (
    echo [ERROR] Fixed Backend runtime is missing: %PYTHON_EXE%
    echo [ERROR] Run server\init-server.bat first.
    exit /b 1
)

echo [BACKEND] Stopping any existing backend process on port %WEB_BACKEND_PORT%...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%WEB_BACKEND_PORT% ^| findstr LISTENING') do (
    echo [BACKEND] Closing previous backend process PID %%a
    taskkill /PID %%a /F >nul 2>&1
)

powershell -Command "Start-Sleep -Seconds 1"

set "LOG_SUFFIX=%RANDOM%%RANDOM%"
set "BACKEND_LOG=%~dp0logs\backend_%LOG_SUFFIX%.log"
set "BACKEND_ERR_LOG=%~dp0logs\backend_%LOG_SUFFIX%.err.log"
if not exist "%SERVER_ROOT%\shared\data" mkdir "%SERVER_ROOT%\shared\data"
set "WEB_BACKEND_DB_PATH=%SERVER_ROOT%\shared\data\meeting_notes.db"
set "ACCOUNT_DB_PATH=%SERVER_ROOT%\shared\data\accounts.db"
echo [BACKEND] Starting backend server in background on port %WEB_BACKEND_PORT%...
echo [BACKEND] Output log: %BACKEND_LOG%
echo [BACKEND] Error log: %BACKEND_ERR_LOG%
powershell -NoProfile -ExecutionPolicy Bypass -Command "$env:HOME='%USERPROFILE%'; $env:USERPROFILE='%USERPROFILE%'; $env:STT_SERVICE_BASE_URL='%STT_SERVICE_URL%'; $env:WEB_BACKEND_PORT='%WEB_BACKEND_PORT%'; $env:WEB_BACKEND_DB_PATH='%WEB_BACKEND_DB_PATH%'; $env:ACCOUNT_DB_PATH='%ACCOUNT_DB_PATH%'; Start-Process -FilePath '%PYTHON_EXE%' -ArgumentList @('-u','web_backend.py') -WorkingDirectory '%~dp0' -WindowStyle Hidden -RedirectStandardOutput '%BACKEND_LOG%' -RedirectStandardError '%BACKEND_ERR_LOG%'"

echo [BACKEND] Waiting for server to start...
powershell -Command "Start-Sleep -Seconds 3"

echo [BACKEND] Checking health...
powershell -NoProfile -Command "try { $h=Invoke-RestMethod -Uri http://127.0.0.1:%WEB_BACKEND_PORT%/health -TimeoutSec 5; Write-Host '[BACKEND] Health OK' } catch { Write-Host '[BACKEND] Not ready yet, check the log files printed above.' }"

echo [BACKEND] Done. Backend Service: http://localhost:%WEB_BACKEND_PORT%
echo [BACKEND] Logs: %~dp0logs\
endlocal
exit /b 0
