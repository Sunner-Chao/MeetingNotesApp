#!/usr/bin/env python3
"""MeetingNotesApp Backend Service and local operations console."""

from __future__ import annotations

import base64
import binascii
import contextlib
import hashlib
import hmac
import json
import logging
import os
import platform
import re
import shutil
import sqlite3
import smtplib
import time
import uuid
from contextlib import asynccontextmanager, closing, contextmanager
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from html import escape as html_escape
from pathlib import Path
from typing import Annotated, Iterator
from urllib.parse import parse_qs, parse_qsl, unquote, urlencode, urlparse, urlunparse

import requests
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
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
    AccountDeliveryUnavailableError,
    AccountConflictError,
    AccountError,
    AccountPrincipal,
    AccountService,
)
from alipay_payment import (
    AlipayConfigurationError,
    AlipayGatewayError,
    AlipayPaymentClient,
    amount_cents as alipay_amount_cents,
    is_paid_notification,
    load_alipay_config,
    notify_business_matches,
    verify_notify_signature,
)
from social_auth import (
    build_oauth_authorization_url,
    load_social_auth_config,
    load_social_auth_providers,
    provider_field,
)
from community_api import build_community_router, build_public_community_router
from community_service import CommunityService


def _env(name: str, default: str) -> str:
    value = os.getenv(name, default).strip()
    return value or default


def _env_bool(name: str, default: bool) -> bool:
    fallback = "true" if default else "false"
    return _env(name, fallback).lower() not in {"0", "false", "no", "off"}


HOST = _env("WEB_BACKEND_HOST", "0.0.0.0")
PORT = int(_env("WEB_BACKEND_PORT", "8090"))
DB_PATH = Path(_env("WEB_BACKEND_DB_PATH", "./data/meeting_notes.db")).resolve()
ACCOUNT_DB_PATH = Path(_env("ACCOUNT_DB_PATH", "./data/accounts.db")).resolve()
ACCOUNT_MEDIA_DIR = Path(_env("ACCOUNT_MEDIA_DIR", "./data/account-media")).resolve()
ACCOUNT_MEDIA_MAX_BYTES = max(1, int(_env("ACCOUNT_MEDIA_MAX_BYTES", str(12 * 1024 * 1024))))
COMMUNITY_DB_PATH = Path(
    _env("COMMUNITY_DB_PATH", str(ACCOUNT_DB_PATH))
).resolve()
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
APK_DIRECTORY_TEMPLATE_PATH = Path(__file__).with_name("apk_directory.html")
BEIJING_TIMEZONE = timezone(timedelta(hours=8), name="Asia/Shanghai")
PROCESS_STARTED_AT = int(time.time())
PRIVATE_CHANNEL_MEDIA_DIR = Path(
    _env("GROWTH_MEDIA_DIR", "./data/growth-media")
).resolve()
PRIVATE_CHANNEL_QR_MAX_BYTES = int(_env("GROWTH_QR_MAX_BYTES", str(4 * 1024 * 1024)))


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
ACCOUNT_FREE_PLAN_NAME = _env("ACCOUNT_FREE_PLAN_NAME", "免费账户")
ACCOUNT_FREE_STT_MINUTES = int(_env("ACCOUNT_FREE_STT_MINUTES", "0"))
ACCOUNT_FREE_AI_CREDITS = int(_env("ACCOUNT_FREE_AI_CREDITS", "0"))
ACCOUNT_FREE_POINTS = int(_env("ACCOUNT_FREE_POINTS", "1000"))
ACCOUNT_STT_POINTS_PER_MINUTE = int(_env("ACCOUNT_STT_POINTS_PER_MINUTE", "10"))
ACCOUNT_AI_SUMMARY_POINTS = int(_env("ACCOUNT_AI_SUMMARY_POINTS", "30"))
ACCOUNT_AI_CHAT_POINTS = int(_env("ACCOUNT_AI_CHAT_POINTS", "10"))
ALIPAY_ENABLED = _env_bool("ALIPAY_ENABLED", False)
ALIPAY_ENVIRONMENT = _env("ALIPAY_ENVIRONMENT", "sandbox")
ALIPAY_NOTIFY_URL = os.getenv("ALIPAY_NOTIFY_URL", "").strip()
ACCOUNT_AUTH_CODE_DEBUG = _env_bool("ACCOUNT_AUTH_CODE_DEBUG", False)
ACCOUNT_AUTH_CODE_WEBHOOK_URL = os.getenv("ACCOUNT_AUTH_CODE_WEBHOOK_URL", "").strip()
ACCOUNT_AUTH_CODE_WEBHOOK_TOKEN = os.getenv("ACCOUNT_AUTH_CODE_WEBHOOK_TOKEN", "").strip()
ACCOUNT_SMTP_HOST = os.getenv("ACCOUNT_SMTP_HOST", "").strip()
ACCOUNT_SMTP_PORT = int(_env("ACCOUNT_SMTP_PORT", "465"))
ACCOUNT_SMTP_USERNAME = os.getenv("ACCOUNT_SMTP_USERNAME", "").strip()
ACCOUNT_SMTP_PASSWORD = os.getenv("ACCOUNT_SMTP_PASSWORD", "")
ACCOUNT_SMTP_FROM = os.getenv("ACCOUNT_SMTP_FROM", ACCOUNT_SMTP_USERNAME).strip()
ACCOUNT_SMTP_USE_SSL = _env_bool("ACCOUNT_SMTP_USE_SSL", True)
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
ACCOUNT_AUTH_PUBLIC_BASE_URL = os.getenv("ACCOUNT_AUTH_PUBLIC_BASE_URL", "").strip().rstrip("/")
ACCOUNT_AUTH_ANDROID_CALLBACK_URI = _env(
    "ACCOUNT_AUTH_ANDROID_CALLBACK_URI", "zhiwuben://auth/callback"
)
ACCOUNT_AUTH_ALLOWED_REDIRECTS = tuple(
    value.strip()
    for value in re.split(r"[,\n]", os.getenv("ACCOUNT_AUTH_ALLOWED_REDIRECTS", ""))
    if value.strip()
)
ACCOUNT_AUTH_HTTP_TIMEOUT_SEC = max(
    5.0, float(_env("ACCOUNT_AUTH_HTTP_TIMEOUT_SEC", "20"))
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

ACCOUNT_MIGRATION_ID = "legacy-main-db-accounts-v1"
ACCOUNT_MIGRATION_TABLES = (
    "users",
    "account_plans",
    "user_entitlements",
    "account_identities",
    "account_registration_sources",
    "social_auth_states",
    "social_auth_tickets",
    "social_auth_audit",
    "user_sessions",
    "auth_verification_codes",
    "recharge_orders",
    "alipay_transactions",
    "alipay_notify_events",
    "account_usage_balances",
    "account_usage_events",
    "account_teams",
    "account_team_members",
    "account_meetings",
    "account_meeting_images",
    "account_meeting_tombstones",
    "agent_tokens",
    "agent_tasks",
)
COMMUNITY_MIGRATION_ID = "legacy-main-db-community-v1"
COMMUNITY_MIGRATION_TABLES = (
    "community_posts",
    "community_moderation",
    "community_post_media",
    "community_reports",
    "community_post_likes",
    "community_post_bookmarks",
    "community_comments",
    "community_comment_reports",
    "community_action_rate_windows",
    "community_activity_metrics",
    "community_post_index",
    "community_post_stages",
    "community_post_tags",
    "community_post_pois",
    "community_collections",
    "community_collection_posts",
    "community_collection_bookmarks",
    "community_collection_audit",
)


def _release_value(filename: str, default: str) -> str:
    try:
        return (SERVER_ROOT / filename).read_text(encoding="utf-8").strip() or default
    except OSError:
        return default


SERVER_VERSION = os.getenv("MEETINGNOTES_SERVER_VERSION", _release_value("VERSION", "dev")).strip()
SERVER_RELEASE = os.getenv("MEETINGNOTES_RELEASE_ID", _release_value("RELEASE", SERVER_VERSION)).strip()
DB_PATH.parent.mkdir(parents=True, exist_ok=True)
AGENT_GATEWAY = gateway_from_env(ACCOUNT_DB_PATH)


def _sqlite_table_columns(conn: sqlite3.Connection, table: str) -> list[str]:
    return [str(row["name"]) for row in conn.execute(f"PRAGMA table_info([{table}])").fetchall()]


def migrate_legacy_account_database(source_path: Path, target_path: Path) -> dict[str, object]:
    """Copy legacy account/Agent rows out of the business database once."""
    source = Path(source_path).resolve()
    target = Path(target_path).resolve()
    if source == target or not source.is_file():
        return {"migrated": False, "reason": "source_missing_or_same"}

    with closing(sqlite3.connect(source)) as source_conn:
        source_conn.row_factory = sqlite3.Row
        source_tables = {
            str(row["name"])
            for row in source_conn.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).fetchall()
        }
        if "users" not in source_tables:
            return {"migrated": False, "reason": "no_legacy_accounts"}

        target.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(target, timeout=30)) as target_conn:
            target_conn.row_factory = sqlite3.Row
            target_conn.execute("PRAGMA foreign_keys = ON")
            target_conn.execute("PRAGMA busy_timeout = 30000")
            target_conn.execute(
                """
                CREATE TABLE IF NOT EXISTS account_migration_log (
                    migration_id TEXT PRIMARY KEY,
                    source_path TEXT NOT NULL,
                    source_mtime_ns INTEGER NOT NULL,
                    copied_at INTEGER NOT NULL,
                    copied_tables TEXT NOT NULL
                )
                """
            )
            already = target_conn.execute(
                "SELECT 1 FROM account_migration_log WHERE migration_id = ?",
                (ACCOUNT_MIGRATION_ID,),
            ).fetchone()
            if already is not None:
                return {"migrated": False, "reason": "already_migrated"}
            existing_users = int(
                target_conn.execute("SELECT COUNT(*) FROM users").fetchone()[0]
            )
            if existing_users:
                raise RuntimeError(
                    "ACCOUNT_DB_PATH already contains users but legacy account migration is incomplete"
                )

            target_tables = {
                str(row["name"])
                for row in target_conn.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table'"
                ).fetchall()
            }
            missing_target_tables = sorted(
                table
                for table in ACCOUNT_MIGRATION_TABLES
                if table in source_tables and table not in target_tables
            )
            if missing_target_tables:
                raise RuntimeError(
                    "ACCOUNT_DB_PATH schema is incomplete for legacy migration: "
                    + ", ".join(missing_target_tables)
                )
            occupied_tables: list[str] = []
            for table in ACCOUNT_MIGRATION_TABLES:
                if table not in target_tables:
                    continue
                if table == "agent_tokens":
                    row_count = int(
                        target_conn.execute(
                            "SELECT COUNT(*) FROM agent_tokens WHERE id <> 'bootstrap'"
                        ).fetchone()[0]
                    )
                else:
                    row_count = int(
                        target_conn.execute(f"SELECT COUNT(*) FROM [{table}]").fetchone()[0]
                    )
                if row_count:
                    occupied_tables.append(table)
            if occupied_tables:
                raise RuntimeError(
                    "ACCOUNT_DB_PATH contains partial account data before legacy migration: "
                    + ", ".join(occupied_tables)
                )

            copied: list[str] = []
            target_conn.execute("ATTACH DATABASE ? AS legacy_source", (str(source),))
            target_conn.execute("BEGIN IMMEDIATE")
            try:
                for table in ACCOUNT_MIGRATION_TABLES:
                    if table not in source_tables:
                        continue
                    source_columns = set(_sqlite_table_columns(source_conn, table))
                    target_columns = _sqlite_table_columns(target_conn, table)
                    columns = [column for column in target_columns if column in source_columns]
                    if not columns:
                        continue
                    quoted = ", ".join(f"[{column}]" for column in columns)
                    conflict_clause = " OR IGNORE" if table == "agent_tokens" else ""
                    target_conn.execute(
                        f"INSERT{conflict_clause} INTO [{table}] ({quoted}) "
                        f"SELECT {quoted} FROM legacy_source.[{table}]"
                    )
                    source_count = int(
                        target_conn.execute(
                            f"SELECT COUNT(*) FROM legacy_source.[{table}]"
                        ).fetchone()[0]
                    )
                    if table == "agent_tokens":
                        missing_count = int(
                            target_conn.execute(
                                """
                                SELECT COUNT(*)
                                FROM legacy_source.agent_tokens AS source
                                LEFT JOIN main.agent_tokens AS target ON target.id = source.id
                                WHERE target.id IS NULL
                                """
                            ).fetchone()[0]
                        )
                        if missing_count:
                            raise RuntimeError(
                                f"Legacy account migration did not copy all rows from {table}"
                            )
                    else:
                        target_count = int(
                            target_conn.execute(f"SELECT COUNT(*) FROM [{table}]").fetchone()[0]
                        )
                        if target_count != source_count:
                            raise RuntimeError(
                                f"Legacy account migration row count mismatch for {table}: "
                                f"source={source_count}, target={target_count}"
                            )
                    copied.append(table)
                source_mtime_ns = source.stat().st_mtime_ns
                target_conn.execute(
                    """
                    INSERT INTO account_migration_log (
                        migration_id, source_path, source_mtime_ns, copied_at, copied_tables
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    (
                        ACCOUNT_MIGRATION_ID,
                        str(source),
                        source_mtime_ns,
                        int(datetime.now(timezone.utc).timestamp()),
                        json.dumps(copied, ensure_ascii=False),
                    ),
                )
                target_conn.commit()
            except Exception:
                target_conn.rollback()
                raise
            finally:
                target_conn.execute("DETACH DATABASE legacy_source")
            return {"migrated": True, "copied_tables": copied}


def _sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _copy_community_media_tree(source_root: Path, target_root: Path) -> dict[str, int]:
    source = Path(source_root).resolve()
    target = Path(target_root).resolve()
    if source == target or not source.is_dir():
        return {"copied_files": 0, "existing_files": 0}
    planned: list[tuple[Path, Path, str, str]] = []
    existing = 0

    # Preflight every path before writing any file.  This keeps a late conflict
    # from leaving an otherwise successful-looking partial media migration.
    for source_path in sorted(source.rglob("*")):
        if source_path.is_symlink():
            raise RuntimeError(f"Community media migration refuses symlink: {source_path}")
        if not source_path.is_file():
            continue
        resolved_source = source_path.resolve()
        try:
            relative = resolved_source.relative_to(source)
        except ValueError as exc:
            raise RuntimeError("Community media source escapes its root") from exc
        target_path = (target / relative).resolve()
        try:
            target_path.relative_to(target)
        except ValueError as exc:
            raise RuntimeError("Community media target escapes its root") from exc
        source_digest = _sha256_path(resolved_source)
        if target_path.exists():
            if not target_path.is_file() or _sha256_path(target_path) != source_digest:
                raise RuntimeError(
                    f"Community media conflict at {relative.as_posix()}"
                )
            existing += 1
            continue
        planned.append((resolved_source, target_path, source_digest, relative.as_posix()))

    target.mkdir(parents=True, exist_ok=True)
    copied = 0
    copied_paths: list[Path] = []
    try:
        for resolved_source, target_path, source_digest, relative_name in planned:
            target_path.parent.mkdir(parents=True, exist_ok=True)
            # The temporary file lives beside the destination so the final
            # rename remains atomic on the same filesystem.
            temporary = target_path.with_name(
                f".{target_path.name}.{uuid.uuid4().hex}.migration"
            )
            try:
                shutil.copy2(resolved_source, temporary)
                if _sha256_path(temporary) != source_digest:
                    raise RuntimeError(
                        f"Community media verification failed for {relative_name}"
                    )
                os.replace(temporary, target_path)
            finally:
                with contextlib.suppress(FileNotFoundError):
                    temporary.unlink()
            copied_paths.append(target_path)
            copied += 1
    except Exception:
        # The database transaction will roll back separately.  Remove files
        # created by this invocation so a retry sees the original state.
        for copied_path in reversed(copied_paths):
            with contextlib.suppress(FileNotFoundError):
                copied_path.unlink()
        raise
    return {"copied_files": copied, "existing_files": existing}


def _remove_community_media_files(paths: list[Path]) -> None:
    for path in reversed(paths):
        with contextlib.suppress(FileNotFoundError):
            path.unlink()


def _sqlite_primary_key_columns(conn: sqlite3.Connection, table: str) -> list[str]:
    rows = conn.execute(f"PRAGMA table_info([{table}])").fetchall()
    return [
        str(row["name"])
        for row in sorted(rows, key=lambda item: int(item["pk"]))
        if int(row["pk"]) > 0
    ]


def migrate_legacy_community_database(
    source_path: Path,
    target_path: Path,
    *,
    source_media_root: Path | None = None,
    target_media_root: Path | None = None,
) -> dict[str, object]:
    """Merge legacy community rows and media into the account database once."""
    source = Path(source_path).resolve()
    target = Path(target_path).resolve()
    if source == target or not source.is_file():
        return {"migrated": False, "reason": "source_missing_or_same"}

    with closing(sqlite3.connect(source)) as source_conn:
        source_conn.row_factory = sqlite3.Row
        source_tables = {
            str(row["name"])
            for row in source_conn.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).fetchall()
        }
        tables = [table for table in COMMUNITY_MIGRATION_TABLES if table in source_tables]
        if not tables:
            return {"migrated": False, "reason": "no_legacy_community"}

        with closing(sqlite3.connect(target, timeout=30)) as target_conn:
            target_conn.row_factory = sqlite3.Row
            target_conn.execute("PRAGMA foreign_keys = ON")
            target_conn.execute("PRAGMA busy_timeout = 30000")
            target_conn.execute(
                """
                CREATE TABLE IF NOT EXISTS community_migration_log (
                    migration_id TEXT PRIMARY KEY,
                    source_path TEXT NOT NULL,
                    source_mtime_ns INTEGER NOT NULL,
                    copied_at INTEGER NOT NULL,
                    copied_tables TEXT NOT NULL,
                    media_result TEXT NOT NULL
                )
                """
            )
            already = target_conn.execute(
                "SELECT 1 FROM community_migration_log WHERE migration_id = ?",
                (COMMUNITY_MIGRATION_ID,),
            ).fetchone()
            if already is not None:
                return {
                    "migrated": False,
                    "reason": "already_migrated",
                }

            source_media = source_media_root or source.parent / "community-media"
            target_media = target_media_root or target.parent / "community-media"
            CommunityService(target, media_root=target_media).initialize()

            target_tables = {
                str(row["name"])
                for row in target_conn.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table'"
                ).fetchall()
            }
            missing = [table for table in tables if table not in target_tables]
            if missing:
                raise RuntimeError(
                    "Community target schema is incomplete: " + ", ".join(missing)
                )

            copied_counts: dict[str, int] = {}
            target_media_path = Path(target_media).resolve()
            media_before: set[Path] = set()
            media_snapshot_taken = False
            target_conn.execute("BEGIN IMMEDIATE")
            try:
                for table in tables:
                    source_columns = set(_sqlite_table_columns(source_conn, table))
                    target_columns = _sqlite_table_columns(target_conn, table)
                    columns = [column for column in target_columns if column in source_columns]
                    primary_key = _sqlite_primary_key_columns(source_conn, table)
                    if not columns or not primary_key or any(
                        column not in columns for column in primary_key
                    ):
                        raise RuntimeError(
                            f"Community table {table} has no compatible primary key"
                        )
                    quoted_columns = ", ".join(f"[{column}]" for column in columns)
                    placeholders = ", ".join("?" for _ in columns)
                    source_rows = source_conn.execute(
                        f"SELECT {quoted_columns} FROM [{table}]"
                    ).fetchall()
                    inserted = 0
                    for source_row in source_rows:
                        where = " AND ".join(f"[{column}] = ?" for column in primary_key)
                        key_values = tuple(source_row[column] for column in primary_key)
                        existing = target_conn.execute(
                            f"SELECT {quoted_columns} FROM [{table}] WHERE {where}",
                            key_values,
                        ).fetchone()
                        values = tuple(source_row[column] for column in columns)
                        if existing is not None:
                            if any(existing[column] != source_row[column] for column in columns):
                                raise RuntimeError(
                                    f"Community row conflict in {table}: {key_values!r}"
                                )
                            continue
                        target_conn.execute(
                            f"INSERT INTO [{table}] ({quoted_columns}) VALUES ({placeholders})",
                            values,
                        )
                        inserted += 1
                    copied_counts[table] = inserted

                media_before = (
                    {
                        path.resolve()
                        for path in target_media_path.rglob("*")
                        if path.is_file()
                    }
                    if target_media_path.is_dir()
                    else set()
                )
                media_snapshot_taken = True
                media_result = _copy_community_media_tree(source_media, target_media_path)
                target_conn.execute(
                    """
                    INSERT INTO community_migration_log(
                        migration_id, source_path, source_mtime_ns, copied_at,
                        copied_tables, media_result
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (
                        COMMUNITY_MIGRATION_ID,
                        str(source),
                        source.stat().st_mtime_ns,
                        int(datetime.now(timezone.utc).timestamp()),
                        json.dumps(copied_counts, ensure_ascii=False, sort_keys=True),
                        json.dumps(media_result, ensure_ascii=False, sort_keys=True),
                    ),
                )
                target_conn.commit()
            except Exception:
                target_conn.rollback()
                if media_snapshot_taken and target_media_path.is_dir():
                    created_media = [
                        path.resolve()
                        for path in target_media_path.rglob("*")
                        if path.is_file() and path.resolve() not in media_before
                    ]
                    _remove_community_media_files(created_media)
                raise
            return {
                "migrated": True,
                "copied_tables": copied_counts,
                "media": media_result,
            }


def send_account_auth_code(channel: str, identifier: str, code: str) -> None:
    if channel == "phone":
        raise AccountDeliveryUnavailableError("手机号验证码服务暂未开放")
    if channel != "email":
        raise RuntimeError("不支持的验证码渠道")
    if ACCOUNT_SMTP_HOST and ACCOUNT_SMTP_FROM:
        message = EmailMessage()
        message["Subject"] = "智悟本验证码"
        message["From"] = ACCOUNT_SMTP_FROM
        message["To"] = identifier
        message.set_content(f"你的智悟本验证码是：{code}\n验证码 5 分钟内有效，请勿转发给他人。")
        if ACCOUNT_SMTP_USE_SSL:
            with smtplib.SMTP_SSL(ACCOUNT_SMTP_HOST, ACCOUNT_SMTP_PORT, timeout=30) as smtp:
                if ACCOUNT_SMTP_USERNAME:
                    smtp.login(ACCOUNT_SMTP_USERNAME, ACCOUNT_SMTP_PASSWORD)
                smtp.send_message(message)
        else:
            with smtplib.SMTP(ACCOUNT_SMTP_HOST, ACCOUNT_SMTP_PORT, timeout=30) as smtp:
                smtp.starttls()
                if ACCOUNT_SMTP_USERNAME:
                    smtp.login(ACCOUNT_SMTP_USERNAME, ACCOUNT_SMTP_PASSWORD)
                smtp.send_message(message)
        return
    if not ACCOUNT_AUTH_CODE_WEBHOOK_URL:
        raise RuntimeError("邮箱发送服务尚未配置")
    send_account_auth_code_webhook(channel, identifier, code)


def send_account_auth_code_webhook(channel: str, identifier: str, code: str) -> None:
    headers = {"Content-Type": "application/json"}
    if ACCOUNT_AUTH_CODE_WEBHOOK_TOKEN:
        headers["Authorization"] = f"Bearer {ACCOUNT_AUTH_CODE_WEBHOOK_TOKEN}"
    response = requests.post(
        ACCOUNT_AUTH_CODE_WEBHOOK_URL,
        headers=headers,
        json={"channel": channel, "identifier": identifier, "code": code},
        timeout=(10, 30),
    )
    response.raise_for_status()


ACCOUNT_SERVICE = AccountService(
    ACCOUNT_DB_PATH,
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
    auth_code_sender=(
        send_account_auth_code
        if (ACCOUNT_AUTH_CODE_WEBHOOK_URL or (ACCOUNT_SMTP_HOST and ACCOUNT_SMTP_FROM))
        else None
    ),
    expose_auth_code=ACCOUNT_AUTH_CODE_DEBUG,
    free_stt_minutes=ACCOUNT_FREE_STT_MINUTES,
    free_ai_credits=ACCOUNT_FREE_AI_CREDITS,
    free_points=ACCOUNT_FREE_POINTS,
    stt_points_per_minute=ACCOUNT_STT_POINTS_PER_MINUTE,
    ai_summary_points=ACCOUNT_AI_SUMMARY_POINTS,
    ai_chat_points=ACCOUNT_AI_CHAT_POINTS,
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
    engine: str = Field(pattern="^faster-whisper$")
    model: str | None = None


class AgentTokenCreatePayload(BaseModel):
    label: str = Field(min_length=1, max_length=100)
    request_limit: int = Field(default=1000, ge=1, le=10_000_000)
    allowed_providers: list[str] = Field(default_factory=lambda: sorted(PROVIDERS))
    expires_at: int | None = None


class AgentTokenStatePayload(BaseModel):
    enabled: bool


class AccountCredentialsPayload(BaseModel):
    username: str = Field(min_length=1)
    password: str = Field(min_length=8, max_length=128)


class AccountRegistrationVerifyPayload(BaseModel):
    channel: str = Field(pattern="^email$")
    identifier: str = Field(min_length=3, max_length=254)
    code: str = Field(pattern=r"^\d{6}$")
    username: str = Field(min_length=1)
    password: str = Field(min_length=8, max_length=128)
    referral_code: str | None = Field(default=None, max_length=64)


class AccountAuthCodeRequestPayload(BaseModel):
    channel: str = Field(pattern="^(email|phone)$")
    identifier: str = Field(min_length=3, max_length=254)
    purpose: str = Field(default="login", pattern="^(login|register|bind|reset_password)$")


class AccountAuthCodeVerifyPayload(AccountAuthCodeRequestPayload):
    code: str = Field(pattern=r"^\d{6}$")


class AccountPasswordResetPayload(AccountAuthCodeVerifyPayload):
    purpose: str = Field(default="reset_password", pattern="^reset_password$")
    new_password: str = Field(min_length=8, max_length=128)


class SocialAuthExchangePayload(BaseModel):
    ticket: str = Field(min_length=20, max_length=512)


class AccountTeamMemberPayload(BaseModel):
    user_id: str = Field(min_length=1, max_length=64)


class AccountProfileUpdatePayload(BaseModel):
    display_name: str = Field(default="", max_length=ACCOUNT_PROFILE_NAME_MAX_LENGTH)
    avatar_data_url: str | None = Field(
        default=None,
        max_length=ACCOUNT_PROFILE_AVATAR_DATA_URL_MAX_LENGTH,
    )


class RechargeOrderCreatePayload(BaseModel):
    plan_code: str = Field(min_length=1, max_length=64)


class AlipayRefundPayload(BaseModel):
    refund_amount_cents: int | None = Field(default=None, ge=1)
    reason: str = Field(default="用户申请退款", max_length=200)


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


def normalized_account_image_id(value: str) -> str:
    clean = value.strip().lower()
    if not re.fullmatch(r"[a-f0-9-]{16,64}", clean):
        raise HTTPException(status_code=400, detail="图片 ID 格式无效")
    return clean


def account_media_path(user_id: str, meeting_id: str, image_id: str) -> Path:
    # IDs are validated before reaching this helper; resolve and contain the
    # path anyway so a future caller cannot escape the media root.
    target = ACCOUNT_MEDIA_DIR / user_id / meeting_id / f"{image_id}.bin"
    resolved = target.resolve()
    try:
        resolved.relative_to(ACCOUNT_MEDIA_DIR.resolve())
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="媒体路径无效") from exc
    return resolved


class GrowthRedeemPayload(BaseModel):
    code: str = Field(min_length=4, max_length=64)


class GrowthChannelEventPayload(BaseModel):
    event_type: str = Field(min_length=2, max_length=32)
    channel_id: str = Field(min_length=1, max_length=128)
    source: str = Field(default="pwa", max_length=64)
    campaign_id: str | None = Field(default=None, max_length=128)
    metadata: dict = Field(default_factory=dict)


class GrowthBatchPayload(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    quantity: int = Field(default=100, ge=1, le=5000)
    reward_type: str = Field(default="points", pattern="^(points|coupon|entitlement)$")
    reward_quantity: int = Field(default=100, ge=1, le=1000000)
    expires_at: int | None = Field(default=None, ge=0)
    max_uses: int = Field(default=1, ge=1, le=100000)
    prefix: str = Field(default="ZW", max_length=12)


class GrowthChannelConfigPayload(BaseModel):
    id: str = Field(default="default-welfare-group", max_length=128)
    name: str = Field(default="智悟本福利7群", max_length=80)
    qr_image_url: str = Field(default="", max_length=500)
    manager_card_image_url: str = Field(default="", max_length=500)
    join_url: str = Field(default="", max_length=500)
    short_url: str = Field(default="", max_length=500)
    slogan: str = Field(default="扫码加入福利群，每日专属兑换码、活动优先通知、客服一对一", max_length=300)
    reward_type: str = Field(default="points", max_length=30)
    reward: dict = Field(default_factory=lambda: {"quantity": 200})
    valid_until: int | None = Field(default=None, ge=0)
    enabled: bool = True


class GrowthCampaignConfigPayload(BaseModel):
    id: str | None = Field(default=None, max_length=128)
    title: str = Field(min_length=1, max_length=120)
    campaign_type: str = Field(pattern="^(ranking|quiz|contest|checkin|draw)$")
    summary: str = Field(default="", max_length=500)
    rules: dict = Field(default_factory=dict)
    reward_pool: dict = Field(default_factory=dict)
    starts_at: int = Field(gt=0)
    ends_at: int = Field(gt=0)
    status: str = Field(default="draft", pattern="^(draft|active|running|paused)$")


class GrowthAnswerPayload(BaseModel):
    question_key: str = Field(min_length=1, max_length=80)
    answer: str = Field(min_length=1, max_length=200)


class GrowthChannelApplicationPayload(BaseModel):
    channel_id: str = Field(default="default-welfare-group", min_length=1, max_length=128)
    answers: dict = Field(default_factory=dict)


class GrowthChannelApplicationDecisionPayload(BaseModel):
    decision: str = Field(pattern="^(approved|rejected)$")
    note: str = Field(default="", max_length=500)


def normalized_account_meeting_id(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", value):
        raise HTTPException(status_code=400, detail="会议 ID 格式无效")
    return value


@asynccontextmanager
async def app_lifespan(_app: FastAPI):
    init_db()
    ACCOUNT_MEDIA_DIR.mkdir(parents=True, exist_ok=True)
    account_db_path = ACCOUNT_SERVICE.db_path if ACCOUNT_SERVICE is not None else AGENT_GATEWAY.db_path
    AGENT_GATEWAY.initialize()
    if ACCOUNT_SERVICE is not None:
        # Build the isolated schema first, migrate legacy accounts, then seed defaults.
        ACCOUNT_SERVICE.initialize(bootstrap_admin=False, seed_plans=False)
    if ACCOUNT_SERVICE is not None and Path(DB_PATH).resolve() != Path(account_db_path).resolve():
        migrate_legacy_account_database(DB_PATH, account_db_path)
        ACCOUNT_SERVICE.initialize()
        # Reconcile tasks copied from a previous database after the gateway schema exists.
        AGENT_GATEWAY.initialize()
    elif ACCOUNT_SERVICE is not None:
        ACCOUNT_SERVICE.initialize()
    community_db_path = (
        Path(ACCOUNT_SERVICE.db_path).resolve()
        if ACCOUNT_SERVICE is not None
        else COMMUNITY_DB_PATH
    )
    CommunityService(community_db_path).initialize()
    if Path(DB_PATH).resolve() != community_db_path:
        migrate_legacy_community_database(
            DB_PATH,
            community_db_path,
            source_media_root=Path(DB_PATH).resolve().parent / "community-media",
            target_media_root=community_db_path.parent / "community-media",
        )
    yield


app = FastAPI(title="Meeting Notes Web Backend", lifespan=app_lifespan)

# Payment notifications answer "fail" on any problem by design; the log is the
# only place an operator can see WHY a notification was rejected.
payment_logger = logging.getLogger("meetingnotes.payment")


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
        "/api/auth/register/verify",
        "/api/auth/login",
        "/api/auth/password/login",
        "/api/auth/password/reset",
        "/api/auth/code/request",
        "/api/auth/code/verify",
        "/api/auth/providers",
    } or request.url.path.startswith("/api/auth/social/")
    is_user_account_path = request.url.path.startswith("/api/account/")
    is_account_admin_path = request.url.path.startswith("/api/admin/accounts/")
    is_user_stt_path = request.url.path.startswith("/api/stt/")
    is_public_update_path = request.url.path == "/api/app-update/android" or request.url.path.startswith(
        "/api/app-update/android/"
    )
    is_public_community_path = request.url.path == "/api/community" or request.url.path.startswith(
        "/api/community/"
    )
    is_public_growth_path = request.url.path in {"/api/growth/campaigns", "/api/growth/private-channel"} or request.url.path.startswith("/api/growth/private-channel/")
    is_public_alipay_notify = request.url.path == "/api/payment/alipay/notify"
    is_growth_campaign_path = request.url.path.startswith("/api/growth/campaigns/")
    is_user_web_path = request.url.path == "/app" or request.url.path.startswith("/app/")
    if (
        request.url.path == "/"
        or request.url.path == "/health"
        or is_user_web_path
        or is_agent_path
        or is_public_account_path
        or is_user_account_path
        or is_account_admin_path
        or is_user_stt_path
        or is_public_update_path
        or is_public_community_path
        or is_public_growth_path
        or is_public_alipay_notify
        or is_growth_campaign_path
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


def optional_account_principal(
    authorization: Annotated[str | None, Header()] = None,
) -> AccountPrincipal | None:
    if not authorization:
        return None
    return require_account_principal(authorization)


def require_growth_admin_principal(
    authorization: Annotated[str | None, Header()] = None,
) -> AccountPrincipal:
    service = configured_account_service()
    if authorization:
        try:
            principal = service.authenticate(authorization)
            if principal.is_admin:
                return principal
        except AccountError:
            pass
    if WEB_API_TOKEN and is_web_request_authorized(authorization):
        try:
            return service.dashboard_admin_principal()
        except AccountError as exc:
            raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc
    raise HTTPException(status_code=403, detail="需要管理员权限")


def account_http_error(exc: AccountError) -> HTTPException:
    return HTTPException(status_code=exc.status_code, detail=str(exc))


def _new_alipay_out_trade_no() -> str:
    # Keep the merchant order number short and composed only of safe ASCII.
    return f"ZW{int(time.time())}{uuid.uuid4().hex[:18]}"


def _alipay_payload_for_storage(params: dict[str, str]) -> tuple[str, str]:
    sanitized = {key: value for key, value in params.items() if key not in {"sign", "sign_type"}}
    payload_json = json.dumps(sanitized, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    payload_hash = hashlib.sha256(payload_json.encode("utf-8")).hexdigest()
    return payload_hash, payload_json


def _alipay_response_status(response: dict) -> str:
    return str(response.get("trade_status") or response.get("status") or "")


def _alipay_gateway_http_error(exc: Exception) -> HTTPException:
    if isinstance(exc, AlipayConfigurationError):
        return HTTPException(status_code=503, detail=str(exc))
    if isinstance(exc, AlipayGatewayError):
        return HTTPException(status_code=502, detail=str(exc))
    return HTTPException(status_code=500, detail="支付宝支付处理失败")


def _account_auth_public_base(request: Request) -> str:
    return ACCOUNT_AUTH_PUBLIC_BASE_URL or str(request.base_url).rstrip("/")


def _social_client_redirect(request: Request, client: str, requested: str) -> str:
    public_base = _account_auth_public_base(request)
    default = ACCOUNT_AUTH_ANDROID_CALLBACK_URI if client == "android" else f"{public_base}/app/"
    candidate = requested.strip() or default
    parsed = urlparse(candidate)
    public = urlparse(public_base)
    same_origin = (
        parsed.scheme in {"http", "https"}
        and parsed.scheme == public.scheme
        and parsed.netloc == public.netloc
    )
    allowed = (ACCOUNT_AUTH_ANDROID_CALLBACK_URI, *ACCOUNT_AUTH_ALLOWED_REDIRECTS)
    if same_origin or any(_same_redirect_target(candidate, target) for target in allowed if target):
        return candidate
    raise AccountError("第三方登录回调地址不在允许列表中")


def _same_redirect_target(candidate: str, allowed: str) -> bool:
    candidate_uri = urlparse(candidate)
    allowed_uri = urlparse(allowed)
    if candidate_uri.fragment or not candidate_uri.scheme or not candidate_uri.netloc:
        return False
    candidate_path = candidate_uri.path.rstrip("/") or "/"
    allowed_path = allowed_uri.path.rstrip("/") or "/"
    return (
        candidate_uri.scheme.lower() == allowed_uri.scheme.lower()
        and candidate_uri.netloc.lower() == allowed_uri.netloc.lower()
        and candidate_path == allowed_path
    )


def _append_url_query(url: str, values: dict[str, str]) -> str:
    parsed = urlparse(url)
    query = dict(parse_qsl(parsed.query, keep_blank_values=True))
    query.update({key: value for key, value in values.items() if value})
    return urlunparse(parsed._replace(query=urlencode(query)))


def _social_callback_url(request: Request, provider: str) -> str:
    return f"{_account_auth_public_base(request)}/api/auth/social/{provider}/callback"


def _telegram_identity(config: dict, params: dict[str, str]) -> dict:
    signed_fields = {
        key: value
        for key, value in params.items()
        if key in {"id", "first_name", "last_name", "username", "photo_url", "auth_date"}
    }
    supplied_hash = params.get("hash", "")
    if not supplied_hash or not signed_fields.get("id") or not signed_fields.get("auth_date"):
        raise AccountError("Telegram 返回的身份信息不完整")
    try:
        auth_date = int(signed_fields["auth_date"])
    except ValueError as exc:
        raise AccountError("Telegram 登录时间无效") from exc
    if abs(int(time.time()) - auth_date) > 10 * 60:
        raise AccountError("Telegram 登录信息已过期")
    data_check = "\n".join(f"{key}={signed_fields[key]}" for key in sorted(signed_fields))
    secret_key = hashlib.sha256(str(config["bot_token"]).encode("utf-8")).digest()
    expected = hmac.new(secret_key, data_check.encode("utf-8"), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, supplied_hash):
        raise AccountError("Telegram 登录签名校验失败")
    display_name = " ".join(
        value for value in (signed_fields.get("first_name", ""), signed_fields.get("last_name", "")) if value
    ) or signed_fields.get("username", "")
    return {"subject": signed_fields["id"], "display_name": display_name, "raw": signed_fields}


def _oauth_identity(config: dict, code: str, callback_url: str, verifier: str) -> dict:
    client_id_param = str(config.get("token_client_id_param", "client_id")) or "client_id"
    client_secret_param = str(config.get("token_client_secret_param", "client_secret")) or "client_secret"
    token_payload = {
        str(key): str(value)
        for key, value in config.get("token_extra_params", {}).items()
    }
    token_payload.update({
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": callback_url,
        client_id_param: config["client_id"],
        client_secret_param: config["client_secret"],
    })
    if verifier and bool(config.get("pkce_enabled", True)):
        token_payload["code_verifier"] = verifier
    request_method = requests.get if config.get("token_method") == "GET" else requests.post
    token_body_format = str(config.get("token_body_format", "form")).lower()
    token_response = request_method(
        config["token_endpoint"],
        params=token_payload if request_method is requests.get else None,
        json=token_payload if request_method is requests.post and token_body_format == "json" else None,
        data=token_payload if request_method is requests.post and token_body_format != "json" else None,
        timeout=ACCOUNT_AUTH_HTTP_TIMEOUT_SEC,
    )
    token_response.raise_for_status()
    token_data = _decode_social_provider_response(
        token_response,
        str(config.get("token_response_format", "auto")),
    )
    access_token = provider_field(config, "token_field", token_data)
    if not access_token:
        raise AccountError("第三方平台未返回访问令牌")

    provisional_subject = provider_field(config, "token_subject_field", token_data)
    subject_data: dict = {}
    subject_endpoint = str(config.get("subject_endpoint", ""))
    if subject_endpoint:
        subject_params: dict[str, str] = {
            str(key): str(value)
            for key, value in config.get("subject_extra_params", {}).items()
        }
        subject_token_param = str(config.get("subject_token_param", ""))
        if subject_token_param:
            subject_params[subject_token_param] = access_token
        subject_headers = {} if subject_token_param else {"Authorization": f"Bearer {access_token}"}
        subject_method = requests.post if config.get("subject_method") == "POST" else requests.get
        subject_response = subject_method(
            subject_endpoint,
            headers=subject_headers,
            params=subject_params if subject_method is requests.get else None,
            data=subject_params if subject_method is requests.post else None,
            timeout=ACCOUNT_AUTH_HTTP_TIMEOUT_SEC,
        )
        subject_response.raise_for_status()
        subject_data = _decode_social_provider_response(
            subject_response,
            str(config.get("subject_response_format", "auto")),
        )
        provisional_subject = provider_field(config, "subject_field", subject_data) or provisional_subject

    userinfo_params: dict[str, str] = {
        str(key): str(value)
        for key, value in config.get("userinfo_extra_params", {}).items()
    }
    token_param = str(config.get("userinfo_token_param", ""))
    if token_param:
        userinfo_params[token_param] = access_token
    subject_param = str(config.get("userinfo_subject_param", ""))
    if subject_param and provisional_subject:
        userinfo_params[subject_param] = provisional_subject
    client_id_query_param = str(config.get("userinfo_client_id_param", ""))
    if client_id_query_param:
        userinfo_params[client_id_query_param] = str(config["client_id"])
    headers = {} if token_param else {"Authorization": f"Bearer {access_token}"}
    info_method = requests.post if config.get("userinfo_method") == "POST" else requests.get
    info_response = info_method(
        config["userinfo_endpoint"],
        headers=headers,
        params=userinfo_params if info_method is requests.get else None,
        data=userinfo_params if info_method is requests.post else None,
        timeout=ACCOUNT_AUTH_HTTP_TIMEOUT_SEC,
    )
    info_response.raise_for_status()
    userinfo = _decode_social_provider_response(
        info_response,
        str(config.get("userinfo_response_format", "auto")),
    )
    subject = provider_field(config, "subject_field", userinfo) or provisional_subject
    if not subject:
        raise AccountError("第三方平台未返回用户标识")
    return {
        "subject": subject,
        "display_name": provider_field(config, "display_name_field", userinfo),
        "raw": {"provider_user": userinfo},
    }


def _decode_social_provider_response(response: requests.Response, response_format: str) -> dict:
    clean_format = response_format.strip().lower() or "auto"
    text = response.text.strip()
    if clean_format in {"auto", "json"}:
        try:
            payload = response.json()
            if isinstance(payload, dict):
                return payload
        except ValueError:
            if clean_format == "json":
                raise AccountError("第三方平台返回了无效 JSON")
    if clean_format in {"auto", "form"}:
        parsed = {key: values[-1] for key, values in parse_qs(text, keep_blank_values=True).items()}
        if parsed:
            return parsed
        if clean_format == "form":
            raise AccountError("第三方平台返回了无效表单数据")
    if clean_format in {"auto", "jsonp"}:
        left = text.find("(")
        right = text.rfind(")")
        if 0 <= left < right:
            try:
                payload = json.loads(text[left + 1:right].strip())
                if isinstance(payload, dict):
                    return payload
            except json.JSONDecodeError:
                pass
        if clean_format == "jsonp":
            raise AccountError("第三方平台返回了无效 JSONP")
    raise AccountError("无法解析第三方平台响应")


def configured_community_db_path() -> Path:
    """Keep community identities in the same SQLite database as accounts.

    SQLite foreign keys cannot reference a table in another database.  The
    account split therefore must also move the community tables onto the
    account database (or an explicitly configured community database that is
    provisioned with the same users table).  Looking up the service at request
    time keeps the test/deployment override behaviour intact.
    """
    if ACCOUNT_SERVICE is not None:
        return Path(ACCOUNT_SERVICE.db_path).resolve()
    return COMMUNITY_DB_PATH


app.include_router(build_community_router(configured_community_db_path, require_account_principal))
app.include_router(build_public_community_router(configured_community_db_path))


@app.get("/", include_in_schema=False)
def public_root_redirect() -> RedirectResponse:
    """Keep the host root focused on the user-facing Web application."""
    return RedirectResponse(url="/app/", status_code=307)


@app.get("/web", include_in_schema=False)
def legacy_admin_redirect() -> RedirectResponse:
    """Preserve the former Backend dashboard URL during the domain migration."""
    return RedirectResponse(url="/admin/", status_code=308)


@app.get("/admin/stt/", include_in_schema=False)
def admin_stt_redirect() -> RedirectResponse:
    """Expose a stable deep link for the STT section in the unified admin console."""
    return RedirectResponse(url="/admin/#services", status_code=307)


@app.get("/admin", response_class=HTMLResponse)
@app.get("/admin/", response_class=HTMLResponse)
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


@app.get("/favicon.ico", include_in_schema=False)
def favicon() -> Response:
    return Response(status_code=204)


def _memory_metrics() -> tuple[int, int]:
    meminfo_path = Path("/proc/meminfo")
    if meminfo_path.is_file():
        values: dict[str, int] = {}
        for line in meminfo_path.read_text(encoding="utf-8", errors="replace").splitlines():
            key, separator, value = line.partition(":")
            if separator:
                match = re.search(r"\d+", value)
                if match:
                    values[key] = int(match.group()) * 1024
        total = values.get("MemTotal", 0)
        available = values.get("MemAvailable", values.get("MemFree", 0))
        return total, max(0, total - available)
    if platform.system() == "Windows":
        try:
            import ctypes

            class MemoryStatusEx(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            status = MemoryStatusEx()
            status.dwLength = ctypes.sizeof(MemoryStatusEx)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
                return int(status.ullTotalPhys), int(status.ullTotalPhys - status.ullAvailPhys)
        except (AttributeError, OSError, ValueError):
            pass
    return 0, 0


def _process_rss_bytes() -> int:
    status_path = Path("/proc/self/status")
    if status_path.is_file():
        match = re.search(
            r"^VmRSS:\s+(\d+)\s+kB$",
            status_path.read_text(encoding="utf-8", errors="replace"),
            re.MULTILINE,
        )
        if match:
            return int(match.group(1)) * 1024
    return 0


@app.get("/api/admin/system/metrics")
def admin_system_metrics(
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    configured_account_service()._require_admin(principal)
    now = int(time.time())
    cpu_count = max(1, os.cpu_count() or 1)
    try:
        load_1m, load_5m, load_15m = os.getloadavg()
    except (AttributeError, OSError):
        load_1m = load_5m = load_15m = 0.0
    memory_total, memory_used = _memory_metrics()
    disk_root = DB_PATH.parent if DB_PATH.parent.exists() else SERVER_ROOT
    disk = shutil.disk_usage(disk_root)
    account_db = configured_account_service().db_path
    return {
        "host": platform.node() or "unknown",
        "platform": platform.platform(),
        "cpu": {
            "logical_cores": cpu_count,
            "load_percent": round(min(100.0, max(0.0, load_1m / cpu_count * 100)), 1),
            "load_1m": round(load_1m, 2),
            "load_5m": round(load_5m, 2),
            "load_15m": round(load_15m, 2),
        },
        "memory": {
            "total_bytes": memory_total,
            "used_bytes": memory_used,
            "used_percent": round(memory_used / memory_total * 100, 1) if memory_total else 0,
            "process_rss_bytes": _process_rss_bytes(),
        },
        "disk": {
            "total_bytes": disk.total,
            "used_bytes": disk.used,
            "free_bytes": disk.free,
            "used_percent": round(disk.used / disk.total * 100, 1) if disk.total else 0,
        },
        "database": {
            "meeting_bytes": DB_PATH.stat().st_size if DB_PATH.is_file() else 0,
            "account_bytes": account_db.stat().st_size if account_db.is_file() else 0,
        },
        "process": {
            "pid": os.getpid(),
            "started_at": PROCESS_STARTED_AT,
            "uptime_seconds": max(0, now - PROCESS_STARTED_AT),
        },
        "observed_at": now,
        "timezone": "Asia/Shanghai",
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


def retained_android_app_update_artifacts() -> tuple[dict | None, list[dict]]:
    """Return the current and immediately previous downloadable APKs."""
    configured = configured_android_app_update_with_artifact()
    if configured is None:
        return None, []

    update, current_apk = configured
    current_version_code = int(update["version_code"])
    candidates = [
        (current_version_code, current_apk, True),
    ]
    previous = sorted(
        (
            (int(match.group(1)), artifact)
            for artifact in APP_UPDATE_ANDROID_APK_PATH.parent.glob("ZhiWuBen-Android-*.apk")
            if artifact.is_file()
            and (match := re.fullmatch(r"ZhiWuBen-Android-([0-9]+)\.apk", artifact.name))
            and int(match.group(1)) < current_version_code
        ),
        key=lambda item: item[0],
        reverse=True,
    )
    if previous:
        candidates.append((previous[0][0], previous[0][1], False))

    artifacts = []
    seen_paths: set[Path] = set()
    for version_code, apk_path, is_current in candidates:
        resolved_path = apk_path.resolve()
        if resolved_path in seen_paths:
            continue
        try:
            stat = resolved_path.stat()
        except OSError:
            continue
        seen_paths.add(resolved_path)
        artifacts.append(
            {
                "version_code": version_code,
                "filename": resolved_path.name,
                "path": resolved_path,
                "size_bytes": stat.st_size,
                "modified_at": datetime.fromtimestamp(stat.st_mtime, timezone.utc),
                "is_current": is_current,
            }
        )
    return update, artifacts


def _format_file_size(size_bytes: int) -> str:
    size = float(max(0, size_bytes))
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            precision = 0 if unit == "B" else 1
            return f"{size:.{precision}f} {unit}"
        size /= 1024
    return f"{size_bytes} B"


def _android_apk_directory_row(request: Request, update: dict, artifact: dict) -> str:
    version_code = int(artifact["version_code"])
    is_current = bool(artifact["is_current"])
    download_url = str(
        request.url_for("android_app_update_download", version_code=str(version_code))
    )
    version_label = update["version_name"] if is_current else f"versionCode {version_code}"
    status_label = "当前版本" if is_current else "上一版本"
    status_class = "current" if is_current else "retained"
    release_notes = update.get("release_notes", "") if is_current else "可用于覆盖安装或回退验证"
    sha256 = update.get("sha256") if is_current else None
    sha_markup = (
        f'<div class="checksum"><span>SHA-256</span><code>{html_escape(str(sha256))}</code></div>'
        if sha256
        else ""
    )
    return f"""
        <article class="release-row" data-testid="release-row-{version_code}">
          <div class="file-icon" aria-hidden="true">APK</div>
          <div class="release-main">
            <div class="release-title-line">
              <h2>{html_escape(str(artifact["filename"]))}</h2>
              <span class="version-badge {status_class}">{status_label}</span>
            </div>
            <p class="release-version">{html_escape(str(version_label))}</p>
            <p class="release-notes">{html_escape(str(release_notes))}</p>
            <div class="release-meta">
              <span>{_format_file_size(int(artifact["size_bytes"]))}</span>
              <span>versionCode {version_code}</span>
              <span>{artifact["modified_at"].astimezone(BEIJING_TIMEZONE).strftime("%Y-%m-%d %H:%M 北京时间")}</span>
            </div>
            {sha_markup}
          </div>
          <div class="release-actions">
            <button class="button secondary copy-link" type="button" data-copy-url="{html_escape(download_url, quote=True)}" aria-label="复制 {html_escape(str(version_label), quote=True)} 下载链接">
              <span aria-hidden="true">⧉</span>
              <span class="button-label">复制链接</span>
            </button>
            <a class="button primary" href="{html_escape(download_url, quote=True)}" download>
              <span aria-hidden="true">↓</span>
              下载 APK
            </a>
          </div>
        </article>
    """


@app.get("/api/app-update/android", name="android_app_update_metadata")
def android_app_update_metadata(request: Request) -> Response:
    configured = configured_android_app_update_with_artifact()
    if configured is None:
        return Response(status_code=204, headers={"Cache-Control": "no-store"})
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
        },
        headers={"Cache-Control": "no-store"},
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


@app.get(
    "/api/app-update/android/apk/",
    name="android_app_update_directory",
    response_class=HTMLResponse,
    include_in_schema=False,
)
def android_app_update_directory(request: Request) -> HTMLResponse:
    update, artifacts = retained_android_app_update_artifacts()
    current_version = str(update["version_name"]) if update else "暂无发布"
    current_version_code = str(update["version_code"]) if update else "-"
    release_rows = "".join(
        _android_apk_directory_row(request, update or {}, artifact) for artifact in artifacts
    )
    empty_state_class = "hidden" if artifacts else ""
    template = APK_DIRECTORY_TEMPLATE_PATH.read_text(encoding="utf-8")
    replacements = {
        "__CURRENT_VERSION__": current_version,
        "__CURRENT_VERSION_CODE__": current_version_code,
        "__RELEASE_COUNT__": str(len(artifacts)),
        "__RELEASE_ROWS__": release_rows,
        "__EMPTY_STATE_CLASS__": empty_state_class,
        "__GENERATED_AT__": datetime.now(BEIJING_TIMEZONE).strftime("%Y-%m-%d %H:%M 北京时间"),
    }
    for placeholder, value in replacements.items():
        if placeholder == "__RELEASE_ROWS__":
            template = template.replace(placeholder, value)
        else:
            template = template.replace(placeholder, html_escape(value, quote=True))
    return HTMLResponse(template, headers={"Cache-Control": "no-store"})


@app.get("/api/app-update/android/apk/{version_code}", name="android_app_update_download")
def android_app_update_download(version_code: int) -> FileResponse:
    update, artifacts = retained_android_app_update_artifacts()
    if update is None or version_code <= 0:
        raise HTTPException(status_code=404, detail="Android update package is not published")
    artifact = next(
        (item for item in artifacts if int(item["version_code"]) == version_code),
        None,
    )
    if artifact is None:
        raise HTTPException(status_code=404, detail="Android update package is unavailable")
    return android_app_update_file_response(Path(artifact["path"]))


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
    raise HTTPException(
        status_code=400,
        detail="注册账号必须先完成邮箱验证，请使用邮箱注册",
    )


@app.post("/api/auth/register/verify")
def register_account_with_email(payload: AccountRegistrationVerifyPayload) -> dict:
    try:
        return configured_account_service().register_with_identity(
            payload.username,
            payload.password,
            payload.channel,
            payload.identifier,
            payload.code,
            payload.referral_code,
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/auth/login")
@app.post("/api/auth/password/login")
def login_account(payload: AccountCredentialsPayload) -> dict:
    try:
        return configured_account_service().login(payload.username, payload.password)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/auth/code/request")
def request_account_auth_code(payload: AccountAuthCodeRequestPayload) -> dict:
    try:
        return configured_account_service().request_auth_code(
            payload.channel,
            payload.identifier,
            payload.purpose,
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/growth/campaigns")
def growth_campaigns() -> list[dict]:
    return configured_account_service().list_growth_campaigns()


@app.get("/api/growth/campaigns/{campaign_id}")
def growth_campaign_detail(campaign_id: str, principal: Annotated[AccountPrincipal, Depends(require_account_principal)]) -> dict:
    try:
        return configured_account_service().campaign_detail(principal, campaign_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/growth/campaigns/{campaign_id}/join")
def growth_campaign_join(campaign_id: str, principal: Annotated[AccountPrincipal, Depends(require_account_principal)]) -> dict:
    try:
        return configured_account_service().join_campaign(principal, campaign_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/growth/campaigns/{campaign_id}/checkin")
def growth_campaign_checkin(campaign_id: str, principal: Annotated[AccountPrincipal, Depends(require_account_principal)]) -> dict:
    try:
        return configured_account_service().campaign_checkin(principal, campaign_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/growth/campaigns/{campaign_id}/answer")
def growth_campaign_answer(campaign_id: str, payload: GrowthAnswerPayload, principal: Annotated[AccountPrincipal, Depends(require_account_principal)]) -> dict:
    try:
        return configured_account_service().campaign_answer(principal, campaign_id, payload.question_key, payload.answer)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/growth/campaigns/{campaign_id}/draw")
def growth_campaign_draw(campaign_id: str, principal: Annotated[AccountPrincipal, Depends(require_account_principal)]) -> dict:
    try:
        return configured_account_service().campaign_draw(principal, campaign_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/growth/campaigns/{campaign_id}/leaderboard")
def growth_campaign_leaderboard(campaign_id: str, limit: int = 20) -> list[dict]:
    try:
        return configured_account_service().campaign_leaderboard(campaign_id, limit)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/growth/private-channel")
def growth_private_channel() -> dict:
    channel = configured_account_service().public_private_channel()
    if channel is None:
        raise HTTPException(status_code=404, detail="暂无开放的福利群")
    return channel


@app.get("/api/growth/private-channel/default-qr", include_in_schema=False)
def growth_private_channel_default_qr(
    principal: Annotated[AccountPrincipal | None, Depends(optional_account_principal)],
) -> FileResponse:
    if not configured_account_service().can_access_private_channel_asset("default-qr", principal):
        raise HTTPException(status_code=403, detail="审核通过后才可查看群二维码")
    candidates = (
        SERVER_ROOT.parent / "pwa" / "public" / "assets" / "welfare-group-qr.jpg",
        SERVER_ROOT / "pwa-dist" / "assets" / "welfare-group-qr.jpg",
    )
    image_path = next((candidate for candidate in candidates if candidate.is_file()), None)
    if image_path is None:
        raise HTTPException(status_code=404, detail="福利群二维码素材不存在")
    return FileResponse(image_path, media_type="image/jpeg", headers={"Cache-Control": "public, max-age=300"})


@app.get("/api/growth/private-channel/default-manager-card", include_in_schema=False)
def growth_private_channel_default_manager_card() -> FileResponse:
    candidates = (
        SERVER_ROOT / "backend-service" / "assets" / "private-channel-manager-card.jpg",
        SERVER_ROOT / "backend-service" / "private-channel-manager-card.jpg",
    )
    image_path = next((candidate for candidate in candidates if candidate.is_file()), None)
    if image_path is None:
        raise HTTPException(status_code=404, detail="群主名片素材不存在")
    return FileResponse(image_path, media_type="image/jpeg", headers={"Cache-Control": "public, max-age=300"})


@app.get("/api/growth/private-channel/media/{filename}", include_in_schema=False)
def growth_private_channel_media(
    filename: str,
    principal: Annotated[AccountPrincipal | None, Depends(optional_account_principal)],
) -> FileResponse:
    if not re.fullmatch(r"[a-f0-9]{32}\.(?:jpg|png|webp)", filename):
        raise HTTPException(status_code=404, detail="二维码素材不存在")
    if not configured_account_service().can_access_private_channel_asset(filename, principal):
        raise HTTPException(status_code=403, detail="审核通过后才可查看群二维码素材")
    image_path = PRIVATE_CHANNEL_MEDIA_DIR / filename
    if not image_path.is_file():
        raise HTTPException(status_code=404, detail="二维码素材不存在")
    media_type = {
        ".jpg": "image/jpeg",
        ".png": "image/png",
        ".webp": "image/webp",
    }[image_path.suffix.lower()]
    return FileResponse(
        image_path,
        media_type=media_type,
        headers={"Cache-Control": "public, max-age=300"},
    )


@app.get("/api/account/growth/overview")
def account_growth_overview(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().growth_overview(principal)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/account/growth/private-channel/application")
def account_growth_private_channel_application(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
    channel_id: str = "default-welfare-group",
) -> dict:
    try:
        return configured_account_service().private_channel_application(principal, channel_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/account/growth/private-channel/application")
def submit_account_growth_private_channel_application(
    payload: GrowthChannelApplicationPayload,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().submit_private_channel_application(
            principal, payload.channel_id, payload.answers
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/account/redeem")
def account_redeem_code(
    payload: GrowthRedeemPayload,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().redeem_code(principal, payload.code)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/account/redeem/history")
def account_redeem_history(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> list[dict]:
    return configured_account_service().redemption_history(principal)


@app.get("/api/account/growth/messages")
def account_growth_messages(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
    limit: int = 50,
) -> list[dict]:
    try:
        return configured_account_service().system_messages(principal, limit)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/account/growth/messages/{message_id}/read")
def account_mark_growth_message_read(
    message_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().mark_system_message_read(
            principal, message_id
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/growth/private-channel/events")
def growth_private_channel_event(
    payload: GrowthChannelEventPayload,
    principal: Annotated[AccountPrincipal | None, Depends(optional_account_principal)],
) -> dict:
    try:
        return configured_account_service().record_channel_event(
            payload.event_type,
            channel_id=payload.channel_id,
            user_id=principal.user_id if principal else None,
            source=payload.source,
            campaign_id=payload.campaign_id,
            metadata=payload.metadata,
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/admin/growth/overview")
def admin_growth_overview(
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    try:
        return configured_account_service().admin_growth_overview(principal)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/admin/growth/redemptions/batches")
def admin_create_growth_batch(
    payload: GrowthBatchPayload,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    try:
        return configured_account_service().admin_create_redemption_batch(
            principal, **payload.model_dump()
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/admin/growth/redemptions")
def admin_growth_batches(
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> list[dict]:
    try:
        return configured_account_service().admin_list_redemption_batches(principal)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/admin/growth/redemptions/{batch_id}/codes")
def admin_growth_batch_codes(
    batch_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
    status: str | None = None,
    search: str = "",
    limit: int = 200,
    offset: int = 0,
) -> dict:
    try:
        return configured_account_service().admin_list_redemption_codes(
            principal,
            batch_id,
            status=status,
            search=search,
            limit=limit,
            offset=offset,
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/admin/growth/campaigns")
def admin_growth_campaigns(
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> list[dict]:
    try:
        return configured_account_service().admin_list_campaigns(principal)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/admin/growth/campaigns")
def admin_create_growth_campaign(
    payload: GrowthCampaignConfigPayload,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    try:
        return configured_account_service().admin_create_campaign(
            principal, payload.model_dump()
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.patch("/api/admin/growth/campaigns/{campaign_id}")
def admin_update_growth_campaign(
    campaign_id: str,
    payload: GrowthCampaignConfigPayload,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    try:
        return configured_account_service().admin_update_campaign(
            principal, campaign_id, payload.model_dump()
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/admin/growth/campaigns/{campaign_id}/settle")
def admin_settle_growth_campaign(campaign_id: str, principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)]) -> dict:
    try:
        return configured_account_service().admin_settle_campaign(principal, campaign_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/admin/growth/private-channel")
def admin_growth_private_channel(
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict | None:
    configured_account_service()._require_admin(principal)
    return configured_account_service().private_channel()


@app.patch("/api/admin/growth/private-channel")
def admin_update_growth_private_channel(
    payload: GrowthChannelConfigPayload,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    try:
        return configured_account_service().admin_upsert_private_channel(principal, payload.model_dump())
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/admin/growth/private-channel/applications")
def admin_growth_private_channel_applications(
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
    status: str | None = None,
) -> list[dict]:
    try:
        return configured_account_service().admin_list_private_channel_applications(
            principal, status
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/admin/growth/private-channel/applications/{application_id}/decision")
def admin_decide_growth_private_channel_application(
    application_id: str,
    payload: GrowthChannelApplicationDecisionPayload,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    try:
        return configured_account_service().admin_decide_private_channel_application(
            principal, application_id, payload.decision, payload.note
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/admin/growth/private-channel/qr")
async def admin_upload_growth_private_channel_qr(
    file: Annotated[UploadFile, File()],
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    configured_account_service()._require_admin(principal)
    allowed_types = {
        "image/jpeg": ("jpg", (b"\xff\xd8\xff",)),
        "image/png": ("png", (b"\x89PNG\r\n\x1a\n",)),
        "image/webp": ("webp", (b"RIFF",)),
    }
    media_type = (file.content_type or "").lower()
    config = allowed_types.get(media_type)
    if config is None:
        raise HTTPException(status_code=400, detail="仅支持 JPG、PNG 或 WebP 二维码图片")
    try:
        content = await file.read(PRIVATE_CHANNEL_QR_MAX_BYTES + 1)
    finally:
        await file.close()
    if not content or len(content) > PRIVATE_CHANNEL_QR_MAX_BYTES:
        raise HTTPException(status_code=400, detail="二维码图片为空或超过大小限制")
    extension, signatures = config
    if not any(content.startswith(signature) for signature in signatures):
        raise HTTPException(status_code=400, detail="二维码图片内容与文件类型不符")
    if extension == "webp" and content[8:12] != b"WEBP":
        raise HTTPException(status_code=400, detail="二维码 WebP 文件无效")
    PRIVATE_CHANNEL_MEDIA_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"{uuid.uuid4().hex}.{extension}"
    target = PRIVATE_CHANNEL_MEDIA_DIR / filename
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_bytes(content)
    temporary.replace(target)
    return {
        "status": "uploaded",
        "qr_image_url": f"/api/growth/private-channel/media/{filename}",
        "content_type": media_type,
        "size": len(content),
    }


@app.post("/api/admin/growth/private-channel/manager-card")
async def admin_upload_growth_private_channel_manager_card(
    file: Annotated[UploadFile, File()],
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    configured_account_service()._require_admin(principal)
    allowed_types = {
        "image/jpeg": ("jpg", (b"\xff\xd8\xff",)),
        "image/png": ("png", (b"\x89PNG\r\n\x1a\n",)),
        "image/webp": ("webp", (b"RIFF",)),
    }
    media_type = (file.content_type or "").lower()
    config = allowed_types.get(media_type)
    if config is None:
        raise HTTPException(status_code=400, detail="仅支持 JPG、PNG 或 WebP 名片图片")
    try:
        content = await file.read(PRIVATE_CHANNEL_QR_MAX_BYTES + 1)
    finally:
        await file.close()
    if not content or len(content) > PRIVATE_CHANNEL_QR_MAX_BYTES:
        raise HTTPException(status_code=400, detail="名片图片为空或超过大小限制")
    extension, signatures = config
    if not any(content.startswith(signature) for signature in signatures):
        raise HTTPException(status_code=400, detail="名片图片内容与文件类型不符")
    if extension == "webp" and content[8:12] != b"WEBP":
        raise HTTPException(status_code=400, detail="名片 WebP 文件无效")
    PRIVATE_CHANNEL_MEDIA_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"{uuid.uuid4().hex}.{extension}"
    target = PRIVATE_CHANNEL_MEDIA_DIR / filename
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_bytes(content)
    temporary.replace(target)
    return {
        "status": "uploaded",
        "manager_card_image_url": f"/api/growth/private-channel/media/{filename}",
        "content_type": media_type,
        "size": len(content),
    }


@app.post("/api/auth/code/verify")
def verify_account_auth_code(payload: AccountAuthCodeVerifyPayload) -> dict:
    try:
        return configured_account_service().verify_auth_code(
            payload.channel,
            payload.identifier,
            payload.code,
            payload.purpose,
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/auth/password/reset")
def reset_account_password(payload: AccountPasswordResetPayload) -> dict:
    try:
        return configured_account_service().reset_password(
            payload.channel,
            payload.identifier,
            payload.code,
            payload.new_password,
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/auth/providers")
def social_auth_providers(request: Request) -> list[dict]:
    return load_social_auth_providers(public_base_url=_account_auth_public_base(request))


@app.get("/api/auth/social/{provider}/start")
def start_social_auth(
    provider: str,
    request: Request,
    client: str = "pwa",
    redirect_uri: str = "",
    ref: str = "",
) -> RedirectResponse:
    clean_provider = provider.strip().lower()
    config = load_social_auth_config().get(clean_provider)
    if config is None:
        raise HTTPException(status_code=404, detail="不支持的第三方登录平台")
    if not config["enabled"]:
        raise HTTPException(status_code=503, detail=config["unavailable_reason"])
    try:
        client_redirect = _social_client_redirect(request, client.strip().lower(), redirect_uri)
        state = configured_account_service().begin_social_auth(
            clean_provider,
            client=client.strip().lower() or "pwa",
            redirect_uri=client_redirect,
            referral_code=ref,
        )
        verifier = state["code_verifier"]
        challenge = base64.urlsafe_b64encode(
            hashlib.sha256(verifier.encode("utf-8")).digest()
        ).decode("ascii").rstrip("=")
        authorization_url = build_oauth_authorization_url(
            config,
            state=state["state"],
            redirect_uri=_social_callback_url(request, clean_provider),
            code_challenge=challenge,
        )
        return RedirectResponse(authorization_url, status_code=307)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/auth/social/{provider}/callback")
def complete_social_auth(provider: str, request: Request) -> Response:
    clean_provider = provider.strip().lower()
    config = load_social_auth_config().get(clean_provider)
    if config is None:
        raise HTTPException(status_code=404, detail="不支持的第三方登录平台")
    params = {key: value for key, value in request.query_params.items()}
    raw_state = params.get("state", "")
    try:
        state = configured_account_service().consume_social_auth_state(clean_provider, raw_state)
    except AccountError as exc:
        raise account_http_error(exc) from exc
    client_redirect = str(state["redirect_uri"])
    platform_error = params.get("error_description") or params.get("error")
    if platform_error:
        configured_account_service().record_social_audit(
            clean_provider, "callback", False, str(platform_error)
        )
        return RedirectResponse(
            _append_url_query(client_redirect, {"social_error": str(platform_error)[:200]}),
            status_code=303,
        )
    try:
        if config["protocol"] == "telegram":
            identity = _telegram_identity(config, params)
        else:
            code = params.get("code", "").strip()
            if not code:
                raise AccountError("第三方平台未返回授权码")
            identity = _oauth_identity(
                config,
                code,
                _social_callback_url(request, clean_provider),
                str(state["code_verifier"]),
            )
        user_id = configured_account_service().social_identity_login(
            clean_provider,
            identity["subject"],
            display_name=identity["display_name"],
            referral_code=str(state["referral_code"] or ""),
            metadata={"provider": clean_provider, "client": state["client"]},
        )
        ticket = configured_account_service().issue_social_ticket(
            user_id, clean_provider, client_redirect
        )
        return RedirectResponse(
            _append_url_query(
                client_redirect,
                {"social_ticket": ticket, "social_provider": clean_provider},
            ),
            status_code=303,
        )
    except (AccountError, requests.RequestException, ValueError, TypeError, json.JSONDecodeError) as exc:
        configured_account_service().record_social_audit(
            clean_provider, "callback", False, str(exc)
        )
        return RedirectResponse(
            _append_url_query(client_redirect, {"social_error": str(exc)[:200]}),
            status_code=303,
        )


@app.post("/api/auth/social/exchange")
def exchange_social_auth(payload: SocialAuthExchangePayload) -> dict:
    try:
        return configured_account_service().exchange_social_ticket(payload.ticket)
    except AccountError as exc:
        raise account_http_error(exc) from exc


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


@app.get("/api/account/identities")
def list_account_identities(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> list[dict]:
    return configured_account_service().list_identities(principal)


@app.post("/api/account/identities/verify")
def bind_account_identity(
    payload: AccountAuthCodeVerifyPayload,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> list[dict]:
    try:
        return configured_account_service().bind_identity(
            principal, payload.channel, payload.identifier, payload.code
        )
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/account/team")
def get_account_team(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    return configured_account_service().team(principal)


@app.post("/api/account/team/members")
def add_account_team_member(
    payload: AccountTeamMemberPayload,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().add_team_member(principal, payload.user_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.delete("/api/account/team/members/{user_id}")
def remove_account_team_member(
    user_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().remove_team_member(principal, user_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/account/usage")
def get_account_usage(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    return configured_account_service().usage_summary(principal)


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


@app.get("/api/account/meetings/{meeting_id}/images")
def list_account_meeting_images(
    meeting_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> list[dict]:
    clean_meeting_id = normalized_account_meeting_id(meeting_id)
    try:
        return configured_account_service().list_meeting_images(principal, clean_meeting_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.put("/api/account/meetings/{meeting_id}/images/{image_id}")
async def upload_account_meeting_image(
    meeting_id: str,
    image_id: str,
    file: Annotated[UploadFile, File()],
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
    x_image_name: Annotated[str | None, Header(alias="X-Image-Name")] = None,
    x_image_updated_at: Annotated[str | None, Header(alias="X-Image-Updated-At")] = None,
) -> dict:
    clean_meeting_id = normalized_account_meeting_id(meeting_id)
    clean_image_id = normalized_account_image_id(image_id)
    content_type = (file.content_type or "application/octet-stream").lower()
    if not content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="会议附件必须是图片")
    try:
        content = await file.read(ACCOUNT_MEDIA_MAX_BYTES + 1)
    finally:
        await file.close()
    if not content:
        raise HTTPException(status_code=400, detail="图片内容为空")
    if len(content) > ACCOUNT_MEDIA_MAX_BYTES:
        raise HTTPException(status_code=413, detail="图片超过大小限制")
    try:
        updated_at = max(0, int(x_image_updated_at or "0"))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="图片更新时间无效") from exc
    target = account_media_path(principal.user_id, clean_meeting_id, clean_image_id)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".part")
    temporary.write_bytes(content)
    temporary.replace(target)
    try:
        metadata = configured_account_service().upsert_meeting_image(
            principal,
            clean_meeting_id,
            clean_image_id,
            filename=unquote(x_image_name or file.filename or clean_image_id)[:255],
            content_type=content_type,
            bytes_count=len(content),
            updated_at=updated_at or int(time.time() * 1000),
        )
        return {**metadata, "download_path": f"/api/account/meetings/{clean_meeting_id}/images/{clean_image_id}"}
    except AccountError as exc:
        with contextlib.suppress(OSError):
            target.unlink()
        raise account_http_error(exc) from exc


@app.get("/api/account/meetings/{meeting_id}/images/{image_id}")
def download_account_meeting_image(
    meeting_id: str,
    image_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> FileResponse:
    clean_meeting_id = normalized_account_meeting_id(meeting_id)
    clean_image_id = normalized_account_image_id(image_id)
    try:
        items = configured_account_service().list_meeting_images(principal, clean_meeting_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc
    metadata = next((item for item in items if item["image_id"] == clean_image_id), None)
    if metadata is None:
        raise HTTPException(status_code=404, detail="图片不存在")
    target = account_media_path(principal.user_id, clean_meeting_id, clean_image_id)
    if not target.is_file():
        raise HTTPException(status_code=404, detail="图片文件不存在")
    return FileResponse(
        target,
        media_type=str(metadata.get("content_type") or "application/octet-stream"),
        filename=str(metadata.get("filename") or clean_image_id),
        headers={"Cache-Control": "private, max-age=3600"},
    )


@app.delete("/api/account/meetings/{meeting_id}/images/{image_id}")
def delete_account_meeting_image(
    meeting_id: str,
    image_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    clean_meeting_id = normalized_account_meeting_id(meeting_id)
    clean_image_id = normalized_account_image_id(image_id)
    try:
        result = configured_account_service().delete_meeting_image(principal, clean_meeting_id, clean_image_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc
    with contextlib.suppress(OSError):
        account_media_path(principal.user_id, clean_meeting_id, clean_image_id).unlink()
    return result


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
    x_usage_key: Annotated[str | None, Header(alias="X-Usage-Key")] = None,
) -> Response:
    usage_key = (x_usage_key or "").strip()
    if not usage_key:
        usage_key = (
            f"stt:{x_meeting_id}:full"
            if x_meeting_id
            else f"stt:upload:{uuid.uuid4()}"
        )
    canonical_usage_key = AccountService.canonical_stt_usage_key(
        principal.user_id,
        usage_key,
        meeting_id=x_meeting_id,
    )
    legacy_keys = []
    if x_usage_key and x_usage_key.strip():
        legacy_keys.append(x_usage_key.strip())
    elif x_meeting_id:
        legacy_keys.append(f"stt:{principal.user_id}:{x_meeting_id}:full")
    try:
        account_service = configured_account_service()
        legacy_usage = None
        for legacy_key in legacy_keys:
            if legacy_key == canonical_usage_key:
                continue
            legacy_usage = account_service.usage_event_for_user(
                principal.user_id,
                legacy_key,
            )
            if legacy_usage is not None:
                break
        account_service.ensure_stt_available(
            principal,
            idempotency_key=(
                str(legacy_usage["idempotency_key"])
                if legacy_usage is not None
                else canonical_usage_key
            ),
        )
        credentials = account_service.session_credentials(principal)
    except AccountError as exc:
        raise account_http_error(exc) from exc
    stt_token = str(credentials.get("stt_access_token") or "").strip()
    if not stt_token:
        raise HTTPException(status_code=503, detail="STT access token is unavailable")
    headers = {"Authorization": f"Bearer {stt_token}"}
    if x_meeting_id:
        headers["X-Meeting-Id"] = x_meeting_id
    headers["X-Usage-Key"] = usage_key
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
    if not isinstance(payload, dict):
        raise HTTPException(status_code=502, detail="STT service returned an invalid payload")
    if not payload.get("usage"):
        duration_ms = 0
        with contextlib.suppress(TypeError, ValueError):
            duration_ms = max(0, int(payload.get("duration_ms") or 0))
        if duration_ms <= 0:
            raise HTTPException(status_code=502, detail="STT service did not settle account usage")
        try:
            payload = dict(payload)
            account_service = configured_account_service()
            if legacy_usage is None:
                for legacy_key in legacy_keys:
                    if legacy_key == canonical_usage_key:
                        continue
                    legacy_usage = account_service.usage_event_for_user(
                        principal.user_id,
                        legacy_key,
                    )
                    if legacy_usage is not None:
                        break
            payload["usage"] = legacy_usage or account_service.record_stt_usage(
                principal,
                duration_ms=duration_ms,
                meeting_id=x_meeting_id,
                idempotency_key=canonical_usage_key,
            )
        except AccountError as exc:
            raise account_http_error(exc) from exc
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


@app.get("/api/account/payments/alipay/status")
def account_alipay_status(
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    del principal
    try:
        config = load_alipay_config()
        return {
            "provider": "alipay", "product": "app_pay", "enabled": config.enabled,
            "configured": config.is_ready, "environment": config.environment,
            "gateway": config.gateway, "notify_configured": bool(config.notify_url),
            "notify_https": config.notify_ready,
            "seller_configured": bool(config.seller_id or config.seller_email),
        }
    except AlipayConfigurationError as exc:
        return {
            "provider": "alipay", "product": "app_pay", "enabled": False,
            "configured": False, "environment": ALIPAY_ENVIRONMENT,
            "notify_configured": bool(ALIPAY_NOTIFY_URL),
            "notify_https": ALIPAY_NOTIFY_URL.startswith("https://"), "error": str(exc),
        }


@app.post("/api/account/orders/{order_id}/alipay/pay")
def create_alipay_app_payment(
    order_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    service = configured_account_service()
    try:
        config = load_alipay_config()
        if not config.is_ready:
            raise AlipayConfigurationError(config.unavailable_reason())
        order = service.get_order_for_payment(principal, order_id)
        transaction = service.create_alipay_transaction(
            principal, order_id, out_trade_no=_new_alipay_out_trade_no(),
            subject=f"智悟本积分套餐-{order['plan_name']}", environment=config.environment,
        )
        if transaction["status"] == "paid":
            raise AccountError("该订单已完成支付")
        order_str = AlipayPaymentClient(config).create_app_order(
            transaction["out_trade_no"], transaction["amount_cents"], transaction["subject"]
        )
        service.update_alipay_transaction(transaction["out_trade_no"], status="created", last_error="")
        return {
            "provider": "alipay", "product": "app_pay", "environment": config.environment,
            "order_id": order_id, "out_trade_no": transaction["out_trade_no"],
            "amount_cents": transaction["amount_cents"], "orderStr": order_str,
            "payment_status": "created",
        }
    except AccountError as exc:
        raise account_http_error(exc) from exc
    except (AlipayConfigurationError, AlipayGatewayError) as exc:
        raise _alipay_gateway_http_error(exc) from exc


@app.get("/api/account/orders/{order_id}/alipay")
def get_alipay_payment(
    order_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    try:
        return configured_account_service().alipay_transaction_for_user(principal, order_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/account/orders/{order_id}/alipay/query")
def query_alipay_payment(
    order_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_account_principal)],
) -> dict:
    service = configured_account_service()
    try:
        config = load_alipay_config()
        transaction = service.alipay_transaction_for_user(principal, order_id)
        response = AlipayPaymentClient(config).query(
            out_trade_no=transaction["out_trade_no"], trade_no=transaction.get("trade_no") or ""
        )
        returned_out_trade_no = str(response.get("out_trade_no") or transaction["out_trade_no"])
        returned_trade_no = str(response.get("trade_no") or transaction.get("trade_no") or "")
        if returned_out_trade_no != transaction["out_trade_no"]:
            raise AlipayGatewayError("支付宝查询结果与本地订单不匹配")
        status = _alipay_response_status(response)
        amount = response.get("total_amount")
        if amount is not None and alipay_amount_cents(amount) != int(transaction["amount_cents"]):
            raise AlipayGatewayError("支付宝查询金额与本地订单不一致")
        query_payload = {"source": "query", "out_trade_no": returned_out_trade_no,
                         "trade_no": returned_trade_no, "trade_status": status,
                         "total_amount": amount}
        payload_hash, payload_json = _alipay_payload_for_storage(
            {key: str(value or "") for key, value in query_payload.items()}
        )
        processed = service.process_alipay_notification(
            out_trade_no=returned_out_trade_no, trade_no=returned_trade_no,
            trade_status=status, notify_id="", payload_hash=payload_hash,
            payload_json=payload_json, paid=status in {"TRADE_SUCCESS", "TRADE_FINISHED"},
        )
        return {"payment": service.alipay_transaction_for_user(principal, order_id),
                "alipay": response, "processed": processed}
    except AccountError as exc:
        raise account_http_error(exc) from exc
    except (AlipayConfigurationError, AlipayGatewayError, ValueError) as exc:
        raise _alipay_gateway_http_error(exc) from exc


@app.post("/api/payment/alipay/notify")
async def alipay_notify(request: Request) -> Response:
    params = {str(key): str(value) for key, value in (await request.form()).multi_items()}
    out_trade_no = params.get("out_trade_no", "")
    try:
        config = load_alipay_config()
        service = configured_account_service()
        transaction = service.transaction_by_out_trade_no_for_notify(out_trade_no)
        if not config.is_ready:
            payment_logger.warning(
                "Alipay notify rejected: gateway not ready (%s), out_trade_no=%s",
                config.unavailable_reason(), out_trade_no,
            )
            return Response("fail", media_type="text/plain")
        if not verify_notify_signature(params, config):
            payment_logger.warning(
                "Alipay notify rejected: invalid signature, out_trade_no=%s, notify_id=%s",
                out_trade_no, params.get("notify_id", ""),
            )
            return Response("fail", media_type="text/plain")
        if not notify_business_matches(
            params, config=config, out_trade_no=out_trade_no,
            amount_cents_expected=int(transaction["amount_cents"]),
        ):
            payment_logger.warning(
                "Alipay notify rejected: business mismatch, out_trade_no=%s, "
                "app_id_match=%s, amount=%s (expected_cents=%s), seller_configured=%s",
                out_trade_no,
                params.get("app_id") == config.app_id,
                params.get("total_amount", ""),
                transaction["amount_cents"],
                bool(config.seller_id or config.seller_email),
            )
            return Response("fail", media_type="text/plain")
        payload_hash, payload_json = _alipay_payload_for_storage(params)
        service.process_alipay_notification(
            out_trade_no=out_trade_no, trade_no=params.get("trade_no", ""),
            trade_status=params.get("trade_status", ""), notify_id=params.get("notify_id", ""),
            payload_hash=payload_hash, payload_json=payload_json,
            paid=is_paid_notification(params),
        )
        return Response("success", media_type="text/plain")
    except Exception:
        payment_logger.exception(
            "Alipay notify processing failed, out_trade_no=%s", out_trade_no
        )
        return Response("fail", media_type="text/plain")


@app.post("/api/admin/accounts/orders/{order_id}/alipay/refund")
def refund_alipay_payment(
    order_id: str,
    payload: AlipayRefundPayload,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    service = configured_account_service()
    try:
        config = load_alipay_config()
        transaction = service.alipay_transaction_for_admin(principal, order_id)
        if transaction["status"] != "paid":
            raise AccountError("仅支持对已支付订单发起退款")
        refund_amount = int(payload.refund_amount_cents or transaction["amount_cents"])
        if refund_amount < 1 or refund_amount > int(transaction["amount_cents"]):
            raise AccountError("退款金额超出订单金额")
        request_no = transaction.get("refund_request_no") or f"RF{int(time.time())}{uuid.uuid4().hex[:16]}"
        previous_refund_amount = int(transaction.get("refund_amount_cents") or 0)
        if request_no == transaction.get("refund_request_no") and previous_refund_amount and previous_refund_amount != refund_amount:
            raise AccountConflictError("同一退款请求号不能更换退款金额")
        # Reserve the idempotency key before the network call. If the process
        # times out after Alipay accepts the request, a retry will reuse the
        # same request number and amount instead of issuing a second refund.
        service.update_alipay_transaction(
            transaction["out_trade_no"], status="paid",
            refund_request_no=request_no, refund_amount_cents=refund_amount,
        )
        response = AlipayPaymentClient(config).refund(
            out_trade_no=transaction["out_trade_no"], trade_no=transaction.get("trade_no") or "",
            refund_amount=refund_amount, out_request_no=request_no,
        )
        if str(response.get("code", "")) != "10000":
            # A non-10000 response means Alipay did not accept the refund
            # request. Keep the payment refundable and preserve the gateway
            # reason for an operator retry with the same request number.
            error = str(response.get("sub_msg") or response.get("msg") or "支付宝未受理退款请求")
            service.update_alipay_transaction(
                transaction["out_trade_no"], status="paid", last_error=error,
            )
            raise AlipayGatewayError(error)
        service.update_alipay_transaction(
            transaction["out_trade_no"], status="refund_pending",
            refund_request_no=request_no, refund_amount_cents=refund_amount,
        )
        return {"order_id": order_id, "refund_request_no": request_no,
                "refund_amount_cents": refund_amount, "alipay": response}
    except AccountError as exc:
        raise account_http_error(exc) from exc
    except (AlipayConfigurationError, AlipayGatewayError) as exc:
        raise _alipay_gateway_http_error(exc) from exc


@app.post("/api/admin/accounts/orders/{order_id}/alipay/refund/query")
def query_alipay_refund(
    order_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    service = configured_account_service()
    try:
        config = load_alipay_config()
        transaction = service.alipay_transaction_for_admin(principal, order_id)
        request_no = transaction.get("refund_request_no")
        if not request_no:
            raise AccountError("该订单暂无退款请求")
        response = AlipayPaymentClient(config).refund_query(
            out_trade_no=transaction["out_trade_no"], trade_no=transaction.get("trade_no") or "",
            out_request_no=request_no,
        )
        if response.get("refund_status") == "REFUND_SUCCESS":
            service.update_alipay_transaction(transaction["out_trade_no"], status="refunded")
        return {"order_id": order_id,
                "payment": service.alipay_transaction_for_admin(principal, order_id),
                "alipay": response}
    except AccountError as exc:
        raise account_http_error(exc) from exc
    except (AlipayConfigurationError, AlipayGatewayError) as exc:
        raise _alipay_gateway_http_error(exc) from exc


@app.post("/api/admin/accounts/orders/{order_id}/alipay/close")
def close_alipay_payment(
    order_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    service = configured_account_service()
    try:
        config = load_alipay_config()
        transaction = service.alipay_transaction_for_admin(principal, order_id)
        if transaction["status"] != "created":
            raise AccountError("仅支持关闭待支付订单")
        response = AlipayPaymentClient(config).close(
            out_trade_no=transaction["out_trade_no"], trade_no=transaction.get("trade_no") or ""
        )
        if str(response.get("code", "")) == "10000":
            service.update_alipay_transaction(
                transaction["out_trade_no"], status="closed", trade_status="TRADE_CLOSED"
            )
        return {"order_id": order_id,
                "payment": service.alipay_transaction_for_admin(principal, order_id),
                "alipay": response}
    except AccountError as exc:
        raise account_http_error(exc) from exc
    except (AlipayConfigurationError, AlipayGatewayError) as exc:
        raise _alipay_gateway_http_error(exc) from exc


@app.get("/api/admin/accounts/users")
def list_registered_users(
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> list[dict]:
    try:
        return configured_account_service().admin_list_users(principal)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/admin/accounts/social-auth-audit")
def social_auth_audit(
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
    limit: int = 200,
) -> list[dict]:
    try:
        return configured_account_service().admin_social_auth_audit(principal, limit)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.patch("/api/admin/accounts/users/{user_id}")
def update_registered_user(
    user_id: str,
    payload: AccountUserStatePayload,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
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
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    try:
        return configured_account_service().delete_user(principal, user_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.get("/api/admin/accounts/orders")
def list_recharge_orders(
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
    status: str | None = None,
) -> list[dict]:
    try:
        return configured_account_service().admin_list_orders(principal, status)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/admin/accounts/orders/{order_id}/approve")
def approve_recharge_order(
    order_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
) -> dict:
    try:
        return configured_account_service().approve_order(principal, order_id)
    except AccountError as exc:
        raise account_http_error(exc) from exc


@app.post("/api/admin/accounts/orders/{order_id}/reject")
def reject_recharge_order(
    order_id: str,
    principal: Annotated[AccountPrincipal, Depends(require_growth_admin_principal)],
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
    model = payload.model or "large-v3-turbo"
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
