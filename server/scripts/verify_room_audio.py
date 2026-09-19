"""Headless, loopback-only two-party audio verification; never opens a microphone.

Requires livekit==1.1.19, numpy and backend dependencies. Pass a checksum-verified
LiveKit server executable. All credentials and room data are disposable/in-memory.
"""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import gc
import json
import logging
import os
import secrets
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace

os.environ["RUST_LOG"] = "off"
logging.disable(logging.CRITICAL)

import numpy as np
from livekit import rtc

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend-service"))
from meeting_rooms import RoomService
from room_media import MediaSettings, RoomMediaService


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(socket.AF_INET, kind) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


async def until(predicate, timeout=15):
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.02)


async def verify(server_binary: Path):
    port, tcp, udp = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    key, secret = secrets.token_hex(12), secrets.token_hex(32)
    settings = MediaSettings(f"ws://127.0.0.1:{port}", f"http://127.0.0.1:{port}", key, secret)
    environment = dict(os.environ, LIVEKIT_KEYS=f"{key}: {secret}")
    # This test has no external destinations, independent of desktop proxy settings.
    environment["NO_PROXY"] = "127.0.0.1,localhost"
    os.environ["NO_PROXY"] = environment["NO_PROXY"]
    received = [{"frames": 0, "nonzero": 0, "identity": ""} for _ in range(2)]
    muted = [False, False]
    streams, consumers, producers, sources, clients = [], [], [], [], []
    started = time.monotonic()
    process = None
    with tempfile.TemporaryDirectory(prefix="meeting-room-audio-") as directory:
        root = Path(directory)
        # Test configuration contains no keys. Secrets are child-process environment only.
        config = root / "livekit.yaml"
        config.write_text(
            f"port: {port}\nbind_addresses: [127.0.0.1]\n"
            f"rtc:\n  tcp_port: {tcp}\n  udp_port: {udp}\n  use_external_ip: false\n"
            "  node_ip: 127.0.0.1\n  enable_loopback_candidate: true\n"
            "room:\n  auto_create: false\nlogging:\n  level: error\n",
            encoding="utf-8",
        )
        try:
            process = subprocess.Popen([str(server_binary), "--config", str(config)], env=environment,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            def ready():
                if process.poll() is not None:
                    raise RuntimeError("Media server exited before readiness")
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                        return True
                except OSError:
                    return False
            await until(ready)
            path = root / "rooms.db"
            with contextlib.closing(sqlite3.connect(path)) as db:
                db.execute("CREATE TABLE users(id TEXT PRIMARY KEY)")
                db.executemany("INSERT INTO users VALUES(?)", [("host",), ("guest",)])
                db.commit()
            rooms = RoomService(path, RoomMediaService(settings))
            rooms.initialize()
            room = rooms.create(SimpleNamespace(user_id="host", username="主持人"), "双向音频测试", False)
            rooms.join(SimpleNamespace(user_id="guest", username="成员"), room["code"], False)
            tickets = [await asyncio.to_thread(rooms.media_session, identity, room["id"]) for identity in ("host", "guest")]

            async def consume(index, track, identity):
                stream = rtc.AudioStream(track, sample_rate=48000, num_channels=1)
                streams.append(stream)
                async for event in stream:
                    received[index]["frames"] += 1
                    received[index]["identity"] = identity
                    values = np.frombuffer(event.frame.data, dtype=np.int16).astype(np.float64)
                    if np.sqrt(np.mean(values * values)) > 100:
                        received[index]["nonzero"] += 1

            for index, ticket in enumerate(tickets):
                client = rtc.Room()
                clients.append(client)
                def subscribed(track, publication, participant, slot=index):
                    if track.kind == rtc.TrackKind.KIND_AUDIO:
                        consumers.append(asyncio.create_task(consume(slot, track, participant.identity)))
                client.on("track_subscribed", subscribed)
                client.on("track_muted", lambda publication, participant, slot=index: muted.__setitem__(slot, True))
                client.on("track_unmuted", lambda publication, participant, slot=index: muted.__setitem__(slot, False))
                await asyncio.wait_for(client.connect(ticket["url"], ticket["token"]), 15)

            async def produce(source, frequency):
                position = 0
                while True:
                    points = np.arange(480) + position
                    samples = (np.sin(points * (2 * np.pi * frequency / 48000)) * 8000).astype(np.int16)
                    await source.capture_frame(rtc.AudioFrame(samples.tobytes(), 48000, 1, 480))
                    position += 480
                    await asyncio.sleep(0.01)

            tracks = []
            for index, client in enumerate(clients):
                source = rtc.AudioSource(48000, 1)
                sources.append(source)
                track = rtc.LocalAudioTrack.create_audio_track("synthetic-microphone", source)
                tracks.append(track)
                await client.local_participant.publish_track(track,
                    rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE))
                producers.append(asyncio.create_task(produce(source, 440 + index * 220)))
            await until(lambda: all(item["nonzero"] >= 25 for item in received))
            first_audio_seconds = round(time.monotonic() - started, 3)
            assert received[0]["identity"] == "guest" and received[1]["identity"] == "host"
            tracks[0].mute()
            await until(lambda: muted[1])
            await asyncio.sleep(0.5)  # Drain the small receiver jitter buffer.
            before = received[1]["nonzero"]
            await asyncio.sleep(0.4)
            assert received[1]["nonzero"] <= before + 2, "Muted track still carried nonzero audio"
            tracks[0].unmute()
            await until(lambda: not muted[1] and received[1]["nonzero"] >= before + 20)
            await asyncio.to_thread(rooms.update, "host", room["id"], "end")
            await until(lambda: all(not client.isconnected() for client in clients))
            stale = rtc.Room()
            clients.append(stale)
            try:
                await asyncio.wait_for(stale.connect(tickets[0]["url"], tickets[0]["token"]), 5)
            except Exception:
                pass
            else:
                raise AssertionError("Ended room accepted an old admission token")
            return {"passed": True, "transport": "LiveKit/WebRTC/Opus loopback",
                    "received": received, "startup_and_bidirectional_audio_seconds": first_audio_seconds,
                    "mute_and_unmute": True, "host_end_disconnects_both": True,
                    "ended_room_rejects_old_ticket": True, "microphone_or_device_used": False}
        finally:
            for task in producers + consumers:
                task.cancel()
            await asyncio.gather(*(producers + consumers), return_exceptions=True)
            for stream in streams:
                await stream.aclose()
            for client in clients:
                await client.disconnect()
            for source in sources:
                await source.aclose()
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-binary", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = asyncio.run(asyncio.wait_for(verify(args.server_binary.resolve()), 90))
        # Release SDK reference cycles before its process-wide native FFI shutdown.
        gc.collect()
    except Exception as error:
        # SDK exceptions can contain connection URLs; keep diagnostics credential-free.
        print(json.dumps({"passed": False, "error_type": type(error).__name__}))
        sys.exit(1)
    print(json.dumps(result, ensure_ascii=False, indent=2))
