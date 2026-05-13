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
echo [MAIN] All services stopped.
endlocal
exit /b 0
