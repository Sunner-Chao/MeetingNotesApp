#!/usr/bin/env python3
"""V100 FunASR gateway for MeetingNotesApp.

The service deliberately keeps the wire protocol small and compatible with the
existing WebSocket client: authenticate, start, binary PCM16 frames, stop;
partial/final/status/error JSON events are returned.  Model and credentials
are supplied through environment variables so this file is safe to version.
"""

from __future__ import annotations

import asyncio
import contextlib
import hmac
import json
import os
import subprocess
import sys
import tempfile
import time
from uuid import uuid4
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import numpy as np
import torch
from fastapi import FastAPI, File, Header, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
import uvicorn
from funasr import AutoModel

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "common"))
from account_stt_token import verify_account_stt_token


ROOT = Path(os.getenv("V100_MODEL_ROOT", r"D:\LS-Models\modelscope\models"))
ONLINE_MODEL = Path(os.getenv(
    "V100_ONLINE_MODEL",
    str(ROOT / "iic--speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-online" / "snapshots" / "master"),
))
OFFLINE_MODEL = Path(os.getenv(
    "V100_OFFLINE_MODEL",
    str(ROOT / "iic--speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch" / "snapshots" / "master"),
))
VAD_MODEL = Path(os.getenv(
    "V100_VAD_MODEL",
    str(ROOT / "iic--speech_fsmn_vad_zh-cn-16k-common-pytorch" / "snapshots" / "master"),
))
PUNC_MODEL = Path(os.getenv(
    "V100_PUNC_MODEL",
    str(ROOT / "iic--punc_ct-transformer_cn-en-common-vocab471067-large" / "snapshots" / "master"),
))
DEVICE = os.getenv("V100_DEVICE", "cuda:0")
FILE_DEVICE = os.getenv("V100_FILE_DEVICE", DEVICE)
PORT = int(os.getenv("V100_PORT", "8889"))
ACCESS_TOKEN = os.getenv("V100_STT_TOKEN", "").strip()
ACCOUNT_TOKEN_SECRET = os.getenv("ACCOUNT_TOKEN_SECRET", "").strip()
CHUNK_MS = int(os.getenv("V100_STREAM_CHUNK_MS", "300"))
if CHUNK_MS not in (300, 600):
    raise ValueError("V100_STREAM_CHUNK_MS must be 300 or 600")
CHUNK_SAMPLES = 16000 * CHUNK_MS // 1000
MAX_STREAMS = int(os.getenv("V100_MAX_STREAMS", "2"))
MAX_UPLOAD_BYTES = int(os.getenv("V100_MAX_UPLOAD_MB", "1024")) * 1024 * 1024
TEMP_DIR = os.getenv("V100_TEMP_DIR") or None
# FunASR mutates per-model kwargs. Each executor serializes one model, while
# file inference cannot block the event loop or the streaming model.
online_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="stt-online")
offline_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="stt-file")
active_streams = 0
file_requests = 0
inference_calls = 0
events: deque[dict[str, Any]] = deque(maxlen=200)

app = FastAPI(title="MeetingNotesApp V100 STT", version="v100-paraformer-2")
online_model: Any = None
offline_model: Any = None
model_error = ""
model_started_at = 0.0


def require_token(value: str | None) -> str:
    token = (value or "").removeprefix("Bearer ").strip()
    if ACCESS_TOKEN and hmac.compare_digest(token, ACCESS_TOKEN):
        return "management"
    owner = verify_account_stt_token(ACCOUNT_TOKEN_SECRET, token)
    if owner:
        return owner
    raise HTTPException(status_code=401, detail="Missing or invalid bearer token")


def load_models() -> None:
    global online_model, offline_model, model_error, model_started_at
    model_started_at = time.time()
    try:
        if not all((path / "model.pt").is_file() for path in (ONLINE_MODEL, OFFLINE_MODEL, VAD_MODEL, PUNC_MODEL)):
            raise RuntimeError("Required model weights are missing")
        if DEVICE.startswith("cuda") and not torch.cuda.is_available():
            raise RuntimeError("CUDA requested but unavailable")
        torch.set_num_threads(int(os.getenv("V100_CPU_THREADS", "4")))
        online_model = AutoModel(model=str(ONLINE_MODEL), device=DEVICE, disable_update=True,
                                 disable_pbar=True, disable_log=True)
        offline_model = AutoModel(model=str(OFFLINE_MODEL), device=FILE_DEVICE, disable_update=True,
                                  disable_pbar=True, disable_log=True,
                                  vad_model=str(VAD_MODEL) if VAD_MODEL.exists() else None,
                                  punc_model=str(PUNC_MODEL) if PUNC_MODEL.exists() else None)
        # Warm up the online graph before advertising readiness.
        online_model.generate(input=np.zeros(CHUNK_SAMPLES, dtype=np.float32), cache={}, is_final=True,
                              chunk_size=[0, CHUNK_MS // 60, 5], encoder_chunk_look_back=4,
                              decoder_chunk_look_back=1, disable_pbar=True)
        if DEVICE.startswith("cuda"):
            torch.cuda.synchronize()
        model_error = ""
    except Exception as exc:  # keep /health available for diagnosis
        online_model = None
        offline_model = None
        model_error = str(exc)


@app.on_event("startup")
async def startup() -> None:
    if not ACCESS_TOKEN and not ACCOUNT_TOKEN_SECRET:
        raise RuntimeError("Configure V100_STT_TOKEN or ACCOUNT_TOKEN_SECRET before starting")
    if TEMP_DIR:
        Path(TEMP_DIR).mkdir(parents=True, exist_ok=True)
    await asyncio.get_running_loop().run_in_executor(None, load_models)


@app.get("/health")
async def health() -> JSONResponse:
    return JSONResponse({
        "status": "ok" if not model_error and online_model is not None else "degraded",
        "service": "v100-funasr",
        "version": "v100-paraformer-2",
        "engine": "funasr-paraformer",
        "device": DEVICE,
        "file_device": FILE_DEVICE,
        "model": "paraformer-large-online",
        "models_present": all((path / "model.pt").is_file() for path in (ONLINE_MODEL, OFFLINE_MODEL, VAD_MODEL, PUNC_MODEL)),
        "model_loaded": online_model is not None and offline_model is not None,
        "active": inference_calls > 0,
        "active_streams": active_streams,
        "inference_calls": inference_calls,
        "file_requests": file_requests,
        "stream_chunk_ms": CHUNK_MS,
        "diarization": {"enabled": False, "active": False, "models_present": False, "planned_provider": "CAM++"},
        "model_error": model_error,
        "cuda": torch.cuda.is_available(),
    })


@app.get("/ready")
async def ready() -> JSONResponse:
    if model_error or online_model is None or offline_model is None:
        raise HTTPException(status_code=503, detail=model_error or "models are loading")
    return JSONResponse({"ready": True, "engine": "funasr-paraformer", "device": DEVICE})


def pcm_to_float32(data: bytes) -> np.ndarray:
    if len(data) % 2:
        raise ValueError("PCM16 payload must contain an even number of bytes")
    return np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0


@app.get("/debug/stream-events")
async def stream_events(limit: int = 20, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    owner = require_token(authorization)
    visible = [{k: v for k, v in event.items() if k != "owner"}
               for event in events if owner == "management" or event["owner"] == owner]
    return {"events": visible[-max(1, min(limit, 100)):], "engine": "funasr-paraformer"}


def generate_online(data: bytes, cache: dict[str, Any], final: bool) -> tuple[str, float]:
    global inference_calls
    started = time.perf_counter()
    with torch.inference_mode():
        result = online_model.generate(input=pcm_to_float32(data), cache=cache, is_final=final,
                                       chunk_size=[0, CHUNK_MS // 60, 5], encoder_chunk_look_back=4,
                                       decoder_chunk_look_back=1, disable_pbar=True)
    inference_calls += 1
    return "".join(str(item.get("text", "")) for item in (result or [])), (time.perf_counter() - started) * 1000


def generate_file(path: Path) -> dict[str, Any]:
    global inference_calls
    from imageio_ffmpeg import get_ffmpeg_exe
    decoded = subprocess.run(
        [get_ffmpeg_exe(), "-v", "error", "-nostdin", "-i", str(path), "-vn", "-ac", "1",
         "-ar", "16000", "-f", "f32le", "pipe:1"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600, check=True,
    )
    audio = np.frombuffer(decoded.stdout, dtype="<f4").copy()
    if not len(audio):
        raise ValueError("Decoded audio is empty")
    duration_ms = round(len(audio) / 16)
    with torch.inference_mode():
        result = offline_model.generate(input=audio, batch_size_s=60, disable_pbar=True)
    inference_calls += 1
    return {"text": "".join(str(item.get("text", "")) for item in (result or [])).strip(),
            "language": "zh", "provider": "v100-paraformer", "duration_ms": duration_ms,
            "diarization": {"enabled": False, "active": False}}


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...), authorization: str | None = Header(default=None)) -> dict[str, Any]:
    global file_requests
    require_token(authorization)
    if offline_model is None:
        raise HTTPException(status_code=503, detail=model_error or "offline model is not ready")
    if file_requests >= 4:
        raise HTTPException(status_code=429, detail="File transcription queue is full")
    file_requests += 1
    path = None
    try:
        suffix = Path(file.filename or "audio.wav").suffix or ".wav"
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix, dir=TEMP_DIR) as target:
            path = Path(target.name)
            size = 0
            while chunk := await file.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_UPLOAD_BYTES:
                    raise HTTPException(status_code=413, detail="Audio exceeds server upload capacity")
                target.write(chunk)
        if not size:
            raise HTTPException(status_code=400, detail="Audio file is empty")
        return await asyncio.get_running_loop().run_in_executor(offline_executor, generate_file, path)
    except (ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise HTTPException(status_code=422, detail="Unable to decode this audio file") from exc
    finally:
        file_requests -= 1
        if path:
            path.unlink(missing_ok=True)
        await file.close()


@app.websocket("/ws/transcribe-stream")
async def stream(websocket: WebSocket) -> None:
    global active_streams
    await websocket.accept()
    reserved = False
    owner = ""
    session_id = uuid4().hex
    audio_bytes = 0
    try:
        # Android sends the bearer token in the WebSocket handshake and then
        # sends start immediately. Browser clients may still use the legacy
        # authenticate message for compatibility.
        token_header = websocket.headers.get("authorization")
        if token_header:
            owner = require_token(token_header)
        else:
            auth = await asyncio.wait_for(websocket.receive_text(), timeout=5)
            payload = json.loads(auth)
            if payload.get("event") != "authenticate":
                raise ValueError("authentication required")
            token = str(payload.get("access_token") or "")
            owner = require_token("Bearer " + token)
        start = json.loads(await asyncio.wait_for(websocket.receive_text(), timeout=5))
        if start.get("event") != "start":
            raise ValueError("start event required")
        if start.get("sample_rate", 16000) != 16000 or start.get("channels", 1) != 1:
            raise ValueError("16 kHz mono PCM16 audio required")
        if online_model is None:
            await websocket.send_json({"type": "error", "message": model_error or "online model is not ready"})
            await websocket.close(code=1011)
            return
        if active_streams >= MAX_STREAMS:
            raise ValueError("Local STT is busy; please use cloud STT")
        active_streams += 1
        reserved = True
        events.append({"owner": owner, "session_id": session_id, "event": "session_start", "timestamp": time.time()})
        language = str(start.get("language") or "zh")
        diarization = {"enabled": bool(start.get("speaker_diarization")), "active": False}

        async def status(message: str) -> None:
            await websocket.send_json({"type": "status", "message": message, "session_id": session_id,
                                       "stream_provider": "local", "provider": "v100-paraformer",
                                       "language": language, "diarization": diarization})

        await status("智悟本地模型已连接")
        cache: dict[str, Any] = {}
        buffer = bytearray()
        transcript = ""
        segment_has_audio = False
        paused = False

        async def infer(data: bytes, final: bool) -> None:
            nonlocal transcript
            fragment, elapsed = await asyncio.get_running_loop().run_in_executor(
                online_executor, generate_online, data, cache, final)
            if fragment:
                transcript += fragment
                await websocket.send_json({"type": "partial", "text": transcript,
                                           "committed_text": transcript, "preview_text": "",
                                           "session_id": session_id, "provider": "v100-paraformer",
                                           "language": language, "diarization": diarization,
                                           "audio_end_ms": round(audio_bytes / 32),
                                           "inference_ms": round(elapsed, 2)})

        async def flush() -> None:
            nonlocal cache, segment_has_audio
            if segment_has_audio:
                # Exact chunk boundaries still retain delayed decoder output.
                await infer(bytes(buffer), True)
            buffer.clear()
            cache = {}
            segment_has_audio = False

        while True:
            message = await websocket.receive()
            if message.get("type") == "websocket.disconnect":
                return
            if message.get("text") is not None:
                control = json.loads(message["text"])
                event = control.get("event")
                if event in ("pause", "stop"):
                    await flush()
                    paused = True
                    if event == "stop":
                        await websocket.send_json({"type": "final", "text": transcript.strip(), "provider": "v100-paraformer"})
                        await websocket.close(code=1000)
                        return
                    await status("已暂停，文字已保留")
                elif event == "resume":
                    paused = False
                    await status("智悟本地模型已连接")
                elif event == "switch_language":
                    await flush()
                    language = str(control.get("language") or "zh")
                    await status("识别语言已更新")
                elif event == "switch_provider":
                    if control.get("stream_provider") != "local":
                        raise ValueError("Cloud STT requires the configured cloud endpoint")
                    await status("智悟本地模型已连接")
                continue
            frame = message.get("bytes")
            if not frame or paused:
                continue
            if len(frame) % 2:
                raise ValueError("PCM16 payload must contain an even number of bytes")
            audio_bytes += len(frame)
            segment_has_audio = True
            buffer.extend(frame)
            while len(buffer) >= CHUNK_SAMPLES * 2:
                audio = bytes(buffer[:CHUNK_SAMPLES * 2])
                del buffer[:CHUNK_SAMPLES * 2]
                await infer(audio, False)
    except (WebSocketDisconnect, asyncio.TimeoutError):
        return
    except Exception as exc:
        with contextlib.suppress(Exception):
            await websocket.send_json({"type": "error", "message": exc.detail if isinstance(exc, HTTPException) else str(exc)})
            await websocket.close(code=1008)
    finally:
        if reserved:
            active_streams -= 1
            events.append({"owner": owner, "session_id": session_id, "event": "session_close",
                           "timestamp": time.time(), "audio_bytes": audio_bytes})


if __name__ == "__main__":
    uvicorn.run(app, host=os.getenv("V100_BIND", "127.0.0.1"), port=PORT, log_level="info",
                ws_max_size=2 * 1024 * 1024, ws_max_queue=16, access_log=False)
