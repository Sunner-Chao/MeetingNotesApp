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

from account_service import (
    AccountAuthError,
    AccountConflictError,
    AccountDeliveryUnavailableError,
    AccountError,
    AccountPermissionError,
    AccountService,
)
from agent_gateway import AgentAuthError, AgentGateway, AgentProviderError, AgentQuotaError
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
                        "included_minutes": 100,
                        "ai_credits": 20,
                        "team_seats": 1,
                        "duration_days": 30,
                        "construction_logs_unlocked": True,
                        "active": True,
                    }
                    ,
                    {
                        "code": "team_test",
                        "name": "Team Test",
                        "description": "team plan",
                        "price_cents": 500,
                        "quota_amount": 500,
                        "included_minutes": 500,
                        "ai_credits": 50,
                        "team_seats": 3,
                        "duration_days": 30,
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
            expose_auth_code=True,
            auth_code_cooldown_sec=1,
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
        self.assertEqual(profile["plan_name"], "免费账户")
        self.assertEqual(profile["quota"]["request_limit"], 10)
        self.assertEqual(profile["usage"]["included_minutes"], 0)
        self.assertEqual(profile["usage"]["ai_credits_remaining"], 0)
        self.assertEqual(profile["usage"]["points_remaining"], 1_000)
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
        self.assertEqual(refreshed["user"]["plan_name"], "免费账户")
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

    def test_email_codes_create_verified_identities_once_and_phone_is_disabled(self) -> None:
        requested = self.service.request_auth_code("email", "Owner@Example.com")
        self.assertEqual(requested["masked_identifier"], "ow***@example.com")
        code = requested["verification_code"]

        session = self.service.verify_auth_code("email", "owner@example.com", code)
        principal = self.service.authenticate(f"Bearer {session['access_token']}")
        identities = self.service.list_identities(principal)
        self.assertEqual(identities[0]["provider"], "email")
        self.assertTrue(identities[0]["verified"])
        with self.assertRaises(AccountAuthError):
            self.service.verify_auth_code("email", "owner@example.com", code)

        with self.assertRaisesRegex(
            AccountDeliveryUnavailableError,
            "手机号验证码服务暂未开放",
        ):
            self.service.request_auth_code("phone", "138-0013-8000")

    def test_auth_code_is_rate_limited_and_attempt_limited(self) -> None:
        requested = self.service.request_auth_code("email", "rate@example.com")
        with self.assertRaises(AccountConflictError):
            self.service.request_auth_code("email", "rate@example.com")
        wrong_code = "000000" if requested["verification_code"] != "000000" else "000001"
        for _ in range(self.service.auth_code_max_attempts):
            with self.assertRaises(AccountAuthError):
                self.service.verify_auth_code("email", "rate@example.com", wrong_code)
        with self.assertRaises(AccountAuthError):
            self.service.verify_auth_code(
                "email",
                "rate@example.com",
                requested["verification_code"],
            )

    def test_delivery_unavailable_error_is_preserved_for_disabled_phone_codes(self) -> None:
        def reject_phone(_channel: str, _subject: str, _code: str) -> None:
            raise AccountDeliveryUnavailableError("手机号验证码服务暂未开放")

        self.service.auth_code_sender = reject_phone
        with self.assertRaisesRegex(
            AccountDeliveryUnavailableError,
            "手机号验证码服务暂未开放",
        ):
            self.service.request_auth_code("phone", "13800138000")

    def test_stt_usage_is_charged_after_success_and_is_idempotent(self) -> None:
        session = self.service.register("stt_user", "strong-password")
        principal = self.service.authenticate(f"Bearer {session['access_token']}")
        first = self.service.record_stt_usage(
            principal,
            duration_ms=61_001,
            meeting_id="meeting-1",
            idempotency_key="stt:meeting-1:full",
        )
        duplicate = self.service.record_stt_usage(
            principal,
            duration_ms=61_001,
            meeting_id="meeting-1",
            idempotency_key="stt:meeting-1:full",
        )
        self.assertEqual(first["id"], duplicate["id"])
        usage = self.service.usage_summary(principal)
        self.assertEqual(usage["stt_seconds_used"], 62)
        self.assertEqual(usage["points_used"], 20)

        with self.service._connect() as conn:
            conn.execute(
                """
                UPDATE account_usage_balances
                SET ai_credits_used = 3, period_end = 1
                WHERE user_id = ?
                """,
                (principal.user_id,),
            )
        rolled = self.service.usage_summary(principal)
        self.assertEqual(rolled["stt_seconds_used"], 0)
        self.assertEqual(rolled["ai_credits_used"], 0)
        self.assertEqual(rolled["points_used"], 0)

    def test_stt_user_id_billing_rejects_disabled_users_and_allows_idempotent_retry(self) -> None:
        session = self.service.register("direct_stt_user", "strong-password")
        principal = self.service.authenticate(f"Bearer {session['access_token']}")
        usage_key = self.service.canonical_stt_usage_key(
            principal.user_id,
            "android-upload-01",
        )
        with self.service._connect() as conn:
            conn.execute(
                "UPDATE account_usage_balances SET points_granted = 20, points_used = 0 WHERE user_id = ?",
                (principal.user_id,),
            )

        first = self.service.record_stt_usage_for_user(
            principal.user_id,
            duration_ms=61_000,
            meeting_id="meeting-direct-stt",
            idempotency_key=usage_key,
        )
        self.assertEqual(first["quantity"], 20)
        retry_balance = self.service.ensure_stt_available_for_user(
            principal.user_id,
            idempotency_key=usage_key,
        )
        self.assertEqual(retry_balance["points_remaining"], 0)
        duplicate = self.service.record_stt_usage_for_user(
            principal.user_id,
            duration_ms=61_000,
            meeting_id="meeting-direct-stt",
            idempotency_key=usage_key,
        )
        self.assertEqual(duplicate["id"], first["id"])

        with self.service._connect() as conn:
            conn.execute("UPDATE users SET enabled = 0 WHERE id = ?", (principal.user_id,))
        with self.assertRaises(AccountAuthError):
            self.service.ensure_stt_available_for_user(
                principal.user_id,
                idempotency_key=usage_key,
            )
        with self.assertRaises(AccountAuthError):
            self.service.record_stt_usage_for_user(
                principal.user_id,
                duration_ms=1_000,
                meeting_id="meeting-direct-stt",
                idempotency_key=usage_key,
            )

    def test_agent_credits_refund_failures_and_allow_three_free_regenerations(self) -> None:
        session = self.service.register("credit_user", "strong-password")
        principal = self.gateway.authenticate(f"Bearer {session['agent_access_token']}")
        root = Path(self.temp_dir.name)

        def fail_runner(*_args):
            raise RuntimeError("provider failed")

        failing = AgentGateway(
            db_path=self.db_path,
            work_root=root / "failing-tasks",
            runner=fail_runner,
        )
        failing.initialize()
        with self.assertRaises(AgentProviderError):
            failing.execute(
                principal,
                {
                    "provider": "codex-cli",
                    "operation": "generate_report",
                    "transcript": "test",
                    "meeting_id": "meeting-credit",
                    "usage_key": "credit-failure",
                },
                [],
            )
        account = self.service.authenticate(f"Bearer {session['access_token']}")
        self.assertEqual(self.service.usage_summary(account)["ai_credits_used"], 0)
        self.assertEqual(self.service.usage_summary(account)["points_used"], 0)

        succeeding = AgentGateway(
            db_path=self.db_path,
            work_root=root / "success-tasks",
            runner=lambda *_args: "ok",
        )
        succeeding.initialize()
        charges = []
        for index in range(5):
            result = succeeding.execute(
                principal,
                {
                    "provider": "codex-cli",
                    "operation": "generate_report",
                    "transcript": "test",
                    "meeting_id": "meeting-credit",
                    "usage_key": f"credit-success-{index}",
                },
                [],
            )
            charges.append(result["charged"])
        self.assertEqual(charges, [True, False, False, False, True])
        self.assertEqual(self.service.usage_summary(account)["ai_credits_used"], 2)
        self.assertEqual(self.service.usage_summary(account)["points_used"], 60)

    def test_agent_is_limited_by_points_not_legacy_ai_credits(self) -> None:
        session = self.service.register("points_only_user", "strong-password")
        account = self.service.authenticate(f"Bearer {session['access_token']}")
        points_gateway = AgentGateway(
            db_path=self.db_path,
            work_root=Path(self.temp_dir.name) / "points-only-tasks",
            runner=lambda *_args: "ok",
        )
        points_gateway.initialize()
        principal = points_gateway.authenticate(f"Bearer {session['agent_access_token']}")
        with self.service._connect() as conn:
            conn.execute(
                """
                UPDATE account_usage_balances
                SET ai_credits_granted = 0, ai_credits_used = 0,
                    points_granted = 100, points_used = 0
                WHERE user_id = ?
                """,
                (account.user_id,),
            )

        result = points_gateway.execute(
            principal,
            {
                "provider": "codex-cli",
                "operation": "chat",
                "messages": [{"role": "user", "content": "hello"}],
                "usage_key": "points-only-chat",
            },
            [],
        )
        self.assertEqual(result["status"], "succeeded")
        self.assertEqual(self.service.usage_summary(account)["points_used"], 10)

        with self.service._connect() as conn:
            conn.execute(
                "UPDATE account_usage_balances SET period_end = 1 WHERE user_id = ?",
                (account.user_id,),
            )
        with self.assertRaises(AgentQuotaError):
            points_gateway.execute(
                principal,
                {
                    "provider": "codex-cli",
                    "operation": "chat",
                    "messages": [{"role": "user", "content": "expired"}],
                    "usage_key": "expired-points-chat",
                },
                [],
            )

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
        self.assertEqual(profile["usage"]["included_minutes"], 100)
        self.assertEqual(profile["usage"]["ai_credits_remaining"], 20)
        self.assertEqual(profile["usage"]["points_remaining"], 1_600)
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

    def test_identity_binding_and_password_reset_require_the_right_verification_purpose(self) -> None:
        session = self.service.register("identity_user", "strong-password")
        principal = self.service.authenticate(f"Bearer {session['access_token']}")
        requested = self.service.request_auth_code("email", "identity@example.com", "bind")
        identities = self.service.bind_identity(
            principal,
            "email",
            "identity@example.com",
            requested["verification_code"],
        )
        self.assertEqual([item["provider"] for item in identities], ["password", "email"])

        requested = self.service.request_auth_code("email", "identity@example.com", "reset_password")
        self.assertEqual(
            self.service.reset_password(
                "email",
                "identity@example.com",
                requested["verification_code"],
                "new-strong-password",
            )["status"],
            "password_reset",
        )
        with self.assertRaises(AccountAuthError):
            self.service.authenticate(f"Bearer {session['access_token']}")
        self.assertIn("access_token", self.service.login("identity_user", "new-strong-password"))

    def test_team_seats_and_subscription_expiry_are_enforced(self) -> None:
        owner_session = self.service.register("team_owner", "strong-password")
        owner = self.service.authenticate(f"Bearer {owner_session['access_token']}")
        members = [
            self.service.authenticate(
                f"Bearer {self.service.register(f'team_member_{index}', 'strong-password')['access_token']}"
            )
            for index in range(3)
        ]
        admin = self.service.authenticate(
            f"Bearer {self.service.login('admin', 'admin-test-password')['access_token']}"
        )
        order = self.service.create_order(owner, "team_test")
        self.service.approve_order(admin, order["id"])

        team = self.service.team(owner)
        self.assertEqual(team["seat_limit"], 3)
        self.service.add_team_member(owner, members[0].user_id)
        self.service.add_team_member(owner, members[1].user_id)
        with self.assertRaises(AccountPermissionError):
            self.service.add_team_member(owner, members[2].user_id)

        with self.service._connect() as conn:
            conn.execute(
                "UPDATE user_entitlements SET vip_expires_at = ? WHERE user_id = ?",
                (1, owner.user_id),
            )
        expired = self.service.profile(owner)
        self.assertFalse(expired["vip_enabled"])
        self.assertEqual(expired["usage"]["team_seats"], 1)
        self.assertEqual(self.service.team(owner)["seats_used"], 1)


if __name__ == "__main__":
    unittest.main()
