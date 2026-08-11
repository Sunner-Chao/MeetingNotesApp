from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

import web_backend as backend
from account_service import AccountService
from agent_gateway import AgentGateway


class PwaRouteTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.db_path = root / "pwa-routes.db"
        self.pwa_dist = root / "pwa-dist"
        (self.pwa_dist / "assets").mkdir(parents=True)
        (self.pwa_dist / "index.html").write_text(
            "<!doctype html><title>智悟本轻享版</title><main id=\"root\"></main>",
            encoding="utf-8",
        )
        (self.pwa_dist / "assets" / "app.js").write_text(
            "window.__PWA_TEST__ = true;",
            encoding="utf-8",
        )
        plans_path = root / "plans.json"
        plans_path.write_text(json.dumps([]), encoding="utf-8")

        self.previous_db = backend.DB_PATH
        self.previous_gateway = backend.AGENT_GATEWAY
        self.previous_accounts = backend.ACCOUNT_SERVICE
        self.previous_pwa_dist = backend.PWA_DIST_DIR
        self.previous_stt_url = backend.STT_SERVICE_BASE_URL
        backend.DB_PATH = self.db_path
        backend.AGENT_GATEWAY = AgentGateway(
            db_path=self.db_path,
            work_root=root / "tasks",
            bootstrap_token="pwa-agent-token",
        )
        backend.ACCOUNT_SERVICE = AccountService(
            self.db_path,
            token_secret="pwa-account-secret",
            plans_path=plans_path,
        )
        backend.PWA_DIST_DIR = self.pwa_dist
        backend.STT_SERVICE_BASE_URL = "http://stt.internal.test"

    def tearDown(self) -> None:
        backend.DB_PATH = self.previous_db
        backend.AGENT_GATEWAY = self.previous_gateway
        backend.ACCOUNT_SERVICE = self.previous_accounts
        backend.PWA_DIST_DIR = self.previous_pwa_dist
        backend.STT_SERVICE_BASE_URL = self.previous_stt_url
        self.temp_dir.cleanup()

    def test_pwa_serves_index_assets_and_spa_fallback(self) -> None:
        with TestClient(backend.app) as client:
            index = client.get("/app/")
            fallback = client.get("/app/meeting/local-id")
            asset = client.get("/app/assets/app.js")
            missing_asset = client.get("/app/assets/missing.js")

        self.assertEqual(index.status_code, 200)
        self.assertIn("智悟本轻享版", index.text)
        self.assertEqual(index.headers["cache-control"], "no-cache")
        self.assertEqual(fallback.status_code, 200)
        self.assertIn("智悟本轻享版", fallback.text)
        self.assertEqual(asset.status_code, 200)
        self.assertIn("__PWA_TEST__", asset.text)
        self.assertEqual(asset.headers["cache-control"], "public, max-age=31536000, immutable")
        self.assertEqual(missing_asset.status_code, 404)

    def test_stt_proxy_requires_account_authentication(self) -> None:
        with TestClient(backend.app) as client:
            response = client.post(
                "/api/stt/transcribe",
                files={"file": ("sample.webm", b"audio", "audio/webm")},
            )

        self.assertEqual(response.status_code, 401)

    def test_stt_proxy_forwards_audio_with_fresh_account_token(self) -> None:
        forwarded: dict[str, object] = {}

        def capture_request(url: str, **kwargs: object) -> Mock:
            files = kwargs["files"]
            upload = files["file"]  # type: ignore[index]
            forwarded.update(
                url=url,
                headers=kwargs["headers"],
                timeout=kwargs["timeout"],
                filename=upload[0],
                content=upload[1].read(),
                content_type=upload[2],
            )
            response = Mock()
            response.ok = True
            response.status_code = 200
            response.json.return_value = {"text": "这是最终转写。"}
            return response

        with TestClient(backend.app) as client:
            registered = client.post(
                "/api/auth/register",
                json={"username": "pwa_user", "password": "strong-password"},
            )
            account_token = registered.json()["access_token"]
            principal = backend.ACCOUNT_SERVICE.authenticate(f"Bearer {account_token}")
            expected_stt_token = backend.ACCOUNT_SERVICE.session_credentials(principal)[
                "stt_access_token"
            ]

            with patch.object(backend.requests, "post", side_effect=capture_request) as request_post:
                response = client.post(
                    "/api/stt/transcribe",
                    headers={
                        "Authorization": f"Bearer {account_token}",
                        "X-Meeting-Id": "meeting-pwa-01",
                    },
                    files={"file": ("meeting.webm", b"recorded-audio", "audio/webm")},
                )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"text": "这是最终转写。"})
        request_post.assert_called_once()
        self.assertEqual(forwarded["url"], "http://stt.internal.test/transcribe")
        self.assertEqual(
            forwarded["headers"],
            {
                "Authorization": f"Bearer {expected_stt_token}",
                "X-Meeting-Id": "meeting-pwa-01",
            },
        )
        self.assertEqual(
            forwarded["timeout"],
            (backend.STT_PROXY_CONNECT_TIMEOUT_SEC, backend.STT_PROXY_READ_TIMEOUT_SEC),
        )
        self.assertEqual(forwarded["filename"], "meeting.webm")
        self.assertEqual(forwarded["content"], b"recorded-audio")
        self.assertEqual(forwarded["content_type"], "audio/webm")

    def test_audio_archive_proxy_uses_account_scoped_stt_token(self) -> None:
        with TestClient(backend.app) as client:
            registered = client.post(
                "/api/auth/register",
                json={"username": "archive_user", "password": "strong-password"},
            ).json()
            account_token = registered["access_token"]
            principal = backend.ACCOUNT_SERVICE.authenticate(f"Bearer {account_token}")
            expected_stt_token = backend.ACCOUNT_SERVICE.session_credentials(principal)[
                "stt_access_token"
            ]
            upstream = Mock()
            upstream.ok = True
            upstream.status_code = 200
            upstream.json.return_value = {
                "items": [
                    {
                        "id": "a" * 32,
                        "meeting_id": "meeting-archive-01",
                        "filename": "meeting.wav",
                    }
                ]
            }
            with patch.object(backend.requests, "get", return_value=upstream) as request_get:
                response = client.get(
                    "/api/stt/audio-archive",
                    headers={"Authorization": f"Bearer {account_token}"},
                    params={"meeting_id": "meeting-archive-01"},
                )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["items"][0]["id"], "a" * 32)
        request_get.assert_called_once_with(
            "http://stt.internal.test/audio-archive",
            headers={"Authorization": f"Bearer {expected_stt_token}"},
            params={"meeting_id": "meeting-archive-01"},
            timeout=(backend.STT_PROXY_CONNECT_TIMEOUT_SEC, backend.STT_PROXY_READ_TIMEOUT_SEC),
        )


if __name__ == "__main__":
    unittest.main()
