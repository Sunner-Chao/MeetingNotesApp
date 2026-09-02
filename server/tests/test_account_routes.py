from __future__ import annotations

import json
import io
import sys
import tempfile
import time
import unittest
from pathlib import Path

from fastapi.testclient import TestClient


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

import web_backend as backend
from account_service import AccountDeliveryUnavailableError, AccountService
from agent_gateway import AgentGateway


class AccountRouteTests(unittest.TestCase):
    def test_social_redirect_allowlist_uses_structured_exact_matching(self) -> None:
        self.assertTrue(
            backend._same_redirect_target(
                "zhiwuben://auth/callback?source=android",
                "zhiwuben://auth/callback",
            )
        )
        self.assertFalse(
            backend._same_redirect_target(
                "zhiwuben://auth/callback.evil",
                "zhiwuben://auth/callback",
            )
        )
        self.assertFalse(
            backend._same_redirect_target(
                "https://trusted.example.evil/app/",
                "https://trusted.example/app/",
            )
        )

    def test_product_callback_allowlist_accepts_configured_light_callback(self) -> None:
        previous_callbacks = backend.ACCOUNT_AUTH_ANDROID_CALLBACK_URIS
        backend.ACCOUNT_AUTH_ANDROID_CALLBACK_URIS = (
            "zhiwuben-light://auth/callback",
        )
        try:
            request = type("RequestStub", (), {"base_url": "https://lstwin.space/"})()
            self.assertEqual(
                backend._social_client_redirect(
                    request,
                    "android",
                    "zhiwuben-light://auth/callback?source=android",
                ),
                "zhiwuben-light://auth/callback?source=android",
            )
        finally:
            backend.ACCOUNT_AUTH_ANDROID_CALLBACK_URIS = previous_callbacks

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
                        "points": 2_000,
                        "construction_logs_unlocked": True,
                    }
                ]
            ),
            encoding="utf-8",
        )
        self.previous_db = backend.DB_PATH
        self.previous_gateway = backend.AGENT_GATEWAY
        self.previous_accounts = backend.ACCOUNT_SERVICE
        self.previous_web_api_token = backend.WEB_API_TOKEN
        self.previous_web_api_username = backend.WEB_API_USERNAME
        self.previous_growth_media_dir = backend.PRIVATE_CHANNEL_MEDIA_DIR
        self.previous_account_media_dir = backend.ACCOUNT_MEDIA_DIR
        backend.DB_PATH = self.db_path
        backend.WEB_API_TOKEN = "web-dashboard-secret"
        backend.WEB_API_USERNAME = "dashboard-admin"
        backend.PRIVATE_CHANNEL_MEDIA_DIR = root / "growth-media"
        backend.ACCOUNT_MEDIA_DIR = root / "account-media"
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
            expose_auth_code=True,
        )

    def tearDown(self) -> None:
        backend.DB_PATH = self.previous_db
        backend.AGENT_GATEWAY = self.previous_gateway
        backend.ACCOUNT_SERVICE = self.previous_accounts
        backend.WEB_API_TOKEN = self.previous_web_api_token
        backend.WEB_API_USERNAME = self.previous_web_api_username
        backend.PRIVATE_CHANNEL_MEDIA_DIR = self.previous_growth_media_dir
        backend.ACCOUNT_MEDIA_DIR = self.previous_account_media_dir
        self.temp_dir.cleanup()

    def register_with_email(self, client: TestClient, username: str):
        identifier = f"{username}@example.com"
        requested = client.post(
            "/api/auth/code/request",
            json={
                "channel": "email",
                "identifier": identifier,
                "purpose": "register",
            },
        )
        self.assertEqual(requested.status_code, 200, requested.text)
        registered = client.post(
            "/api/auth/register/verify",
            json={
                "channel": "email",
                "identifier": identifier,
                "code": requested.json()["verification_code"],
                "username": username,
                "password": "strong-password",
            },
        )
        self.assertEqual(registered.status_code, 200, registered.text)
        return registered

    def test_register_order_and_admin_approval_flow(self) -> None:
        with TestClient(backend.app) as client:
            legacy_registration = client.post(
                "/api/auth/register",
                json={"username": "legacy_route_user", "password": "strong-password"},
            )
            self.assertEqual(legacy_registration.status_code, 400)
            self.assertEqual(
                legacy_registration.json()["detail"],
                "注册账号必须先完成邮箱验证，请使用邮箱注册",
            )
            registered = self.register_with_email(client, "route_user")
            user_token = registered.json()["access_token"]
            self.assertTrue(registered.json()["stt_access_token"].startswith("mn_stt_user_v1."))
            user_headers = {"Authorization": f"Bearer {user_token}"}

            self.assertEqual(client.get("/api/account/me").status_code, 401)
            free_profile = client.get("/api/account/me", headers=user_headers).json()
            self.assertFalse(free_profile["vip_enabled"])
            self.assertEqual(free_profile["plan_name"], "免费账户")
            self.assertEqual(free_profile["quota"]["requests_remaining"], 10)
            self.assertEqual(free_profile["usage"]["points_remaining"], 1_000)
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
            self.assertEqual(refreshed.json()["user"]["plan_name"], "免费账户")
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
            self.assertEqual(profile["usage"]["points_remaining"], 2_000)

            deleted = client.delete(
                f"/api/admin/accounts/users/{profile['id']}",
                headers=admin_headers,
            )
            self.assertEqual(deleted.status_code, 200)
            self.assertEqual(deleted.json()["status"], "deleted")
            self.assertEqual(client.get("/api/account/me", headers=user_headers).status_code, 401)

    def test_admin_dashboard_alias_and_web_credentials_manage_accounts(self) -> None:
        import base64

        credentials = base64.b64encode(b"dashboard-admin:web-dashboard-secret").decode("ascii")
        web_headers = {"Authorization": f"Basic {credentials}"}
        with TestClient(backend.app) as client:
            self.assertEqual(client.get("/admin").status_code, 401)
            dashboard = client.get("/admin", headers=web_headers)
            self.assertEqual(dashboard.status_code, 200)
            self.assertIn("用户管理", dashboard.text)

            self.register_with_email(client, "web_dashboard_user")
            users = client.get("/api/admin/accounts/users", headers=web_headers)
            self.assertEqual(users.status_code, 200, users.text)
            self.assertTrue(any(item["username"] == "web_dashboard_user" for item in users.json()))
            orders = client.get("/api/admin/accounts/orders", headers=web_headers)
            self.assertEqual(orders.status_code, 200, orders.text)
            metrics = client.get("/api/admin/system/metrics", headers=web_headers)
            self.assertEqual(metrics.status_code, 200, metrics.text)
            self.assertIn("memory", metrics.json())
            self.assertIn("disk", metrics.json())

            batch = client.post(
                "/api/admin/growth/redemptions/batches",
                headers=web_headers,
                json={
                    "name": "路由测试福利",
                    "quantity": 2,
                    "reward_type": "points",
                    "reward_quantity": 30,
                    "max_uses": 1,
                    "prefix": "ROUTE",
                },
            )
            self.assertEqual(batch.status_code, 200, batch.text)
            codes = client.get(
                f"/api/admin/growth/redemptions/{batch.json()['id']}/codes",
                headers=web_headers,
            )
            self.assertEqual(codes.status_code, 200, codes.text)
            self.assertEqual(codes.json()["status_counts"]["unused"], 2)

            uploaded = client.post(
                "/api/admin/growth/private-channel/qr",
                headers=web_headers,
                files={
                    "file": (
                        "qr.png",
                        io.BytesIO(b"\x89PNG\r\n\x1a\nroute-test"),
                        "image/png",
                    )
                },
            )
            self.assertEqual(uploaded.status_code, 200, uploaded.text)
            qr_url = uploaded.json()["qr_image_url"]
            self.assertEqual(client.get(qr_url).status_code, 403)
            admin = client.post(
                "/api/auth/login",
                json={"username": "admin", "password": "route-admin-password"},
            )
            self.assertEqual(admin.status_code, 200, admin.text)
            admin_headers = {"Authorization": f"Bearer {admin.json()['access_token']}"}
            self.assertEqual(client.get(qr_url, headers=admin_headers).status_code, 200)

    def test_growth_admin_api_never_uses_open_dashboard_mode(self) -> None:
        previous_token = backend.WEB_API_TOKEN
        backend.WEB_API_TOKEN = ""
        try:
            with TestClient(backend.app) as client:
                dashboard = client.get("/admin")
                self.assertEqual(dashboard.status_code, 200)
                users = client.get("/api/admin/accounts/users")
                self.assertEqual(users.status_code, 403, users.text)
                growth = client.get("/api/admin/growth/overview")
                self.assertEqual(growth.status_code, 403, growth.text)
        finally:
            backend.WEB_API_TOKEN = previous_token

    def test_private_channel_events_accept_guests_but_reject_invalid_tokens(self) -> None:
        with TestClient(backend.app) as client:
            public_channel = client.get("/api/growth/private-channel")
            self.assertEqual(public_channel.status_code, 200, public_channel.text)
            self.assertEqual(public_channel.json()["qr_image_url"], "")
            manager_card_url = public_channel.json()["manager_card_image_url"]
            self.assertEqual(client.get(manager_card_url).status_code, 200)
            guest_event = client.post(
                "/api/growth/private-channel/events",
                json={
                    "event_type": "open_qr",
                    "channel_id": "default-welfare-group",
                    "source": "android",
                },
            )
            self.assertEqual(guest_event.status_code, 200, guest_event.text)
            self.assertEqual(guest_event.json()["status"], "recorded")

            invalid_token_event = client.post(
                "/api/growth/private-channel/events",
                headers={"Authorization": "Bearer invalid-account-token"},
                json={
                    "event_type": "click",
                    "channel_id": "default-welfare-group",
                    "source": "android",
                },
            )
            self.assertEqual(invalid_token_event.status_code, 401)

    def test_system_messages_are_scoped_and_support_read_receipts(self) -> None:
        with TestClient(backend.app) as client:
            registered = self.register_with_email(client, "message_user").json()
            headers = {"Authorization": f"Bearer {registered['access_token']}"}
            message_id = "route-system-message"
            with backend.ACCOUNT_SERVICE._connect() as conn:
                conn.execute(
                    "INSERT INTO growth_system_messages (id, user_id, message_type, title, body, action_path, created_at) VALUES (?, NULL, 'system', ?, ?, '', ?)",
                    (message_id, "系统维护通知", "本条通知用于路由回归测试", int(time.time())),
                )
            messages = client.get("/api/account/growth/messages", headers=headers)
            self.assertEqual(messages.status_code, 200)
            self.assertFalse(messages.json()[0]["read_at"])
            marked = client.post(f"/api/account/growth/messages/{message_id}/read", headers=headers)
            self.assertEqual(marked.status_code, 200)
            refreshed = client.get("/api/account/growth/messages", headers=headers).json()
            self.assertIsNotNone(refreshed[0]["read_at"])

    def test_code_auth_and_provider_discovery_use_mobile_first_policy(self) -> None:
        with TestClient(backend.app) as client:
            requested = client.post(
                "/api/auth/code/request",
                json={"channel": "phone", "identifier": "13800138000"},
            )
            self.assertEqual(requested.status_code, 503)
            self.assertIn("手机号验证码服务暂未开放", requested.json()["detail"])

            providers = client.get("/api/auth/providers").json()
            self.assertEqual(
                [item["id"] for item in providers],
                ["wechat", "qq", "feishu", "telegram", "whatsapp", "instagram"],
            )
            self.assertEqual(providers[2]["tier"], "team")
            self.assertTrue(all(item["status"] == "not_configured" for item in providers))

    def test_production_sender_rejects_phone_when_sms_is_not_open(self) -> None:
        with self.assertRaisesRegex(
            AccountDeliveryUnavailableError,
            "手机号验证码服务暂未开放",
        ):
            backend.send_account_auth_code("phone", "13800138000", "123456")

    def test_password_reset_and_identity_binding_routes_use_scoped_codes(self) -> None:
        with TestClient(backend.app) as client:
            registered = self.register_with_email(client, "binding_user").json()
            headers = {"Authorization": f"Bearer {registered['access_token']}"}
            requested = client.post(
                "/api/auth/code/request",
                json={"channel": "email", "identifier": "binding@example.com", "purpose": "bind"},
            ).json()
            bound = client.post(
                "/api/account/identities/verify",
                headers=headers,
                json={
                    "channel": "email",
                    "identifier": "binding@example.com",
                    "purpose": "bind",
                    "code": requested["verification_code"],
                },
            )
            self.assertEqual(bound.status_code, 200)
            self.assertIn("email", [item["provider"] for item in bound.json()])

            requested = client.post(
                "/api/auth/code/request",
                json={
                    "channel": "email",
                    "identifier": "binding@example.com",
                    "purpose": "reset_password",
                },
            ).json()
            reset = client.post(
                "/api/auth/password/reset",
                json={
                    "channel": "email",
                    "identifier": "binding@example.com",
                    "purpose": "reset_password",
                    "code": requested["verification_code"],
                    "new_password": "new-strong-password",
                },
            )
            self.assertEqual(reset.status_code, 200)
            self.assertEqual(reset.json()["status"], "password_reset")
            self.assertEqual(client.get("/api/account/me", headers=headers).status_code, 401)
            self.assertEqual(
                client.post(
                    "/api/auth/password/login",
                    json={"username": "binding_user", "password": "new-strong-password"},
                ).status_code,
                200,
            )

    def test_account_meetings_are_isolated_synced_and_tombstoned(self) -> None:
        with TestClient(backend.app) as client:
            first = self.register_with_email(client, "meeting_owner").json()
            second = self.register_with_email(client, "other_owner").json()
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
            uploaded_image = client.put(
                f"/api/account/meetings/{meeting_id}/images/550e8400-e29b-41d4-a716-446655440000",
                headers={**first_headers, "X-Image-Name": "onsite.png", "X-Image-Updated-At": str(now)},
                files={"file": ("onsite.png", io.BytesIO(b"\x89PNG\r\n\x1a\nimage"), "image/png")},
            )
            self.assertEqual(uploaded_image.status_code, 200, uploaded_image.text)
            image_path = uploaded_image.json()["download_path"]
            self.assertEqual(client.get(image_path, headers=first_headers).status_code, 200)
            self.assertEqual(client.get(image_path, headers=second_headers).status_code, 404)
            image_list = client.get(f"/api/account/meetings/{meeting_id}/images", headers=first_headers)
            self.assertEqual(image_list.status_code, 200)
            self.assertEqual(image_list.json()[0]["filename"], "onsite.png")
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
            self.assertEqual(client.get(image_path, headers=first_headers).status_code, 404)


if __name__ == "__main__":
    unittest.main()
