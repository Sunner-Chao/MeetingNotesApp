#!/usr/bin/env python3
"""Runtime discovery for externally hosted social-login entry points."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Mapping
from urllib.parse import urlparse


PROVIDERS = (
    ("wechat", "微信", "WECHAT"),
    ("qq", "QQ", "QQ"),
    ("feishu", "飞书", "FEISHU"),
)


def _enabled(value: object) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


def _valid_login_url(value: object) -> str:
    url = str(value or "").strip()
    parsed = urlparse(url)
    return url if parsed.scheme in {"http", "https"} and parsed.netloc else ""


def _read_provider_file(path: Path | None) -> dict:
    if path is None or not path.is_file():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    providers = payload.get("providers", payload) if isinstance(payload, dict) else {}
    return providers if isinstance(providers, dict) else {}


def load_social_auth_providers(
    *,
    environ: Mapping[str, str] | None = None,
    config_path: Path | None = None,
) -> list[dict]:
    env = os.environ if environ is None else environ
    configured_path = str(env.get("ACCOUNT_AUTH_PROVIDERS_PATH", "")).strip()
    path = config_path or (Path(configured_path).expanduser().resolve() if configured_path else None)
    file_config = _read_provider_file(path)
    discovered: list[dict] = []

    for provider_id, default_name, env_name in PROVIDERS:
        item = file_config.get(provider_id, {})
        item = item if isinstance(item, dict) else {}
        enabled_value = env.get(f"ACCOUNT_AUTH_{env_name}_ENABLED", item.get("enabled", False))
        login_url = _valid_login_url(
            env.get(f"ACCOUNT_AUTH_{env_name}_LOGIN_URL", item.get("authorization_url", ""))
        )
        discovered.append(
            {
                "id": provider_id,
                "name": str(item.get("name", default_name)).strip() or default_name,
                "enabled": _enabled(enabled_value) and bool(login_url),
                "authorization_url": login_url,
            }
        )
    return discovered
