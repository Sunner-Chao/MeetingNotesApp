@echo off
setlocal EnableExtensions EnableDelayedExpansion

echo ==========================================
echo   MeetingNotesApp Services Stopper
echo ==========================================
echo.

echo [MAIN] Stopping all services...
call "%~dp0stt-service\stop.bat"
call "%~dp0backend-service\stop.bat"

echo.
echo [MAIN] Stopping tunnel and keepalive processes...
taskkill /f /im bore.exe 2>nul
taskkill /f /im curl.exe 2>nul
taskkill /f /im node.exe 2>nul
taskkill /f /im ngrok.exe 2>nul

echo.
echo [MAIN] All services and tunnels stopped.
del /q "%~dp0tunnel_stt.txt" 2>nul
del /q "%~dp0tunnel_backend.txt" 2>nul
del /q "%~dp0tunnel_stt_url.txt" 2>nul
del /q "%~dp0tunnel_backend_url.txt" 2>nul
endlocal
echo.
echo Press any key to exit...
pause >nul
