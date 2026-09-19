"""Optional in-process room subscriber. LiveKit and STT credentials stay server-side."""
from __future__ import annotations

import asyncio
import json
import os
import sys
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlparse

from room_media import RoomMediaService, encode_token
from room_workspace import RoomWorkspaceService, now_ms
from audio_ingress import AudioClock, PcmFrame

SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))


@dataclass(frozen=True)
class TranscriptionSettings:
    enabled: bool = False
    stt_url: str = ""
    stt_token: str = field(default="", repr=False)
    account_secret: str = field(default="", repr=False)
    report_provider: str = "codex-cli"

    @classmethod
    def from_env(cls):
        return cls(os.getenv("MEETINGNOTES_ROOM_TRANSCRIPTION_ENABLED", "0") == "1",
                   os.getenv("MEETINGNOTES_ROOM_STT_URL", "").strip(),
                   os.getenv("MEETINGNOTES_ROOM_STT_TOKEN", "").strip(),
                   os.getenv("ACCOUNT_TOKEN_SECRET", "").strip(),
                   os.getenv("MEETINGNOTES_ROOM_REPORT_PROVIDER", "codex-cli").strip())

    @property
    def configured(self):
        try:
            target = urlparse(self.stt_url)
            valid = target.scheme == "wss" or (target.scheme == "ws" and target.hostname in {"127.0.0.1", "localhost", "::1"})
            return bool(self.enabled and valid and target.hostname and not target.username and not target.query and (self.stt_token or self.account_secret))
        except ValueError:
            return False

    def token_for(self, user_id):
        if self.account_secret:
            from common.account_stt_token import issue_account_stt_token
            return issue_account_stt_token(self.account_secret, user_id, int(time.time()) + 3600)
        return self.stt_token


class TranscriptAssembler:
    """Group cumulative committed fragments into readable, incrementally updated rows."""
    def __init__(self):
        self.seen = ""
        self.text = ""
        self.key = 0
        self.start_ms = 0
        self.end_ms = 0

    def accept(self, cumulative, end_ms, final=False):
        if not cumulative.startswith(self.seen):
            raise ValueError("STT cumulative transcript changed unexpectedly")
        delta = cumulative[len(self.seen):]
        self.seen = cumulative
        if delta:
            if not self.text:
                self.start_ms = max(self.end_ms, end_ms - 1200)
            self.text += delta
            self.end_ms = max(self.start_ms, end_ms)
        if not self.text.strip():
            return None
        closed = final or self.text.rstrip().endswith(tuple("。！？!?；;")) or self.end_ms - self.start_ms >= 8_000 or len(self.text) >= 140
        row = (self.key, self.start_ms, self.end_ms, self.text, closed)
        if closed:
            self.key += 1
            self.text = ""
        return row


class RoomAudioSession:
    def __init__(self, store, run, owner, media, settings):
        self.store, self.run, self.owner, self.media, self.settings = store, run, owner, media, settings
        self.client = None
        self.tracks = {}
        self.streams = {}
        self.allowed = set()
        self.failure = None
        self.stop_requested = asyncio.Event()

    async def context(self):
        return await asyncio.to_thread(self.store.capture_context, self.run["room_id"], self.run["run_id"], self.owner)

    async def run_session(self):
        from livekit import rtc
        context = await self.context()
        if not context:
            return
        if context["state"] == "stopping":
            return
        self.client = rtc.Room()
        self.allowed = set(context["members"])
        self.client.on("track_published", self.subscribe)
        self.client.on("participant_connected", lambda member: [self.subscribe(p, member) for p in member.track_publications.values()])
        self.client.on("track_subscribed", self.track_started)
        self.client.on("track_unsubscribed", self.track_stopped)
        name = self.media.room_name(self.run["room_id"])
        now = int(time.time())
        token = encode_token(self.media.settings, {"iss": self.media.settings.api_key,
            "sub": "transcriber-" + self.run["run_id"], "name": "会议转写", "nbf": now - 5, "exp": now + 60,
            "video": {"roomJoin": True, "room": name, "canPublish": False, "canSubscribe": True,
                      "canPublishData": False, "hidden": True, "recorder": True}})
        try:
            # Host can start transcription before anyone has published a microphone.
            await asyncio.to_thread(self.media.rpc, "CreateRoom", {"name": name, "empty_timeout": 300, "max_participants": 17}, {"roomCreate": True})
            await asyncio.wait_for(self.client.connect(self.media.settings.url, token, rtc.RoomOptions(auto_subscribe=False)), 10)
            for member in self.client.remote_participants.values():
                for publication in member.track_publications.values():
                    self.subscribe(publication, member)
            await asyncio.to_thread(self.store.running, self.run["room_id"], self.run["run_id"], self.owner)
            while not self.failure:
                context = await self.context()
                if not context:
                    break
                if context["state"] == "stopping":
                    self.stop_requested.set()
                    await asyncio.wait_for(asyncio.gather(*list(self.tracks.values())), 7)
                    break
                self.allowed = set(context["members"])
                if not self.client.isconnected():
                    raise RuntimeError("Room media disconnected")
                for member in self.client.remote_participants.values():
                    for publication in member.track_publications.values():
                        self.subscribe(publication, member)
                await asyncio.sleep(0.25)
            if self.failure:
                raise RuntimeError("Room STT track failed")
        finally:
            tasks = list(self.tracks.values())
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            await self.client.disconnect()

    def subscribe(self, publication, member):
        from livekit import rtc
        publication.set_subscribed(member.identity in self.allowed and publication.kind == rtc.TrackKind.KIND_AUDIO)

    def track_started(self, track, publication, member):
        from livekit import rtc
        if member.identity not in self.allowed or track.kind != rtc.TrackKind.KIND_AUDIO or publication.sid in self.tracks:
            return
        task = asyncio.create_task(self.transcribe(track, member.identity, publication.sid, uuid.uuid4().hex))
        self.tracks[publication.sid] = task
        def finished(completed):
            self.tracks.pop(publication.sid, None)
            if not completed.cancelled() and completed.exception() is not None:
                self.failure = True
        task.add_done_callback(finished)

    def track_stopped(self, track, publication, member):
        stream = self.streams.get(publication.sid)
        if stream:
            asyncio.create_task(stream.aclose())

    async def transcribe(self, track, user_id, publication_id, track_key):
        from livekit import rtc
        from websockets.asyncio.client import connect
        stream = rtc.AudioStream(track, sample_rate=16000, num_channels=1, frame_size_ms=20)
        self.streams[publication_id] = stream
        assembler, clock = TranscriptAssembler(), AudioClock()
        origin_ms = self.run["origin_ms"]
        # Bound unsent audio to two seconds. If the downstream stalls, fail visibly
        # instead of accumulating minutes of latency or silently dropping speech.
        frames = asyncio.Queue(maxsize=100)

        async def collect_audio():
            sequence = 0
            async for event in stream:
                frames.put_nowait(PcmFrame(bytes(event.frame.data), sequence, now_ms() - origin_ms))
                sequence += 1
            await frames.put(None)

        collector = asyncio.create_task(collect_audio())
        try:
            async with connect(self.settings.stt_url, additional_headers={"Authorization": "Bearer " + self.settings.token_for(user_id)},
                               open_timeout=5, close_timeout=2, max_size=2**20, max_queue=8, proxy=None) as socket:
                await socket.send(json.dumps({"event": "start", "sample_rate": 16000, "channels": 1,
                    "meeting_id": self.run["room_id"], "stream_provider": "local", "language": "zh",
                    "speaker_diarization": False}))
                ready = json.loads(await asyncio.wait_for(socket.recv(), 5))
                if ready.get("type") != "status":
                    raise RuntimeError("STT did not become ready")

                async def receive():
                    async for message in socket:
                        data = json.loads(message)
                        if data.get("type") == "error":
                            raise RuntimeError("STT rejected room track")
                        if data.get("type") not in {"partial", "final"}:
                            continue
                        final = data["type"] == "final"
                        text = data.get("committed_text", data.get("text", "") if final else "")
                        end_ms = clock.map(min(clock.sent_ms, float(data.get("audio_end_ms", clock.sent_ms))))
                        context = await self.context()
                        if context and context["state"] == "stopping":
                            # STT time is approximate; a frame already in flight may
                            # straddle pause. Keep the decoder tail within the cutoff.
                            end_ms = min(end_ms, context["cutoff_ms"])
                        row = assembler.accept(text, end_ms, final)
                        if row:
                            await asyncio.to_thread(self.store.append, self.run["room_id"], self.run["run_id"], self.owner,
                                user_id, track_key, *row)
                        if final:
                            return

                receiver = asyncio.create_task(receive())
                async def send_audio():
                    checked = 0.0
                    context = None
                    while True:
                        frame = await frames.get()
                        if frame is None:
                            return
                        if receiver.done():
                            await receiver
                            raise RuntimeError("STT ended while microphone was active")
                        if time.monotonic() - checked > 0.2:
                            context = await self.context()
                            if not context or user_id not in context["members"]:
                                return
                            checked = time.monotonic()
                        if context["state"] == "stopping" and frame.end_ms > context["cutoff_ms"]:
                            return
                        clock.push(frame)
                        await asyncio.wait_for(socket.send(frame.data), 2)
                sender = asyncio.create_task(send_audio())
                stop = asyncio.create_task(self.stop_requested.wait())
                try:
                    done, _ = await asyncio.wait({sender, receiver, stop, collector}, return_when=asyncio.FIRST_COMPLETED)
                    if collector in done:
                        await collector  # Propagate queue overflow, never hide it.
                    if receiver in done:
                        await receiver
                        raise RuntimeError("STT ended before room stop")
                    if stop in done:
                        collector.cancel()
                        await asyncio.gather(collector, return_exceptions=True)
                        # Wake an idle/muted track and drain only pre-pause frames.
                        await frames.put(None)
                    await asyncio.wait_for(sender, 3)
                    await socket.send(json.dumps({"event": "stop"}))
                    await asyncio.wait_for(receiver, 5)
                finally:
                    for task in (sender, receiver, stop):
                        task.cancel()
                    await asyncio.gather(sender, receiver, stop, return_exceptions=True)
        finally:
            collector.cancel()
            await asyncio.gather(collector, return_exceptions=True)
            self.streams.pop(publication_id, None)
            await stream.aclose()


REPORT_TEMPLATE_PATH = Path(__file__).resolve().parents[1] / "knowledge/templates/meeting-minutes/listening-room-v1/prompt.md"


class RoomTranscriptionSupervisor:
    def __init__(self, path: Path, media=None, settings=None, report_generator=None, session_factory=RoomAudioSession):
        self.store = RoomWorkspaceService(path)
        self.media = media or RoomMediaService()
        self.settings = settings or TranscriptionSettings.from_env()
        self.report_generator = report_generator
        self.session_factory = session_factory
        self.owner = uuid.uuid4().hex
        self.tasks = {}
        self.reports = {}

    async def _capture(self, run):
        try:
            await self.session_factory(self.store, run, self.owner, self.media, self.settings).run_session()
            await asyncio.to_thread(self.store.finish_run, run["room_id"], run["run_id"], self.owner)
            await asyncio.to_thread(self.store.fail, run["room_id"], run["run_id"], self.owner, "转写连接已断开，内容已保留，请继续")
        except asyncio.CancelledError:
            raise
        except Exception:
            await asyncio.to_thread(self.store.fail, run["room_id"], run["run_id"], self.owner,
                                   "本地转写暂不可用或通道已满，已识别内容保留，请稍后继续")

    async def _report(self, job):
        if not await asyncio.to_thread(self.store.claim_report, job["room_id"], job["request_id"], self.owner):
            return
        try:
            if not self.report_generator:
                raise RuntimeError("Report generator unavailable")
            template = REPORT_TEMPLATE_PATH.read_text(encoding="utf-8")
            text = await asyncio.to_thread(self.report_generator, job, self.settings.report_provider, template)
            if not text.strip():
                raise ValueError("Empty report")
            await asyncio.to_thread(self.store.finish_report, job["room_id"], job["request_id"], self.owner, text)
        except asyncio.CancelledError:
            raise
        except Exception:
            await asyncio.to_thread(self.store.finish_report, job["room_id"], job["request_id"], self.owner,
                                   message="纪要暂未生成，请检查生成服务与账户额度后重试")

    async def run(self):
        if not self.settings.configured or not self.media.configured:
            return
        # Missing optional runtime must not advertise a healthy transcriber.
        try:
            from livekit import rtc
            from websockets.asyncio.client import connect
        except ImportError:
            import logging
            logging.getLogger("meetingnotes.rooms").error("Room transcription runtime is not installed")
            return
        try:
            while True:
                if not await asyncio.to_thread(self.store.heartbeat, self.owner):
                    for task in list(self.tasks.values()) + list(self.reports.values()):
                        task.cancel()
                    await asyncio.gather(*self.tasks.values(), *self.reports.values(), return_exceptions=True)
                    self.tasks.clear()
                    self.reports.clear()
                    await asyncio.sleep(1)
                    continue
                runs, reports = await asyncio.to_thread(self.store.jobs, self.owner)
                valid = {run["run_id"] for run in runs}
                for key, task in list(self.tasks.items()):
                    if key not in valid:
                        task.cancel()
                        await asyncio.gather(task, return_exceptions=True)
                        self.tasks.pop(key)
                for run in runs:
                    if run["run_id"] not in self.tasks:
                        self.tasks[run["run_id"]] = asyncio.create_task(self._capture(run))
                for key, task in list(self.reports.items()):
                    if task.done():
                        self.reports.pop(key)
                for job in reports:
                    if job["request_id"] not in self.reports:
                        self.reports[job["request_id"]] = asyncio.create_task(self._report(job))
                await asyncio.sleep(0.5)
        finally:
            tasks = list(self.tasks.values()) + list(self.reports.values())
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            await asyncio.to_thread(self.store.release, self.owner)
