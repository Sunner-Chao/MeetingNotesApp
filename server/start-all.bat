@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SERVER_ROOT=%~dp0"
set "STT_PORT=8888"
set "BACKEND_PORT=8090"
set "STT_SERVICE_URL=http://127.0.0.1:%STT_PORT%"
if "%STT_ENGINE%"=="" set "STT_ENGINE=faster-whisper"

echo ==========================================
echo   MeetingNotesApp Services Launcher
echo ==========================================
echo.

echo [MAIN] Starting STT Service (%STT_ENGINE%) on port %STT_PORT%...
set "STT_MODEL_ROOT=%SERVER_ROOT%models"
call "%SERVER_ROOT%stt-service\start.bat" "%STT_ENGINE%" "%STT_MODEL%"
if errorlevel 1 (
    echo [MAIN] STT service failed to start!
    pause
    exit /b 1
)

echo.
echo [MAIN] Waiting for STT to initialize...
timeout /t 5 /nobreak >nul

echo.
echo [MAIN] Starting Backend Service...
set "STT_SERVICE_URL=http://127.0.0.1:%STT_PORT%"
call "%SERVER_ROOT%backend-service\start.bat"
if errorlevel 1 (
    echo [MAIN] Backend service failed to start!
    pause
    exit /b 1
)

echo.
echo ==========================================
echo   All Local Services Started!
echo ==========================================
echo   STT Service:  http://localhost:%STT_PORT%
echo   Backend:      http://localhost:%BACKEND_PORT%
echo   Debug UI:     http://localhost:%BACKEND_PORT%/web
echo ==========================================
echo.
echo Services are running in background.
echo Press any key to exit this window (services will keep running)...
pause >nul
endlocal
exit /b 0
