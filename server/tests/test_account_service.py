from __future__ import annotations

import base64
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

from account_service import AccountAuthError, AccountConflictError, AccountError, AccountService
from agent_gateway import AgentAuthError, AgentGateway
from common.account_stt_token import verify_account_stt_token


class AccountServiceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.db_path = root / "accounts.db"
        self.plans_path = root / "plans.json"
        self.plans_path.write_text(
            json.dumps(
                [
                    {
                        "code": "vip_test",
                        "name": "VIP Test",
                        "description": "test plan",
                        "price_cents": 100,
                        "quota_amount": 300,
                        "construction_logs_unlocked": True,
                        "active": True,
                    }
                ]
            ),
            encoding="utf-8",
        )
        self.gateway = AgentGateway(
            db_path=self.db_path,
            work_root=root / "tasks",
            bootstrap_token="bootstrap-test-token",
        )
        self.gateway.initialize()
        self.service = AccountService(
            self.db_path,
            token_secret="account-test-secret",
            plans_path=self.plans_path,
            admin_username="admin",
            admin_password="admin-test-password",
            admin_request_limit=10_000,
        )
        self.service.initialize()

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_admin_is_bootstrapped_with_full_vip_access(self) -> None:
        session = self.service.login("admin", "admin-test-password")
        principal = self.service.authenticate(f"Bearer {session['access_token']}")
        profile = self.service.profile(principal)

        self.assertTrue(profile["is_admin"])
        self.assertTrue(profile["vip_enabled"])
        self.assertTrue(profile["construction_logs_unlocked"])
        self.assertEqual(profile["quota"]["request_limit"], 10_000)
        agent = self.gateway.authenticate(f"Bearer {session['agent_access_token']}")
        self.assertEqual(agent.request_limit, 10_000)

    def test_registration_login_and_logout_use_database_sessions(self) -> None:
        session = self.service.register("normal_user", "strong-password")
        principal = self.service.authenticate(f"Bearer {session['access_token']}")
        self.assertEqual(principal.username, "normal_user")
        profile = self.service.profile(principal)
        self.assertFalse(profile["vip_enabled"])
        self.assertEqual(profile["plan_code"], "free")
        self.assertEqual(profile["plan_name"], "Free")
        self.assertEqual(profile["quota"]["request_limit"], 10)
        self.assertEqual(
            verify_account_stt_token(
                "account-test-secret",
                session["stt_access_token"],
            ),
            principal.user_id,
        )
        agent = self.gateway.authenticate(f"Bearer {session['agent_access_token']}")
        self.assertEqual(agent.request_limit, 10)

        refreshed = self.service.session_credentials(principal)
        self.assertEqual(refreshed["user"]["plan_name"], "Free")
        self.assertEqual(refreshed["agent_access_token"], session["agent_access_token"])
        self.assertEqual(
            verify_account_stt_token(
                "account-test-secret",
                refreshed["stt_access_token"],
            ),
            principal.user_id,
        )

        with self.assertRaises(AccountConflictError):
            self.service.register("NORMAL_USER", "another-password")
        with self.assertRaises(AccountAuthError):
            self.service.login("normal_user", "wrong-password")

        self.service.logout(principal)
        with self.assertRaises(AccountAuthError):
            self.service.authenticate(f"Bearer {session['access_token']}")
        with self.assertRaises(AgentAuthError):
            self.gateway.authenticate(f"Bearer {session['agent_access_token']}")

    def test_profile_can_update_display_name_and_avatar(self) -> None:
        session = self.service.register("profile_user", "strong-password")
        principal = self.service.authenticate(f"Bearer {session['access_token']}")
        avatar = "data:image/png;base64," + base64.b64encode(
            b"\x89PNG\r\n\x1a\nprofile"
        ).decode("ascii")

        updated = self.service.update_profile(principal, "  会议主持人  ", avatar)

        self.assertEqual(updated["display_name"], "会议主持人")
        self.assertEqual(updated["avatar_data_url"], avatar)
        refreshed = self.service.session_credentials(principal)
        self.assertEqual(refreshed["user"]["display_name"], "会议主持人")

        cleared = self.service.update_profile(principal, "", None)
        self.assertEqual(cleared["display_name"], "")
        self.assertIsNone(cleared["avatar_data_url"])
        with self.assertRaises(AccountError):
            self.service.update_profile(
                principal,
                "valid",
                "data:image/png;base64,not-valid-base64!",
            )

    def test_existing_meeting_table_is_migrated_for_inspection_template(self) -> None:
        # Reuse the test database so TemporaryDirectory does not race an extra
        # SQLite handle during cleanup on Windows.
        with self.service._connect() as conn:
            conn.execute("DROP TABLE account_meetings")
            conn.execute(
                """
                CREATE TABLE account_meetings (
                    user_id TEXT NOT NULL,
                    id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    template_key TEXT NOT NULL CHECK(template_key IN ('project', 'administrative', 'brainstorming')),
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    duration_seconds INTEGER NOT NULL DEFAULT 0,
                    transcript TEXT NOT NULL DEFAULT '',
                    report TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(user_id, id)
                )
                """
            )

        self.service.initialize()
        session = self.service.register("visit_user", "strong-password")
        principal = self.service.authenticate(f"Bearer {session['access_token']}")
        saved = self.service.upsert_meeting(
            principal,
            "inspection-01",
            title="园区参观考察",
            template_key="inspection",
            created_at=1,
            updated_at=2,
            duration_seconds=0,
            transcript="现场交流记录",
            report="",
        )

        self.assertEqual(saved["template_key"], "inspection")
        with self.service._connect() as conn:
            definition = conn.execute(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'account_meetings'"
            ).fetchone()[0]
        self.assertIn("'inspection'", definition)

    def test_approved_recharge_adds_quota_and_unlocks_templates_once(self) -> None:
        user_session = self.service.register("paid_user", "strong-password")
        user = self.service.authenticate(f"Bearer {user_session['access_token']}")
        order = self.service.create_order(user, "vip_test")
        self.assertEqual(order["status"], "pending")

        admin_session = self.service.login("admin", "admin-test-password")
        admin = self.service.authenticate(f"Bearer {admin_session['access_token']}")
        approved = self.service.approve_order(admin, order["id"])
        self.assertEqual(approved["status"], "approved")

        profile = self.service.profile(user)
        self.assertTrue(profile["vip_enabled"])
        self.assertTrue(profile["construction_logs_unlocked"])
        self.assertEqual(profile["plan_code"], "vip_test")
        self.assertEqual(profile["plan_name"], "VIP Test")
        self.assertEqual(profile["quota"]["requests_remaining"], 310)
        agent = self.gateway.authenticate(f"Bearer {user_session['agent_access_token']}")
        self.assertEqual(agent.request_limit, 310)

        with self.assertRaises(AccountConflictError):
            self.service.approve_order(admin, order["id"])

        disabled = self.service.set_user_enabled(admin, user.user_id, False)
        self.assertFalse(disabled["enabled"])
        with self.assertRaises(AccountAuthError):
            self.service.authenticate(f"Bearer {user_session['access_token']}")
        with self.assertRaises(AgentAuthError):
            self.gateway.authenticate(f"Bearer {user_session['agent_access_token']}")

    def test_initialization_backfills_free_quota_for_legacy_users_once(self) -> None:
        session = self.service.register("legacy_user", "strong-password")
        principal = self.service.authenticate(f"Bearer {session['access_token']}")
        with self.service._connect() as conn:
            conn.execute(
                "UPDATE user_entitlements SET quota_granted = 0 WHERE user_id = ?",
                (principal.user_id,),
            )
            conn.execute(
                "UPDATE agent_tokens SET request_limit = 0 WHERE id = ?",
                (f"user:{principal.user_id}",),
            )

        self.service.initialize()
        self.service.initialize()

        profile = self.service.profile(principal)
        self.assertEqual(profile["quota"]["request_limit"], 10)
        self.assertEqual(profile["quota"]["requests_remaining"], 10)

    def test_admin_can_delete_user_and_all_account_dependencies(self) -> None:
        user_session = self.service.register("delete_user", "strong-password")
        user = self.service.authenticate(f"Bearer {user_session['access_token']}")
        order = self.service.create_order(user, "vip_test")
        token_id = f"user:{user.user_id}"
        with self.service._connect() as conn:
            conn.execute(
                """
                INSERT INTO agent_tasks (
                    id, token_id, provider, operation, status,
                    attachment_count, created_at
                ) VALUES ('delete-task', ?, 'codex-cli', 'report', 'succeeded', 0, 1)
                """,
                (token_id,),
            )

        admin_session = self.service.login("admin", "admin-test-password")
        admin = self.service.authenticate(f"Bearer {admin_session['access_token']}")
        deleted = self.service.delete_user(admin, user.user_id)
        self.assertEqual(deleted["status"], "deleted")
        self.assertEqual(deleted["username"], "delete_user")

        with self.assertRaises(AccountAuthError):
            self.service.authenticate(f"Bearer {user_session['access_token']}")
        with self.assertRaises(AgentAuthError):
            self.gateway.authenticate(f"Bearer {user_session['agent_access_token']}")
        with self.service._connect() as conn:
            self.assertIsNone(conn.execute("SELECT id FROM users WHERE id = ?", (user.user_id,)).fetchone())
            self.assertIsNone(conn.execute("SELECT id FROM recharge_orders WHERE id = ?", (order["id"],)).fetchone())
            self.assertIsNone(conn.execute("SELECT id FROM agent_tokens WHERE id = ?", (token_id,)).fetchone())
            self.assertIsNone(conn.execute("SELECT id FROM agent_tasks WHERE id = 'delete-task'").fetchone())

        with self.assertRaises(AccountConflictError):
            self.service.delete_user(admin, admin.user_id)


if __name__ == "__main__":
    unittest.main()
