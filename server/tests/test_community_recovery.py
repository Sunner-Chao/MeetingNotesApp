from __future__ import annotations

import sqlite3
import sys
import tempfile
import unittest
import hashlib
import json
from contextlib import closing
from pathlib import Path


SERVER_DIR = Path(__file__).resolve().parents[1]
BACKEND_DIR = SERVER_DIR / "backend-service"
SCRIPTS_DIR = SERVER_DIR / "scripts"
sys.path.insert(0, str(BACKEND_DIR))
sys.path.insert(0, str(SCRIPTS_DIR))

from community_service import CommunityDraftInput, CommunityMediaManifestInput, CommunityService
from verify_community_backup import (
    CommunityBackupVerificationError,
    build_community_media_manifest,
    verify_community_database,
)


class CommunityRecoveryTests(unittest.TestCase):
    def test_native_backup_and_restore_scripts_include_media_manifest_contract(self) -> None:
        backup_script = (SERVER_DIR / "scripts" / "backup-native.sh").read_text(encoding="utf-8")
        restore_script = (SERVER_DIR / "scripts" / "restore-native.sh").read_text(encoding="utf-8")
        self.assertIn('backend/community-media', backup_script)
        self.assertIn('backend/account-media', backup_script)
        self.assertIn('backend/accounts.db', backup_script)
        self.assertIn('--write-manifest "$MANIFEST_PATH"', backup_script)
        self.assertIn('community-media-manifest.json', backup_script)
        self.assertIn('--manifest "$MEDIA_MANIFEST"', restore_script)
        self.assertIn('Community media backup is incomplete', restore_script)

        install_script = (SERVER_DIR / "scripts" / "install-native.sh").read_text(encoding="utf-8")
        self.assertIn('STATE_ROOT/backend/account-media', install_script)
        self.assertIn('"$CURRENT_ACCOUNT_MEDIA_DIR" != "$STATE_ROOT/"*', install_script)
        self.assertIn('set_env_value ACCOUNT_MEDIA_DIR "$STATE_ROOT/backend/account-media"', install_script)

    @staticmethod
    def _png_bytes(note: str) -> bytes:
        return (
            b"\x89PNG\r\n\x1a\n"
            + b"\x00\x00\x00\x00IEND\xaeB`\x82"
        )

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

    def test_media_manifest_detects_tampering_and_orphan_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "media.db"
            media_root = root / "community-media"
            owner_id = "media-recovery-owner"
            admin_id = "media-recovery-admin"
            with closing(sqlite3.connect(db_path)) as connection:
                connection.execute("CREATE TABLE users (id TEXT PRIMARY KEY)")
                connection.executemany(
                    "INSERT INTO users(id) VALUES (?)",
                    ((owner_id,), (admin_id,)),
                )
                connection.commit()
            service = CommunityService(db_path, media_root=media_root)
            service.initialize()
            post, _ = service.create_private_draft(
                owner_id,
                CommunityDraftInput(
                    client_snapshot_id="media-recovery-snapshot",
                    journey_id="media-recovery-journey",
                    journey_edition_id="media-recovery-edition",
                    source_edition_version=1,
                    title="媒体恢复演练",
                    content="媒体文件恢复校验。",
                    ai_assisted=False,
                    redacted_coordinate_count=0,
                    privacy_reviewed=True,
                    rights_confirmed=True,
                ),
            )
            service.publish(owner_id, post["id"])
            original = self._png_bytes("original")
            thumbnail = self._png_bytes("thumbnail")
            media, _ = service.create_media_manifest(
                owner_id,
                post["id"],
                CommunityMediaManifestInput(
                    client_media_id="media-recovery-01",
                    display_name="恢复图.png",
                    mime_type="image/png",
                    original_bytes=len(original),
                    original_sha256=hashlib.sha256(original).hexdigest(),
                    thumbnail_bytes=len(thumbnail),
                    thumbnail_sha256=hashlib.sha256(thumbnail).hexdigest(),
                ),
            )
            for variant, data in (("original", original), ("thumbnail", thumbnail)):
                service.append_media_chunk(
                    owner_id,
                    post["id"],
                    media["id"],
                    variant,
                    start=0,
                    end=len(data) - 1,
                    total=len(data),
                    data=data,
                    chunk_sha256=hashlib.sha256(data).hexdigest(),
                )
            manifest_path = root / "community-media-manifest.json"
            manifest_path.write_text(
                json.dumps(build_community_media_manifest(db_path, media_root), ensure_ascii=False),
                encoding="utf-8",
            )
            self.assertEqual(
                verify_community_database(
                    db_path,
                    media_root=media_root,
                    manifest_path=manifest_path,
                )["media"]["file_count"],
                2,
            )
            media_file = next(media_root.rglob("*.png"))
            media_file.write_bytes(media_file.read_bytes() + b"tamper")
            with self.assertRaises(CommunityBackupVerificationError):
                verify_community_database(db_path, media_root=media_root, manifest_path=manifest_path)
            media_file.write_bytes(media_file.read_bytes()[:-6])
            (media_root / "orphan.png").write_bytes(b"orphan")
            with self.assertRaises(CommunityBackupVerificationError):
                verify_community_database(db_path, media_root=media_root)


if __name__ == "__main__":
    unittest.main()
