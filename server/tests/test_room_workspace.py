from __future__ import annotations

import sqlite3
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend-service"))
from meeting_rooms import RoomService
from room_workspace import RoomWorkspaceService, build_workspace_router
from room_transcription_worker import TranscriptAssembler, TranscriptionSettings
from audio_ingress import AudioClock, PcmFrame


class WorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "rooms.db"
        with closing(sqlite3.connect(self.path)) as db:
            db.execute("CREATE TABLE users(id TEXT PRIMARY KEY)")
            db.executemany("INSERT INTO users VALUES(?)", [("host",), ("guest",), ("outsider",)])
            db.commit()
        self.rooms = RoomService(self.path)
        self.rooms.initialize()
        self.host = SimpleNamespace(user_id="host", username="主持人")
        self.guest = SimpleNamespace(user_id="guest", username="成员")
        self.room = self.rooms.create(self.host, "讨论", True)
        self.id = self.room["id"]
        self.rooms.join(self.guest, self.room["code"], True)
        self.store = RoomWorkspaceService(self.path)
        self.worker = "worker-a"

    def start(self):
        self.store.heartbeat(self.worker)
        self.store.start("host", self.id)
        runs, _ = self.store.jobs(self.worker)
        self.run = runs[0]["run_id"]
        self.store.running(self.id, self.run, self.worker)

    def append(self, text="方案", user="guest", key=0, start=0, end=10, final=False):
        return self.store.append(self.id, self.run, self.worker, user, "track-" + user, key, start, end, text, final)

    def paused(self):
        self.store.pause("host", self.id)
        self.store.finish_run(self.id, self.run, self.worker)

    def test_start_requires_worker_host_and_all_consents(self):
        with self.assertRaises(HTTPException) as error:
            self.store.start("host", self.id)
        self.assertEqual(503, error.exception.status_code)
        self.store.heartbeat(self.worker)
        with self.assertRaises(HTTPException) as error:
            self.store.start("guest", self.id)
        self.assertEqual(403, error.exception.status_code)
        self.rooms.update("guest", self.id, "consent", False)
        with self.assertRaises(HTTPException):
            self.store.start("host", self.id)

    def test_updates_replace_preview_and_duplicate_final_does_not_duplicate_text(self):
        self.start()
        self.assertTrue(self.append())
        first = self.store.read("host", self.id)
        self.assertFalse(self.append())
        self.assertTrue(self.append("方案通过。", final=True))
        self.assertFalse(self.append("重复", final=True))
        delta = self.store.read("guest", self.id, first["next_cursor"])
        self.assertEqual(first["segments"][0]["id"], delta["segments"][0]["id"])
        self.assertEqual("方案通过。", delta["segments"][0]["text"])
        self.assertEqual("成员", delta["segments"][0]["display_name"])
        self.assertEqual(1, len(self.store.read("host", self.id)["segments"]))

    def test_pause_accepts_pre_stop_tail_then_rejects_late_updates(self):
        self.start()
        self.append(end=0)
        self.assertEqual("stopping", self.store.pause("host", self.id)["state"])
        self.assertTrue(self.append("方案最后一句", end=0, final=True))
        self.assertFalse(self.append("暂停后的音频", key=2, end=99999999))
        with self.assertRaises(HTTPException):
            self.store.request_report("host", self.id)
        self.store.finish_run(self.id, self.run, self.worker)
        self.assertFalse(self.append("迟到结果", key=3, end=0))
        self.assertEqual("paused", self.store.read("host", self.id)["transcription"]["state"])

    def test_resume_preserves_timeline_origin_and_rejects_old_run(self):
        self.start()
        old_run = self.run
        origin = self.store.read("host", self.id)["transcription"]["origin_ms"]
        self.append(final=True)
        self.paused()
        self.start()
        self.assertNotEqual(old_run, self.run)
        self.assertEqual(origin, self.store.read("host", self.id)["transcription"]["origin_ms"])
        self.assertFalse(self.store.append(self.id, old_run, self.worker, "guest", "old", 0, 0, 0, "旧段", True))
        self.assertTrue(self.append("继续", end=30, final=True))
        self.assertEqual(2, len(self.store.read("host", self.id)["segments"]))

    def test_consent_revocation_blocks_results_and_finalizes_existing_partial(self):
        self.start()
        self.append()
        self.rooms.update("guest", self.id, "consent", False)
        self.assertFalse(self.append("不再同意后的文字"))
        result = self.store.read("host", self.id)
        self.assertEqual("paused", result["transcription"]["state"])
        self.assertTrue(result["segments"][0]["finalized"])
        self.assertIsNone(self.store.capture_context(self.id, self.run, self.worker))

    def test_new_member_without_consent_pauses_running_transcription(self):
        self.start()
        self.rooms.join(SimpleNamespace(user_id="outsider", username="新成员"), self.room["code"], False)
        self.assertFalse(self.append())
        self.assertEqual("paused", self.store.read("host", self.id)["transcription"]["state"])

    def test_outsider_and_left_member_cannot_read_shared_content(self):
        self.start()
        self.append()
        with self.assertRaises(HTTPException):
            self.store.read("outsider", self.id)
        self.rooms.update("guest", self.id, "leave")
        self.assertFalse(self.append())
        with self.assertRaises(HTTPException):
            self.store.read("guest", self.id)

    def test_worker_lease_fences_old_writer_after_takeover(self):
        self.start()
        self.assertFalse(self.store.heartbeat("worker-b"))
        with self.rooms.connect() as db:
            db.execute("UPDATE room_worker SET heartbeat_ms=0")
        self.assertEqual("interrupted", self.store.read("host", self.id)["transcription"]["state"])
        self.assertTrue(self.store.heartbeat("worker-b"))
        self.assertFalse(self.append())
        self.assertEqual("failed", self.store.read("host", self.id)["transcription"]["state"])

    def test_report_snapshot_is_shared_idempotent_and_clears_previous_body(self):
        self.start()
        self.append("旧内容", final=True)
        self.paused()
        first = self.store.request_report("host", self.id)
        self.assertEqual(first, self.store.request_report("host", self.id))
        self.assertTrue(self.store.claim_report(self.id, first["request_id"], self.worker))
        self.store.finish_report(self.id, first["request_id"], self.worker, "第一版纪要")
        self.assertEqual("第一版纪要", self.store.read("guest", self.id)["report"]["text"])
        self.start()
        self.append("新增内容", final=True)
        self.paused()
        second = self.store.request_report("host", self.id)
        self.assertNotEqual(first["request_id"], second["request_id"])
        self.assertEqual("", self.store.read("guest", self.id)["report"]["text"])
        self.store.finish_report(self.id, first["request_id"], self.worker, "迟到旧稿")
        self.assertEqual("queued", self.store.read("guest", self.id)["report"]["state"])
        with self.rooms.connect(write=False) as db:
            job = db.execute("SELECT snapshot FROM room_reports WHERE room_id=?", (self.id,)).fetchone()
        self.assertIn("旧内容", job[0])
        self.assertIn("新增内容", job[0])
        self.assertIn("成员：", job[0])

    def test_guest_cannot_generate_or_pause_and_empty_report_is_rejected(self):
        self.start()
        with self.assertRaises(HTTPException):
            self.store.pause("guest", self.id)
        self.paused()
        with self.assertRaises(HTTPException):
            self.store.request_report("guest", self.id)
        with self.assertRaises(HTTPException):
            self.store.request_report("host", self.id)

    def test_deleted_member_removes_transcripts_and_reports_and_changes_sync_key(self):
        self.start()
        self.append(final=True)
        self.paused()
        self.store.request_report("host", self.id)
        before = self.store.read("host", self.id)
        with self.rooms.connect() as db:
            db.execute("DELETE FROM users WHERE id='guest'")
        after = self.store.read("host", self.id)
        self.assertEqual([], after["segments"])
        self.assertIsNone(after["report"])
        self.assertNotEqual(before["snapshot_key"], after["snapshot_key"])

    def test_delta_pagination_and_endpoint_membership(self):
        self.start()
        for number in range(3):
            self.append(str(number), key=number, final=True)
        first = self.store.read("host", self.id, limit=2)
        self.assertTrue(first["has_more"])
        self.assertEqual(1, len(self.store.read("host", self.id, first["next_cursor"])["segments"]))
        app = FastAPI()
        app.include_router(build_workspace_router(lambda: self.path, lambda: self.guest))
        with TestClient(app) as client:
            result = client.get(f"/api/account/rooms/{self.id}/transcripts")
            self.assertEqual("no-store", result.headers["cache-control"])
            self.assertEqual(403, client.post(f"/api/account/rooms/{self.id}/transcription/start").status_code)
            self.assertEqual(422, client.get(f"/api/account/rooms/{self.id}/transcripts?after=-1").status_code)

    def test_room_end_allows_bounded_tail_and_retains_read_access(self):
        self.start()
        self.rooms.update("host", self.id, "end")
        self.assertTrue(self.append("结束尾句", end=0, final=True))
        self.store.finish_run(self.id, self.run, self.worker)
        self.assertEqual("结束尾句", self.store.read("guest", self.id)["segments"][0]["text"])
        with self.assertRaises(HTTPException):
            self.store.start("host", self.id)

    def test_expired_worker_cannot_revive_stale_streams_with_its_old_identity(self):
        self.start()
        self.append()
        with self.rooms.connect() as db:
            db.execute("UPDATE room_worker SET heartbeat_ms=0")
        self.assertTrue(self.store.heartbeat(self.worker))
        page = self.store.read("host", self.id)
        self.assertEqual("failed", page["transcription"]["state"])
        self.assertTrue(page["segments"][0]["finalized"])
        self.assertEqual([], self.store.jobs(self.worker)[0])


class AudioBoundaryTests(unittest.TestCase):
    def test_pcm_format_and_sequence_are_validated_without_logging_audio(self):
        frame = PcmFrame(b"\x01\x02" * 160, 0, 10)
        self.assertEqual(10, frame.duration_ms)
        self.assertNotIn("data=", repr(frame))
        with self.assertRaises(ValueError):
            PcmFrame(b"odd", 0, 0)
        with self.assertRaises(ValueError):
            PcmFrame(b"\0\0", 0, 0, sample_rate=48000)
        clock = AudioClock()
        clock.push(frame)
        with self.assertRaises(ValueError):
            clock.push(frame)

    def test_late_decoder_results_keep_their_original_time_before_a_mute_gap(self):
        clock = AudioClock()
        for index in range(3):
            clock.push(PcmFrame(b"\0" * 320, index, 1000 + (index + 1) * 10))
        clock.push(PcmFrame(b"\0" * 320, 3, 5000))
        self.assertEqual(1020, clock.map(20))
        self.assertEqual(1030, clock.map(30))
        self.assertEqual(5000, clock.map(40))

    def test_preview_updates_segment_and_stop_preserves_delayed_tail(self):
        assembler = TranscriptAssembler()
        first = assembler.accept("讨论", 1000)
        second = assembler.accept("讨论方案", 2000)
        final = assembler.accept("讨论方案通过", 2100, True)
        self.assertEqual(first[0], second[0])
        self.assertEqual("讨论方案通过", final[3])
        self.assertTrue(final[4])
        self.assertIsNone(assembler.accept("讨论方案通过", 2100, True))
        self.assertEqual(1, assembler.accept("讨论方案通过下一题。", 3000)[0])

    def test_changed_cumulative_result_is_not_silently_duplicated(self):
        assembler = TranscriptAssembler()
        assembler.accept("原句", 1000)
        with self.assertRaises(ValueError):
            assembler.accept("不兼容的新句", 2000)

    def test_runtime_is_explicit_and_credentials_are_redacted(self):
        self.assertFalse(TranscriptionSettings().configured)
        settings = TranscriptionSettings(True, "ws://127.0.0.1:8889/ws/transcribe-stream", "private-test-token")
        self.assertTrue(settings.configured)
        self.assertNotIn("private-test-token", repr(settings))
        self.assertFalse(TranscriptionSettings(True, "ws://public.example", "x").configured)

    def test_room_track_uses_existing_owner_scoped_stt_token(self):
        from common.account_stt_token import verify_account_stt_token
        settings = TranscriptionSettings(account_secret="unit-test-secret")
        token = settings.token_for("guest")
        self.assertEqual("guest", verify_account_stt_token("unit-test-secret", token))


if __name__ == "__main__":
    unittest.main()
