from __future__ import annotations

import sys
import sqlite3
import tempfile
import unittest
from pathlib import Path


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

from community_service import (
    CommunityConflictError,
    CommunityDraftInput,
    CommunityError,
    CommunityNotFoundError,
    CommunityService,
)


class CommunityServiceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "community.db"
        self.owner_id = "community-owner"
        self.other_id = "community-other"
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute("CREATE TABLE users (id TEXT PRIMARY KEY)")
            conn.executemany(
                "INSERT INTO users(id) VALUES (?)",
                ((self.owner_id,), (self.other_id,)),
            )
            conn.commit()
        finally:
            conn.close()
        self.service = CommunityService(self.db_path)
        self.service.initialize()

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    @staticmethod
    def snapshot(**overrides) -> CommunityDraftInput:
        values = {
            "client_snapshot_id": "snapshot-01",
            "journey_id": "journey-01",
            "journey_edition_id": "edition-01",
            "source_edition_version": 2,
            "title": "园区研学第一日",
            "content": "上午参观能源站，下午完成小组复盘。",
            "ai_assisted": True,
            "redacted_coordinate_count": 1,
            "privacy_reviewed": True,
            "rights_confirmed": True,
        }
        values.update(overrides)
        return CommunityDraftInput(**values)

    def test_create_is_idempotent_and_rejects_key_reuse_with_changed_content(self) -> None:
        created, was_created = self.service.create_private_draft(
            self.owner_id,
            self.snapshot(),
        )
        repeated, was_repeated_created = self.service.create_private_draft(
            self.owner_id,
            self.snapshot(),
        )

        self.assertTrue(was_created)
        self.assertFalse(was_repeated_created)
        self.assertEqual(created["id"], repeated["id"])
        self.assertEqual(created["status"], "private_draft")
        with self.assertRaises(CommunityConflictError):
            self.service.create_private_draft(
                self.owner_id,
                self.snapshot(content="相同幂等键下的另一份正文"),
            )

    def test_owner_isolation_publish_and_terminal_withdrawal(self) -> None:
        created, _ = self.service.create_private_draft(
            self.owner_id,
            self.snapshot(),
        )

        with self.assertRaises(CommunityNotFoundError):
            self.service.get_post(self.other_id, created["id"])
        published = self.service.publish(self.owner_id, created["id"])
        self.assertEqual(published["status"], "published")
        self.assertEqual(published["moderation_status"], "pending")
        self.assertIsNotNone(published["published_at"])
        self.assertEqual(
            self.service.publish(self.owner_id, created["id"])["published_at"],
            published["published_at"],
        )

        withdrawn = self.service.withdraw(self.owner_id, created["id"])
        self.assertEqual(withdrawn["status"], "withdrawn")
        self.assertEqual(
            self.service.withdraw(self.owner_id, created["id"])["withdrawn_at"],
            withdrawn["withdrawn_at"],
        )
        with self.assertRaises(CommunityConflictError):
            self.service.publish(self.owner_id, created["id"])

    def test_rejects_unreviewed_or_precisely_located_content(self) -> None:
        invalid_snapshots = (
            self.snapshot(privacy_reviewed=False),
            self.snapshot(rights_confirmed=False),
            self.snapshot(content="集合点坐标：31.230416, 121.473701"),
            self.snapshot(content="latitude: 31.230416，地点已确认"),
        )
        for snapshot in invalid_snapshots:
            with self.subTest(snapshot=snapshot):
                with self.assertRaises(CommunityError):
                    self.service.create_private_draft(self.owner_id, snapshot)

    def test_public_visibility_requires_approval_and_withdrawal_removes_it(self) -> None:
        created, _ = self.service.create_private_draft(self.owner_id, self.snapshot())
        self.service.publish(self.owner_id, created["id"])

        self.assertEqual(self.service.list_public_posts()["items"], [])
        with self.assertRaises(CommunityNotFoundError):
            self.service.get_public_post(created["id"])

        approved = self.service.review_post(
            created["id"],
            decision="approved",
            reason="",
            reviewed_by="reviewer-01",
        )
        self.assertEqual(approved["review"]["status"], "approved")
        self.assertEqual(
            self.service.get_post(self.owner_id, created["id"])["review"]["status"],
            "approved",
        )
        public = self.service.list_public_posts()
        self.assertEqual([item["id"] for item in public["items"]], [created["id"]])
        self.assertEqual(public["items"][0]["author_label"], "研学同行者")
        self.assertNotIn("journey_id", public["items"][0])

        self.service.withdraw(self.owner_id, created["id"])
        self.assertEqual(self.service.list_public_posts()["items"], [])

    def test_rejected_post_stays_private_and_owner_list_includes_review(self) -> None:
        created, _ = self.service.create_private_draft(self.owner_id, self.snapshot())
        self.service.publish(self.owner_id, created["id"])
        with self.assertRaises(CommunityError):
            self.service.review_post(
                created["id"],
                decision="rejected",
                reason="",
                reviewed_by="reviewer-01",
            )
        reviewed = self.service.review_post(
            created["id"],
            decision="rejected",
            reason="需去除未授权人物信息",
            reviewed_by="reviewer-01",
        )
        self.assertEqual(reviewed["review"]["status"], "rejected")
        self.assertEqual(self.service.list_public_posts()["items"], [])
        mine = self.service.list_owner_posts(self.owner_id)
        self.assertEqual(mine["items"][0]["review"]["reason"], "需去除未授权人物信息")
        self.assertEqual(self.service.list_owner_posts(self.other_id)["items"], [])


if __name__ == "__main__":
    unittest.main()
