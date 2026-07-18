@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"
set "SERVER_ROOT=%~dp0.."
set "STT_PORT=8888"
if "%STT_MODEL_ROOT%"=="" set "STT_MODEL_ROOT=%SERVER_ROOT%\models"

if not "%~1"=="" set "STT_ENGINE=%~1"
if not "%~2"=="" set "STT_MODEL=%~2"
if "%STT_ENGINE%"=="" set "STT_ENGINE=faster-whisper"
if "%STT_MODEL%"=="" set "STT_MODEL=small"

if not exist "%STT_MODEL_ROOT%" mkdir "%STT_MODEL_ROOT%"

echo [STT] STT Service Starter
echo [STT] Port: %STT_PORT%
echo [STT] Model Root: %STT_MODEL_ROOT%
echo [STT] Engine: %STT_ENGINE%
echo [STT] Model: %STT_MODEL%
echo.

if not exist "logs" mkdir "logs"

set "PYTHON_EXE=%~dp0runtime\Scripts\python.exe"
if not exist "%PYTHON_EXE%" (
    echo [ERROR] Fixed STT runtime is missing: %PYTHON_EXE%
    echo [ERROR] Run server\init-server.bat first.
    exit /b 1
)

REM Stop existing processes
echo [STT] Stopping any existing STT process on port %STT_PORT%...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%STT_PORT% ^| findstr LISTENING') do (
    echo [STT] Closing previous process PID %%a
    taskkill /PID %%a /F >nul 2>&1
)

powershell -Command "Start-Sleep -Seconds 1"

set "LOG_SUFFIX=%RANDOM%%RANDOM%"
set "STT_OUT_LOG=%~dp0logs\stt_%LOG_SUFFIX%.log"
set "STT_ERR_LOG=%~dp0logs\stt_%LOG_SUFFIX%.err.log"

echo [STT] Starting STT server in background...
echo [STT] Output log: %STT_OUT_LOG%
echo [STT] Error log: %STT_ERR_LOG%
echo [STT] Model will be saved to: %STT_MODEL_ROOT%

powershell -NoProfile -ExecutionPolicy Bypass -Command "$env:STT_MODEL_ROOT='%STT_MODEL_ROOT%'; $env:STT_ENGINE='%STT_ENGINE%'; Start-Process -FilePath '%PYTHON_EXE%' -ArgumentList @('-u','stt_server.py','--engine','%STT_ENGINE%','--model','%STT_MODEL%','--port','%STT_PORT%') -WorkingDirectory '%~dp0' -WindowStyle Hidden -RedirectStandardOutput '%STT_OUT_LOG%' -RedirectStandardError '%STT_ERR_LOG%'"

echo [STT] Waiting for server to start...
powershell -Command "Start-Sleep -Seconds 5"

echo [STT] Checking health...
powershell -NoProfile -Command "try { $h=Invoke-RestMethod -Uri http://127.0.0.1:%STT_PORT%/health -TimeoutSec 10; Write-Host '[STT] Health OK - Engine:' $h.engine 'Model:' $h.model } catch { Write-Host '[STT] Not ready yet, check logs above.' }"

echo.
echo [STT] Done. STT Service: http://localhost:%STT_PORT%
echo [STT] Logs: %~dp0logs\
echo [STT] Models: %STT_MODEL_ROOT%\
endlocal
exit /b 0
