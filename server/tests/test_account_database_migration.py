from __future__ import annotations

import base64
import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

from account_service import AccountService
from agent_gateway import AgentGateway
from community_service import CommunityDraftInput, CommunityMediaManifestInput, CommunityService
import web_backend as backend
from web_backend import migrate_legacy_account_database, migrate_legacy_community_database


class AccountDatabaseMigrationTests(unittest.TestCase):
    def test_copies_legacy_accounts_and_agent_rows_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "meeting_notes.db"
            target = root / "accounts.db"
            plans = root / "plans.json"
            plans.write_text(
                json.dumps(
                    [{
                        "code": "free",
                        "name": "免费账户",
                        "price_cents": 0,
                        "quota_amount": 1000,
                        "included_minutes": 120,
                        "ai_credits": 5,
                        "team_seats": 1,
                        "duration_days": 30,
                    }]
                ),
                encoding="utf-8",
            )
            source_gateway = AgentGateway(
                source,
                root / "source-tasks",
                bootstrap_token="legacy-agent-token",
            )
            source_gateway.initialize()
            source_service = AccountService(
                source,
                token_secret="migration-secret",
                plans_path=plans,
                admin_password="admin-password",
                expose_auth_code=True,
            )
            source_service.initialize()
            registered = source_service.register("legacy_user", "strong-password")
            source_principal = source_service.authenticate(
                f"Bearer {registered['access_token']}"
            )
            source_gateway.issue_token(
                "legacy-issued",
                25,
                {"codex-cli"},
                None,
            )

            target_gateway = AgentGateway(
                target,
                root / "target-tasks",
                bootstrap_token="new-agent-token",
            )
            target_gateway.initialize()
            target_service = AccountService(
                target,
                token_secret="migration-secret",
                plans_path=plans,
                admin_password="admin-password",
                expose_auth_code=True,
            )
            target_service.initialize(bootstrap_admin=False, seed_plans=False)

            first = migrate_legacy_account_database(source, target)
            self.assertTrue(first["migrated"])
            self.assertIn("users", first["copied_tables"])

            target_service.initialize()
            login = target_service.login("legacy_user", "strong-password")
            self.assertEqual(login["user"]["username"], "legacy_user")
            self.assertEqual(
                target_service.profile(
                    target_service.authenticate(f"Bearer {login['access_token']}")
                )["username"],
                "legacy_user",
            )
            second = migrate_legacy_account_database(source, target)
            self.assertFalse(second["migrated"])
            self.assertEqual(second["reason"], "already_migrated")

            # A partially initialized target must fail closed instead of losing legacy rows.
            other_target = root / "accounts-with-user.db"
            other_gateway = AgentGateway(
                other_target,
                root / "other-tasks",
                bootstrap_token="other-agent-token",
            )
            other_gateway.initialize()
            other_service = AccountService(
                other_target,
                token_secret="migration-secret",
                plans_path=plans,
                expose_auth_code=True,
            )
            other_service.initialize(bootstrap_admin=False, seed_plans=False)
            other_service.register("new_user", "strong-password")
            with self.assertRaises(RuntimeError):
                migrate_legacy_account_database(source, other_target)

            # Non-user account rows also indicate an interrupted or foreign target.
            partial_target = root / "accounts-with-plan.db"
            partial_gateway = AgentGateway(
                partial_target,
                root / "partial-tasks",
                bootstrap_token="partial-agent-token",
            )
            partial_gateway.initialize()
            partial_service = AccountService(
                partial_target,
                token_secret="migration-secret",
                plans_path=plans,
                expose_auth_code=True,
            )
            partial_service.initialize(bootstrap_admin=False)
            with self.assertRaises(RuntimeError):
                migrate_legacy_account_database(source, partial_target)

    def test_new_account_community_draft_uses_the_isolated_account_database(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            meeting_db = root / "meeting_notes.db"
            accounts_db = root / "accounts.db"
            plans = root / "plans.json"
            plans.write_text("[]", encoding="utf-8")
            account_service = AccountService(
                accounts_db,
                token_secret="community-route-secret",
                plans_path=plans,
                expose_auth_code=True,
            )
            gateway = AgentGateway(
                accounts_db,
                root / "tasks",
                bootstrap_token="community-agent-token",
            )
            gateway.initialize()
            previous = (
                backend.DB_PATH,
                backend.ACCOUNT_SERVICE,
                backend.AGENT_GATEWAY,
                backend.COMMUNITY_DB_PATH,
            )
            backend.DB_PATH = meeting_db
            backend.ACCOUNT_SERVICE = account_service
            backend.AGENT_GATEWAY = gateway
            backend.COMMUNITY_DB_PATH = accounts_db
            try:
                with TestClient(backend.app) as client:
                    requested = client.post(
                        "/api/auth/code/request",
                        json={
                            "channel": "email",
                            "identifier": "community-user@example.com",
                            "purpose": "register",
                        },
                    )
                    self.assertEqual(requested.status_code, 200, requested.text)
                    registered = client.post(
                        "/api/auth/register/verify",
                        json={
                            "channel": "email",
                            "identifier": "community-user@example.com",
                            "code": requested.json()["verification_code"],
                            "username": "community_user",
                            "password": "strong-password",
                        },
                    )
                    self.assertEqual(registered.status_code, 200, registered.text)
                    created = client.post(
                        "/api/account/community/drafts",
                        headers={"Authorization": f"Bearer {registered.json()['access_token']}"},
                        json={
                            "client_snapshot_id": "account-community-01",
                            "journey_id": "journey-01",
                            "journey_edition_id": "edition-01",
                            "source_edition_version": 1,
                            "title": "账户库社区草稿",
                            "content": "验证新注册账户的社区内容写入位置。",
                            "ai_assisted": False,
                            "redacted_coordinate_count": 0,
                            "privacy_reviewed": True,
                            "rights_confirmed": True,
                        },
                    )
                    self.assertEqual(created.status_code, 201, created.text)

                with closing(sqlite3.connect(accounts_db)) as conn:
                    post_count = conn.execute(
                        "SELECT COUNT(*) FROM community_posts"
                    ).fetchone()[0]
                    user_count = conn.execute(
                        "SELECT COUNT(*) FROM users WHERE username_normalized = ?",
                        ("community_user",),
                    ).fetchone()[0]
                self.assertEqual(post_count, 1)
                self.assertEqual(user_count, 1)
                with closing(sqlite3.connect(meeting_db)) as conn:
                    legacy_community_table = conn.execute(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'community_posts'"
                    ).fetchone()
                self.assertIsNone(legacy_community_table)
            finally:
                (
                    backend.DB_PATH,
                    backend.ACCOUNT_SERVICE,
                    backend.AGENT_GATEWAY,
                    backend.COMMUNITY_DB_PATH,
                ) = previous
                gateway._executor.shutdown(wait=True, cancel_futures=True)

    def test_legacy_community_rows_and_media_migrate_with_hash_verification(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "meeting_notes.db"
            target = root / "accounts.db"
            source_media = root / "legacy-community-media"
            target_media = root / "account-community-media"
            plans = root / "plans.json"
            plans.write_text("[]", encoding="utf-8")

            target_service = AccountService(
                target,
                token_secret="migration-community-secret",
                plans_path=plans,
                expose_auth_code=True,
            )
            target_gateway = AgentGateway(
                target,
                root / "target-tasks",
                bootstrap_token="migration-agent-token",
            )
            target_gateway.initialize()
            target_service.initialize(bootstrap_admin=False)
            session = target_service.register("legacy_community_user", "strong-password")
            user_id = target_service.authenticate(
                f"Bearer {session['access_token']}"
            ).user_id

            with closing(sqlite3.connect(source)) as conn:
                conn.execute("CREATE TABLE users (id TEXT PRIMARY KEY)")
                conn.execute("INSERT INTO users(id) VALUES (?)", (user_id,))
                conn.commit()
            source_service = CommunityService(source, media_root=source_media)
            source_service.initialize()
            post, created = source_service.create_private_draft(
                user_id,
                CommunityDraftInput(
                    client_snapshot_id="legacy-community-snapshot",
                    journey_id="legacy-journey",
                    journey_edition_id="legacy-edition",
                    source_edition_version=1,
                    title="迁移前社区帖子",
                    content="旧业务库中的社区内容。",
                    ai_assisted=False,
                    redacted_coordinate_count=0,
                    privacy_reviewed=True,
                    rights_confirmed=True,
                ),
            )
            self.assertTrue(created)

            png = base64.b64decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
            )
            digest = hashlib.sha256(png).hexdigest()
            manifest, _ = source_service.create_media_manifest(
                user_id,
                post["id"],
                CommunityMediaManifestInput(
                    client_media_id="legacy-cover",
                    display_name="legacy.png",
                    mime_type="image/png",
                    original_bytes=len(png),
                    original_sha256=digest,
                    thumbnail_bytes=len(png),
                    thumbnail_sha256=digest,
                ),
            )
            for variant in ("original", "thumbnail"):
                source_service.append_media_chunk(
                    user_id,
                    post["id"],
                    manifest["id"],
                    variant,
                    start=0,
                    end=len(png) - 1,
                    total=len(png),
                    data=png,
                    chunk_sha256=digest,
                )

            first = migrate_legacy_community_database(
                source,
                target,
                source_media_root=source_media,
                target_media_root=target_media,
            )
            self.assertTrue(first["migrated"])
            with closing(sqlite3.connect(target)) as conn:
                self.assertEqual(
                    conn.execute("SELECT COUNT(*) FROM community_posts").fetchone()[0],
                    1,
                )
                media_row = conn.execute(
                    "SELECT original_storage_key FROM community_post_media WHERE id = ?",
                    (manifest["id"],),
                ).fetchone()
            self.assertIsNotNone(media_row)
            copied_media = target_media / media_row[0]
            self.assertTrue(copied_media.is_file())
            self.assertEqual(hashlib.sha256(copied_media.read_bytes()).hexdigest(), digest)

            second = migrate_legacy_community_database(
                source,
                target,
                source_media_root=source_media,
                target_media_root=target_media,
            )
            self.assertFalse(second["migrated"])
            self.assertEqual(second["reason"], "already_migrated")


if __name__ == "__main__":
    unittest.main()
