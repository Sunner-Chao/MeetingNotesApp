"""Stateless, short-lived STT tokens issued for authenticated app accounts."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time


TOKEN_PREFIX = "mn_stt_user_v1."


def issue_account_stt_token(token_secret: str, user_id: str, expires_at: int) -> str:
    clean_secret = token_secret.strip()
    clean_user_id = user_id.strip()
    if not clean_secret or not clean_user_id:
        raise ValueError("Account STT token requires a configured secret and user id")

    payload = json.dumps(
        {"exp": int(expires_at), "sub": clean_user_id},
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    encoded_payload = _encode(payload)
    signature = hmac.new(
        clean_secret.encode("utf-8"),
        encoded_payload.encode("ascii"),
        hashlib.sha256,
    ).digest()
    return f"{TOKEN_PREFIX}{encoded_payload}.{_encode(signature)}"


def verify_account_stt_token(
    token_secret: str,
    token: str,
    *,
    now: int | None = None,
) -> str | None:
    clean_secret = token_secret.strip()
    if not clean_secret or not token.startswith(TOKEN_PREFIX):
        return None
    try:
        encoded_payload, encoded_signature = token[len(TOKEN_PREFIX):].split(".", 1)
        expected_signature = hmac.new(
            clean_secret.encode("utf-8"),
            encoded_payload.encode("ascii"),
            hashlib.sha256,
        ).digest()
        actual_signature = _decode(encoded_signature)
        if not hmac.compare_digest(actual_signature, expected_signature):
            return None
        payload = json.loads(_decode(encoded_payload).decode("utf-8"))
        user_id = str(payload.get("sub", "")).strip()
        expires_at = int(payload.get("exp", 0))
        if not user_id or expires_at <= int(time.time() if now is None else now):
            return None
        return user_id
    except (TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def _encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
