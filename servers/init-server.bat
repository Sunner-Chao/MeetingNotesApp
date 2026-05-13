@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

echo ==========================================
echo   MeetingNotesApp Server Initializer
echo ==========================================
echo.
echo [INIT] Root: %~dp0

where python >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found in PATH.
    echo [ERROR] Please install Python 3.10+ first.
    exit /b 1
)

echo [INIT] Initializing STT runtime...
pushd stt-service
if not exist "runtime" (
    python -m venv runtime
)
call runtime\Scripts\activate.bat
python -m pip install --upgrade pip -q
python -m pip install -r requirements.txt -q
popd

echo [INIT] Initializing Backend runtime...
pushd backend-service
if not exist "runtime" (
    python -m venv runtime
)
call runtime\Scripts\activate.bat
python -m pip install --upgrade pip -q
python -m pip install -r requirements.txt -q
popd

echo.
echo [INIT] Optional: install SenseVoice CUDA deps
echo [INIT] Run: stt-service\install-sensevoice-cuda.bat
echo.
echo [INIT] Done.
echo [INIT] Start services with: start-all.bat
endlocal
exit /b 0
