from __future__ import annotations

import sqlite3
import base64
import sys
import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

import web_backend as backend
from agent_gateway import AgentGateway


class BackendDatabaseTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        backend.DB_PATH = Path(self.temp_dir.name) / "meeting_notes.db"
        backend.init_db()

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def create_meeting(self) -> dict:
        return backend.create_meeting(
            backend.MeetingPayload(
                title="Concurrency review",
                created_at=1,
                duration_ms=0,
            )
        )

    def test_foreign_keys_reject_orphan_transcript(self) -> None:
        with self.assertRaises(sqlite3.IntegrityError):
            backend.create_transcript(
                backend.TranscriptPayload(
                    meeting_id="missing",
                    content="orphan",
                    created_at=1,
                )
            )

    def test_report_upsert_returns_stable_database_id(self) -> None:
        meeting = self.create_meeting()
        payload = backend.ReportPayload(
            meeting_id=meeting["id"],
            summary="first",
            generated_at=2,
        )
        first = backend.upsert_report(meeting["id"], payload)
        second = backend.upsert_report(
            meeting["id"],
            payload.model_copy(update={"summary": "second", "generated_at": 3}),
        )

        self.assertEqual(first["id"], second["id"])
        self.assertEqual(backend.get_report(meeting["id"])["summary"], "second")

    def test_web_api_accepts_bearer_and_admin_basic_tokens(self) -> None:
        previous_token = backend.WEB_API_TOKEN
        previous_username = backend.WEB_API_USERNAME
        backend.WEB_API_TOKEN = "test-secret"
        backend.WEB_API_USERNAME = "ubuntu"
        basic = base64.b64encode(b"ubuntu:test-secret").decode("ascii")
        try:
            self.assertTrue(backend.is_web_request_authorized("Bearer test-secret"))
            self.assertTrue(backend.is_web_request_authorized(f"Basic {basic}"))
            admin_basic = base64.b64encode(b"admin:test-secret").decode("ascii")
            self.assertFalse(backend.is_web_request_authorized(f"Basic {admin_basic}"))
            self.assertFalse(backend.is_web_request_authorized("Bearer wrong"))
            self.assertFalse(backend.is_web_request_authorized("Basic invalid"))
        finally:
            backend.WEB_API_TOKEN = previous_token
            backend.WEB_API_USERNAME = previous_username

    def test_dashboard_is_rendered_from_template_with_runtime_values(self) -> None:
        html = backend.index()

        self.assertIn("智悟本 | 管理端 Web", html)
        self.assertIn(f"Version {backend.SERVER_VERSION}", html)
        self.assertIn(f"127.0.0.1:{backend.PORT}", html)
        self.assertNotIn("__VERSION__", html)
        self.assertNotIn("__DB_PATH__", html)

    def test_agent_routes_require_their_own_bearer_token(self) -> None:
        previous_gateway = backend.AGENT_GATEWAY
        previous_web_token = backend.WEB_API_TOKEN
        backend.AGENT_GATEWAY = AgentGateway(
            db_path=backend.DB_PATH,
            work_root=Path(self.temp_dir.name) / "agent-tasks",
            bootstrap_token="agent-secret",
        )
        backend.WEB_API_TOKEN = "web-secret"
        try:
            with TestClient(backend.app) as client:
                self.assertEqual(client.get("/api/agent/health").status_code, 401)
                self.assertEqual(
                    client.get(
                        "/api/agent/health",
                        headers={"Authorization": "Bearer web-secret"},
                    ).status_code,
                    401,
                )
                response = client.get(
                    "/api/agent/health",
                    headers={"Authorization": "Bearer agent-secret"},
                )
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.json()["quota"]["requests_remaining"], 1000)
        finally:
            backend.AGENT_GATEWAY = previous_gateway
            backend.WEB_API_TOKEN = previous_web_token

    def test_public_community_routes_bypass_web_admin_credentials(self) -> None:
        previous_web_token = backend.WEB_API_TOKEN
        backend.WEB_API_TOKEN = "web-secret"
        try:
            with TestClient(backend.app) as client:
                for path in (
                    "/api/community/status",
                    "/api/community/posts",
                    "/api/community/collections",
                ):
                    with self.subTest(path=path):
                        self.assertEqual(client.get(path).status_code, 200)
                self.assertEqual(client.get("/api/meetings").status_code, 401)
        finally:
            backend.WEB_API_TOKEN = previous_web_token


if __name__ == "__main__":
    unittest.main()
