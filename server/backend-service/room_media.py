"""Self-hosted LiveKit adapter. Credentials never leave the account server."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
from dataclasses import dataclass, field
from urllib.parse import urlparse

import requests
from fastapi import HTTPException


@dataclass(frozen=True)
class MediaSettings:
    url: str = ""
    api_url: str = ""
    api_key: str = field(default="", repr=False)
    api_secret: str = field(default="", repr=False)

    @classmethod
    def from_env(cls):
        return cls(*(os.getenv(name, "").strip() for name in (
            "MEETINGNOTES_RTC_URL", "MEETINGNOTES_RTC_API_URL",
            "MEETINGNOTES_RTC_API_KEY", "MEETINGNOTES_RTC_API_SECRET",
        )))

    @property
    def configured(self) -> bool:
        public = urlparse(self.url)
        internal = urlparse(self.api_url)
        secure = public.scheme == "wss" or (
            public.scheme == "ws" and public.hostname in {"127.0.0.1", "localhost", "::1"}
        )
        return bool(secure and public.hostname and not public.username and not public.query and
                    internal.scheme in {"http", "https"} and internal.hostname and
                    not internal.username and not internal.query and self.api_key and len(self.api_secret) >= 32)


def encode_token(settings: MediaSettings, claims: dict) -> str:
    """LiveKit's documented HS256 access-token format; short-lived and room scoped."""
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")
    message = encode({"alg": "HS256", "typ": "JWT"}) + b"." + encode(claims)
    signature = hmac.new(settings.api_secret.encode(), message, hashlib.sha256).digest()
    return (message + b"." + base64.urlsafe_b64encode(signature).rstrip(b"=")).decode()


class RoomMediaService:
    def __init__(self, settings: MediaSettings | None = None):
        self.settings = settings or MediaSettings.from_env()

    @property
    def configured(self):
        return self.settings.configured

    @staticmethod
    def room_name(room_id: str):
        return "meeting-" + room_id

    def rpc(self, method: str, payload: dict, grants: dict, *, missing_ok=False):
        if not self.configured:
            raise HTTPException(503, "多人通话服务尚未配置")
        now = int(time.time())
        token = encode_token(self.settings, {
            "iss": self.settings.api_key, "nbf": now - 5, "exp": now + 60,
            "video": grants,
        })
        try:
            response = requests.post(
                self.settings.api_url.rstrip("/") + "/twirp/livekit.RoomService/" + method,
                json=payload, headers={"Authorization": "Bearer " + token}, timeout=(3, 5),
            )
            if missing_ok and response.status_code == 404:
                return {}
            response.raise_for_status()
            return response.json()
        except (requests.RequestException, ValueError):
            # Provider errors can contain URLs, headers or tokens. Never relay them.
            raise HTTPException(503, "通话服务暂时无法连接，请稍后重试") from None

    def connect(self, room: dict, user_id: str, display_name: str):
        name = self.room_name(room["id"])
        self.rpc("CreateRoom", {"name": name, "empty_timeout": 300, "departure_timeout": 30,
                                "max_participants": 16}, {"roomCreate": True})
        now = int(time.time())
        token = encode_token(self.settings, {
            "iss": self.settings.api_key, "sub": user_id, "name": display_name,
            "nbf": now - 5, "exp": now + 60,
            "video": {"roomJoin": True, "room": name, "canPublish": True,
                      "canPublishSources": ["microphone"], "canSubscribe": True,
                      "canPublishData": False, "canUpdateOwnMetadata": False},
        })
        return {"url": self.settings.url, "token": token, "room_name": name,
                "identity": user_id, "expires_at": now + 60}

    def remove(self, room_id: str, user_id: str):
        if self.configured:
            name = self.room_name(room_id)
            self.rpc("RemoveParticipant", {"room": name, "identity": user_id},
                     {"roomAdmin": True, "room": name}, missing_ok=True)

    def end(self, room_id: str):
        if self.configured:
            name = self.room_name(room_id)
            self.rpc("DeleteRoom", {"room": name}, {"roomCreate": True}, missing_ok=True)
