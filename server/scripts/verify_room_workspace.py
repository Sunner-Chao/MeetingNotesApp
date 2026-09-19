"""Real loopback WebRTC audio -> STT protocol fixture -> shared text/report storage.

The STT fixture emits deterministic text from audio frame counts. This verifies
transport and lifecycle, NOT speech recognition accuracy or real LLM quality.
No microphone, phone, emulator, production service or permanent credentials used.
"""
from __future__ import annotations

import argparse
import asyncio
import gc
import json
import secrets
import sys
import traceback
from pathlib import Path

from verify_room_audio import verify, until
from room_transcription_worker import RoomTranscriptionSupervisor, TranscriptionSettings
from room_workspace import RoomWorkspaceService
from websockets.asyncio.server import serve


async def verify_workspace(rooms, room, clients, tracks):
    store = RoomWorkspaceService(rooms.path)
    room_id = room["id"]
    observed = []
    snapshots = []
    token = secrets.token_urlsafe(32)

    async def protocol_fixture(socket):
        assert socket.request.headers["Authorization"] == "Bearer " + token
        start = json.loads(await socket.recv())
        assert start["sample_rate"] == 16000 and start["speaker_diarization"] is False
        item = {"audio_bytes": 0, "stopped": False}
        observed.append(item)
        slot = len(observed)
        text = ""
        await socket.send(json.dumps({"type": "status", "message": "ready"}))
        async for message in socket:
            if isinstance(message, bytes):
                item["audio_bytes"] += len(message)
                if item["audio_bytes"] >= 16000 and not text:
                    text = f"通道{slot}讨论实施方案"
                    await socket.send(json.dumps({"type": "partial", "committed_text": text,
                        "audio_end_ms": item["audio_bytes"] / 32}))
            elif json.loads(message).get("event") == "stop":
                item["stopped"] = True
                await socket.send(json.dumps({"type": "final", "text": text + "。",
                    "audio_end_ms": item["audio_bytes"] / 32}))
                return

    def generate(job, provider, template):
        assert "共识与分歧" in template
        snapshots.append(job["snapshot"])
        return "# 测试纪要\n\n" + job["snapshot"]

    async with serve(protocol_fixture, "127.0.0.1", 0) as fixture:
        port = fixture.sockets[0].getsockname()[1]
        settings = TranscriptionSettings(True, f"ws://127.0.0.1:{port}", stt_token=token)
        supervisor = RoomTranscriptionSupervisor(rooms.path, rooms.media, settings, generate)
        worker = asyncio.create_task(supervisor.run())
        def read():
            return store.read("host", room_id)
        try:
            await until(lambda: read()["transcription"]["available"])
            for identity in ("host", "guest"):
                rooms.update(identity, room_id, "consent", True)
            store.start("host", room_id)
            await until(lambda: {row["user_id"] for row in read()["segments"]} == {"host", "guest"})
            assert read()["transcription"]["state"] == "running"
            assert any(not row["finalized"] for row in read()["segments"]), "No preview before pause"
            store.pause("host", room_id)
            await until(lambda: read()["transcription"]["state"] == "paused")
            paused = read()
            assert all(row["finalized"] for row in paused["segments"])
            assert all(row["text"].endswith("。") for row in paused["segments"]), "Final tail missing"
            first_ids = {row["id"] for row in paused["segments"]}
            queued = store.request_report("host", room_id)
            assert store.request_report("host", room_id)["request_id"] == queued["request_id"]
            await until(lambda: read()["report"]["state"] == "ready")
            assert "主持人：" in snapshots[0] and "成员：" in snapshots[0]
            assert store.read("guest", room_id)["report"]["text"] == read()["report"]["text"]
            store.start("host", room_id)
            await until(lambda: len(read()["segments"]) >= len(first_ids) + 2)
            assert first_ids.issubset({row["id"] for row in read()["segments"]})
            assert read()["transcription"]["origin_ms"] == paused["transcription"]["origin_ms"]
            rooms.update("guest", room_id, "consent", False)
            await until(lambda: read()["transcription"]["state"] == "paused")
            revision = read()["transcription"]["revision"]
            await asyncio.sleep(0.7)
            assert read()["transcription"]["revision"] == revision, "Text changed after consent revoked"
            return {"passed": True, "stt": "protocol fixture, not a recognition model",
                "report": "deterministic fixture, not a large language model",
                "tracks": len(observed), "audio_bytes": sum(item["audio_bytes"] for item in observed),
                "preview_before_pause": True, "pause_flushes_final": True,
                "resume_preserves_rows_and_clock": True, "consent_revocation_stops_writes": True,
                "members_read_same_report": True, "report_requests": len(snapshots)}
        finally:
            worker.cancel()
            await asyncio.gather(worker, return_exceptions=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-binary", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = asyncio.run(asyncio.wait_for(verify(args.server_binary.resolve(), verify_workspace), 90))
        gc.collect()
    except Exception as error:
        locations = [{"file": Path(item.filename).name, "line": item.lineno}
                     for item in traceback.extract_tb(error.__traceback__)]
        print(json.dumps({"passed": False, "error_type": type(error).__name__, "locations": locations}))
        gc.collect()
        sys.exit(1)
    print(json.dumps(result, ensure_ascii=False, indent=2))
