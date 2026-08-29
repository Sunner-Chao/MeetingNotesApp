@echo off
REM Copy this file to .env.windows-local.bat and set a machine-local model root.
set "STT_MODEL_ROOT=D:\path\to\models"
REM Private env snapshot containing STT_API_TOKEN, ACCOUNT_TOKEN_SECRET,
REM WEB_API_USERNAME, and WEB_API_TOKEN. Never commit the snapshot.
set "STT_AUTH_ENV_FILE=C:\path\to\private\server.env"
set "STT_REQUIRE_API_TOKEN=1"
REM The personal GPU node validates signed account tokens, while the production
REM cloud service remains the single source of truth for points and billing.
set "ACCOUNT_STT_BILLING_ENABLED=0"
REM RTX 30/40 series and similar CUDA hosts can use the stronger local model.
set "STT_DEVICE=cuda"
set "STT_COMPUTE_TYPE=float16"
set "STT_MODEL=large-v3-turbo"
set "STT_STREAM_MODEL=large-v3-turbo"
set "STT_MAX_CONCURRENT=1"
REM Final transcripts can include stable speaker labels when the two local
REM sherpa-onnx models exist under %STT_MODEL_ROOT%\speaker-diarization.
set "STT_SPEAKER_DIARIZATION_ENABLED=1"
set "STT_SPEAKER_DIARIZATION_MAX_SPEAKERS=8"
set "STT_SPEAKER_DIARIZATION_MIN_TURN_SEC=0.7"
set "STT_SPEAKER_DIARIZATION_CLUSTER_THRESHOLD=0.9"
