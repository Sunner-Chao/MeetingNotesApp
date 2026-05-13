@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"
set "PROJECT_ROOT=%~dp0..\.."
set "STT_MODEL_ROOT=%PROJECT_ROOT%\models"
set "STT_PORT=8888"

if not "%~1"=="" set "STT_ENGINE=%~1"
if not "%~2"=="" set "STT_MODEL=%~2"
if "%STT_ENGINE%"=="" set "STT_ENGINE=faster-whisper"
if "%STT_MODEL%"=="" (
    if /I "%STT_ENGINE%"=="sensevoice" (
        set "STT_MODEL=SenseVoiceSmall"
    ) else (
        set "STT_MODEL=small"
    )
)

echo [STT] MeetingNotesApp STT Service
echo [STT] Model Root: %STT_MODEL_ROOT%
echo [STT] Engine: %STT_ENGINE%
echo [STT] Model: %STT_MODEL%
echo [STT] Port: %STT_PORT%
echo [STT] Manual startup window will close after this script finishes.

if not exist "logs" mkdir "logs"
if not exist "runtime" (
    where python >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] python not found
        exit /b 1
    )
    echo [STT] Creating Python virtual environment...
    python -m venv runtime
)

call runtime\Scripts\activate.bat
set "HOME=%USERPROFILE%"
set "USERPROFILE=%USERPROFILE%"
echo [STT] Python runtime ready. Skipping dependency install on restart.

echo [STT] Stopping any existing STT process on port %STT_PORT%...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%STT_PORT% ^| findstr LISTENING') do (
    echo [STT] Closing previous STT process PID %%a
    taskkill /PID %%a /F >nul 2>&1
)
echo [STT] Cleaning stale stt_server.py processes from this service directory...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process -Filter \"Name = 'python.exe'\" | Where-Object { $_.CommandLine -like '*stt_server.py*' } | ForEach-Object { Write-Host ('[STT] Killing stale process ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force }"

set "LOG_SUFFIX=%RANDOM%%RANDOM%"
set "STT_LOG=%~dp0logs\stt_%LOG_SUFFIX%.log"
set "STT_ERR_LOG=%~dp0logs\stt_%LOG_SUFFIX%.err.log"
echo [STT] Starting STT server in background on port %STT_PORT%...
echo [STT] Output log: %STT_LOG%
echo [STT] Error log: %STT_ERR_LOG%
powershell -NoProfile -ExecutionPolicy Bypass -Command "$env:STT_MODEL_ROOT='%STT_MODEL_ROOT%'; $env:STT_ENGINE='%STT_ENGINE%'; $env:STT_MODEL='%STT_MODEL%'; $env:HOME='%HOME%'; $env:USERPROFILE='%USERPROFILE%'; Start-Process -FilePath '%~dp0runtime\Scripts\python.exe' -ArgumentList @('-u','stt_server.py','--host','0.0.0.0','--port','%STT_PORT%','--engine','%STT_ENGINE%','--model','%STT_MODEL%') -WorkingDirectory '%~dp0' -WindowStyle Hidden -RedirectStandardOutput '%STT_LOG%' -RedirectStandardError '%STT_ERR_LOG%'"

timeout /t 5 /nobreak >nul
powershell -NoProfile -Command "try { $h=Invoke-RestMethod -Uri http://127.0.0.1:%STT_PORT%/health -TimeoutSec 5; Write-Host '[STT] Health OK:' ($h | ConvertTo-Json -Compress) } catch { Write-Host '[STT] Not ready yet, check the log files printed above.' }"

echo [STT] Done. STT Service: http://localhost:%STT_PORT%
echo [STT] Logs: %~dp0logs\
endlocal
exit /b 0
