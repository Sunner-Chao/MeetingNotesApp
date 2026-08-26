@echo off
setlocal EnableExtensions

if exist "%~dp0.env.windows-local.bat" call "%~dp0.env.windows-local.bat"

if defined STT_AUTH_ENV_FILE (
    if not exist "%STT_AUTH_ENV_FILE%" (
        echo [ERROR] STT auth environment is missing: %STT_AUTH_ENV_FILE%
        exit /b 1
    )
    for /f "usebackq tokens=1,* delims==" %%A in (`findstr /b /c:"STT_API_TOKEN=" /c:"ACCOUNT_TOKEN_SECRET=" /c:"WEB_API_USERNAME=" /c:"WEB_API_TOKEN=" "%STT_AUTH_ENV_FILE%"`) do set "%%A=%%~B"
)

if defined STT_LOCAL_WEB_API_USERNAME set "WEB_API_USERNAME=%STT_LOCAL_WEB_API_USERNAME%"
if defined STT_LOCAL_WEB_API_TOKEN set "WEB_API_TOKEN=%STT_LOCAL_WEB_API_TOKEN%"

if /i "%STT_REQUIRE_API_TOKEN%"=="1" (
    if not defined STT_API_TOKEN (
        echo [ERROR] STT_API_TOKEN is required for the Windows STT service.
        exit /b 1
    )
    if not defined ACCOUNT_TOKEN_SECRET (
        echo [ERROR] ACCOUNT_TOKEN_SECRET is required for account-scoped STT access.
        exit /b 1
    )
    if not defined WEB_API_TOKEN (
        echo [ERROR] WEB_API_TOKEN is required for the STT management page.
        exit /b 1
    )
)

set "NVIDIA_RUNTIME_ROOT=%~dp0runtime\Lib\site-packages\nvidia"
if exist "%NVIDIA_RUNTIME_ROOT%\cublas\bin" set "PATH=%NVIDIA_RUNTIME_ROOT%\cublas\bin;%PATH%"
if exist "%NVIDIA_RUNTIME_ROOT%\cudnn\bin" set "PATH=%NVIDIA_RUNTIME_ROOT%\cudnn\bin;%PATH%"
if exist "%NVIDIA_RUNTIME_ROOT%\cuda_nvrtc\bin" set "PATH=%NVIDIA_RUNTIME_ROOT%\cuda_nvrtc\bin;%PATH%"

if not defined STT_DEVICE set "STT_DEVICE=auto"
if not defined STT_COMPUTE_TYPE set "STT_COMPUTE_TYPE=float16"
if not defined STT_ENGINE set "STT_ENGINE=faster-whisper"
if not defined STT_MODEL set "STT_MODEL=large-v3-turbo"
if not defined STT_STREAM_MODEL set "STT_STREAM_MODEL=%STT_MODEL%"
if not defined STT_FINAL_AUDIO_ENHANCEMENT set "STT_FINAL_AUDIO_ENHANCEMENT=1"
if not defined STT_FINAL_DENOISE_NOISE_FLOOR_DBFS set "STT_FINAL_DENOISE_NOISE_FLOOR_DBFS=-48"
if not defined STT_FINAL_DENOISE_MAX_SNR_DB set "STT_FINAL_DENOISE_MAX_SNR_DB=26"
if not defined STT_FINAL_GAIN_SPEECH_LEVEL_DBFS set "STT_FINAL_GAIN_SPEECH_LEVEL_DBFS=-28"
if not defined STT_FINAL_DENOISE_REDUCTION_DB set "STT_FINAL_DENOISE_REDUCTION_DB=8"
if not defined STT_FINAL_AUDIO_ANALYSIS_MAX_WINDOWS set "STT_FINAL_AUDIO_ANALYSIS_MAX_WINDOWS=3000"
if not defined STT_FINAL_CONTEXT_HINT_MAX_CHARS set "STT_FINAL_CONTEXT_HINT_MAX_CHARS=240"

call "%~dp0start.bat" "%STT_ENGINE%" "%STT_MODEL%"
exit /b %errorlevel%
