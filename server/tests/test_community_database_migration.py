from __future__ import annotations

import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from fastapi.testclient import TestClient


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

import web_backend as backend
from account_service import AccountService
from agent_gateway import AgentGateway
from community_service import CommunityDraftInput, CommunityService


class CommunityDatabaseMigrationTests(unittest.TestCase):
    @staticmethod
    def _create_users_database(path: Path, *user_ids: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(path)) as conn:
            conn.execute("PRAGMA foreign_keys = ON")
            conn.execute("CREATE TABLE users (id TEXT PRIMARY KEY)")
            conn.executemany(
                "INSERT INTO users(id) VALUES (?)",
                ((user_id,) for user_id in user_ids),
            )
            conn.commit()

    @staticmethod
    def _draft(snapshot_id: str = "legacy-snapshot") -> CommunityDraftInput:
        return CommunityDraftInput(
            client_snapshot_id=snapshot_id,
            journey_id="legacy-journey",
            journey_edition_id="legacy-edition",
            source_edition_version=1,
            title="既有社区记录",
            content="这条记录创建于账户数据库拆分之前。",
            ai_assisted=False,
            redacted_coordinate_count=0,
            privacy_reviewed=True,
            rights_confirmed=True,
            destination="北京",
            travel_date="2026-08-19",
            travel_days=1,
            stage_titles=["上午参访"],
            tags=["研学"],
            pois=["中国国家博物馆"],
        )

    @staticmethod
    def _insert_ready_media(
        db_path: Path,
        media_root: Path,
        *,
        post_id: str,
        user_id: str,
    ) -> tuple[str, dict[str, bytes]]:
        media_id = "legacy-media"
        files = {
            f"{post_id}/{media_id}/original.png": b"legacy-original-image",
            f"{post_id}/{media_id}/thumbnail.png": b"legacy-thumbnail-image",
        }
        for storage_key, data in files.items():
            path = media_root / storage_key
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        now = 1_756_000_000_000
        with closing(sqlite3.connect(db_path)) as conn:
            conn.execute(
                """
                INSERT INTO community_post_media (
                    id, post_id, user_id, client_media_id, display_name, mime_type,
                    original_total_bytes, original_sha256, original_received_bytes,
                    thumbnail_total_bytes, thumbnail_sha256, thumbnail_received_bytes,
                    status, original_storage_key, thumbnail_storage_key, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'image/png', ?, ?, ?, ?, ?, ?, 'ready', ?, ?, ?, ?)
                """,
                (
                    media_id,
                    post_id,
                    user_id,
                    "legacy-client-media",
                    "既有插图.png",
                    len(files[f"{post_id}/{media_id}/original.png"]),
                    hashlib.sha256(files[f"{post_id}/{media_id}/original.png"]).hexdigest(),
                    len(files[f"{post_id}/{media_id}/original.png"]),
                    len(files[f"{post_id}/{media_id}/thumbnail.png"]),
                    hashlib.sha256(files[f"{post_id}/{media_id}/thumbnail.png"]).hexdigest(),
                    len(files[f"{post_id}/{media_id}/thumbnail.png"]),
                    f"{post_id}/{media_id}/original.png",
                    f"{post_id}/{media_id}/thumbnail.png",
                    now,
                    now,
                ),
            )
            conn.commit()
        return media_id, files

    def test_migrates_legacy_rows_and_media_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_db = root / "legacy" / "meeting_notes.db"
            target_db = root / "account" / "accounts.db"
            source_media = source_db.parent / "community-media"
            target_media = target_db.parent / "community-media"
            user_id = "legacy-user"
            self._create_users_database(source_db, user_id)
            self._create_users_database(target_db, user_id)

            source_service = CommunityService(source_db, media_root=source_media)
            source_service.initialize()
            post, _ = source_service.create_private_draft(user_id, self._draft())
            source_service.publish(user_id, post["id"])
            media_id, files = self._insert_ready_media(
                source_db,
                source_media,
                post_id=post["id"],
                user_id=user_id,
            )

            first = backend.migrate_legacy_community_database(
                source_db,
                target_db,
                source_media_root=source_media,
                target_media_root=target_media,
            )
            self.assertTrue(first["migrated"])
            self.assertEqual(first["copied_tables"]["community_posts"], 1)
            self.assertEqual(first["copied_tables"]["community_post_media"], 1)
            self.assertEqual(first["media"]["copied_files"], 2)

            with closing(sqlite3.connect(target_db)) as conn:
                conn.row_factory = sqlite3.Row
                migrated_post = conn.execute(
                    "SELECT * FROM community_posts WHERE id = ?", (post["id"],)
                ).fetchone()
                migrated_media = conn.execute(
                    "SELECT * FROM community_post_media WHERE id = ?", (media_id,)
                ).fetchone()
                migration_count = conn.execute(
                    "SELECT COUNT(*) FROM community_migration_log"
                ).fetchone()[0]
                foreign_key_errors = conn.execute("PRAGMA foreign_key_check").fetchall()
            self.assertEqual(migrated_post["status"], "published")
            self.assertEqual(migrated_post["title"], "既有社区记录")
            self.assertEqual(migrated_media["status"], "ready")
            self.assertEqual(migration_count, 1)
            self.assertEqual(foreign_key_errors, [])
            for storage_key, expected in files.items():
                self.assertEqual((target_media / storage_key).read_bytes(), expected)

            late_source_file = source_media / "late" / "orphan.png"
            late_source_file.parent.mkdir(parents=True, exist_ok=True)
            late_source_file.write_bytes(b"must-not-copy-after-migration")
            second = backend.migrate_legacy_community_database(
                source_db,
                target_db,
                source_media_root=source_media,
                target_media_root=target_media,
            )
            self.assertFalse(second["migrated"])
            self.assertEqual(second["reason"], "already_migrated")
            self.assertFalse((target_media / "late" / "orphan.png").exists())

    def test_row_conflict_fails_without_copying_media_or_changing_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_db = root / "legacy" / "meeting_notes.db"
            target_db = root / "account" / "accounts.db"
            source_media = source_db.parent / "community-media"
            target_media = target_db.parent / "community-media"
            user_id = "shared-user"
            self._create_users_database(source_db, user_id)
            self._create_users_database(target_db, user_id)
            source_service = CommunityService(source_db, media_root=source_media)
            target_service = CommunityService(target_db, media_root=target_media)
            source_service.initialize()
            target_service.initialize()
            source_post, _ = source_service.create_private_draft(user_id, self._draft("same-key"))
            with closing(sqlite3.connect(source_db)) as source_conn, closing(
                sqlite3.connect(target_db)
            ) as target_conn:
                source_conn.row_factory = sqlite3.Row
                row = source_conn.execute(
                    "SELECT * FROM community_posts WHERE id = ?", (source_post["id"],)
                ).fetchone()
                columns = list(row.keys())
                values = [row[column] for column in columns]
                values[columns.index("title")] = "目标库中的不同标题"
                quoted = ", ".join(f"[{column}]" for column in columns)
                placeholders = ", ".join("?" for _ in columns)
                target_conn.execute(
                    f"INSERT INTO community_posts ({quoted}) VALUES ({placeholders})",
                    values,
                )
                target_conn.commit()
            incoming = source_media / "incoming" / "new.png"
            incoming.parent.mkdir(parents=True, exist_ok=True)
            incoming.write_bytes(b"new-media")

            with self.assertRaisesRegex(RuntimeError, "Community row conflict"):
                backend.migrate_legacy_community_database(
                    source_db,
                    target_db,
                    source_media_root=source_media,
                    target_media_root=target_media,
                )

            self.assertFalse((target_media / "incoming" / "new.png").exists())
            with closing(sqlite3.connect(target_db)) as conn:
                title = conn.execute(
                    "SELECT title FROM community_posts WHERE id = ?", (source_post["id"],)
                ).fetchone()[0]
                log_exists = conn.execute(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'community_migration_log'"
                ).fetchone()
                log_count = (
                    conn.execute("SELECT COUNT(*) FROM community_migration_log").fetchone()[0]
                    if log_exists
                    else 0
                )
            self.assertEqual(title, "目标库中的不同标题")
            self.assertEqual(log_count, 0)

    def test_media_conflict_is_preflighted_without_partial_files_or_database_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_db = root / "legacy" / "meeting_notes.db"
            target_db = root / "account" / "accounts.db"
            source_media = source_db.parent / "community-media"
            target_media = target_db.parent / "community-media"
            user_id = "media-user"
            self._create_users_database(source_db, user_id)
            self._create_users_database(target_db, user_id)
            source_service = CommunityService(source_db, media_root=source_media)
            source_service.initialize()
            post, _ = source_service.create_private_draft(user_id, self._draft("media-conflict"))
            (source_media / "a-new.png").parent.mkdir(parents=True, exist_ok=True)
            (source_media / "a-new.png").write_bytes(b"new-file")
            (source_media / "z-conflict.png").write_bytes(b"source-version")
            target_media.mkdir(parents=True, exist_ok=True)
            (target_media / "z-conflict.png").write_bytes(b"target-version")

            with self.assertRaisesRegex(RuntimeError, "Community media conflict"):
                backend.migrate_legacy_community_database(
                    source_db,
                    target_db,
                    source_media_root=source_media,
                    target_media_root=target_media,
                )

            self.assertFalse((target_media / "a-new.png").exists())
            self.assertEqual((target_media / "z-conflict.png").read_bytes(), b"target-version")
            with closing(sqlite3.connect(target_db)) as conn:
                migrated = conn.execute(
                    "SELECT COUNT(*) FROM community_posts WHERE id = ?", (post["id"],)
                ).fetchone()[0]
                log_count = conn.execute(
                    "SELECT COUNT(*) FROM community_migration_log"
                ).fetchone()[0]
            self.assertEqual(migrated, 0)
            self.assertEqual(log_count, 0)


class SplitDatabaseCommunityRouteTests(unittest.TestCase):
    def test_new_email_account_can_create_and_publish_community_post(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            business_db = root / "business" / "meeting_notes.db"
            account_db = root / "account" / "accounts.db"
            business_db.parent.mkdir(parents=True, exist_ok=True)
            plans_path = root / "plans.json"
            plans_path.write_text(json.dumps([]), encoding="utf-8")
            previous_db = backend.DB_PATH
            previous_gateway = backend.AGENT_GATEWAY
            previous_accounts = backend.ACCOUNT_SERVICE
            test_gateway = AgentGateway(
                account_db,
                root / "tasks",
                bootstrap_token="split-database-agent",
            )
            backend.DB_PATH = business_db
            backend.AGENT_GATEWAY = test_gateway
            backend.ACCOUNT_SERVICE = AccountService(
                account_db,
                token_secret="split-database-secret",
                plans_path=plans_path,
                expose_auth_code=True,
            )
            try:
                with TestClient(backend.app) as client:
                    requested = client.post(
                        "/api/auth/code/request",
                        json={
                            "channel": "email",
                            "identifier": "new-community-user@example.com",
                            "purpose": "register",
                        },
                    )
                    self.assertEqual(requested.status_code, 200, requested.text)
                    registered = client.post(
                        "/api/auth/register/verify",
                        json={
                            "channel": "email",
                            "identifier": "new-community-user@example.com",
                            "code": requested.json()["verification_code"],
                            "username": "new_community_user",
                            "password": "strong-password",
                        },
                    )
                    self.assertEqual(registered.status_code, 200, registered.text)
                    headers = {
                        "Authorization": f"Bearer {registered.json()['access_token']}"
                    }
                    created = client.post(
                        "/api/account/community/drafts",
                        headers=headers,
                        json={
                            "client_snapshot_id": "new-user-snapshot",
                            "journey_id": "new-user-journey",
                            "journey_edition_id": "new-user-edition",
                            "source_edition_version": 1,
                            "title": "注册后第一条社区记录",
                            "content": "邮箱注册完成后应当可以立即创建并发布。",
                            "ai_assisted": False,
                            "redacted_coordinate_count": 0,
                            "privacy_reviewed": True,
                            "rights_confirmed": True,
                        },
                    )
                    self.assertEqual(created.status_code, 201, created.text)
                    published = client.post(
                        f"/api/account/community/posts/{created.json()['id']}/publish",
                        headers=headers,
                    )
                    self.assertEqual(published.status_code, 200, published.text)
                    self.assertEqual(published.json()["status"], "published")

                with closing(sqlite3.connect(account_db)) as conn:
                    user_id = conn.execute(
                        "SELECT id FROM users WHERE username_normalized = ?",
                        ("new_community_user",),
                    ).fetchone()[0]
                    owner_id, status = conn.execute(
                        "SELECT user_id, status FROM community_posts WHERE client_snapshot_id = ?",
                        ("new-user-snapshot",),
                    ).fetchone()
                    foreign_key_errors = conn.execute("PRAGMA foreign_key_check").fetchall()
                self.assertEqual(owner_id, user_id)
                self.assertEqual(status, "published")
                self.assertEqual(foreign_key_errors, [])
                with closing(sqlite3.connect(business_db)) as conn:
                    community_table = conn.execute(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'community_posts'"
                    ).fetchone()
                self.assertIsNone(community_table)
            finally:
                backend.DB_PATH = previous_db
                backend.AGENT_GATEWAY = previous_gateway
                backend.ACCOUNT_SERVICE = previous_accounts
                test_gateway._executor.shutdown(wait=True, cancel_futures=True)


if __name__ == "__main__":
    unittest.main()
