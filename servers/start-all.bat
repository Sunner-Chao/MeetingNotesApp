@echo off
setlocal EnableExtensions EnableDelayedExpansion

pushd "%~dp0.."
set "PROJECT_ROOT=%CD%"
popd
set "STT_PORT=8888"
set "BACKEND_PORT=8090"
set "STT_SERVICE_URL=http://127.0.0.1:%STT_PORT%"
if "%STT_ENGINE%"=="" set "STT_ENGINE=faster-whisper"

echo ==========================================
echo   MeetingNotesApp Services Launcher
echo ==========================================
echo.

echo [MAIN] Starting STT Service (%STT_ENGINE%) on port %STT_PORT%...
call "%PROJECT_ROOT%\servers\stt-service\start.bat" "%STT_ENGINE%" "%STT_MODEL%"
if errorlevel 1 (
    echo [MAIN] STT service failed to start!
    pause
    exit /b 1
)

echo.
echo [MAIN] Waiting for STT to initialize...
timeout /t 3 /nobreak >nul

echo.
echo [MAIN] Starting Backend Service...
set "STT_SERVICE_URL=http://127.0.0.1:%STT_PORT%"
call "%PROJECT_ROOT%\servers\backend-service\start.bat"
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
echo [MAIN] Cleaning up old tunnels...
taskkill /f /im bore.exe 2>nul
del /q "%PROJECT_ROOT%\servers\tunnel_stt.txt" 2>nul
del /q "%PROJECT_ROOT%\servers\tunnel_backend.txt" 2>nul
del /q "%PROJECT_ROOT%\servers\tunnel_stt_url.txt" 2>nul
del /q "%PROJECT_ROOT%\servers\tunnel_backend_url.txt" 2>nul
timeout /t 1 /nobreak >nul

echo [MAIN] Starting bore tunnels (auto-reconnect on disconnect)...
set "STT_LOG=%PROJECT_ROOT%\servers\tunnel_stt.txt"
set "STT_URL=%PROJECT_ROOT%\servers\tunnel_stt_url.txt"
set "BACKEND_LOG=%PROJECT_ROOT%\servers\tunnel_backend.txt"
set "BACKEND_URL=%PROJECT_ROOT%\servers\tunnel_backend_url.txt"

set "NO_COLOR=1"
powershell.exe -Command "Start-Process cmd.exe -ArgumentList '/c', '%PROJECT_ROOT%\servers\tunnel_helper.bat', '%STT_PORT%', '%STT_LOG%', '%STT_URL%' -WindowStyle Minimized"
powershell.exe -Command "Start-Process cmd.exe -ArgumentList '/c', '%PROJECT_ROOT%\servers\tunnel_helper.bat', '%BACKEND_PORT%', '%BACKEND_LOG%', '%BACKEND_URL%' -WindowStyle Minimized"

echo [MAIN] Waiting for tunnels (up to 40s)...
set "TUNNEL_STT=(failed)"
set "TUNNEL_BACKEND=(failed)"
for /L %%i in (1,1,40) do (
    timeout /t 1 /nobreak >nul
    if "!TUNNEL_STT!"=="(failed)" (
        if exist "%STT_LOG%" (
            for /f "tokens=*" %%U in ('findstr /r "listening at bore.pub:" "%STT_LOG%" 2^>nul') do (
                set "LINE=%%U"
                set "LINE=!LINE:*listening at =!"
                set "TUNNEL_STT=http://!LINE!"
                echo !TUNNEL_STT!> "%STT_URL%"
            )
        )
    )
    if "!TUNNEL_BACKEND!"=="(failed)" (
        if exist "%BACKEND_LOG%" (
            for /f "tokens=*" %%U in ('findstr /r "listening at bore.pub:" "%BACKEND_LOG%" 2^>nul') do (
                set "LINE=%%U"
                set "LINE=!LINE:*listening at =!"
                set "TUNNEL_BACKEND=http://!LINE!"
                echo !TUNNEL_BACKEND!> "%BACKEND_URL%"
            )
        )
    )
    if not "!TUNNEL_STT!"=="(failed)" if not "!TUNNEL_BACKEND!"=="(failed)" goto tunnels_ready
)
:tunnels_ready

echo.
echo [MAIN] Starting keepalive (pings tunnels every 120s to prevent idle timeout)...
powershell.exe -Command "Start-Process cmd.exe -ArgumentList '/c', '%PROJECT_ROOT%\servers\keepalive.bat', '120' -WindowStyle Minimized"

echo.
echo ==========================================
echo   All Services and Tunnels Started!
echo ==========================================
echo   Local:
echo     STT:      http://localhost:%STT_PORT%
echo     Backend:  http://localhost:%BACKEND_PORT%
echo     Web UI:   http://localhost:%BACKEND_PORT%/web
echo   Public (bore - auto-reconnects on drop):
echo     STT:      !TUNNEL_STT!
echo     Backend:  !TUNNEL_BACKEND!
echo     Web UI:   !TUNNEL_BACKEND!/web
echo   URL files (keepalive uses these):
echo     STT:      %STT_URL%
echo     Backend:  %BACKEND_URL%
echo ==========================================
echo.
echo NOTE: Tunnels auto-reconnect if bore drops (URL files auto-update).
echo       Keepalive pings every 120s to prevent idle timeout.
echo.
echo Press any key to exit this window (services, tunnels, and keepalive will keep running)...
pause >nul
endlocal
exit /b 0
