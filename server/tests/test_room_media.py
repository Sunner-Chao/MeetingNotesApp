from __future__ import annotations

import base64
import hashlib
import hmac
import json
import sqlite3
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import requests
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend-service"))
from meeting_rooms import RoomService, build_room_router
from room_media import MediaSettings, RoomMediaService


class FakeMedia:
    configured = True

    def __init__(self):
        self.removed = []
        self.ended = []
        self.unavailable = False
        self.on_connect = lambda: None

    def connect(self, room, user_id, display_name):
        self.on_connect()
        return {"identity": user_id, "room_name": "meeting-" + room["id"], "token": "test-ticket"}

    def remove(self, room_id, user_id):
        if self.unavailable:
            raise HTTPException(503, "unavailable")
        self.removed.append((room_id, user_id))

    def end(self, room_id):
        if self.unavailable:
            raise HTTPException(503, "unavailable")
        self.ended.append(room_id)


class RoomMediaTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "rooms.db"
        with closing(sqlite3.connect(self.path)) as db:
            db.execute("CREATE TABLE users(id TEXT PRIMARY KEY)")
            db.executemany("INSERT INTO users VALUES(?)", [("host",), ("guest",), ("outsider",)])
            db.commit()
        self.media = FakeMedia()
        self.rooms = RoomService(self.path, self.media)
        self.rooms.initialize()
        self.host = SimpleNamespace(user_id="host", username="主持人")
        self.guest = SimpleNamespace(user_id="guest", username="成员")
        self.room = self.rooms.create(self.host, "策划讨论", False)
        self.rooms.join(self.guest, self.room["code"], False)

    def test_unrecorded_call_does_not_require_recording_consent(self):
        self.assertFalse(self.rooms.get("host", self.room["id"])["all_recording_consented"])
        self.assertEqual("guest", self.rooms.media_session("guest", self.room["id"])["identity"])

    def test_outsider_and_departed_member_cannot_obtain_ticket(self):
        with self.assertRaises(HTTPException) as error:
            self.rooms.media_session("outsider", self.room["id"])
        self.assertEqual(404, error.exception.status_code)
        self.rooms.update("guest", self.room["id"], "leave")
        with self.assertRaises(HTTPException):
            self.rooms.media_session("guest", self.room["id"])
        self.assertIn((self.room["id"], "guest"), self.media.removed)

    def test_only_host_can_end_and_ended_room_has_no_new_ticket(self):
        with self.assertRaises(HTTPException):
            self.rooms.update("guest", self.room["id"], "end")
        self.rooms.update("host", self.room["id"], "end")
        self.assertIn(self.room["id"], self.media.ended)
        with self.assertRaises(HTTPException) as error:
            self.rooms.media_session("host", self.room["id"])
        self.assertEqual(409, error.exception.status_code)

    def test_end_during_media_creation_rejects_ticket_and_deletes_media_room(self):
        self.media.on_connect = lambda: self.rooms.update("host", self.room["id"], "end")
        with self.assertRaises(HTTPException):
            self.rooms.media_session("guest", self.room["id"])
        self.assertGreaterEqual(self.media.ended.count(self.room["id"]), 2)

    def test_leave_during_media_creation_rejects_ticket(self):
        self.media.on_connect = lambda: self.rooms.update("guest", self.room["id"], "leave")
        with self.assertRaises(HTTPException):
            self.rooms.media_session("guest", self.room["id"])
        self.assertGreaterEqual(self.media.removed.count((self.room["id"], "guest")), 2)

    def test_cleanup_survives_restart_and_blocks_rejoin_until_removed(self):
        self.media.unavailable = True
        self.rooms.media_leave("guest", self.room["id"])
        restarted = RoomService(self.path, self.media)
        with self.assertRaises(HTTPException) as error:
            restarted.media_session("guest", self.room["id"])
        self.assertEqual(503, error.exception.status_code)
        self.media.unavailable = False
        restarted.flush_media_cleanup()
        self.assertEqual("guest", restarted.media_session("guest", self.room["id"])["identity"])
        restarted.flush_media_cleanup()
        self.assertEqual(1, len(self.media.removed))

    def test_deleting_host_account_enqueues_durable_room_cleanup(self):
        with self.rooms.connect() as db:
            db.execute("DELETE FROM users WHERE id='host'")
        self.rooms.flush_media_cleanup()
        self.assertIn(self.room["id"], self.media.ended)
        self.assertIn((self.room["id"], "guest"), self.media.removed)

    def test_deleting_guest_only_removes_guest_media(self):
        with self.rooms.connect() as db:
            db.execute("DELETE FROM users WHERE id='guest'")
        self.rooms.flush_media_cleanup()
        self.assertEqual([(self.room["id"], "guest")], self.media.removed)
        self.assertEqual([], self.media.ended)

    def test_ticket_response_is_not_cacheable_and_uses_account_identity(self):
        app = FastAPI()
        app.include_router(build_room_router(lambda: self.path, lambda: self.guest))
        with patch("meeting_rooms.RoomMediaService", return_value=self.media):
            with TestClient(app) as client:
                result = client.post(f"/api/account/rooms/{self.room['id']}/media-session")
        self.assertEqual(200, result.status_code)
        self.assertEqual("no-store", result.headers["cache-control"])
        self.assertEqual("guest", result.json()["identity"])


class LiveKitAdapterTests(unittest.TestCase):
    def setUp(self):
        self.settings = MediaSettings("wss://rtc.example", "http://127.0.0.1:7880", "test-key", "s" * 32)
        self.media = RoomMediaService(self.settings)

    def test_client_jwt_is_signed_room_scoped_and_microphone_only(self):
        with patch.object(self.media, "rpc"):
            ticket = self.media.connect({"id": "room-one"}, "user-one", "成员一")
        header, payload, signature = ticket["token"].split(".")
        claims = json.loads(base64.urlsafe_b64decode(payload + "=="))
        expected = hmac.new(self.settings.api_secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()
        self.assertEqual(expected, base64.urlsafe_b64decode(signature + "=="))
        self.assertEqual("user-one", claims["sub"])
        self.assertEqual("meeting-room-one", claims["video"]["room"])
        self.assertEqual(["microphone"], claims["video"]["canPublishSources"])
        self.assertFalse(claims["video"]["canPublishData"])
        self.assertNotIn("roomAdmin", claims["video"])
        self.assertEqual(65, claims["exp"] - claims["nbf"])

    def test_config_rejects_plaintext_public_endpoint_and_hides_credentials(self):
        self.assertTrue(self.settings.configured)
        self.assertNotIn(self.settings.api_secret, repr(self.settings))
        for public in ["ws://rtc.example", "wss://name:password@rtc.example", "wss://rtc.example?token=x", "garbage"]:
            self.assertFalse(MediaSettings(public, self.settings.api_url, "test-key", "s" * 32).configured)
        self.assertTrue(MediaSettings("ws://127.0.0.1:7880", self.settings.api_url, "test-key", "s" * 32).configured)

    def test_provider_exception_does_not_expose_sensitive_detail(self):
        with patch("room_media.requests.post", side_effect=requests.ConnectionError("Bearer secret-token")):
            with self.assertRaises(HTTPException) as error:
                self.media.connect({"id": "room"}, "user", "name")
        self.assertEqual(503, error.exception.status_code)
        self.assertNotIn("secret-token", error.exception.detail)

    def test_missing_room_or_member_cleanup_is_idempotent(self):
        with patch("room_media.requests.post", return_value=Mock(status_code=404)):
            self.media.end("room")
            self.media.remove("room", "user")


if __name__ == "__main__":
    unittest.main()
