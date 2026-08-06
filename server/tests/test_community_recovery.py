from __future__ import annotations

import sqlite3
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path


SERVER_DIR = Path(__file__).resolve().parents[1]
BACKEND_DIR = SERVER_DIR / "backend-service"
SCRIPTS_DIR = SERVER_DIR / "scripts"
sys.path.insert(0, str(BACKEND_DIR))
sys.path.insert(0, str(SCRIPTS_DIR))

from community_service import CommunityDraftInput, CommunityService
from verify_community_backup import verify_community_database


class CommunityRecoveryTests(unittest.TestCase):
    def test_sqlite_backup_restores_community_content_and_review_state(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source_path = Path(temp_dir) / "source.db"
            backup_path = Path(temp_dir) / "restored.db"
            owner_id = "recovery-owner"
            reader_id = "recovery-reader"
            admin_id = "recovery-admin"
            with closing(sqlite3.connect(source_path)) as connection:
                connection.execute("CREATE TABLE users (id TEXT PRIMARY KEY)")
                connection.executemany(
                    "INSERT INTO users(id) VALUES (?)",
                    ((owner_id,), (reader_id,), (admin_id,)),
                )
                connection.commit()

            source_service = CommunityService(source_path)
            source_service.initialize()
            post, _ = source_service.create_private_draft(
                owner_id,
                CommunityDraftInput(
                    client_snapshot_id="recovery-snapshot",
                    journey_id="recovery-journey",
                    journey_edition_id="recovery-edition",
                    source_edition_version=1,
                    title="恢复演练研学笔记",
                    content="用于验证社区备份恢复，不输出到校验日志。",
                    ai_assisted=False,
                    redacted_coordinate_count=0,
                    privacy_reviewed=True,
                    rights_confirmed=True,
                ),
            )
            post_id = post["id"]
            source_service.publish(owner_id, post_id)
            source_service.review_post(
                post_id,
                decision="approved",
                reason="",
                reviewed_by=admin_id,
            )
            source_service.toggle_like(reader_id, post_id)
            source_service.toggle_bookmark(reader_id, post_id)
            comment = source_service.create_comment(reader_id, post_id, "恢复后仍应可见")

            with closing(sqlite3.connect(source_path)) as source, closing(
                sqlite3.connect(backup_path)
            ) as target:
                source.backup(target)

            result = verify_community_database(backup_path)
            self.assertEqual(result["status"], "ok")
            self.assertEqual(result["table_counts"]["community_posts"], 1)
            self.assertEqual(result["table_counts"]["community_moderation"], 1)
            self.assertEqual(result["table_counts"]["community_comments"], 1)
            self.assertEqual(result["table_counts"]["community_post_bookmarks"], 1)

            restored_service = CommunityService(backup_path)
            restored_service.initialize()
            public_items = restored_service.list_public_posts()["items"]
            self.assertEqual([item["id"] for item in public_items], [post_id])
            self.assertEqual(
                restored_service.get_interactions(reader_id, post_id)["liked"],
                True,
            )
            self.assertEqual(
                restored_service.list_bookmarks(reader_id)["items"][0]["id"],
                post_id,
            )
            restored_comments = restored_service.list_comments(
                post_id,
                viewer_user_id=reader_id,
            )["items"]
            self.assertEqual(restored_comments[0]["id"], comment["id"])
            self.assertTrue(restored_comments[0]["can_delete"])


if __name__ == "__main__":
    unittest.main()
