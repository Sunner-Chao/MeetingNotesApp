#!/usr/bin/env python3
"""Runtime-configured social authentication provider registry.

The registry deliberately contains no client secrets. Provider credentials and
deployment-specific callback URLs are resolved from environment variables or a
private JSON file selected by ``ACCOUNT_AUTH_PROVIDERS_PATH``.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Mapping
from urllib.parse import urlencode, urlparse


PROVIDER_DEFINITIONS = {
    "wechat": ("微信", "WECHAT", "oauth2", "consumer"),
    "qq": ("QQ", "QQ", "oauth2", "consumer"),
    "feishu": ("飞书", "FEISHU", "oauth2", "team"),
    "telegram": ("Telegram", "TELEGRAM", "telegram", "consumer"),
    "whatsapp": ("WhatsApp", "WHATSAPP", "meta", "consumer"),
    "instagram": ("Instagram", "INSTAGRAM", "meta", "consumer"),
}
DEFAULT_PROVIDER_CONFIG_PATH = Path(__file__).resolve().parents[1] / "config" / "auth-providers.defaults.json"


def _enabled(value: object) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


def _valid_url(value: object) -> str:
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


def _config_path(env: Mapping[str, str], config_path: Path | None) -> Path | None:
    if config_path is not None:
        return config_path
    configured = str(env.get("ACCOUNT_AUTH_PROVIDERS_PATH", "")).strip()
    return Path(configured).expanduser().resolve() if configured else None


def _provider_ids(env: Mapping[str, str], file_config: Mapping[str, object]) -> list[str]:
    return list(PROVIDER_DEFINITIONS)


def _merged_provider_config(provider_id: str, item: Mapping[str, object], env: Mapping[str, str]) -> dict:
    default_name, env_name, protocol, tier = PROVIDER_DEFINITIONS[provider_id]

    def value(key: str, default: object = "") -> object:
        env_value = str(env.get(f"ACCOUNT_AUTH_{env_name}_{key.upper()}", "")).strip()
        return env_value if env_value else item.get(key, default)

    def mapping_value(key: str) -> dict[str, str]:
        raw = value(key, {})
        if isinstance(raw, str):
            try:
                raw = json.loads(raw)
            except json.JSONDecodeError:
                return {}
        if not isinstance(raw, Mapping):
            return {}
        return {str(name): str(setting) for name, setting in raw.items() if str(name).strip()}

    scopes_value = value("scopes", [])
    if isinstance(scopes_value, str):
        scopes = [part for part in scopes_value.replace(",", " ").split() if part]
    elif isinstance(scopes_value, list):
        scopes = [str(part).strip() for part in scopes_value if str(part).strip()]
    else:
        scopes = []
    enabled = _enabled(value("enabled", False))
    login_url = _valid_url(value("authorization_url", value("login_url", "")))
    client_id = str(value("client_id", "")).strip()
    client_secret_env = str(value("client_secret_env", "")).strip()
    client_secret = str(env.get(client_secret_env, "")).strip() if client_secret_env else str(value("client_secret", "")).strip()
    authorize_endpoint = _valid_url(value("authorize_endpoint", ""))
    token_endpoint = _valid_url(value("token_endpoint", ""))
    userinfo_endpoint = _valid_url(value("userinfo_endpoint", ""))
    subject_endpoint = _valid_url(value("subject_endpoint", ""))
    bot_token_env = str(value("bot_token_env", "")).strip()
    bot_token = str(env.get(bot_token_env, "")).strip() if bot_token_env else str(value("bot_token", "")).strip()
    bot_id = str(value("bot_id", "")).strip()
    if protocol in {"oauth2", "meta"}:
        ready = enabled and bool(client_id and client_secret and authorize_endpoint and token_endpoint and userinfo_endpoint)
    elif protocol == "telegram":
        ready = enabled and bool(bot_token and (login_url or bot_id))
    else:
        ready = enabled and bool(login_url)
    reason = ""
    if not ready:
        reason = (
            "需配置 Meta 应用及 WhatsApp/Instagram 官方登录权限" if protocol == "meta"
            else "需配置 Telegram Bot Token 与登录入口" if protocol == "telegram"
            else "管理员尚未完成该平台的服务端配置"
        )
    return {
        "id": provider_id,
        "name": str(value("name", default_name)).strip() or default_name,
        "enabled": ready,
        "configured": bool(enabled),
        "status": "available" if ready else "not_configured",
        "unavailable_reason": reason,
        "authorization_url": login_url,
        "protocol": protocol,
        "tier": str(value("tier", tier)).strip() or tier,
        "authorize_endpoint": authorize_endpoint,
        "token_endpoint": token_endpoint,
        "userinfo_endpoint": userinfo_endpoint,
        "subject_endpoint": subject_endpoint,
        "client_id": client_id,
        "client_secret": client_secret,
        "scopes": scopes,
        "bot_token": bot_token,
        "bot_id": bot_id,
        "subject_field": str(value("subject_field", "id")).strip() or "id",
        "display_name_field": str(value("display_name_field", "name")).strip() or "name",
        "avatar_field": str(value("avatar_field", "picture.data.url")).strip() or "picture.data.url",
        "token_field": str(value("token_field", "access_token")).strip() or "access_token",
        "token_method": str(value("token_method", "POST")).strip().upper() or "POST",
        "token_body_format": str(value("token_body_format", "form")).strip().lower() or "form",
        "token_response_format": str(value("token_response_format", "auto")).strip().lower() or "auto",
        "pkce_enabled": _enabled(value("pkce_enabled", True)),
        "token_subject_field": str(value("token_subject_field", "")).strip(),
        "token_client_id_param": str(value("token_client_id_param", "client_id")).strip() or "client_id",
        "token_client_secret_param": str(value("token_client_secret_param", "client_secret")).strip() or "client_secret",
        "authorize_client_id_param": str(value("authorize_client_id_param", "client_id")).strip() or "client_id",
        "authorize_url_suffix": str(value("authorize_url_suffix", "")).strip(),
        "authorize_extra_params": mapping_value("authorize_extra_params"),
        "token_extra_params": mapping_value("token_extra_params"),
        "subject_method": str(value("subject_method", "GET")).strip().upper() or "GET",
        "subject_response_format": str(value("subject_response_format", "auto")).strip().lower() or "auto",
        "subject_token_param": str(value("subject_token_param", "")).strip(),
        "subject_extra_params": mapping_value("subject_extra_params"),
        "userinfo_method": str(value("userinfo_method", "GET")).strip().upper() or "GET",
        "userinfo_response_format": str(value("userinfo_response_format", "auto")).strip().lower() or "auto",
        "userinfo_token_param": str(value("userinfo_token_param", "")).strip(),
        "userinfo_subject_param": str(value("userinfo_subject_param", "")).strip(),
        "userinfo_client_id_param": str(value("userinfo_client_id_param", "")).strip(),
        "userinfo_extra_params": mapping_value("userinfo_extra_params"),
    }


def load_social_auth_config(*, environ: Mapping[str, str] | None = None, config_path: Path | None = None) -> dict[str, dict]:
    env = os.environ if environ is None else environ
    defaults = _read_provider_file(DEFAULT_PROVIDER_CONFIG_PATH)
    overrides = _read_provider_file(_config_path(env, config_path))
    result: dict[str, dict] = {}
    for provider_id in _provider_ids(env, defaults):
        item = dict(defaults.get(provider_id, {})) if isinstance(defaults.get(provider_id), dict) else {}
        override = overrides.get(provider_id, {})
        if isinstance(override, dict):
            item.update(override)
        result[provider_id] = _merged_provider_config(
            provider_id,
            item,
            env,
        )
    return result


def load_social_auth_providers(*, environ: Mapping[str, str] | None = None, config_path: Path | None = None, public_base_url: str = "") -> list[dict]:
    base = public_base_url.rstrip("/")
    response = []
    for provider_id, item in load_social_auth_config(environ=environ, config_path=config_path).items():
        start_path = f"/api/auth/social/{provider_id}/start"
        response.append({
            "id": provider_id,
            "name": item["name"],
            "enabled": item["enabled"],
            "configured": item["configured"],
            "status": item["status"],
            "unavailable_reason": item["unavailable_reason"],
            "authorization_url": f"{base}{start_path}" if base else start_path,
            "start_path": start_path,
            "tier": item["tier"],
        })
    return response


def build_oauth_authorization_url(config: Mapping[str, object], *, state: str, redirect_uri: str, code_challenge: str = "") -> str:
    if str(config.get("protocol", "")) == "telegram":
        configured = str(config.get("authorization_url", "")).strip()
        if configured:
            return configured
        separator = "&" if "?" in redirect_uri else "?"
        return_to = f"{redirect_uri}{separator}{urlencode({'state': state})}"
        parsed_callback = urlparse(redirect_uri)
        origin = f"{parsed_callback.scheme}://{parsed_callback.netloc}"
        return "https://oauth.telegram.org/auth?" + urlencode({
            "bot_id": str(config.get("bot_id", "")),
            "origin": origin,
            "request_access": "write",
            "return_to": return_to,
        })
    configured = str(config.get("authorization_url", "")).strip()
    if configured:
        return configured
    client_id_param = str(config.get("authorize_client_id_param", "client_id")) or "client_id"
    params = {
        str(key): str(value)
        for key, value in config.get("authorize_extra_params", {}).items()
    }
    params.update({
        client_id_param: str(config.get("client_id", "")),
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": " ".join(str(value) for value in config.get("scopes", [])),
        "state": state,
    })
    if code_challenge and bool(config.get("pkce_enabled", True)):
        params.update({"code_challenge": code_challenge, "code_challenge_method": "S256"})
    suffix = str(config.get("authorize_url_suffix", ""))
    return f"{str(config.get('authorize_endpoint', '')).rstrip('?')}?{urlencode(params)}{suffix}"


def provider_field(config: Mapping[str, object], name: str, payload: Mapping[str, object], default: str = "") -> str:
    value: object = payload
    for part in str(config.get(name, "")).split("."):
        if not isinstance(value, Mapping):
            return default
        value = value.get(part)
    text = str(value or "").strip()
    return text or default
