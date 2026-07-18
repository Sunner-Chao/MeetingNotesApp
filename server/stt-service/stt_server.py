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
import contextlib
import difflib
import hashlib
import hmac
import json
import math
import re
import sys
import tempfile
import threading
import time
import wave
from collections import deque
from datetime import datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

import ctranslate2
import faster_whisper
from opencc import OpenCC

try:
    from funasr import AutoModel as FunASRAutoModel
except Exception:
    FunASRAutoModel = None
import uvicorn
from fastapi import Depends, Header, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi import FastAPI
from pydantic import BaseModel

from inference_scheduler import (
    InferenceQueueFullError,
    InferenceQueuePausedError,
    InferenceScheduler,
)

model = None
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


STT_MAX_CONCURRENT = positive_int_env("STT_MAX_CONCURRENT", 2)
STT_MAX_QUEUE = positive_int_env("STT_MAX_QUEUE", 16)
STT_MAX_STREAMS = positive_int_env("STT_MAX_STREAMS", 16)
STT_MAX_UPLOAD_MB = positive_int_env("STT_MAX_UPLOAD_MB", 256)
STT_UPLOAD_CHUNK_BYTES = 1024 * 1024
STT_SWITCH_DRAIN_TIMEOUT_SEC = float(os.getenv("STT_SWITCH_DRAIN_TIMEOUT_SEC", "900"))
STT_DEVICE = os.getenv("STT_DEVICE", "auto").strip().lower()
STT_CPU_THREADS = positive_int_env("STT_CPU_THREADS", max(1, (os.cpu_count() or 2) // STT_MAX_CONCURRENT))
STT_API_TOKEN = os.getenv("STT_API_TOKEN", "").strip()
STT_REQUIRE_API_TOKEN = os.getenv("STT_REQUIRE_API_TOKEN", "0").strip().lower() in {"1", "true", "yes"}
STT_MODEL_SHA256 = os.getenv("STT_MODEL_SHA256", "").strip().lower()
STT_STREAM_MODEL = os.getenv("STT_STREAM_MODEL", "small").strip() or "small"
STT_STREAM_MODEL_SHA256 = os.getenv("STT_STREAM_MODEL_SHA256", "").strip().lower()
STT_STREAM_CPU_THREADS = positive_int_env("STT_STREAM_CPU_THREADS", STT_CPU_THREADS)
STT_FINAL_RETRY_MIN_CHARS = positive_int_env("STT_FINAL_RETRY_MIN_CHARS", 8)
FINAL_BEAM_SIZE = 5
STT_TEMP_DIR = Path(os.getenv("STT_TEMP_DIR", tempfile.gettempdir())).resolve()
STT_TEMP_MAX_AGE_SEC = positive_int_env("STT_TEMP_MAX_AGE_SEC", 21600)
STT_TEMP_CLEANUP_INTERVAL_SEC = positive_int_env("STT_TEMP_CLEANUP_INTERVAL_SEC", 3600)
STT_TEMP_PREFIX = "meetingnotes-stt-"
STT_ALLOWED_MODELS = {
    item.strip()
    for item in os.getenv(
        "STT_ALLOWED_MODELS",
        "tiny,base,small,medium,large-v3,SenseVoiceSmall,iic/SenseVoiceSmall",
    ).split(",")
    if item.strip()
}

inference_scheduler = InferenceScheduler(STT_MAX_CONCURRENT, STT_MAX_QUEUE)
model_switch_lock = asyncio.Lock()
sensevoice_inference_lock = threading.Lock()
active_stream_sessions: set[str] = set()
model_checksum_verified = False
temp_files_cleaned = 0

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
stream_debug_events: deque[dict[str, Any]] = deque(maxlen=STREAM_DEBUG_EVENT_LIMIT)
simplified_chinese = OpenCC("t2s")


class TranscribeResponse(BaseModel):
    text: str
    language: str = "zh"


class SwitchSTTRequest(BaseModel):
    engine: str
    model: str | None = None


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


async def temp_cleanup_loop() -> None:
    global temp_files_cleaned
    while True:
        await asyncio.sleep(STT_TEMP_CLEANUP_INTERVAL_SEC)
        temp_files_cleaned += await asyncio.to_thread(cleanup_stale_temp_files)


def remove_angle_tags(text: str) -> str:
    return re.sub(r"<[^>\r\n]{0,120}>", " ", text or "")


def normalize_preview_text(text: str) -> str:
    normalized = " ".join(remove_angle_tags(text).split()).strip()
    return simplified_chinese.convert(normalized)


def is_known_hallucination(text: str) -> bool:
    normalized = normalize_preview_text(text).strip("，。！？,.!? ")
    return normalized in STREAM_HALLUCINATION_PHRASES


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
    global model, model_size, model_source, stt_engine, model_load_error, model_checksum_verified
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

    return run_transcribe(
        beam_size=beam_size,
        vad_filter=vad_filter,
        language="zh",
        condition_on_previous_text=False,
    )


def transcribe_faster_whisper_file(active_model, file_path: str) -> dict[str, Any]:
    text, info, segment_rows = decode_faster_whisper_file(
        active_model,
        file_path,
        beam_size=FINAL_BEAM_SIZE,
    )
    if len(normalize_preview_text(text)) < STT_FINAL_RETRY_MIN_CHARS:
        retry_text, retry_info, retry_segments = decode_faster_whisper_file(
            active_model,
            file_path,
            beam_size=1,
            vad_filter=False,
        )
        if not is_known_hallucination(retry_text) and len(retry_text) > len(text):
            text, info, segment_rows = retry_text, retry_info, retry_segments

    text = normalize_preview_text(text)
    if is_known_hallucination(text):
        text = ""
    return {
        "text": text,
        "language": info.language,
        "segments": segment_rows,
        "strategy": "final-compatible",
    }


def transcribe_faster_whisper_preview_file(active_model, file_path: str) -> dict[str, Any]:
    """Decode a revisable preview without the final transcript retry."""
    text, info, segment_rows = decode_faster_whisper_file(
        active_model,
        file_path,
        beam_size=STREAM_BEAM_SIZE,
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


def transcribe_file(file_path: str) -> TranscribeResponse:
    if model is None:
        raise RuntimeError("Model not loaded")

    if stt_engine == "sensevoice":
        # FunASR model objects keep mutable decoding state, so one shared instance
        # must be serialized. Faster-Whisper remains parallel through its workers.
        with sensevoice_inference_lock:
            result = model.generate(input=file_path)
        text = ""
        language = "zh"
        if isinstance(result, list) and result:
            item = result[0]
            if isinstance(item, dict):
                text = str(item.get("text", "") or "").strip()
                language = str(item.get("lang", "zh") or "zh")
            else:
                text = str(item).strip()
        elif isinstance(result, dict):
            text = str(result.get("text", "") or "").strip()
            language = str(result.get("lang", "zh") or "zh")
        else:
            text = str(result or "").strip()

        text = normalize_preview_text(text)
        if is_known_hallucination(text):
            text = ""
        print(f"SenseVoice transcription completed: chars={len(text)}, language={language}", flush=True)
        return TranscribeResponse(text=text, language=language)

    result = transcribe_faster_whisper_file(model, file_path)
    print(
        f"Transcription completed: chars={len(result['text'])}, language={result['language']}",
        flush=True,
    )
    return TranscribeResponse(text=result["text"], language=result["language"])


def transcribe_audio(audio_data: bytes, suffix: str = ".m4a") -> TranscribeResponse:
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
        return transcribe_file(tmp_path)
    finally:
        os.unlink(tmp_path)


def transcribe_stream_pcm(pcm_bytes: bytes, sample_rate: int = 16000, channels: int = 1) -> TranscribeResponse:
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
        return transcribe_file(tmp_path)
    finally:
        os.unlink(tmp_path)


def transcribe_stream_snapshot(pcm_bytes: bytes, sample_rate: int = 16000, channels: int = 1) -> dict[str, Any]:
    if len(pcm_bytes) == 0:
        return {
            "text": "",
            "language": "zh",
            "segments": [],
        }

    # SenseVoice streaming preview currently uses whole-buffer snapshot text.
    if stt_engine == "sensevoice":
        response = transcribe_stream_pcm(pcm_bytes, sample_rate=sample_rate, channels=channels)
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

        return transcribe_faster_whisper_preview_file(stream_model or model, tmp_path)
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


def is_api_token_valid(authorization: str | None) -> bool:
    if not STT_API_TOKEN:
        return True
    if not authorization or not authorization.startswith("Bearer "):
        return False
    return hmac.compare_digest(authorization[7:], STT_API_TOKEN)


def require_api_token(authorization: str | None = Header(default=None)) -> None:
    if not is_api_token_valid(authorization):
        raise HTTPException(status_code=401, detail="Missing or invalid bearer token")


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


def transcribe_spooled_file(temp_path: str) -> TranscribeResponse:
    try:
        return transcribe_file(temp_path)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temp_path)


@contextlib.asynccontextmanager
async def app_lifespan(_app: FastAPI):
    global temp_files_cleaned
    if STT_REQUIRE_API_TOKEN and not STT_API_TOKEN:
        raise RuntimeError("STT_API_TOKEN is required when STT_REQUIRE_API_TOKEN=1")
    temp_files_cleaned += await asyncio.to_thread(cleanup_stale_temp_files)
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
        "streams": {
            "active": len(active_stream_sessions),
            "max": STT_MAX_STREAMS,
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
        "temp_files": {
            "directory": str(STT_TEMP_DIR),
            "cleaned": temp_files_cleaned,
            "max_age_sec": STT_TEMP_MAX_AGE_SEC,
        },
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


@app.post("/admin/stt/switch", dependencies=[Depends(require_api_token)])
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

    push_debug_event(
        "switch_requested",
        from_engine=stt_engine,
        from_model=model_size,
        target_engine=engine,
        target_model=model_name,
    )

    async with model_switch_lock:
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
    dependencies=[Depends(require_api_token)],
)
async def transcribe(file: UploadFile):
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    temp_path, upload_bytes = await spool_upload(file)
    print(f"Received audio upload: {upload_bytes} bytes -> {temp_path}", flush=True)
    try:
        return await run_inference(transcribe_spooled_file, temp_path, label="file-upload")
    except HTTPException:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temp_path)
        raise
    except Exception as exc:
        print(f"File transcription failed: {exc}", flush=True)
        raise HTTPException(status_code=500, detail="Audio transcription failed") from exc


@app.websocket("/ws/transcribe-stream")
async def transcribe_stream(websocket: WebSocket):
    await websocket.accept()
    if not is_api_token_valid(websocket.headers.get("authorization")):
        await websocket.send_text(json.dumps({"type": "error", "message": "Unauthorized"}))
        await websocket.close(code=1008)
        return
    print(f"[WS] WebSocket accepted from {websocket.client} session will follow", flush=True)
    if model is None:
        await websocket.send_text(json.dumps({"type": "error", "message": "Model not loaded"}))
        await websocket.close(code=1011)
        return

    session_id = uuid4().hex[:8]
    if len(active_stream_sessions) >= STT_MAX_STREAMS:
        await websocket.send_text(json.dumps({"type": "error", "message": "Too many streaming sessions"}))
        await websocket.close(code=1013)
        return
    active_stream_sessions.add(session_id)
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
    # Per-session params (can be overridden by client via start event)
    session_update_interval = STREAM_UPDATE_INTERVAL_SEC
    session_min_audio_sec = STREAM_MIN_AUDIO_SEC
    session_min_confidence = STREAM_MIN_CONFIDENCE
    session_max_no_speech_prob = STREAM_MAX_NO_SPEECH_PROB
    session_stable_frames = STREAM_STABLE_FRAMES
    session_reject_fallback_threshold = STREAM_REJECT_FALLBACK_THRESHOLD
    min_bytes = int(sample_rate * channels * 2 * session_min_audio_sec)
    push_debug_event("session_open", session_id=session_id)

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
                result = await inference_scheduler.run(
                    transcribe_stream_snapshot,
                    snapshot,
                    sample_rate,
                    channels,
                    label=f"stream:{session_id}",
                )
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
                        committed_text = merge_transcript_text(committed_text, preview_candidate)
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
            if "text" in message and message["text"] is not None:
                payload = json.loads(message["text"])
                event = payload.get("event")
                if event == "start":
                    sample_rate = int(payload.get("sample_rate", 16000))
                    channels = int(payload.get("channels", 1))
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
                    push_debug_event(
                        "session_start",
                        session_id=session_id,
                        sample_rate=sample_rate,
                        channels=channels,
                        stream_params={
                            "update_interval_sec": session_update_interval,
                            "min_audio_sec": session_min_audio_sec,
                            "stable_frames": session_stable_frames,
                        },
                    )
                    await websocket.send_text(
                        json.dumps(
                            {"type": "status", "message": "高精度预览已连接，正在积累首段音频"},
                            ensure_ascii=False,
                        )
                    )
                elif event == "stop":
                    stream_stopped = True
                    push_debug_event(
                        "session_stop",
                        session_id=session_id,
                        final_text=last_sent_text,
                        committed_text=committed_text,
                    )
                    break

            if "bytes" in message and message["bytes"] is not None:
                received = len(message["bytes"])
                total_audio_bytes += received
                pcm_buffer.extend(message["bytes"])
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
        active_stream_sessions.discard(session_id)
        worker.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await worker


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
