"""Replay a WAV at microphone speed, with an ephemeral account STT token.

Only use synthetic/non-sensitive validation audio if saving an output artifact.
Tokens are read from private config and never printed or written to the result.
"""
import argparse
import asyncio
import json
import statistics
import sys
import time
import wave
from pathlib import Path

import httpx
import websockets

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "common"))
from account_stt_token import issue_account_stt_token


async def verify(args, token):
    with wave.open(str(args.audio), "rb") as audio:
        assert (audio.getframerate(), audio.getnchannels(), audio.getsampwidth()) == (16000, 1, 2)
        pcm = audio.readframes(audio.getnframes())
    headers = {"Authorization": "Bearer " + token}
    events = []
    started = time.perf_counter()
    kwargs = {"additional_headers": None if args.browser else headers, "open_timeout": 15,
              "close_timeout": 5, "max_size": 2**22}
    async with websockets.connect(args.url.replace("http", "ws", 1) + "/ws/transcribe-stream", **kwargs) as ws:
        if args.browser:
            await ws.send(json.dumps({"event": "authenticate", "access_token": token}))
        await ws.send(json.dumps({"event": "start", "sample_rate": 16000, "channels": 1,
                                  "stream_provider": "local", "speaker_diarization": True}))
        status = json.loads(await asyncio.wait_for(ws.recv(), 10))
        if status.get("type") != "status" or len(status.get("session_id", "")) != 32:
            raise RuntimeError("Invalid start acknowledgement: " + str(status.get("message")))
        handshake_ms = round((time.perf_counter() - started) * 1000, 2)
        audio_start = time.perf_counter()
        paused_seconds = 0.0

        async def receive():
            while True:
                event = json.loads(await ws.recv())
                event["client_elapsed_ms"] = round((time.perf_counter() - audio_start - paused_seconds) * 1000, 2)
                events.append(event)
                if event.get("type") in ("final", "error"):
                    return

        receiver = asyncio.create_task(receive())
        for offset in range(0, len(pcm), 3200):
            await asyncio.sleep(max(0, audio_start + paused_seconds + (offset + 3200) / 32000 - time.perf_counter()))
            await ws.send(pcm[offset:offset + 3200])
            if args.pause and offset == 3200 * 49:
                await ws.send(json.dumps({"event": "pause"}))
                await asyncio.sleep(1)
                paused_seconds += 1
                await ws.send(json.dumps({"event": "resume"}))
        stopped = time.perf_counter()
        await ws.send(json.dumps({"event": "stop"}))
        await asyncio.wait_for(receiver, 20)
        drain_ms = round((time.perf_counter() - stopped) * 1000, 2)
    partials = [x for x in events if x.get("type") == "partial"]
    delays = sorted(x["client_elapsed_ms"] - x.get("audio_end_ms", 0) for x in partials)
    errors = [x.get("message") for x in events if x.get("type") == "error"]
    result = {"audio": args.audio.name, "audio_seconds": len(pcm) / 32000,
              "handshake_ms": handshake_ms, "first_partial_ms": partials[0]["client_elapsed_ms"] if partials else None,
              "partial_count": len(partials), "drain_ms": drain_ms,
              "inference_median_ms": statistics.median([x["inference_ms"] for x in partials]) if partials else None,
              "delivery_delay_p50_ms": statistics.median(delays) if delays else None,
              "delivery_delay_p95_ms": delays[min(len(delays)-1, int(len(delays)*.95))] if delays else None,
              "text": next((x.get("text", "") for x in events if x.get("type") == "final"), ""),
              "errors": errors, "pause_resume": args.pause, "browser_auth": args.browser}
    if args.file:
        async with httpx.AsyncClient(trust_env=False, timeout=180) as client:
            started = time.perf_counter()
            with args.audio.open("rb") as handle:
                response = await client.post(args.url + "/transcribe", headers=headers, files={"file": (args.audio.name, handle, "audio/wav")})
            result["file_http_status"] = response.status_code
            result["file_wall_seconds"] = round(time.perf_counter() - started, 3)
            if response.is_success:
                result["file_result"] = response.json()
            else:
                result["errors"].append("File transcription HTTP " + str(response.status_code))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({"summary": result, "events": events}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if errors or not result["text"]:
        raise RuntimeError("Streaming verification failed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-env", type=Path, required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--pause", action="store_true")
    parser.add_argument("--browser", action="store_true")
    parser.add_argument("--file", action="store_true")
    args = parser.parse_args()
    source = dict(line.split("=", 1) for line in args.source_env.read_text(encoding="utf-8-sig").splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
    token = issue_account_stt_token(source["ACCOUNT_TOKEN_SECRET"].strip().strip('"'), "v100-validation", int(time.time()) + 3600)
    asyncio.run(verify(args, token))


if __name__ == "__main__":
    main()
