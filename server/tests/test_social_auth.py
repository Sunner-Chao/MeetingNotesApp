from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

from social_auth import build_oauth_authorization_url, load_social_auth_config, load_social_auth_providers


class SocialAuthProviderTests(unittest.TestCase):
    def test_unconfigured_providers_are_visible_but_disabled(self) -> None:
        providers = load_social_auth_providers(environ={})
        config = load_social_auth_config(environ={})

        self.assertEqual(
            [item["id"] for item in providers],
            ["wechat", "qq", "feishu", "telegram", "whatsapp", "instagram"],
        )
        self.assertTrue(all(not item["enabled"] for item in providers))
        self.assertTrue(all(item["status"] == "not_configured" for item in providers))
        self.assertTrue(all(item["authorization_url"].startswith("/api/auth/social/") for item in providers))
        self.assertEqual(config["wechat"]["authorize_endpoint"], "https://open.weixin.qq.com/connect/qrconnect")
        self.assertEqual(config["qq"]["subject_response_format"], "jsonp")
        self.assertEqual(config["feishu"]["subject_field"], "data.open_id")
        self.assertEqual(config["telegram"]["bot_token"], "")
        self.assertEqual(config["whatsapp"]["userinfo_endpoint"], "https://graph.facebook.com/v23.0/me")
        self.assertEqual(config["instagram"]["userinfo_extra_params"], {"fields": "id,name,picture"})

    def test_environment_overrides_module_config(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "providers.json"
            config_path.write_text(
                json.dumps(
                    {
                        "providers": {
                            "wechat": {
                                "enabled": False,
                                "authorize_endpoint": "https://config.example/authorize",
                                "token_endpoint": "https://config.example/token",
                                "userinfo_endpoint": "https://config.example/user",
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
                    "ACCOUNT_AUTH_WECHAT_CLIENT_ID": "runtime-client",
                    "ACCOUNT_AUTH_WECHAT_CLIENT_SECRET": "runtime-secret",
                    "ACCOUNT_AUTH_WECHAT_AUTHORIZE_ENDPOINT": "https://login.example/authorize",
                },
            )
            internal = load_social_auth_config(
                config_path=config_path,
                environ={
                    "ACCOUNT_AUTH_WECHAT_ENABLED": "true",
                    "ACCOUNT_AUTH_WECHAT_CLIENT_ID": "runtime-client",
                    "ACCOUNT_AUTH_WECHAT_CLIENT_SECRET": "runtime-secret",
                    "ACCOUNT_AUTH_WECHAT_AUTHORIZE_ENDPOINT": "https://login.example/authorize",
                },
            )["wechat"]

            self.assertTrue(providers[0]["enabled"])
            self.assertEqual(providers[0]["authorization_url"], "/api/auth/social/wechat/start")
            self.assertFalse(providers[1]["enabled"])
            self.assertEqual(internal["client_id"], "runtime-client")
            self.assertEqual(internal["authorize_endpoint"], "https://login.example/authorize")

    def test_enabled_flag_requires_a_valid_http_url(self) -> None:
        providers = load_social_auth_providers(
            environ={
                "ACCOUNT_AUTH_FEISHU_ENABLED": "true",
                "ACCOUNT_AUTH_FEISHU_CLIENT_ID": "client",
                "ACCOUNT_AUTH_FEISHU_CLIENT_SECRET": "secret",
                "ACCOUNT_AUTH_FEISHU_AUTHORIZE_ENDPOINT": "javascript:alert(1)",
            }
        )

        self.assertFalse(providers[2]["enabled"])
        self.assertEqual(providers[2]["status"], "not_configured")

    def test_wechat_parameter_names_are_configuration_driven(self) -> None:
        config = {
            "authorize_endpoint": "https://login.example/authorize",
            "authorize_client_id_param": "appid",
            "authorize_url_suffix": "#wechat_redirect",
            "pkce_enabled": False,
            "client_id": "runtime-app-id",
            "scopes": ["snsapi_login"],
            "protocol": "oauth2",
        }

        url = build_oauth_authorization_url(
            config,
            state="state-value",
            redirect_uri="https://auth.example/callback",
            code_challenge="challenge-value",
        )

        self.assertIn("appid=runtime-app-id", url)
        self.assertNotIn("client_id=", url)
        self.assertNotIn("code_challenge=", url)
        self.assertTrue(url.endswith("#wechat_redirect"))

    def test_extra_parameter_maps_can_be_overridden_from_environment(self) -> None:
        config = load_social_auth_config(
            environ={
                "ACCOUNT_AUTH_INSTAGRAM_USERINFO_EXTRA_PARAMS": '{"fields":"id,username"}',
                "ACCOUNT_AUTH_INSTAGRAM_AUTHORIZE_EXTRA_PARAMS": '{"display":"page"}',
            }
        )["instagram"]

        self.assertEqual(config["userinfo_extra_params"], {"fields": "id,username"})
        self.assertEqual(config["authorize_extra_params"], {"display": "page"})


if __name__ == "__main__":
    unittest.main()
