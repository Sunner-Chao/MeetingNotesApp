from __future__ import annotations

import hashlib
import os
import sqlite3
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from unittest.mock import patch


SERVER_DIR = Path(__file__).resolve().parents[1]
BACKEND_DIR = SERVER_DIR / "backend-service"
SCRIPTS_DIR = SERVER_DIR / "scripts"
sys.path.insert(0, str(BACKEND_DIR))
sys.path.insert(0, str(SCRIPTS_DIR))

from community_service import CommunityDraftInput, CommunityMediaManifestInput, CommunityService
from cleanup_community_media import cleanup_community_media
from verify_community_backup import verify_community_database


class CommunityMediaCleanupTests(unittest.TestCase):
    @staticmethod
    def _png_bytes() -> bytes:
        return b"\x89PNG\r\n\x1a\n\x00\x00\x00\x00IEND\xaeB`\x82"

    def test_dry_run_and_apply_quarantine_stale_partial_withdrawn_and_orphan_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "cleanup.db"
            media_root = root / "community-media"
            quarantine_root = root / "community-media-quarantine"
            owner_id = "cleanup-owner"
            reviewer_id = "cleanup-reviewer"
            with closing(sqlite3.connect(db_path)) as connection:
                connection.execute("CREATE TABLE users (id TEXT PRIMARY KEY)")
                connection.executemany(
                    "INSERT INTO users(id) VALUES (?)",
                    ((owner_id,), (reviewer_id,)),
                )
                connection.commit()
            service = CommunityService(db_path, media_root=media_root)
            service.initialize()
            data = self._png_bytes()

            partial_post, _ = service.create_private_draft(
                owner_id,
                CommunityDraftInput(
                    client_snapshot_id="cleanup-partial",
                    journey_id="cleanup-journey-1",
                    journey_edition_id="cleanup-edition-1",
                    source_edition_version=1,
                    title="过期分片",
                    content="清理测试",
                    ai_assisted=False,
                    redacted_coordinate_count=0,
                    privacy_reviewed=True,
                    rights_confirmed=True,
                ),
            )
            service.publish(owner_id, partial_post["id"])
            partial_media, _ = service.create_media_manifest(
                owner_id,
                partial_post["id"],
                CommunityMediaManifestInput(
                    client_media_id="cleanup-partial-media",
                    display_name="partial.png",
                    mime_type="image/png",
                    original_bytes=len(data),
                    original_sha256=hashlib.sha256(data).hexdigest(),
                    thumbnail_bytes=len(data),
                    thumbnail_sha256=hashlib.sha256(data).hexdigest(),
                ),
            )
            split = max(1, len(data) // 2)
            service.append_media_chunk(
                owner_id,
                partial_post["id"],
                partial_media["id"],
                "original",
                start=0,
                end=split - 1,
                total=len(data),
                data=data[:split],
                chunk_sha256=hashlib.sha256(data[:split]).hexdigest(),
            )
            with closing(sqlite3.connect(db_path)) as connection:
                connection.execute(
                    "UPDATE community_post_media SET updated_at = 0 WHERE id = ?",
                    (partial_media["id"],),
                )
                connection.commit()

            withdrawn_post, _ = service.create_private_draft(
                owner_id,
                CommunityDraftInput(
                    client_snapshot_id="cleanup-withdrawn",
                    journey_id="cleanup-journey-2",
                    journey_edition_id="cleanup-edition-2",
                    source_edition_version=1,
                    title="过期撤回媒体",
                    content="清理测试",
                    ai_assisted=False,
                    redacted_coordinate_count=0,
                    privacy_reviewed=True,
                    rights_confirmed=True,
                ),
            )
            service.publish(owner_id, withdrawn_post["id"])
            withdrawn_media, _ = service.create_media_manifest(
                owner_id,
                withdrawn_post["id"],
                CommunityMediaManifestInput(
                    client_media_id="cleanup-withdrawn-media",
                    display_name="withdrawn.png",
                    mime_type="image/png",
                    original_bytes=len(data),
                    original_sha256=hashlib.sha256(data).hexdigest(),
                    thumbnail_bytes=len(data),
                    thumbnail_sha256=hashlib.sha256(data).hexdigest(),
                ),
            )
            for variant in ("original", "thumbnail"):
                service.append_media_chunk(
                    owner_id,
                    withdrawn_post["id"],
                    withdrawn_media["id"],
                    variant,
                    start=0,
                    end=len(data) - 1,
                    total=len(data),
                    data=data,
                    chunk_sha256=hashlib.sha256(data).hexdigest(),
                )
            service.withdraw(owner_id, withdrawn_post["id"])
            with closing(sqlite3.connect(db_path)) as connection:
                connection.execute(
                    "UPDATE community_posts SET withdrawn_at = 1 WHERE id = ?",
                    (withdrawn_post["id"],),
                )
                connection.commit()

            orphan = media_root / "orphan.bin"
            orphan.write_bytes(b"orphan")
            before = cleanup_community_media(
                db_path,
                media_root,
                quarantine_root,
                now_ms=31 * DAY_MS,
            )
            self.assertTrue(before["dry_run"])
            self.assertEqual(before["stale_partial_variant_count"], 1)
            self.assertEqual(before["withdrawn_media_count"], 1)
            self.assertEqual(before["orphan_file_count"], 1)
            self.assertTrue(orphan.is_file())

            with patch.dict(os.environ, {"COMMUNITY_WRITE_ENABLED": "false"}):
                applied = cleanup_community_media(
                    db_path,
                    media_root,
                    quarantine_root,
                    now_ms=31 * DAY_MS,
                    apply=True,
                )
            self.assertFalse(applied["dry_run"])
            self.assertEqual(applied["quarantined_file_count"], 4)
            self.assertEqual(list(media_root.rglob("*.png")), [])
            self.assertFalse(orphan.exists())

            with closing(sqlite3.connect(db_path)) as connection:
                partial_state = connection.execute(
                    "SELECT original_received_bytes, status FROM community_post_media WHERE id = ?",
                    (partial_media["id"],),
                ).fetchone()
                withdrawn_state = connection.execute(
                    "SELECT 1 FROM community_post_media WHERE id = ?",
                    (withdrawn_media["id"],),
                ).fetchone()
            self.assertEqual(tuple(partial_state), (0, "pending"))
            self.assertIsNone(withdrawn_state)
            self.assertGreaterEqual(len(list(quarantine_root.rglob("*"))), 4)
            self.assertEqual(
                verify_community_database(db_path, media_root=media_root)["media"]["file_count"],
                0,
            )

    def test_apply_failure_restores_moved_files_and_database_state(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "cleanup.db"
            media_root = root / "community-media"
            quarantine_root = root / "community-media-quarantine"
            owner_id = "cleanup-rollback-owner"
            with closing(sqlite3.connect(db_path)) as connection:
                connection.execute("CREATE TABLE users (id TEXT PRIMARY KEY)")
                connection.execute("INSERT INTO users(id) VALUES (?)", (owner_id,))
                connection.commit()
            service = CommunityService(db_path, media_root=media_root)
            service.initialize()
            data = self._png_bytes()
            post, _ = service.create_private_draft(
                owner_id,
                CommunityDraftInput(
                    client_snapshot_id="cleanup-rollback",
                    journey_id="cleanup-rollback-journey",
                    journey_edition_id="cleanup-rollback-edition",
                    source_edition_version=1,
                    title="回滚测试",
                    content="清理测试",
                    ai_assisted=False,
                    redacted_coordinate_count=0,
                    privacy_reviewed=True,
                    rights_confirmed=True,
                ),
            )
            media, _ = service.create_media_manifest(
                owner_id,
                post["id"],
                CommunityMediaManifestInput(
                    client_media_id="cleanup-rollback-media",
                    display_name="rollback.png",
                    mime_type="image/png",
                    original_bytes=len(data),
                    original_sha256=hashlib.sha256(data).hexdigest(),
                    thumbnail_bytes=len(data),
                    thumbnail_sha256=hashlib.sha256(data).hexdigest(),
                ),
            )
            service.append_media_chunk(
                owner_id,
                post["id"],
                media["id"],
                "original",
                start=0,
                end=max(1, len(data) // 2) - 1,
                total=len(data),
                data=data[: max(1, len(data) // 2)],
                chunk_sha256=hashlib.sha256(data[: max(1, len(data) // 2)]).hexdigest(),
            )
            partial_path = next(media_root.rglob("*.part"))
            with closing(sqlite3.connect(db_path)) as connection:
                connection.execute(
                    "UPDATE community_post_media SET updated_at = 0 WHERE id = ?",
                    (media["id"],),
                )
                connection.execute(
                    """
                    CREATE TRIGGER reject_cleanup_reset
                    BEFORE UPDATE OF original_received_bytes ON community_post_media
                    BEGIN
                        SELECT RAISE(ABORT, 'test reset rejection');
                    END
                    """
                )
                connection.commit()

            with patch.dict(os.environ, {"COMMUNITY_WRITE_ENABLED": "false"}):
                with self.assertRaises(sqlite3.IntegrityError):
                    cleanup_community_media(
                        db_path,
                        media_root,
                        quarantine_root,
                        now_ms=31 * DAY_MS,
                        apply=True,
                    )

            self.assertTrue(partial_path.is_file())
            self.assertEqual(list(quarantine_root.rglob("*")), [])
            with closing(sqlite3.connect(db_path)) as connection:
                state = connection.execute(
                    "SELECT original_received_bytes FROM community_post_media WHERE id = ?",
                    (media["id"],),
                ).fetchone()
            self.assertEqual(state[0], len(data) // 2)


DAY_MS = 24 * 60 * 60 * 1000


if __name__ == "__main__":
    unittest.main()
