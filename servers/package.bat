@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0.."
set "PROJECT_ROOT=%CD%"
set "PACKAGE_NAME=MeetingNotesApp-Server-%date:~0,4%%date:~5,2%%date:~8,2%"
set "PACKAGE_DIR=%PROJECT_ROOT%\dist\%PACKAGE_NAME%"

echo ==========================================
echo   MeetingNotesApp Server Package Builder
echo ==========================================
echo.
echo [INFO] Project Root: %PROJECT_ROOT%
echo [INFO] Package Dir: %PACKAGE_DIR%
echo.

REM Clean previous package
if exist "%PROJECT_ROOT%\dist" rd /s /q "%PROJECT_ROOT%\dist"
mkdir "%PACKAGE_DIR%"

echo [STEP 1] Copying models...
mkdir "%PACKAGE_DIR%\models"
xcopy "%PROJECT_ROOT%\models" "%PACKAGE_DIR%\models" /E /I /Q
echo        Models copied.

echo [STEP 2] Copying servers...
mkdir "%PACKAGE_DIR%\servers"
xcopy "%PROJECT_ROOT%\servers" "%PACKAGE_DIR%\servers" /E /I /Q
echo        Servers copied.

echo [STEP 3] Removing runtime directories (will be created on target)...
if exist "%PACKAGE_DIR%\servers\stt-service\runtime" rd /s /q "%PACKAGE_DIR%\servers\stt-service\runtime"
if exist "%PACKAGE_DIR%\servers\backend-service\runtime" rd /s /q "%PACKAGE_DIR%\servers\backend-service\runtime"
if exist "%PACKAGE_DIR%\servers\stt-service\logs" rd /s /q "%PACKAGE_DIR%\servers\stt-service\logs"
if exist "%PACKAGE_DIR%\servers\backend-service\logs" rd /s /q "%PACKAGE_DIR%\servers\backend-service\logs"
if exist "%PACKAGE_DIR%\servers\stt-service\__pycache__" rd /s /q "%PACKAGE_DIR%\servers\stt-service\__pycache__"
if exist "%PACKAGE_DIR%\servers\backend-service\__pycache__" rd /s /q "%PACKAGE_DIR%\servers\backend-service\__pycache__"
if exist "%PACKAGE_DIR%\servers\stt-service\pip" rd /s /q "%PACKAGE_DIR%\servers\stt-service\pip"
echo        Cleaned.

echo [STEP 4] Creating shared data directory...
mkdir "%PACKAGE_DIR%\servers\shared\data" 2>nul
echo        Created.

echo [STEP 5] Copying deployment guide...
copy "%~dp0DEPLOY.md" "%PACKAGE_DIR%\DEPLOY.md" >nul
echo        Guide copied.

echo.
echo ==========================================
echo   Package created successfully!
echo ==========================================
echo.
echo   Location: %PACKAGE_DIR%
echo.
echo   Next steps:
echo   1. Copy the '%PACKAGE_NAME%' folder to target server
echo   2. Install Python 3.10+ on target server
echo   3. Run: servers\init-server.bat
echo   4. Run: servers\start-all.bat
echo.
echo ==========================================

REM Open the dist folder
explorer "%PROJECT_ROOT%\dist"

endlocal
pause
