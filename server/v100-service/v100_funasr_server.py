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
import threading
from uuid import uuid4
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import numpy as np
import torch
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.responses import FileResponse, RedirectResponse
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
WEB_ADMIN_USERNAME = os.getenv("V100_ADMIN_USERNAME", "admin").strip() or "admin"
WEB_ADMIN_TOKEN = os.getenv("V100_ADMIN_TOKEN", "").strip()
LOG_DIR = Path(os.getenv("V100_LOG_DIR", str(Path(__file__).resolve().parent / "logs")))
AUDIO_ENHANCEMENT_ENABLED = os.getenv("V100_AUDIO_ENHANCEMENT_ENABLED", "1").strip().lower() not in {"0", "false", "no", "off"}
DIARIZATION_ENABLED = os.getenv("V100_DIARIZATION_ENABLED", "1").strip().lower() not in {"0", "false", "no", "off"}
DIARIZATION_SEGMENTATION_MODEL = Path(os.getenv(
    "V100_DIARIZATION_SEGMENTATION_MODEL",
    str(ROOT / "speaker-diarization" / "sherpa-onnx-pyannote-segmentation-3-0" / "model.int8.onnx"),
))
DIARIZATION_EMBEDDING_MODEL = Path(os.getenv(
    "V100_DIARIZATION_EMBEDDING_MODEL",
    str(ROOT / "speaker-diarization" / "3dspeaker-eres2net-base-zh-16k.onnx"),
))
DIARIZATION_MAX_SPEAKERS = int(os.getenv("V100_DIARIZATION_MAX_SPEAKERS", "8"))
DIARIZATION_MIN_TURN_SEC = float(os.getenv("V100_DIARIZATION_MIN_TURN_SEC", "0.7"))
DIARIZATION_CLUSTER_THRESHOLD = float(os.getenv("V100_DIARIZATION_CLUSTER_THRESHOLD", "0.9"))
# FunASR mutates per-model kwargs. Each executor serializes one model, while
# file inference cannot block the event loop or the streaming model.
online_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="stt-online")
offline_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="stt-file")
active_streams = 0
file_requests = 0
inference_calls = 0
events: deque[dict[str, Any]] = deque(maxlen=200)

app = FastAPI(title="MeetingNotesApp V100 STT", version="v100-paraformer-2")
web_admin_security = HTTPBasic(auto_error=False)
online_model: Any = None
offline_model: Any = None
file_vad_model: Any = None
file_punc_model: Any = None
model_error = ""
model_started_at = 0.0
speaker_diarizer: Any = None
speaker_diarizer_error = ""
speaker_diarizer_lock = threading.Lock()


def require_token(value: str | None) -> str:
    token = (value or "").removeprefix("Bearer ").strip()
    if ACCESS_TOKEN and hmac.compare_digest(token, ACCESS_TOKEN):
        return "management"
    owner = verify_account_stt_token(ACCOUNT_TOKEN_SECRET, token)
    if owner:
        return owner
    raise HTTPException(status_code=401, detail="Missing or invalid bearer token")


def require_web_admin(credentials: HTTPBasicCredentials | None = Depends(web_admin_security)) -> str:
    """Protect the V100 operations page without exposing model credentials."""
    if not WEB_ADMIN_TOKEN or credentials is None:
        raise HTTPException(status_code=401, detail="STT admin authentication required",
                            headers={"WWW-Authenticate": "Basic"})
    valid_user = hmac.compare_digest(credentials.username, WEB_ADMIN_USERNAME)
    valid_password = hmac.compare_digest(credentials.password, WEB_ADMIN_TOKEN)
    if not (valid_user and valid_password):
        raise HTTPException(status_code=401, detail="Invalid STT admin credentials",
                            headers={"WWW-Authenticate": "Basic"})
    return credentials.username


def read_log_tail(limit: int = 160) -> dict[str, list[str]]:
    safe_limit = max(1, min(int(limit), 400))
    files = sorted(LOG_DIR.glob("*.log"), key=lambda path: path.stat().st_mtime, reverse=True)
    stdout: list[str] = []
    stderr: list[str] = []
    for path in files[:6]:
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        target = stderr if "stderr" in path.name else stdout
        target.extend(lines[-safe_limit:])
    return {"stdout": stdout[-safe_limit:], "stderr": stderr[-safe_limit:]}


@app.get("/admin", include_in_schema=False)
async def admin_redirect() -> RedirectResponse:
    return RedirectResponse(url="/admin/", status_code=307)


@app.get("/admin/", include_in_schema=False, dependencies=[Depends(require_web_admin)])
async def admin_page() -> FileResponse:
    page = Path(__file__).resolve().with_name("v100_admin.html")
    if not page.is_file():
        raise HTTPException(status_code=503, detail="V100 STT management page is unavailable")
    return FileResponse(page, media_type="text/html; charset=utf-8",
                        headers={"Cache-Control": "no-store"})


def load_models() -> None:
    global online_model, offline_model, file_vad_model, file_punc_model, model_error, model_started_at
    model_started_at = time.time()
    try:
        if not all((path / "model.pt").is_file() for path in (ONLINE_MODEL, OFFLINE_MODEL, VAD_MODEL, PUNC_MODEL)):
            raise RuntimeError("Required model weights are missing")
        if DEVICE.startswith("cuda") and not torch.cuda.is_available():
            raise RuntimeError("CUDA requested but unavailable")
        torch.set_num_threads(int(os.getenv("V100_CPU_THREADS", "4")))
        online_model = AutoModel(model=str(ONLINE_MODEL), device=DEVICE, disable_update=True,
                                 disable_pbar=True, disable_log=True)
        # This checkpoint has no token timestamps. Run VAD and punctuation
        # explicitly so long audio stays bounded without the wrapper's
        # assumption that every ASR result contains a timestamp array.
        offline_model = AutoModel(model=str(OFFLINE_MODEL), device=FILE_DEVICE, disable_update=True,
                                  disable_pbar=True, disable_log=True)
        file_vad_model = AutoModel(model=str(VAD_MODEL), device=FILE_DEVICE, disable_update=True,
                                   disable_pbar=True, disable_log=True)
        file_punc_model = AutoModel(model=str(PUNC_MODEL), device=FILE_DEVICE, disable_update=True,
                                    disable_pbar=True, disable_log=True)
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
        file_vad_model = None
        file_punc_model = None
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
        "diarization": {
            "enabled": DIARIZATION_ENABLED,
            "active": speaker_diarizer is not None,
            "models_present": DIARIZATION_SEGMENTATION_MODEL.is_file() and DIARIZATION_EMBEDDING_MODEL.is_file(),
            "provider": "local-sherpa-onnx",
            "error": speaker_diarizer_error,
            "max_speakers": DIARIZATION_MAX_SPEAKERS,
        },
        "model_error": model_error,
        "cuda": torch.cuda.is_available(),
        "audio_enhancement": {"enabled": AUDIO_ENHANCEMENT_ENABLED, "mode": "ffmpeg-adaptive"},
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


@app.get("/admin/api/status", dependencies=[Depends(require_web_admin)])
async def admin_status() -> dict[str, Any]:
    payload = (await health()).body
    data = json.loads(payload.decode("utf-8"))
    data["management"] = {
        "domain": "lstwin.space",
        "node": "V100",
        "log_available": LOG_DIR.is_dir(),
    }
    return data


@app.get("/admin/api/events", dependencies=[Depends(require_web_admin)])
async def admin_events(limit: int = 40) -> dict[str, Any]:
    safe_limit = max(1, min(int(limit), 100))
    return {"events": list(events)[-safe_limit:], "limit": safe_limit, "buffer_limit": events.maxlen}


@app.get("/admin/api/logs", dependencies=[Depends(require_web_admin)])
async def admin_logs(limit: int = 160) -> dict[str, list[str]]:
    return read_log_tail(limit)


@app.post("/admin/api/stt/switch", dependencies=[Depends(require_web_admin)])
async def admin_switch_stt() -> dict[str, Any]:
    # V100 uses a fixed FunASR online/offline pair. A runtime Faster-Whisper
    # switch would unload the GPU models and break the low-latency stream.
    raise HTTPException(status_code=409,
                        detail="V100 当前使用 FunASR Paraformer，模型切换请通过部署配置完成")


def generate_online(data: bytes, cache: dict[str, Any], final: bool) -> tuple[str, float]:
    global inference_calls
    started = time.perf_counter()
    with torch.inference_mode():
        result = online_model.generate(input=pcm_to_float32(data), cache=cache, is_final=final,
                                       chunk_size=[0, CHUNK_MS // 60, 5], encoder_chunk_look_back=4,
                                       decoder_chunk_look_back=1, disable_pbar=True)
    inference_calls += 1
    return "".join(str(item.get("text", "")) for item in (result or [])), (time.perf_counter() - started) * 1000


def generate_file(path: Path, speaker_diarization: bool = True, audio_enhancement: bool = True) -> dict[str, Any]:
    global inference_calls
    from imageio_ffmpeg import get_ffmpeg_exe
    filters = ["highpass=f=80", "lowpass=f=7600", "afftdn=nr=8dB"] if AUDIO_ENHANCEMENT_ENABLED and audio_enhancement else []
    command = [get_ffmpeg_exe(), "-v", "error", "-nostdin", "-i", str(path), "-vn", "-ac", "1", "-ar", "16000"]
    if filters:
        command.extend(["-af", ",".join(filters)])
    command.extend(["-f", "f32le", "pipe:1"])
    decoded = subprocess.run(
        command,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600, check=True,
    )
    audio = np.frombuffer(decoded.stdout, dtype="<f4").copy()
    if not len(audio):
        raise ValueError("Decoded audio is empty")
    result = generate_file_audio(audio, speaker_diarization=speaker_diarization)
    inference_calls += 1
    return result


def bounded_speech_ranges(audio: np.ndarray) -> list[tuple[int, int]]:
    """VAD timestamps are milliseconds; every ASR input is at most 20 seconds."""
    ranges: list[tuple[int, int]] = []
    window_samples, max_samples = 60 * 16000, 20 * 16000
    for offset in range(0, len(audio), window_samples):
        window = audio[offset:offset + window_samples]
        with torch.inference_mode():
            detected = file_vad_model.generate(input=window, cache={}, disable_pbar=True)
        for item in detected or []:
            for pair in item.get("value") or []:
                if len(pair) != 2:
                    continue
                start = max(offset, offset + int(float(pair[0]) * 16))
                end = min(offset + len(window), offset + int(float(pair[1]) * 16))
                if ranges:
                    start = max(start, ranges[-1][1])
                while start < end:
                    boundary = min(end, start + max_samples)
                    if boundary < end:
                        # Cut sustained speech at the quietest 20 ms frame in
                        # the final two seconds, never beyond the ASR ceiling.
                        candidates = range(boundary - 2 * 16000, boundary, 320)
                        boundary = min(candidates, key=lambda pos: float(np.mean(audio[pos:pos + 320] ** 2))) + 160
                    ranges.append((start, boundary))
                    start = boundary
    return ranges


def generate_file_audio(audio: np.ndarray, speaker_diarization: bool = True) -> dict[str, Any]:
    if file_vad_model is None or file_punc_model is None:
        raise RuntimeError("File VAD/punctuation models are not ready")
    results: list[dict[str, Any]] = []
    for start, end in bounded_speech_ranges(audio):
        with torch.inference_mode():
            decoded = offline_model.generate(input=audio[start:end], cache={}, disable_pbar=True)
            text = "".join(str(item.get("text") or "") for item in decoded or []).strip()
            if not text:
                continue
            punctuated = file_punc_model.generate(input=text, cache={}, disable_pbar=True)
        text = "".join(str(item.get("text") or "") for item in punctuated or []).strip() or text
        results.append({"text": text, "timestamp": [[start / 16, end / 16]]})
    output = build_file_result(audio, results, round(len(audio) / 16), speaker_diarization)
    output["segmentation"] = {"provider": "fsmn-vad", "max_segment_seconds": 20,
                               "timestamp_source": "vad", "punctuation": "ct-transformer"}
    return output


def _asr_rows(result: Any) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for item in result or []:
        if not isinstance(item, dict):
            continue
        text = str(item.get("text") or "").strip()
        if not text:
            continue
        timestamp = item.get("timestamp") or item.get("timestamps") or []
        start = end = 0.0
        if isinstance(timestamp, list) and timestamp:
            pairs = [pair for pair in timestamp if isinstance(pair, (list, tuple)) and len(pair) >= 2]
            if pairs:
                start = float(pairs[0][0]) / 1000.0
                end = float(pairs[-1][1]) / 1000.0
        rows.append({"text": text, "start": start, "end": max(start, end)})
    return rows


def _speaker_label(value: object) -> str:
    try:
        return f"说话人 {int(value) + 1}"
    except (TypeError, ValueError):
        return "说话人 1"


def _format_rows(rows: list[dict[str, Any]]) -> str:
    grouped: list[dict[str, Any]] = []
    for row in rows:
        text = str(row.get("text") or "").strip()
        if not text:
            continue
        speaker = row.get("speaker")
        if grouped and grouped[-1].get("speaker") == speaker:
            grouped[-1]["text"] += text
        else:
            grouped.append({"speaker": speaker, "text": text})
    return "\n".join(
        f"{_speaker_label(row['speaker'])}：{row['text']}" if row.get("speaker") is not None else row["text"]
        for row in grouped
    )


def _load_speaker_diarizer() -> Any:
    global speaker_diarizer, speaker_diarizer_error
    if not DIARIZATION_ENABLED:
        return None
    if speaker_diarizer is not None:
        return speaker_diarizer
    with speaker_diarizer_lock:
        if speaker_diarizer is not None:
            return speaker_diarizer
        if not DIARIZATION_SEGMENTATION_MODEL.is_file() or not DIARIZATION_EMBEDDING_MODEL.is_file():
            speaker_diarizer_error = "说话人分离模型文件缺失"
            return None
        try:
            import sherpa_onnx

            segmentation = sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
                pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                    model=str(DIARIZATION_SEGMENTATION_MODEL)
                ),
                num_threads=max(1, min(8, int(os.getenv("V100_DIARIZATION_CPU_THREADS", "4")))),
                provider="cpu",
            )
            embedding = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
                model=str(DIARIZATION_EMBEDDING_MODEL),
                num_threads=max(1, min(8, int(os.getenv("V100_DIARIZATION_CPU_THREADS", "4")))),
                provider="cpu",
            )
            config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
                segmentation=segmentation,
                embedding=embedding,
                clustering=sherpa_onnx.FastClusteringConfig(
                    num_clusters=-1, threshold=DIARIZATION_CLUSTER_THRESHOLD
                ),
                min_duration_on=DIARIZATION_MIN_TURN_SEC,
                min_duration_off=0.5,
            )
            if not config.validate():
                raise RuntimeError("说话人分离配置无效")
            speaker_diarizer = sherpa_onnx.OfflineSpeakerDiarization(config)
            speaker_diarizer_error = ""
            return speaker_diarizer
        except Exception as exc:
            speaker_diarizer_error = str(exc)
            return None


def _speaker_turns(audio: np.ndarray) -> list[dict[str, Any]]:
    diarizer = _load_speaker_diarizer()
    if diarizer is None or not len(audio):
        return []
    with speaker_diarizer_lock:
        result = diarizer.process(audio.astype(np.float32, copy=False))
    turns = [
        {"start": float(segment.start), "end": float(segment.end), "speaker": int(segment.speaker)}
        for segment in result.sort_by_start_time()
        if float(segment.end) - float(segment.start) >= DIARIZATION_MIN_TURN_SEC
    ]
    labels = list(dict.fromkeys(turn["speaker"] for turn in turns))
    # sherpa-onnx cluster ids are opaque and may be sparse (for example
    # 0,1,6,9). Normalize them to the compact 0..N-1 labels exposed to the
    # app. Clusters beyond the display limit remain unassigned; never merge
    # unrelated voices merely to fit a display limit.
    labels = labels[:DIARIZATION_MAX_SPEAKERS]
    label_map = {value: index for index, value in enumerate(labels)}
    for turn in turns:
        turn["speaker"] = label_map.get(turn["speaker"])
    return turns


def _attach_speakers(rows: list[dict[str, Any]], turns: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not turns or not any(float(row.get("end") or 0) > float(row.get("start") or 0) for row in rows):
        return rows
    enriched: list[dict[str, Any]] = []
    for row in rows:
        start, end = float(row.get("start") or 0), float(row.get("end") or 0)
        def overlap(turn: dict[str, Any]) -> float:
            return max(0.0, min(end, turn["end"]) - max(start, turn["start"]))
        best = max(turns, key=overlap)
        enriched.append({**row, "speaker": best["speaker"]} if overlap(best) > 0 else dict(row))
    labels: dict[int, int] = {}
    for row in enriched:
        speaker = row.get("speaker")
        if speaker is not None:
            row["speaker"] = labels.setdefault(speaker, len(labels))
    return enriched


def build_file_result(audio: np.ndarray, result: Any, duration_ms: int, speaker_diarization: bool = True) -> dict[str, Any]:
    rows = _asr_rows(result)
    diarization = {"enabled": False, "active": False, "provider": "local-sherpa-onnx"}
    if speaker_diarization and DIARIZATION_ENABLED and rows and _load_speaker_diarizer() is not None:
        rows = _attach_speakers(rows, _speaker_turns(audio))
        active = any(row.get("speaker") is not None for row in rows)
        diarization.update({"enabled": True, "active": active, "speaker_count": len({row["speaker"] for row in rows if row.get("speaker") is not None})})
    text = _format_rows(rows) if rows else "".join(str(item.get("text", "")) for item in (result or [])).strip()
    return {"text": text, "segments": rows, "language": "zh", "provider": "v100-paraformer", "duration_ms": duration_ms,
            "diarization": diarization}


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...), authorization: str | None = Header(default=None),
                     speaker_diarization: bool = Form(default=True),
                     audio_enhancement: bool = Form(default=True)) -> dict[str, Any]:
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
        return await asyncio.get_running_loop().run_in_executor(
            offline_executor, generate_file, path, speaker_diarization, audio_enhancement)
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
        stream_audio = bytearray()
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
                        final_text = transcript.strip()
                        final_segments: list[dict[str, Any]] = []
                        if diarization["enabled"] and DIARIZATION_ENABLED and stream_audio:
                            def finalize_stream() -> dict[str, Any]:
                                samples = pcm_to_float32(bytes(stream_audio))
                                return generate_file_audio(samples, speaker_diarization=True)
                            finalized = await asyncio.get_running_loop().run_in_executor(offline_executor, finalize_stream)
                            if finalized.get("text"):
                                # Return sentence timestamps even when the
                                # diarizer cannot confidently assign a speaker.
                                # Android can render the timeline immediately;
                                # speaker labels remain opt-in and evidence-based.
                                final_text = finalized["text"]
                                final_segments = finalized.get("segments") or []
                            if finalized["diarization"].get("active"):
                                diarization.update(finalized["diarization"])
                        await websocket.send_json({"type": "final", "text": final_text,
                                                   "segments": final_segments, "diarization": diarization,
                                                   "provider": "v100-paraformer"})
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
            stream_audio.extend(frame)
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
