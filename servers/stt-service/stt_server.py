#!/usr/bin/env python3
"""
OA助手 STT Server
Supports both file-based transcription and WebSocket streaming preview.
"""

import os
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"

import argparse
import asyncio
import contextlib
import json
import re
import subprocess
import sys
import tempfile
import wave
from concurrent.futures import ThreadPoolExecutor
from collections import deque
from datetime import datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

import faster_whisper

try:
    import torch
except Exception:
    torch = None

try:
    from funasr import AutoModel as FunASRAutoModel
except Exception:
    FunASRAutoModel = None
import uvicorn
from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from pydantic import BaseModel

model = None
stt_engine = "faster-whisper"
model_size = "small"
model_source = "small"
model_load_error = ""
model_root = Path(os.getenv("STT_MODEL_ROOT", "./models")).resolve()
executor = ThreadPoolExecutor(max_workers=2)

# Streaming transcription parameters (can be overridden per-session via WebSocket start event)
STREAM_UPDATE_INTERVAL_SEC = float(os.getenv("STREAM_UPDATE_INTERVAL_SEC", "0.6"))
STREAM_MIN_AUDIO_SEC = float(os.getenv("STREAM_MIN_AUDIO_SEC", "0.4"))
STREAM_DEBUG_EVENT_LIMIT = int(os.getenv("STREAM_DEBUG_EVENT_LIMIT", "200"))
# 最低置信度阈值，越小越宽松（稳定内容多），默认 -2.0，建议 -2.5 ~ -1.5
STREAM_MIN_CONFIDENCE = float(os.getenv("STREAM_MIN_CONFIDENCE", "-2.0"))
# 无语音概率上限，越大越宽松，默认 0.92，建议 0.88-0.95
STREAM_MAX_NO_SPEECH_PROB = float(os.getenv("STREAM_MAX_NO_SPEECH_PROB", "0.92"))
# 连续相同结果帧数达到此值则提前 commit，越小稳定越快，默认 2，建议 1-3
STREAM_STABLE_FRAMES = int(os.getenv("STREAM_STABLE_FRAMES", "2"))
# 连续被过滤帧数达到此阈值后 fail-open（避免预览卡死），默认 2
STREAM_REJECT_FALLBACK_THRESHOLD = int(os.getenv("STREAM_REJECT_FALLBACK_THRESHOLD", "2"))
STREAM_SNAPSHOT_PAD_SEC = float(os.getenv("STREAM_SNAPSHOT_PAD_SEC", "0.3"))
STREAM_MAX_SNAPSHOT_SEC = float(os.getenv("STREAM_MAX_SNAPSHOT_SEC", "25"))
STREAM_HALLUCINATION_PHRASES = [
    phrase.strip()
    for phrase in os.getenv(
        "STREAM_HALLUCINATION_PHRASES",
        "字幕by索兰娅,字幕製作人Zither Harp,字幕制作人Zither Harp,字幕製作人,字幕制作人,Zither Harp"
    ).split(",")
    if phrase.strip()
]
stream_debug_events: deque[dict[str, Any]] = deque(maxlen=STREAM_DEBUG_EVENT_LIMIT)


class TranscribeResponse(BaseModel):
    text: str
    language: str = "zh"


class SwitchSTTRequest(BaseModel):
    engine: str
    model: str | None = None


def utc_now() -> str:
    return datetime.utcnow().isoformat(timespec="seconds") + "Z"


def remove_angle_tags(text: str) -> str:
    return re.sub(r"<[^>\r\n]{0,120}>", " ", text or "")


def normalize_preview_text(text: str) -> str:
    return " ".join(remove_angle_tags(text).split()).strip()


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


def load_model(size: str = "small", engine: str = "faster-whisper"):
    global model, model_size, model_source, stt_engine, model_load_error
    stt_engine = engine
    model_size = size
    model = None
    model_load_error = ""
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
            model_source = str(candidate_path)
        else:
            model_source = size if "/" in size else "iic/SenseVoiceSmall"

        print(f"Loading SenseVoice model: {model_source}", flush=True)
        print(f"Model root: {model_root}", flush=True)
        model = FunASRAutoModel(
            model=model_source,
            trust_remote_code=True,
        )
        print("SenseVoice model loaded successfully!", flush=True)
        return

    fw_root = (model_root / "faster-whisper").resolve()
    fw_root.mkdir(parents=True, exist_ok=True)

    candidate_path = Path(size)
    if not candidate_path.is_absolute():
        candidate_path = (fw_root / size).resolve()

    if candidate_path.exists() and has_valid_fw_model_dir(candidate_path):
        model_source = str(candidate_path)
    else:
        model_source = size

    print(f"Loading Faster-Whisper model: {model_source}", flush=True)
    print(f"Model root: {model_root}", flush=True)
    print(f"Using HF Mirror: {os.environ.get('HF_ENDPOINT', 'default')}", flush=True)

    prefer_gpu = torch is not None and torch.cuda.is_available()
    if prefer_gpu:
        try:
            model = faster_whisper.WhisperModel(
                model_source,
                device="cuda",
                compute_type="float16",
                download_root=str(fw_root)
            )
            print("Faster-Whisper model loaded successfully on CUDA (float16)!", flush=True)
            return
        except Exception as gpu_exc:
            print(f"CUDA init failed, fallback to CPU: {gpu_exc}", flush=True)

    model = faster_whisper.WhisperModel(
        model_source,
        device="cpu",
        compute_type="int8",
        download_root=str(fw_root)
    )
    print("Faster-Whisper model loaded successfully on CPU (int8)!", flush=True)


def transcribe_file(file_path: str) -> TranscribeResponse:
    if model is None:
        raise RuntimeError("Model not loaded")

    if stt_engine == "sensevoice":
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
        print(f"SenseVoice transcription result: '{text}'", flush=True)
        return TranscribeResponse(text=text, language=language)

    def run_transcribe(**kwargs):
        segments, info = model.transcribe(file_path, **kwargs)
        segment_rows: list[dict[str, Any]] = []
        text_parts = []
        for segment in segments:
            cleaned = segment.text.strip()
            segment_rows.append(
                {
                    "start": float(segment.start),
                    "end": float(segment.end),
                    "text": cleaned,
                }
            )
            text_parts.append(cleaned)
        return " ".join(part for part in text_parts if part).strip(), info, segment_rows

    text, info, segment_rows = run_transcribe(
        beam_size=5,
        vad_filter=True,
        language="zh",
        condition_on_previous_text=False
    )

    if not text:
        text, info, segment_rows = run_transcribe(
            beam_size=1,
            vad_filter=False,
            language="zh",
            condition_on_previous_text=False
        )

    print(f"Transcription result: '{text}'", flush=True)
    return TranscribeResponse(text=text, language=info.language)


def transcribe_audio(audio_data: bytes, suffix: str = ".m4a") -> TranscribeResponse:
    if model is None:
        raise RuntimeError("Model not loaded")
    print(f"Received audio data: {len(audio_data)} bytes", flush=True)
    if len(audio_data) == 0:
        raise ValueError("Empty audio data received")

    if not suffix.startswith("."):
        suffix = f".{suffix}"

    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
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

    with tempfile.NamedTemporaryFile(delete=False, suffix=".wav") as temp_file:
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

    with tempfile.NamedTemporaryFile(delete=False, suffix=".wav") as temp_file:
        tmp_path = temp_file.name

    try:
        silence_padding = b"\x00" * int(sample_rate * channels * 2 * STREAM_SNAPSHOT_PAD_SEC)
        with wave.open(tmp_path, "wb") as wav_file:
            wav_file.setnchannels(channels)
            wav_file.setsampwidth(2)
            wav_file.setframerate(sample_rate)
            wav_file.writeframes(pcm_bytes + silence_padding)

        def run_transcribe(**kwargs):
            segments, info = model.transcribe(tmp_path, **kwargs)
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

        text, info, segment_rows = run_transcribe(
            beam_size=3,
            vad_filter=True,
            language="zh",
            condition_on_previous_text=False
        )

        if not text:
            text, info, segment_rows = run_transcribe(
                beam_size=1,
                vad_filter=False,
                language="zh",
                condition_on_previous_text=False
            )

        return {
            "text": normalize_preview_text(text),
            "language": info.language,
            "segments": segment_rows,
        }
    finally:
        os.unlink(tmp_path)


def filter_stream_segments(segment_rows: list[dict[str, Any]]) -> tuple[str, list[dict[str, Any]], list[dict[str, Any]]]:
    accepted_segments: list[dict[str, Any]] = []
    rejected_segments: list[dict[str, Any]] = []

    for segment in segment_rows:
        text = normalize_preview_text(segment.get("text", ""))
        avg_logprob = float(segment.get("avg_logprob", 0.0))
        no_speech_prob = float(segment.get("no_speech_prob", 0.0))

        reason = None
        if not text:
            reason = "empty"
        elif any(phrase in text for phrase in STREAM_HALLUCINATION_PHRASES):
            reason = "hallucination_phrase"
        elif no_speech_prob > STREAM_MAX_NO_SPEECH_PROB:
            reason = "high_no_speech_prob"
        elif avg_logprob < STREAM_MIN_CONFIDENCE:
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


app = FastAPI(title="OA助手 STT Server")


@app.get("/health")
async def health():
    return {
        "status": "ok" if model is not None else "model_unavailable",
        "engine": stt_engine,
        "model": model_size,
        "model_source": model_source,
        "model_root": str(model_root),
        "model_loaded": model is not None,
        "model_error": model_load_error,
    }


@app.post("/admin/stt/switch")
async def switch_stt(request: SwitchSTTRequest):
    engine = request.engine.strip().lower()
    if engine not in {"faster-whisper", "sensevoice"}:
        raise HTTPException(status_code=400, detail="engine must be faster-whisper or sensevoice")

    model_name = (request.model or "").strip()
    if not model_name:
        model_name = "SenseVoiceSmall" if engine == "sensevoice" else "small"

    push_debug_event(
        "switch_requested",
        from_engine=stt_engine,
        from_model=model_size,
        target_engine=engine,
        target_model=model_name,
    )

    if os.name == "nt":
        service_dir = Path(__file__).resolve().parent
        service_dir.joinpath("logs").mkdir(exist_ok=True)
        log_suffix = uuid4().hex[:8]
        stdout_log = service_dir / "logs" / f"stt_switch_{log_suffix}.log"
        stderr_log = service_dir / "logs" / f"stt_switch_{log_suffix}.err.log"
        current_pid = os.getpid()
        python_exe = Path(sys.executable).resolve()
        restart_helper = service_dir / "restart_stt.py"
        creation_flags = (
            getattr(subprocess, "CREATE_NO_WINDOW", 0)
            | getattr(subprocess, "DETACHED_PROCESS", 0)
        )
        subprocess.Popen(
            [
                str(python_exe),
                "-u",
                str(restart_helper),
                "--pid",
                str(current_pid),
                "--engine",
                engine,
                "--model",
                model_name,
                "--port",
                "8888",
                "--model-root",
                str(model_root),
                "--python",
                str(python_exe),
                "--service-dir",
                str(service_dir),
                "--stdout-log",
                str(stdout_log),
                "--stderr-log",
                str(stderr_log),
            ],
            cwd=str(service_dir),
            close_fds=True,
            creationflags=creation_flags,
        )
    else:
        raise HTTPException(status_code=501, detail="STT switch is currently implemented for Windows bat scripts")

    return {
        "status": "switching",
        "from_engine": stt_engine,
        "from_model": model_size,
        "target_engine": engine,
        "target_model": model_name,
        "port": 8888,
    }


@app.get("/debug/stream-events")
async def debug_stream_events(limit: int = 40):
    safe_limit = max(1, min(limit, STREAM_DEBUG_EVENT_LIMIT))
    return {
        "events": list(stream_debug_events)[-safe_limit:],
        "limit": safe_limit,
        "buffer_limit": STREAM_DEBUG_EVENT_LIMIT,
    }


@app.delete("/debug/stream-events")
async def clear_stream_events():
    """清空流式事件缓冲区"""
    global stream_debug_events
    cleared_count = len(stream_debug_events)
    stream_debug_events.clear()
    return {"status": "cleared", "message": f"已清空 {cleared_count} 条事件", "buffer_limit": STREAM_DEBUG_EVENT_LIMIT}


@app.post("/transcribe", response_model=TranscribeResponse)
async def transcribe(file: UploadFile = File(...)):
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    data = await file.read()
    suffix = Path(file.filename or "audio.m4a").suffix or ".m4a"
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(executor, transcribe_audio, data, suffix)


@app.websocket("/ws/transcribe-stream")
async def transcribe_stream(websocket: WebSocket):
    await websocket.accept()
    print(f"[WS] WebSocket accepted from {websocket.client} session will follow", flush=True)
    if model is None:
        await websocket.send_text(json.dumps({"type": "error", "message": "Model not loaded"}))
        await websocket.close(code=1011)
        return

    session_id = uuid4().hex[:8]
    pcm_buffer = bytearray()
    total_audio_bytes = 0
    sample_rate = 16000
    channels = 1
    last_sent_text = ""
    last_window_text = ""
    committed_text = ""
    last_processed_size = 0
    stream_stopped = False
    # Per-session params (can be overridden by client via start event)
    session_update_interval = STREAM_UPDATE_INTERVAL_SEC
    session_min_audio_sec = float(os.getenv("STREAM_MIN_AUDIO_SEC", "0.4"))
    session_min_confidence = STREAM_MIN_CONFIDENCE
    session_max_no_speech_prob = STREAM_MAX_NO_SPEECH_PROB
    session_stable_frames = STREAM_STABLE_FRAMES
    session_reject_fallback_threshold = STREAM_REJECT_FALLBACK_THRESHOLD
    min_bytes = int(sample_rate * channels * 2 * session_min_audio_sec)
    push_debug_event("session_open", session_id=session_id)

    async def transcribe_loop():
        nonlocal last_sent_text, last_window_text, committed_text, last_processed_size, min_bytes, stream_stopped
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
            if current_size < min_bytes or current_total_bytes == last_processed_size:
                continue

            max_snapshot_bytes = int(sample_rate * channels * 2 * STREAM_MAX_SNAPSHOT_SEC)
            snapshot = bytes(pcm_buffer[-max_snapshot_bytes:])
            last_processed_size = current_total_bytes
            result = await loop.run_in_executor(
                executor,
                transcribe_stream_snapshot,
                snapshot,
                sample_rate,
                channels
            )
            raw_text = normalize_preview_text(result["text"])
            if stt_engine == "sensevoice":
                current_text = raw_text
                accepted_segments = []
                rejected_segments = []
            else:
                current_text, accepted_segments, rejected_segments = filter_stream_segments(result["segments"])
            if rejected_segments:
                push_debug_event(
                    "partial_rejected",
                    session_id=session_id,
                    audio_bytes=current_total_bytes,
                    buffered_bytes=current_size,
                    rejected_segments=rejected_segments,
                )
            if not current_text:
                rejected_streak += 1
                if rejected_streak >= session_reject_fallback_threshold and raw_text:
                    # Fail-open after consecutive filtered frames so preview can continue.
                    has_hallucination = any(phrase in raw_text for phrase in STREAM_HALLUCINATION_PHRASES)
                    if not has_hallucination:
                        current_text = raw_text
                        push_debug_event(
                            "partial_fallback_raw",
                            session_id=session_id,
                            audio_bytes=current_total_bytes,
                            buffered_bytes=current_size,
                            text=current_text,
                            reason="consecutive_filtered_frames",
                        )
                        rejected_streak = 0
                else:
                    push_debug_event(
                        "partial_empty",
                        session_id=session_id,
                        audio_bytes=current_total_bytes,
                        buffered_bytes=current_size,
                        raw_text=raw_text,
                        segment_count=len(result.get("segments", [])),
                    )
            else:
                rejected_streak = 0

            if current_text and current_text != last_window_text:
                delta_text = extract_window_delta(last_window_text, current_text)
                last_window_text = current_text
                if not delta_text:
                    continue

                merged_text = merge_transcript_text(last_sent_text, delta_text)
                if merged_text == last_sent_text:
                    continue

                committed_text = merged_text
                preview_text = ""
                last_sent_text = merged_text
                push_debug_event(
                    "partial",
                    session_id=session_id,
                    audio_bytes=current_total_bytes,
                    buffered_bytes=current_size,
                    snapshot_bytes=len(snapshot),
                    text=merged_text,
                    window_text=current_text,
                    delta_text=delta_text,
                    committed_text=committed_text,
                    preview_text=preview_text,
                    accepted_segments=accepted_segments,
                )
                await websocket.send_text(
                    json.dumps(
                        {
                            "type": "partial",
                            "text": merged_text,
                            "language": result["language"],
                            "committed_text": committed_text,
                            "preview_text": preview_text,
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
                    session_min_audio_sec = float(payload.get("min_audio_sec")) if payload.get("min_audio_sec") else session_min_audio_sec
                    session_min_confidence = float(payload.get("min_confidence")) if payload.get("min_confidence") else session_min_confidence
                    session_max_no_speech_prob = float(payload.get("max_no_speech_prob")) if payload.get("max_no_speech_prob") else session_max_no_speech_prob
                    session_stable_frames = int(payload.get("stable_frames")) if payload.get("stable_frames") else session_stable_frames
                    session_reject_fallback_threshold = int(payload.get("reject_fallback_threshold")) if payload.get("reject_fallback_threshold") else session_reject_fallback_threshold
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
                        json.dumps({"type": "status", "message": "流式识别已连接"}, ensure_ascii=False)
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
                max_buffer_bytes = int(sample_rate * channels * 2 * STREAM_MAX_SNAPSHOT_SEC)
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
        worker.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await worker


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8888)
    parser.add_argument("--model", default="small")
    parser.add_argument("--engine", default=os.getenv("STT_ENGINE", "faster-whisper"), choices=["faster-whisper", "sensevoice"])
    args = parser.parse_args()

    try:
        load_model(args.model, engine=args.engine)
    except Exception as exc:
        model_load_error = str(exc)
        print(f"Model load failed, starting admin endpoints only: {exc}", flush=True)
    uvicorn.run(app, host=args.host, port=args.port)
