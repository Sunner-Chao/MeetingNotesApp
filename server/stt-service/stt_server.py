#!/usr/bin/env python3
"""
OA助手 STT Server
Supports both file-based transcription and WebSocket streaming preview.
"""

import os
import shutil

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

# Ensure ffmpeg is discoverable (required by SenseVoice/FunASR to load audio)
_ffmpeg = shutil.which("ffmpeg")
if _ffmpeg is None:
    for _d in (
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "Microsoft", "WinGet", "Links"),
        os.path.join(os.environ.get("SystemDrive", "C:"), os.sep, "ffmpeg"),
        os.path.join(os.environ.get("ProgramFiles", "C:\\Program Files"), "ffmpeg"),
    ):
        _ffmpeg_candidate = os.path.join(_d, "ffmpeg.exe")
        if os.path.isfile(_ffmpeg_candidate):
            os.environ["PATH"] = _d + os.pathsep + os.environ.get("PATH", "")
            _ffmpeg = _ffmpeg_candidate
            break
    if _ffmpeg is None:
        print("[STT] WARNING: ffmpeg not found - SenseVoice transcription will fail", flush=True)
    else:
        print(f"[STT] ffmpeg added to PATH: {_ffmpeg}", flush=True)
else:
    print(f"[STT] ffmpeg found on PATH: {_ffmpeg}", flush=True)

import argparse
import asyncio
import base64
import contextlib
import difflib
import hashlib
import hmac
import json
import math
import re
import secrets
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import wave
from urllib.parse import quote, urlencode, urlparse
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any
from uuid import uuid4
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from common.account_stt_token import verify_account_stt_token

import ctranslate2
import faster_whisper
from opencc import OpenCC

try:
    from funasr import AutoModel as FunASRAutoModel
except Exception:
    FunASRAutoModel = None
import uvicorn
import httpx
import websockets
from fastapi import Depends, Form, Header, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi import FastAPI
from fastapi.responses import FileResponse
from pydantic import BaseModel

from inference_scheduler import (
    InferenceQueueFullError,
    InferenceQueuePausedError,
    InferenceScheduler,
)

model = None
final_model = None
stream_model = None
stt_engine = "faster-whisper"
model_size = "small"
model_source = "small"
model_load_error = ""
stream_model_size = ""
stream_model_source = ""
stream_model_error = ""
stream_model_checksum_verified = False
model_root = Path(os.getenv("STT_MODEL_ROOT", str(Path(__file__).resolve().parent.parent / "models"))).resolve()


def read_release_value(filename: str, default: str) -> str:
    path = Path(__file__).resolve().parent.parent / filename
    try:
        return path.read_text(encoding="utf-8").strip() or default
    except OSError:
        return default


SERVER_VERSION = os.getenv("MEETINGNOTES_SERVER_VERSION", read_release_value("VERSION", "dev")).strip()
SERVER_RELEASE = os.getenv("MEETINGNOTES_RELEASE_ID", read_release_value("RELEASE", SERVER_VERSION)).strip()


def positive_int_env(name: str, default: int) -> int:
    try:
        return max(1, int(os.getenv(name, str(default))))
    except ValueError:
        return default


def nonnegative_int_env(name: str, default: int) -> int:
    try:
        return max(0, int(os.getenv(name, str(default))))
    except ValueError:
        return default


def boolean_env(name: str, default: bool = False) -> bool:
    return os.getenv(name, "1" if default else "0").strip().lower() in {"1", "true", "yes"}


STT_MAX_CONCURRENT = positive_int_env("STT_MAX_CONCURRENT", 2)
STT_MAX_QUEUE = positive_int_env("STT_MAX_QUEUE", 16)
STT_MAX_STREAMS = positive_int_env("STT_MAX_STREAMS", 16)
STT_MAX_STREAMS_PER_OWNER = positive_int_env("STT_MAX_STREAMS_PER_OWNER", 2)
STT_MAX_UPLOAD_MB = positive_int_env("STT_MAX_UPLOAD_MB", 1024)
STT_UPLOAD_CHUNK_BYTES = 1024 * 1024
STT_LONG_AUDIO_CHUNK_THRESHOLD_SEC = positive_int_env(
    "STT_LONG_AUDIO_CHUNK_THRESHOLD_SEC", 45 * 60
)
STT_LONG_AUDIO_CHUNK_SECONDS = positive_int_env(
    "STT_LONG_AUDIO_CHUNK_SECONDS", 30 * 60
)
STT_LONG_AUDIO_CHUNK_OVERLAP_SEC = min(
    10.0,
    max(0.0, float(os.getenv("STT_LONG_AUDIO_CHUNK_OVERLAP_SEC", "3"))),
)
STT_SWITCH_DRAIN_TIMEOUT_SEC = float(os.getenv("STT_SWITCH_DRAIN_TIMEOUT_SEC", "900"))
STT_DEVICE = os.getenv("STT_DEVICE", "auto").strip().lower()
STT_CPU_THREADS = positive_int_env("STT_CPU_THREADS", max(1, (os.cpu_count() or 2) // STT_MAX_CONCURRENT))
STT_API_TOKEN = os.getenv("STT_API_TOKEN", "").strip()
STT_REQUIRE_API_TOKEN = os.getenv("STT_REQUIRE_API_TOKEN", "0").strip().lower() in {"1", "true", "yes"}
ACCOUNT_TOKEN_SECRET = os.getenv("ACCOUNT_TOKEN_SECRET", "").strip()
STT_MODEL_SHA256 = os.getenv("STT_MODEL_SHA256", "").strip().lower()
STT_STREAM_MODEL = os.getenv("STT_STREAM_MODEL", "small").strip() or "small"
STT_STREAM_MODEL_SHA256 = os.getenv("STT_STREAM_MODEL_SHA256", "").strip().lower()
STT_STREAM_CPU_THREADS = positive_int_env("STT_STREAM_CPU_THREADS", STT_CPU_THREADS)
STT_FINAL_RETRY_MIN_CHARS = positive_int_env("STT_FINAL_RETRY_MIN_CHARS", 8)
FINAL_BEAM_SIZE = positive_int_env("STT_FINAL_BEAM_SIZE", 5)
STT_FINAL_BATCH_SIZE = positive_int_env("STT_FINAL_BATCH_SIZE", 1)
STT_FINAL_CONDITION_ON_PREVIOUS_TEXT = os.getenv(
    "STT_FINAL_CONDITION_ON_PREVIOUS_TEXT", "1"
).strip().lower() in {"1", "true", "yes"}
STT_FINAL_INITIAL_PROMPT = os.getenv(
    "STT_FINAL_INITIAL_PROMPT", ""
).strip()
STT_FINAL_RESTORE_PUNCTUATION = os.getenv(
    "STT_FINAL_RESTORE_PUNCTUATION", "1"
).strip().lower() in {"1", "true", "yes"}
STT_FINAL_PUNCTUATION_PAUSE_SEC = max(
    0.1, float(os.getenv("STT_FINAL_PUNCTUATION_PAUSE_SEC", "0.8"))
)
STT_STREAM_FINALIZE_WAIT_SEC = max(
    1.0,
    float(os.getenv("STT_STREAM_FINALIZE_WAIT_SEC", "15")),
)
STT_TEMP_DIR = Path(os.getenv("STT_TEMP_DIR", tempfile.gettempdir())).resolve()
STT_TEMP_MAX_AGE_SEC = positive_int_env("STT_TEMP_MAX_AGE_SEC", 21600)
STT_TEMP_CLEANUP_INTERVAL_SEC = positive_int_env("STT_TEMP_CLEANUP_INTERVAL_SEC", 3600)
STT_TEMP_PREFIX = "meetingnotes-stt-"
STT_AUDIO_ARCHIVE_ENABLED = os.getenv("STT_AUDIO_ARCHIVE_ENABLED", "0").strip().lower() in {
    "1", "true", "yes"
}
STT_AUDIO_ARCHIVE_DIR = Path(
    os.getenv("STT_AUDIO_ARCHIVE_DIR", str(Path(__file__).resolve().parent.parent / "audio-archive"))
).resolve()
STT_AUDIO_ARCHIVE_RETENTION_DAYS = positive_int_env("STT_AUDIO_ARCHIVE_RETENTION_DAYS", 30)
STT_AUDIO_ARCHIVE_MAX_GB = positive_int_env("STT_AUDIO_ARCHIVE_MAX_GB", 10)
audio_archive_lock = threading.Lock()
# Deprecated generic switches remain only for source compatibility. They are never
# consulted by the production routes so an old deployment cannot activate the paid tier.
TENCENT_ASR_ENABLED = boolean_env("TENCENT_ASR_ENABLED")
TENCENT_ASR_BASE_URL = os.getenv(
    "TENCENT_ASR_BASE_URL", "https://asr.cloud.tencent.com/asr/flash/v1"
).strip().rstrip("/")
TENCENT_ASR_APP_ID = os.getenv("TENCENT_ASR_APP_ID", "").strip()
TENCENT_ASR_SECRET_ID = os.getenv("TENCENT_ASR_SECRET_ID", "").strip()
TENCENT_ASR_SECRET_KEY = os.getenv("TENCENT_ASR_SECRET_KEY", "").strip()
# Deprecated generic engine values are retained for legacy unit tests only.
TENCENT_ASR_ENGINE_TYPE = os.getenv("TENCENT_ASR_ENGINE_TYPE", "16k_zh_en").strip()
TENCENT_ASR_ENGINE_TYPE_EN = os.getenv(
    "TENCENT_ASR_ENGINE_TYPE_EN", TENCENT_ASR_ENGINE_TYPE
).strip()
TENCENT_ASR_TIMEOUT_SEC = max(30.0, float(os.getenv("TENCENT_ASR_TIMEOUT_SEC", "900")))
TENCENT_ASR_MAX_UPLOAD_MB = min(100, positive_int_env("TENCENT_ASR_MAX_UPLOAD_MB", 100))
TENCENT_ASR_MAX_CONCURRENT = positive_int_env("TENCENT_ASR_MAX_CONCURRENT", 2)
TENCENT_ASR_CHUNK_SECONDS = max(60, positive_int_env("TENCENT_ASR_CHUNK_SECONDS", 2400))
TENCENT_ASR_CHUNK_OVERLAP_SEC = min(
    10.0,
    max(0.0, float(os.getenv("TENCENT_ASR_CHUNK_OVERLAP_SEC", "3"))),
)
TENCENT_REALTIME_ASR_ENABLED = boolean_env("TENCENT_REALTIME_ASR_ENABLED")
TENCENT_REALTIME_ASR_BASE_URL = os.getenv(
    "TENCENT_REALTIME_ASR_BASE_URL", "wss://asr.cloud.tencent.com/asr/v2"
).strip().rstrip("/")
TENCENT_REALTIME_ASR_ENGINE_TYPE = os.getenv(
    "TENCENT_REALTIME_ASR_ENGINE_TYPE", TENCENT_ASR_ENGINE_TYPE
).strip()
TENCENT_REALTIME_ASR_ENGINE_TYPE_EN = os.getenv(
    "TENCENT_REALTIME_ASR_ENGINE_TYPE_EN", TENCENT_ASR_ENGINE_TYPE_EN
).strip()
TENCENT_REALTIME_ASR_MAX_CONCURRENT = positive_int_env(
    "TENCENT_REALTIME_ASR_MAX_CONCURRENT", STT_MAX_STREAMS
)
TENCENT_REALTIME_ASR_CONNECT_TIMEOUT_SEC = max(
    1.0, float(os.getenv("TENCENT_REALTIME_ASR_CONNECT_TIMEOUT_SEC", "15"))
)
TENCENT_REALTIME_ASR_ACQUIRE_TIMEOUT_SEC = max(
    1.0, float(os.getenv("TENCENT_REALTIME_ASR_ACQUIRE_TIMEOUT_SEC", "10"))
)
TENCENT_REALTIME_ASR_FINAL_TIMEOUT_SEC = max(
    1.0, float(os.getenv("TENCENT_REALTIME_ASR_FINAL_TIMEOUT_SEC", "15"))
)
TENCENT_REALTIME_ASR_SIGNATURE_TTL_SEC = positive_int_env(
    "TENCENT_REALTIME_ASR_SIGNATURE_TTL_SEC", 3600
)
TENCENT_REALTIME_ASR_FRAME_MS = max(
    40, min(1000, positive_int_env("TENCENT_REALTIME_ASR_FRAME_MS", 200))
)
TENCENT_REALTIME_ASR_QUEUE_SEC = positive_int_env("TENCENT_REALTIME_ASR_QUEUE_SEC", 6)
TENCENT_REALTIME_ASR_BACKPRESSURE_TIMEOUT_SEC = max(
    1.0,
    float(
        os.getenv(
            "TENCENT_REALTIME_ASR_BACKPRESSURE_TIMEOUT_SEC",
            str(max(10, TENCENT_REALTIME_ASR_QUEUE_SEC * 2)),
        )
    ),
)

TENCENT_STANDARD_TIER = "standard"
TENCENT_PRECISION_TIER = "precision"
TENCENT_LEGACY_USAGE_TIER = os.getenv(
    "TENCENT_LEGACY_USAGE_TIER", TENCENT_PRECISION_TIER
).strip().lower()
if TENCENT_LEGACY_USAGE_TIER not in {TENCENT_STANDARD_TIER, TENCENT_PRECISION_TIER}:
    TENCENT_LEGACY_USAGE_TIER = TENCENT_PRECISION_TIER
TENCENT_STANDARD_ASR_ENABLED = boolean_env("TENCENT_STANDARD_ASR_ENABLED")
TENCENT_STANDARD_REALTIME_ASR_ENABLED = boolean_env("TENCENT_STANDARD_REALTIME_ASR_ENABLED")
TENCENT_STANDARD_ASR_ENGINE_TYPE = os.getenv(
    "TENCENT_STANDARD_ASR_ENGINE_TYPE", "16k_zh"
).strip()
TENCENT_STANDARD_ASR_ENGINE_TYPE_EN = os.getenv(
    "TENCENT_STANDARD_ASR_ENGINE_TYPE_EN", "16k_en"
).strip()
TENCENT_STANDARD_REALTIME_ASR_ENGINE_TYPE = os.getenv(
    "TENCENT_STANDARD_REALTIME_ASR_ENGINE_TYPE", TENCENT_STANDARD_ASR_ENGINE_TYPE
).strip()
TENCENT_STANDARD_REALTIME_ASR_ENGINE_TYPE_EN = os.getenv(
    "TENCENT_STANDARD_REALTIME_ASR_ENGINE_TYPE_EN", TENCENT_STANDARD_ASR_ENGINE_TYPE_EN
).strip()
TENCENT_STANDARD_MONTHLY_LIMIT_SEC = nonnegative_int_env(
    "TENCENT_STANDARD_MONTHLY_LIMIT_SEC", 5 * 60 * 60
)

# Precision uses Tencent's large-model engines. It must be opt-in and have a
# positive server-side cap; setting only ENABLED=1 is intentionally insufficient.
TENCENT_PRECISION_ASR_ENABLED = boolean_env("TENCENT_PRECISION_ASR_ENABLED")
TENCENT_PRECISION_REALTIME_ASR_ENABLED = boolean_env("TENCENT_PRECISION_REALTIME_ASR_ENABLED")
TENCENT_PRECISION_ASR_ENGINE_TYPE = os.getenv(
    "TENCENT_PRECISION_ASR_ENGINE_TYPE", "16k_zh_en"
).strip()
TENCENT_PRECISION_ASR_ENGINE_TYPE_EN = os.getenv(
    "TENCENT_PRECISION_ASR_ENGINE_TYPE_EN", TENCENT_PRECISION_ASR_ENGINE_TYPE
).strip()
TENCENT_PRECISION_REALTIME_ASR_ENGINE_TYPE = os.getenv(
    "TENCENT_PRECISION_REALTIME_ASR_ENGINE_TYPE", TENCENT_PRECISION_ASR_ENGINE_TYPE
).strip()
TENCENT_PRECISION_REALTIME_ASR_ENGINE_TYPE_EN = os.getenv(
    "TENCENT_PRECISION_REALTIME_ASR_ENGINE_TYPE_EN", TENCENT_PRECISION_ASR_ENGINE_TYPE_EN
).strip()
TENCENT_PRECISION_MONTHLY_LIMIT_SEC = nonnegative_int_env(
    "TENCENT_PRECISION_MONTHLY_LIMIT_SEC", 0
)

TENCENT_STANDARD_MODEL = "tencent-standard"
TENCENT_PRECISION_MODEL = "tencent-precision"
LOCAL_STREAM_PROVIDER = "local"
TENCENT_STANDARD_REALTIME_STREAM_PROVIDER = "tencent-realtime-standard"
TENCENT_PRECISION_REALTIME_STREAM_PROVIDER = "tencent-realtime-precision"
# The generic value is accepted only as a legacy alias and always means standard.
TENCENT_REALTIME_STREAM_PROVIDER = "tencent-realtime"
TENCENT_ASR_USAGE_ENABLED = os.getenv("TENCENT_ASR_USAGE_ENABLED", "1").strip().lower() in {
    "1", "true", "yes"
}
TENCENT_ASR_USAGE_API_ENDPOINT = os.getenv(
    "TENCENT_ASR_USAGE_API_ENDPOINT", "asr.tencentcloudapi.com"
).strip()
TENCENT_ASR_USAGE_REGION = os.getenv("TENCENT_ASR_USAGE_REGION", "ap-guangzhou").strip()
SUPPORTED_STT_LANGUAGES = {"zh", "en"}
TENCENT_ASR_USAGE_TIMEZONE = os.getenv("TENCENT_ASR_USAGE_TIMEZONE", "Asia/Shanghai").strip()
TENCENT_ASR_USAGE_CACHE_SEC = positive_int_env("TENCENT_ASR_USAGE_CACHE_SEC", 300)
TENCENT_ASR_USAGE_LEDGER_ENABLED = os.getenv(
    "TENCENT_ASR_USAGE_LEDGER_ENABLED", "1"
).strip().lower() in {"1", "true", "yes"}
TENCENT_ASR_USAGE_LEDGER_PATH = Path(
    os.getenv("TENCENT_ASR_USAGE_LEDGER_PATH", "./data/tencent-asr-usage.db")
).resolve()
TENCENT_REALTIME_MONTHLY_FREE_SEC = positive_int_env(
    "TENCENT_REALTIME_MONTHLY_FREE_SEC", 5 * 60 * 60
)
TENCENT_FLASH_MONTHLY_FREE_SEC = positive_int_env(
    "TENCENT_FLASH_MONTHLY_FREE_SEC", 5 * 60 * 60
)
STT_ALLOWED_MODELS = {
    item.strip()
    for item in os.getenv(
        "STT_ALLOWED_MODELS",
        "tiny,base,small,medium,large-v3,SenseVoiceSmall,iic/SenseVoiceSmall",
    ).split(",")
    if item.strip()
}

inference_scheduler = InferenceScheduler(STT_MAX_CONCURRENT, STT_MAX_QUEUE)
tencent_asr_semaphore = asyncio.Semaphore(TENCENT_ASR_MAX_CONCURRENT)
tencent_realtime_asr_semaphore = asyncio.Semaphore(TENCENT_REALTIME_ASR_MAX_CONCURRENT)
tencent_usage_cache_lock = threading.Lock()
tencent_usage_ledger_lock = threading.Lock()
tencent_usage_cache: tuple[float, str, dict[str, Any]] | None = None
_tencent_usage_ledger_initialized = False
model_switch_lock = asyncio.Lock()
sensevoice_inference_lock = threading.Lock()
active_stream_sessions: set[str] = set()
active_stream_owners: dict[str, int] = {}
stream_recordings: dict[str, "StreamRecording"] = {}
model_checksum_verified = False
temp_files_cleaned = 0


@contextlib.contextmanager
def _sqlite_connection(path: Path):
    """Commit or roll back a SQLite transaction and always release the file handle."""
    conn = sqlite3.connect(path)
    try:
        with conn:
            yield conn
    finally:
        conn.close()


@dataclass(frozen=True)
class TencentAsrTierConfig:
    key: str
    display_name: str
    model_id: str
    stream_provider: str
    flash_enabled: bool
    realtime_enabled: bool
    flash_engine_type: str
    realtime_engine_type: str
    monthly_limit_sec: int
    paid: bool


def tencent_tier_config(tier: str) -> TencentAsrTierConfig:
    normalized = tier.strip().lower()
    if normalized == TENCENT_STANDARD_TIER:
        return TencentAsrTierConfig(
            key=TENCENT_STANDARD_TIER,
            display_name="智悟增强云模型 · 标准",
            model_id=TENCENT_STANDARD_MODEL,
            stream_provider=TENCENT_STANDARD_REALTIME_STREAM_PROVIDER,
            flash_enabled=TENCENT_STANDARD_ASR_ENABLED,
            realtime_enabled=TENCENT_STANDARD_REALTIME_ASR_ENABLED,
            flash_engine_type=TENCENT_STANDARD_ASR_ENGINE_TYPE,
            realtime_engine_type=TENCENT_STANDARD_REALTIME_ASR_ENGINE_TYPE,
            monthly_limit_sec=TENCENT_STANDARD_MONTHLY_LIMIT_SEC,
            paid=False,
        )
    if normalized == TENCENT_PRECISION_TIER:
        return TencentAsrTierConfig(
            key=TENCENT_PRECISION_TIER,
            display_name="智悟增强云模型 · 臻享",
            model_id=TENCENT_PRECISION_MODEL,
            stream_provider=TENCENT_PRECISION_REALTIME_STREAM_PROVIDER,
            flash_enabled=TENCENT_PRECISION_ASR_ENABLED,
            realtime_enabled=TENCENT_PRECISION_REALTIME_ASR_ENABLED,
            flash_engine_type=TENCENT_PRECISION_ASR_ENGINE_TYPE,
            realtime_engine_type=TENCENT_PRECISION_REALTIME_ASR_ENGINE_TYPE,
            monthly_limit_sec=TENCENT_PRECISION_MONTHLY_LIMIT_SEC,
            paid=True,
        )
    raise ValueError("Unsupported Tencent ASR tier")


def tencent_model_tier(model_name: str | None) -> str | None:
    normalized = (model_name or "").strip().lower()
    if normalized in {"", "tencent-flash", TENCENT_STANDARD_MODEL}:
        return TENCENT_STANDARD_TIER
    if normalized == TENCENT_PRECISION_MODEL:
        return TENCENT_PRECISION_TIER
    return None


def normalize_stt_language(language: str | None) -> str:
    normalized = (language or "zh").strip().lower()
    if normalized not in SUPPORTED_STT_LANGUAGES:
        raise ValueError("Unsupported STT language; expected zh or en")
    return normalized


def tencent_engine_type_for_language(
    *,
    tier: str | None,
    language: str,
    realtime: bool,
) -> str:
    normalized_language = normalize_stt_language(language)
    if tier == TENCENT_STANDARD_TIER:
        if realtime:
            return (
                TENCENT_STANDARD_REALTIME_ASR_ENGINE_TYPE_EN
                if normalized_language == "en"
                else TENCENT_STANDARD_REALTIME_ASR_ENGINE_TYPE
            )
        return (
            TENCENT_STANDARD_ASR_ENGINE_TYPE_EN
            if normalized_language == "en"
            else TENCENT_STANDARD_ASR_ENGINE_TYPE
        )
    if tier == TENCENT_PRECISION_TIER:
        if realtime:
            return (
                TENCENT_PRECISION_REALTIME_ASR_ENGINE_TYPE_EN
                if normalized_language == "en"
                else TENCENT_PRECISION_REALTIME_ASR_ENGINE_TYPE
            )
        return (
            TENCENT_PRECISION_ASR_ENGINE_TYPE_EN
            if normalized_language == "en"
            else TENCENT_PRECISION_ASR_ENGINE_TYPE
        )
    if realtime:
        return TENCENT_REALTIME_ASR_ENGINE_TYPE_EN if normalized_language == "en" else TENCENT_REALTIME_ASR_ENGINE_TYPE
    return TENCENT_ASR_ENGINE_TYPE_EN if normalized_language == "en" else TENCENT_ASR_ENGINE_TYPE


def tencent_stream_tier(provider: str | None) -> str | None:
    normalized = (provider or "").strip().lower()
    if normalized in {TENCENT_REALTIME_STREAM_PROVIDER, TENCENT_STANDARD_REALTIME_STREAM_PROVIDER}:
        return TENCENT_STANDARD_TIER
    if normalized == TENCENT_PRECISION_REALTIME_STREAM_PROVIDER:
        return TENCENT_PRECISION_TIER
    return None


def tencent_asr_configured(tier: str | None = None) -> bool:
    if tier is not None:
        config = tencent_tier_config(tier)
        return bool(
            config.flash_enabled
            and (not config.paid or config.monthly_limit_sec > 0)
            and TENCENT_ASR_BASE_URL
            and TENCENT_ASR_APP_ID
            and TENCENT_ASR_SECRET_ID
            and TENCENT_ASR_SECRET_KEY
            and config.flash_engine_type
        )
    return bool(
        TENCENT_ASR_ENABLED
        and TENCENT_ASR_BASE_URL
        and TENCENT_ASR_APP_ID
        and TENCENT_ASR_SECRET_ID
        and TENCENT_ASR_SECRET_KEY
        and TENCENT_ASR_ENGINE_TYPE
    )


def tencent_realtime_asr_configured(tier: str | None = None) -> bool:
    if tier is not None:
        config = tencent_tier_config(tier)
        return bool(
            config.realtime_enabled
            and (not config.paid or config.monthly_limit_sec > 0)
            and TENCENT_REALTIME_ASR_BASE_URL
            and TENCENT_ASR_APP_ID
            and TENCENT_ASR_SECRET_ID
            and TENCENT_ASR_SECRET_KEY
            and config.realtime_engine_type
        )
    return bool(
        TENCENT_REALTIME_ASR_ENABLED
        and TENCENT_REALTIME_ASR_BASE_URL
        and TENCENT_ASR_APP_ID
        and TENCENT_ASR_SECRET_ID
        and TENCENT_ASR_SECRET_KEY
        and TENCENT_REALTIME_ASR_ENGINE_TYPE
    )


def tencent_asr_budget_enforced(tier: str) -> bool:
    """Only the opt-in paid tier is guarded by the application budget ledger."""
    return tencent_tier_config(tier).paid


def tencent_asr_usage_configured() -> bool:
    return bool(
        TENCENT_ASR_USAGE_ENABLED
        and TENCENT_ASR_USAGE_API_ENDPOINT
        and TENCENT_ASR_USAGE_REGION
        and TENCENT_ASR_SECRET_ID
        and TENCENT_ASR_SECRET_KEY
    )


def tencent_voice_format(filename: str | None) -> str:
    suffix = Path(filename or "recording.wav").suffix.lower().lstrip(".")
    aliases = {"oga": "ogg-opus", "ogg": "ogg-opus", "mp4": "m4a"}
    value = aliases.get(suffix, suffix)
    if value not in {"wav", "pcm", "ogg-opus", "speex", "silk", "mp3", "m4a", "aac", "amr"}:
        raise ValueError("Tencent Cloud ASR does not support this audio format")
    return value


def build_tencent_flash_request(
    *,
    voice_format: str,
    tier: str | None = None,
    language: str = "zh",
    timestamp: int | None = None,
) -> tuple[str, str]:
    request_timestamp = int(time.time()) if timestamp is None else int(timestamp)
    engine_type = tencent_engine_type_for_language(
        tier=tier,
        language=language,
        realtime=False,
    )
    params = {
        "convert_num_mode": "1",
        "engine_type": engine_type,
        "filter_dirty": "0",
        "filter_modal": "0",
        "filter_punc": "0",
        "first_channel_only": "1",
        "secretid": TENCENT_ASR_SECRET_ID,
        "speaker_diarization": "0",
        "timestamp": str(request_timestamp),
        "voice_format": voice_format,
        "word_info": "0",
    }
    query = urlencode(sorted(params.items()))
    request_url = f"{TENCENT_ASR_BASE_URL}/{quote(TENCENT_ASR_APP_ID, safe='')}?{query}"
    parsed = urlparse(request_url)
    signature_source = f"POST{parsed.netloc}{parsed.path}?{parsed.query}"
    digest = hmac.new(
        TENCENT_ASR_SECRET_KEY.encode("utf-8"),
        signature_source.encode("utf-8"),
        hashlib.sha1,
    ).digest()
    return request_url, base64.b64encode(digest).decode("ascii")


def parse_tencent_flash_response(payload: dict[str, Any]) -> str:
    code = int(payload.get("code", -1))
    if code != 0:
        detail = str(payload.get("message") or "Tencent Cloud ASR request failed").strip()
        raise ValueError(f"Tencent Cloud ASR error {code}: {detail}")
    results = payload.get("flash_result")
    if not isinstance(results, list):
        raise ValueError("Tencent Cloud ASR returned an invalid result")
    text = normalize_preview_text(
        "\n".join(
            str(item.get("text") or "").strip()
            for item in results
            if isinstance(item, dict) and str(item.get("text") or "").strip()
        )
    )
    if not text:
        raise ValueError("Tencent Cloud ASR did not recognize valid speech")
    return text


def transcribe_with_tencent_flash(
    path: Path,
    voice_format: str,
    *,
    tier: str | None = None,
    language: str = "zh",
    record_usage: bool = True,
) -> tuple[str, dict[str, Any]]:
    request_url, authorization = build_tencent_flash_request(
        voice_format=voice_format,
        tier=tier,
        language=language,
    )
    size = path.stat().st_size

    def content_chunks():
        with path.open("rb") as source:
            while chunk := source.read(STT_UPLOAD_CHUNK_BYTES):
                yield chunk

    headers = {
        "Authorization": authorization,
        "Content-Type": "application/octet-stream",
        "Content-Length": str(size),
    }
    with httpx.Client(timeout=TENCENT_ASR_TIMEOUT_SEC) as client:
        response = client.post(request_url, headers=headers, content=content_chunks())
    if response.status_code != 200:
        raise ValueError(f"Tencent Cloud ASR returned HTTP {response.status_code}")
    payload = response.json()
    if not isinstance(payload, dict):
        raise ValueError("Tencent Cloud ASR returned invalid JSON")
    text = parse_tencent_flash_response(payload)
    duration_seconds = 0.0
    with contextlib.suppress(TypeError, ValueError):
        duration_seconds = max(0.0, float(payload.get("audio_duration") or 0) / 1000)
    if duration_seconds <= 0:
        duration_seconds = wav_duration_sec(path) or 0.0
    if record_usage:
        with contextlib.suppress(Exception):
            record_local_tencent_asr_usage("asr_rec", duration_seconds)
    return text, payload


@dataclass(frozen=True)
class AudioChunk:
    path: Path
    start_seconds: float
    duration_seconds: float


TencentAudioChunk = AudioChunk


class AudioChunkingError(RuntimeError):
    pass


class TencentChunkedTranscriptionError(RuntimeError):
    pass


def create_audio_chunks(
    source: Path,
    output_dir: Path,
    *,
    chunk_seconds: int,
    overlap_seconds: float,
    max_chunk_bytes: int | None = None,
    ffmpeg_timeout_sec: float | None = None,
) -> list[AudioChunk]:
    duration = audio_duration_for_tencent_budget(source)
    if duration is None or duration <= 0:
        raise AudioChunkingError("无法读取长录音时长，不能安全分段")

    ffmpeg_path = _ffmpeg or shutil.which("ffmpeg")
    if not ffmpeg_path:
        raise AudioChunkingError("服务器缺少音频分段组件")

    output_dir.mkdir(parents=True, exist_ok=True)
    chunks: list[AudioChunk] = []
    core_start = 0.0
    safe_chunk_seconds = max(60, chunk_seconds)
    overlap = min(max(0.0, overlap_seconds), safe_chunk_seconds / 4)
    while core_start < duration:
        core_end = min(duration, core_start + safe_chunk_seconds)
        segment_start = max(0.0, core_start - (overlap if chunks else 0.0))
        segment_end = min(
            duration,
            core_end + (overlap if core_end < duration else 0.0),
        )
        segment_duration = segment_end - segment_start
        output_path = output_dir / f"chunk-{len(chunks) + 1:03d}.wav"
        command = [
            ffmpeg_path,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source),
            "-ss",
            f"{segment_start:.3f}",
            "-t",
            f"{segment_duration:.3f}",
            "-map",
            "0:a:0",
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "pcm_s16le",
            str(output_path),
        ]
        try:
            subprocess.run(
                command,
                check=True,
                capture_output=True,
                timeout=max(120.0, ffmpeg_timeout_sec or segment_duration * 2),
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise AudioChunkingError(
                f"第 {len(chunks) + 1} 段音频生成失败"
            ) from exc
        if not output_path.is_file() or output_path.stat().st_size <= 44:
            raise AudioChunkingError(
                f"第 {len(chunks) + 1} 段音频为空"
            )
        if max_chunk_bytes is not None and output_path.stat().st_size > max_chunk_bytes:
            raise AudioChunkingError(
                f"第 {len(chunks) + 1} 段标准化后仍超过单段体积限制"
            )
        chunks.append(
            AudioChunk(
                path=output_path,
                start_seconds=segment_start,
                duration_seconds=segment_duration,
            )
        )
        core_start = core_end

    if not chunks:
        raise AudioChunkingError("长录音分段结果为空")
    return chunks


def create_tencent_audio_chunks(source: Path, output_dir: Path) -> list[TencentAudioChunk]:
    try:
        return create_audio_chunks(
            source,
            output_dir,
            chunk_seconds=TENCENT_ASR_CHUNK_SECONDS,
            overlap_seconds=TENCENT_ASR_CHUNK_OVERLAP_SEC,
            max_chunk_bytes=TENCENT_ASR_MAX_UPLOAD_MB * 1024 * 1024,
            ffmpeg_timeout_sec=TENCENT_ASR_TIMEOUT_SEC,
        )
    except AudioChunkingError as exc:
        raise TencentChunkedTranscriptionError(str(exc)) from exc


def transcribe_with_tencent_flash_chunked(
    path: Path,
    voice_format: str,
    *,
    tier: str | None = None,
    language: str = "zh",
    record_usage: bool = True,
) -> tuple[str, dict[str, Any]]:
    max_bytes = TENCENT_ASR_MAX_UPLOAD_MB * 1024 * 1024
    source_bytes = path.stat().st_size
    duration_seconds = None if source_bytes > max_bytes else audio_duration_for_tencent_budget(path)
    requires_chunking = source_bytes > max_bytes or (
        duration_seconds is not None and duration_seconds > TENCENT_ASR_CHUNK_SECONDS
    )
    if not requires_chunking:
        return transcribe_with_tencent_flash(
            path,
            voice_format,
            tier=tier,
            language=language,
            record_usage=record_usage,
        )

    STT_TEMP_DIR.mkdir(parents=True, exist_ok=True)
    try:
        with tempfile.TemporaryDirectory(
            dir=STT_TEMP_DIR,
            prefix=f"{STT_TEMP_PREFIX}tencent-chunks-",
        ) as directory:
            chunks = create_tencent_audio_chunks(path, Path(directory))
            merged_text = ""
            total_duration_ms = 0.0
            for index, chunk in enumerate(chunks, start=1):
                try:
                    chunk_text, payload = transcribe_with_tencent_flash(
                        chunk.path,
                        "wav",
                        tier=tier,
                        language=language,
                        record_usage=record_usage,
                    )
                except Exception as exc:
                    raise TencentChunkedTranscriptionError(
                        f"第 {index}/{len(chunks)} 段云端转写失败: {sanitize_upstream_error(exc)}"
                    ) from exc
                merged_text = merge_chunk_transcript_text(merged_text, chunk_text)
                chunk_duration_ms = chunk.duration_seconds * 1000
                with contextlib.suppress(TypeError, ValueError):
                    chunk_duration_ms = max(
                        0.0,
                        float(payload.get("audio_duration") or 0),
                    ) or chunk_duration_ms
                total_duration_ms += chunk_duration_ms
            if not merged_text:
                raise TencentChunkedTranscriptionError("分段云端转写未返回有效文字")
            push_debug_event(
                "cloud_asr_chunked",
                chunk_count=len(chunks),
                source_bytes=path.stat().st_size,
                provider=tencent_tier_config(tier).model_id if tier else "tencent-cloud",
            )
            return merged_text, {
                "code": 0,
                "audio_duration": round(total_duration_ms),
                "chunked": True,
                "chunk_count": len(chunks),
            }
    except TencentChunkedTranscriptionError:
        raise
    except Exception as exc:
        raise TencentChunkedTranscriptionError(
            f"超大录音分段处理失败: {sanitize_upstream_error(exc)}"
        ) from exc


def build_tencent_realtime_request(
    *,
    voice_id: str,
    tier: str | None = None,
    language: str = "zh",
    timestamp: int | None = None,
    nonce: int | None = None,
) -> tuple[str, str]:
    request_timestamp = int(time.time()) if timestamp is None else int(timestamp)
    request_nonce = secrets.randbelow(2_147_483_647) if nonce is None else int(nonce)
    engine_type = tencent_engine_type_for_language(
        tier=tier,
        language=language,
        realtime=True,
    )
    params = {
        "convert_num_mode": "1",
        "engine_model_type": engine_type,
        "expired": str(request_timestamp + TENCENT_REALTIME_ASR_SIGNATURE_TTL_SEC),
        "filter_dirty": "0",
        "filter_modal": "0",
        "filter_punc": "0",
        "needvad": "1",
        "nonce": str(request_nonce),
        "secretid": TENCENT_ASR_SECRET_ID,
        "timestamp": str(request_timestamp),
        "voice_format": "1",
        "voice_id": voice_id,
        "word_info": "0",
    }
    query = urlencode(sorted(params.items()))
    request_url = f"{TENCENT_REALTIME_ASR_BASE_URL}/{quote(TENCENT_ASR_APP_ID, safe='')}?{query}"
    parsed = urlparse(request_url)
    signature_source = f"{parsed.netloc}{parsed.path}?{parsed.query}"
    digest = hmac.new(
        TENCENT_ASR_SECRET_KEY.encode("utf-8"),
        signature_source.encode("utf-8"),
        hashlib.sha1,
    ).digest()
    signature = base64.b64encode(digest).decode("ascii")
    return f"{request_url}&signature={quote(signature, safe='')}", signature_source


@dataclass
class TencentRealtimeTranscriptState:
    stable_sentences: dict[int, str] = field(default_factory=dict)
    preview_index: int | None = None
    preview_text: str = ""
    final: bool = False

    def apply(self, payload: dict[str, Any]) -> tuple[str, str, bool]:
        code = int(payload.get("code", -1))
        if code != 0:
            detail = str(payload.get("message") or "Tencent realtime ASR request failed").strip()
            raise ValueError(f"Tencent realtime ASR error {code}: {detail}")

        result = payload.get("result")
        if isinstance(result, dict):
            slice_type = int(result.get("slice_type", -1))
            index = int(result.get("index", 0))
            text = normalize_preview_text(str(result.get("voice_text_str") or ""))
            if slice_type == 2:
                if text:
                    self.stable_sentences[index] = text
                if self.preview_index == index:
                    self.preview_index = None
                    self.preview_text = ""
            elif slice_type in {0, 1}:
                self.preview_index = index
                self.preview_text = text

        self.final = self.final or int(payload.get("final", 0)) == 1
        committed = normalize_preview_text(
            " ".join(self.stable_sentences[index] for index in sorted(self.stable_sentences))
        )
        return committed, self.preview_text, self.final


def sanitize_upstream_error(error: Exception | str) -> str:
    message = str(error).strip() or "upstream request failed"
    message = re.sub(r"(?:wss|https)://[^\s]+", "智悟增强云模型服务地址", message)
    message = re.sub(r"Tencent(?: Cloud)?(?: realtime)? ASR", "智悟增强云模型", message, flags=re.IGNORECASE)
    for sensitive_value in (
        TENCENT_ASR_SECRET_ID,
        TENCENT_ASR_SECRET_KEY,
        TENCENT_ASR_APP_ID,
    ):
        if sensitive_value:
            message = message.replace(sensitive_value, "[redacted]")
    return message[:300]


def tencent_usage_timezone() -> ZoneInfo:
    try:
        return ZoneInfo(TENCENT_ASR_USAGE_TIMEZONE)
    except ZoneInfoNotFoundError:
        return ZoneInfo("Asia/Shanghai")


def tencent_usage_account_key() -> str:
    account_identity = f"{TENCENT_ASR_APP_ID}:{TENCENT_ASR_SECRET_ID}"
    return hashlib.sha256(account_identity.encode("utf-8")).hexdigest()[:32]


def _initialize_tencent_usage_ledger() -> None:
    global _tencent_usage_ledger_initialized
    if _tencent_usage_ledger_initialized:
        return
    TENCENT_ASR_USAGE_LEDGER_PATH.parent.mkdir(parents=True, exist_ok=True)
    with _sqlite_connection(TENCENT_ASR_USAGE_LEDGER_PATH) as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS tencent_asr_usage (
                account_key TEXT NOT NULL,
                usage_month TEXT NOT NULL,
                business_name TEXT NOT NULL,
                duration_millis INTEGER NOT NULL DEFAULT 0,
                request_count INTEGER NOT NULL DEFAULT 0,
                updated_at TEXT NOT NULL,
                PRIMARY KEY(account_key, usage_month, business_name)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS tencent_asr_budget_baseline (
                account_key TEXT NOT NULL,
                usage_month TEXT NOT NULL,
                tier TEXT NOT NULL,
                business_name TEXT NOT NULL,
                duration_millis INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                PRIMARY KEY(account_key, usage_month, tier, business_name)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS tencent_asr_budget_reservation (
                reservation_id TEXT PRIMARY KEY,
                account_key TEXT NOT NULL,
                usage_month TEXT NOT NULL,
                tier TEXT NOT NULL,
                business_name TEXT NOT NULL,
                reserved_millis INTEGER NOT NULL,
                consumed_millis INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
    _tencent_usage_ledger_initialized = True


def record_local_tencent_asr_usage(
    business_name: str,
    duration_seconds: float,
    *,
    request_count: int = 1,
    now: datetime | None = None,
) -> None:
    global tencent_usage_cache
    if (
        not TENCENT_ASR_USAGE_LEDGER_ENABLED
        or business_name not in {"asr_rt", "asr_rec"}
    ):
        return
    zone = tencent_usage_timezone()
    local_now = now or datetime.now(zone)
    if local_now.tzinfo is None:
        local_now = local_now.replace(tzinfo=zone)
    else:
        local_now = local_now.astimezone(zone)
    duration_millis = max(0, math.ceil(max(0.0, duration_seconds) * 1000))
    safe_request_count = max(0, int(request_count))
    if duration_millis == 0 and safe_request_count == 0:
        return
    with tencent_usage_cache_lock:
        with tencent_usage_ledger_lock:
            _initialize_tencent_usage_ledger()
            with _sqlite_connection(TENCENT_ASR_USAGE_LEDGER_PATH) as conn:
                conn.execute(
                    """
                    INSERT INTO tencent_asr_usage (
                        account_key, usage_month, business_name,
                        duration_millis, request_count, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_key, usage_month, business_name)
                    DO UPDATE SET
                        duration_millis = duration_millis + excluded.duration_millis,
                        request_count = request_count + excluded.request_count,
                        updated_at = excluded.updated_at
                    """,
                    (
                        tencent_usage_account_key(),
                        local_now.strftime("%Y-%m"),
                        business_name,
                        duration_millis,
                        safe_request_count,
                        utc_now(),
                    ),
                )
        tencent_usage_cache = None


def read_local_tencent_asr_usage(usage_month: str) -> list[dict[str, Any]]:
    if not TENCENT_ASR_USAGE_LEDGER_ENABLED:
        return []
    with tencent_usage_ledger_lock:
        _initialize_tencent_usage_ledger()
        with _sqlite_connection(TENCENT_ASR_USAGE_LEDGER_PATH) as conn:
            rows = conn.execute(
                """
                SELECT business_name, duration_millis, request_count
                FROM tencent_asr_usage
                WHERE account_key = ? AND usage_month = ?
                """,
                (tencent_usage_account_key(), usage_month),
            ).fetchall()
    return [
        {
            "business_name": str(row[0]),
            "duration_seconds": math.ceil(max(0, int(row[1])) / 1000),
            "count": max(0, int(row[2])),
        }
        for row in rows
    ]


@dataclass(frozen=True)
class TencentAsrBudgetReservation:
    reservation_id: str
    tier: str
    business_name: str
    reserved_seconds: int


def _tencent_budget_month(now: datetime | None = None) -> str:
    zone = tencent_usage_timezone()
    local_now = now or datetime.now(zone)
    if local_now.tzinfo is None:
        local_now = local_now.replace(tzinfo=zone)
    else:
        local_now = local_now.astimezone(zone)
    return local_now.strftime("%Y-%m")


def _budget_baseline_millis(
    conn: sqlite3.Connection,
    *,
    account_key: str,
    usage_month: str,
    tier: str,
    business_name: str,
) -> int:
    row = conn.execute(
        """
        SELECT duration_millis FROM tencent_asr_budget_baseline
        WHERE account_key = ? AND usage_month = ? AND tier = ? AND business_name = ?
        """,
        (account_key, usage_month, tier, business_name),
    ).fetchone()
    if row is not None:
        return max(0, int(row[0]))
    duration_millis = 0
    if tier == TENCENT_LEGACY_USAGE_TIER:
        historical = conn.execute(
            """
            SELECT duration_millis FROM tencent_asr_usage
            WHERE account_key = ? AND usage_month = ? AND business_name = ?
            """,
            (account_key, usage_month, business_name),
        ).fetchone()
        duration_millis = max(0, int(historical[0])) if historical is not None else 0
    conn.execute(
        """
        INSERT INTO tencent_asr_budget_baseline (
            account_key, usage_month, tier, business_name, duration_millis, created_at
        ) VALUES (?, ?, ?, ?, ?, ?)
        """,
        (account_key, usage_month, tier, business_name, duration_millis, utc_now()),
    )
    return duration_millis


def reserve_tencent_asr_budget(
    tier: str,
    business_name: str,
    duration_seconds: float,
    *,
    allow_partial: bool = False,
    now: datetime | None = None,
) -> TencentAsrBudgetReservation:
    if business_name not in {"asr_rt", "asr_rec"}:
        raise ValueError("Unsupported Tencent ASR business")
    config = tencent_tier_config(tier)
    if config.monthly_limit_sec <= 0:
        raise RuntimeError(f"{config.display_name} is disabled by the server budget policy")
    reserved_millis = max(1, math.ceil(max(0.0, duration_seconds) * 1000))
    month = _tencent_budget_month(now)
    account_key = tencent_usage_account_key()
    reservation_id = uuid4().hex
    with tencent_usage_ledger_lock:
        _initialize_tencent_usage_ledger()
        with _sqlite_connection(TENCENT_ASR_USAGE_LEDGER_PATH) as conn:
            conn.execute("BEGIN IMMEDIATE")
            baseline_millis = _budget_baseline_millis(
                conn,
                account_key=account_key,
                usage_month=month,
                tier=config.key,
                business_name=business_name,
            )
            row = conn.execute(
                """
                SELECT COALESCE(SUM(CASE
                    WHEN status = 'active' THEN reserved_millis
                    WHEN status = 'settled' THEN consumed_millis
                    ELSE 0 END), 0)
                FROM tencent_asr_budget_reservation
                WHERE account_key = ? AND usage_month = ? AND tier = ? AND business_name = ?
                """,
                (account_key, month, config.key, business_name),
            ).fetchone()
            allocated_millis = baseline_millis + max(0, int(row[0] or 0))
            limit_millis = config.monthly_limit_sec * 1000
            available_millis = max(0, limit_millis - allocated_millis)
            if reserved_millis > available_millis:
                if allow_partial and available_millis > 0:
                    reserved_millis = available_millis
                else:
                    raise RuntimeError(
                        f"{config.display_name} monthly budget is exhausted; switched to local STT"
                    )
            if reserved_millis <= 0:
                raise RuntimeError(
                    f"{config.display_name} monthly budget is exhausted; switched to local STT"
                )
            now_text = utc_now()
            conn.execute(
                """
                INSERT INTO tencent_asr_budget_reservation (
                    reservation_id, account_key, usage_month, tier, business_name,
                    reserved_millis, consumed_millis, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 0, 'active', ?, ?)
                """,
                (
                    reservation_id,
                    account_key,
                    month,
                    config.key,
                    business_name,
                    reserved_millis,
                    now_text,
                    now_text,
                ),
            )
    return TencentAsrBudgetReservation(
        reservation_id=reservation_id,
        tier=config.key,
        business_name=business_name,
        reserved_seconds=math.ceil(reserved_millis / 1000),
    )


def release_tencent_asr_budget(reservation: TencentAsrBudgetReservation | None) -> None:
    if reservation is None:
        return
    with tencent_usage_ledger_lock:
        _initialize_tencent_usage_ledger()
        with _sqlite_connection(TENCENT_ASR_USAGE_LEDGER_PATH) as conn:
            conn.execute(
                """
                UPDATE tencent_asr_budget_reservation
                SET status = 'released', updated_at = ?
                WHERE reservation_id = ? AND status = 'active'
                """,
                (utc_now(), reservation.reservation_id),
            )


def settle_tencent_asr_budget(
    reservation: TencentAsrBudgetReservation | None,
    actual_duration_seconds: float,
) -> None:
    if reservation is None:
        return
    consumed_millis = max(0, math.ceil(max(0.0, actual_duration_seconds) * 1000))
    with tencent_usage_ledger_lock:
        _initialize_tencent_usage_ledger()
        with _sqlite_connection(TENCENT_ASR_USAGE_LEDGER_PATH) as conn:
            row = conn.execute(
                """
                SELECT reserved_millis FROM tencent_asr_budget_reservation
                WHERE reservation_id = ? AND status = 'active'
                """,
                (reservation.reservation_id,),
            ).fetchone()
            if row is None:
                return
            conn.execute(
                """
                UPDATE tencent_asr_budget_reservation
                SET consumed_millis = ?, status = 'settled', updated_at = ?
                WHERE reservation_id = ? AND status = 'active'
                """,
                (
                    min(max(0, int(row[0])), consumed_millis),
                    utc_now(),
                    reservation.reservation_id,
                ),
            )


def tencent_asr_budget_summary(now: datetime | None = None) -> dict[str, Any]:
    month = _tencent_budget_month(now)
    account_key = tencent_usage_account_key()
    tiers: list[dict[str, Any]] = []
    with tencent_usage_ledger_lock:
        _initialize_tencent_usage_ledger()
        with _sqlite_connection(TENCENT_ASR_USAGE_LEDGER_PATH) as conn:
            for tier in (TENCENT_STANDARD_TIER, TENCENT_PRECISION_TIER):
                config = tencent_tier_config(tier)
                services = []
                for business_name, display_name in (
                    ("asr_rt", "实时语音识别"),
                    ("asr_rec", "录音文件识别极速版"),
                ):
                    baseline_millis = _budget_baseline_millis(
                        conn,
                        account_key=account_key,
                        usage_month=month,
                        tier=tier,
                        business_name=business_name,
                    )
                    row = conn.execute(
                        """
                        SELECT
                            COALESCE(SUM(CASE WHEN status = 'settled' THEN consumed_millis ELSE 0 END), 0),
                            COALESCE(SUM(CASE WHEN status = 'active' THEN reserved_millis ELSE 0 END), 0)
                        FROM tencent_asr_budget_reservation
                        WHERE account_key = ? AND usage_month = ? AND tier = ? AND business_name = ?
                        """,
                        (account_key, month, tier, business_name),
                    ).fetchone()
                    settled_millis = max(0, int(row[0] or 0))
                    reserved_millis = max(0, int(row[1] or 0))
                    limit_millis = config.monthly_limit_sec * 1000
                    used_millis = baseline_millis + settled_millis
                    remaining_millis = max(0, limit_millis - used_millis - reserved_millis)
                    services.append(
                        {
                            "business_name": business_name,
                            "display_name": display_name,
                            "used_seconds": math.ceil(used_millis / 1000),
                            "reserved_seconds": math.ceil(reserved_millis / 1000),
                            "limit_seconds": config.monthly_limit_sec,
                            "remaining_seconds": math.floor(remaining_millis / 1000),
                        }
                    )
                tiers.append(
                    {
                        "id": tier,
                        "display_name": config.display_name,
                        "paid": config.paid,
                        "flash_enabled": tencent_asr_configured(tier),
                        "realtime_enabled": tencent_realtime_asr_configured(tier),
                        "monthly_limit_sec": config.monthly_limit_sec,
                        "budget_enforced": tencent_asr_budget_enforced(tier),
                        "services": services,
                    }
                )
    return {
        "month": month,
        "source": "standard-usage-reference; precision-budget-ledger",
        "tiers": tiers,
    }


def synchronize_tencent_usage_ledger(
    usage_month: str,
    official_rows: list[dict[str, Any]],
) -> None:
    if not TENCENT_ASR_USAGE_LEDGER_ENABLED:
        return
    totals = {
        "asr_rt": {"duration": 0, "count": 0},
        "asr_rec": {"duration": 0, "count": 0},
    }
    for row in official_rows:
        business_name = str(
            row.get("business_name") or row.get("BizName") or ""
        )
        if business_name not in totals:
            continue
        totals[business_name]["duration"] += max(
            0, int(row.get("duration_seconds") or row.get("Duration") or 0)
        )
        totals[business_name]["count"] += max(
            0, int(row.get("count") or row.get("Count") or 0)
        )
    with tencent_usage_ledger_lock:
        _initialize_tencent_usage_ledger()
        with _sqlite_connection(TENCENT_ASR_USAGE_LEDGER_PATH) as conn:
            for business_name, values in totals.items():
                conn.execute(
                    """
                    INSERT INTO tencent_asr_usage (
                        account_key, usage_month, business_name,
                        duration_millis, request_count, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_key, usage_month, business_name)
                    DO UPDATE SET
                        duration_millis = MAX(
                            duration_millis,
                            excluded.duration_millis
                        ),
                        request_count = MAX(
                            request_count,
                            excluded.request_count
                        ),
                        updated_at = excluded.updated_at
                    """,
                    (
                        tencent_usage_account_key(),
                        usage_month,
                        business_name,
                        values["duration"] * 1000,
                        values["count"],
                        utc_now(),
                    ),
                )


def merge_tencent_asr_usage_rows(
    official_rows: list[dict[str, Any]],
    local_rows: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], bool]:
    def aggregate(rows: list[dict[str, Any]]) -> dict[str, dict[str, int]]:
        totals = {
            "asr_rt": {"duration": 0, "count": 0},
            "asr_rec": {"duration": 0, "count": 0},
        }
        for row in rows:
            name = str(row.get("business_name") or row.get("BizName") or "")
            if name not in totals:
                continue
            totals[name]["duration"] += max(
                0, int(row.get("duration_seconds") or row.get("Duration") or 0)
            )
            totals[name]["count"] += max(
                0, int(row.get("count") or row.get("Count") or 0)
            )
        return totals

    official = aggregate(official_rows)
    local = aggregate(local_rows)
    estimated = False
    merged = []
    for business_name in ("asr_rt", "asr_rec"):
        estimated = estimated or (
            local[business_name]["duration"] > official[business_name]["duration"]
            or local[business_name]["count"] > official[business_name]["count"]
        )
        merged.append(
            {
                "business_name": business_name,
                "duration_seconds": max(
                    official[business_name]["duration"],
                    local[business_name]["duration"],
                ),
                "count": max(
                    official[business_name]["count"],
                    local[business_name]["count"],
                ),
            }
        )
    return merged, estimated


def build_tencent_asr_usage_summary(
    rows: list[dict[str, Any]],
    now: datetime | None = None,
    *,
    source: str = "tencent-cloud-api",
    is_estimated: bool = False,
    local_rows: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    zone = tencent_usage_timezone()
    local_now = now or datetime.now(zone)
    if local_now.tzinfo is None:
        local_now = local_now.replace(tzinfo=zone)
    else:
        local_now = local_now.astimezone(zone)

    totals: dict[str, dict[str, int]] = {
        "asr_rt": {"duration": 0, "count": 0},
        "asr_rec": {"duration": 0, "count": 0},
    }
    for row in rows:
        business_name = str(row.get("business_name") or row.get("BizName") or "")
        if business_name not in totals:
            continue
        totals[business_name]["duration"] += max(
            0, int(row.get("duration_seconds") or row.get("Duration") or 0)
        )
        totals[business_name]["count"] += max(0, int(row.get("count") or row.get("Count") or 0))

    local_totals: dict[str, dict[str, int]] = {
        "asr_rt": {"duration": 0, "count": 0},
        "asr_rec": {"duration": 0, "count": 0},
    }
    for row in local_rows or []:
        business_name = str(row.get("business_name") or row.get("BizName") or "")
        if business_name not in local_totals:
            continue
        local_totals[business_name]["duration"] += max(
            0, int(row.get("duration_seconds") or row.get("Duration") or 0)
        )
        local_totals[business_name]["count"] += max(
            0, int(row.get("count") or row.get("Count") or 0)
        )

    definitions = (
        ("realtime", "asr_rt", "实时语音识别", TENCENT_REALTIME_MONTHLY_FREE_SEC),
        ("flash", "asr_rec", "录音文件识别极速版", TENCENT_FLASH_MONTHLY_FREE_SEC),
    )
    services = []
    for service_id, business_name, display_name, free_seconds in definitions:
        used_seconds = totals[business_name]["duration"]
        remaining_seconds = max(0, free_seconds - used_seconds)
        pending_seconds = max(0, local_totals[business_name]["duration"] - used_seconds)
        pending_count = max(0, local_totals[business_name]["count"] - totals[business_name]["count"])
        services.append(
            {
                "id": service_id,
                "business_name": business_name,
                "display_name": display_name,
                "used_seconds": used_seconds,
                "free_seconds": free_seconds,
                "remaining_seconds": remaining_seconds,
                "usage_ratio": round(min(1.0, used_seconds / free_seconds), 6),
                "request_count": totals[business_name]["count"],
                "pending_local_seconds": pending_seconds,
                "pending_local_request_count": pending_count,
            }
        )

    hybrid_remaining_seconds = min(item["remaining_seconds"] for item in services)
    warning_level = "normal"
    if hybrid_remaining_seconds <= 0:
        warning_level = "exhausted"
    elif hybrid_remaining_seconds <= 15 * 60:
        warning_level = "critical"
    elif hybrid_remaining_seconds <= 60 * 60:
        warning_level = "low"

    if local_now.month == 12:
        next_reset = local_now.replace(
            year=local_now.year + 1, month=1, day=1, hour=0, minute=0, second=0, microsecond=0
        )
    else:
        next_reset = local_now.replace(
            month=local_now.month + 1, day=1, hour=0, minute=0, second=0, microsecond=0
        )
    return {
        "month": local_now.strftime("%Y-%m"),
        "period_start": local_now.replace(day=1).strftime("%Y-%m-%d"),
        "period_end": local_now.strftime("%Y-%m-%d"),
        "timezone": str(zone),
        "source": source,
        "is_estimated": is_estimated,
        "scope": "tencent-account",
        "reporting_note": "Cloud console statistics can be delayed; local pending usage is shown separately.",
        "updated_at": utc_now(),
        "next_reset_at": next_reset.isoformat(timespec="seconds"),
        "hybrid_remaining_seconds": hybrid_remaining_seconds,
        "warning_level": warning_level,
        "services": services,
    }


def fetch_tencent_asr_usage(
    now: datetime | None = None,
    *,
    force_refresh: bool = False,
) -> dict[str, Any]:
    global tencent_usage_cache
    if not TENCENT_ASR_USAGE_ENABLED:
        raise RuntimeError("Tencent ASR usage query is disabled")
    if not tencent_asr_usage_configured():
        raise RuntimeError("Tencent Cloud ASR usage query is not configured")

    zone = tencent_usage_timezone()
    local_now = now or datetime.now(zone)
    if local_now.tzinfo is None:
        local_now = local_now.replace(tzinfo=zone)
    else:
        local_now = local_now.astimezone(zone)
    usage_month = local_now.strftime("%Y-%m")

    with tencent_usage_cache_lock:
        if not force_refresh and tencent_usage_cache is not None:
            cached_at, cached_month, cached_value = tencent_usage_cache
            if cached_month == usage_month and time.monotonic() - cached_at < TENCENT_ASR_USAGE_CACHE_SEC:
                return cached_value

        official_error = ""
        rows: list[dict[str, Any]] = []
        try:
            from tencentcloud.asr.v20190614 import asr_client, models as asr_models
            from tencentcloud.common import credential
            from tencentcloud.common.profile.client_profile import ClientProfile
            from tencentcloud.common.profile.http_profile import HttpProfile

            credentials = credential.Credential(TENCENT_ASR_SECRET_ID, TENCENT_ASR_SECRET_KEY)
            http_profile = HttpProfile()
            http_profile.endpoint = TENCENT_ASR_USAGE_API_ENDPOINT
            client_profile = ClientProfile()
            client_profile.httpProfile = http_profile
            client = asr_client.AsrClient(credentials, TENCENT_ASR_USAGE_REGION, client_profile)
            request = asr_models.GetUsageByDateRequest()
            request.BizNameList = ["asr_rt", "asr_rec"]
            request.StartDate = local_now.replace(day=1).strftime("%Y-%m-%d")
            request.EndDate = local_now.strftime("%Y-%m-%d")
            response = client.GetUsageByDate(request)
            usage_rows = getattr(
                getattr(response, "Data", None),
                "UsageByDateInfoList",
                None,
            ) or []
            rows = [
                {
                    "business_name": getattr(item, "BizName", ""),
                    "duration_seconds": getattr(item, "Duration", 0),
                    "count": getattr(item, "Count", 0),
                }
                for item in usage_rows
            ]
        except Exception as exc:
            official_error = sanitize_upstream_error(exc)
        if not official_error:
            synchronize_tencent_usage_ledger(usage_month, rows)
        local_rows = read_local_tencent_asr_usage(usage_month)
        if official_error and not local_rows:
            raise RuntimeError(official_error)
        summary_rows = local_rows if official_error else rows
        source = "server-ledger" if official_error else "tencent-cloud-api"
        summary = build_tencent_asr_usage_summary(
            summary_rows,
            local_now,
            source=source,
            is_estimated=bool(official_error),
            local_rows=[] if official_error else local_rows,
        )
        if official_error:
            summary["official_sync_error"] = official_error
        tencent_usage_cache = (time.monotonic(), usage_month, summary)
        return summary


class TencentRealtimeBridge:
    def __init__(
        self,
        on_update,
        on_failure,
        *,
        tier: str,
        language: str = "zh",
    ):
        self.on_update = on_update
        self.on_failure = on_failure
        self.tier = tier
        self.language = normalize_stt_language(language)
        self.voice_id = uuid4().hex
        self.connection = None
        self.sender_task: asyncio.Task | None = None
        self.receiver_task: asyncio.Task | None = None
        self.failed = False
        self.failure_message = ""
        self.semaphore_acquired = False
        self.audio_bytes = 0
        self.frame_bytes = int(16000 * 2 * TENCENT_REALTIME_ASR_FRAME_MS / 1000)
        self.pending_audio = bytearray()
        frames_per_second = max(1, math.ceil(1000 / TENCENT_REALTIME_ASR_FRAME_MS))
        self.audio_queue: asyncio.Queue[bytes | None] = asyncio.Queue(
            maxsize=max(1, TENCENT_REALTIME_ASR_QUEUE_SEC * frames_per_second)
        )

    async def start(self) -> None:
        try:
            await asyncio.wait_for(
                tencent_realtime_asr_semaphore.acquire(),
                timeout=TENCENT_REALTIME_ASR_ACQUIRE_TIMEOUT_SEC,
            )
        except asyncio.TimeoutError as exc:
            raise RuntimeError("智悟增强云模型当前并发繁忙，已切换至智悟本地模型") from exc
        self.semaphore_acquired = True
        request_url, _ = build_tencent_realtime_request(
            voice_id=self.voice_id,
            tier=self.tier,
            language=self.language,
        )
        try:
            self.connection = await asyncio.wait_for(
                websockets.connect(
                    request_url,
                    open_timeout=TENCENT_REALTIME_ASR_CONNECT_TIMEOUT_SEC,
                    close_timeout=TENCENT_REALTIME_ASR_FINAL_TIMEOUT_SEC,
                    ping_interval=None,
                    max_size=1024 * 1024,
                ),
                timeout=TENCENT_REALTIME_ASR_CONNECT_TIMEOUT_SEC,
            )
            handshake_raw = await asyncio.wait_for(
                self.connection.recv(), timeout=TENCENT_REALTIME_ASR_CONNECT_TIMEOUT_SEC
            )
            handshake = json.loads(handshake_raw)
            if not isinstance(handshake, dict):
                raise ValueError("Tencent realtime ASR returned an invalid handshake")
            TencentRealtimeTranscriptState().apply(handshake)
            self.sender_task = asyncio.create_task(self._send_audio(), name=f"tencent-send:{self.voice_id}")
            self.receiver_task = asyncio.create_task(self._receive_results(), name=f"tencent-recv:{self.voice_id}")
        except Exception:
            await self.abort()
            raise

    async def feed(self, pcm_bytes: bytes) -> None:
        if not pcm_bytes or self.failed or self.connection is None:
            return
        self.pending_audio.extend(pcm_bytes)
        self.audio_bytes += len(pcm_bytes)
        while len(self.pending_audio) >= self.frame_bytes and not self.failed:
            frame = bytes(self.pending_audio[: self.frame_bytes])
            del self.pending_audio[: self.frame_bytes]
            if not await self._enqueue_audio(frame):
                return

    async def finish(self) -> None:
        if self.connection is None:
            self._release_semaphore()
            return
        if self.failed:
            await self.abort()
            return
        if self.sender_task is not None and not self.sender_task.done():
            if self.pending_audio:
                if not await self._enqueue_audio(bytes(self.pending_audio)):
                    return
                self.pending_audio.clear()
            if not await self._enqueue_audio(None):
                return
            with contextlib.suppress(asyncio.TimeoutError, asyncio.CancelledError, Exception):
                await asyncio.wait_for(
                    self.sender_task,
                    timeout=TENCENT_REALTIME_ASR_FINAL_TIMEOUT_SEC,
                )
        if self.receiver_task is not None and not self.receiver_task.done():
            with contextlib.suppress(asyncio.TimeoutError, asyncio.CancelledError, Exception):
                await asyncio.wait_for(
                    self.receiver_task,
                    timeout=TENCENT_REALTIME_ASR_FINAL_TIMEOUT_SEC,
                )
        await self._close_connection()
        self.pending_audio.clear()
        self._release_semaphore()

    async def _enqueue_audio(self, frame: bytes | None) -> bool:
        try:
            await asyncio.wait_for(
                self.audio_queue.put(frame),
                timeout=TENCENT_REALTIME_ASR_BACKPRESSURE_TIMEOUT_SEC,
            )
            return True
        except asyncio.TimeoutError:
            await self._fail(
                RuntimeError("Tencent realtime ASR upstream remained congested")
            )
            return False

    async def abort(self) -> None:
        for task in (self.sender_task, self.receiver_task):
            if task is not None and not task.done():
                task.cancel()
        for task in (self.sender_task, self.receiver_task):
            if task is not None:
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await task
        await self._close_connection()
        self._release_semaphore()

    async def _send_audio(self) -> None:
        frame_interval = TENCENT_REALTIME_ASR_FRAME_MS / 1000.0
        next_send_at = time.monotonic()
        try:
            while True:
                frame = await self.audio_queue.get()
                if frame is None:
                    break
                delay = next_send_at - time.monotonic()
                if delay > 0:
                    await asyncio.sleep(delay)
                await self.connection.send(frame)
                next_send_at = max(next_send_at + frame_interval, time.monotonic())
            await self.connection.send(json.dumps({"type": "end"}))
        except Exception as exc:
            await self._fail(exc)

    async def _receive_results(self) -> None:
        state = TencentRealtimeTranscriptState()
        try:
            async for raw in self.connection:
                payload = json.loads(raw)
                if not isinstance(payload, dict):
                    raise ValueError("Tencent realtime ASR returned invalid JSON")
                committed, preview, final = state.apply(payload)
                if committed or preview:
                    await self.on_update(committed, preview)
                if final:
                    break
        except Exception as exc:
            await self._fail(exc)

    async def _fail(self, exc: Exception) -> None:
        if self.failed:
            return
        self.failed = True
        self.failure_message = sanitize_upstream_error(exc)
        current_task = asyncio.current_task()
        for task in (self.sender_task, self.receiver_task):
            if task is not None and task is not current_task and not task.done():
                task.cancel()
        await self._close_connection()
        self._release_semaphore()
        await self.on_failure(self.failure_message)

    async def _close_connection(self) -> None:
        if self.connection is not None:
            with contextlib.suppress(Exception):
                await self.connection.close()
            self.connection = None

    def _release_semaphore(self) -> None:
        if self.semaphore_acquired:
            tencent_realtime_asr_semaphore.release()
            self.semaphore_acquired = False


def reserve_stream_session(session_id: str, owner_id: str) -> str | None:
    if len(active_stream_sessions) >= STT_MAX_STREAMS:
        return "total"
    if active_stream_owners.get(owner_id, 0) >= STT_MAX_STREAMS_PER_OWNER:
        return "owner"
    active_stream_sessions.add(session_id)
    active_stream_owners[owner_id] = active_stream_owners.get(owner_id, 0) + 1
    return None


def release_stream_session(session_id: str, owner_id: str) -> None:
    if session_id not in active_stream_sessions:
        return
    active_stream_sessions.discard(session_id)
    remaining = active_stream_owners.get(owner_id, 0) - 1
    if remaining > 0:
        active_stream_owners[owner_id] = remaining
    else:
        active_stream_owners.pop(owner_id, None)

# Streaming transcription parameters (can be overridden per-session via WebSocket start event)
STREAM_UPDATE_INTERVAL_SEC = float(os.getenv("STREAM_UPDATE_INTERVAL_SEC", "0.6"))
STREAM_MIN_AUDIO_SEC = float(os.getenv("STREAM_MIN_AUDIO_SEC", "0.4"))
STREAM_DEBUG_EVENT_LIMIT = int(os.getenv("STREAM_DEBUG_EVENT_LIMIT", "200"))
# Prefer an incomplete preview over displaying low-confidence environmental noise.
STREAM_MIN_CONFIDENCE = float(os.getenv("STREAM_MIN_CONFIDENCE", "-0.90"))
STREAM_MAX_NO_SPEECH_PROB = float(os.getenv("STREAM_MAX_NO_SPEECH_PROB", "0.35"))
# 连续相同结果帧数达到此值则提前 commit，越小稳定越快，默认 2，建议 1-3
STREAM_STABLE_FRAMES = int(os.getenv("STREAM_STABLE_FRAMES", "2"))
# Retained for protocol compatibility. Filtered Faster-Whisper text never fails open.
STREAM_REJECT_FALLBACK_THRESHOLD = int(os.getenv("STREAM_REJECT_FALLBACK_THRESHOLD", "3"))
STREAM_MAX_SNAPSHOT_SEC = float(os.getenv("STREAM_MAX_SNAPSHOT_SEC", "8"))
STREAM_BUFFER_SEC = max(STREAM_MAX_SNAPSHOT_SEC, float(os.getenv("STREAM_BUFFER_SEC", "24")))
STREAM_OVERLAP_SEC = min(
    STREAM_MAX_SNAPSHOT_SEC / 2,
    max(0.0, float(os.getenv("STREAM_OVERLAP_SEC", "4"))),
)
STREAM_BEAM_SIZE = positive_int_env("STREAM_BEAM_SIZE", 1)
STREAM_FINAL_COMPAT_MIN_AUDIO_SEC = max(
    STREAM_MIN_AUDIO_SEC,
    float(os.getenv("STREAM_FINAL_COMPAT_MIN_AUDIO_SEC", "2")),
)
STREAM_STABLE_SIMILARITY = float(os.getenv("STREAM_STABLE_SIMILARITY", "0.70"))
STREAM_STABLE_MIN_CHARS = positive_int_env("STREAM_STABLE_MIN_CHARS", 4)
STREAM_PROVISIONAL_MIN_CHARS = positive_int_env("STREAM_PROVISIONAL_MIN_CHARS", 2)
STREAM_STEP_SEC = min(
    STREAM_MAX_SNAPSHOT_SEC,
    max(STREAM_MIN_AUDIO_SEC, float(os.getenv("STREAM_STEP_SEC", "4"))),
)
STREAM_HALLUCINATION_PHRASES = [
    phrase.strip()
    for phrase in os.getenv(
        "STREAM_HALLUCINATION_PHRASES",
        "字幕by索兰娅,字幕製作人Zither Harp,字幕制作人Zither Harp,字幕製作人,字幕制作人,Zither Harp"
    ).split(",")
    if phrase.strip()
]
FINAL_PROMPT_ECHO_PHRASES = (
    "以下是普通话会议记录，请使用规范中文标点",
    "请使用规范中文标点",
    "以下是普通话会议记录",
)
stream_debug_events: deque[dict[str, Any]] = deque(maxlen=STREAM_DEBUG_EVENT_LIMIT)
simplified_chinese = OpenCC("t2s")


class TranscribeResponse(BaseModel):
    text: str
    language: str = "zh"


class SwitchSTTRequest(BaseModel):
    engine: str
    model: str | None = None


@dataclass(frozen=True)
class ApiPrincipal:
    owner_id: str
    is_management: bool = False


class ArchivedAudioResponse(BaseModel):
    id: str
    meeting_id: str
    created_at: str
    bytes: int
    duration_sec: float | None = None
    filename: str
    source: str
    sha256: str = ""
    download_path: str


class ArchivedAudioListResponse(BaseModel):
    items: list[ArchivedAudioResponse]


@dataclass
class StreamRecording:
    path: Path
    ready: asyncio.Event
    created_at: float
    owner_id: str
    meeting_id: str = ""
    language: str = "zh"
    audio_bytes: int = 0
    claimed: bool = False
    archive_id: str | None = None
    stream_provider: str = LOCAL_STREAM_PROVIDER
    final_provider: str = "faster-whisper"
    realtime_error: str = ""


def utc_now() -> str:
    return datetime.utcnow().isoformat(timespec="seconds") + "Z"


def new_temp_file(suffix: str):
    STT_TEMP_DIR.mkdir(parents=True, exist_ok=True)
    return tempfile.NamedTemporaryFile(
        delete=False,
        dir=STT_TEMP_DIR,
        prefix=STT_TEMP_PREFIX,
        suffix=suffix,
    )


def cleanup_stale_temp_files(now: float | None = None) -> int:
    STT_TEMP_DIR.mkdir(parents=True, exist_ok=True)
    cutoff = (now if now is not None else time.time()) - STT_TEMP_MAX_AGE_SEC
    removed = 0
    for path in STT_TEMP_DIR.glob(f"{STT_TEMP_PREFIX}*"):
        try:
            if path.is_file() and path.stat().st_mtime < cutoff:
                path.unlink()
                removed += 1
        except OSError:
            continue
    return removed


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_archive_meeting_id(meeting_id: str | None) -> str:
    value = (meeting_id or "").strip()
    if not value:
        return ""
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", value):
        raise ValueError("Invalid meeting id for audio archive")
    return value


def normalize_archive_key(archive_key: str | None) -> str:
    value = (archive_key or "").strip()
    if not value:
        return ""
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", value):
        raise ValueError("Invalid audio archive key")
    return value


def archive_owner_dir(owner_id: str) -> Path:
    owner_key = hashlib.sha256(owner_id.encode("utf-8")).hexdigest()[:32]
    return STT_AUDIO_ARCHIVE_DIR / owner_key


def wav_duration_sec(path: Path) -> float | None:
    if path.suffix.lower() != ".wav":
        return None
    try:
        with wave.open(str(path), "rb") as reader:
            frame_rate = reader.getframerate()
            return round(reader.getnframes() / frame_rate, 3) if frame_rate > 0 else None
    except (EOFError, OSError, wave.Error):
        return None


def audio_duration_for_tencent_budget(path: Path) -> float | None:
    duration = wav_duration_sec(path)
    if duration is not None and duration > 0:
        return duration
    try:
        probe = subprocess.run(
            [
                "ffprobe",
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                str(path),
            ],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
        duration = float(probe.stdout.strip())
        return duration if math.isfinite(duration) and duration > 0 else None
    except (OSError, subprocess.SubprocessError, ValueError):
        return None


def archive_audio_file(
    source_path: str | Path,
    *,
    owner_id: str,
    meeting_id: str | None,
    source_kind: str,
    original_filename: str | None = None,
    archive_key: str | None = None,
) -> dict[str, Any] | None:
    if not STT_AUDIO_ARCHIVE_ENABLED:
        return None
    normalized_meeting_id = normalize_archive_meeting_id(meeting_id)
    normalized_archive_key = normalize_archive_key(archive_key)
    if not normalized_meeting_id:
        return None
    source = Path(source_path)
    if not source.is_file() or source.stat().st_size <= 0:
        return None

    target_dir = archive_owner_dir(owner_id) / normalized_meeting_id
    target_dir.mkdir(parents=True, exist_ok=True)
    source_bytes = source.stat().st_size
    source_sha256 = sha256_file(source)
    source_duration = audio_duration_for_tencent_budget(source)

    with audio_archive_lock:
        existing_entries = [
            (metadata_path, metadata)
            for metadata_path in target_dir.glob("*.json")
            if (metadata := read_archive_metadata(metadata_path)) is not None
        ]
        if existing_entries:
            canonical_path, canonical = max(
                existing_entries,
                key=lambda entry: archive_quality_key(entry[1]),
            )
            for metadata_path, metadata in existing_entries:
                if metadata_path != canonical_path:
                    remove_archive_entry_unlocked(metadata_path, metadata)

            canonical_duration = archive_duration_sec(canonical)
            incoming_is_longer = source_duration is not None and (
                canonical_duration is None or source_duration > canonical_duration + 0.5
            )
            same_format_is_larger = (
                source_duration is None
                and canonical_duration is None
                and safe_audio_suffix(original_filename or source.name)
                == Path(str(canonical.get("_audio_path", ""))).suffix.lower()
                and source_bytes > int(canonical.get("bytes", 0))
            )
            if not incoming_is_longer and not same_format_is_larger:
                return public_archive_metadata(canonical)

            archive_id = str(canonical["id"])
            suffix = safe_audio_suffix(original_filename or source.name)
            audio_path = target_dir / f"{archive_id}{suffix}"
            old_audio_path = Path(str(canonical["_audio_path"]))
            partial_path = target_dir / f".{archive_id}{suffix}.part"
            shutil.copyfile(source, partial_path)
            os.replace(partial_path, audio_path)
            if old_audio_path != audio_path:
                old_audio_path.unlink(missing_ok=True)
            updated = {
                **public_archive_metadata(canonical),
                "schema": 3,
                "archive_key": normalized_archive_key or str(canonical.get("archive_key", "")),
                "updated_at": utc_now(),
                "bytes": audio_path.stat().st_size,
                "sha256": source_sha256,
                "duration_sec": source_duration,
                "source": source_kind,
                "audio_file": audio_path.name,
            }
            metadata_partial = target_dir / f".{archive_id}.json.part"
            metadata_partial.write_text(
                json.dumps(updated, ensure_ascii=False, separators=(",", ":")),
                encoding="utf-8",
            )
            os.replace(metadata_partial, canonical_path)
            return updated

        archive_id = uuid4().hex
        suffix = safe_audio_suffix(original_filename or source.name)
        audio_path = target_dir / f"{archive_id}{suffix}"
        partial_path = target_dir / f".{archive_id}{suffix}.part"
        shutil.copyfile(source, partial_path)
        os.replace(partial_path, audio_path)
        created_at = utc_now()
        timestamp = datetime.utcnow().strftime("%Y%m%d-%H%M%S")
        metadata = {
            "schema": 3,
            "id": archive_id,
            "owner_id": owner_id,
            "meeting_id": normalized_meeting_id,
            "archive_key": normalized_archive_key,
            "created_at": created_at,
            "bytes": audio_path.stat().st_size,
            "sha256": source_sha256,
            "duration_sec": source_duration,
            "filename": f"meeting-{normalized_meeting_id}-{timestamp}{suffix}",
            "source": source_kind,
            "audio_file": audio_path.name,
        }
        metadata_path = target_dir / f"{archive_id}.json"
        metadata_partial = target_dir / f".{archive_id}.json.part"
        metadata_partial.write_text(
            json.dumps(metadata, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        os.replace(metadata_partial, metadata_path)
        return metadata


def public_archive_metadata(metadata: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in metadata.items() if not key.startswith("_")}


def archive_duration_sec(metadata: dict[str, Any]) -> float | None:
    with contextlib.suppress(TypeError, ValueError):
        duration = float(metadata.get("duration_sec"))
        if math.isfinite(duration) and duration > 0:
            return duration
    audio_path = Path(str(metadata.get("_audio_path", "")))
    if audio_path.is_file():
        return audio_duration_for_tencent_budget(audio_path)
    return None


def archive_quality_key(metadata: dict[str, Any]) -> tuple[float, int, float]:
    duration = archive_duration_sec(metadata) or 0.0
    audio_path = Path(str(metadata.get("_audio_path", "")))
    modified_at = audio_path.stat().st_mtime if audio_path.is_file() else time.time()
    with contextlib.suppress(TypeError, ValueError):
        source_bytes = int(metadata.get("bytes", 0))
        return duration, source_bytes, -modified_at
    return duration, 0, -modified_at


def remove_archive_entry_unlocked(metadata_path: Path, metadata: dict[str, Any]) -> None:
    audio_path = Path(str(metadata.get("_audio_path", "")))
    audio_path.unlink(missing_ok=True)
    metadata_path.unlink(missing_ok=True)


def read_archive_metadata(path: Path) -> dict[str, Any] | None:
    try:
        metadata = json.loads(path.read_text(encoding="utf-8"))
        audio_path = path.parent / str(metadata.get("audio_file", ""))
        if not audio_path.is_file():
            return None
        metadata["_audio_path"] = str(audio_path)
        return metadata
    except (OSError, TypeError, ValueError, json.JSONDecodeError):
        return None


def list_archived_audio(owner_id: str, meeting_id: str | None = None) -> list[dict[str, Any]]:
    normalized_meeting_id = normalize_archive_meeting_id(meeting_id)
    root = archive_owner_dir(owner_id)
    if not root.is_dir():
        return []
    candidates = (
        (root / normalized_meeting_id).glob("*.json")
        if normalized_meeting_id
        else root.glob("*/*.json")
    )
    items = [metadata for path in candidates if (metadata := read_archive_metadata(path)) is not None]
    best_by_meeting: dict[str, dict[str, Any]] = {}
    for item in items:
        scope = str(item.get("meeting_id", ""))
        current = best_by_meeting.get(scope)
        if current is None or archive_quality_key(item) > archive_quality_key(current):
            best_by_meeting[scope] = item
    return sorted(
        best_by_meeting.values(),
        key=lambda item: str(item.get("created_at", "")),
        reverse=True,
    )


def find_archived_audio(owner_id: str, archive_id: str) -> dict[str, Any] | None:
    if not re.fullmatch(r"[0-9a-f]{32}", archive_id):
        return None
    root = archive_owner_dir(owner_id)
    if not root.is_dir():
        return None
    for metadata_path in root.glob(f"*/{archive_id}.json"):
        metadata = read_archive_metadata(metadata_path)
        if metadata is not None:
            metadata["_metadata_path"] = str(metadata_path)
            return metadata
    return None


def delete_archived_audio(owner_id: str, archive_id: str) -> bool:
    metadata = find_archived_audio(owner_id, archive_id)
    if metadata is None:
        return False
    for key in ("_audio_path", "_metadata_path"):
        with contextlib.suppress(FileNotFoundError):
            Path(str(metadata[key])).unlink()
    return True


def cleanup_audio_archive(now: float | None = None) -> int:
    if not STT_AUDIO_ARCHIVE_ENABLED:
        return 0
    with audio_archive_lock:
        return cleanup_audio_archive_unlocked(now)


def cleanup_audio_archive_unlocked(now: float | None = None) -> int:
    STT_AUDIO_ARCHIVE_DIR.mkdir(parents=True, exist_ok=True)
    cutoff = (now if now is not None else time.time()) - STT_AUDIO_ARCHIVE_RETENTION_DAYS * 86400
    removed = 0
    retained: list[tuple[float, int, Path, Path]] = []
    entries_by_meeting: dict[str, list[tuple[Path, dict[str, Any]]]] = {}
    for metadata_path in STT_AUDIO_ARCHIVE_DIR.glob("*/*/*.json"):
        metadata = read_archive_metadata(metadata_path)
        if metadata is None:
            with contextlib.suppress(FileNotFoundError):
                metadata_path.unlink()
            continue
        entries_by_meeting.setdefault(str(metadata_path.parent), []).append(
            (metadata_path, metadata)
        )

    canonical_entries: list[tuple[Path, dict[str, Any]]] = []
    for entries in entries_by_meeting.values():
        canonical_path, canonical = max(entries, key=lambda entry: archive_quality_key(entry[1]))
        canonical_entries.append((canonical_path, canonical))
        for metadata_path, metadata in entries:
            if metadata_path != canonical_path:
                remove_archive_entry_unlocked(metadata_path, metadata)
                removed += 1

    for metadata_path, metadata in canonical_entries:
        audio_path = Path(str(metadata["_audio_path"]))
        stat = audio_path.stat()
        if stat.st_mtime < cutoff:
            audio_path.unlink(missing_ok=True)
            metadata_path.unlink(missing_ok=True)
            removed += 1
        else:
            retained.append((stat.st_mtime, stat.st_size, audio_path, metadata_path))

    max_bytes = STT_AUDIO_ARCHIVE_MAX_GB * 1024 * 1024 * 1024
    total_bytes = sum(item[1] for item in retained)
    for _, size, audio_path, metadata_path in sorted(retained):
        if total_bytes <= max_bytes:
            break
        audio_path.unlink(missing_ok=True)
        metadata_path.unlink(missing_ok=True)
        total_bytes -= size
        removed += 1
    return removed


async def temp_cleanup_loop() -> None:
    global temp_files_cleaned
    while True:
        await asyncio.sleep(STT_TEMP_CLEANUP_INTERVAL_SEC)
        now = time.time()
        temp_files_cleaned += await asyncio.to_thread(cleanup_stale_temp_files, now)
        await asyncio.to_thread(cleanup_audio_archive, now)
        cutoff = now - STT_TEMP_MAX_AGE_SEC
        stale_sessions = [
            session_id
            for session_id, recording in stream_recordings.items()
            if recording.created_at < cutoff
        ]
        for session_id in stale_sessions:
            stream_recordings.pop(session_id, None)


def remove_angle_tags(text: str) -> str:
    return re.sub(r"<[^>\r\n]{0,120}>", " ", text or "")


def normalize_preview_text(text: str) -> str:
    normalized = " ".join(remove_angle_tags(text).split()).strip()
    return simplified_chinese.convert(normalized)


def restore_final_punctuation(
    text: str,
    segments: list[dict[str, Any]],
    language: str = "zh",
) -> str:
    language = normalize_stt_language(language)
    normalized = normalize_preview_text(text)
    if not normalized or not STT_FINAL_RESTORE_PUNCTUATION:
        return normalized

    normalized = re.sub(r"\s+([，。！？；：,.!?;:])", r"\1", normalized)
    normalized = re.sub(r"([，。！？；：])\s+", r"\1", normalized)
    has_any_punctuation = re.search(r"[，。！？；：,.!?;:]", normalized) is not None

    if not has_any_punctuation:
        fragments = [
            {
                "text": normalize_preview_text(str(segment.get("text") or "")),
                "start": float(segment.get("start") or 0.0),
                "end": float(segment.get("end") or 0.0),
            }
            for segment in segments
            if normalize_preview_text(str(segment.get("text") or ""))
        ]
        if fragments:
            punctuated: list[str] = []
            for index, fragment in enumerate(fragments):
                is_last = index == len(fragments) - 1
                if is_last:
                    suffix = "." if language == "en" else "。"
                else:
                    pause = max(0.0, fragments[index + 1]["start"] - fragment["end"])
                    if language == "en":
                        suffix = "." if pause >= STT_FINAL_PUNCTUATION_PAUSE_SEC else ","
                    else:
                        suffix = "。" if pause >= STT_FINAL_PUNCTUATION_PAUSE_SEC else "，"
                punctuated.append(f"{fragment['text']}{suffix}")
            normalized = (" " if language == "en" else "").join(punctuated)

    if normalized and normalized[-1] not in "。！？.!?":
        normalized += "." if language == "en" else "。"
    return normalized


def is_known_hallucination(text: str) -> bool:
    normalized = normalize_preview_text(text).strip("，。！？,.!? ")
    return normalized in STREAM_HALLUCINATION_PHRASES


def sanitize_final_transcript(text: str) -> str:
    normalized = normalize_preview_text(text)
    if not normalized:
        return ""

    def compact(value: str) -> str:
        return re.sub(r"[\s，。！？；：,.!?;:'\"“”‘’]+", "", value).casefold()

    echo_compact = {compact(phrase) for phrase in FINAL_PROMPT_ECHO_PHRASES}
    if compact(normalized) in echo_compact:
        return ""

    for phrase in sorted(FINAL_PROMPT_ECHO_PHRASES, key=len, reverse=True):
        prefix = normalize_preview_text(phrase).rstrip("，。！？；：,.!?;: ")
        if not normalized.startswith(prefix):
            continue
        remainder = normalized[len(prefix):]
        if phrase == FINAL_PROMPT_ECHO_PHRASES[0] or (
            remainder and remainder[0] in "，。！？；：,.!?;: "
        ):
            normalized = remainder.lstrip("，。！？；：,.!?;: ")
            break

    if is_known_hallucination(normalized):
        return ""
    return normalized


def stable_common_prefix(previous: str, current: str) -> str:
    if not previous or not current:
        return ""

    max_idx = min(len(previous), len(current))
    idx = 0
    while idx < max_idx and previous[idx] == current[idx]:
        idx += 1
    return normalize_preview_text(current[:idx])


def merge_transcript_text(existing: str, update: str) -> str:
    existing = normalize_preview_text(existing)
    update = normalize_preview_text(update)
    if not existing:
        return update
    if not update or update in existing:
        return existing
    if existing in update:
        return update

    max_overlap = min(len(existing), len(update))
    for overlap in range(max_overlap, 3, -1):
        if existing[-overlap:] == update[:overlap]:
            return normalize_preview_text(existing + update[overlap:])

    return normalize_preview_text(f"{existing} {update}")


def merge_chunk_transcript_text(existing: str, update: str) -> str:
    """Merge overlapping long-audio chunks without duplicating punctuation variants."""
    existing = normalize_preview_text(existing)
    update = normalize_preview_text(update)
    if not existing:
        return update
    if not update or update in existing:
        return existing
    if existing in update:
        return update

    punctuation = set("，。！？；：,.!?;:、（）()【】[]「」『』“”‘’\"'—-…·")
    existing_units = [
        (character, index)
        for index, character in enumerate(existing)
        if not character.isspace() and character not in punctuation
    ]
    update_units = [
        (character, index)
        for index, character in enumerate(update)
        if not character.isspace() and character not in punctuation
    ]
    max_overlap = min(len(existing_units), len(update_units))
    for overlap in range(max_overlap, 3, -1):
        if [item[0] for item in existing_units[-overlap:]] != [
            item[0] for item in update_units[:overlap]
        ]:
            continue
        update_cut = update_units[overlap - 1][1] + 1
        tail = update[update_cut:]
        if existing[-1:] in punctuation and tail[:1] in punctuation:
            existing = existing.rstrip("".join(punctuation))
        return normalize_preview_text(existing + tail)

    first = existing[-1:] if existing else ""
    next_character = update[:1] if update else ""
    both_cjk = (
        first and next_character
        and "\u4e00" <= first <= "\u9fff"
        and "\u4e00" <= next_character <= "\u9fff"
    )
    boundary_punctuation = first in punctuation or next_character in punctuation
    separator = "" if both_cjk or boundary_punctuation else " "
    return normalize_preview_text(existing + separator + update)


def promote_revisable_preview(
    committed_text: str,
    active_preview_text: str,
    stable_candidate: str,
) -> str:
    """Promote a stable window without discarding earlier provisional windows."""
    accumulated_preview = merge_transcript_text(active_preview_text, stable_candidate)
    return merge_transcript_text(committed_text, accumulated_preview)


def extract_window_delta(previous_window: str, current_window: str) -> str:
    previous_window = normalize_preview_text(previous_window)
    current_window = normalize_preview_text(current_window)
    if not current_window:
        return ""
    if not previous_window:
        return current_window
    if current_window == previous_window or current_window in previous_window:
        return ""
    if current_window.startswith(previous_window):
        return normalize_preview_text(current_window[len(previous_window):])

    max_overlap = min(len(previous_window), len(current_window))
    for overlap in range(max_overlap, 5, -1):
        if previous_window[-overlap:] == current_window[:overlap]:
            return normalize_preview_text(current_window[overlap:])

    # Sliding windows often share most content. If we cannot find a reliable overlap,
    # keep the current window as the new baseline but do not append it wholesale.
    return ""


def push_debug_event(event_type: str, **payload: Any) -> None:
    stream_debug_events.append(
        {
            "time": utc_now(),
            "type": event_type,
            **payload,
        }
    )


def has_valid_fw_model_dir(path: Path) -> bool:
    model_bin = path / "model.bin"
    tokenizer = path / "tokenizer.json"
    vocab = path / "vocabulary.txt"
    return model_bin.exists() and model_bin.stat().st_size > 1024 and tokenizer.exists() and vocab.exists()


def warmup_faster_whisper(candidate_model) -> None:
    """Run a tiny decode so missing CUDA runtime libraries fail before readiness."""
    with new_temp_file(".wav") as temp_file:
        temp_path = temp_file.name
    try:
        with wave.open(temp_path, "wb") as wav_file:
            wav_file.setnchannels(1)
            wav_file.setsampwidth(2)
            wav_file.setframerate(16000)
            wav_file.writeframes(b"\x00" * 6400)
        segments, _ = candidate_model.transcribe(
            temp_path,
            beam_size=1,
            vad_filter=False,
            language="zh",
            condition_on_previous_text=False,
        )
        list(segments)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temp_path)


def load_model(size: str = "small", engine: str = "faster-whisper"):
    global model, final_model, model_size, model_source
    global stt_engine, model_load_error, model_checksum_verified
    model_root.mkdir(parents=True, exist_ok=True)

    if engine == "sensevoice":
        if FunASRAutoModel is None:
            raise RuntimeError("SenseVoice dependencies missing. Please install funasr and dependencies.")

        sensevoice_root = (model_root / "sensevoice").resolve()
        sensevoice_root.mkdir(parents=True, exist_ok=True)

        candidate_path = Path(size)
        if not candidate_path.is_absolute():
            candidate_path = (sensevoice_root / size).resolve()

        # Use local folder first; otherwise fallback to official hub id.
        if candidate_path.exists():
            next_model_source = str(candidate_path)
        else:
            next_model_source = size if "/" in size else "iic/SenseVoiceSmall"

        print(f"Loading SenseVoice model: {next_model_source}", flush=True)
        print(f"Model root: {model_root}", flush=True)
        next_model = FunASRAutoModel(
            model=next_model_source,
            trust_remote_code=True,
        )
        model = next_model
        final_model = None
        model_size = size
        model_source = next_model_source
        stt_engine = engine
        model_load_error = ""
        print("SenseVoice model loaded successfully!", flush=True)
        return

    fw_root = (model_root / "faster-whisper").resolve()
    fw_root.mkdir(parents=True, exist_ok=True)

    candidate_path = Path(size)
    if not candidate_path.is_absolute():
        candidate_path = (fw_root / size).resolve()

    if candidate_path.exists() and has_valid_fw_model_dir(candidate_path):
        next_model_source = str(candidate_path)
        model_checksum_verified = False
        if STT_MODEL_SHA256:
            digest = sha256_file(candidate_path / "model.bin")
            if not hmac.compare_digest(digest, STT_MODEL_SHA256):
                raise RuntimeError(
                    f"Model checksum mismatch for {candidate_path / 'model.bin'}: "
                    f"expected {STT_MODEL_SHA256}, got {digest}"
                )
            model_checksum_verified = True
    else:
        if STT_MODEL_SHA256:
            raise RuntimeError(
                f"Pinned Faster-Whisper model is missing or incomplete: {candidate_path}"
            )
        next_model_source = size
        model_checksum_verified = False

    print(f"Loading Faster-Whisper model: {next_model_source}", flush=True)
    print(f"Model root: {model_root}", flush=True)
    print(f"Using HF Mirror: {os.environ.get('HF_ENDPOINT', 'default')}", flush=True)

    cuda_devices = 0
    if STT_DEVICE != "cpu":
        with contextlib.suppress(Exception):
            cuda_devices = ctranslate2.get_cuda_device_count()
    prefer_gpu = STT_DEVICE == "cuda" or (STT_DEVICE == "auto" and cuda_devices > 0)
    if prefer_gpu:
        try:
            next_model = faster_whisper.WhisperModel(
                next_model_source,
                device="cuda",
                compute_type=os.getenv("STT_COMPUTE_TYPE", "float16"),
                download_root=str(fw_root),
                num_workers=STT_MAX_CONCURRENT,
            )
            warmup_faster_whisper(next_model)
            model = next_model
            final_model = (
                faster_whisper.BatchedInferencePipeline(next_model)
                if STT_FINAL_BATCH_SIZE > 1
                else next_model
            )
            model_size = size
            model_source = next_model_source
            stt_engine = engine
            model_load_error = ""
            print("Faster-Whisper model loaded successfully on CUDA (float16)!", flush=True)
            return
        except Exception as gpu_exc:
            if STT_DEVICE == "cuda":
                raise RuntimeError(f"CUDA model initialization failed: {gpu_exc}") from gpu_exc
            print(f"CUDA init failed, fallback to CPU: {gpu_exc}", flush=True)

    next_model = faster_whisper.WhisperModel(
        next_model_source,
        device="cpu",
        compute_type=os.getenv("STT_COMPUTE_TYPE", "int8"),
        download_root=str(fw_root),
        cpu_threads=STT_CPU_THREADS,
        num_workers=STT_MAX_CONCURRENT,
    )
    warmup_faster_whisper(next_model)
    model = next_model
    final_model = (
        faster_whisper.BatchedInferencePipeline(next_model)
        if STT_FINAL_BATCH_SIZE > 1
        else next_model
    )
    model_size = size
    model_source = next_model_source
    stt_engine = engine
    model_load_error = ""
    print("Faster-Whisper model loaded successfully on CPU (int8)!", flush=True)


def load_stream_model() -> None:
    global stream_model, stream_model_size, stream_model_source
    global stream_model_error, stream_model_checksum_verified

    if model is None:
        stream_model = None
        stream_model_error = "Primary model is not loaded"
        return
    if stt_engine != "faster-whisper" or STT_STREAM_MODEL == model_size:
        stream_model = model
        stream_model_size = model_size
        stream_model_source = model_source
        stream_model_error = ""
        stream_model_checksum_verified = model_checksum_verified
        return

    candidate_path = (model_root / "faster-whisper" / STT_STREAM_MODEL).resolve()
    if not has_valid_fw_model_dir(candidate_path):
        stream_model = model
        stream_model_size = model_size
        stream_model_source = model_source
        stream_model_error = f"Pinned stream model is missing or incomplete: {candidate_path}"
        stream_model_checksum_verified = False
        return

    if STT_STREAM_MODEL_SHA256:
        digest = sha256_file(candidate_path / "model.bin")
        if not hmac.compare_digest(digest, STT_STREAM_MODEL_SHA256):
            stream_model = model
            stream_model_size = model_size
            stream_model_source = model_source
            stream_model_error = "Pinned stream model checksum mismatch"
            stream_model_checksum_verified = False
            return

    try:
        print(f"Loading stream preview model: {candidate_path}", flush=True)
        next_stream_model = faster_whisper.WhisperModel(
            str(candidate_path),
            device="cpu",
            compute_type="int8",
            cpu_threads=STT_STREAM_CPU_THREADS,
            num_workers=STT_MAX_CONCURRENT,
        )
        warmup_faster_whisper(next_stream_model)
    except Exception as exc:
        stream_model = model
        stream_model_size = model_size
        stream_model_source = model_source
        stream_model_error = str(exc)
        stream_model_checksum_verified = False
        return

    stream_model = next_stream_model
    stream_model_size = STT_STREAM_MODEL
    stream_model_source = str(candidate_path)
    stream_model_error = ""
    stream_model_checksum_verified = bool(STT_STREAM_MODEL_SHA256)
    print("Stream preview model loaded successfully on CPU (int8)!", flush=True)


def decode_faster_whisper_file(
    active_model,
    file_path: str,
    beam_size: int,
    vad_filter: bool = True,
    batch_size: int | None = None,
    condition_on_previous_text: bool = False,
    initial_prompt: str | None = None,
    language: str = "zh",
) -> tuple[str, Any, list[dict[str, Any]]]:
    def run_transcribe(**kwargs):
        segments, info = active_model.transcribe(file_path, **kwargs)
        segment_rows: list[dict[str, Any]] = []
        text_parts = []
        for segment in segments:
            cleaned = segment.text.strip()
            segment_rows.append(
                {
                    "start": float(segment.start),
                    "end": float(segment.end),
                    "text": cleaned,
                    "avg_logprob": float(getattr(segment, "avg_logprob", 0.0)),
                    "no_speech_prob": float(getattr(segment, "no_speech_prob", 0.0)),
                }
            )
            text_parts.append(cleaned)
        return " ".join(part for part in text_parts if part).strip(), info, segment_rows

    options = dict(
        beam_size=beam_size,
        vad_filter=vad_filter,
        language=normalize_stt_language(language),
        condition_on_previous_text=condition_on_previous_text,
    )
    if initial_prompt:
        options["initial_prompt"] = initial_prompt
    if batch_size is not None and batch_size > 1:
        options["batch_size"] = batch_size
    return run_transcribe(**options)


def transcribe_faster_whisper_file(
    active_model,
    file_path: str,
    language: str = "zh",
) -> dict[str, Any]:
    language = normalize_stt_language(language)
    primary_model = active_model
    primary_batch_size = None
    if active_model is model and final_model is not None:
        primary_model = final_model
        if final_model is not model:
            primary_batch_size = STT_FINAL_BATCH_SIZE
    text, info, segment_rows = decode_faster_whisper_file(
        primary_model,
        file_path,
        beam_size=FINAL_BEAM_SIZE,
        batch_size=primary_batch_size,
        condition_on_previous_text=STT_FINAL_CONDITION_ON_PREVIOUS_TEXT,
        initial_prompt=(STT_FINAL_INITIAL_PROMPT or None) if language == "zh" else None,
        language=language,
    )
    text = sanitize_final_transcript(text)
    if len(text) < STT_FINAL_RETRY_MIN_CHARS:
        retry_text, retry_info, retry_segments = decode_faster_whisper_file(
            active_model,
            file_path,
            beam_size=1,
            vad_filter=False,
            condition_on_previous_text=STT_FINAL_CONDITION_ON_PREVIOUS_TEXT,
            initial_prompt=None,
            language=language,
        )
        retry_text = sanitize_final_transcript(retry_text)
        if len(retry_text) > len(text):
            text, info, segment_rows = retry_text, retry_info, retry_segments

    text = sanitize_final_transcript(text)
    if text:
        text = restore_final_punctuation(text, segment_rows, language)
    return {
        "text": text,
        "language": info.language,
        "segments": segment_rows,
        "strategy": "final-compatible",
    }


def transcribe_faster_whisper_preview_file(
    active_model,
    file_path: str,
    language: str = "zh",
) -> dict[str, Any]:
    """Decode a revisable preview without the final transcript retry."""
    text, info, segment_rows = decode_faster_whisper_file(
        active_model,
        file_path,
        beam_size=STREAM_BEAM_SIZE,
        language=language,
    )
    text = normalize_preview_text(text)
    if is_known_hallucination(text):
        text = ""
    return {
        "text": text,
        "language": info.language,
        "segments": segment_rows,
        "strategy": "revisable-preview",
    }


def transcribe_local_single_file(file_path: str, language: str = "zh") -> TranscribeResponse:
    language = normalize_stt_language(language)
    if model is None:
        raise RuntimeError("Model not loaded")
    started_at = time.monotonic()

    if stt_engine == "sensevoice":
        # FunASR model objects keep mutable decoding state, so one shared instance
        # must be serialized. Faster-Whisper remains parallel through its workers.
        with sensevoice_inference_lock:
            result = model.generate(input=file_path)
        text = ""
        detected_language = language
        if isinstance(result, list) and result:
            item = result[0]
            if isinstance(item, dict):
                text = str(item.get("text", "") or "").strip()
                detected_language = str(item.get("lang", language) or language)
            else:
                text = str(item).strip()
        elif isinstance(result, dict):
            text = str(result.get("text", "") or "").strip()
            detected_language = str(result.get("lang", language) or language)
        else:
            text = str(result or "").strip()

        text = normalize_preview_text(text)
        if is_known_hallucination(text):
            text = ""
        else:
            text = restore_final_punctuation(text, [], language)
        print(
            "SenseVoice transcription completed: "
            f"chars={len(text)}, language={detected_language}, elapsed_sec={time.monotonic() - started_at:.2f}",
            flush=True,
        )
        return TranscribeResponse(text=text, language=detected_language)

    result = transcribe_faster_whisper_file(model, file_path, language)
    print(
        "Transcription completed: "
        f"chars={len(result['text'])}, language={result['language']}, "
        f"beam={FINAL_BEAM_SIZE}, batch={STT_FINAL_BATCH_SIZE}, "
        "strategy=single-file",
        flush=True,
    )
    return TranscribeResponse(text=result["text"], language=result["language"])


def transcribe_local_long_audio(file_path: str, language: str = "zh") -> TranscribeResponse:
    source = Path(file_path)
    duration_seconds = audio_duration_for_tencent_budget(source)
    if duration_seconds is None or duration_seconds <= STT_LONG_AUDIO_CHUNK_THRESHOLD_SEC:
        return transcribe_local_single_file(file_path, language)

    started_at = time.monotonic()
    STT_TEMP_DIR.mkdir(parents=True, exist_ok=True)
    try:
        with tempfile.TemporaryDirectory(
            dir=STT_TEMP_DIR,
            prefix=f"{STT_TEMP_PREFIX}local-chunks-",
        ) as directory:
            chunks = create_audio_chunks(
                source,
                Path(directory),
                chunk_seconds=STT_LONG_AUDIO_CHUNK_SECONDS,
                overlap_seconds=STT_LONG_AUDIO_CHUNK_OVERLAP_SEC,
            )
            merged_text = ""
            detected_language = normalize_stt_language(language)
            for index, chunk in enumerate(chunks, start=1):
                result = transcribe_local_single_file(str(chunk.path), language)
                detected_language = result.language or detected_language
                merged_text = merge_chunk_transcript_text(merged_text, result.text)
                print(
                    f"Long audio local chunk {index}/{len(chunks)} completed: "
                    f"chars={len(result.text)}",
                    flush=True,
                )
            if not merged_text:
                raise AudioChunkingError("长录音分段转写未返回有效文字")
            elapsed = time.monotonic() - started_at
            push_debug_event(
                "local_asr_chunked",
                engine=stt_engine,
                chunk_count=len(chunks),
                duration_seconds=round(duration_seconds, 3),
                elapsed_sec=round(elapsed, 2),
            )
            print(
                "Long audio local transcription completed: "
                f"chunks={len(chunks)}, chars={len(merged_text)}, "
                f"duration_sec={duration_seconds:.1f}, elapsed_sec={elapsed:.2f}",
                flush=True,
            )
            return TranscribeResponse(text=merged_text, language=detected_language)
    except AudioChunkingError:
        raise
    except Exception as exc:
        raise AudioChunkingError(f"长录音分段处理失败: {sanitize_upstream_error(exc)}") from exc


def transcribe_file(file_path: str, language: str = "zh") -> TranscribeResponse:
    return transcribe_local_long_audio(file_path, language)


def transcribe_audio(
    audio_data: bytes,
    suffix: str = ".m4a",
    language: str = "zh",
) -> TranscribeResponse:
    if model is None:
        raise RuntimeError("Model not loaded")
    print(f"Received audio data: {len(audio_data)} bytes", flush=True)
    if len(audio_data) == 0:
        raise ValueError("Empty audio data received")

    if not suffix.startswith("."):
        suffix = f".{suffix}"

    with new_temp_file(suffix) as temp_file:
        temp_file.write(audio_data)
        tmp_path = temp_file.name

    print(f"Temp file: {tmp_path}, size: {os.path.getsize(tmp_path)} bytes", flush=True)
    try:
        return transcribe_file(tmp_path, language)
    finally:
        os.unlink(tmp_path)


def transcribe_stream_pcm(
    pcm_bytes: bytes,
    sample_rate: int = 16000,
    channels: int = 1,
    language: str = "zh",
) -> TranscribeResponse:
    if len(pcm_bytes) == 0:
        return TranscribeResponse(text="", language="zh")

    with new_temp_file(".wav") as temp_file:
        tmp_path = temp_file.name

    try:
        with wave.open(tmp_path, "wb") as wav_file:
            wav_file.setnchannels(channels)
            wav_file.setsampwidth(2)
            wav_file.setframerate(sample_rate)
            wav_file.writeframes(pcm_bytes)
        return transcribe_file(tmp_path, language)
    finally:
        os.unlink(tmp_path)


def transcribe_stream_snapshot(
    pcm_bytes: bytes,
    sample_rate: int = 16000,
    channels: int = 1,
    language: str = "zh",
) -> dict[str, Any]:
    language = normalize_stt_language(language)
    if len(pcm_bytes) == 0:
        return {
            "text": "",
            "language": language,
            "segments": [],
        }

    # SenseVoice streaming preview currently uses whole-buffer snapshot text.
    if stt_engine == "sensevoice":
        response = transcribe_stream_pcm(
            pcm_bytes,
            sample_rate=sample_rate,
            channels=channels,
            language=language,
        )
        return {
            "text": normalize_preview_text(response.text),
            "language": response.language,
            "segments": [],
        }

    with new_temp_file(".wav") as temp_file:
        tmp_path = temp_file.name

    try:
        with wave.open(tmp_path, "wb") as wav_file:
            wav_file.setnchannels(channels)
            wav_file.setsampwidth(2)
            wav_file.setframerate(sample_rate)
            wav_file.writeframes(pcm_bytes)

        return transcribe_faster_whisper_preview_file(stream_model or model, tmp_path, language)
    finally:
        os.unlink(tmp_path)


def filter_stream_segments(
    segment_rows: list[dict[str, Any]],
    min_confidence: float = STREAM_MIN_CONFIDENCE,
    max_no_speech_prob: float = STREAM_MAX_NO_SPEECH_PROB,
    settled_before_sec: float | None = None,
) -> tuple[str, list[dict[str, Any]], list[dict[str, Any]]]:
    accepted_segments: list[dict[str, Any]] = []
    rejected_segments: list[dict[str, Any]] = []

    for segment in segment_rows:
        text = normalize_preview_text(segment.get("text", ""))
        start = float(segment.get("start", 0.0))
        end = float(segment.get("end", start))
        avg_logprob = float(segment.get("avg_logprob", 0.0))
        no_speech_prob = float(segment.get("no_speech_prob", 0.0))

        reason = None
        if not text:
            reason = "empty"
        elif settled_before_sec is not None and (start + end) / 2 > settled_before_sec:
            reason = "unstable_tail"
        elif any(phrase in text for phrase in STREAM_HALLUCINATION_PHRASES):
            reason = "hallucination_phrase"
        elif no_speech_prob > max_no_speech_prob:
            reason = "high_no_speech_prob"
        elif avg_logprob < min_confidence:
            reason = "low_confidence"

        if reason is None:
            accepted = dict(segment)
            accepted["text"] = text
            accepted_segments.append(accepted)
        else:
            rejected = dict(segment)
            rejected["text"] = text
            rejected["reject_reason"] = reason
            rejected_segments.append(rejected)

    accepted_text = normalize_preview_text(" ".join(segment["text"] for segment in accepted_segments))
    return accepted_text, accepted_segments, rejected_segments


def fallback_stream_preview(raw_text: str, rejected_streak: int, threshold: int) -> str:
    preview = normalize_preview_text(raw_text)
    if (
        rejected_streak < max(1, threshold)
        or len(re.sub(r"\s+", "", preview)) < STREAM_PROVISIONAL_MIN_CHARS
        or not preview
        or is_known_hallucination(preview)
    ):
        return ""
    return preview


def select_stream_preview(
    candidate: str,
    previous_candidate: str,
    segment_rows: list[dict[str, Any]],
    min_confidence: float = STREAM_MIN_CONFIDENCE,
    max_no_speech_prob: float = STREAM_MAX_NO_SPEECH_PROB,
    stable_similarity: float = STREAM_STABLE_SIMILARITY,
) -> tuple[str, str, float, list[dict[str, Any]], list[dict[str, Any]]]:
    candidate = normalize_preview_text(candidate)
    accepted_text, accepted_segments, rejected_segments = filter_stream_segments(
        segment_rows,
        min_confidence=min_confidence,
        max_no_speech_prob=max_no_speech_prob,
        settled_before_sec=None,
    )
    previous = normalize_preview_text(previous_candidate)
    compact_candidate = re.sub(r"\s+", "", candidate)
    compact_previous = re.sub(r"\s+", "", previous)
    similarity = (
        difflib.SequenceMatcher(None, compact_previous, compact_candidate).ratio()
        if compact_previous and compact_candidate
        else 0.0
    )

    accepted_chars = len(re.sub(r"\s+", "", accepted_text))
    if accepted_chars >= STREAM_STABLE_MIN_CHARS and similarity >= stable_similarity:
        return accepted_text, "stable", similarity, accepted_segments, rejected_segments
    if accepted_chars >= STREAM_STABLE_MIN_CHARS:
        return accepted_text, "confidence", 1.0, accepted_segments, rejected_segments
    if accepted_chars >= STREAM_PROVISIONAL_MIN_CHARS:
        return accepted_text, "provisional", similarity, accepted_segments, rejected_segments
    if len(compact_candidate) >= STREAM_STABLE_MIN_CHARS and similarity >= stable_similarity:
        return candidate, "stable", similarity, accepted_segments, rejected_segments
    return "", "held", similarity, accepted_segments, rejected_segments


def resolve_api_principal(authorization: str | None) -> ApiPrincipal | None:
    if not STT_REQUIRE_API_TOKEN and not STT_API_TOKEN:
        return ApiPrincipal(owner_id="anonymous")
    if not authorization or not authorization.startswith("Bearer "):
        return None
    token = authorization[7:].strip()
    if STT_API_TOKEN and hmac.compare_digest(token, STT_API_TOKEN):
        return ApiPrincipal(owner_id="management", is_management=True)
    owner_id = verify_account_stt_token(ACCOUNT_TOKEN_SECRET, token)
    return ApiPrincipal(owner_id=owner_id) if owner_id is not None else None


def is_api_token_valid(authorization: str | None) -> bool:
    return resolve_api_principal(authorization) is not None


def require_api_token(authorization: str | None = Header(default=None)) -> ApiPrincipal:
    principal = resolve_api_principal(authorization)
    if principal is None:
        raise HTTPException(status_code=401, detail="Missing or invalid bearer token")
    return principal


def is_management_token_valid(authorization: str | None) -> bool:
    if not STT_API_TOKEN or not authorization or not authorization.startswith("Bearer "):
        return False
    return hmac.compare_digest(authorization[7:].strip(), STT_API_TOKEN)


def require_management_token(authorization: str | None = Header(default=None)) -> None:
    if not STT_API_TOKEN:
        raise HTTPException(status_code=503, detail="STT management token is not configured")
    if not is_management_token_valid(authorization):
        raise HTTPException(status_code=401, detail="Missing or invalid management bearer token")


def safe_audio_suffix(filename: str | None) -> str:
    suffix = Path(filename or "audio.bin").suffix.lower()
    if suffix in {".wav", ".m4a", ".mp3", ".mp4", ".aac", ".ogg", ".flac", ".webm"}:
        return suffix
    return ".bin"


async def spool_upload(file: UploadFile) -> tuple[str, int]:
    max_bytes = STT_MAX_UPLOAD_MB * 1024 * 1024
    total_bytes = 0
    with new_temp_file(safe_audio_suffix(file.filename)) as temp_file:
        temp_path = temp_file.name
        try:
            while chunk := await file.read(STT_UPLOAD_CHUNK_BYTES):
                total_bytes += len(chunk)
                if total_bytes > max_bytes:
                    raise HTTPException(
                        status_code=413,
                        detail=f"Audio upload exceeds the {STT_MAX_UPLOAD_MB} MB limit",
                    )
                temp_file.write(chunk)
        except Exception:
            temp_file.close()
            with contextlib.suppress(FileNotFoundError):
                os.unlink(temp_path)
            raise
        finally:
            await file.close()

    if total_bytes == 0:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temp_path)
        raise HTTPException(status_code=400, detail="Empty audio upload")
    return temp_path, total_bytes


async def run_inference(callback, *args, label: str):
    try:
        return await inference_scheduler.run(callback, *args, label=label)
    except InferenceQueueFullError as exc:
        raise HTTPException(
            status_code=429,
            detail="STT service is busy; retry later",
            headers={"Retry-After": "2"},
        ) from exc
    except InferenceQueuePausedError as exc:
        raise HTTPException(
            status_code=503,
            detail="STT model maintenance is in progress",
            headers={"Retry-After": "5"},
        ) from exc


def transcribe_spooled_file(temp_path: str, language: str = "zh") -> TranscribeResponse:
    try:
        return transcribe_file(temp_path, language)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temp_path)


@contextlib.asynccontextmanager
async def app_lifespan(_app: FastAPI):
    global temp_files_cleaned
    if STT_REQUIRE_API_TOKEN and not STT_API_TOKEN:
        raise RuntimeError("STT_API_TOKEN is required when STT_REQUIRE_API_TOKEN=1")
    temp_files_cleaned += await asyncio.to_thread(cleanup_stale_temp_files)
    if STT_AUDIO_ARCHIVE_ENABLED:
        STT_AUDIO_ARCHIVE_DIR.mkdir(parents=True, exist_ok=True)
        await asyncio.to_thread(cleanup_audio_archive)
    if TENCENT_ASR_USAGE_LEDGER_ENABLED and tencent_asr_usage_configured():
        await asyncio.to_thread(_initialize_tencent_usage_ledger)
    await inference_scheduler.start()
    cleanup_task = asyncio.create_task(temp_cleanup_loop(), name="stt-temp-cleanup")
    try:
        yield
    finally:
        cleanup_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await cleanup_task
        await inference_scheduler.close()


app = FastAPI(title="OA助手 STT Server", lifespan=app_lifespan)


@app.get("/health")
async def health():
    return {
        "status": "ok" if model is not None else "model_unavailable",
        "version": SERVER_VERSION,
        "release": SERVER_RELEASE,
        "python": sys.version.split()[0],
        "engine": stt_engine,
        "model": model_size,
        "model_source": model_source,
        "model_root": str(model_root),
        "model_loaded": model is not None,
        "model_error": model_load_error,
        "model_checksum_verified": model_checksum_verified,
        "device": STT_DEVICE,
        "sensevoice_serialized": stt_engine == "sensevoice",
        "long_audio": {
            "chunk_threshold_sec": STT_LONG_AUDIO_CHUNK_THRESHOLD_SEC,
            "chunk_seconds": STT_LONG_AUDIO_CHUNK_SECONDS,
            "chunk_overlap_sec": STT_LONG_AUDIO_CHUNK_OVERLAP_SEC,
            "applies_to": ["faster-whisper", "sensevoice", "tencent-cloud"],
        },
        "streams": {
            "active": len(active_stream_sessions),
            "max": STT_MAX_STREAMS,
            "active_owners": len(active_stream_owners),
            "max_per_owner": STT_MAX_STREAMS_PER_OWNER,
            "update_interval_sec": STREAM_UPDATE_INTERVAL_SEC,
            "max_snapshot_sec": STREAM_MAX_SNAPSHOT_SEC,
            "buffer_sec": STREAM_BUFFER_SEC,
            "overlap_sec": STREAM_OVERLAP_SEC,
            "step_sec": STREAM_STEP_SEC,
            "preview_beam_size": STREAM_BEAM_SIZE,
            "preview_model": stream_model_size,
            "preview_model_source": stream_model_source,
            "preview_model_loaded": stream_model is not None and not stream_model_error,
            "preview_model_error": stream_model_error,
            "preview_model_checksum_verified": stream_model_checksum_verified,
        },
        "inference": inference_scheduler.stats(),
        "final_transcription": {
            "beam_size": FINAL_BEAM_SIZE,
            "batch_size": STT_FINAL_BATCH_SIZE,
            "stream_finalize_wait_sec": STT_STREAM_FINALIZE_WAIT_SEC,
            "initial_prompt_enabled": bool(STT_FINAL_INITIAL_PROMPT),
        },
        "temp_files": {
            "directory": str(STT_TEMP_DIR),
            "cleaned": temp_files_cleaned,
            "max_age_sec": STT_TEMP_MAX_AGE_SEC,
        },
        "audio_archive": {
            "enabled": STT_AUDIO_ARCHIVE_ENABLED,
            "retention_days": STT_AUDIO_ARCHIVE_RETENTION_DAYS,
            "max_gb": STT_AUDIO_ARCHIVE_MAX_GB,
        },
        "cloud_asr": {
            "provider": "tencent-tiered",
            "enabled": any(
                tencent_asr_configured(tier)
                for tier in (TENCENT_STANDARD_TIER, TENCENT_PRECISION_TIER)
            ),
            "configured": any(
                tencent_asr_configured(tier)
                for tier in (TENCENT_STANDARD_TIER, TENCENT_PRECISION_TIER)
            ),
            "max_upload_mb": STT_MAX_UPLOAD_MB,
            "per_request_max_upload_mb": TENCENT_ASR_MAX_UPLOAD_MB,
            "chunk_seconds": TENCENT_ASR_CHUNK_SECONDS,
            "chunk_overlap_sec": TENCENT_ASR_CHUNK_OVERLAP_SEC,
            "max_concurrent": TENCENT_ASR_MAX_CONCURRENT,
        },
        "realtime_asr": {
            "provider": "tencent-tiered",
            "enabled": any(
                tencent_realtime_asr_configured(tier)
                for tier in (TENCENT_STANDARD_TIER, TENCENT_PRECISION_TIER)
            ),
            "configured": any(
                tencent_realtime_asr_configured(tier)
                for tier in (TENCENT_STANDARD_TIER, TENCENT_PRECISION_TIER)
            ),
            "max_concurrent": TENCENT_REALTIME_ASR_MAX_CONCURRENT,
            "acquire_timeout_sec": TENCENT_REALTIME_ASR_ACQUIRE_TIMEOUT_SEC,
            "frame_ms": TENCENT_REALTIME_ASR_FRAME_MS,
            "queue_sec": TENCENT_REALTIME_ASR_QUEUE_SEC,
            "backpressure_timeout_sec": TENCENT_REALTIME_ASR_BACKPRESSURE_TIMEOUT_SEC,
        },
        "cloud_asr_usage": {
            "enabled": TENCENT_ASR_USAGE_ENABLED,
            "configured": tencent_asr_usage_configured(),
            "cache_sec": TENCENT_ASR_USAGE_CACHE_SEC,
            "ledger_enabled": TENCENT_ASR_USAGE_LEDGER_ENABLED,
            "ledger_path": str(TENCENT_ASR_USAGE_LEDGER_PATH),
            "timezone": str(tencent_usage_timezone()),
            "official_usage_note": "Reference only; enforcement uses cloud_asr_policy.",
        },
        "cloud_asr_policy": tencent_asr_budget_summary(),
    }


@app.get("/ready")
async def ready():
    if model is None:
        raise HTTPException(status_code=503, detail="STT model is not loaded")
    if stream_model is None or stream_model_error:
        raise HTTPException(status_code=503, detail="STT stream preview model is not loaded")
    return {
        "status": "ready",
        "version": SERVER_VERSION,
        "release": SERVER_RELEASE,
        "engine": stt_engine,
        "model": model_size,
        "stream_model": stream_model_size,
    }


@app.post("/admin/stt/switch", dependencies=[Depends(require_management_token)])
async def switch_stt(request: SwitchSTTRequest):
    global model_load_error
    engine = request.engine.strip().lower()
    if engine not in {"faster-whisper", "sensevoice"}:
        raise HTTPException(status_code=400, detail="engine must be faster-whisper or sensevoice")

    model_name = (request.model or "").strip()
    if not model_name:
        model_name = "SenseVoiceSmall" if engine == "sensevoice" else "small"
    if model_name not in STT_ALLOWED_MODELS:
        raise HTTPException(status_code=400, detail="model is not in STT_ALLOWED_MODELS")

    async with model_switch_lock:
        if (
            engine == stt_engine
            and model_name == model_size
            and model is not None
            and stream_model is not None
            and not stream_model_error
        ):
            push_debug_event("switch_noop", engine=engine, model=model_name)
            return {
                "status": "ready",
                "engine": stt_engine,
                "model": model_size,
                "model_source": model_source,
                "unchanged": True,
            }

        push_debug_event(
            "switch_requested",
            from_engine=stt_engine,
            from_model=model_size,
            target_engine=engine,
            target_model=model_name,
        )
        inference_scheduler.pause()
        try:
            await inference_scheduler.wait_idle(timeout_sec=STT_SWITCH_DRAIN_TIMEOUT_SEC)
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(None, load_model, model_name, engine)
            await loop.run_in_executor(None, load_stream_model)
            if stream_model_error:
                raise RuntimeError(stream_model_error)
        except asyncio.TimeoutError as exc:
            raise HTTPException(status_code=409, detail="Timed out waiting for active STT jobs") from exc
        except Exception as exc:
            model_load_error = str(exc)
            push_debug_event("switch_failed", target_engine=engine, target_model=model_name, error=str(exc))
            raise HTTPException(status_code=500, detail="STT model switch failed") from exc
        finally:
            inference_scheduler.resume()

    return {
        "status": "ready",
        "engine": stt_engine,
        "model": model_size,
        "model_source": model_source,
    }


@app.get("/debug/stream-events", dependencies=[Depends(require_api_token)])
async def debug_stream_events(limit: int = 40):
    safe_limit = max(1, min(limit, STREAM_DEBUG_EVENT_LIMIT))
    return {
        "events": list(stream_debug_events)[-safe_limit:],
        "limit": safe_limit,
        "buffer_limit": STREAM_DEBUG_EVENT_LIMIT,
    }


@app.delete("/debug/stream-events", dependencies=[Depends(require_api_token)])
async def clear_stream_events():
    """清空流式事件缓冲区"""
    global stream_debug_events
    cleared_count = len(stream_debug_events)
    stream_debug_events.clear()
    return {"status": "cleared", "message": f"已清空 {cleared_count} 条事件", "buffer_limit": STREAM_DEBUG_EVENT_LIMIT}


@app.post(
    "/transcribe",
    response_model=TranscribeResponse,
)
async def transcribe(
    file: UploadFile,
    language: str = Form(default="zh"),
    principal: ApiPrincipal = Depends(require_api_token),
    x_meeting_id: str | None = Header(default=None, alias="X-Meeting-Id"),
    x_archive_key: str | None = Header(default=None, alias="X-Archive-Key"),
):
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    try:
        language = normalize_stt_language(language)
        x_archive_key = normalize_archive_key(x_archive_key)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    temp_path, upload_bytes = await spool_upload(file)
    print(f"Received audio upload: {upload_bytes} bytes -> {temp_path}", flush=True)
    try:
        await asyncio.to_thread(
            archive_audio_file,
            temp_path,
            owner_id=principal.owner_id,
            meeting_id=x_meeting_id,
            source_kind="file-upload",
            original_filename=file.filename,
            archive_key=x_archive_key,
        )
        return await run_inference(
            transcribe_spooled_file,
            temp_path,
            language,
            label="file-upload",
        )
    except ValueError as exc:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temp_path)
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except HTTPException:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temp_path)
        raise
    except Exception as exc:
        print(f"File transcription failed: {exc}", flush=True)
        raise HTTPException(status_code=500, detail="Audio transcription failed") from exc


@app.get("/cloud-asr/v1/models")
async def managed_cloud_asr_models(
    _principal: ApiPrincipal = Depends(require_api_token),
):
    models = []
    for tier in (TENCENT_STANDARD_TIER, TENCENT_PRECISION_TIER):
        config = tencent_tier_config(tier)
        if not tencent_asr_configured(tier):
            continue
        models.append(
            {
                "id": config.model_id,
                "object": "model",
                "provider": "tencent-cloud",
                "tier": tier,
                "engine_type": config.flash_engine_type,
                "paid": config.paid,
            }
        )
    if not models:
        raise HTTPException(status_code=503, detail="Tencent Cloud ASR is not configured")
    return {"object": "list", "data": models}


@app.get("/cloud-asr/policy")
async def managed_cloud_asr_policy(
    _principal: ApiPrincipal = Depends(require_api_token),
):
    return tencent_asr_budget_summary()


@app.get("/cloud-asr/usage")
async def managed_cloud_asr_usage(
    _principal: ApiPrincipal = Depends(require_api_token),
    force: bool = False,
):
    if not TENCENT_ASR_USAGE_ENABLED:
        raise HTTPException(status_code=503, detail="Tencent Cloud ASR usage query is disabled")
    if not tencent_asr_usage_configured():
        raise HTTPException(
            status_code=503,
            detail="Tencent Cloud ASR usage query is not configured",
        )
    try:
        return await asyncio.to_thread(
            fetch_tencent_asr_usage,
            force_refresh=force,
        )
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=sanitize_upstream_error(exc)) from exc
    except Exception as exc:
        detail = sanitize_upstream_error(exc)
        print(f"Tencent Cloud ASR usage query failed: {detail}", flush=True)
        raise HTTPException(
            status_code=502,
            detail=f"Tencent Cloud ASR usage query failed: {detail}",
        ) from exc


async def local_cloud_asr_fallback(
    temp_path: str,
    language: str,
    tier: str,
    upstream_error: Exception,
) -> dict[str, Any]:
    fallback_reason = sanitize_upstream_error(upstream_error)
    requested_provider = tencent_tier_config(tier).model_id
    print(
        f"Cloud ASR fallback: requested={requested_provider}, reason={fallback_reason}",
        flush=True,
    )
    push_debug_event(
        "cloud_asr_fallback",
        requested_provider=requested_provider,
        fallback_provider="faster-whisper",
        error=fallback_reason,
    )
    try:
        fallback = await run_inference(
            transcribe_spooled_file,
            temp_path,
            language,
            label="cloud-asr-fallback",
        )
    except HTTPException:
        raise
    except Exception as fallback_error:
        raise HTTPException(
            status_code=502,
            detail=(
                "智悟增强云模型和智悟本地模型均未能完成转写: "
                f"{sanitize_upstream_error(fallback_error)}"
            ),
        ) from fallback_error
    return {
        "text": fallback.text,
        "provider": "faster-whisper",
        "requested_provider": requested_provider,
        "tier": tier,
        "fallback": True,
        "fallback_reason": fallback_reason,
        "language": fallback.language,
    }


@app.post("/cloud-asr/v1/audio/transcriptions")
async def managed_cloud_asr_transcription(
    file: UploadFile,
    model: str = Form(default=TENCENT_STANDARD_MODEL),
    language: str = Form(default="zh"),
    principal: ApiPrincipal = Depends(require_api_token),
    x_meeting_id: str | None = Header(default=None, alias="X-Meeting-Id"),
    x_archive_key: str | None = Header(default=None, alias="X-Archive-Key"),
):
    try:
        language = normalize_stt_language(language)
        x_archive_key = normalize_archive_key(x_archive_key)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    tier = tencent_model_tier(model)
    if tier is None:
        raise HTTPException(status_code=400, detail="Unsupported managed Cloud ASR model")
    if not tencent_asr_configured(tier):
        raise HTTPException(status_code=503, detail="Selected Tencent Cloud ASR tier is unavailable")
    voice_format = tencent_voice_format(file.filename)
    temp_path, upload_bytes = await spool_upload(file)
    reservation: TencentAsrBudgetReservation | None = None
    submitted_to_tencent = False
    reserved_duration_seconds = 0.0
    enforce_budget = tencent_asr_budget_enforced(tier)
    try:
        await asyncio.to_thread(
            archive_audio_file,
            temp_path,
            owner_id=principal.owner_id,
            meeting_id=x_meeting_id,
            source_kind="tencent-cloud-asr",
            original_filename=file.filename,
            archive_key=x_archive_key,
        )
        if enforce_budget:
            reserved_duration_seconds = await asyncio.to_thread(
                audio_duration_for_tencent_budget,
                Path(temp_path),
            ) or 0.0
            if reserved_duration_seconds <= 0:
                raise HTTPException(
                    status_code=422,
                    detail="Unable to determine audio duration for cloud-model budget enforcement",
                )
            try:
                reservation = await asyncio.to_thread(
                    reserve_tencent_asr_budget,
                    tier,
                    "asr_rec",
                    reserved_duration_seconds,
                )
            except RuntimeError as exc:
                raise HTTPException(status_code=429, detail=str(exc)) from exc
        async with tencent_asr_semaphore:
            submitted_to_tencent = True
            text, payload = await asyncio.to_thread(
                transcribe_with_tencent_flash_chunked,
                Path(temp_path),
                voice_format,
                tier=tier,
                language=language,
                record_usage=False,
            )
        if enforce_budget:
            actual_duration_seconds = reserved_duration_seconds
            with contextlib.suppress(TypeError, ValueError):
                actual_duration_seconds = max(
                    0.0,
                    float(payload.get("audio_duration") or 0) / 1000,
                ) or reserved_duration_seconds
            settle_tencent_asr_budget(reservation, actual_duration_seconds)
        reservation = None
        return {
            "text": text,
            "provider": tencent_tier_config(tier).model_id,
            "tier": tier,
            "engine_type": tencent_engine_type_for_language(
                tier=tier,
                language=language,
                realtime=False,
            ),
            "language": language,
            "duration_ms": payload.get("audio_duration"),
            "chunked": bool(payload.get("chunked", False)),
            "chunk_count": int(payload.get("chunk_count") or 1),
        }
    except HTTPException:
        release_tencent_asr_budget(reservation)
        raise
    except TencentChunkedTranscriptionError as exc:
        if submitted_to_tencent:
            settle_tencent_asr_budget(reservation, reserved_duration_seconds)
        else:
            release_tencent_asr_budget(reservation)
        raise HTTPException(
            status_code=502,
            detail=f"智悟增强云模型分段云转写失败: {sanitize_upstream_error(exc)}",
        ) from exc
    except ValueError as exc:
        if submitted_to_tencent and "4004" not in str(exc):
            settle_tencent_asr_budget(reservation, reserved_duration_seconds)
        else:
            release_tencent_asr_budget(reservation)
        return await local_cloud_asr_fallback(temp_path, language, tier, exc)
    except Exception as exc:
        if submitted_to_tencent:
            settle_tencent_asr_budget(reservation, reserved_duration_seconds)
        else:
            release_tencent_asr_budget(reservation)
        return await local_cloud_asr_fallback(temp_path, language, tier, exc)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temp_path)


@app.post(
    "/transcribe/stream/{session_id}",
    response_model=TranscribeResponse,
)
async def transcribe_stream_recording(
    session_id: str,
    principal: ApiPrincipal = Depends(require_api_token),
):
    if not re.fullmatch(r"[0-9a-f]{32}", session_id):
        raise HTTPException(status_code=400, detail="Invalid stream session id")
    recording = stream_recordings.get(session_id)
    if recording is None:
        raise HTTPException(status_code=404, detail="Stream recording is unavailable")
    if recording.owner_id != principal.owner_id and not principal.is_management:
        raise HTTPException(status_code=404, detail="Stream recording is unavailable")
    if recording.claimed:
        raise HTTPException(status_code=409, detail="Stream recording is already being finalized")

    recording.claimed = True
    try:
        await asyncio.wait_for(recording.ready.wait(), timeout=STT_STREAM_FINALIZE_WAIT_SEC)
    except asyncio.TimeoutError as exc:
        recording.claimed = False
        raise HTTPException(status_code=409, detail="Stream recording is still being finalized") from exc

    stream_recordings.pop(session_id, None)
    temp_path = str(recording.path)
    if recording.audio_bytes <= 0 or not recording.path.is_file() or recording.path.stat().st_size <= 44:
        with contextlib.suppress(FileNotFoundError):
            recording.path.unlink()
        raise HTTPException(status_code=400, detail="Stream recording is empty")

    print(
        f"Finalizing streamed audio: session={session_id}, bytes={recording.audio_bytes}",
        flush=True,
    )
    try:
        archived = await asyncio.to_thread(
            archive_audio_file,
            temp_path,
            owner_id=recording.owner_id,
            meeting_id=recording.meeting_id,
            source_kind="stream",
            original_filename="recording.wav",
            archive_key=session_id,
        )
        recording.archive_id = str(archived["id"]) if archived is not None else None
        final_tier = tencent_model_tier(recording.final_provider)
        if final_tier is not None and tencent_asr_configured(final_tier):
            reservation: TencentAsrBudgetReservation | None = None
            reserved_duration_seconds = 0.0
            enforce_budget = tencent_asr_budget_enforced(final_tier)
            try:
                if enforce_budget:
                    reserved_duration_seconds = audio_duration_for_tencent_budget(recording.path) or 0.0
                    if reserved_duration_seconds <= 0:
                        raise ValueError("Unable to determine streamed audio duration for cloud-model budget enforcement")
                    reservation = await asyncio.to_thread(
                        reserve_tencent_asr_budget,
                        final_tier,
                        "asr_rec",
                        reserved_duration_seconds,
                    )
                async with tencent_asr_semaphore:
                    text, _ = await asyncio.to_thread(
                        transcribe_with_tencent_flash_chunked,
                        recording.path,
                        "wav",
                        tier=final_tier,
                        language=recording.language,
                        record_usage=False,
                    )
                if enforce_budget:
                    settle_tencent_asr_budget(reservation, reserved_duration_seconds)
                reservation = None
                with contextlib.suppress(FileNotFoundError):
                    recording.path.unlink()
                push_debug_event(
                    "stream_finalized",
                    session_id=session_id,
                    stream_provider=recording.stream_provider,
                    final_provider=tencent_tier_config(final_tier).model_id,
                )
                return TranscribeResponse(text=text, language=recording.language)
            except TencentChunkedTranscriptionError as exc:
                if enforce_budget:
                    settle_tencent_asr_budget(reservation, reserved_duration_seconds)
                push_debug_event(
                    "stream_final_chunked_cloud_failed",
                    session_id=session_id,
                    stream_provider=recording.stream_provider,
                    requested_provider=recording.final_provider,
                    error=sanitize_upstream_error(exc),
                )
                raise HTTPException(
                    status_code=502,
                    detail=f"智悟增强云模型分段云转写失败: {sanitize_upstream_error(exc)}",
                ) from exc
            except Exception as exc:
                if enforce_budget:
                    if "4004" in str(exc):
                        release_tencent_asr_budget(reservation)
                    else:
                        settle_tencent_asr_budget(reservation, reserved_duration_seconds)
                push_debug_event(
                    "stream_final_fallback",
                    session_id=session_id,
                    stream_provider=recording.stream_provider,
                    requested_provider=recording.final_provider,
                    fallback_provider="faster-whisper",
                    error=str(exc),
                )
        return await run_inference(
            transcribe_spooled_file,
            temp_path,
            recording.language,
            label="stream-finalize",
        )
    except HTTPException:
        with contextlib.suppress(FileNotFoundError):
            recording.path.unlink()
        raise
    except Exception as exc:
        with contextlib.suppress(FileNotFoundError):
            recording.path.unlink()
        print(f"Stream transcription failed: {exc}", flush=True)
        raise HTTPException(status_code=500, detail="Stream transcription failed") from exc


def archived_audio_response(metadata: dict[str, Any]) -> ArchivedAudioResponse:
    archive_id = str(metadata["id"])
    return ArchivedAudioResponse(
        id=archive_id,
        meeting_id=str(metadata["meeting_id"]),
        created_at=str(metadata["created_at"]),
        bytes=int(metadata["bytes"]),
        duration_sec=metadata.get("duration_sec"),
        filename=str(metadata["filename"]),
        source=str(metadata["source"]),
        sha256=str(metadata.get("sha256", "")),
        download_path=f"/audio-archive/{archive_id}",
    )


@app.get("/audio-archive", response_model=ArchivedAudioListResponse)
async def audio_archive_list(
    meeting_id: str | None = None,
    principal: ApiPrincipal = Depends(require_api_token),
):
    try:
        items = await asyncio.to_thread(list_archived_audio, principal.owner_id, meeting_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ArchivedAudioListResponse(items=[archived_audio_response(item) for item in items])


@app.get("/audio-archive/{archive_id}")
async def audio_archive_download(
    archive_id: str,
    principal: ApiPrincipal = Depends(require_api_token),
):
    metadata = await asyncio.to_thread(find_archived_audio, principal.owner_id, archive_id)
    if metadata is None:
        raise HTTPException(status_code=404, detail="Archived audio is unavailable")
    audio_path = Path(str(metadata["_audio_path"]))
    return FileResponse(
        path=audio_path,
        filename=str(metadata["filename"]),
        media_type="audio/wav" if audio_path.suffix.lower() == ".wav" else "application/octet-stream",
    )


@app.delete("/audio-archive/{archive_id}")
async def audio_archive_delete(
    archive_id: str,
    principal: ApiPrincipal = Depends(require_api_token),
):
    deleted = await asyncio.to_thread(delete_archived_audio, principal.owner_id, archive_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Archived audio is unavailable")
    return {"status": "deleted", "id": archive_id}


@app.websocket("/ws/transcribe-stream")
async def transcribe_stream(websocket: WebSocket):
    await websocket.accept()
    principal = resolve_api_principal(websocket.headers.get("authorization"))
    if principal is None:
        await websocket.send_text(json.dumps({"type": "error", "message": "Unauthorized"}))
        await websocket.close(code=1008)
        return
    print(f"[WS] WebSocket accepted from {websocket.client} session will follow", flush=True)
    if model is None:
        await websocket.send_text(json.dumps({"type": "error", "message": "Model not loaded"}))
        await websocket.close(code=1011)
        return

    session_id = uuid4().hex
    admission_error = reserve_stream_session(session_id, principal.owner_id)
    if admission_error is not None:
        if admission_error == "owner":
            message = "当前账户正在进行的实时转写已达上限，请先结束已有会议"
        else:
            message = "实时转写服务繁忙，请稍后重试"
        push_debug_event("session_rejected", owner_limit=admission_error)
        await websocket.send_text(json.dumps({"type": "error", "message": message}))
        await websocket.close(code=1013)
        return
    with new_temp_file(".wav") as stream_file:
        stream_recording = StreamRecording(
            path=Path(stream_file.name),
            ready=asyncio.Event(),
            created_at=time.time(),
            owner_id=principal.owner_id,
        )
    stream_recordings[session_id] = stream_recording
    wave_writer = None
    pcm_buffer = bytearray()
    total_audio_bytes = 0
    sample_rate = 16000
    channels = 1
    last_sent_text = ""
    last_window_text = ""
    committed_text = ""
    active_preview_text = ""
    previous_preview_candidate = ""
    last_payload: tuple[str, str] = ("", "")
    last_processed_size = 0
    stream_stopped = False
    stream_stop_requested = False
    stream_started = False
    stream_provider = LOCAL_STREAM_PROVIDER
    language = "zh"
    tencent_bridge: TencentRealtimeBridge | None = None
    # Per-session params (can be overridden by client via start event)
    session_update_interval = STREAM_UPDATE_INTERVAL_SEC
    session_min_audio_sec = STREAM_MIN_AUDIO_SEC
    session_min_confidence = STREAM_MIN_CONFIDENCE
    session_max_no_speech_prob = STREAM_MAX_NO_SPEECH_PROB
    session_stable_frames = STREAM_STABLE_FRAMES
    session_reject_fallback_threshold = STREAM_REJECT_FALLBACK_THRESHOLD
    min_bytes = int(sample_rate * channels * 2 * session_min_audio_sec)
    push_debug_event("session_open", session_id=session_id)

    def ensure_wave_writer():
        nonlocal wave_writer
        if wave_writer is None:
            wave_writer = wave.open(str(stream_recording.path), "wb")
            wave_writer.setnchannels(channels)
            wave_writer.setsampwidth(2)
            wave_writer.setframerate(sample_rate)
        return wave_writer

    async def send_tencent_update(next_committed_text: str, next_preview_text: str) -> None:
        if stream_stopped:
            return
        display_text = normalize_preview_text(
            " ".join(part for part in (next_committed_text, next_preview_text) if part)
        )
        push_debug_event(
            "partial",
            session_id=session_id,
            stream_provider=stream_recording.stream_provider,
            text=display_text,
            committed_text=next_committed_text,
            preview_text=next_preview_text,
        )
        await websocket.send_text(
            json.dumps(
                {
                    "type": "partial",
                    "text": display_text,
                    "language": language,
                    "committed_text": next_committed_text,
                    "preview_text": next_preview_text,
                },
                ensure_ascii=False,
            )
        )

    async def handle_tencent_failure(message: str) -> None:
        nonlocal stream_provider
        failed_provider = stream_provider
        stream_provider = LOCAL_STREAM_PROVIDER
        stream_recording.stream_provider = f"{failed_provider}-fallback"
        stream_recording.final_provider = "faster-whisper"
        stream_recording.realtime_error = message
        push_debug_event(
            "realtime_fallback",
            session_id=session_id,
            fallback_provider=LOCAL_STREAM_PROVIDER,
            error=message,
        )
        if not stream_stopped:
            with contextlib.suppress(Exception):
                await websocket.send_text(
                    json.dumps(
                        {
                            "type": "status",
                            "message": "智悟增强云模型已停止，已切换到智悟本地模型实时预览",
                            "session_id": session_id,
                            "stream_provider": LOCAL_STREAM_PROVIDER,
                        },
                        ensure_ascii=False,
                    )
                )

    async def transcribe_loop():
        nonlocal last_sent_text, last_window_text, committed_text, active_preview_text
        nonlocal previous_preview_candidate, last_payload
        nonlocal last_processed_size, min_bytes, stream_stopped
        rejected_streak = 0
        loop = asyncio.get_event_loop()
        debug_audio_ticks = 0
        last_debug_time = loop.time()
        while not stream_stopped:
            await asyncio.sleep(session_update_interval)
            if stream_provider != LOCAL_STREAM_PROVIDER:
                continue
            current_size = len(pcm_buffer)
            current_total_bytes = total_audio_bytes
            debug_audio_ticks += 1
            # 每 3 秒输出一次缓冲状态，方便诊断
            now = loop.time()
            if now - last_debug_time >= 3.0:
                last_debug_time = now
                push_debug_event(
                    "audio_buffer_status",
                    session_id=session_id,
                    audio_bytes=current_total_bytes,
                    buffered_bytes=current_size,
                    min_bytes=min_bytes,
                    tick=debug_audio_ticks,
                    server_params={
                        "update_interval_sec": session_update_interval,
                        "min_audio_sec": session_min_audio_sec,
                        "stable_frames": session_stable_frames,
                    },
                )
            step_bytes = int(sample_rate * channels * 2 * STREAM_STEP_SEC)
            required_new_bytes = step_bytes if last_processed_size > 0 else max(min_bytes, step_bytes)
            if current_size < min_bytes or current_total_bytes - last_processed_size < required_new_bytes:
                continue

            bytes_per_second = sample_rate * channels * 2
            max_snapshot_bytes = int(bytes_per_second * STREAM_MAX_SNAPSHOT_SEC)
            overlap_bytes = int(bytes_per_second * STREAM_OVERLAP_SEC)
            buffer_start_total = current_total_bytes - current_size
            snapshot_start_total = max(buffer_start_total, last_processed_size - overlap_bytes)
            snapshot_end_total = min(current_total_bytes, snapshot_start_total + max_snapshot_bytes)
            relative_start = snapshot_start_total - buffer_start_total
            relative_end = snapshot_end_total - buffer_start_total
            snapshot = bytes(pcm_buffer[relative_start:relative_end])
            last_processed_size = snapshot_end_total
            try:
                inference_started_at = time.monotonic()
                inference_language = language
                result = await inference_scheduler.run(
                    transcribe_stream_snapshot,
                    snapshot,
                    sample_rate,
                    channels,
                    inference_language,
                    label=f"stream:{session_id}",
                )
                if inference_language != language:
                    continue
                push_debug_event(
                    "inference_completed",
                    session_id=session_id,
                    audio_bytes=current_total_bytes,
                    buffered_bytes=current_size,
                    snapshot_bytes=len(snapshot),
                    snapshot_start_bytes=snapshot_start_total,
                    snapshot_end_bytes=snapshot_end_total,
                    lag_bytes=current_total_bytes - snapshot_end_total,
                    elapsed_ms=round((time.monotonic() - inference_started_at) * 1000, 1),
                )
            except (InferenceQueueFullError, InferenceQueuePausedError) as exc:
                push_debug_event(
                    "queue_rejected",
                    session_id=session_id,
                    error=str(exc),
                    inference=inference_scheduler.stats(),
                )
                await websocket.send_text(
                    json.dumps({"type": "status", "message": "STT 服务繁忙，正在等待下一次识别"}, ensure_ascii=False)
                )
                continue
            except Exception as exc:
                push_debug_event(
                    "transcribe_error",
                    session_id=session_id,
                    audio_bytes=current_total_bytes,
                    buffered_bytes=current_size,
                    snapshot_bytes=len(snapshot),
                    error=str(exc),
                )
                print(f"[WS] Transcription error for session {session_id}: {exc}", flush=True)
                # Recreate executor if it was broken by a thread crash
                continue
            raw_text = normalize_preview_text(result["text"])
            final_compatible_preview = result.get("strategy") == "final-compatible"
            revisable_preview = result.get("strategy") == "revisable-preview"
            quality_gated_preview = final_compatible_preview or revisable_preview
            preview_candidate = ""
            preview_mode = ""
            preview_similarity = 0.0
            if stt_engine == "sensevoice":
                current_text = raw_text
                accepted_segments = []
                rejected_segments = []
            elif quality_gated_preview:
                (
                    preview_candidate,
                    preview_mode,
                    preview_similarity,
                    accepted_segments,
                    rejected_segments,
                ) = select_stream_preview(
                    raw_text,
                    previous_preview_candidate,
                    result.get("segments", []),
                    min_confidence=session_min_confidence,
                    max_no_speech_prob=session_max_no_speech_prob,
                )
                previous_preview_candidate = raw_text
                current_text = ""
            else:
                current_text, accepted_segments, rejected_segments = filter_stream_segments(
                    result["segments"],
                    min_confidence=session_min_confidence,
                    max_no_speech_prob=session_max_no_speech_prob,
                    # The second half is decoded again in the next overlapping
                    # window with more future context. Commit only the settled half.
                    settled_before_sec=STREAM_STEP_SEC,
                )
            if rejected_segments:
                push_debug_event(
                    "partial_rejected",
                    session_id=session_id,
                    audio_bytes=current_total_bytes,
                    buffered_bytes=current_size,
                    rejected_segments=rejected_segments,
                )
            if quality_gated_preview:
                if preview_candidate:
                    rejected_streak = 0
                else:
                    rejected_streak += 1
                    push_debug_event(
                        "partial_empty",
                        session_id=session_id,
                        audio_bytes=current_total_bytes,
                        buffered_bytes=current_size,
                        raw_text=raw_text,
                        segment_count=len(result.get("segments", [])),
                        rejected_streak=rejected_streak,
                    )
                if not raw_text:
                    push_debug_event(
                        "partial_empty",
                        session_id=session_id,
                        audio_bytes=current_total_bytes,
                        buffered_bytes=current_size,
                        raw_text="",
                        segment_count=len(result.get("segments", [])),
                        rejected_streak=0,
                    )
            elif not current_text:
                rejected_streak += 1
                push_debug_event(
                    "partial_empty",
                    session_id=session_id,
                    audio_bytes=current_total_bytes,
                    buffered_bytes=current_size,
                    raw_text=raw_text,
                    segment_count=len(result.get("segments", [])),
                    rejected_streak=rejected_streak,
                )
            else:
                rejected_streak = 0

            if final_compatible_preview:
                committed_text = ""
                last_sent_text = ""
                if preview_candidate:
                    active_preview_text = preview_candidate
                else:
                    push_debug_event(
                        "preview_held",
                        session_id=session_id,
                        audio_bytes=current_total_bytes,
                        buffered_bytes=current_size,
                        candidate_chars=len(raw_text),
                        similarity=round(preview_similarity, 3),
                        decision=preview_mode,
                    )
            elif revisable_preview:
                if preview_candidate:
                    if preview_mode == "stable":
                        committed_text = promote_revisable_preview(
                            committed_text,
                            active_preview_text,
                            preview_candidate,
                        )
                        active_preview_text = ""
                    else:
                        active_preview_text = merge_transcript_text(
                            active_preview_text,
                            preview_candidate,
                        )
                else:
                    fallback_preview = fallback_stream_preview(
                        raw_text,
                        rejected_streak,
                        session_reject_fallback_threshold,
                    )
                    if fallback_preview:
                        active_preview_text = merge_transcript_text(
                            active_preview_text,
                            fallback_preview,
                        )
                    else:
                        push_debug_event(
                            "preview_held",
                            session_id=session_id,
                            audio_bytes=current_total_bytes,
                            buffered_bytes=current_size,
                            candidate_chars=len(raw_text),
                            similarity=round(preview_similarity, 3),
                            decision=preview_mode,
                        )
            elif current_text:
                active_preview_text = ""
                if current_text != last_window_text:
                    delta_text = current_text
                    last_window_text = current_text
                    merged_text = merge_transcript_text(last_sent_text, delta_text)
                    if merged_text != last_sent_text:
                        committed_text = merged_text
                        last_sent_text = merged_text
            elif not current_text:
                fallback_preview = fallback_stream_preview(
                    raw_text,
                    rejected_streak,
                    session_reject_fallback_threshold,
                )
                if fallback_preview:
                    active_preview_text = fallback_preview

            wire_committed_text = committed_text
            wire_preview_text = active_preview_text
            if revisable_preview:
                wire_committed_text = ""
                wire_preview_text = merge_transcript_text(committed_text, active_preview_text)
            payload = (wire_committed_text, wire_preview_text)
            if payload == last_payload or (not committed_text and not active_preview_text):
                continue
            last_payload = payload
            display_text = normalize_preview_text(
                " ".join(part for part in payload if part)
            )
            push_debug_event(
                "partial",
                session_id=session_id,
                audio_bytes=current_total_bytes,
                buffered_bytes=current_size,
                snapshot_bytes=len(snapshot),
                text=display_text,
                window_text=current_text,
                committed_text=committed_text,
                preview_text=active_preview_text,
                preview_is_fallback=False,
                preview_quality_gate=preview_mode,
                preview_mode=preview_mode,
                preview_similarity=round(preview_similarity, 3),
                accepted_segments=accepted_segments,
            )
            await websocket.send_text(
                json.dumps(
                    {
                        "type": "partial",
                        "text": display_text,
                        "language": result["language"],
                        "committed_text": wire_committed_text,
                        "preview_text": wire_preview_text,
                    },
                    ensure_ascii=False
                )
            )

    worker = asyncio.create_task(transcribe_loop())
    print(f"[WS] Background transcribe_loop task created for session {session_id}", flush=True)

    try:
        while True:
            if stream_stopped:
                break
            message = await websocket.receive()
            if message.get("type") == "websocket.disconnect":
                raise WebSocketDisconnect(int(message.get("code") or 1000))
            if "text" in message and message["text"] is not None:
                payload = json.loads(message["text"])
                event = payload.get("event")
                if event == "start":
                    if stream_started:
                        await websocket.send_text(json.dumps({"type": "error", "message": "Stream already started"}))
                        await websocket.close(code=1008)
                        stream_stopped = True
                        break
                    stream_started = True
                    sample_rate = int(payload.get("sample_rate", 16000))
                    channels = int(payload.get("channels", 1))
                    try:
                        language = normalize_stt_language(payload.get("language"))
                    except ValueError:
                        await websocket.send_text(json.dumps({"type": "error", "message": "Invalid STT language"}))
                        await websocket.close(code=1008)
                        stream_stopped = True
                        break
                    stream_recording.language = language
                    requested_provider = str(
                        payload.get("stream_provider") or LOCAL_STREAM_PROVIDER
                    ).strip().lower()
                    if requested_provider != LOCAL_STREAM_PROVIDER and tencent_stream_tier(requested_provider) is None:
                        await websocket.send_text(json.dumps({"type": "error", "message": "Invalid stream provider"}))
                        await websocket.close(code=1008)
                        stream_stopped = True
                        break
                    try:
                        stream_recording.meeting_id = normalize_archive_meeting_id(payload.get("meeting_id"))
                    except ValueError:
                        await websocket.send_text(json.dumps({"type": "error", "message": "Invalid meeting id"}))
                        await websocket.close(code=1008)
                        stream_stopped = True
                        break
                    # 支持客户端传入流式参数，覆盖默认值
                    session_update_interval = float(payload.get("update_interval_sec")) if payload.get("update_interval_sec") else session_update_interval
                    requested_min_audio_sec = (
                        float(payload.get("min_audio_sec"))
                        if payload.get("min_audio_sec")
                        else session_min_audio_sec
                    )
                    session_min_audio_sec = max(requested_min_audio_sec, STREAM_FINAL_COMPAT_MIN_AUDIO_SEC)
                    session_min_confidence = float(payload.get("min_confidence")) if payload.get("min_confidence") else session_min_confidence
                    session_max_no_speech_prob = float(payload.get("max_no_speech_prob")) if payload.get("max_no_speech_prob") else session_max_no_speech_prob
                    session_stable_frames = int(payload.get("stable_frames")) if payload.get("stable_frames") else session_stable_frames
                    session_reject_fallback_threshold = int(payload.get("reject_fallback_threshold")) if payload.get("reject_fallback_threshold") else session_reject_fallback_threshold
                    if not 8000 <= sample_rate <= 48000 or channels not in {1, 2}:
                        await websocket.send_text(json.dumps({"type": "error", "message": "Invalid audio format"}))
                        await websocket.close(code=1008)
                        stream_stopped = True
                        break
                    if not all(
                        math.isfinite(value)
                        for value in (
                            session_update_interval,
                            session_min_audio_sec,
                            session_min_confidence,
                            session_max_no_speech_prob,
                        )
                    ):
                        await websocket.send_text(json.dumps({"type": "error", "message": "Invalid stream parameters"}))
                        await websocket.close(code=1008)
                        stream_stopped = True
                        break
                    session_update_interval = max(0.2, min(session_update_interval, 10.0))
                    session_min_audio_sec = max(0.2, min(session_min_audio_sec, 10.0))
                    session_min_confidence = max(-10.0, min(session_min_confidence, 0.0))
                    session_max_no_speech_prob = max(0.0, min(session_max_no_speech_prob, 1.0))
                    session_stable_frames = max(1, min(session_stable_frames, 10))
                    session_reject_fallback_threshold = max(1, min(session_reject_fallback_threshold, 20))
                    min_bytes = int(sample_rate * channels * 2 * session_min_audio_sec)
                    ensure_wave_writer()
                    stream_provider = requested_provider
                    stream_recording.stream_provider = requested_provider
                    requested_tier = tencent_stream_tier(requested_provider)
                    if requested_tier is not None:
                        tier_config = tencent_tier_config(requested_tier)
                        stream_recording.final_provider = (
                            tier_config.model_id
                            if tencent_asr_configured(requested_tier)
                            else "faster-whisper"
                        )
                        if sample_rate != 16000 or channels != 1:
                            await handle_tencent_failure("智悟增强云模型需要 16 kHz 单声道音频")
                        elif not tencent_realtime_asr_configured(requested_tier):
                            await handle_tencent_failure("当前智悟增强云模型档位不可用")
                        else:
                            try:
                                tencent_bridge = TencentRealtimeBridge(
                                    send_tencent_update,
                                    handle_tencent_failure,
                                    tier=requested_tier,
                                    language=language,
                                )
                                await tencent_bridge.start()
                            except Exception as exc:
                                if tencent_bridge is not None:
                                    await tencent_bridge.abort()
                                    tencent_bridge = None
                                await handle_tencent_failure(sanitize_upstream_error(exc))
                    push_debug_event(
                        "session_start",
                        session_id=session_id,
                        sample_rate=sample_rate,
                        channels=channels,
                        language=language,
                        stream_provider=stream_recording.stream_provider,
                        final_provider=stream_recording.final_provider,
                        stream_params={
                            "update_interval_sec": session_update_interval,
                            "min_audio_sec": session_min_audio_sec,
                            "stable_frames": session_stable_frames,
                        },
                    )
                    await websocket.send_text(
                        json.dumps(
                            {
                                "type": "status",
                                "message": (
                                    "智悟增强云模型已连接"
                                    if tencent_stream_tier(stream_provider) is not None
                                    else "高精度预览已连接，正在积累首段音频"
                                ),
                                "session_id": session_id,
                                "stream_provider": stream_recording.stream_provider,
                                "language": language,
                            },
                            ensure_ascii=False,
                        )
                    )
                elif event == "switch_language":
                    if not stream_started:
                        await websocket.send_text(
                            json.dumps({"type": "error", "message": "Stream has not started"})
                        )
                        continue
                    try:
                        requested_language = normalize_stt_language(payload.get("language"))
                    except ValueError:
                        await websocket.send_text(
                            json.dumps({"type": "error", "message": "Invalid STT language"})
                        )
                        continue
                    if requested_language == language:
                        await websocket.send_text(
                            json.dumps(
                                {
                                    "type": "status",
                                    "message": "当前识别语言已启用",
                                    "session_id": session_id,
                                    "stream_provider": stream_provider,
                                    "language": language,
                                },
                                ensure_ascii=False,
                            )
                        )
                        continue

                    requested_tier = tencent_stream_tier(stream_provider)
                    if requested_tier is not None:
                        next_bridge: TencentRealtimeBridge | None = None
                        try:
                            next_bridge = TencentRealtimeBridge(
                                send_tencent_update,
                                handle_tencent_failure,
                                tier=requested_tier,
                                language=requested_language,
                            )
                            await next_bridge.start()
                        except Exception as exc:
                            if next_bridge is not None:
                                await next_bridge.abort()
                            await websocket.send_text(
                                json.dumps(
                                    {"type": "error", "message": sanitize_upstream_error(exc)},
                                    ensure_ascii=False,
                                )
                            )
                            continue
                        if tencent_bridge is not None:
                            await tencent_bridge.abort()
                        tencent_bridge = next_bridge

                    language = requested_language
                    stream_recording.language = language
                    last_sent_text = ""
                    last_window_text = ""
                    committed_text = ""
                    active_preview_text = ""
                    previous_preview_candidate = ""
                    last_payload = ("", "")
                    pcm_buffer.clear()
                    last_processed_size = total_audio_bytes
                    push_debug_event(
                        "stream_language_switch",
                        session_id=session_id,
                        language=language,
                        stream_provider=stream_provider,
                    )
                    await websocket.send_text(
                        json.dumps(
                            {
                                "type": "status",
                                "message": f"已切换至{'English' if language == 'en' else '中文'}识别",
                                "session_id": session_id,
                                "stream_provider": stream_provider,
                                "language": language,
                            },
                            ensure_ascii=False,
                        )
                    )
                elif event == "switch_provider":
                    if not stream_started:
                        await websocket.send_text(
                            json.dumps(
                                {"type": "error", "message": "Stream has not started"}
                            )
                        )
                        continue
                    requested_provider = str(
                        payload.get("stream_provider") or ""
                    ).strip().lower()
                    if requested_provider != LOCAL_STREAM_PROVIDER and tencent_stream_tier(requested_provider) is None:
                        await websocket.send_text(
                            json.dumps(
                                {"type": "error", "message": "Invalid stream provider"}
                            )
                        )
                        continue
                    if requested_provider == stream_provider:
                        await websocket.send_text(
                            json.dumps(
                                {
                                    "type": "status",
                                    "message": "当前识别引擎已启用",
                                    "session_id": session_id,
                                    "stream_provider": stream_provider,
                                },
                                ensure_ascii=False,
                            )
                        )
                        continue

                    requested_tier = tencent_stream_tier(requested_provider)
                    if requested_tier is not None:
                        switch_error = ""
                        if sample_rate != 16000 or channels != 1:
                            switch_error = "智悟增强云模型需要 16 kHz 单声道音频"
                        elif not tencent_realtime_asr_configured(requested_tier):
                            switch_error = "当前智悟增强云模型档位不可用"
                        if switch_error:
                            await websocket.send_text(
                                json.dumps(
                                    {"type": "error", "message": switch_error},
                                    ensure_ascii=False,
                                )
                            )
                            continue
                        next_bridge: TencentRealtimeBridge | None = None
                        try:
                            next_bridge = TencentRealtimeBridge(
                                send_tencent_update,
                                handle_tencent_failure,
                                tier=requested_tier,
                                language=language,
                            )
                            await next_bridge.start()
                        except Exception as exc:
                            if next_bridge is not None:
                                await next_bridge.abort()
                            await websocket.send_text(
                                json.dumps(
                                    {
                                        "type": "error",
                                        "message": sanitize_upstream_error(exc),
                                    },
                                    ensure_ascii=False,
                                )
                            )
                            continue
                        if tencent_bridge is not None:
                            await tencent_bridge.abort()
                        tencent_bridge = next_bridge
                        stream_provider = requested_provider
                        stream_recording.stream_provider = stream_provider
                        stream_recording.final_provider = (
                            tencent_tier_config(requested_tier).model_id
                            if tencent_asr_configured(requested_tier)
                            else "faster-whisper"
                        )
                        stream_recording.realtime_error = ""
                        status_message = "已切换至智悟增强云模型"
                    else:
                        if tencent_bridge is not None:
                            await tencent_bridge.abort()
                            tencent_bridge = None
                        stream_provider = LOCAL_STREAM_PROVIDER
                        stream_recording.stream_provider = stream_provider
                        stream_recording.final_provider = "faster-whisper"
                        status_message = "已切换至智悟本地模型实时预览"

                    push_debug_event(
                        "stream_provider_switch",
                        session_id=session_id,
                        stream_provider=stream_provider,
                        final_provider=stream_recording.final_provider,
                    )
                    await websocket.send_text(
                        json.dumps(
                            {
                                "type": "status",
                                "message": status_message,
                                "session_id": session_id,
                                "stream_provider": stream_provider,
                                "language": language,
                            },
                            ensure_ascii=False,
                        )
                    )
                elif event == "stop":
                    stream_stop_requested = True
                    stream_stopped = True
                    push_debug_event(
                        "session_stop",
                        session_id=session_id,
                        final_text=last_sent_text,
                        committed_text=committed_text,
                    )
                    break

            if "bytes" in message and message["bytes"] is not None:
                if not stream_started:
                    await websocket.send_text(json.dumps({"type": "error", "message": "Stream has not started"}))
                    await websocket.close(code=1008)
                    stream_stopped = True
                    break
                received = len(message["bytes"])
                total_audio_bytes += received
                ensure_wave_writer().writeframesraw(message["bytes"])
                pcm_buffer.extend(message["bytes"])
                if tencent_stream_tier(stream_provider) is not None and tencent_bridge is not None:
                    try:
                        await tencent_bridge.feed(message["bytes"])
                    except Exception as exc:
                        await handle_tencent_failure(sanitize_upstream_error(exc))
                max_buffer_bytes = int(sample_rate * channels * 2 * STREAM_BUFFER_SEC)
                if len(pcm_buffer) > max_buffer_bytes:
                    del pcm_buffer[:-max_buffer_bytes]
                if total_audio_bytes % 16000 == 0:  # log every half-second of 16k mono PCM
                    print(f"[WS] Received {received} bytes, total={total_audio_bytes} bytes, buffered={len(pcm_buffer)} bytes", flush=True)
    except WebSocketDisconnect:
        stream_stopped = True
        push_debug_event("session_disconnect", session_id=session_id)
    except Exception as exc:
        push_debug_event("session_error", session_id=session_id, error=str(exc))
        raise
    finally:
        stream_stopped = True
        release_stream_session(session_id, principal.owner_id)
        worker.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await worker
        if wave_writer is not None:
            with contextlib.suppress(Exception):
                wave_writer.close()
        if tencent_bridge is not None:
            if stream_stop_requested:
                await tencent_bridge.finish()
            else:
                await tencent_bridge.abort()
        stream_recording.audio_bytes = total_audio_bytes
        stream_recording.ready.set()
        if not stream_stop_requested:
            stream_recordings.pop(session_id, None)
            with contextlib.suppress(FileNotFoundError):
                stream_recording.path.unlink()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=os.getenv("STT_BIND_IP", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.getenv("STT_PORT", "8888")))
    parser.add_argument("--model", default=os.getenv("STT_MODEL", "small"))
    parser.add_argument("--engine", default=os.getenv("STT_ENGINE", "faster-whisper"), choices=["faster-whisper", "sensevoice"])
    parser.add_argument("--device", default=STT_DEVICE, choices=["auto", "cpu", "cuda"])
    args = parser.parse_args()
    STT_DEVICE = args.device

    if STT_REQUIRE_API_TOKEN and not STT_API_TOKEN:
        print("STT_API_TOKEN is required when STT_REQUIRE_API_TOKEN=1", flush=True)
        raise SystemExit(2)

    try:
        load_model(args.model, engine=args.engine)
        load_stream_model()
        if stream_model_error:
            raise RuntimeError(stream_model_error)
    except Exception as exc:
        model_load_error = str(exc)
        print(f"Model load failed, starting admin endpoints only: {exc}", flush=True)
    uvicorn.run(app, host=args.host, port=args.port)
