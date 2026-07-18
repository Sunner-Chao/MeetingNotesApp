@echo off
setlocal EnableExtensions

cd /d "%~dp0"
echo ==========================================
echo   MeetingNotesApp Server 1.0.2 Initializer
echo ==========================================
echo.
echo [INIT] Root: %~dp0
echo [INIT] Runtime: Python 3.11.15 with locked dependencies

where uv >nul 2>&1
if not errorlevel 1 goto use_uv

py -3.11 -c "import sys; raise SystemExit(0 if sys.version_info[:2] == (3, 11) else 1)" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] uv or Python 3.11 is required.
    echo [ERROR] Install uv from https://docs.astral.sh/uv/ and run this script again.
    exit /b 1
)

echo [INIT] Creating STT runtime with py -3.11...
if not exist "stt-service\runtime\Scripts\python.exe" py -3.11 -m venv "stt-service\runtime"
"stt-service\runtime\Scripts\python.exe" -m pip install --disable-pip-version-check -r "stt-service\requirements-core.lock.txt"
if errorlevel 1 exit /b 1

echo [INIT] Creating Backend runtime with py -3.11...
if not exist "backend-service\runtime\Scripts\python.exe" py -3.11 -m venv "backend-service\runtime"
"backend-service\runtime\Scripts\python.exe" -m pip install --disable-pip-version-check -r "backend-service\requirements.lock.txt"
if errorlevel 1 exit /b 1
goto verify

:use_uv
echo [INIT] Ensuring managed Python 3.11.15...
uv python install 3.11.15
if errorlevel 1 exit /b 1

echo [INIT] Creating STT runtime...
uv venv --clear --python 3.11.15 "stt-service\runtime"
if errorlevel 1 exit /b 1
uv pip install --python "stt-service\runtime\Scripts\python.exe" -r "stt-service\requirements-core.lock.txt"
if errorlevel 1 exit /b 1

echo [INIT] Creating Backend runtime...
uv venv --clear --python 3.11.15 "backend-service\runtime"
if errorlevel 1 exit /b 1
uv pip install --python "backend-service\runtime\Scripts\python.exe" -r "backend-service\requirements.lock.txt"
if errorlevel 1 exit /b 1

:verify
"stt-service\runtime\Scripts\python.exe" -c "import ctranslate2, fastapi, faster_whisper, uvicorn; print('[INIT] STT dependencies OK')"
if errorlevel 1 exit /b 1
uv pip check --python "stt-service\runtime\Scripts\python.exe" >nul 2>&1
if errorlevel 1 "stt-service\runtime\Scripts\python.exe" -m pip check
if errorlevel 1 exit /b 1
"backend-service\runtime\Scripts\python.exe" -c "import fastapi, requests, uvicorn; print('[INIT] Backend dependencies OK')"
if errorlevel 1 exit /b 1
uv pip check --python "backend-service\runtime\Scripts\python.exe" >nul 2>&1
if errorlevel 1 "backend-service\runtime\Scripts\python.exe" -m pip check
if errorlevel 1 exit /b 1

echo.
echo [INIT] Done. Start local services with start-all.bat.
endlocal
exit /b 0
