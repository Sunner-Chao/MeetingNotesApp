#!/usr/bin/env python3
"""MeetingNotesApp Backend Service and local operations console."""

from __future__ import annotations

import base64
import binascii
import hmac
import json
import os
import sqlite3
import uuid
from contextlib import asynccontextmanager, contextmanager
from html import escape as html_escape
from pathlib import Path
from typing import Annotated, Iterator

import requests
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field

from agent_gateway import (
    PROVIDERS,
    AgentError,
    AgentPrincipal,
    IncomingAttachment,
    gateway_from_env,
)


def _env(name: str, default: str) -> str:
    value = os.getenv(name, default).strip()
    return value or default


HOST = _env("WEB_BACKEND_HOST", "0.0.0.0")
PORT = int(_env("WEB_BACKEND_PORT", "8090"))
DB_PATH = Path(_env("WEB_BACKEND_DB_PATH", "./data/meeting_notes.db")).resolve()
STT_SERVICE_BASE_URL = _env("STT_SERVICE_BASE_URL", "http://127.0.0.1:8888").rstrip("/")
PUBLIC_STT_URL = os.getenv("PUBLIC_STT_URL", "").strip().rstrip("/")
STT_LOG_PATH = Path(_env("STT_LOG_PATH", "../stt-service/logs/stt.log")).resolve()
STT_API_TOKEN = os.getenv("STT_API_TOKEN", "").strip()
STT_SWITCH_TIMEOUT_SEC = float(_env("STT_SWITCH_TIMEOUT_SEC", "900"))
WEB_API_TOKEN = os.getenv("WEB_API_TOKEN", "").strip()
WEB_API_USERNAME = _env("WEB_API_USERNAME", "admin")
SERVER_ROOT = Path(__file__).resolve().parent.parent
DASHBOARD_TEMPLATE_PATH = Path(__file__).with_name("dashboard.html")


def _release_value(filename: str, default: str) -> str:
    try:
        return (SERVER_ROOT / filename).read_text(encoding="utf-8").strip() or default
    except OSError:
        return default


SERVER_VERSION = os.getenv("MEETINGNOTES_SERVER_VERSION", _release_value("VERSION", "dev")).strip()
SERVER_RELEASE = os.getenv("MEETINGNOTES_RELEASE_ID", _release_value("RELEASE", SERVER_VERSION)).strip()
DB_PATH.parent.mkdir(parents=True, exist_ok=True)
AGENT_GATEWAY = gateway_from_env(DB_PATH)


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
    content: str = Field(min_length=1, max_length=2_000_000)
    start_time_ms: int = Field(default=0, ge=0)
    end_time_ms: int = Field(default=0, ge=0)
    created_at: int


class ReportPayload(BaseModel):
    meeting_id: str = Field(min_length=1, max_length=100)
    summary: str = Field(default="", max_length=500_000)
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


@asynccontextmanager
async def app_lifespan(_app: FastAPI):
    init_db()
    AGENT_GATEWAY.initialize()
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
    if (
        request.url.path == "/health"
        or is_agent_path
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
    return {"status": "ok", "version": SERVER_VERSION, "release": SERVER_RELEASE, "port": PORT}


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
    if len(request_json.encode("utf-8")) > 1_000_000:
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
