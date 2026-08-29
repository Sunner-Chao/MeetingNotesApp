from __future__ import annotations

import base64
import json
import sqlite3
import sys
import tempfile
import time
import unittest
from datetime import datetime, timezone
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
    beijing_day_start_epoch,
)
from agent_gateway import AgentAuthError, AgentGateway, AgentProviderError, AgentQuotaError
from common.account_stt_token import verify_account_stt_token


class AccountServiceTests(unittest.TestCase):
    def test_beijing_day_start_uses_utc_plus_eight_boundary(self) -> None:
        observed = int(datetime(2026, 8, 27, 16, 30, tzinfo=timezone.utc).timestamp())
        expected = int(datetime(2026, 8, 27, 16, 0, tzinfo=timezone.utc).timestamp())
        self.assertEqual(beijing_day_start_epoch(observed), expected)

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

    def test_social_auth_state_ticket_and_referral_are_single_use(self) -> None:
        inviter_session = self.service.register("social_inviter", "strong-password")
        inviter = self.service.authenticate(f"Bearer {inviter_session['access_token']}")
        referral_code = self.service.growth_overview(inviter)["referral"]["code"]

        started = self.service.begin_social_auth(
            "telegram",
            client="pwa",
            redirect_uri="https://app.example/app/",
            referral_code=referral_code,
        )
        state = self.service.consume_social_auth_state("telegram", started["state"])
        self.assertEqual(state["referral_code"], referral_code)
        with self.assertRaises(AccountAuthError):
            self.service.consume_social_auth_state("telegram", started["state"])

        user_id = self.service.social_identity_login(
            "telegram",
            "tg-user-42",
            display_name="第三方用户",
            referral_code=state["referral_code"],
            metadata={"client": "pwa"},
        )
        ticket = self.service.issue_social_ticket(
            user_id, "telegram", "https://app.example/app/"
        )
        session = self.service.exchange_social_ticket(ticket)
        with self.assertRaises(AccountAuthError):
            self.service.exchange_social_ticket(ticket)

        principal = self.service.authenticate(f"Bearer {session['access_token']}")
        profile = self.service.profile(principal)
        self.assertEqual(profile["registration_source"], "telegram")
        self.assertEqual(profile["used_referral_code"], referral_code)
        self.assertEqual(profile["referrer"]["user_id"], inviter.user_id)
        self.assertEqual(profile["social_identities"][0]["provider"], "telegram")
        self.assertEqual(
            self.service.profile(inviter)["referral"]["successful_invites"], 1
        )

        admin_session = self.service.login("admin", "admin-test-password")
        admin = self.service.authenticate(f"Bearer {admin_session['access_token']}")
        audit = self.service.admin_social_auth_audit(admin)
        self.assertEqual(audit[0]["provider"], "telegram")
        self.assertTrue(audit[0]["success"])

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

    def test_admin_tracks_each_redemption_code_and_claim(self) -> None:
        admin = self.service.authenticate(
            f"Bearer {self.service.login('admin', 'admin-test-password')['access_token']}"
        )
        user_session = self.service.register("growth_code_user", "strong-password")
        user = self.service.authenticate(f"Bearer {user_session['access_token']}")
        batch = self.service.admin_create_redemption_batch(
            admin,
            name="秋季福利",
            quantity=2,
            reward_type="points",
            reward_quantity=80,
            expires_at=int(time.time()) + 3600,
            max_uses=1,
            prefix="AUTUMN",
        )

        unused = self.service.admin_list_redemption_codes(admin, batch["id"])
        self.assertEqual(unused["total"], 2)
        self.assertEqual(unused["status_counts"]["unused"], 2)
        self.assertTrue(all(item["claims"] == [] for item in unused["items"]))

        redeemed = self.service.redeem_code(user, batch["codes"][0])
        self.assertEqual(redeemed["quantity"], 80)
        used = self.service.admin_list_redemption_codes(
            admin,
            batch["id"],
            status="used",
            search=batch["codes"][0][-6:],
        )
        self.assertEqual(used["total"], 1)
        self.assertEqual(used["items"][0]["status"], "used")
        self.assertEqual(used["items"][0]["claims"][0]["username"], "growth_code_user")
        self.assertEqual(used["items"][0]["claims"][0]["reward"]["quantity"], 80)

    def test_growth_defaults_and_referrals_use_increased_point_rewards(self) -> None:
        inviter_session = self.service.register("growth_inviter", "strong-password")
        inviter = self.service.authenticate(f"Bearer {inviter_session['access_token']}")
        overview = self.service.growth_overview(inviter)
        self.assertEqual(overview["referral"]["reward_points"], 300)
        self.assertEqual(overview["private_channel"]["name"], "智悟本福利7群")
        self.assertEqual(overview["private_channel"]["reward"]["quantity"], 200)

        invitee_session = self.service.register(
            "growth_invitee",
            "strong-password",
            overview["referral"]["code"],
        )
        invitee = self.service.authenticate(f"Bearer {invitee_session['access_token']}")
        self.assertEqual(self.service.profile(inviter)["usage"]["points_remaining"], 1_300)
        self.assertEqual(self.service.profile(invitee)["usage"]["points_remaining"], 1_300)

        campaigns = {item["id"]: item for item in self.service.list_growth_campaigns()}
        self.assertEqual(campaigns["daily-quiz"]["rules"]["answer_reward"], 50)
        self.assertEqual(campaigns["zhejiang-study"]["rules"]["draw_reward"], 200)
        self.assertEqual(campaigns["invitation-ranking"]["reward_pool"]["ranks"]["1"], 2_000)

    def test_growth_default_migration_preserves_custom_campaigns(self) -> None:
        old_quiz_rules = {
            "checkin_reward": 10,
            "answer_reward": 20,
            "questions": [
                {"key": "q1", "question": "浙江省省会是哪里？", "options": ["杭州", "宁波", "温州"], "answer": "杭州"},
                {"key": "q2", "question": "每天完成记录后最重要的动作是什么？", "options": ["复盘", "删除", "跳过"], "answer": "复盘"},
            ],
        }
        with self.service._connect() as conn:
            conn.execute(
                "UPDATE growth_private_channels SET name = '智悟本福利群', reward_payload_json = '{\"quantity\":50}' WHERE id = 'default-welfare-group'"
            )
            conn.execute(
                "UPDATE growth_campaigns SET rules_json = ?, reward_pool_json = ? WHERE id = 'daily-quiz'",
                (json.dumps(old_quiz_rules, ensure_ascii=False), json.dumps({"ranks": {"1": 500, "2": 300, "3": 100}}, ensure_ascii=False)),
            )
            conn.execute(
                "UPDATE growth_campaigns SET rules_json = ? WHERE id = 'zhejiang-study'",
                (json.dumps({"checkin_reward": 999}, ensure_ascii=False),),
            )

        self.service.initialize()

        with self.service._connect() as conn:
            channel = conn.execute(
                "SELECT name, reward_payload_json FROM growth_private_channels WHERE id = 'default-welfare-group'"
            ).fetchone()
            quiz = conn.execute(
                "SELECT rules_json FROM growth_campaigns WHERE id = 'daily-quiz'"
            ).fetchone()
            custom = conn.execute(
                "SELECT rules_json FROM growth_campaigns WHERE id = 'zhejiang-study'"
            ).fetchone()
        self.assertEqual(channel["name"], "智悟本福利7群")
        self.assertEqual(json.loads(channel["reward_payload_json"])["quantity"], 200)
        self.assertEqual(json.loads(quiz["rules_json"])["answer_reward"], 50)
        self.assertEqual(json.loads(custom["rules_json"])["checkin_reward"], 999)

    def test_private_channel_requires_application_before_qr_is_revealed(self) -> None:
        user_session = self.service.register("channel_applicant", "strong-password")
        user = self.service.authenticate(f"Bearer {user_session['access_token']}")
        admin_session = self.service.login("admin", "admin-test-password")
        admin = self.service.authenticate(f"Bearer {admin_session['access_token']}")

        public_channel = self.service.public_private_channel()
        self.assertEqual(public_channel["manager_card_image_url"], "/api/growth/private-channel/default-manager-card")
        self.assertEqual(public_channel["qr_image_url"], "")

        before = self.service.growth_overview(user)["private_channel"]
        self.assertEqual(before["application"], None)
        self.assertEqual(before["qr_image_url"], "")

        submitted = self.service.submit_private_channel_application(
            user,
            "default-welfare-group",
            {"name": "测试用户", "city": "杭州", "purpose": "参加研学活动"},
        )
        self.assertEqual(submitted["channel"]["application"]["status"], "pending")
        self.assertEqual(submitted["channel"]["qr_image_url"], "")
        pending = self.service.admin_list_private_channel_applications(admin, "pending")
        self.assertEqual(len(pending), 1)
        self.assertEqual(pending[0]["answers"]["city"], "杭州")

        self.service.admin_decide_private_channel_application(
            admin, submitted["application"]["id"], "approved"
        )
        after = self.service.growth_overview(user)["private_channel"]
        self.assertEqual(after["application"]["status"], "approved")
        self.assertNotEqual(after["qr_image_url"], "")

    def test_campaign_admin_lifecycle_hides_drafts_and_notifies_winners(self) -> None:
        admin = self.service.authenticate(
            f"Bearer {self.service.login('admin', 'admin-test-password')['access_token']}"
        )
        user_session = self.service.register("campaign_user", "strong-password")
        user = self.service.authenticate(f"Bearer {user_session['access_token']}")
        now = int(time.time())
        draft = self.service.admin_create_campaign(
            admin,
            {
                "title": "内部草稿活动",
                "campaign_type": "ranking",
                "summary": "尚未发布",
                "rules": {},
                "reward_pool": {"ranks": {"1": 120}},
                "starts_at": now - 10,
                "ends_at": now + 3600,
                "status": "draft",
            },
        )
        self.assertNotIn(draft["id"], [item["id"] for item in self.service.list_growth_campaigns()])
        published = self.service.admin_update_campaign(
            admin,
            draft["id"],
            {**draft, "status": "active"},
        )
        self.assertEqual(published["status"], "active")
        self.service.join_campaign(user, draft["id"])

        settled = self.service.admin_settle_campaign(admin, draft["id"])
        self.assertEqual(settled["winners"][0]["quantity"], 120)
        messages = self.service.system_messages(user)
        self.assertEqual(len(messages), 2)
        self.assertEqual(
            {item["message_type"] for item in messages},
            {"campaign_reward", "campaign_announcement"},
        )
        announcement = next(
            item for item in messages if item["message_type"] == "campaign_announcement"
        )
        self.service.mark_system_message_read(user, announcement["id"])
        refreshed = self.service.system_messages(user)
        self.assertIsNotNone(
            next(item for item in refreshed if item["id"] == announcement["id"])["read_at"]
        )
        self.assertEqual(len(refreshed), 2)

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
