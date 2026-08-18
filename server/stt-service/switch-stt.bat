@echo off
setlocal EnableExtensions

set "STT_ENGINE=%~1"
set "STT_MODEL=%~2"
set "SERVER_ROOT=%~dp0.."
if "%STT_MODEL_ROOT%"=="" set "STT_MODEL_ROOT=%SERVER_ROOT%\models"
set "STT_PORT=8888"

if "%STT_ENGINE%"=="" set "STT_ENGINE=faster-whisper"
if "%STT_MODEL%"=="" set "STT_MODEL=large-v3-turbo"

echo [STT] Switch request received.
echo [STT] Target engine: %STT_ENGINE%
echo [STT] Target model: %STT_MODEL%
echo [STT] Port: %STT_PORT%
echo [STT] Waiting briefly for the HTTP response to return...
powershell -NoProfile -Command "Start-Sleep -Seconds 1"

echo [STT] Stopping previous STT process on port %STT_PORT%...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%STT_PORT% ^| findstr LISTENING') do (
    echo [STT] Closing previous STT process PID %%a
    taskkill /PID %%a /F >nul 2>&1
)

cd /d "%~dp0"
echo [STT] Cleaning stale stt_server.py processes from this service directory...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process -Filter \"Name = 'python.exe'\" | Where-Object { $_.CommandLine -like '*stt_server.py*' } | ForEach-Object { Write-Host ('[STT] Killing stale process ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force }"

if not exist "logs" mkdir "logs"
if not exist "runtime" (
    echo [STT] Creating Python virtual environment...
    python -m venv runtime
)

set "LOG_SUFFIX=%RANDOM%%RANDOM%"
set "STT_LOG=%~dp0logs\stt_switch_%LOG_SUFFIX%.log"
set "STT_ERR_LOG=%~dp0logs\stt_switch_%LOG_SUFFIX%.err.log"
echo [STT] Starting target STT service in background...
echo [STT] Output log: %STT_LOG%
echo [STT] Error log: %STT_ERR_LOG%
powershell -NoProfile -ExecutionPolicy Bypass -Command "$env:STT_MODEL_ROOT='%STT_MODEL_ROOT%'; $env:STT_ENGINE='%STT_ENGINE%'; $env:STT_MODEL='%STT_MODEL%'; $env:HOME='%USERPROFILE%'; $env:USERPROFILE='%USERPROFILE%'; Start-Process -FilePath '%~dp0runtime\Scripts\python.exe' -ArgumentList @('-u','stt_server.py','--host','0.0.0.0','--port','%STT_PORT%','--engine','%STT_ENGINE%','--model','%STT_MODEL%') -WorkingDirectory '%~dp0' -WindowStyle Hidden -RedirectStandardOutput '%~dp0logs\stt_switch_%LOG_SUFFIX%.log' -RedirectStandardError '%~dp0logs\stt_switch_%LOG_SUFFIX%.err.log'"

echo [STT] Switch command finished. Health: http://127.0.0.1:%STT_PORT%/health
endlocal
