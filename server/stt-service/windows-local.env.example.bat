@echo off
REM Copy this file to .env.windows-local.bat and set a machine-local model root.
set "STT_MODEL_ROOT=D:\path\to\models"
REM Private env snapshot containing STT_API_TOKEN, ACCOUNT_TOKEN_SECRET,
REM WEB_API_USERNAME, and WEB_API_TOKEN. Never commit the snapshot.
set "STT_AUTH_ENV_FILE=C:\path\to\private\server.env"
set "STT_REQUIRE_API_TOKEN=1"
REM RTX 30/40 series and similar CUDA hosts can use the stronger local model.
set "STT_DEVICE=cuda"
set "STT_COMPUTE_TYPE=float16"
set "STT_MODEL=large-v3-turbo"
set "STT_STREAM_MODEL=large-v3-turbo"
set "STT_MAX_CONCURRENT=1"
