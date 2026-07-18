@echo off
setlocal EnableExtensions

cd /d "%~dp0"
set "SERVER_ROOT=%CD%"
set "DIST_ROOT=%SERVER_ROOT%\dist"
set "PACKAGE_NAME=MeetingNotesApp-Server-%date:~0,4%%date:~5,2%%date:~8,2%-%RANDOM%"
set "PACKAGE_DIR=%DIST_ROOT%\%PACKAGE_NAME%"

echo ==========================================
echo   MeetingNotesApp Server Package Builder
echo ==========================================
echo.
echo [INFO] Server Root: %SERVER_ROOT%
echo [INFO] Package Dir: %PACKAGE_DIR%
echo.

mkdir "%PACKAGE_DIR%"

echo [STEP 1] Copying standalone server project...
robocopy "%SERVER_ROOT%" "%PACKAGE_DIR%" /E /R:1 /W:1 ^
  /XD "%SERVER_ROOT%\dist" "%SERVER_ROOT%\data" "%SERVER_ROOT%\logs" runtime __pycache__ pip ^
  /XF .env *.db *.sqlite *.sqlite3 *.log *.pyc
if errorlevel 8 (
    echo [ERROR] Server package copy failed.
    exit /b 1
)

echo [STEP 2] Creating runtime directories...
mkdir "%PACKAGE_DIR%\data\backend" 2>nul
mkdir "%PACKAGE_DIR%\logs\stt" 2>nul
mkdir "%PACKAGE_DIR%\shared\data" 2>nul

echo.
echo ==========================================
echo   Package created successfully!
echo ==========================================
echo   Location: %PACKAGE_DIR%
echo.
echo   Ubuntu: bash deploy-ubuntu.sh
echo   Windows: init-server.bat then start-all.bat
echo ==========================================

explorer "%DIST_ROOT%"
endlocal
pause
