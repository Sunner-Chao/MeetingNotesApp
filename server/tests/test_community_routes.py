from __future__ import annotations

import sys
import sqlite3
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from fastapi.testclient import TestClient


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

from community_api import build_community_router


@dataclass(frozen=True)
class TestPrincipal:
    user_id: str


class CommunityRouteTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "routes.db"
        self.owner_id = "route-owner"
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute("CREATE TABLE users (id TEXT PRIMARY KEY)")
            conn.execute("INSERT INTO users(id) VALUES (?)", (self.owner_id,))
            conn.commit()
        finally:
            conn.close()

        def require_principal(authorization: str | None = Header(default=None)) -> TestPrincipal:
            if authorization != "Bearer owner-token":
                raise HTTPException(status_code=401, detail="unauthorized")
            return TestPrincipal(self.owner_id)

        self.app = FastAPI()
        self.app.include_router(
            build_community_router(lambda: self.db_path, require_principal)
        )

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    @staticmethod
    def payload() -> dict:
        return {
            "client_snapshot_id": "snapshot-route-01",
            "journey_id": "journey-route-01",
            "journey_edition_id": "edition-route-01",
            "source_edition_version": 1,
            "title": "研学路线记录",
            "content": "完成现场参观与讲解要点整理。",
            "ai_assisted": False,
            "redacted_coordinate_count": 0,
            "privacy_reviewed": True,
            "rights_confirmed": True,
        }

    def test_authenticated_idempotent_draft_publish_and_withdraw_flow(self) -> None:
        headers = {"Authorization": "Bearer owner-token"}
        with TestClient(self.app) as client:
            self.assertEqual(
                client.post("/api/account/community/drafts", json=self.payload()).status_code,
                401,
            )
            created = client.post(
                "/api/account/community/drafts",
                headers=headers,
                json=self.payload(),
            )
            self.assertEqual(created.status_code, 201)
            post_id = created.json()["id"]

            repeated = client.post(
                "/api/account/community/drafts",
                headers=headers,
                json=self.payload(),
            )
            self.assertEqual(repeated.status_code, 200)
            self.assertEqual(repeated.json()["id"], post_id)
            self.assertEqual(
                client.get(
                    f"/api/account/community/drafts/{post_id}",
                    headers=headers,
                ).status_code,
                200,
            )
            published = client.post(
                f"/api/account/community/posts/{post_id}/publish",
                headers=headers,
            )
            self.assertEqual(published.json()["status"], "published")
            withdrawn = client.post(
                f"/api/account/community/posts/{post_id}/withdraw",
                headers=headers,
            )
            self.assertEqual(withdrawn.json()["status"], "withdrawn")

    def test_rejects_unknown_payload_fields_including_raw_resource_data(self) -> None:
        response = TestClient(self.app).post(
            "/api/account/community/drafts",
            headers={"Authorization": "Bearer owner-token"},
            json={**self.payload(), "raw_audio": "base64-data"},
        )
        self.assertEqual(response.status_code, 422)


if __name__ == "__main__":
    unittest.main()
