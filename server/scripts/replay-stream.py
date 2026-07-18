#!/usr/bin/env python3
"""Replay a WAV over the STT WebSocket without printing transcript content."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import time
import wave

import websockets


async def replay(args: argparse.Namespace) -> None:
    with wave.open(str(args.audio), "rb") as audio:
        sample_rate = audio.getframerate()
        channels = audio.getnchannels()
        pcm = audio.readframes(audio.getnframes())

    uri = args.url
    headers = {"Authorization": f"Bearer {args.token}"} if args.token else None
    partials: list[dict[str, object]] = []
    errors: list[str] = []
    started = time.monotonic()

    async with websockets.connect(uri, additional_headers=headers, max_size=2**20) as socket:
        await socket.send(
            json.dumps(
                {"event": "start", "sample_rate": sample_rate, "channels": channels},
                ensure_ascii=False,
            )
        )
        chunk_size = max(3200, int(sample_rate * channels * 2 * args.chunk_sec))
        for offset in range(0, len(pcm), chunk_size):
            await socket.send(pcm[offset : offset + chunk_size])
            await asyncio.sleep(args.send_interval)

        deadline = time.monotonic() + args.wait_sec
        while time.monotonic() < deadline:
            try:
                message = json.loads(await asyncio.wait_for(socket.recv(), timeout=0.5))
            except asyncio.TimeoutError:
                continue
            except websockets.exceptions.ConnectionClosed:
                break
            if message.get("type") == "partial":
                partials.append(
                    {
                        "elapsed_sec": round(time.monotonic() - started, 3),
                        "text_chars": len(str(message.get("text", ""))),
                        "committed_chars": len(str(message.get("committed_text", ""))),
                        "preview_chars": len(str(message.get("preview_text", ""))),
                    }
                )
            elif message.get("type") == "error":
                errors.append(str(message.get("message", "unknown")))

        await socket.send(json.dumps({"event": "stop"}))

    print(
        json.dumps(
            {
                "audio_bytes": len(pcm),
                "audio_sec": round(len(pcm) / (sample_rate * channels * 2), 3),
                "partial_count": len(partials),
                "partial_text_chars": [item["text_chars"] for item in partials],
                "first_partial": partials[0] if partials else None,
                "last_partial": partials[-1] if partials else None,
                "errors": errors,
            },
            indent=2,
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("audio")
    parser.add_argument("--url", default="ws://127.0.0.1:8888/ws/transcribe-stream")
    parser.add_argument("--token", default=os.getenv("STT_API_TOKEN", ""))
    parser.add_argument("--chunk-sec", type=float, default=0.1)
    parser.add_argument("--send-interval", type=float, default=0.02)
    parser.add_argument("--wait-sec", type=float, default=30.0)
    asyncio.run(replay(parser.parse_args()))


if __name__ == "__main__":
    main()
