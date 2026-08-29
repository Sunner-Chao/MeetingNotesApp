from __future__ import annotations

import json
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

from account_service import AccountService
from agent_gateway import AgentGateway
from alipay_payment import (
    AlipayConfig,
    AlipayConfigurationError,
    AlipayPaymentClient,
    amount_cents,
    is_paid_notification,
    load_alipay_config,
    normalize_amount,
    notify_business_matches,
)


class AlipayPaymentTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.db_path = root / "accounts.db"
        self.plans_path = root / "plans.json"
        self.plans_path.write_text(
            json.dumps(
                [
                    {
                        "code": "points_2000",
                        "name": "2000 积分",
                        "price_cents": 990,
                        "quota_amount": 2000,
                        "points": 2000,
                    }
                ]
            ),
            encoding="utf-8",
        )
        AgentGateway(
            db_path=self.db_path,
            work_root=root / "tasks",
            bootstrap_token="agent-test-token",
        ).initialize()
        self.service = AccountService(
            self.db_path,
            token_secret="account-test-secret",
            plans_path=self.plans_path,
            admin_username="admin",
            admin_password="admin-test-password",
        )
        self.service.initialize()
        registered = self.service.register("payment_user", "strong-password")
        self.principal = self.service.authenticate(f"Bearer {registered['access_token']}")

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_amount_normalization_uses_fixed_precision(self) -> None:
        self.assertEqual(normalize_amount("9"), "9.00")
        self.assertEqual(normalize_amount("9.9"), "9.90")
        self.assertEqual(amount_cents("9.90"), 990)
        self.assertIsNone(normalize_amount("9.999"))
        self.assertIsNone(normalize_amount("NaN"))
        self.assertIsNone(normalize_amount("Infinity"))
        self.assertIsNone(normalize_amount("invalid"))

    def test_missing_configuration_blocks_sdk_use(self) -> None:
        with patch.dict(
            os.environ,
            {
                "ALIPAY_ENABLED": "1",
                "ALIPAY_ENVIRONMENT": "sandbox",
                "ALIPAY_SANDBOX_CONFIG_PATH": str(Path(self.temp_dir.name) / "missing.json"),
                "ALIPAY_APP_ID": "",
                "ALIPAY_APP_PRIVATE_KEY": "",
                "ALIPAY_ALIPAY_PUBLIC_KEY": "",
            },
            clear=False,
        ):
            config = load_alipay_config()
            self.assertFalse(config.is_ready)
            with self.assertRaisesRegex(AlipayConfigurationError, "配置不完整"):
                AlipayPaymentClient(config)

    def test_production_failures_never_mention_the_sandbox(self) -> None:
        """A production deployment must not tell users the sandbox is unavailable."""
        base = {
            "ALIPAY_ENVIRONMENT": "production",
            "ALIPAY_SANDBOX_CONFIG_PATH": str(Path(self.temp_dir.name) / "missing.json"),
            "ALIPAY_APP_ID": "",
            "ALIPAY_APP_PRIVATE_KEY": "",
            "ALIPAY_ALIPAY_PUBLIC_KEY": "",
        }
        with patch.dict(os.environ, {**base, "ALIPAY_ENABLED": "0"}, clear=False):
            reason = load_alipay_config().unavailable_reason()
        self.assertNotIn("沙箱", reason)
        self.assertIn("尚未启用", reason)
        self.assertIn("生产", reason)

        with patch.dict(os.environ, {**base, "ALIPAY_ENABLED": "1"}, clear=False):
            reason = load_alipay_config().unavailable_reason()
        self.assertNotIn("沙箱", reason)
        self.assertIn("配置不完整", reason)
        for label in ("应用 APPID", "应用私钥", "支付宝公钥", "商家收款账号"):
            self.assertIn(label, reason)

    def test_seller_identity_is_required_for_readiness(self) -> None:
        """Without a seller identity every async notify is silently dropped,
        so the gateway must refuse to come up rather than take money."""
        base = {
            "ALIPAY_ENABLED": "1",
            "ALIPAY_ENVIRONMENT": "production",
            "ALIPAY_SANDBOX_CONFIG_PATH": str(Path(self.temp_dir.name) / "missing.json"),
            "ALIPAY_APP_ID": "prod-app",
            "ALIPAY_APP_PRIVATE_KEY": "private",
            "ALIPAY_ALIPAY_PUBLIC_KEY": "public",
            "ALIPAY_SELLER_ID": "",
            "ALIPAY_SELLER_EMAIL": "",
        }
        with patch.dict(os.environ, base, clear=False):
            config = load_alipay_config()
            self.assertFalse(config.is_ready)
            self.assertIn("商家收款账号", config.unavailable_reason())

        with patch.dict(os.environ, {**base, "ALIPAY_SELLER_ID": "2088123456789012"}, clear=False):
            config = load_alipay_config()
            self.assertTrue(config.is_ready)

    def test_sandbox_failures_still_name_the_sandbox(self) -> None:
        with patch.dict(
            os.environ,
            {
                "ALIPAY_ENABLED": "0",
                "ALIPAY_ENVIRONMENT": "sandbox",
                "ALIPAY_SANDBOX_CONFIG_PATH": str(Path(self.temp_dir.name) / "missing.json"),
            },
            clear=False,
        ):
            reason = load_alipay_config().unavailable_reason()
        self.assertIn("沙箱", reason)

    def test_notify_business_validation_requires_amount_app_and_seller(self) -> None:
        config = AlipayConfig(
            enabled=True,
            environment="sandbox",
            app_id="sandbox-app",
            app_private_key="private",
            alipay_public_key="public",
            gateway="https://example.invalid/gateway.do",
            notify_url="",
            seller_id="seller-1",
            seller_email="",
            timeout_sec=30,
        )
        params = {
            "app_id": "sandbox-app",
            "out_trade_no": "ZW123",
            "total_amount": "9.90",
            "seller_id": "seller-1",
            "trade_status": "TRADE_SUCCESS",
        }
        self.assertTrue(
            notify_business_matches(
                params, config=config, out_trade_no="ZW123", amount_cents_expected=990
            )
        )
        self.assertFalse(
            notify_business_matches(
                {**params, "seller_id": "other-seller", "seller_email": ""},
                config=config,
                out_trade_no="ZW123",
                amount_cents_expected=990,
            )
        )
        self.assertFalse(
            notify_business_matches(
                {**params, "total_amount": "9.91"},
                config=config,
                out_trade_no="ZW123",
                amount_cents_expected=990,
            )
        )
        self.assertTrue(is_paid_notification(params))
        self.assertFalse(is_paid_notification({**params, "refund_fee": "1.00"}))

    def test_fresh_pending_transaction_reuses_out_trade_no(self) -> None:
        order = self.service.create_order(self.principal, "points_2000")
        first = self.service.create_alipay_transaction(
            self.principal, order["id"],
            out_trade_no="ZW-ROTATE-A", subject="套餐", environment="sandbox",
        )
        second = self.service.create_alipay_transaction(
            self.principal, order["id"],
            out_trade_no="ZW-ROTATE-B", subject="套餐", environment="sandbox",
        )
        self.assertEqual(first["out_trade_no"], "ZW-ROTATE-A")
        self.assertEqual(second["out_trade_no"], "ZW-ROTATE-A")

    def test_expired_pending_transaction_rotates_out_trade_no(self) -> None:
        """Alipay closes unpaid app-pay trades at timeout_express; re-signing the
        same out_trade_no afterwards is rejected forever, so the order would
        become permanently unpayable without rotation."""
        order = self.service.create_order(self.principal, "points_2000")
        first = self.service.create_alipay_transaction(
            self.principal, order["id"],
            out_trade_no="ZW-ROTATE-OLD", subject="套餐", environment="sandbox",
        )
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute(
                "UPDATE alipay_transactions SET created_at = created_at - 3600 WHERE order_id = ?",
                (order["id"],),
            )
            conn.commit()
        finally:
            conn.close()
        rotated = self.service.create_alipay_transaction(
            self.principal, order["id"],
            out_trade_no="ZW-ROTATE-NEW", subject="套餐", environment="sandbox",
        )
        self.assertEqual(first["out_trade_no"], "ZW-ROTATE-OLD")
        self.assertEqual(rotated["out_trade_no"], "ZW-ROTATE-NEW")
        self.assertEqual(rotated["status"], "created")
        self.assertIsNone(rotated["trade_no"])

    def test_closed_transaction_rotates_out_trade_no(self) -> None:
        order = self.service.create_order(self.principal, "points_2000")
        self.service.create_alipay_transaction(
            self.principal, order["id"],
            out_trade_no="ZW-CLOSED-OLD", subject="套餐", environment="sandbox",
        )
        self.service.update_alipay_transaction("ZW-CLOSED-OLD", status="closed")
        rotated = self.service.create_alipay_transaction(
            self.principal, order["id"],
            out_trade_no="ZW-CLOSED-NEW", subject="套餐", environment="sandbox",
        )
        self.assertEqual(rotated["out_trade_no"], "ZW-CLOSED-NEW")
        self.assertEqual(rotated["status"], "created")

    def test_paid_notification_is_persistent_and_idempotent(self) -> None:
        order = self.service.create_order(self.principal, "points_2000")
        transaction = self.service.create_alipay_transaction(
            self.principal,
            order["id"],
            out_trade_no="ZW-TEST-001",
            subject="智悟本积分套餐-2000 积分",
            environment="sandbox",
        )
        first = self.service.process_alipay_notification(
            out_trade_no=transaction["out_trade_no"],
            trade_no="ALIPAY-TRADE-001",
            trade_status="TRADE_SUCCESS",
            notify_id="NOTIFY-001",
            payload_hash="PAYLOAD-001",
            payload_json="{}",
            paid=True,
        )
        second = self.service.process_alipay_notification(
            out_trade_no=transaction["out_trade_no"],
            trade_no="ALIPAY-TRADE-001",
            trade_status="TRADE_SUCCESS",
            notify_id="NOTIFY-001",
            payload_hash="PAYLOAD-001",
            payload_json="{}",
            paid=True,
        )
        self.assertFalse(first["duplicate"])
        self.assertTrue(second["duplicate"])
        self.assertEqual(
            self.service.alipay_transaction_for_user(self.principal, order["id"])["status"],
            "paid",
        )
        self.assertEqual(self.service.list_orders(self.principal)[0]["status"], "approved")
        self.assertEqual(self.service.profile(self.principal)["usage"]["points_remaining"], 2000)
        conn = sqlite3.connect(self.db_path)
        try:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM alipay_notify_events").fetchone()[0], 1)
        finally:
            conn.close()

    def test_late_payment_notification_does_not_reopen_refunded_transaction(self) -> None:
        order = self.service.create_order(self.principal, "points_2000")
        transaction = self.service.create_alipay_transaction(
            self.principal,
            order["id"],
            out_trade_no="ZW-TEST-REFUND-001",
            subject="智悟本积分套餐-2000 积分",
            environment="sandbox",
        )
        self.service.update_alipay_transaction(
            transaction["out_trade_no"], status="refunded", trade_no="ALIPAY-TRADE-REFUND"
        )
        result = self.service.process_alipay_notification(
            out_trade_no=transaction["out_trade_no"],
            trade_no="ALIPAY-TRADE-REFUND",
            trade_status="TRADE_SUCCESS",
            notify_id="NOTIFY-REFUND-LATE",
            payload_hash="PAYLOAD-REFUND-LATE",
            payload_json="{}",
            paid=True,
        )
        self.assertFalse(result["duplicate"])
        current = self.service.alipay_transaction_for_user(self.principal, order["id"])
        self.assertEqual(current["status"], "refunded")
        self.assertEqual(self.service.list_orders(self.principal)[0]["status"], "pending")


if __name__ == "__main__":
    unittest.main()
