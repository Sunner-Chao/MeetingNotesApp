from __future__ import annotations

import sys
import hashlib
import sqlite3
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from fastapi.testclient import TestClient


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

from community_api import build_community_router, build_public_community_router


@dataclass(frozen=True)
class TestPrincipal:
    user_id: str
    is_admin: bool = False


class CommunityRouteTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "routes.db"
        self.owner_id = "route-owner"
        self.admin_id = "route-admin"
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute("CREATE TABLE users (id TEXT PRIMARY KEY)")
            conn.executemany(
                "INSERT INTO users(id) VALUES (?)",
                ((self.owner_id,), (self.admin_id,)),
            )
            conn.commit()
        finally:
            conn.close()

        def require_principal(authorization: str | None = Header(default=None)) -> TestPrincipal:
            if authorization == "Bearer owner-token":
                return TestPrincipal(self.owner_id)
            if authorization == "Bearer admin-token":
                return TestPrincipal(self.admin_id, is_admin=True)
            raise HTTPException(status_code=401, detail="unauthorized")

        self.app = FastAPI()
        self.app.include_router(
            build_community_router(lambda: self.db_path, require_principal)
        )
        self.app.include_router(build_public_community_router(lambda: self.db_path))

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

    def test_public_browsing_requires_approved_review_and_moderation_is_admin_only(self) -> None:
        owner_headers = {"Authorization": "Bearer owner-token"}
        with TestClient(self.app) as client:
            created = client.post(
                "/api/account/community/drafts",
                headers=owner_headers,
                json=self.payload(),
            )
            post_id = created.json()["id"]
            client.post(
                f"/api/account/community/posts/{post_id}/publish",
                headers=owner_headers,
            )
            self.assertEqual(client.get("/api/community/posts").json()["items"], [])
            self.assertEqual(
                client.post(
                    f"/api/account/community/moderation/{post_id}",
                    headers=owner_headers,
                    json={"decision": "approved"},
                ).status_code,
                403,
            )
            approved = client.post(
                f"/api/account/community/moderation/{post_id}",
                headers={"Authorization": "Bearer admin-token"},
                json={"decision": "approved", "reason": ""},
            )
            self.assertEqual(approved.status_code, 200)
            public = client.get("/api/community/posts")
            self.assertEqual(public.status_code, 200)
            self.assertEqual(public.json()["items"][0]["id"], post_id)
            self.assertEqual(client.get(f"/api/community/posts/{post_id}").status_code, 200)
            mine = client.get("/api/account/community/posts", headers=owner_headers)
            self.assertEqual(mine.status_code, 200)
            self.assertEqual(mine.json()["items"][0]["review"]["status"], "approved")

    def test_public_search_exposes_only_confirmed_coarse_metadata(self) -> None:
        owner_headers = {"Authorization": "Bearer owner-token"}
        admin_headers = {"Authorization": "Bearer admin-token"}
        payload = {
            **self.payload(),
            "destination": "北京",
            "travel_date": "2026-08-06",
            "travel_days": 2,
            "stage_titles": ["展馆参观", "小组讨论"],
            "tags": ["历史", "研学"],
            "pois": ["中国国家博物馆"],
        }
        with TestClient(self.app) as client:
            created = client.post(
                "/api/account/community/drafts", headers=owner_headers, json=payload
            )
            self.assertEqual(created.status_code, 201)
            post_id = created.json()["id"]
            client.post(
                f"/api/account/community/posts/{post_id}/publish", headers=owner_headers
            )
            client.post(
                f"/api/account/community/moderation/{post_id}",
                headers=admin_headers,
                json={"decision": "approved"},
            )
            result = client.get(
                "/api/community/posts?q=国家博物馆&destination=北京&tag=历史&poi=中国国家博物馆"
            )
            self.assertEqual(result.status_code, 200)
            item = result.json()["items"][0]
            self.assertEqual(item["destination"], "北京")
            self.assertEqual(item["stages"], ["展馆参观", "小组讨论"])
            self.assertNotIn("journey_id", item)
            self.assertNotIn("client_snapshot_id", item)

    def test_interaction_and_comment_routes_require_approved_post_and_owner_delete(self) -> None:
        owner_headers = {"Authorization": "Bearer owner-token"}
        admin_headers = {"Authorization": "Bearer admin-token"}
        with TestClient(self.app) as client:
            created = client.post(
                "/api/account/community/drafts", headers=owner_headers, json=self.payload()
            )
            post_id = created.json()["id"]
            client.post(f"/api/account/community/posts/{post_id}/publish", headers=owner_headers)
            self.assertEqual(
                client.post(
                    f"/api/account/community/posts/{post_id}/like",
                    headers=owner_headers,
                ).status_code,
                404,
            )
            client.post(
                f"/api/account/community/moderation/{post_id}",
                headers=admin_headers,
                json={"decision": "approved", "reason": ""},
            )

            liked = client.post(
                f"/api/account/community/posts/{post_id}/like", headers=owner_headers
            )
            self.assertEqual(liked.status_code, 200)
            self.assertTrue(liked.json()["liked"])
            bookmarked = client.post(
                f"/api/account/community/posts/{post_id}/bookmark", headers=owner_headers
            )
            self.assertTrue(bookmarked.json()["bookmarked"])
            self.assertTrue(
                client.get(
                    f"/api/account/community/posts/{post_id}/interactions",
                    headers=owner_headers,
                ).json()["bookmarked"]
            )

            comment = client.post(
                f"/api/account/community/posts/{post_id}/comments",
                headers=owner_headers,
                json={"content": "建议安排半天现场观察"},
            )
            self.assertEqual(comment.status_code, 201)
            comment_id = comment.json()["id"]
            public_comments = client.get(f"/api/community/posts/{post_id}/comments")
            self.assertEqual(public_comments.status_code, 200)
            self.assertEqual(public_comments.json()["items"][0]["content"], "建议安排半天现场观察")
            self.assertNotIn("user_id", public_comments.json()["items"][0])

            forbidden_delete = client.delete(
                f"/api/account/community/comments/{comment_id}", headers=admin_headers
            )
            self.assertEqual(forbidden_delete.status_code, 404)
            deleted = client.delete(
                f"/api/account/community/comments/{comment_id}", headers=owner_headers
            )
            self.assertEqual(deleted.json()["status"], "deleted")
            self.assertEqual(client.get(f"/api/community/posts/{post_id}/comments").json()["items"], [])

    def test_bookmark_and_comment_report_routes_are_scoped_and_admin_resolvable(self) -> None:
        owner_headers = {"Authorization": "Bearer owner-token"}
        admin_headers = {"Authorization": "Bearer admin-token"}
        with TestClient(self.app) as client:
            created = client.post(
                "/api/account/community/drafts", headers=owner_headers, json=self.payload()
            )
            post_id = created.json()["id"]
            client.post(f"/api/account/community/posts/{post_id}/publish", headers=owner_headers)
            client.post(
                f"/api/account/community/moderation/{post_id}",
                headers=admin_headers,
                json={"decision": "approved", "reason": ""},
            )
            bookmarked = client.post(
                f"/api/account/community/posts/{post_id}/bookmark",
                headers=owner_headers,
            )
            self.assertTrue(bookmarked.json()["bookmarked"])
            saved = client.get("/api/account/community/bookmarks", headers=owner_headers)
            self.assertEqual(saved.status_code, 200)
            self.assertEqual(saved.json()["items"][0]["id"], post_id)

            comment = client.post(
                f"/api/account/community/posts/{post_id}/comments",
                headers=owner_headers,
                json={"content": "需要补充现场安全说明"},
            )
            comment_id = comment.json()["id"]
            report = client.post(
                f"/api/account/community/comments/{comment_id}/report",
                headers=admin_headers,
                json={"category": "safety", "reason": "安全信息不足"},
            )
            self.assertEqual(report.status_code, 201)
            queue = client.get(
                "/api/account/community/comment-reports?status=open",
                headers=admin_headers,
            )
            self.assertEqual(queue.status_code, 200)
            report_id = queue.json()["items"][0]["id"]
            self.assertNotIn("reporter_user_id", queue.json()["items"][0])
            self.assertEqual(
                client.get(
                    "/api/account/community/comment-reports",
                    headers={"Authorization": "Bearer owner-token"},
                ).status_code,
                403,
            )
            resolved = client.post(
                f"/api/account/community/comment-reports/{report_id}",
                headers=admin_headers,
                json={"decision": "keep"},
            )
            self.assertEqual(resolved.status_code, 200)
            self.assertEqual(resolved.json()["status"], "resolved")

    def test_media_manifest_and_chunk_routes_require_owner_then_public_review(self) -> None:
        owner_headers = {"Authorization": "Bearer owner-token"}
        original = b"\x89PNG\r\n\x1a\n" + b"not-a-real-image"
        thumbnail = original
        with TestClient(self.app) as client:
            created = client.post(
                "/api/account/community/drafts", headers=owner_headers, json=self.payload()
            )
            post_id = created.json()["id"]
            manifest = client.post(
                f"/api/account/community/posts/{post_id}/media",
                headers=owner_headers,
                json={
                    "client_media_id": "route-media-01",
                    "display_name": "现场图.png",
                    "mime_type": "image/png",
                    "original_bytes": len(original),
                    "original_sha256": hashlib.sha256(original).hexdigest(),
                    "thumbnail_bytes": len(thumbnail),
                    "thumbnail_sha256": hashlib.sha256(thumbnail).hexdigest(),
                },
            )
            self.assertEqual(manifest.status_code, 201)
            media_id = manifest.json()["id"]
            invalid_chunk = client.put(
                f"/api/account/community/posts/{post_id}/media/{media_id}/original",
                headers={
                    **owner_headers,
                    "Content-Range": f"bytes 0-{len(original) - 1}/{len(original)}",
                    "X-Chunk-SHA256": hashlib.sha256(original).hexdigest(),
                },
                content=original,
            )
            self.assertEqual(invalid_chunk.status_code, 400)
            self.assertEqual(
                client.get(
                    f"/api/account/community/posts/{post_id}/media", headers=owner_headers
                ).status_code,
                200,
            )
            self.assertEqual(client.get("/api/account/community/media-quota", headers=owner_headers).status_code, 200)

    def test_report_and_admin_queue_do_not_expose_reporter(self) -> None:
        owner_headers = {"Authorization": "Bearer owner-token"}
        other_headers = {"Authorization": "Bearer admin-token"}
        with TestClient(self.app) as client:
            created = client.post(
                "/api/account/community/drafts", headers=owner_headers, json=self.payload()
            )
            post_id = created.json()["id"]
            client.post(
                f"/api/account/community/posts/{post_id}/publish", headers=owner_headers
            )
            self.assertEqual(
                client.post(
                    f"/api/account/community/posts/{post_id}/report",
                    headers=owner_headers,
                    json={"category": "spam", "reason": "不能举报自己的内容"},
                ).status_code,
                404,
            )
            self.assertEqual(
                client.get(
                    "/api/account/community/moderation", headers=owner_headers
                ).status_code,
                403,
            )
            approved = client.post(
                f"/api/account/community/moderation/{post_id}",
                headers=other_headers,
                json={"decision": "approved"},
            )
            self.assertEqual(approved.status_code, 200)
            self.assertEqual(
                client.post(
                    f"/api/account/community/posts/{post_id}/report",
                    headers=owner_headers,
                    json={"category": "spam"},
                ).status_code,
                403,
            )

            reported = client.post(
                f"/api/account/community/posts/{post_id}/report",
                headers=other_headers,
                json={"category": "privacy", "reason": "位置描述需要复核"},
            )
            self.assertEqual(reported.status_code, 201)
            self.assertNotIn("reporter_user_id", reported.json())
            repeated = client.post(
                f"/api/account/community/posts/{post_id}/report",
                headers=other_headers,
                json={"category": "spam", "reason": "再次点击"},
            )
            self.assertEqual(repeated.status_code, 200)
            self.assertEqual(repeated.json()["id"], reported.json()["id"])

            queue = client.get(
                "/api/account/community/moderation?status=reported",
                headers=other_headers,
            )
            self.assertEqual(queue.status_code, 200)
            item = queue.json()["items"][0]
            self.assertEqual(item["open_report_count"], 1)
            self.assertNotIn("reporter_user_id", item)
            self.assertNotIn("reporter_user_id", item["reports"][0])

            rejected = client.post(
                f"/api/account/community/moderation/{post_id}",
                headers=other_headers,
                json={"decision": "rejected", "reason": "请补充来源说明"},
            )
            self.assertEqual(rejected.status_code, 200)
            self.assertEqual(
                client.get(
                    "/api/account/community/moderation?status=reported",
                    headers=other_headers,
                ).json()["items"],
                [],
            )


if __name__ == "__main__":
    unittest.main()
