#!/usr/bin/env python3
"""MeetingNotesApp Backend Service and local operations console."""

from __future__ import annotations

import base64
import binascii
import hmac
import json
import os
import re
import sqlite3
import uuid
from contextlib import asynccontextmanager, contextmanager
from html import escape as html_escape
from pathlib import Path
from typing import Annotated, Iterator

import requests
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from pydantic import BaseModel, Field
from starlette.background import BackgroundTask
from starlette.responses import StreamingResponse

from agent_gateway import (
    PROVIDERS,
    AgentError,
    AgentPrincipal,
    IncomingAttachment,
    gateway_from_env,
)
from account_service import (
    AccountError,
    AccountPrincipal,
    AccountService,
)
from social_auth import load_social_auth_providers
from community_api import build_community_router, build_public_community_router


def _env(name: str, default: str) -> str:
    value = os.getenv(name, default).strip()
    return value or default


def _env_bool(name: str, default: bool) -> bool:
    fallback = "true" if default else "false"
    return _env(name, fallback).lower() not in {"0", "false", "no", "off"}


HOST = _env("WEB_BACKEND_HOST", "0.0.0.0")
PORT = int(_env("WEB_BACKEND_PORT", "8090"))
DB_PATH = Path(_env("WEB_BACKEND_DB_PATH", "./data/meeting_notes.db")).resolve()
STT_SERVICE_BASE_URL = _env("STT_SERVICE_BASE_URL", "http://127.0.0.1:8888").rstrip("/")
PUBLIC_STT_URL = os.getenv("PUBLIC_STT_URL", "").strip().rstrip("/")
STT_LOG_PATH = Path(_env("STT_LOG_PATH", "../stt-service/logs/stt.log")).resolve()
STT_API_TOKEN = os.getenv("STT_API_TOKEN", "").strip()
STT_SWITCH_TIMEOUT_SEC = float(_env("STT_SWITCH_TIMEOUT_SEC", "900"))
STT_PROXY_CONNECT_TIMEOUT_SEC = float(_env("STT_PROXY_CONNECT_TIMEOUT_SEC", "15"))
STT_PROXY_READ_TIMEOUT_SEC = float(_env("STT_PROXY_READ_TIMEOUT_SEC", "1800"))
WEB_API_TOKEN = os.getenv("WEB_API_TOKEN", "").strip()
WEB_API_USERNAME = _env("WEB_API_USERNAME", "admin")
COMMUNITY_WRITE_ENABLED = _env_bool("COMMUNITY_WRITE_ENABLED", True)
SERVER_ROOT = Path(__file__).resolve().parent.parent
DASHBOARD_TEMPLATE_PATH = Path(__file__).with_name("dashboard.html")


def _default_pwa_dist_dir() -> Path:
    candidates = (
        SERVER_ROOT / "pwa-dist",
        SERVER_ROOT.parent / "pwa" / "dist",
    )
    return next(
        (candidate for candidate in candidates if (candidate / "index.html").is_file()),
        candidates[0],
    )


PWA_DIST_DIR = Path(
    _env(
        "PWA_DIST_DIR",
        str(_default_pwa_dist_dir()),
    )
).resolve()
ACCOUNT_TOKEN_SECRET = os.getenv("ACCOUNT_TOKEN_SECRET", "").strip()
ACCOUNT_ADMIN_USERNAME = _env("ACCOUNT_ADMIN_USERNAME", "admin")
ACCOUNT_ADMIN_PASSWORD = os.getenv("ACCOUNT_ADMIN_PASSWORD", "")
ACCOUNT_ADMIN_REQUEST_LIMIT = int(_env("ACCOUNT_ADMIN_REQUEST_LIMIT", "10000000"))
ACCOUNT_SESSION_TTL_SEC = int(_env("ACCOUNT_SESSION_TTL_SEC", "2592000"))
ACCOUNT_FREE_REQUEST_LIMIT = int(_env("ACCOUNT_FREE_REQUEST_LIMIT", "10"))
ACCOUNT_FREE_PLAN_CODE = _env("ACCOUNT_FREE_PLAN_CODE", "free")
ACCOUNT_FREE_PLAN_NAME = _env("ACCOUNT_FREE_PLAN_NAME", "Free")
ACCOUNT_STT_TOKEN_TTL_SEC = int(_env("ACCOUNT_STT_TOKEN_TTL_SEC", "43200"))
ACCOUNT_PROFILE_NAME_MAX_LENGTH = max(
    1, int(_env("ACCOUNT_PROFILE_NAME_MAX_LENGTH", "40"))
)
ACCOUNT_PROFILE_AVATAR_MAX_BYTES = max(
    1, int(_env("ACCOUNT_PROFILE_AVATAR_MAX_BYTES", "262144"))
)
ACCOUNT_PROFILE_AVATAR_DATA_URL_MAX_LENGTH = max(
    128,
    int(
        _env(
            "ACCOUNT_PROFILE_AVATAR_DATA_URL_MAX_LENGTH",
            str(ACCOUNT_PROFILE_AVATAR_MAX_BYTES * 2),
        )
    ),
)
AGENT_MAX_REQUEST_JSON_BYTES = max(0, int(_env("AGENT_MAX_REQUEST_JSON_BYTES", "0")))
ACCOUNT_PLANS_PATH = Path(
    _env("ACCOUNT_PLANS_PATH", str(Path(__file__).resolve().parent.parent / "config" / "account-plans.json"))
).resolve()
APP_UPDATE_CONFIG_PATH = Path(
    _env("APP_UPDATE_CONFIG_PATH", str(SERVER_ROOT / "config" / "app-update.json"))
).resolve()
APP_UPDATE_ANDROID_APK_PATH = Path(
    _env("APP_UPDATE_ANDROID_APK_PATH", str(SERVER_ROOT / "downloads" / "ZhiWuBen-Android.apk"))
).resolve()


def _release_value(filename: str, default: str) -> str:
    try:
        return (SERVER_ROOT / filename).read_text(encoding="utf-8").strip() or default
    except OSError:
        return default


SERVER_VERSION = os.getenv("MEETINGNOTES_SERVER_VERSION", _release_value("VERSION", "dev")).strip()
SERVER_RELEASE = os.getenv("MEETINGNOTES_RELEASE_ID", _release_value("RELEASE", SERVER_VERSION)).strip()
DB_PATH.parent.mkdir(parents=True, exist_ok=True)
AGENT_GATEWAY = gateway_from_env(DB_PATH)
ACCOUNT_SERVICE = AccountService(
    DB_PATH,
    token_secret=ACCOUNT_TOKEN_SECRET,
    plans_path=ACCOUNT_PLANS_PATH,
    session_ttl_sec=ACCOUNT_SESSION_TTL_SEC,
    admin_username=ACCOUNT_ADMIN_USERNAME,
    admin_password=ACCOUNT_ADMIN_PASSWORD,
    admin_request_limit=ACCOUNT_ADMIN_REQUEST_LIMIT,
    free_request_limit=ACCOUNT_FREE_REQUEST_LIMIT,
    free_plan_code=ACCOUNT_FREE_PLAN_CODE,
    free_plan_name=ACCOUNT_FREE_PLAN_NAME,
    stt_token_ttl_sec=ACCOUNT_STT_TOKEN_TTL_SEC,
    profile_name_max_length=ACCOUNT_PROFILE_NAME_MAX_LENGTH,
    profile_avatar_max_bytes=ACCOUNT_PROFILE_AVATAR_MAX_BYTES,
) if ACCOUNT_TOKEN_SECRET else None


def stt_headers() -> dict[str, str]:
    if not STT_API_TOKEN:
        return {}
    return {"Authorization": f"Bearer {STT_API_TOKEN}"}


@contextmanager
def db_conn() -> Iterator[sqlite3.Connection]:
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 30000")
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_db() -> None:
    with db_conn() as conn:
        conn.execute("PRAGMA journal_mode = WAL")
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS meetings (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                audio_file_path TEXT
            );

            CREATE TABLE IF NOT EXISTS transcripts (
                id TEXT PRIMARY KEY,
                meeting_id TEXT NOT NULL,
                speaker_name TEXT,
                content TEXT NOT NULL,
                start_time_ms INTEGER NOT NULL DEFAULT 0,
                end_time_ms INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(meeting_id) REFERENCES meetings(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS reports (
                id TEXT PRIMARY KEY,
                meeting_id TEXT NOT NULL UNIQUE,
                summary TEXT NOT NULL DEFAULT '',
                key_points TEXT NOT NULL DEFAULT '[]',
                tasks TEXT NOT NULL DEFAULT '[]',
                decisions TEXT NOT NULL DEFAULT '[]',
                action_items TEXT NOT NULL DEFAULT '[]',
                generated_at INTEGER NOT NULL,
                FOREIGN KEY(meeting_id) REFERENCES meetings(id) ON DELETE CASCADE
            );
            """
        )


class MeetingPayload(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    created_at: int
    duration_ms: int = Field(default=0, ge=0)
    audio_file_path: str | None = Field(default=None, max_length=2048)


class TranscriptPayload(BaseModel):
    meeting_id: str = Field(min_length=1, max_length=100)
    speaker_name: str | None = Field(default=None, max_length=100)
    content: str = Field(min_length=1)
    start_time_ms: int = Field(default=0, ge=0)
    end_time_ms: int = Field(default=0, ge=0)
    created_at: int


class ReportPayload(BaseModel):
    meeting_id: str = Field(min_length=1, max_length=100)
    summary: str = Field(default="")
    key_points: list[str] = Field(default_factory=list)
    tasks: list[dict] = Field(default_factory=list)
    decisions: list[str] = Field(default_factory=list)
    action_items: list[str] = Field(default_factory=list)
    generated_at: int


class SwitchSTTPayload(BaseModel):
    engine: str = Field(pattern="^(faster-whisper|sensevoice)$")
    model: str | None = None


class AgentTokenCreatePayload(BaseModel):
    label: str = Field(min_length=1, max_length=100)
    request_limit: int = Field(default=1000, ge=1, le=10_000_000)
    allowed_providers: list[str] = Field(default_factory=lambda: sorted(PROVIDERS))
    expires_at: int | None = None


class AgentTokenStatePayload(BaseModel):
    enabled: bool


class AccountCredentialsPayload(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    password: str = Field(min_length=8, max_length=128)


class AccountProfileUpdatePayload(BaseModel):
    display_name: str = Field(default="", max_length=ACCOUNT_PROFILE_NAME_MAX_LENGTH)
    avatar_data_url: str | None = Field(
        default=None,
        max_length=ACCOUNT_PROFILE_AVATAR_DATA_URL_MAX_LENGTH,
    )


class RechargeOrderCreatePayload(BaseModel):
    plan_code: str = Field(min_length=1, max_length=64)


class AccountUserStatePayload(BaseModel):
    enabled: bool


class AccountMeetingPayload(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    template_key: str = Field(pattern="^(project|administrative|brainstorming|inspection)$")
    created_at: int = Field(ge=0)
    updated_at: int = Field(ge=0)
    duration_seconds: int = Field(default=0, ge=0)
    transcript: str = ""
    report: str = ""


def normalized_account_meeting_id(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", value):
        raise HTTPException(status_code=400, detail="会议 ID 格式无效")
    return value


@asynccontextmanager
async def app_lifespan(_app: FastAPI):
    init_db()
    AGENT_GATEWAY.initialize()
    if ACCOUNT_SERVICE is not None:
        ACCOUNT_SERVICE.initialize()
    yield


app = FastAPI(title="Meeting Notes Web Backend", lifespan=app_lifespan)


def is_web_request_authorized(authorization: str | None) -> bool:
    if not WEB_API_TOKEN:
        return True
    if not authorization:
        return False
    if authorization.startswith("Bearer "):
        return hmac.compare_digest(authorization[7:], WEB_API_TOKEN)
    if authorization.startswith("Basic "):
        try:
            decoded = base64.b64decode(authorization[6:], validate=True).decode("utf-8")
            username, password = decoded.split(":", 1)
        except (binascii.Error, ValueError, UnicodeDecodeError):
            return False
        return username == WEB_API_USERNAME and hmac.compare_digest(password, WEB_API_TOKEN)
    return False


@app.middleware("http")
async def authenticate_web_api(request: Request, call_next):
    is_agent_path = request.url.path == "/api/agent" or request.url.path.startswith("/api/agent/")
    is_public_account_path = request.url.path in {
        "/api/auth/register",
        "/api/auth/login",
        "/api/auth/providers",
    }
    is_user_account_path = request.url.path.startswith("/api/account/")
    is_account_admin_path = request.url.path.startswith("/api/admin/accounts/")
    is_user_stt_path = request.url.path.startswith("/api/stt/")
    is_public_update_path = request.url.path == "/api/app-update/android" or request.url.path.startswith(
        "/api/app-update/android/"
    )
    is_public_community_path = request.url.path == "/api/community" or request.url.path.startswith(
        "/api/community/"
    )
    is_pwa_path = request.url.path == "/app" or request.url.path.startswith("/app/")
    if (
        request.url.path == "/health"
        or is_pwa_path
        or is_agent_path
        or is_public_account_path
        or is_user_account_path
        or is_account_admin_path
        or is_user_stt_path
        or is_public_update_path
        or is_public_community_path
        or is_web_request_authorized(request.headers.get("authorization"))
    ):
        return await call_next(request)
    return JSONResponse(
        status_code=401,
        content={"detail": "Missing or invalid API credentials"},
        headers={"WWW-Authenticate": 'Basic realm="Meeting Notes Admin"'},
    )


def require_agent_principal(
    authorization: Annotated[str | None, Header()] = None,
) -> AgentPrincipal:
    try:
        return AGENT_GATEWAY.authenticate(authorization)
    except AgentError as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail=str(exc),
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc


def agent_http_error(exc: AgentError) -> HTTPException:
    return HTTPException(status_code=exc.status_code, detail=str(exc))


def configured_account_service() -> AccountService:
    if ACCOUNT_SERVICE is None:
        raise HTTPException(status_code=503, detail="Account service is not configured")
    return ACCOUNT_SERVICE


def require_account_principal(
    authorization: Annotated[str | None, Header()] = None,
) -> AccountPrincipal:
    service = configured_account_service()
    try:
        return service.authenticate(authorization)
    except AccountError as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail=str(exc),
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc


def account_http_error(exc: AccountError) -> HTTPException:
    return HTTPException(status_code=exc.status_code, detail=str(exc))


app.include_router(build_community_router(lambda: DB_PATH, require_account_principal))
app.include_router(build_public_community_router(lambda: DB_PATH))


@app.get("/", response_class=HTMLResponse)
@app.get("/web", response_class=HTMLResponse)
def index() -> str:
    template = DASHBOARD_TEMPLATE_PATH.read_text(encoding="utf-8")
    replacements = {
        "__BACKEND_PORT__": str(PORT),
        "__STT_BASE_URL__": STT_SERVICE_BASE_URL,
        "__PUBLIC_STT_URL__": PUBLIC_STT_URL or "未配置",
        "__DB_PATH__": str(DB_PATH),
        "__STT_LOG_PATH__": str(STT_LOG_PATH),
        "__VERSION__": SERVER_VERSION,
        "__RELEASE__": SERVER_RELEASE,
    }
    for placeholder, value in replacements.items():
        template = template.replace(placeholder, html_escape(value, quote=True))
    return template


@app.get("/health")
def health() -> dict:
    with db_conn() as conn:
        conn.execute("SELECT 1").fetchone()
    return {
        "status": "ok",
        "version": SERVER_VERSION,
        "release": SERVER_RELEASE,
        "port": PORT,
        "community_write_enabled": COMMUNITY_WRITE_ENABLED,
    }


def _android_app_update_artifact(raw: dict, version_code: int) -> Path | None:
    """Resolve a published APK without allowing manifest path traversal."""
    filename = str(raw.get("apk_filename", "")).strip()
    if not filename:
        return APP_UPDATE_ANDROID_APK_PATH
    if not re.fullmatch(r"ZhiWuBen-Android-[0-9]+\.apk", filename):
        return None
    return APP_UPDATE_ANDROID_APK_PATH.parent / filename


def configured_android_app_update_with_artifact() -> tuple[dict, Path] | None:
    """Read the current non-secret manifest and its immutable APK artifact."""
    try:
        if not APP_UPDATE_CONFIG_PATH.is_file():
            return None
        raw = json.loads(APP_UPDATE_CONFIG_PATH.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            return None
        version_code = int(raw["version_code"])
        version_name = str(raw["version_name"]).strip()
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError):
        return None
    if version_code <= 0 or not version_name:
        return None
    artifact = _android_app_update_artifact(raw, version_code)
    if artifact is None or not artifact.is_file():
        return None
    sha256 = str(raw.get("sha256", "")).strip().lower()
    if sha256 and not re.fullmatch(r"[0-9a-f]{64}", sha256):
        return None
    return (
        {
            "version_code": version_code,
            "version_name": version_name,
            "mandatory": bool(raw.get("mandatory", False)),
            "release_notes": str(raw.get("release_notes", "")).strip(),
            "published_at": str(raw.get("published_at", "")).strip(),
            "sha256": sha256 or None,
        },
        artifact,
    )


def configured_android_app_update() -> dict | None:
    configured = configured_android_app_update_with_artifact()
    return configured[0] if configured is not None else None


@app.get("/api/app-update/android", name="android_app_update_metadata")
def android_app_update_metadata(request: Request) -> Response:
    configured = configured_android_app_update_with_artifact()
    if configured is None:
        return Response(status_code=204)
    update, _ = configured
    return JSONResponse(
        content={
            **update,
            "download_url": str(
                request.url_for(
                    "android_app_update_download",
                    version_code=str(update["version_code"]),
                )
            ),
        }
    )


def android_app_update_file_response(apk_path: Path) -> FileResponse:
    return FileResponse(
        apk_path,
        media_type="application/vnd.android.package-archive",
        filename="ZhiWuBen-Android.apk",
    )


@app.get("/api/app-update/android/apk", name="android_app_update_download_legacy")
def android_app_update_download_legacy() -> FileResponse:
    configured = configured_android_app_update_with_artifact()
    if configured is None:
        raise HTTPException(status_code=404, detail="Android update package is not published")
    return android_app_update_file_response(configured[1])


@app.get("/api/app-update/android/apk/{version_code}", name="android_app_update_download")
def android_app_update_download(version_code: int) -> FileResponse:
    configured = configured_android_app_update_with_artifact()
    if configured is None or version_code <= 0:
        raise HTTPException(status_code=404, detail="Android update package is not published")
    update, current_apk = configured
    if version_code == update["version_code"]:
        return android_app_update_file_response(current_apk)

    retained_apk = APP_UPDATE_ANDROID_APK_PATH.parent / f"ZhiWuBen-Android-{version_code}.apk"
    if not retained_apk.is_file():
        raise HTTPException(status_code=404, detail="Android update package is unavailable")
    return android_app_update_file_response(retained_apk)


def pwa_file_response(asset_path: str = "") -> FileResponse:
    index_path = PWA_DIST_DIR / "index.html"
    if not index_path.is_file():
        raise HTTPException(status_code=404, detail="PWA build is not available")
    clean_path = asset_path.strip("/")
    if clean_path:
        candidate = (PWA_DIST_DIR / clean_path).resolve()
        try:
            candidate.relative_to(PWA_DIST_DIR)
        except ValueError as exc:
            raise HTTPException(status_code=404, detail="PWA asset is unavailable") from exc
        if candidate.is_file():
            cache_control = "public, max-age=31536000, immutable" if clean_path.startswith("assets/") else "no-cache"
            return FileResponse(candidate, headers={"Cache-Control": cache_control})
        if "." in Path(clean_path).name:
            raise HTTPException(status_code=404, detail="PWA asset is unavailable")
    return FileResponse(index_path, headers={"Cache-Control": "no-cache"})


@app.get("/app", include_in_schema=False)
@app.get("/app/", include_in_schema=False)
def pwa_index() -> FileResponse:
    return pwa_file_response()


@app.get("/app/{asset_path:path}", include_in_schema=False)
def pwa_asset(asset_path: str) -> FileResponse:
    return pwa_file_response(asset_path)


@app.post("/api/auth/register")
def register_account(payload: AccountCredentialsPayload) -> dict:
    try:
        return configured_account_service().register(payload.username, payload.password)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/auth/login")
def login_account(payload: AccountCredentialsPayload) -> dict:
    try:
        return configured_account_service().login(payload.username, payload.password)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/auth/providers")
def social_auth_providers() -> list[dict]:
    return load_social_auth_providers()


@app.post("/api/account/logout")
def logout_account(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    configured_account_service().logout(principal)
    return {"status": "logged_out"}


@app.get("/api/account/me")
def get_account_profile(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    return configured_account_service().profile(principal)


@app.patch("/api/account/me")
def update_account_profile(
    payload: AccountProfileUpdatePayload,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().update_profile(
            principal,
            payload.display_name,
            payload.avatar_data_url,
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/account/session")
def refresh_account_session(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().session_credentials(principal)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/account/plans")
def list_account_plans(
    _principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> list[dict]:
    return configured_account_service().list_plans()


@app.get("/api/account/meetings")
def list_account_meetings(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    return configured_account_service().list_meetings(principal)


@app.put("/api/account/meetings/{meeting_id}")
def upsert_account_meeting(
    meeting_id: str,
    payload: AccountMeetingPayload,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().upsert_meeting(
            principal,
            normalized_account_meeting_id(meeting_id),
            **payload.model_dump(),
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.delete("/api/account/meetings/{meeting_id}")
def delete_account_meeting(
    meeting_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
    deleted_at: int = 0,
) -> dict:
    return configured_account_service().delete_meeting(
        principal,
        normalized_account_meeting_id(meeting_id),
        max(0, deleted_at),
    )


@app.delete("/api/account/meetings")
def clear_account_meetings(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
    deleted_at: int = 0,
) -> dict:
    return configured_account_service().clear_meetings(principal, max(0, deleted_at))


@app.post("/api/stt/transcribe")
def transcribe_audio_for_account(
    file: Annotated[UploadFile, File()],
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
    x_meeting_id: Annotated[str | None, Header(alias="X-Meeting-Id")] = None,
) -> Response:
    try:
        credentials = configured_account_service().session_credentials(principal)
    except AccountError as exc:
        raise account_http_error(exc) from exc
    stt_token = str(credentials.get("stt_access_token") or "").strip()
    if not stt_token:
        raise HTTPException(status_code=503, detail="STT access token is unavailable")
    headers = {"Authorization": f"Bearer {stt_token}"}
    if x_meeting_id:
        headers["X-Meeting-Id"] = x_meeting_id
    try:
        upstream = requests.post(
            f"{STT_SERVICE_BASE_URL}/transcribe",
            headers=headers,
            files={
                "file": (
                    file.filename or "meeting-audio",
                    file.file,
                    file.content_type or "application/octet-stream",
                )
            },
            timeout=(STT_PROXY_CONNECT_TIMEOUT_SEC, STT_PROXY_READ_TIMEOUT_SEC),
        )
    except requests.RequestException as exc:
        raise HTTPException(status_code=502, detail=f"STT service request failed: {exc}") from exc
    try:
        payload = upstream.json()
    except ValueError as exc:
        raise HTTPException(status_code=502, detail="STT service returned an invalid response") from exc
    if not upstream.ok:
        detail = payload.get("detail") if isinstance(payload, dict) else None
        raise HTTPException(
            status_code=upstream.status_code,
            detail=str(detail or f"STT service returned HTTP {upstream.status_code}"),
        )
    return JSONResponse(content=payload, status_code=upstream.status_code)


def account_stt_access_token(principal: AccountPrincipal) -> str:
    try:
        credentials = configured_account_service().session_credentials(principal)
    except AccountError as exc:
        raise account_http_error(exc) from exc
    token = str(credentials.get("stt_access_token") or "").strip()
    if not token:
        raise HTTPException(status_code=503, detail="STT access token is unavailable")
    return token


@app.get("/api/stt/audio-archive")
def list_audio_archive_for_account(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
    meeting_id: str | None = None,
) -> Response:
    params = {"meeting_id": normalized_account_meeting_id(meeting_id)} if meeting_id else None
    try:
        upstream = requests.get(
            f"{STT_SERVICE_BASE_URL}/audio-archive",
            headers={"Authorization": f"Bearer {account_stt_access_token(principal)}"},
            params=params,
            timeout=(STT_PROXY_CONNECT_TIMEOUT_SEC, STT_PROXY_READ_TIMEOUT_SEC),
        )
    except requests.RequestException as exc:
        raise HTTPException(status_code=502, detail=f"STT audio archive request failed: {exc}") from exc
    try:
        payload = upstream.json()
    except ValueError as exc:
        raise HTTPException(status_code=502, detail="STT audio archive returned an invalid response") from exc
    if not upstream.ok:
        detail = payload.get("detail") if isinstance(payload, dict) else None
        raise HTTPException(
            status_code=upstream.status_code,
            detail=str(detail or f"STT audio archive returned HTTP {upstream.status_code}"),
        )
    return JSONResponse(content=payload, status_code=upstream.status_code)


@app.get("/api/stt/audio-archive/{archive_id}")
def download_audio_archive_for_account(
    archive_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> StreamingResponse:
    if not re.fullmatch(r"[A-Fa-f0-9]{32}", archive_id):
        raise HTTPException(status_code=400, detail="音频归档 ID 格式无效")
    try:
        upstream = requests.get(
            f"{STT_SERVICE_BASE_URL}/audio-archive/{archive_id}",
            headers={"Authorization": f"Bearer {account_stt_access_token(principal)}"},
            timeout=(STT_PROXY_CONNECT_TIMEOUT_SEC, STT_PROXY_READ_TIMEOUT_SEC),
            stream=True,
        )
    except requests.RequestException as exc:
        raise HTTPException(status_code=502, detail=f"STT audio download failed: {exc}") from exc
    if not upstream.ok:
        try:
            payload = upstream.json()
        except ValueError:
            payload = {}
        upstream.close()
        raise HTTPException(
            status_code=upstream.status_code,
            detail=str(payload.get("detail") or f"STT audio archive returned HTTP {upstream.status_code}"),
        )
    response_headers = {}
    for name in ("Content-Disposition", "Content-Length"):
        value = upstream.headers.get(name)
        if value:
            response_headers[name] = value
    return StreamingResponse(
        upstream.iter_content(chunk_size=64 * 1024),
        status_code=upstream.status_code,
        media_type=upstream.headers.get("Content-Type", "application/octet-stream"),
        headers=response_headers,
        background=BackgroundTask(upstream.close),
    )


@app.get("/api/account/orders")
def list_account_orders(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> list[dict]:
    return configured_account_service().list_orders(principal)


@app.post("/api/account/orders")
def create_account_order(
    payload: RechargeOrderCreatePayload,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().create_order(principal, payload.plan_code)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/admin/accounts/users")
def list_registered_users(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> list[dict]:
    try:
        return configured_account_service().admin_list_users(principal)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.patch("/api/admin/accounts/users/{user_id}")
def update_registered_user(
    user_id: str,
    payload: AccountUserStatePayload,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().set_user_enabled(
            principal,
            user_id,
            payload.enabled,
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.delete("/api/admin/accounts/users/{user_id}")
def delete_registered_user(
    user_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().delete_user(principal, user_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/admin/accounts/orders")
def list_recharge_orders(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
    status: str | None = None,
) -> list[dict]:
    try:
        return configured_account_service().admin_list_orders(principal, status)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/admin/accounts/orders/{order_id}/approve")
def approve_recharge_order(
    order_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().approve_order(principal, order_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/admin/accounts/orders/{order_id}/reject")
def reject_recharge_order(
    order_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().reject_order(principal, order_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/agent/health")
def agent_health(principal: Annotated[AgentPrincipal, Depends(require_agent_principal)]) -> dict:
    try:
        return AGENT_GATEWAY.health(principal)
    except AgentError as exc:
        raise agent_http_error(exc) from exc


@app.get("/api/agent/quota")
def agent_quota(principal: Annotated[AgentPrincipal, Depends(require_agent_principal)]) -> dict:
    try:
        return AGENT_GATEWAY.quota(principal)
    except AgentError as exc:
        raise agent_http_error(exc) from exc


@app.post("/api/agent")
def execute_agent(
    request_json: Annotated[str, Form(alias="request")],
    principal: Annotated[AgentPrincipal, Depends(require_agent_principal)],
    attachments: Annotated[list[UploadFile], File()] = [],
) -> dict:
    if AGENT_MAX_REQUEST_JSON_BYTES > 0 and len(request_json.encode("utf-8")) > AGENT_MAX_REQUEST_JSON_BYTES:
        raise HTTPException(status_code=413, detail="Agent request metadata is too large")
    try:
        payload = json.loads(request_json)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=400, detail="Agent request must be valid JSON") from exc
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="Agent request must be a JSON object")
    incoming = [
        IncomingAttachment(
            filename=upload.filename or "image",
            content_type=upload.content_type or "application/octet-stream",
            stream=upload.file,
        )
        for upload in attachments
    ]
    try:
        return AGENT_GATEWAY.execute(principal, payload, incoming)
    except AgentError as exc:
        raise agent_http_error(exc) from exc
    finally:
        for upload in attachments:
            upload.file.close()


@app.get("/api/agent/tasks/{task_id}")
def get_agent_task(
    task_id: str,
    principal: Annotated[AgentPrincipal, Depends(require_agent_principal)],
) -> dict:
    try:
        return AGENT_GATEWAY.get_task(principal, task_id)
    except AgentError as exc:
        raise agent_http_error(exc) from exc


@app.get("/api/admin/agent/tokens")
def list_agent_tokens() -> list[dict]:
    return AGENT_GATEWAY.list_tokens()


@app.get("/api/admin/agent/status")
def get_agent_admin_status() -> dict:
    return AGENT_GATEWAY.admin_status()


@app.post("/api/admin/agent/tokens")
def create_agent_token(payload: AgentTokenCreatePayload) -> dict:
    try:
        return AGENT_GATEWAY.issue_token(
            label=payload.label,
            request_limit=payload.request_limit,
            allowed_providers=set(payload.allowed_providers),
            expires_at=payload.expires_at,
        )
    except AgentError as exc:
        raise agent_http_error(exc) from exc


@app.patch("/api/admin/agent/tokens/{token_id}")
def update_agent_token(token_id: str, payload: AgentTokenStatePayload) -> dict:
    try:
        return AGENT_GATEWAY.set_token_enabled(token_id, payload.enabled)
    except AgentError as exc:
        raise agent_http_error(exc) from exc


@app.get("/api/debug/stt-health")
def stt_health() -> dict:
    try:
        response = requests.get(f"{STT_SERVICE_BASE_URL}/health", timeout=5)
        response.raise_for_status()
        payload = response.json()
        payload["base_url"] = STT_SERVICE_BASE_URL
        return payload
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"STT health check failed: {exc}") from exc


@app.post("/api/admin/stt/switch")
def switch_stt(payload: SwitchSTTPayload) -> dict:
    model = payload.model or ("SenseVoiceSmall" if payload.engine == "sensevoice" else "small")
    try:
        response = requests.post(
            f"{STT_SERVICE_BASE_URL}/admin/stt/switch",
            json={"engine": payload.engine, "model": model},
            headers=stt_headers(),
            timeout=STT_SWITCH_TIMEOUT_SEC,
        )
        response.raise_for_status()
        result = response.json()
        result["base_url"] = STT_SERVICE_BASE_URL
        return result
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"STT switch failed: {exc}") from exc


@app.get("/api/debug/stt-log")
def stt_log(lines: int = 80) -> dict:
    if not STT_LOG_PATH.exists():
        return {"path": str(STT_LOG_PATH), "lines": ["STT 日志文件不存在"]}
    content = STT_LOG_PATH.read_text(encoding="utf-8", errors="replace").splitlines()
    return {"path": str(STT_LOG_PATH), "lines": content[-max(1, min(lines, 300)):]}


@app.delete("/api/debug/stt-log")
def clear_stt_log() -> dict:
    if not STT_LOG_PATH.exists():
        return {"path": str(STT_LOG_PATH), "status": "not_found", "message": "日志文件不存在"}
    STT_LOG_PATH.write_text("", encoding="utf-8")
    return {"path": str(STT_LOG_PATH), "status": "cleared", "message": "日志已清空"}


@app.delete("/api/debug/stt-events")
def clear_stt_events() -> dict:
    try:
        response = requests.delete(
            f"{STT_SERVICE_BASE_URL}/debug/stream-events",
            headers=stt_headers(),
            timeout=5,
        )
        response.raise_for_status()
        return response.json()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Failed to clear STT events: {exc}") from exc


@app.get("/api/debug/stt-events")
def stt_events(limit: int = 40) -> dict:
    try:
        response = requests.get(
            f"{STT_SERVICE_BASE_URL}/debug/stream-events",
            params={"limit": limit},
            headers=stt_headers(),
            timeout=5,
        )
        response.raise_for_status()
        payload = response.json()
        payload["base_url"] = STT_SERVICE_BASE_URL
        return payload
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"STT stream events fetch failed: {exc}") from exc


@app.get("/api/meetings")
def list_meetings() -> list[dict]:
    with db_conn() as conn:
        rows = conn.execute(
            "SELECT id, title, created_at, duration_ms, audio_file_path FROM meetings ORDER BY created_at DESC"
        ).fetchall()
        return [dict(row) for row in rows]


@app.post("/api/meetings")
def create_meeting(payload: MeetingPayload) -> dict:
    meeting_id = str(uuid.uuid4())
    with db_conn() as conn:
        conn.execute(
            "INSERT INTO meetings (id, title, created_at, duration_ms, audio_file_path) VALUES (?, ?, ?, ?, ?)",
            (meeting_id, payload.title, payload.created_at, payload.duration_ms, payload.audio_file_path),
        )
    return {"id": meeting_id, **payload.model_dump()}


@app.patch("/api/meetings/{meeting_id}")
def update_meeting(meeting_id: str, payload: MeetingPayload) -> dict:
    with db_conn() as conn:
        result = conn.execute(
            "UPDATE meetings SET title = ?, created_at = ?, duration_ms = ?, audio_file_path = ? WHERE id = ?",
            (payload.title, payload.created_at, payload.duration_ms, payload.audio_file_path, meeting_id),
        )
        if result.rowcount == 0:
            raise HTTPException(status_code=404, detail="Meeting not found")
    return {"id": meeting_id, **payload.model_dump()}


@app.get("/api/meetings/{meeting_id}/transcripts")
def list_transcripts(meeting_id: str) -> list[dict]:
    with db_conn() as conn:
        rows = conn.execute(
            """
            SELECT id, meeting_id, speaker_name, content, start_time_ms, end_time_ms, created_at
            FROM transcripts WHERE meeting_id = ? ORDER BY created_at ASC
            """,
            (meeting_id,),
        ).fetchall()
        return [dict(row) for row in rows]


@app.post("/api/transcripts")
def create_transcript(payload: TranscriptPayload) -> dict:
    transcript_id = str(uuid.uuid4())
    with db_conn() as conn:
        conn.execute(
            """
            INSERT INTO transcripts (id, meeting_id, speaker_name, content, start_time_ms, end_time_ms, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                transcript_id,
                payload.meeting_id,
                payload.speaker_name,
                payload.content,
                payload.start_time_ms,
                payload.end_time_ms,
                payload.created_at,
            ),
        )
    return {"id": transcript_id, **payload.model_dump()}


@app.get("/api/reports/{meeting_id}")
def get_report(meeting_id: str) -> dict:
    with db_conn() as conn:
        row = conn.execute(
            "SELECT id, meeting_id, summary, key_points, tasks, decisions, action_items, generated_at FROM reports WHERE meeting_id = ?",
            (meeting_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="Report not found")
        payload = dict(row)
        payload["key_points"] = json.loads(payload["key_points"])
        payload["tasks"] = json.loads(payload["tasks"])
        payload["decisions"] = json.loads(payload["decisions"])
        payload["action_items"] = json.loads(payload["action_items"])
        return payload


@app.put("/api/reports/{meeting_id}")
def upsert_report(meeting_id: str, payload: ReportPayload) -> dict:
    if payload.meeting_id != meeting_id:
        raise HTTPException(status_code=400, detail="meeting_id mismatch")

    with db_conn() as conn:
        existing = conn.execute("SELECT id FROM reports WHERE meeting_id = ?", (meeting_id,)).fetchone()
        report_id = existing["id"] if existing is not None else str(uuid.uuid4())
        conn.execute(
            """
            INSERT INTO reports (id, meeting_id, summary, key_points, tasks, decisions, action_items, generated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(meeting_id) DO UPDATE SET
                summary = excluded.summary, key_points = excluded.key_points, tasks = excluded.tasks,
                decisions = excluded.decisions, action_items = excluded.action_items, generated_at = excluded.generated_at
            """,
            (
                report_id,
                meeting_id,
                payload.summary,
                json.dumps(payload.key_points, ensure_ascii=False),
                json.dumps(payload.tasks, ensure_ascii=False),
                json.dumps(payload.decisions, ensure_ascii=False),
                json.dumps(payload.action_items, ensure_ascii=False),
                payload.generated_at,
            ),
        )
    return {"id": report_id, **payload.model_dump()}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=HOST, port=PORT)
