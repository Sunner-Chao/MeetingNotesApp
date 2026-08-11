from __future__ import annotations

import json
import sys
import tempfile
import time
import unittest
from pathlib import Path

from fastapi.testclient import TestClient


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

import web_backend as backend
from account_service import AccountService
from agent_gateway import AgentGateway


class AccountRouteTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.db_path = root / "routes.db"
        self.plans_path = root / "plans.json"
        self.plans_path.write_text(
            json.dumps(
                [
                    {
                        "code": "route_plan",
                        "name": "Route Plan",
                        "price_cents": 100,
                        "quota_amount": 20,
                        "construction_logs_unlocked": True,
                    }
                ]
            ),
            encoding="utf-8",
        )
        self.previous_db = backend.DB_PATH
        self.previous_gateway = backend.AGENT_GATEWAY
        self.previous_accounts = backend.ACCOUNT_SERVICE
        backend.DB_PATH = self.db_path
        backend.AGENT_GATEWAY = AgentGateway(
            db_path=self.db_path,
            work_root=root / "tasks",
            bootstrap_token="route-agent-token",
        )
        backend.ACCOUNT_SERVICE = AccountService(
            self.db_path,
            token_secret="route-account-secret",
            plans_path=self.plans_path,
            admin_username="admin",
            admin_password="route-admin-password",
        )

    def tearDown(self) -> None:
        backend.DB_PATH = self.previous_db
        backend.AGENT_GATEWAY = self.previous_gateway
        backend.ACCOUNT_SERVICE = self.previous_accounts
        self.temp_dir.cleanup()

    def test_register_order_and_admin_approval_flow(self) -> None:
        with TestClient(backend.app) as client:
            registered = client.post(
                "/api/auth/register",
                json={"username": "route_user", "password": "strong-password"},
            )
            self.assertEqual(registered.status_code, 200)
            user_token = registered.json()["access_token"]
            self.assertTrue(registered.json()["stt_access_token"].startswith("mn_stt_user_v1."))
            user_headers = {"Authorization": f"Bearer {user_token}"}

            self.assertEqual(client.get("/api/account/me").status_code, 401)
            free_profile = client.get("/api/account/me", headers=user_headers).json()
            self.assertFalse(free_profile["vip_enabled"])
            self.assertEqual(free_profile["plan_name"], "Free")
            self.assertEqual(free_profile["quota"]["requests_remaining"], 10)
            updated_profile = client.patch(
                "/api/account/me",
                headers=user_headers,
                json={
                    "display_name": "现场负责人",
                    "avatar_data_url": None,
                },
            )
            self.assertEqual(updated_profile.status_code, 200)
            self.assertEqual(updated_profile.json()["display_name"], "现场负责人")
            refreshed = client.get("/api/account/session", headers=user_headers)
            self.assertEqual(refreshed.status_code, 200)
            self.assertEqual(refreshed.json()["user"]["plan_name"], "Free")
            self.assertEqual(refreshed.json()["user"]["display_name"], "现场负责人")
            self.assertTrue(refreshed.json()["stt_access_token"].startswith("mn_stt_user_v1."))
            order = client.post(
                "/api/account/orders",
                headers=user_headers,
                json={"plan_code": "route_plan"},
            )
            self.assertEqual(order.status_code, 200)

            admin = client.post(
                "/api/auth/login",
                json={"username": "admin", "password": "route-admin-password"},
            )
            admin_headers = {"Authorization": f"Bearer {admin.json()['access_token']}"}
            approved = client.post(
                f"/api/admin/accounts/orders/{order.json()['id']}/approve",
                headers=admin_headers,
            )
            self.assertEqual(approved.status_code, 200)
            profile = client.get("/api/account/me", headers=user_headers).json()
            self.assertTrue(profile["vip_enabled"])
            self.assertTrue(profile["construction_logs_unlocked"])
            self.assertEqual(profile["plan_code"], "route_plan")
            self.assertEqual(profile["quota"]["requests_remaining"], 30)

            deleted = client.delete(
                f"/api/admin/accounts/users/{profile['id']}",
                headers=admin_headers,
            )
            self.assertEqual(deleted.status_code, 200)
            self.assertEqual(deleted.json()["status"], "deleted")
            self.assertEqual(client.get("/api/account/me", headers=user_headers).status_code, 401)

    def test_account_meetings_are_isolated_synced_and_tombstoned(self) -> None:
        with TestClient(backend.app) as client:
            first = client.post(
                "/api/auth/register",
                json={"username": "meeting_owner", "password": "strong-password"},
            ).json()
            second = client.post(
                "/api/auth/register",
                json={"username": "other_owner", "password": "strong-password"},
            ).json()
            first_headers = {"Authorization": f"Bearer {first['access_token']}"}
            second_headers = {"Authorization": f"Bearer {second['access_token']}"}
            meeting_id = "pwa-meeting-01"
            now = int(time.time() * 1000)
            payload = {
                "title": "园区参观考察",
                "template_key": "inspection",
                "created_at": now - 1000,
                "updated_at": now,
                "duration_seconds": 125,
                "transcript": "第一版转写",
                "report": "# 第一版纪要",
            }

            created = client.put(
                f"/api/account/meetings/{meeting_id}",
                headers=first_headers,
                json=payload,
            )
            self.assertEqual(created.status_code, 200)
            self.assertEqual(created.json()["transcript"], "第一版转写")
            self.assertEqual(created.json()["template_key"], "inspection")
            owner_snapshot = client.get("/api/account/meetings", headers=first_headers).json()
            self.assertEqual([item["id"] for item in owner_snapshot["meetings"]], [meeting_id])
            self.assertEqual(
                client.get("/api/account/meetings", headers=second_headers).json()["meetings"],
                [],
            )

            newer = {**payload, "updated_at": now + 2000, "transcript": "云端较新版本"}
            self.assertEqual(
                client.put(
                    f"/api/account/meetings/{meeting_id}",
                    headers=first_headers,
                    json=newer,
                ).status_code,
                200,
            )
            stale = {**payload, "updated_at": now + 1000, "transcript": "过期设备版本"}
            stale_result = client.put(
                f"/api/account/meetings/{meeting_id}",
                headers=first_headers,
                json=stale,
            )
            self.assertEqual(stale_result.status_code, 200)
            self.assertEqual(stale_result.json()["transcript"], "云端较新版本")

            deleted = client.delete(
                f"/api/account/meetings/{meeting_id}",
                headers=first_headers,
                params={"deleted_at": now + 3000},
            )
            self.assertEqual(deleted.status_code, 200)
            after_delete = client.get("/api/account/meetings", headers=first_headers).json()
            self.assertEqual(after_delete["meetings"], [])
            self.assertEqual(after_delete["deleted"][0]["meeting_id"], meeting_id)

            rejected = client.put(
                f"/api/account/meetings/{meeting_id}",
                headers=first_headers,
                json={**payload, "updated_at": now + 2500},
            )
            self.assertEqual(rejected.status_code, 409)
            restored = client.put(
                f"/api/account/meetings/{meeting_id}",
                headers=first_headers,
                json={**payload, "updated_at": deleted.json()["deleted_at"] + 1},
            )
            self.assertEqual(restored.status_code, 200)

            cleared = client.delete(
                "/api/account/meetings",
                headers=first_headers,
                params={"deleted_at": int(time.time() * 1000)},
            )
            self.assertEqual(cleared.status_code, 200)
            self.assertEqual(cleared.json()["deleted"], 1)


if __name__ == "__main__":
    unittest.main()
