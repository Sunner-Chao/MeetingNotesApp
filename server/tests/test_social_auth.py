from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

from social_auth import load_social_auth_providers


class SocialAuthProviderTests(unittest.TestCase):
    def test_unconfigured_providers_are_visible_but_disabled(self) -> None:
        providers = load_social_auth_providers(environ={})

        self.assertEqual([item["id"] for item in providers], ["wechat", "feishu"])
        self.assertTrue(all(not item["enabled"] for item in providers))
        self.assertTrue(all(item["authorization_url"] == "" for item in providers))

    def test_environment_overrides_module_config(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "providers.json"
            config_path.write_text(
                json.dumps(
                    {
                        "providers": {
                            "wechat": {
                                "enabled": False,
                                "authorization_url": "https://config.example/wechat",
                            }
                        }
                    }
                ),
                encoding="utf-8",
            )
            providers = load_social_auth_providers(
                config_path=config_path,
                environ={
                    "ACCOUNT_AUTH_WECHAT_ENABLED": "true",
                    "ACCOUNT_AUTH_WECHAT_LOGIN_URL": "https://login.example/wechat",
                },
            )

        self.assertTrue(providers[0]["enabled"])
        self.assertEqual(providers[0]["authorization_url"], "https://login.example/wechat")
        self.assertFalse(providers[1]["enabled"])

    def test_enabled_flag_requires_a_valid_http_url(self) -> None:
        providers = load_social_auth_providers(
            environ={
                "ACCOUNT_AUTH_FEISHU_ENABLED": "true",
                "ACCOUNT_AUTH_FEISHU_LOGIN_URL": "javascript:alert(1)",
            }
        )

        self.assertFalse(providers[1]["enabled"])
        self.assertEqual(providers[1]["authorization_url"], "")


if __name__ == "__main__":
    unittest.main()
