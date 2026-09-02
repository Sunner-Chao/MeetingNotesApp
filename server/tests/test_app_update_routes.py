import json
import sys
import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

import web_backend as backend


class AppUpdateRouteTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.previous_config = backend.APP_UPDATE_CONFIG_PATH
        self.previous_apk = backend.APP_UPDATE_ANDROID_APK_PATH
        self.previous_light_config = backend.APP_UPDATE_LIGHT_CONFIG_PATH
        self.previous_light_apk = backend.APP_UPDATE_LIGHT_ANDROID_APK_PATH
        self.previous_web_token = backend.WEB_API_TOKEN
        backend.APP_UPDATE_CONFIG_PATH = Path(self.temp_dir.name) / "app-update.json"
        backend.APP_UPDATE_ANDROID_APK_PATH = Path(self.temp_dir.name) / "ZhiWuBen-Android.apk"
        backend.APP_UPDATE_LIGHT_CONFIG_PATH = Path(self.temp_dir.name) / "app-update-light.json"
        backend.APP_UPDATE_LIGHT_ANDROID_APK_PATH = Path(self.temp_dir.name) / "light" / "ZhiWuBen-Android.apk"
        backend.WEB_API_TOKEN = "test-protected-backend"

    def tearDown(self) -> None:
        backend.APP_UPDATE_CONFIG_PATH = self.previous_config
        backend.APP_UPDATE_ANDROID_APK_PATH = self.previous_apk
        backend.APP_UPDATE_LIGHT_CONFIG_PATH = self.previous_light_config
        backend.APP_UPDATE_LIGHT_ANDROID_APK_PATH = self.previous_light_apk
        backend.WEB_API_TOKEN = self.previous_web_token
        self.temp_dir.cleanup()

    def test_update_is_unpublished_without_a_manifest_and_apk(self) -> None:
        with TestClient(backend.app) as client:
            response = client.get("/api/app-update/android")
            self.assertEqual(response.status_code, 204)
            self.assertEqual(response.headers["cache-control"], "no-store")

    def test_metadata_and_apk_are_served_after_publication(self) -> None:
        backend.APP_UPDATE_CONFIG_PATH.write_text(
            json.dumps({"version_code": 10210, "version_name": "1.2.10", "mandatory": False}),
            encoding="utf-8",
        )
        backend.APP_UPDATE_ANDROID_APK_PATH.write_bytes(b"apk-bytes")
        with TestClient(backend.app) as client:
            response = client.get("/api/app-update/android")
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.headers["cache-control"], "no-store")
            self.assertEqual(response.json()["version_code"], 10210)
            self.assertTrue(response.json()["download_url"].endswith("/api/app-update/android/apk/10210"))
            apk_response = client.get("/api/app-update/android/apk/10210")
            self.assertEqual(apk_response.status_code, 200)
            self.assertEqual(apk_response.content, b"apk-bytes")

    def test_versioned_artifact_remains_available_after_next_release(self) -> None:
        previous_apk = backend.APP_UPDATE_ANDROID_APK_PATH.parent / "ZhiWuBen-Android-10210.apk"
        previous_apk.write_bytes(b"previous-apk")
        backend.APP_UPDATE_ANDROID_APK_PATH.parent.joinpath("ZhiWuBen-Android-10211.apk").write_bytes(b"current-apk")
        backend.APP_UPDATE_CONFIG_PATH.write_text(
            json.dumps(
                {
                    "version_code": 10211,
                    "version_name": "1.2.11",
                    "apk_filename": "ZhiWuBen-Android-10211.apk",
                }
            ),
            encoding="utf-8",
        )
        with TestClient(backend.app) as client:
            previous_response = client.get("/api/app-update/android/apk/10210")
            self.assertEqual(previous_response.status_code, 200)
            self.assertEqual(previous_response.content, b"previous-apk")
            current_response = client.get("/api/app-update/android/apk/10211")
            self.assertEqual(current_response.status_code, 200)
            self.assertEqual(current_response.content, b"current-apk")

    def test_only_current_and_immediately_previous_artifacts_are_downloadable(self) -> None:
        for version_code in (10209, 10210, 10211):
            backend.APP_UPDATE_ANDROID_APK_PATH.parent.joinpath(
                f"ZhiWuBen-Android-{version_code}.apk"
            ).write_bytes(f"apk-{version_code}".encode())
        backend.APP_UPDATE_CONFIG_PATH.write_text(
            json.dumps(
                {
                    "version_code": 10211,
                    "version_name": "1.2.11",
                    "apk_filename": "ZhiWuBen-Android-10211.apk",
                }
            ),
            encoding="utf-8",
        )

        with TestClient(backend.app) as client:
            self.assertEqual(client.get("/api/app-update/android/apk/10211").status_code, 200)
            self.assertEqual(client.get("/api/app-update/android/apk/10210").status_code, 200)
            self.assertEqual(client.get("/api/app-update/android/apk/10209").status_code, 404)

    def test_apk_directory_lists_current_and_previous_downloads(self) -> None:
        for version_code in (10209, 10210, 10211):
            backend.APP_UPDATE_ANDROID_APK_PATH.parent.joinpath(
                f"ZhiWuBen-Android-{version_code}.apk"
            ).write_bytes(f"apk-{version_code}".encode())
        backend.APP_UPDATE_CONFIG_PATH.write_text(
            json.dumps(
                {
                    "version_code": 10211,
                    "version_name": "1.2.11",
                    "release_notes": "Directory page release notes",
                    "sha256": "a" * 64,
                    "apk_filename": "ZhiWuBen-Android-10211.apk",
                }
            ),
            encoding="utf-8",
        )

        with TestClient(backend.app) as client:
            response = client.get("/api/app-update/android/apk/")

        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.headers["content-type"].startswith("text/html"))
        self.assertEqual(response.headers["cache-control"], "no-store")
        self.assertIn("智悟本 Android 发布中心", response.text)
        self.assertIn("ZhiWuBen-Android-10211.apk", response.text)
        self.assertIn("ZhiWuBen-Android-10210.apk", response.text)
        self.assertNotIn("ZhiWuBen-Android-10209.apk", response.text)
        self.assertIn("当前版本", response.text)
        self.assertIn("上一版本", response.text)
        self.assertIn("北京时间", response.text)
        self.assertNotIn("UTC", response.text)
        self.assertIn('href="http://testserver/api/app-update/android/apk/10211"', response.text)
        self.assertIn('data-copy-url="http://testserver/api/app-update/android/apk/10210"', response.text)

    def test_apk_directory_shows_an_empty_state_without_a_release(self) -> None:
        with TestClient(backend.app) as client:
            response = client.get("/api/app-update/android/apk/")

        self.assertEqual(response.status_code, 200)
        self.assertIn("暂无可下载版本", response.text)
        self.assertIn("0 个", response.text)

    def test_manifest_rejects_an_unsafe_artifact_filename(self) -> None:
        backend.APP_UPDATE_CONFIG_PATH.write_text(
            json.dumps(
                {
                    "version_code": 10210,
                    "version_name": "1.2.10",
                    "apk_filename": "../other.apk",
                }
            ),
            encoding="utf-8",
        )
        backend.APP_UPDATE_ANDROID_APK_PATH.write_bytes(b"apk-bytes")
        with TestClient(backend.app) as client:
            self.assertEqual(client.get("/api/app-update/android").status_code, 204)

    def test_light_channel_metadata_and_versioned_download_are_isolated(self) -> None:
        backend.APP_UPDATE_LIGHT_ANDROID_APK_PATH.parent.mkdir(parents=True)
        backend.APP_UPDATE_LIGHT_ANDROID_APK_PATH.parent.joinpath(
            "ZhiWuBen-Android-10259.apk"
        ).write_bytes(b"light-apk")
        backend.APP_UPDATE_LIGHT_CONFIG_PATH.write_text(
            json.dumps(
                {
                    "version_code": 10259,
                    "version_name": "1.2.59",
                    "mandatory": False,
                    "release_notes": "Light Enjoy",
                    "apk_filename": "ZhiWuBen-Android-10259.apk",
                }
            ),
            encoding="utf-8",
        )
        with TestClient(backend.app) as client:
            response = client.get("/api/app-update/android/light")
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["version_code"], 10259)
            self.assertTrue(
                response.json()["download_url"].endswith(
                    "/api/app-update/android/light/apk/10259"
                )
            )
            apk_response = client.get("/api/app-update/android/light/apk/10259")
            self.assertEqual(apk_response.status_code, 200)
            self.assertEqual(apk_response.content, b"light-apk")
            social_response = client.get("/api/app-update/android")
            self.assertEqual(social_response.status_code, 204)


if __name__ == "__main__":
    unittest.main()
