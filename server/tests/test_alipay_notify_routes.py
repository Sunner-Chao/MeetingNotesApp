from __future__ import annotations

import sys
import unittest
from pathlib import Path

from fastapi.testclient import TestClient


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

import web_backend as backend


class AlipayNotifyRouteAuthTests(unittest.TestCase):
    """Alipay posts the async notification without any API credentials.

    If the web credential gate rejects it, payments never get confirmed and Alipay
    keeps retrying, so the bypass needs a regression guard.
    """

    def setUp(self) -> None:
        self.previous_web_token = backend.WEB_API_TOKEN
        backend.WEB_API_TOKEN = "test-protected-backend"

    def tearDown(self) -> None:
        backend.WEB_API_TOKEN = self.previous_web_token

    def test_notify_endpoint_is_reachable_without_api_credentials(self) -> None:
        with TestClient(backend.app) as client:
            response = client.post(
                "/api/payment/alipay/notify",
                data={"out_trade_no": "ZWNOTIFYGUARD01", "trade_status": "TRADE_SUCCESS"},
            )
        self.assertNotEqual(response.status_code, 401)
        self.assertEqual(response.status_code, 200)
        # Unsigned payloads must still be rejected at the business layer.
        self.assertEqual(response.text, "fail")

    def test_lookalike_notify_paths_still_require_credentials(self) -> None:
        with TestClient(backend.app) as client:
            for path in (
                "/api/payment/alipay/notify-forged",
                "/api/payment/alipay/notify/extra",
                "/api/payment/alipay/refund",
            ):
                with self.subTest(path=path):
                    response = client.post(path, data={"trade_status": "TRADE_SUCCESS"})
                    self.assertEqual(response.status_code, 401)


if __name__ == "__main__":
    unittest.main()
