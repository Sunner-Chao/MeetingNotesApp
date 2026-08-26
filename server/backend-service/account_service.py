#!/usr/bin/env python3
"""Database-backed accounts, sessions, plans, and manually approved recharge orders."""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import re
import secrets
import sqlite3
import sys
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, Iterator

SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from common.account_stt_token import issue_account_stt_token


class AccountError(Exception):
    status_code = 400


class AccountAuthError(AccountError):
    status_code = 401


class AccountPermissionError(AccountError):
    status_code = 403


class AccountConflictError(AccountError):
    status_code = 409


class AccountNotFoundError(AccountError):
    status_code = 404


class AccountDeliveryUnavailableError(AccountError):
    status_code = 503


@dataclass(frozen=True)
class AccountPrincipal:
    user_id: str
    session_id: str
    username: str
    role: str

    @property
    def is_admin(self) -> bool:
        return self.role == "admin"


class AccountService:
    def __init__(
        self,
        db_path: Path,
        *,
        token_secret: str,
        plans_path: Path | None = None,
        session_ttl_sec: int = 30 * 24 * 60 * 60,
        admin_username: str = "admin",
        admin_password: str = "",
        admin_request_limit: int = 10_000_000,
        free_request_limit: int = 10,
        free_plan_code: str = "free",
        free_plan_name: str = "免费账户",
        stt_token_ttl_sec: int = 12 * 60 * 60,
        profile_name_max_length: int = 40,
        profile_avatar_max_bytes: int = 262_144,
        auth_code_sender: Callable[[str, str, str], None] | None = None,
        expose_auth_code: bool = False,
        auth_code_ttl_sec: int = 5 * 60,
        auth_code_cooldown_sec: int = 60,
        auth_code_max_attempts: int = 5,
        free_stt_minutes: int = 0,
        free_ai_credits: int = 0,
        free_points: int = 1000,
        stt_points_per_minute: int = 10,
        ai_summary_points: int = 30,
        ai_chat_points: int = 10,
    ) -> None:
        self.db_path = Path(db_path)
        self.token_secret = token_secret.strip()
        self.plans_path = Path(plans_path) if plans_path else None
        self.session_ttl_sec = max(3600, session_ttl_sec)
        self.admin_username = admin_username.strip() or "admin"
        self.admin_password = admin_password
        self.admin_request_limit = max(1, admin_request_limit)
        self.free_request_limit = max(0, free_request_limit)
        self.free_plan_code = free_plan_code.strip() or "free"
        self.free_plan_name = free_plan_name.strip() or "免费账户"
        self.stt_token_ttl_sec = max(300, stt_token_ttl_sec)
        self.profile_name_max_length = max(1, profile_name_max_length)
        self.profile_avatar_max_bytes = max(1, profile_avatar_max_bytes)
        self.auth_code_sender = auth_code_sender
        self.expose_auth_code = expose_auth_code
        self.auth_code_ttl_sec = max(60, auth_code_ttl_sec)
        self.auth_code_cooldown_sec = max(1, auth_code_cooldown_sec)
        self.auth_code_max_attempts = max(1, auth_code_max_attempts)
        self.free_stt_minutes = max(0, free_stt_minutes)
        self.free_ai_credits = max(0, free_ai_credits)
        self.free_points = max(0, free_points)
        self.stt_points_per_minute = max(1, stt_points_per_minute)
        self.ai_summary_points = max(1, ai_summary_points)
        self.ai_chat_points = max(1, ai_chat_points)

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.db_path, timeout=30)
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

    @staticmethod
    def _table_exists(conn: sqlite3.Connection, name: str) -> bool:
        return conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            (name,),
        ).fetchone() is not None

    def initialize(
        self,
        *,
        bootstrap_admin: bool = True,
        seed_plans: bool = True,
    ) -> None:
        if not self.token_secret:
            raise RuntimeError("ACCOUNT_TOKEN_SECRET must be configured")
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    username_normalized TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    password_salt TEXT NOT NULL,
                    role TEXT NOT NULL CHECK(role IN ('admin', 'user')),
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS user_sessions (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,
                    expires_at INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS user_entitlements (
                    user_id TEXT PRIMARY KEY,
                    vip_enabled INTEGER NOT NULL DEFAULT 0,
                    vip_expires_at INTEGER,
                    construction_logs_unlocked INTEGER NOT NULL DEFAULT 0,
                    duration_days INTEGER NOT NULL DEFAULT 30,
                    quota_granted INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS account_plans (
                    id TEXT PRIMARY KEY,
                    code TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    price_cents INTEGER NOT NULL,
                    quota_amount INTEGER NOT NULL,
                    points INTEGER NOT NULL DEFAULT 0,
                    construction_logs_unlocked INTEGER NOT NULL DEFAULT 0,
                    duration_days INTEGER NOT NULL DEFAULT 30,
                    subscription_started_at INTEGER,
                    subscription_expires_at INTEGER,
                    active INTEGER NOT NULL DEFAULT 1,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS recharge_orders (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    plan_id TEXT NOT NULL,
                    amount_cents INTEGER NOT NULL,
                    quota_amount INTEGER NOT NULL,
                    points INTEGER NOT NULL DEFAULT 0,
                    construction_logs_unlocked INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL CHECK(status IN ('pending', 'approved', 'rejected')),
                    created_at INTEGER NOT NULL,
                    decided_at INTEGER,
                    decided_by TEXT,
                    FOREIGN KEY(user_id) REFERENCES users(id),
                    FOREIGN KEY(plan_id) REFERENCES account_plans(id),
                    FOREIGN KEY(decided_by) REFERENCES users(id)
                );

                CREATE TABLE IF NOT EXISTS account_meetings (
                    user_id TEXT NOT NULL,
                    id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    template_key TEXT NOT NULL CHECK(template_key IN ('project', 'administrative', 'brainstorming', 'inspection')),
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    duration_seconds INTEGER NOT NULL DEFAULT 0,
                    transcript TEXT NOT NULL DEFAULT '',
                    report TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(user_id, id),
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS account_meeting_tombstones (
                    user_id TEXT NOT NULL,
                    meeting_id TEXT NOT NULL,
                    deleted_at INTEGER NOT NULL,
                    PRIMARY KEY(user_id, meeting_id),
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS account_identities (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    provider TEXT NOT NULL CHECK(provider IN ('email', 'phone', 'wechat', 'feishu', 'password')),
                    subject TEXT NOT NULL,
                    verified INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    UNIQUE(provider, subject),
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS auth_verification_codes (
                    id TEXT PRIMARY KEY,
                    channel TEXT NOT NULL CHECK(channel IN ('email', 'phone')),
                    subject TEXT NOT NULL,
                    purpose TEXT NOT NULL CHECK(purpose IN ('login', 'register', 'bind', 'reset_password')),
                    code_hash TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    max_attempts INTEGER NOT NULL,
                    consumed_at INTEGER,
                    created_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS account_usage_balances (
                    user_id TEXT PRIMARY KEY,
                    included_stt_seconds INTEGER NOT NULL DEFAULT 0,
                    stt_seconds_used INTEGER NOT NULL DEFAULT 0,
                    ai_credits_granted INTEGER NOT NULL DEFAULT 0,
                    ai_credits_used INTEGER NOT NULL DEFAULT 0,
                    points_granted INTEGER NOT NULL DEFAULT 0,
                    points_used INTEGER NOT NULL DEFAULT 0,
                    team_seats INTEGER NOT NULL DEFAULT 1,
                    period_start INTEGER NOT NULL,
                    period_end INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS account_usage_events (
                    id TEXT PRIMARY KEY,
                    idempotency_key TEXT NOT NULL UNIQUE,
                    user_id TEXT NOT NULL,
                    meeting_id TEXT,
                    kind TEXT NOT NULL CHECK(kind IN ('stt_seconds', 'ai_summary', 'ai_chat')),
                    quantity INTEGER NOT NULL,
                    unit TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('reserved', 'succeeded', 'refunded')),
                    charged INTEGER NOT NULL DEFAULT 0,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    created_at INTEGER NOT NULL,
                    completed_at INTEGER,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS account_teams (
                    id TEXT PRIMARY KEY,
                    owner_user_id TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    seat_limit INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(owner_user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS account_team_members (
                    team_id TEXT NOT NULL,
                    user_id TEXT NOT NULL UNIQUE,
                    role TEXT NOT NULL CHECK(role IN ('owner', 'member')),
                    joined_at INTEGER NOT NULL,
                    PRIMARY KEY(team_id, user_id),
                    FOREIGN KEY(team_id) REFERENCES account_teams(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS growth_referral_codes (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL UNIQUE,
                    code TEXT NOT NULL UNIQUE,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    max_uses INTEGER NOT NULL DEFAULT 0,
                    used_count INTEGER NOT NULL DEFAULT 0,
                    expires_at INTEGER,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS growth_referral_bindings (
                    id TEXT PRIMARY KEY,
                    referrer_user_id TEXT NOT NULL,
                    referred_user_id TEXT NOT NULL UNIQUE,
                    code TEXT NOT NULL,
                    source TEXT NOT NULL DEFAULT 'register',
                    created_at INTEGER NOT NULL,
                    qualified_at INTEGER,
                    rewarded_at INTEGER,
                    FOREIGN KEY(referrer_user_id) REFERENCES users(id) ON DELETE CASCADE,
                    FOREIGN KEY(referred_user_id) REFERENCES users(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS growth_redemption_batches (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    code_type TEXT NOT NULL DEFAULT 'gift',
                    reward_type TEXT NOT NULL,
                    reward_payload_json TEXT NOT NULL DEFAULT '{}',
                    total_count INTEGER NOT NULL,
                    created_by TEXT,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY(created_by) REFERENCES users(id) ON DELETE SET NULL
                );
                CREATE TABLE IF NOT EXISTS growth_redemption_codes (
                    id TEXT PRIMARY KEY,
                    batch_id TEXT NOT NULL,
                    code TEXT NOT NULL UNIQUE,
                    status TEXT NOT NULL DEFAULT 'unused',
                    max_uses INTEGER NOT NULL DEFAULT 1,
                    used_count INTEGER NOT NULL DEFAULT 0,
                    expires_at INTEGER,
                    created_at INTEGER NOT NULL,
                    disabled_at INTEGER,
                    FOREIGN KEY(batch_id) REFERENCES growth_redemption_batches(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS growth_redemption_claims (
                    id TEXT PRIMARY KEY,
                    code_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    reward_type TEXT NOT NULL,
                    reward_payload_json TEXT NOT NULL DEFAULT '{}',
                    status TEXT NOT NULL DEFAULT 'fulfilled',
                    created_at INTEGER NOT NULL,
                    fulfilled_at INTEGER,
                    UNIQUE(code_id, user_id),
                    FOREIGN KEY(code_id) REFERENCES growth_redemption_codes(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS growth_campaigns (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    campaign_type TEXT NOT NULL,
                    summary TEXT NOT NULL DEFAULT '',
                    rules_json TEXT NOT NULL DEFAULT '{}',
                    reward_pool_json TEXT NOT NULL DEFAULT '{}',
                    starts_at INTEGER NOT NULL,
                    ends_at INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'draft',
                    created_by TEXT,
                    created_at INTEGER NOT NULL,
                    settled_at INTEGER,
                    FOREIGN KEY(created_by) REFERENCES users(id) ON DELETE SET NULL
                );
                CREATE TABLE IF NOT EXISTS growth_campaign_entries (
                    id TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    score INTEGER NOT NULL DEFAULT 0,
                    rank INTEGER,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    created_at INTEGER NOT NULL,
                    UNIQUE(campaign_id, user_id),
                    FOREIGN KEY(campaign_id) REFERENCES growth_campaigns(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS growth_campaign_actions (
                    id TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    action_type TEXT NOT NULL,
                    action_key TEXT NOT NULL,
                    action_date TEXT NOT NULL DEFAULT '',
                    score INTEGER NOT NULL DEFAULT 0,
                    payload_json TEXT NOT NULL DEFAULT '{}',
                    status TEXT NOT NULL DEFAULT 'accepted',
                    created_at INTEGER NOT NULL,
                    UNIQUE(campaign_id, user_id, action_type, action_key),
                    FOREIGN KEY(campaign_id) REFERENCES growth_campaigns(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS growth_reward_ledger (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    reward_type TEXT NOT NULL,
                    quantity INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'fulfilled',
                    idempotency_key TEXT NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS growth_private_channels (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    qr_image_url TEXT NOT NULL DEFAULT '',
                    join_url TEXT NOT NULL DEFAULT '',
                    short_url TEXT NOT NULL DEFAULT '',
                    slogan TEXT NOT NULL DEFAULT '',
                    reward_type TEXT NOT NULL DEFAULT 'points',
                    reward_payload_json TEXT NOT NULL DEFAULT '{}',
                    valid_until INTEGER,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    updated_by TEXT,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(updated_by) REFERENCES users(id) ON DELETE SET NULL
                );
                CREATE TABLE IF NOT EXISTS growth_channel_events (
                    id TEXT PRIMARY KEY,
                    channel_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    user_id TEXT,
                    campaign_id TEXT,
                    source TEXT NOT NULL DEFAULT 'pwa',
                    created_at INTEGER NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    FOREIGN KEY(channel_id) REFERENCES growth_private_channels(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE SET NULL
                );

                CREATE INDEX IF NOT EXISTS index_sessions_user
                ON user_sessions(user_id, expires_at DESC);
                CREATE INDEX IF NOT EXISTS index_orders_user_created
                ON recharge_orders(user_id, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_orders_status_created
                ON recharge_orders(status, created_at ASC);
                CREATE INDEX IF NOT EXISTS index_account_meetings_user_updated
                ON account_meetings(user_id, updated_at DESC);
                CREATE INDEX IF NOT EXISTS index_account_identities_user
                ON account_identities(user_id, created_at ASC);
                CREATE INDEX IF NOT EXISTS index_auth_codes_subject_created
                ON auth_verification_codes(channel, subject, purpose, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_usage_events_user_created
                ON account_usage_events(user_id, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_usage_events_meeting_kind
                ON account_usage_events(user_id, meeting_id, kind, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_growth_events_channel_created
                ON growth_channel_events(channel_id, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_growth_claims_user_created
                ON growth_redemption_claims(user_id, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_growth_actions_campaign_score
                ON growth_campaign_actions(campaign_id, score DESC, created_at ASC);
                """
            )
            self._ensure_profile_columns(conn)
            self._ensure_billing_columns(conn)
            self._ensure_auth_code_purpose_constraint(conn)
            self._ensure_account_meeting_template_constraint(conn)
            if seed_plans:
                self._seed_plans(conn)
            if bootstrap_admin and self.admin_password:
                self._bootstrap_admin(conn)
            self._ensure_free_entitlements(conn)
            self._ensure_usage_balances(conn)
            self._ensure_growth_defaults(conn)

    def register(self, username: str, password: str, referral_code: str | None = None) -> dict:
        clean_username = self._validate_username(username)
        self._validate_password(password)
        user_id = str(uuid.uuid4())
        now = int(time.time())
        salt = secrets.token_bytes(16)
        password_hash = self._password_hash(password, salt)
        with self._connect() as conn:
            try:
                conn.execute(
                    """
                    INSERT INTO users (
                        id, username, username_normalized, password_hash,
                        password_salt, role, enabled, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'user', 1, ?)
                    """,
                    (
                        user_id,
                        clean_username,
                        clean_username.casefold(),
                        password_hash,
                        salt.hex(),
                        now,
                    ),
                )
            except sqlite3.IntegrityError as exc:
                raise AccountConflictError("用户名已存在") from exc
            conn.execute(
                """
                INSERT INTO user_entitlements (
                    user_id, vip_enabled, construction_logs_unlocked,
                    quota_granted, updated_at
                ) VALUES (?, 0, 0, ?, ?)
                """,
                (user_id, self.free_request_limit, now),
            )
            conn.execute(
                """
                INSERT INTO account_identities (
                    id, user_id, provider, subject, verified, created_at
                ) VALUES (?, ?, 'password', ?, 1, ?)
                """,
                (str(uuid.uuid4()), user_id, clean_username.casefold(), now),
            )
            self._ensure_usage_balance(conn, user_id, now)
            self._initialize_growth_user(conn, user_id, now, referral_code)
            return self._create_session(conn, user_id)

    def register_with_identity(
        self,
        username: str,
        password: str,
        channel: str,
        identifier: str,
        code: str,
        referral_code: str | None = None,
    ) -> dict:
        clean_username = self._validate_username(username)
        self._validate_password(password)
        clean_channel, subject = self._normalize_identity(channel, identifier)
        if clean_channel != "email":
            raise AccountError("目前仅支持邮箱注册")
        now = int(time.time())
        salt = secrets.token_bytes(16)
        user_id = str(uuid.uuid4())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            self._consume_auth_code(conn, clean_channel, subject, code, "register", now)
            existing = conn.execute(
                "SELECT user_id FROM account_identities WHERE provider = ? AND subject = ?",
                (clean_channel, subject),
            ).fetchone()
            if existing is not None:
                raise AccountConflictError("该邮箱已注册，请直接登录")
            try:
                conn.execute(
                    """
                    INSERT INTO users (
                        id, username, username_normalized, password_hash,
                        password_salt, role, enabled, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'user', 1, ?)
                    """,
                    (
                        user_id,
                        clean_username,
                        clean_username.casefold(),
                        self._password_hash(password, salt),
                        salt.hex(),
                        now,
                    ),
                )
            except sqlite3.IntegrityError as exc:
                raise AccountConflictError("用户名已存在") from exc
            conn.execute(
                "INSERT INTO user_entitlements (user_id, vip_enabled, construction_logs_unlocked, quota_granted, updated_at) VALUES (?, 0, 0, ?, ?)",
                (user_id, self.free_request_limit, now),
            )
            conn.execute(
                "INSERT INTO account_identities (id, user_id, provider, subject, verified, created_at) VALUES (?, ?, ?, ?, 1, ?)",
                (str(uuid.uuid4()), user_id, clean_channel, subject, now),
            )
            self._ensure_usage_balance(conn, user_id, now)
            self._initialize_growth_user(conn, user_id, now, referral_code)
            return self._create_session(conn, user_id)

    def login(self, username: str, password: str) -> dict:
        normalized = username.strip().casefold()
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT id, password_hash, password_salt, enabled
                FROM users WHERE username_normalized = ?
                """,
                (normalized,),
            ).fetchone()
            if row is None or not row["enabled"]:
                raise AccountAuthError("用户名或密码错误")
            if not row["password_hash"] or not row["password_salt"]:
                raise AccountAuthError("用户名或密码错误")
            salt = bytes.fromhex(row["password_salt"])
            actual = self._password_hash(password, salt)
            if not hmac.compare_digest(actual, row["password_hash"]):
                raise AccountAuthError("用户名或密码错误")
            return self._create_session(conn, row["id"])

    def request_auth_code(
        self,
        channel: str,
        identifier: str,
        purpose: str = "login",
    ) -> dict:
        clean_channel, subject = self._normalize_identity(channel, identifier)
        if purpose not in {"login", "register", "bind", "reset_password"}:
            raise AccountError("验证码用途无效")
        if clean_channel == "phone":
            raise AccountDeliveryUnavailableError("手机号验证码服务暂未开放")
        if self.auth_code_sender is None and not self.expose_auth_code:
            raise AccountDeliveryUnavailableError("验证码发送服务尚未配置")

        now = int(time.time())
        code = f"{secrets.randbelow(1_000_000):06d}"
        code_id = str(uuid.uuid4())
        with self._connect() as conn:
            latest = conn.execute(
                """
                SELECT created_at FROM auth_verification_codes
                WHERE channel = ? AND subject = ? AND purpose = ?
                ORDER BY created_at DESC LIMIT 1
                """,
                (clean_channel, subject, purpose),
            ).fetchone()
            if latest is not None:
                retry_after = self.auth_code_cooldown_sec - (now - int(latest["created_at"]))
                if retry_after > 0:
                    raise AccountConflictError(f"验证码发送过于频繁，请在 {retry_after} 秒后重试")
            conn.execute(
                """
                UPDATE auth_verification_codes SET consumed_at = ?
                WHERE channel = ? AND subject = ? AND purpose = ? AND consumed_at IS NULL
                """,
                (now, clean_channel, subject, purpose),
            )
            conn.execute(
                """
                INSERT INTO auth_verification_codes (
                    id, channel, subject, purpose, code_hash, expires_at,
                    attempts, max_attempts, consumed_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, NULL, ?)
                """,
                (
                    code_id,
                    clean_channel,
                    subject,
                    purpose,
                    self._verification_code_hash(code_id, code),
                    now + self.auth_code_ttl_sec,
                    self.auth_code_max_attempts,
                    now,
                ),
            )

        if self.auth_code_sender is not None:
            try:
                self.auth_code_sender(clean_channel, subject, code)
            except AccountDeliveryUnavailableError:
                with self._connect() as conn:
                    conn.execute(
                        "UPDATE auth_verification_codes SET consumed_at = ? WHERE id = ?",
                        (int(time.time()), code_id),
                    )
                raise
            except Exception as exc:
                with self._connect() as conn:
                    conn.execute(
                        "UPDATE auth_verification_codes SET consumed_at = ? WHERE id = ?",
                        (int(time.time()), code_id),
                    )
                raise AccountDeliveryUnavailableError("验证码发送失败，请稍后重试") from exc

        payload = {
            "status": "sent",
            "channel": clean_channel,
            "masked_identifier": self._mask_identity(clean_channel, subject),
            "expires_in": self.auth_code_ttl_sec,
            "retry_after": self.auth_code_cooldown_sec,
        }
        if self.expose_auth_code:
            payload["verification_code"] = code
        return payload

    def verify_auth_code(
        self,
        channel: str,
        identifier: str,
        code: str,
        purpose: str = "login",
    ) -> dict:
        if purpose != "login":
            raise AccountError("该验证码不能用于登录")
        clean_channel, subject = self._normalize_identity(channel, identifier)
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            self._consume_auth_code(conn, clean_channel, subject, code, purpose, now)
            identity = conn.execute(
                """
                SELECT i.user_id, u.enabled
                FROM account_identities i
                JOIN users u ON u.id = i.user_id
                WHERE i.provider = ? AND i.subject = ?
                """,
                (clean_channel, subject),
            ).fetchone()
            if identity is not None:
                if not identity["enabled"]:
                    raise AccountAuthError("账号已停用")
                return self._create_session(conn, identity["user_id"])

            user_id = str(uuid.uuid4())
            username = self._identity_username(clean_channel, subject, user_id)
            conn.execute(
                """
                INSERT INTO users (
                    id, username, username_normalized, password_hash,
                    password_salt, role, enabled, created_at
                ) VALUES (?, ?, ?, '', '', 'user', 1, ?)
                """,
                (user_id, username, username.casefold(), now),
            )
            conn.execute(
                """
                INSERT INTO user_entitlements (
                    user_id, vip_enabled, construction_logs_unlocked,
                    quota_granted, updated_at
                ) VALUES (?, 0, 0, ?, ?)
                """,
                (user_id, self.free_request_limit, now),
            )
            conn.execute(
                """
                INSERT INTO account_identities (
                    id, user_id, provider, subject, verified, created_at
                ) VALUES (?, ?, ?, ?, 1, ?)
                """,
                (str(uuid.uuid4()), user_id, clean_channel, subject, now),
            )
            self._ensure_usage_balance(conn, user_id, now)
            return self._create_session(conn, user_id)

    def bind_identity(
        self,
        principal: AccountPrincipal,
        channel: str,
        identifier: str,
        code: str,
    ) -> list[dict]:
        clean_channel, subject = self._normalize_identity(channel, identifier)
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            self._consume_auth_code(conn, clean_channel, subject, code, "bind", now)
            existing = conn.execute(
                "SELECT user_id FROM account_identities WHERE provider = ? AND subject = ?",
                (clean_channel, subject),
            ).fetchone()
            if existing is not None and existing["user_id"] != principal.user_id:
                raise AccountConflictError("该手机号或邮箱已绑定其他账号")
            conn.execute(
                """
                INSERT INTO account_identities (
                    id, user_id, provider, subject, verified, created_at
                ) VALUES (?, ?, ?, ?, 1, ?)
                ON CONFLICT(provider, subject) DO UPDATE SET verified = 1
                """,
                (str(uuid.uuid4()), principal.user_id, clean_channel, subject, now),
            )
        return self.list_identities(principal)

    def reset_password(
        self,
        channel: str,
        identifier: str,
        code: str,
        new_password: str,
    ) -> dict:
        self._validate_password(new_password)
        clean_channel, subject = self._normalize_identity(channel, identifier)
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            self._consume_auth_code(conn, clean_channel, subject, code, "reset_password", now)
            identity = conn.execute(
                """
                SELECT i.user_id, u.enabled
                FROM account_identities i JOIN users u ON u.id = i.user_id
                WHERE i.provider = ? AND i.subject = ?
                """,
                (clean_channel, subject),
            ).fetchone()
            if identity is None:
                raise AccountNotFoundError("该手机号或邮箱尚未注册")
            if not identity["enabled"]:
                raise AccountAuthError("账号已停用")
            salt = secrets.token_bytes(16)
            conn.execute(
                "UPDATE users SET password_hash = ?, password_salt = ? WHERE id = ?",
                (self._password_hash(new_password, salt), salt.hex(), identity["user_id"]),
            )
            conn.execute("DELETE FROM user_sessions WHERE user_id = ?", (identity["user_id"],))
            conn.execute(
                "UPDATE agent_tokens SET expires_at = ? WHERE id = ?",
                (now, self._agent_token_id(identity["user_id"])),
            )
            password_identity = conn.execute(
                "SELECT 1 FROM account_identities WHERE user_id = ? AND provider = 'password'",
                (identity["user_id"],),
            ).fetchone()
            if password_identity is None:
                username = conn.execute(
                    "SELECT username_normalized FROM users WHERE id = ?", (identity["user_id"],)
                ).fetchone()["username_normalized"]
                conn.execute(
                    """
                    INSERT INTO account_identities (id, user_id, provider, subject, verified, created_at)
                    VALUES (?, ?, 'password', ?, 1, ?)
                    """,
                    (str(uuid.uuid4()), identity["user_id"], username, now),
                )
            return {"status": "password_reset"}

    def team(self, principal: AccountPrincipal) -> dict:
        now = int(time.time())
        with self._connect() as conn:
            self._refresh_subscription(conn, principal.user_id, now)
            return self._team_payload(conn, principal.user_id, now)

    def add_team_member(self, principal: AccountPrincipal, member_user_id: str) -> dict:
        clean_member_id = member_user_id.strip()
        if not clean_member_id:
            raise AccountError("成员 ID 不能为空")
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            self._refresh_subscription(conn, principal.user_id, now)
            team = self._ensure_team(conn, principal.user_id, now)
            if team["owner_user_id"] != principal.user_id and not principal.is_admin:
                raise AccountPermissionError("仅团队所有者可以管理成员")
            member = conn.execute(
                "SELECT id, enabled FROM users WHERE id = ?", (clean_member_id,)
            ).fetchone()
            if member is None or not member["enabled"]:
                raise AccountNotFoundError("成员账号不存在或已停用")
            existing = conn.execute(
                "SELECT team_id FROM account_team_members WHERE user_id = ?", (clean_member_id,)
            ).fetchone()
            if existing is not None:
                if existing["team_id"] == team["id"]:
                    return self._team_payload(conn, principal.user_id, now)
                raise AccountConflictError("该账号已加入其他团队")
            member_count = int(
                conn.execute(
                    "SELECT COUNT(*) AS count FROM account_team_members WHERE team_id = ?", (team["id"],)
                ).fetchone()["count"]
            )
            if member_count >= int(team["seat_limit"]):
                raise AccountPermissionError("团队席位已满，请升级套餐后再邀请成员")
            conn.execute(
                "INSERT INTO account_team_members (team_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)",
                (team["id"], clean_member_id, now),
            )
            return self._team_payload(conn, principal.user_id, now)

    def remove_team_member(self, principal: AccountPrincipal, member_user_id: str) -> dict:
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            team = self._ensure_team(conn, principal.user_id, now)
            if team["owner_user_id"] != principal.user_id and not principal.is_admin:
                raise AccountPermissionError("仅团队所有者可以管理成员")
            if member_user_id == principal.user_id:
                raise AccountConflictError("团队所有者不能移除自己")
            deleted = conn.execute(
                "DELETE FROM account_team_members WHERE team_id = ? AND user_id = ? AND role = 'member'",
                (team["id"], member_user_id),
            )
            if deleted.rowcount != 1:
                raise AccountNotFoundError("团队成员不存在")
            return self._team_payload(conn, principal.user_id, now)

    def list_identities(self, principal: AccountPrincipal) -> list[dict]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT provider, subject, verified, created_at
                FROM account_identities WHERE user_id = ? ORDER BY created_at ASC
                """,
                (principal.user_id,),
            ).fetchall()
        return [
            {
                "provider": row["provider"],
                "subject": self._mask_identity(row["provider"], row["subject"]),
                "verified": bool(row["verified"]),
                "created_at": row["created_at"],
            }
            for row in rows
        ]

    def logout(self, principal: AccountPrincipal) -> None:
        with self._connect() as conn:
            conn.execute("DELETE FROM user_sessions WHERE id = ?", (principal.session_id,))
            row = conn.execute(
                "SELECT MAX(expires_at) AS expires_at FROM user_sessions WHERE user_id = ?",
                (principal.user_id,),
            ).fetchone()
            expires_at = int(row["expires_at"] or time.time())
            conn.execute(
                "UPDATE agent_tokens SET expires_at = ? WHERE id = ?",
                (expires_at, self._agent_token_id(principal.user_id)),
            )

    def authenticate(self, authorization: str | None) -> AccountPrincipal:
        if not authorization or not authorization.startswith("Bearer "):
            raise AccountAuthError("缺少用户访问令牌")
        token = authorization[7:].strip()
        if not token:
            raise AccountAuthError("缺少用户访问令牌")
        now = int(time.time())
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT s.id AS session_id, s.expires_at, u.id AS user_id,
                       u.username, u.role, u.enabled
                FROM user_sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ?
                """,
                (self._token_hash(token),),
            ).fetchone()
            if row is None or not row["enabled"] or row["expires_at"] <= now:
                raise AccountAuthError("用户会话无效或已过期")
            return AccountPrincipal(
                user_id=row["user_id"],
                session_id=row["session_id"],
                username=row["username"],
                role=row["role"],
            )

    def dashboard_admin_principal(self) -> AccountPrincipal:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT id, username, role FROM users WHERE username_normalized = ? AND role = 'admin' AND enabled = 1",
                (self.admin_username.casefold(),),
            ).fetchone()
            if row is None:
                raise AccountPermissionError("管理员账户不可用")
            return AccountPrincipal(row["id"], "dashboard", row["username"], row["role"])

    def profile(self, principal: AccountPrincipal) -> dict:
        with self._connect() as conn:
            return self._profile(conn, principal.user_id)

    def update_profile(
        self,
        principal: AccountPrincipal,
        display_name: str,
        avatar_data_url: str | None,
    ) -> dict:
        clean_display_name = display_name.strip()
        if len(clean_display_name) > self.profile_name_max_length:
            raise AccountError(
                f"昵称长度不能超过 {self.profile_name_max_length} 个字符"
            )
        if any(not char.isprintable() for char in clean_display_name):
            raise AccountError("昵称不能包含控制字符")
        clean_avatar = self._validate_avatar_data_url(avatar_data_url)
        with self._connect() as conn:
            result = conn.execute(
                """
                UPDATE users
                SET display_name = ?, avatar_data_url = ?
                WHERE id = ?
                """,
                (clean_display_name, clean_avatar, principal.user_id),
            )
            if result.rowcount != 1:
                raise AccountNotFoundError("用户不存在")
            return self._profile(conn, principal.user_id)

    def session_credentials(self, principal: AccountPrincipal) -> dict:
        now = int(time.time())
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT u.username, u.role, s.expires_at
                FROM user_sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.id = ? AND s.user_id = ?
                """,
                (principal.session_id, principal.user_id),
            ).fetchone()
            if row is None or int(row["expires_at"]) <= now:
                raise AccountAuthError("用户会话无效或已过期")
            session_expires_at = int(row["expires_at"])
            return {
                "agent_access_token": self._ensure_agent_token(
                    conn,
                    principal.user_id,
                    row["username"],
                    row["role"],
                    session_expires_at,
                ),
                "stt_access_token": self._stt_token(principal.user_id, session_expires_at),
                "expires_at": session_expires_at,
                "user": self._profile(conn, principal.user_id),
            }

    def list_meetings(self, principal: AccountPrincipal) -> dict:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, title, template_key, created_at, updated_at,
                       duration_seconds, transcript, report
                FROM account_meetings
                WHERE user_id = ?
                ORDER BY updated_at DESC
                """,
                (principal.user_id,),
            ).fetchall()
            deleted = conn.execute(
                """
                SELECT meeting_id, deleted_at
                FROM account_meeting_tombstones
                WHERE user_id = ?
                ORDER BY deleted_at DESC
                """,
                (principal.user_id,),
            ).fetchall()
            return {
                "meetings": [dict(row) for row in rows],
                "deleted": [dict(row) for row in deleted],
            }

    def upsert_meeting(
        self,
        principal: AccountPrincipal,
        meeting_id: str,
        *,
        title: str,
        template_key: str,
        created_at: int,
        updated_at: int,
        duration_seconds: int,
        transcript: str,
        report: str,
    ) -> dict:
        with self._connect() as conn:
            tombstone = conn.execute(
                """
                SELECT deleted_at FROM account_meeting_tombstones
                WHERE user_id = ? AND meeting_id = ?
                """,
                (principal.user_id, meeting_id),
            ).fetchone()
            if tombstone is not None and updated_at <= int(tombstone["deleted_at"]):
                raise AccountConflictError("会议已在其他设备删除")
            if tombstone is not None:
                conn.execute(
                    "DELETE FROM account_meeting_tombstones WHERE user_id = ? AND meeting_id = ?",
                    (principal.user_id, meeting_id),
                )
            conn.execute(
                """
                INSERT INTO account_meetings (
                    user_id, id, title, template_key, created_at, updated_at,
                    duration_seconds, transcript, report
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id, id) DO UPDATE SET
                    title = excluded.title,
                    template_key = excluded.template_key,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    duration_seconds = excluded.duration_seconds,
                    transcript = excluded.transcript,
                    report = excluded.report
                WHERE excluded.updated_at >= account_meetings.updated_at
                """,
                (
                    principal.user_id,
                    meeting_id,
                    title,
                    template_key,
                    created_at,
                    updated_at,
                    duration_seconds,
                    transcript,
                    report,
                ),
            )
            row = conn.execute(
                """
                SELECT id, title, template_key, created_at, updated_at,
                       duration_seconds, transcript, report
                FROM account_meetings
                WHERE user_id = ? AND id = ?
                """,
                (principal.user_id, meeting_id),
            ).fetchone()
            if row is None:
                raise AccountNotFoundError("会议不存在")
            return dict(row)

    def delete_meeting(
        self,
        principal: AccountPrincipal,
        meeting_id: str,
        deleted_at: int,
    ) -> dict:
        effective_deleted_at = max(int(time.time() * 1000), deleted_at)
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO account_meeting_tombstones (user_id, meeting_id, deleted_at)
                VALUES (?, ?, ?)
                ON CONFLICT(user_id, meeting_id) DO UPDATE SET
                    deleted_at = MAX(account_meeting_tombstones.deleted_at, excluded.deleted_at)
                """,
                (principal.user_id, meeting_id, effective_deleted_at),
            )
            conn.execute(
                "DELETE FROM account_meetings WHERE user_id = ? AND id = ?",
                (principal.user_id, meeting_id),
            )
        return {
            "status": "deleted",
            "meeting_id": meeting_id,
            "deleted_at": effective_deleted_at,
        }

    def clear_meetings(self, principal: AccountPrincipal, deleted_at: int) -> dict:
        effective_deleted_at = max(int(time.time() * 1000), deleted_at)
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT id FROM account_meetings WHERE user_id = ?",
                (principal.user_id,),
            ).fetchall()
            for row in rows:
                conn.execute(
                    """
                    INSERT INTO account_meeting_tombstones (user_id, meeting_id, deleted_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(user_id, meeting_id) DO UPDATE SET
                        deleted_at = MAX(account_meeting_tombstones.deleted_at, excluded.deleted_at)
                    """,
                    (principal.user_id, row["id"], effective_deleted_at),
                )
            conn.execute(
                "DELETE FROM account_meetings WHERE user_id = ?",
                (principal.user_id,),
            )
        return {"status": "cleared", "deleted": len(rows), "deleted_at": effective_deleted_at}

    @staticmethod
    def canonical_stt_usage_key(
        user_id: str,
        usage_key: str | None,
        *,
        meeting_id: str | None = None,
        fallback_suffix: str | None = None,
    ) -> str:
        clean_user_id = user_id.strip()
        if not clean_user_id:
            raise AccountAuthError("用户身份无效")
        supplied = (usage_key or "").strip()
        if not supplied:
            supplied = f"{meeting_id or 'unscoped'}:{fallback_suffix or uuid.uuid4().hex}"
        material = f"stt:{clean_user_id}:{supplied}"
        if len(material) <= 200:
            return material
        digest = hashlib.sha256(material.encode("utf-8")).hexdigest()
        return f"stt:{clean_user_id}:{digest}"[:200]

    def ensure_stt_available(
        self,
        principal: AccountPrincipal,
        *,
        idempotency_key: str | None = None,
    ) -> dict:
        with self._connect() as conn:
            self._ensure_usage_balance(conn, principal.user_id, int(time.time()))
            balance = self._usage_balance(conn, principal.user_id)
            clean_key = (idempotency_key or "").strip()
            if clean_key:
                existing = conn.execute(
                    "SELECT user_id, kind, status FROM account_usage_events WHERE idempotency_key = ?",
                    (clean_key,),
                ).fetchone()
                if existing is not None:
                    if str(existing["user_id"]) != principal.user_id:
                        raise AccountConflictError("用量幂等键已被其他账户使用")
                    if str(existing["kind"]) != "stt_seconds" or str(existing["status"]) != "succeeded":
                        raise AccountConflictError("用量幂等键已用于其他计费类型")
                    return balance
            if balance["points_remaining"] < self.stt_points_per_minute and not principal.is_admin:
                raise AccountPermissionError("积分不足，请先补充积分")
            return balance

    def _stt_principal_for_user(self, user_id: str) -> AccountPrincipal:
        """Resolve a stateless STT-token subject to its current account role."""
        clean_user_id = user_id.strip()
        if not clean_user_id:
            raise AccountAuthError("用户身份无效")
        with self._connect() as conn:
            row = conn.execute(
                "SELECT id, username, role, enabled FROM users WHERE id = ?",
                (clean_user_id,),
            ).fetchone()
        if row is None or not row["enabled"]:
            raise AccountAuthError("用户账户无效或已停用")
        return AccountPrincipal(
            user_id=row["id"],
            session_id="stt-token",
            username=row["username"],
            role=row["role"],
        )

    def ensure_stt_available_for_user(
        self,
        user_id: str,
        *,
        idempotency_key: str | None = None,
    ) -> dict:
        return self.ensure_stt_available(
            self._stt_principal_for_user(user_id),
            idempotency_key=idempotency_key,
        )

    def record_stt_usage(
        self,
        principal: AccountPrincipal,
        *,
        duration_ms: int,
        meeting_id: str | None,
        idempotency_key: str,
    ) -> dict:
        seconds = max(1, (max(0, int(duration_ms)) + 999) // 1000)
        points = max(1, (seconds + 59) // 60) * self.stt_points_per_minute
        clean_key = idempotency_key.strip()
        if not clean_key or len(clean_key) > 200:
            raise AccountError("用量幂等键无效")
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            existing = conn.execute(
                "SELECT * FROM account_usage_events WHERE idempotency_key = ?",
                (clean_key,),
            ).fetchone()
            if existing is not None:
                # The historical schema has a global UNIQUE constraint on the key.
                # Keep that constraint for backwards compatibility, but never let a
                # key created by another account replay its usage result.
                if str(existing["user_id"]) != principal.user_id:
                    raise AccountConflictError("用量幂等键已被其他账户使用")
                if str(existing["kind"]) != "stt_seconds":
                    raise AccountConflictError("用量幂等键已用于其他计费类型")
                return self._usage_event_payload(existing)
            self._ensure_usage_balance(conn, principal.user_id, now)
            balance = self._usage_balance(conn, principal.user_id)
            if points > balance["points_remaining"] and not principal.is_admin:
                raise AccountPermissionError("积分不足，请先补充积分")
            conn.execute(
                """
                UPDATE account_usage_balances
                SET stt_seconds_used = stt_seconds_used + ?, points_used = points_used + ?, updated_at = ?
                WHERE user_id = ?
                """,
                (seconds, points, now, principal.user_id),
            )
            event_id = str(uuid.uuid4())
            conn.execute(
                """
                INSERT INTO account_usage_events (
                    id, idempotency_key, user_id, meeting_id, kind, quantity,
                    unit, status, charged, metadata_json, created_at, completed_at
                ) VALUES (?, ?, ?, ?, 'stt_seconds', ?, 'points', 'succeeded', 1, ?, ?, ?)
                """,
                (event_id, clean_key, principal.user_id, meeting_id, points,
                 json.dumps({"duration_seconds": seconds, "points": points}), now, now),
            )
            return self._usage_event_payload(
                conn.execute("SELECT * FROM account_usage_events WHERE id = ?", (event_id,)).fetchone()
            )

    def record_stt_usage_for_user(
        self,
        user_id: str,
        *,
        duration_ms: int,
        meeting_id: str | None,
        idempotency_key: str,
    ) -> dict:
        return self.record_stt_usage(
            self._stt_principal_for_user(user_id),
            duration_ms=duration_ms,
            meeting_id=meeting_id,
            idempotency_key=idempotency_key,
        )

    def usage_event_for_user(self, user_id: str, idempotency_key: str) -> dict | None:
        """Read a settled usage event for rolling-upgrade idempotency checks."""
        principal = self._stt_principal_for_user(user_id)
        clean_key = idempotency_key.strip()
        if not clean_key:
            return None
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM account_usage_events WHERE idempotency_key = ? AND user_id = ?",
                (clean_key, principal.user_id),
            ).fetchone()
        if row is None or str(row["kind"]) != "stt_seconds" or str(row["status"]) != "succeeded":
            return None
        return self._usage_event_payload(row)

    def usage_summary(self, principal: AccountPrincipal) -> dict:
        with self._connect() as conn:
            self._ensure_usage_balance(conn, principal.user_id, int(time.time()))
            return self._usage_balance(conn, principal.user_id)

    def growth_overview(self, principal: AccountPrincipal) -> dict:
        now = int(time.time())
        with self._connect() as conn:
            self._initialize_growth_user(conn, principal.user_id, now, None)
            referral = conn.execute(
                "SELECT code, used_count, max_uses, expires_at FROM growth_referral_codes WHERE user_id = ?",
                (principal.user_id,),
            ).fetchone()
            rewards = conn.execute(
                "SELECT reward_type, COALESCE(SUM(quantity), 0) AS quantity FROM growth_reward_ledger WHERE user_id = ? AND status = 'fulfilled' GROUP BY reward_type",
                (principal.user_id,),
            ).fetchall()
            pending = conn.execute(
                "SELECT COUNT(*) AS total FROM growth_referral_bindings WHERE referrer_user_id = ? AND rewarded_at IS NULL",
                (principal.user_id,),
            ).fetchone()
            campaigns = self._campaign_rows(conn, now)
            channel = self._private_channel(conn)
            return {
                "referral": {
                    "code": referral["code"],
                    "successful_invites": int(referral["used_count"]),
                    "pending_rewards": int(pending["total"]),
                    "share_path": f"/app/?ref={referral['code']}",
                },
                "rewards": {row["reward_type"]: int(row["quantity"]) for row in rewards},
                "campaigns": campaigns,
                "private_channel": channel,
            }

    def redeem_code(self, principal: AccountPrincipal, raw_code: str) -> dict:
        code = raw_code.strip().upper().replace(" ", "")
        if not re.fullmatch(r"[A-Z0-9-]{4,64}", code):
            raise AccountError("兑换码格式无效")
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                """
                SELECT c.*, b.name AS batch_name, b.reward_type, b.reward_payload_json,
                       b.enabled AS batch_enabled
                FROM growth_redemption_codes c
                JOIN growth_redemption_batches b ON b.id = c.batch_id
                WHERE c.code = ?
                """,
                (code,),
            ).fetchone()
            if row is None:
                raise AccountNotFoundError("兑换码不存在")
            if not row["batch_enabled"] or row["disabled_at"] is not None:
                raise AccountError("兑换码已停用")
            expires_at = row["expires_at"]
            if expires_at is not None and int(expires_at) <= now:
                conn.execute("UPDATE growth_redemption_codes SET status = 'expired' WHERE id = ?", (row["id"],))
                raise AccountError("兑换码已过期")
            if int(row["used_count"]) >= int(row["max_uses"]):
                raise AccountConflictError("兑换码已用尽")
            existing = conn.execute(
                "SELECT id FROM growth_redemption_claims WHERE code_id = ? AND user_id = ?",
                (row["id"], principal.user_id),
            ).fetchone()
            if existing is not None:
                raise AccountConflictError("你已经兑换过该兑换码")
            payload = json.loads(row["reward_payload_json"] or "{}")
            quantity = int(payload.get("quantity", 0))
            if quantity <= 0:
                raise AccountError("兑换码奖励配置无效")
            claim_id = str(uuid.uuid4())
            conn.execute(
                "INSERT INTO growth_redemption_claims (id, code_id, user_id, reward_type, reward_payload_json, status, created_at, fulfilled_at) VALUES (?, ?, ?, ?, ?, 'fulfilled', ?, ?)",
                (claim_id, row["id"], principal.user_id, row["reward_type"], row["reward_payload_json"], now, now),
            )
            self._grant_growth_reward(
                conn, principal.user_id, "redemption", claim_id,
                row["reward_type"], quantity, f"redeem:{row['id']}:{principal.user_id}", now,
            )
            used_count = int(row["used_count"]) + 1
            conn.execute(
                "UPDATE growth_redemption_codes SET used_count = ?, status = ? WHERE id = ?",
                (used_count, "used" if used_count >= int(row["max_uses"]) else "partial", row["id"]),
            )
            return {
                "status": "fulfilled",
                "claim_id": claim_id,
                "reward_type": row["reward_type"],
                "quantity": quantity,
                "message": self._reward_message(row["reward_type"], quantity),
                "profile": self._profile(conn, principal.user_id),
                "private_channel": self._private_channel(conn),
            }

    def redemption_history(self, principal: AccountPrincipal) -> list[dict]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT cl.id, c.code, b.name AS batch_name, cl.reward_type,
                       cl.reward_payload_json, cl.status, cl.created_at
                FROM growth_redemption_claims cl
                JOIN growth_redemption_codes c ON c.id = cl.code_id
                JOIN growth_redemption_batches b ON b.id = c.batch_id
                WHERE cl.user_id = ? ORDER BY cl.created_at DESC LIMIT 50
                """,
                (principal.user_id,),
            ).fetchall()
            return [self._json_row(row, "reward_payload_json", "reward") for row in rows]

    def list_growth_campaigns(self) -> list[dict]:
        with self._connect() as conn:
            self._settle_due_campaigns(conn, int(time.time()))
            return self._campaign_rows(conn, int(time.time()))

    def campaign_detail(self, principal: AccountPrincipal, campaign_id: str) -> dict:
        now = int(time.time())
        with self._connect() as conn:
            self._settle_due_campaigns(conn, now)
            campaign = self._campaign_payload(conn, campaign_id)
            entry = conn.execute(
                "SELECT score, rank FROM growth_campaign_entries WHERE campaign_id = ? AND user_id = ?",
                (campaign_id, principal.user_id),
            ).fetchone()
            actions = conn.execute(
                "SELECT action_type, action_key, score, status, created_at FROM growth_campaign_actions WHERE campaign_id = ? AND user_id = ? ORDER BY created_at DESC LIMIT 100",
                (campaign_id, principal.user_id),
            ).fetchall()
            campaign["joined"] = entry is not None
            campaign["my_score"] = int(entry["score"]) if entry else 0
            campaign["my_rank"] = int(entry["rank"]) if entry and entry["rank"] is not None else None
            campaign["actions"] = [dict(row) for row in actions]
            campaign["leaderboard"] = self._campaign_leaderboard(conn, campaign_id, 20)
            return campaign

    def join_campaign(self, principal: AccountPrincipal, campaign_id: str) -> dict:
        now = int(time.time())
        with self._connect() as conn:
            campaign = self._campaign_payload(conn, campaign_id)
            self._assert_campaign_open(campaign, now)
            self._ensure_campaign_entry(conn, campaign_id, principal.user_id, now)
            return {"status": "joined", "campaign_id": campaign_id}

    def campaign_checkin(self, principal: AccountPrincipal, campaign_id: str) -> dict:
        now = int(time.time())
        action_date = datetime.fromtimestamp(now, timezone.utc).astimezone(timezone(timedelta(hours=8))).strftime("%Y-%m-%d")
        with self._connect() as conn:
            campaign = self._campaign_payload(conn, campaign_id)
            self._assert_campaign_open(campaign, now)
            self._ensure_campaign_entry(conn, campaign_id, principal.user_id, now)
            reward = int(campaign["rules"].get("checkin_reward", 10))
            action_id = str(uuid.uuid4())
            try:
                conn.execute(
                    "INSERT INTO growth_campaign_actions (id, campaign_id, user_id, action_type, action_key, action_date, score, payload_json, created_at) VALUES (?, ?, ?, 'checkin', ?, ?, ?, ?, ?)",
                    (action_id, campaign_id, principal.user_id, action_date, action_date, 1, json.dumps({"reward": reward}, ensure_ascii=False), now),
                )
            except sqlite3.IntegrityError as exc:
                raise AccountConflictError("今天已经签到过了") from exc
            self._increment_campaign_score(conn, campaign_id, principal.user_id, 1, now)
            self._grant_growth_reward(conn, principal.user_id, "campaign_checkin", action_id, "points", reward, f"campaign-checkin:{campaign_id}:{principal.user_id}:{action_date}", now)
            return {"status": "accepted", "quantity": reward, "message": f"签到成功，获得 {reward} 积分"}

    def campaign_answer(self, principal: AccountPrincipal, campaign_id: str, question_key: str, answer: str) -> dict:
        now = int(time.time())
        clean_key = question_key.strip()[:80]
        clean_answer = answer.strip()[:200]
        with self._connect() as conn:
            campaign = self._campaign_payload(conn, campaign_id)
            self._assert_campaign_open(campaign, now)
            self._ensure_campaign_entry(conn, campaign_id, principal.user_id, now)
            questions = campaign["rules"].get("questions", [])
            question = next((item for item in questions if str(item.get("key", "")) == clean_key), None)
            if not question:
                raise AccountNotFoundError("题目不存在")
            correct = clean_answer.casefold() == str(question.get("answer", "")).strip().casefold()
            action_id = str(uuid.uuid4())
            try:
                conn.execute(
                    "INSERT INTO growth_campaign_actions (id, campaign_id, user_id, action_type, action_key, score, payload_json, status, created_at) VALUES (?, ?, ?, 'answer', ?, ?, ?, ?, ?)",
                    (action_id, campaign_id, principal.user_id, clean_key, 1 if correct else 0, json.dumps({"answer": clean_answer, "correct": correct}, ensure_ascii=False), "correct" if correct else "wrong", now),
                )
            except sqlite3.IntegrityError as exc:
                raise AccountConflictError("这道题已经提交过了") from exc
            reward = int(campaign["rules"].get("answer_reward", 20)) if correct else 0
            if correct:
                self._increment_campaign_score(conn, campaign_id, principal.user_id, 1, now)
                self._grant_growth_reward(conn, principal.user_id, "campaign_answer", action_id, "points", reward, f"campaign-answer:{campaign_id}:{principal.user_id}:{clean_key}", now)
            return {"status": "accepted", "correct": correct, "quantity": reward, "message": f"回答{'正确' if correct else '错误'}" + (f"，获得 {reward} 积分" if reward else "")}

    def campaign_draw(self, principal: AccountPrincipal, campaign_id: str) -> dict:
        now = int(time.time())
        action_date = datetime.fromtimestamp(now, timezone.utc).astimezone(timezone(timedelta(hours=8))).strftime("%Y-%m-%d")
        with self._connect() as conn:
            campaign = self._campaign_payload(conn, campaign_id)
            self._assert_campaign_open(campaign, now)
            self._ensure_campaign_entry(conn, campaign_id, principal.user_id, now)
            action_id = str(uuid.uuid4())
            probability = max(0, min(10000, int(campaign["rules"].get("win_probability", 1500))))
            won = secrets.randbelow(10000) < probability
            try:
                conn.execute(
                    "INSERT INTO growth_campaign_actions (id, campaign_id, user_id, action_type, action_key, action_date, score, payload_json, status, created_at) VALUES (?, ?, ?, 'draw', ?, ?, ?, ?, ?, ?)",
                    (action_id, campaign_id, principal.user_id, action_date, action_date, 1 if won else 0, json.dumps({"won": won, "probability": probability}, ensure_ascii=False), "won" if won else "miss", now),
                )
            except sqlite3.IntegrityError as exc:
                raise AccountConflictError("今天已经抽过奖了") from exc
            reward = int(campaign["rules"].get("draw_reward", 50)) if won else 0
            if won:
                self._increment_campaign_score(conn, campaign_id, principal.user_id, 1, now)
                self._grant_growth_reward(conn, principal.user_id, "campaign_draw", action_id, "points", reward, f"campaign-draw:{campaign_id}:{principal.user_id}:{action_date}", now)
            return {"status": "accepted", "won": won, "quantity": reward, "probability": probability, "message": f"{'恭喜中奖，获得 ' + str(reward) + ' 积分' if won else '本次未中奖，明天再来'}"}

    def campaign_leaderboard(self, campaign_id: str, limit: int = 20) -> list[dict]:
        with self._connect() as conn:
            self._campaign_payload(conn, campaign_id)
            return self._campaign_leaderboard(conn, campaign_id, limit)

    def admin_settle_campaign(self, principal: AccountPrincipal, campaign_id: str) -> dict:
        self._require_admin(principal)
        with self._connect() as conn:
            return self._settle_campaign(conn, campaign_id, int(time.time()))

    def private_channel(self) -> dict | None:
        with self._connect() as conn:
            return self._private_channel(conn)

    def record_channel_event(
        self, event_type: str, *, channel_id: str, user_id: str | None,
        source: str = "pwa", campaign_id: str | None = None,
        metadata: dict | None = None,
    ) -> dict:
        allowed = {"view", "click", "copy_link", "open_qr", "redeem", "register", "qualified_referral"}
        if event_type not in allowed:
            raise AccountError("渠道事件类型无效")
        now = int(time.time())
        with self._connect() as conn:
            channel = conn.execute("SELECT id FROM growth_private_channels WHERE id = ? AND enabled = 1", (channel_id,)).fetchone()
            if channel is None:
                raise AccountNotFoundError("私域渠道不存在")
            event_id = str(uuid.uuid4())
            conn.execute(
                "INSERT INTO growth_channel_events (id, channel_id, event_type, user_id, campaign_id, source, created_at, metadata_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (event_id, channel_id, event_type, user_id, campaign_id, source[:64], now, json.dumps(metadata or {}, ensure_ascii=False)),
            )
            return {"status": "recorded", "event_id": event_id}

    def admin_growth_overview(self, principal: AccountPrincipal) -> dict:
        self._require_admin(principal)
        now = int(time.time())
        day_start = now - now % 86400
        with self._connect() as conn:
            def scalar(sql: str, args: tuple = ()) -> int:
                return int(conn.execute(sql, args).fetchone()[0] or 0)
            total_users = scalar("SELECT COUNT(*) FROM users WHERE role = 'user'")
            invitations = scalar("SELECT COUNT(*) FROM growth_referral_bindings")
            claims = scalar("SELECT COUNT(*) FROM growth_redemption_claims WHERE status = 'fulfilled'")
            codes = scalar("SELECT COUNT(*) FROM growth_redemption_codes")
            return {
                "users": total_users,
                "new_users_today": scalar("SELECT COUNT(*) FROM users WHERE role = 'user' AND created_at >= ?", (day_start,)),
                "successful_invites": invitations,
                "invitation_conversion_rate": round(invitations / total_users * 100, 1) if total_users else 0,
                "redemption_rate": round(claims / codes * 100, 1) if codes else 0,
                "active_campaigns": scalar("SELECT COUNT(*) FROM growth_campaigns WHERE status = 'active' AND starts_at <= ? AND ends_at > ?", (now, now)),
                "rewards_points": scalar("SELECT COALESCE(SUM(quantity), 0) FROM growth_reward_ledger WHERE reward_type = 'points' AND status = 'fulfilled'"),
                "channel_clicks": scalar("SELECT COUNT(*) FROM growth_channel_events WHERE event_type IN ('click', 'open_qr', 'copy_link')"),
            }

    def admin_create_redemption_batch(
        self, principal: AccountPrincipal, *, name: str, quantity: int,
        reward_type: str, reward_quantity: int, expires_at: int | None,
        max_uses: int = 1, prefix: str = "ZW",
    ) -> dict:
        self._require_admin(principal)
        if not name.strip() or not 1 <= quantity <= 5000 or reward_quantity < 1 or max_uses < 1:
            raise AccountError("兑换码批次参数无效")
        if reward_type not in {"points", "coupon", "entitlement"}:
            raise AccountError("奖励类型无效")
        clean_prefix = re.sub(r"[^A-Z0-9]", "", prefix.upper())[:12] or "ZW"
        now = int(time.time())
        batch_id = str(uuid.uuid4())
        codes: list[str] = []
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute(
                "INSERT INTO growth_redemption_batches (id, name, code_type, reward_type, reward_payload_json, total_count, created_by, created_at, expires_at) VALUES (?, ?, 'gift', ?, ?, ?, ?, ?, ?)",
                (batch_id, name.strip(), reward_type, json.dumps({"quantity": reward_quantity}, ensure_ascii=False), quantity, principal.user_id, now, expires_at),
            )
            for _ in range(quantity):
                code = f"{clean_prefix}-{secrets.token_hex(4).upper()}"
                codes.append(code)
                conn.execute(
                    "INSERT INTO growth_redemption_codes (id, batch_id, code, max_uses, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    (str(uuid.uuid4()), batch_id, code, max_uses, expires_at, now),
                )
        return {"id": batch_id, "name": name.strip(), "codes": codes, "total_count": quantity}

    def admin_list_redemption_batches(self, principal: AccountPrincipal) -> list[dict]:
        self._require_admin(principal)
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT b.*, COALESCE(SUM(c.used_count), 0) AS redeemed_count
                FROM growth_redemption_batches b
                LEFT JOIN growth_redemption_codes c ON c.batch_id = b.id
                GROUP BY b.id ORDER BY b.created_at DESC
                """
            ).fetchall()
            return [self._json_row(row, "reward_payload_json", "reward") for row in rows]

    def admin_upsert_private_channel(self, principal: AccountPrincipal, payload: dict) -> dict:
        self._require_admin(principal)
        now = int(time.time())
        channel_id = str(payload.get("id") or "default-welfare-group")
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO growth_private_channels (id, name, qr_image_url, join_url, short_url, slogan, reward_type, reward_payload_json, valid_until, enabled, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET name=excluded.name, qr_image_url=excluded.qr_image_url,
                  join_url=excluded.join_url, short_url=excluded.short_url, slogan=excluded.slogan,
                  reward_type=excluded.reward_type, reward_payload_json=excluded.reward_payload_json,
                  valid_until=excluded.valid_until, enabled=excluded.enabled, updated_by=excluded.updated_by,
                  updated_at=excluded.updated_at
                """,
                (channel_id, str(payload.get("name", "智悟本福利群"))[:80], str(payload.get("qr_image_url", ""))[:500], str(payload.get("join_url", ""))[:500], str(payload.get("short_url", ""))[:500], str(payload.get("slogan", ""))[:300], str(payload.get("reward_type", "points"))[:30], json.dumps(payload.get("reward", {"quantity": 50}), ensure_ascii=False), payload.get("valid_until"), int(bool(payload.get("enabled", True))), principal.user_id, now),
            )
            return self._private_channel(conn, channel_id)

    def list_plans(self) -> list[dict]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT code, name, description, price_cents, quota_amount,
                       construction_logs_unlocked, included_minutes,
                       ai_credits, points, team_seats, duration_days
                FROM account_plans WHERE active = 1
                ORDER BY sort_order ASC, price_cents ASC
                """
            ).fetchall()
            return [self._plan_payload(row) for row in rows]

    def create_order(self, principal: AccountPrincipal, plan_code: str) -> dict:
        if principal.is_admin:
            raise AccountConflictError("管理员已拥有不限积分和全部功能权限")
        now = int(time.time())
        with self._connect() as conn:
            plan = conn.execute(
                "SELECT * FROM account_plans WHERE code = ? AND active = 1",
                (plan_code.strip(),),
            ).fetchone()
            if plan is None:
                raise AccountNotFoundError("套餐不存在或已下架")
            pending = conn.execute(
                "SELECT id FROM recharge_orders WHERE user_id = ? AND status = 'pending'",
                (principal.user_id,),
            ).fetchone()
            if pending is not None:
                raise AccountConflictError("已有待处理的充值订单")
            order_id = str(uuid.uuid4())
            conn.execute(
                """
                INSERT INTO recharge_orders (
                    id, user_id, plan_id, amount_cents, quota_amount, points,
                    construction_logs_unlocked, included_minutes,
                    ai_credits, team_seats, duration_days, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)
                """,
                (
                    order_id,
                    principal.user_id,
                    plan["id"],
                    plan["price_cents"],
                    plan["quota_amount"],
                    plan["points"],
                    plan["construction_logs_unlocked"],
                    plan["included_minutes"],
                    plan["ai_credits"],
                    plan["team_seats"],
                    plan["duration_days"],
                    now,
                ),
            )
            return self._order(conn, order_id)

    def list_orders(self, principal: AccountPrincipal) -> list[dict]:
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT id FROM recharge_orders WHERE user_id = ? ORDER BY created_at DESC",
                (principal.user_id,),
            ).fetchall()
            return [self._order(conn, row["id"]) for row in rows]

    def admin_list_users(self, principal: AccountPrincipal) -> list[dict]:
        self._require_admin(principal)
        with self._connect() as conn:
            rows = conn.execute("SELECT id FROM users ORDER BY created_at DESC").fetchall()
            return [self._profile(conn, row["id"]) for row in rows]

    def set_user_enabled(
        self,
        principal: AccountPrincipal,
        user_id: str,
        enabled: bool,
    ) -> dict:
        self._require_admin(principal)
        if user_id == principal.user_id and not enabled:
            raise AccountConflictError("管理员不能停用自己的当前账号")
        with self._connect() as conn:
            result = conn.execute(
                "UPDATE users SET enabled = ? WHERE id = ?",
                (int(enabled), user_id),
            )
            if result.rowcount != 1:
                raise AccountNotFoundError("用户不存在")
            if not enabled:
                conn.execute("DELETE FROM user_sessions WHERE user_id = ?", (user_id,))
                conn.execute(
                    "UPDATE agent_tokens SET enabled = 0, expires_at = ? WHERE id = ?",
                    (int(time.time()), self._agent_token_id(user_id)),
                )
            return self._profile(conn, user_id)

    def delete_user(self, principal: AccountPrincipal, user_id: str) -> dict:
        self._require_admin(principal)
        if user_id == principal.user_id:
            raise AccountConflictError("管理员不能删除自己的当前账号")
        with self._connect() as conn:
            user = conn.execute(
                "SELECT username, role FROM users WHERE id = ?",
                (user_id,),
            ).fetchone()
            if user is None:
                raise AccountNotFoundError("用户不存在")
            if user["role"] == "admin":
                raise AccountConflictError("管理员账号不能删除")

            token_id = self._agent_token_id(user_id)
            conn.execute("DELETE FROM agent_tasks WHERE token_id = ?", (token_id,))
            conn.execute("DELETE FROM agent_tokens WHERE id = ?", (token_id,))
            conn.execute("DELETE FROM recharge_orders WHERE user_id = ?", (user_id,))
            conn.execute("DELETE FROM users WHERE id = ?", (user_id,))
            return {
                "status": "deleted",
                "user_id": user_id,
                "username": user["username"],
            }

    def admin_list_orders(self, principal: AccountPrincipal, status: str | None = None) -> list[dict]:
        self._require_admin(principal)
        with self._connect() as conn:
            if status:
                rows = conn.execute(
                    "SELECT id FROM recharge_orders WHERE status = ? ORDER BY created_at ASC",
                    (status,),
                ).fetchall()
            else:
                rows = conn.execute(
                    "SELECT id FROM recharge_orders ORDER BY created_at DESC"
                ).fetchall()
            return [self._order(conn, row["id"]) for row in rows]

    def approve_order(self, principal: AccountPrincipal, order_id: str) -> dict:
        self._require_admin(principal)
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            order = conn.execute(
                "SELECT * FROM recharge_orders WHERE id = ?",
                (order_id,),
            ).fetchone()
            if order is None:
                raise AccountNotFoundError("充值订单不存在")
            if order["status"] != "pending":
                raise AccountConflictError("充值订单已处理")
            current_entitlement = conn.execute(
                "SELECT vip_expires_at FROM user_entitlements WHERE user_id = ?",
                (order["user_id"],),
            ).fetchone()
            subscription_started_at = max(
                now,
                int(current_entitlement["vip_expires_at"] or 0) if current_entitlement else now,
            )
            subscription_expires_at = subscription_started_at + int(order["duration_days"]) * 24 * 60 * 60
            conn.execute(
                """
                UPDATE recharge_orders
                SET status = 'approved', decided_at = ?, decided_by = ?
                WHERE id = ? AND status = 'pending'
                """,
                (now, principal.user_id, order_id),
            )
            conn.execute(
                """
                UPDATE user_entitlements
                SET vip_enabled = 1,
                    vip_expires_at = ?,
                    construction_logs_unlocked = MAX(
                        construction_logs_unlocked, ?
                    ),
                    quota_granted = quota_granted + ?,
                    updated_at = ?
                WHERE user_id = ?
                """,
                (
                    subscription_expires_at,
                    order["construction_logs_unlocked"],
                    order["quota_amount"],
                    now,
                    order["user_id"],
                ),
            )
            conn.execute(
                """
                UPDATE agent_tokens
                SET request_limit = request_limit + ?
                WHERE id = ?
                """,
                (order["quota_amount"], self._agent_token_id(order["user_id"])),
            )
            self._ensure_usage_balance(conn, order["user_id"], now)
            conn.execute(
                """
                UPDATE account_usage_balances
                SET included_stt_seconds = MAX(included_stt_seconds, ?),
                    ai_credits_granted = MAX(ai_credits_granted, ?),
                    points_granted = MAX(points_granted, ?),
                    team_seats = MAX(team_seats, ?),
                    updated_at = ?
                WHERE user_id = ?
                """,
                (
                    int(order["included_minutes"]) * 60,
                    int(order["ai_credits"]),
                    int(order["points"] or order["quota_amount"]),
                    int(order["team_seats"]),
                    now,
                    order["user_id"],
                ),
            )
            conn.execute(
                """
                UPDATE recharge_orders
                SET subscription_started_at = ?, subscription_expires_at = ?
                WHERE id = ?
                """,
                (
                    subscription_started_at,
                    subscription_expires_at,
                    order_id,
                ),
            )
            self._ensure_team(conn, order["user_id"], now)
            return self._order(conn, order_id)

    def reject_order(self, principal: AccountPrincipal, order_id: str) -> dict:
        self._require_admin(principal)
        with self._connect() as conn:
            result = conn.execute(
                """
                UPDATE recharge_orders
                SET status = 'rejected', decided_at = ?, decided_by = ?
                WHERE id = ? AND status = 'pending'
                """,
                (int(time.time()), principal.user_id, order_id),
            )
            if result.rowcount != 1:
                raise AccountConflictError("充值订单不存在或已处理")
            return self._order(conn, order_id)

    def _bootstrap_admin(self, conn: sqlite3.Connection) -> None:
        username = self._validate_username(self.admin_username)
        row = conn.execute(
            "SELECT id FROM users WHERE username_normalized = ?",
            (username.casefold(),),
        ).fetchone()
        now = int(time.time())
        if row is None:
            user_id = str(uuid.uuid4())
            salt = secrets.token_bytes(16)
            conn.execute(
                """
                INSERT INTO users (
                    id, username, username_normalized, password_hash,
                    password_salt, role, enabled, created_at
                ) VALUES (?, ?, ?, ?, ?, 'admin', 1, ?)
                """,
                (
                    user_id,
                    username,
                    username.casefold(),
                    self._password_hash(self.admin_password, salt),
                    salt.hex(),
                    now,
                ),
            )
        else:
            user_id = row["id"]
            salt = secrets.token_bytes(16)
            conn.execute(
                """
                UPDATE users
                SET username = ?, password_hash = ?, password_salt = ?,
                    role = 'admin', enabled = 1
                WHERE id = ?
                """,
                (
                    username,
                    self._password_hash(self.admin_password, salt),
                    salt.hex(),
                    user_id,
                ),
            )
        conn.execute(
            """
            INSERT INTO user_entitlements (
                user_id, vip_enabled, construction_logs_unlocked,
                quota_granted, updated_at
            ) VALUES (?, 1, 1, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                vip_enabled = 1,
                construction_logs_unlocked = 1,
                quota_granted = MAX(quota_granted, excluded.quota_granted),
                updated_at = excluded.updated_at
            """,
            (user_id, self.admin_request_limit, now),
        )
        conn.execute(
            """
            INSERT INTO account_identities (
                id, user_id, provider, subject, verified, created_at
            ) VALUES (?, ?, 'password', ?, 1, ?)
            ON CONFLICT(provider, subject) DO UPDATE SET
                user_id = excluded.user_id,
                verified = 1
            """,
            (str(uuid.uuid4()), user_id, username.casefold(), now),
        )
        self._ensure_usage_balance(conn, user_id, now)
        self._ensure_agent_token(conn, user_id, username, "admin", int(time.time()))

    def _create_session(self, conn: sqlite3.Connection, user_id: str) -> dict:
        user = conn.execute(
            "SELECT username, role FROM users WHERE id = ?",
            (user_id,),
        ).fetchone()
        if user is None:
            raise AccountNotFoundError("用户不存在")
        raw_token = "mn_user_" + secrets.token_urlsafe(32)
        session_id = str(uuid.uuid4())
        now = int(time.time())
        expires_at = now + self.session_ttl_sec
        agent_token = self._ensure_agent_token(
            conn,
            user_id,
            user["username"],
            user["role"],
            expires_at,
        )
        conn.execute("DELETE FROM user_sessions WHERE expires_at <= ?", (now,))
        conn.execute(
            """
            INSERT INTO user_sessions (id, user_id, token_hash, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (session_id, user_id, self._token_hash(raw_token), expires_at, now),
        )
        principal = AccountPrincipal(user_id, session_id, user["username"], user["role"])
        return {
            "access_token": raw_token,
            "agent_access_token": agent_token,
            "stt_access_token": self._stt_token(user_id, expires_at),
            "token_type": "bearer",
            "expires_at": expires_at,
            "user": self._profile(conn, principal.user_id),
        }

    def _ensure_agent_token(
        self,
        conn: sqlite3.Connection,
        user_id: str,
        username: str,
        role: str,
        expires_at: int,
    ) -> str:
        raw_token = self._agent_token(user_id)
        entitlement = conn.execute(
            "SELECT quota_granted FROM user_entitlements WHERE user_id = ?",
            (user_id,),
        ).fetchone()
        request_limit = self.admin_request_limit if role == "admin" else int(
            entitlement["quota_granted"] if entitlement else 0
        )
        token_id = self._agent_token_id(user_id)
        conn.execute(
            """
            INSERT INTO agent_tokens (
                id, label, token_hash, enabled, request_limit,
                requests_used, allowed_providers, expires_at, created_at
            ) VALUES (?, ?, ?, 1, ?, 0, 'claude-cli,codex-cli', ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                label = excluded.label,
                token_hash = excluded.token_hash,
                enabled = 1,
                request_limit = MAX(agent_tokens.request_limit, excluded.request_limit),
                allowed_providers = excluded.allowed_providers,
                expires_at = CASE
                    WHEN agent_tokens.expires_at IS NULL THEN excluded.expires_at
                    ELSE MAX(agent_tokens.expires_at, excluded.expires_at)
                END
            """,
            (
                token_id,
                f"{username} ({role})",
                self._token_hash(raw_token),
                request_limit,
                expires_at,
                int(time.time()),
            ),
        )
        return raw_token

    def _stt_token(self, user_id: str, session_expires_at: int) -> str:
        expires_at = min(session_expires_at, int(time.time()) + self.stt_token_ttl_sec)
        return issue_account_stt_token(self.token_secret, user_id, expires_at)

    def _ensure_free_entitlements(self, conn: sqlite3.Connection) -> None:
        if self.free_request_limit <= 0:
            return
        now = int(time.time())
        conn.execute(
            """
            UPDATE user_entitlements
            SET quota_granted = MAX(quota_granted, ?), updated_at = ?
            WHERE vip_enabled = 0
              AND user_id IN (SELECT id FROM users WHERE role = 'user')
            """,
            (self.free_request_limit, now),
        )
        if self._table_exists(conn, "agent_tokens"):
            conn.execute(
                """
                UPDATE agent_tokens
                SET request_limit = MAX(request_limit, ?)
                WHERE id IN (
                    SELECT 'user:' || e.user_id
                    FROM user_entitlements e
                    JOIN users u ON u.id = e.user_id
                    WHERE u.role = 'user' AND e.vip_enabled = 0
                )
                """,
                (self.free_request_limit,),
            )

    def _ensure_usage_balances(self, conn: sqlite3.Connection) -> None:
        now = int(time.time())
        rows = conn.execute("SELECT id FROM users").fetchall()
        for row in rows:
            self._ensure_usage_balance(conn, row["id"], now)

    def _ensure_usage_balance(
        self,
        conn: sqlite3.Connection,
        user_id: str,
        now: int,
    ) -> None:
        role_row = conn.execute("SELECT role FROM users WHERE id = ?", (user_id,)).fetchone()
        if role_row is None:
            return
        self._refresh_subscription(conn, user_id, now)
        is_admin = role_row["role"] == "admin"
        stt_seconds = self.admin_request_limit if is_admin else self.free_stt_minutes * 60
        ai_credits = self.admin_request_limit if is_admin else self.free_ai_credits
        points = self.admin_request_limit if is_admin else self.free_points
        conn.execute(
            """
            INSERT INTO account_usage_balances (
                user_id, included_stt_seconds, stt_seconds_used,
                ai_credits_granted, ai_credits_used, points_granted, points_used, team_seats,
                period_start, period_end, updated_at
            ) VALUES (?, ?, 0, ?, 0, ?, 0, 1, ?, ?, ?)
            ON CONFLICT(user_id) DO NOTHING
            """,
            (user_id, stt_seconds, ai_credits, points, now, now + 30 * 24 * 60 * 60, now),
        )
        entitlement = conn.execute(
            "SELECT vip_enabled FROM user_entitlements WHERE user_id = ?", (user_id,)
        ).fetchone()
        reset_stt = self.admin_request_limit if is_admin else self.free_stt_minutes * 60
        reset_ai = self.admin_request_limit if is_admin else self.free_ai_credits
        reset_points = self.admin_request_limit if is_admin else self.free_points
        if entitlement is not None and entitlement["vip_enabled"] and not is_admin:
            active = conn.execute(
                """
                SELECT included_minutes, ai_credits, points, quota_amount
                FROM recharge_orders
                WHERE user_id = ? AND status = 'approved' AND subscription_expires_at > ?
                ORDER BY subscription_expires_at DESC LIMIT 1
                """,
                (user_id, now),
            ).fetchone()
            if active is not None:
                reset_stt = int(active["included_minutes"]) * 60
                reset_ai = int(active["ai_credits"])
                reset_points = max(int(active["points"] or 0), int(active["quota_amount"]))
        conn.execute(
            """
            UPDATE account_usage_balances
            SET included_stt_seconds = ?, stt_seconds_used = 0,
                ai_credits_granted = ?, ai_credits_used = 0,
                points_granted = ?, points_used = 0,
                period_start = ?, period_end = ?, updated_at = ?
            WHERE user_id = ? AND period_end <= ?
            """,
            (reset_stt, reset_ai, reset_points, now, now + 30 * 24 * 60 * 60, now, user_id, now),
        )

    @staticmethod
    def _usage_balance(conn: sqlite3.Connection, user_id: str) -> dict:
        row = conn.execute(
            """
            SELECT included_stt_seconds, stt_seconds_used,
                   ai_credits_granted, ai_credits_used,
                   points_granted, points_used, team_seats,
                   period_start, period_end
            FROM account_usage_balances WHERE user_id = ?
            """,
            (user_id,),
        ).fetchone()
        if row is None:
            raise AccountNotFoundError("用户用量账户不存在")
        included_seconds = int(row["included_stt_seconds"])
        used_seconds = int(row["stt_seconds_used"])
        granted_credits = int(row["ai_credits_granted"])
        used_credits = int(row["ai_credits_used"])
        return {
            "included_minutes": included_seconds // 60,
            "stt_seconds_used": used_seconds,
            "stt_minutes_used": round(used_seconds / 60, 2),
            "stt_seconds_remaining": max(0, included_seconds - used_seconds),
            "stt_minutes_remaining": round(max(0, included_seconds - used_seconds) / 60, 2),
            "ai_credits_granted": granted_credits,
            "ai_credits_used": used_credits,
            "ai_credits_remaining": max(0, granted_credits - used_credits),
            "points_granted": int(row["points_granted"]),
            "points_used": int(row["points_used"]),
            "points_remaining": max(0, int(row["points_granted"]) - int(row["points_used"])),
            "team_seats": int(row["team_seats"]),
            "period_start": int(row["period_start"]),
            "period_end": int(row["period_end"]),
        }

    @staticmethod
    def _usage_event_payload(row: sqlite3.Row) -> dict:
        payload = dict(row)
        payload["charged"] = bool(payload["charged"])
        payload["metadata"] = json.loads(payload.pop("metadata_json") or "{}")
        return payload

    def _profile(self, conn: sqlite3.Connection, user_id: str) -> dict:
        self._ensure_usage_balance(conn, user_id, int(time.time()))
        row = conn.execute(
            """
            SELECT u.id, u.username, u.display_name, u.avatar_data_url,
                   u.role, u.enabled, u.created_at,
                   e.vip_enabled, e.vip_expires_at, e.construction_logs_unlocked,
                   e.quota_granted, t.request_limit, t.requests_used
            FROM users u
            JOIN user_entitlements e ON e.user_id = u.id
            LEFT JOIN agent_tokens t ON t.id = ?
            WHERE u.id = ?
            """,
            (self._agent_token_id(user_id), user_id),
        ).fetchone()
        if row is None:
            raise AccountNotFoundError("用户不存在")
        request_limit = int(row["request_limit"] or 0)
        requests_used = int(row["requests_used"] or 0)
        plan = self._active_plan(conn, user_id, row["role"], bool(row["vip_enabled"]))
        usage = self._usage_balance(conn, user_id)
        identities = conn.execute(
            "SELECT provider FROM account_identities WHERE user_id = ? ORDER BY created_at ASC",
            (user_id,),
        ).fetchall()
        return {
            "id": row["id"],
            "username": row["username"],
            "display_name": row["display_name"] or "",
            "avatar_data_url": row["avatar_data_url"],
            "role": row["role"],
            "is_admin": row["role"] == "admin",
            "enabled": bool(row["enabled"]),
            "vip_enabled": bool(row["vip_enabled"]),
            "vip_expires_at": row["vip_expires_at"],
            "construction_logs_unlocked": bool(row["construction_logs_unlocked"]),
            "plan_code": plan["code"],
            "plan_name": plan["name"],
            "created_at": row["created_at"],
            "identity_providers": [identity["provider"] for identity in identities],
            "usage": usage,
            "quota": {
                "request_limit": request_limit,
                "requests_used": requests_used,
                "requests_remaining": max(0, request_limit - requests_used),
            },
        }

    @staticmethod
    def _ensure_profile_columns(conn: sqlite3.Connection) -> None:
        columns = {
            str(row["name"])
            for row in conn.execute("PRAGMA table_info(users)").fetchall()
        }
        if "display_name" not in columns:
            conn.execute(
                "ALTER TABLE users ADD COLUMN display_name TEXT NOT NULL DEFAULT ''"
            )
        if "avatar_data_url" not in columns:
            conn.execute("ALTER TABLE users ADD COLUMN avatar_data_url TEXT")

    def _ensure_billing_columns(self, conn: sqlite3.Connection) -> None:
        entitlement_columns = {
            str(row["name"])
            for row in conn.execute("PRAGMA table_info(user_entitlements)").fetchall()
        }
        if "vip_expires_at" not in entitlement_columns:
            conn.execute("ALTER TABLE user_entitlements ADD COLUMN vip_expires_at INTEGER")
        usage_columns = {
            str(row["name"])
            for row in conn.execute("PRAGMA table_info(account_usage_balances)").fetchall()
        }
        for name, definition in (
            ("points_granted", "INTEGER NOT NULL DEFAULT 0"),
            ("points_used", "INTEGER NOT NULL DEFAULT 0"),
        ):
            if name not in usage_columns:
                conn.execute(f"ALTER TABLE account_usage_balances ADD COLUMN {name} {definition}")
        conn.execute(
            """
            UPDATE account_usage_balances
            SET points_granted = CASE
                WHEN points_granted > 0 THEN points_granted
                ELSE MAX(?, included_stt_seconds / 60 * ?)
            END
            WHERE points_granted <= 0
            """,
            (self.free_points, self.stt_points_per_minute),
        )
        plan_columns = {
            str(row["name"])
            for row in conn.execute("PRAGMA table_info(account_plans)").fetchall()
        }
        for name, definition in (
            ("included_minutes", "INTEGER NOT NULL DEFAULT 0"),
            ("ai_credits", "INTEGER NOT NULL DEFAULT 0"),
            ("points", "INTEGER NOT NULL DEFAULT 0"),
            ("team_seats", "INTEGER NOT NULL DEFAULT 1"),
            ("duration_days", "INTEGER NOT NULL DEFAULT 30"),
        ):
            if name not in plan_columns:
                conn.execute(f"ALTER TABLE account_plans ADD COLUMN {name} {definition}")

        order_columns = {
            str(row["name"])
            for row in conn.execute("PRAGMA table_info(recharge_orders)").fetchall()
        }
        for name, definition in (
            ("included_minutes", "INTEGER NOT NULL DEFAULT 0"),
            ("ai_credits", "INTEGER NOT NULL DEFAULT 0"),
            ("points", "INTEGER NOT NULL DEFAULT 0"),
            ("team_seats", "INTEGER NOT NULL DEFAULT 1"),
            ("duration_days", "INTEGER NOT NULL DEFAULT 30"),
            ("subscription_started_at", "INTEGER"),
            ("subscription_expires_at", "INTEGER"),
        ):
            if name not in order_columns:
                conn.execute(f"ALTER TABLE recharge_orders ADD COLUMN {name} {definition}")
        conn.execute(
            """
            UPDATE account_plans
            SET points = CASE
                WHEN points > 0 THEN points
                ELSE MAX(quota_amount, included_minutes * ? + ai_credits * ?)
            END
            WHERE points <= 0
            """,
            (self.stt_points_per_minute, self.ai_summary_points),
        )
        conn.execute(
            """
            UPDATE recharge_orders
            SET points = CASE
                WHEN points > 0 THEN points
                ELSE MAX(quota_amount, included_minutes * ? + ai_credits * ?)
            END
            WHERE points <= 0
            """,
            (self.stt_points_per_minute, self.ai_summary_points),
        )

    @staticmethod
    def _ensure_auth_code_purpose_constraint(conn: sqlite3.Connection) -> None:
        row = conn.execute(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'auth_verification_codes'"
        ).fetchone()
        definition = str(row["sql"] or "") if row is not None else ""
        if "register" in definition and "reset_password" in definition:
            return
        conn.executescript(
            """
            ALTER TABLE auth_verification_codes RENAME TO auth_verification_codes_legacy;
            CREATE TABLE auth_verification_codes (
                id TEXT PRIMARY KEY,
                channel TEXT NOT NULL CHECK(channel IN ('email', 'phone')),
                subject TEXT NOT NULL,
                purpose TEXT NOT NULL CHECK(purpose IN ('login', 'register', 'bind', 'reset_password')),
                code_hash TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                max_attempts INTEGER NOT NULL,
                consumed_at INTEGER,
                created_at INTEGER NOT NULL
            );
            INSERT INTO auth_verification_codes SELECT * FROM auth_verification_codes_legacy;
            DROP TABLE auth_verification_codes_legacy;
            CREATE INDEX index_auth_codes_subject_created
            ON auth_verification_codes(channel, subject, purpose, created_at DESC);
            """
        )

    @staticmethod
    def _ensure_account_meeting_template_constraint(conn: sqlite3.Connection) -> None:
        row = conn.execute(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'account_meetings'"
        ).fetchone()
        definition = str(row["sql"] or "").casefold() if row is not None else ""
        if "'inspection'" in definition:
            return

        conn.executescript(
            """
            ALTER TABLE account_meetings RENAME TO account_meetings_legacy;
            CREATE TABLE account_meetings (
                user_id TEXT NOT NULL,
                id TEXT NOT NULL,
                title TEXT NOT NULL,
                template_key TEXT NOT NULL CHECK(template_key IN ('project', 'administrative', 'brainstorming', 'inspection')),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                duration_seconds INTEGER NOT NULL DEFAULT 0,
                transcript TEXT NOT NULL DEFAULT '',
                report TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(user_id, id),
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            );
            INSERT INTO account_meetings (
                user_id, id, title, template_key, created_at, updated_at,
                duration_seconds, transcript, report
            )
            SELECT user_id, id, title, template_key, created_at, updated_at,
                   duration_seconds, transcript, report
            FROM account_meetings_legacy;
            DROP TABLE account_meetings_legacy;
            CREATE INDEX IF NOT EXISTS index_account_meetings_user_updated
            ON account_meetings(user_id, updated_at DESC);
            """
        )

    def _validate_avatar_data_url(self, value: str | None) -> str | None:
        clean = (value or "").strip()
        if not clean:
            return None
        prefix, separator, encoded = clean.partition(",")
        allowed_prefixes = {
            "data:image/jpeg;base64",
            "data:image/png;base64",
            "data:image/webp;base64",
        }
        if separator != "," or prefix.casefold() not in allowed_prefixes:
            raise AccountError("头像格式无效")
        try:
            decoded = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as exc:
            raise AccountError("头像数据无效") from exc
        if not decoded or len(decoded) > self.profile_avatar_max_bytes:
            raise AccountError("头像文件过大")
        return clean

    def _active_plan(
        self,
        conn: sqlite3.Connection,
        user_id: str,
        role: str,
        vip_enabled: bool,
    ) -> dict[str, str]:
        if role == "admin":
            return {"code": "admin", "name": "管理员"}
        if not vip_enabled:
            return {"code": self.free_plan_code, "name": self.free_plan_name}
        row = conn.execute(
            """
            SELECT p.code, p.name
            FROM recharge_orders o
            JOIN account_plans p ON p.id = o.plan_id
            WHERE o.user_id = ? AND o.status = 'approved'
            ORDER BY o.decided_at DESC, o.created_at DESC
            LIMIT 1
            """,
            (user_id,),
        ).fetchone()
        if row is None:
            return {"code": "points", "name": "积分账户"}
        return {"code": row["code"], "name": row["name"]}

    def _refresh_subscription(self, conn: sqlite3.Connection, user_id: str, now: int) -> None:
        entitlement = conn.execute(
            "SELECT vip_enabled, vip_expires_at FROM user_entitlements WHERE user_id = ?",
            (user_id,),
        ).fetchone()
        if entitlement is None or not entitlement["vip_enabled"]:
            return
        expires_at = entitlement["vip_expires_at"]
        if expires_at is None or int(expires_at) > now:
            return
        conn.execute(
            """
            UPDATE user_entitlements
            SET vip_enabled = 0, construction_logs_unlocked = 0,
                quota_granted = ?, updated_at = ?
            WHERE user_id = ?
            """,
            (self.free_request_limit, now, user_id),
        )
        conn.execute(
            """
            UPDATE account_usage_balances
            SET included_stt_seconds = ?, stt_seconds_used = 0,
                ai_credits_granted = ?, ai_credits_used = 0,
                points_granted = ?, points_used = 0,
                team_seats = 1, period_start = ?, period_end = ?, updated_at = ?
            WHERE user_id = ?
            """,
            (
                self.free_stt_minutes * 60,
                self.free_ai_credits,
                self.free_points,
                now,
                now + 30 * 24 * 60 * 60,
                now,
                user_id,
            ),
        )
        team = conn.execute(
            "SELECT id FROM account_teams WHERE owner_user_id = ?", (user_id,)
        ).fetchone()
        if team is not None:
            conn.execute("UPDATE account_teams SET seat_limit = 1, updated_at = ? WHERE id = ?", (now, team["id"]))
            conn.execute("DELETE FROM account_team_members WHERE team_id = ? AND role = 'member'", (team["id"],))

    def _initialize_growth_user(
        self, conn: sqlite3.Connection, user_id: str, now: int, referral_code: str | None,
    ) -> None:
        existing = conn.execute("SELECT code FROM growth_referral_codes WHERE user_id = ?", (user_id,)).fetchone()
        if existing is None:
            for _ in range(5):
                code = f"ZW{secrets.token_hex(4).upper()}"
                try:
                    conn.execute(
                        "INSERT INTO growth_referral_codes (id, user_id, code, created_at) VALUES (?, ?, ?, ?)",
                        (str(uuid.uuid4()), user_id, code, now),
                    )
                    break
                except sqlite3.IntegrityError:
                    continue
        clean_referral = (referral_code or "").strip().upper().replace(" ", "")
        if not clean_referral:
            return
        inviter = conn.execute(
            "SELECT user_id, enabled, max_uses, used_count, expires_at FROM growth_referral_codes WHERE code = ?",
            (clean_referral,),
        ).fetchone()
        if inviter is None or not inviter["enabled"] or inviter["user_id"] == user_id:
            raise AccountError("邀请码无效")
        if inviter["expires_at"] is not None and int(inviter["expires_at"]) <= now:
            raise AccountError("邀请码已过期")
        if int(inviter["max_uses"]) > 0 and int(inviter["used_count"]) >= int(inviter["max_uses"]):
            raise AccountError("邀请码已达到使用上限")
        binding_id = str(uuid.uuid4())
        conn.execute(
            "INSERT INTO growth_referral_bindings (id, referrer_user_id, referred_user_id, code, created_at) VALUES (?, ?, ?, ?, ?)",
            (binding_id, inviter["user_id"], user_id, clean_referral, now),
        )
        conn.execute("UPDATE growth_referral_codes SET used_count = used_count + 1 WHERE user_id = ?", (inviter["user_id"],))
        # 注册绑定即记账，后续可由首次有效使用把 rewarded_at 标记为完成。
        self._grant_growth_reward(conn, inviter["user_id"], "referral", binding_id, "points", 100, f"referral:{binding_id}:inviter", now)
        self._grant_growth_reward(conn, user_id, "referral", binding_id, "points", 100, f"referral:{binding_id}:invitee", now)
        conn.execute("UPDATE growth_referral_bindings SET rewarded_at = ?, qualified_at = ? WHERE id = ?", (now, now, binding_id))

    def _grant_growth_reward(
        self, conn: sqlite3.Connection, user_id: str, source_type: str, source_id: str,
        reward_type: str, quantity: int, idempotency_key: str, now: int,
    ) -> None:
        try:
            conn.execute(
                "INSERT INTO growth_reward_ledger (id, user_id, source_type, source_id, reward_type, quantity, idempotency_key, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (str(uuid.uuid4()), user_id, source_type, source_id, reward_type, quantity, idempotency_key, now),
            )
        except sqlite3.IntegrityError:
            return
        if reward_type == "points":
            conn.execute(
                "UPDATE account_usage_balances SET points_granted = points_granted + ?, updated_at = ? WHERE user_id = ?",
                (quantity, now, user_id),
            )

    @staticmethod
    def _reward_message(reward_type: str, quantity: int) -> str:
        labels = {"points": "积分", "coupon": "优惠券", "entitlement": "虚拟权益"}
        return f"已到账 {quantity} {labels.get(reward_type, reward_type)}"

    @staticmethod
    def _json_row(row: sqlite3.Row, field: str, target: str) -> dict:
        payload = dict(row)
        try:
            payload[target] = json.loads(payload.pop(field) or "{}")
        except (TypeError, json.JSONDecodeError):
            payload[target] = {}
        return payload

    def _campaign_payload(self, conn: sqlite3.Connection, campaign_id: str) -> dict:
        row = conn.execute(
            "SELECT id, title, campaign_type, summary, rules_json, reward_pool_json, starts_at, ends_at, status, settled_at FROM growth_campaigns WHERE id = ?",
            (campaign_id,),
        ).fetchone()
        if row is None:
            raise AccountNotFoundError("活动不存在")
        payload = dict(row)
        payload["rules"] = json.loads(payload.pop("rules_json") or "{}")
        payload["reward_pool"] = json.loads(payload.pop("reward_pool_json") or "{}")
        return payload

    @staticmethod
    def _assert_campaign_open(campaign: dict, now: int) -> None:
        if campaign["status"] not in {"active", "running"}:
            raise AccountConflictError("活动当前不可参与")
        if int(campaign["starts_at"]) > now:
            raise AccountConflictError("活动尚未开始")
        if int(campaign["ends_at"]) <= now:
            raise AccountConflictError("活动已经结束")

    @staticmethod
    def _ensure_campaign_entry(conn: sqlite3.Connection, campaign_id: str, user_id: str, now: int) -> None:
        conn.execute(
            "INSERT INTO growth_campaign_entries (id, campaign_id, user_id, score, metadata_json, created_at) VALUES (?, ?, ?, 0, '{}', ?) ON CONFLICT(campaign_id, user_id) DO NOTHING",
            (str(uuid.uuid4()), campaign_id, user_id, now),
        )

    @staticmethod
    def _increment_campaign_score(conn: sqlite3.Connection, campaign_id: str, user_id: str, score: int, now: int) -> None:
        conn.execute(
            "UPDATE growth_campaign_entries SET score = score + ?, metadata_json = json_set(metadata_json, '$.last_action_at', ?) WHERE campaign_id = ? AND user_id = ?",
            (score, now, campaign_id, user_id),
        )

    @staticmethod
    def _campaign_leaderboard(conn: sqlite3.Connection, campaign_id: str, limit: int) -> list[dict]:
        rows = conn.execute(
            "SELECT e.user_id, COALESCE(NULLIF(u.display_name, ''), u.username) AS display_name, e.score, e.rank FROM growth_campaign_entries e JOIN users u ON u.id = e.user_id WHERE e.campaign_id = ? ORDER BY e.score DESC, e.created_at ASC LIMIT ?",
            (campaign_id, max(1, min(100, limit))),
        ).fetchall()
        return [dict(row) for row in rows]

    def _settle_due_campaigns(self, conn: sqlite3.Connection, now: int) -> None:
        rows = conn.execute(
            "SELECT id FROM growth_campaigns WHERE status IN ('active', 'running') AND ends_at <= ? AND settled_at IS NULL",
            (now,),
        ).fetchall()
        for row in rows:
            self._settle_campaign(conn, row["id"], now)

    def _settle_campaign(self, conn: sqlite3.Connection, campaign_id: str, now: int) -> dict:
        campaign = self._campaign_payload(conn, campaign_id)
        if campaign["settled_at"] is not None or campaign["status"] == "settled":
            return {"status": "settled", "campaign_id": campaign_id, "already_settled": True}
        rows = conn.execute(
            "SELECT user_id, score FROM growth_campaign_entries WHERE campaign_id = ? ORDER BY score DESC, created_at ASC",
            (campaign_id,),
        ).fetchall()
        ranks = campaign["reward_pool"].get("ranks", {})
        winners = []
        for index, row in enumerate(rows, start=1):
            conn.execute("UPDATE growth_campaign_entries SET rank = ? WHERE campaign_id = ? AND user_id = ?", (index, campaign_id, row["user_id"]))
            reward = int(ranks.get(str(index), 0))
            if reward > 0:
                reward_id = f"{campaign_id}:{row['user_id']}:rank:{index}"
                self._grant_growth_reward(conn, row["user_id"], "campaign_rank", reward_id, "points", reward, f"campaign-settle:{reward_id}", now)
                winners.append({"user_id": row["user_id"], "rank": index, "score": int(row["score"]), "quantity": reward})
        conn.execute("UPDATE growth_campaigns SET status = 'settled', settled_at = ? WHERE id = ?", (now, campaign_id))
        return {"status": "settled", "campaign_id": campaign_id, "winners": winners}

    def _campaign_rows(self, conn: sqlite3.Connection, now: int) -> list[dict]:
        rows = conn.execute(
            "SELECT id, title, campaign_type, summary, rules_json, reward_pool_json, starts_at, ends_at, status FROM growth_campaigns WHERE ends_at >= ? ORDER BY starts_at ASC LIMIT 20",
            (now,),
        ).fetchall()
        result = []
        for row in rows:
            payload = dict(row)
            payload["rules"] = json.loads(payload.pop("rules_json") or "{}")
            payload["reward_pool"] = json.loads(payload.pop("reward_pool_json") or "{}")
            result.append(payload)
        return result

    def _private_channel(self, conn: sqlite3.Connection, channel_id: str | None = None) -> dict | None:
        row = conn.execute(
            "SELECT * FROM growth_private_channels WHERE id = ? AND enabled = 1" if channel_id else "SELECT * FROM growth_private_channels WHERE enabled = 1 ORDER BY updated_at DESC LIMIT 1",
            (channel_id,) if channel_id else (),
        ).fetchone()
        if row is None:
            return None
        payload = dict(row)
        payload["enabled"] = bool(payload["enabled"])
        if payload.get("qr_image_url") == "/app/assets/welfare-group-qr.jpg":
            payload["qr_image_url"] = "/api/growth/private-channel/default-qr"
        payload["reward"] = json.loads(payload.pop("reward_payload_json") or "{}")
        return payload

    @staticmethod
    def _ensure_growth_defaults(conn: sqlite3.Connection) -> None:
        now = int(time.time())
        conn.execute(
            "INSERT INTO growth_private_channels (id, name, qr_image_url, slogan, reward_type, reward_payload_json, updated_at) VALUES ('default-welfare-group', '智悟本福利群', '/api/growth/private-channel/default-qr', '扫码加入福利群，每日专属兑换码、活动优先通知、客服一对一', 'points', '{\"quantity\":50}', ?) ON CONFLICT(id) DO NOTHING",
            (now,),
        )
        samples = (
            ("invitation-ranking", "邀请好友得积分排行赛", "ranking", "邀请好友注册，双方各得 100 积分，前 20 名额外获得兑换码。", {"checkin_reward": 10}, {"ranks": {"1": 1000, "2": 600, "3": 300}}),
            ("daily-quiz", "每日答题兑好礼", "quiz", "每天完成 3 道题，答对即可获得积分并参与周榜。", {"checkin_reward": 10, "answer_reward": 20, "questions": [{"key": "q1", "question": "浙江省省会是哪里？", "options": ["杭州", "宁波", "温州"], "answer": "杭州"}, {"key": "q2", "question": "每天完成记录后最重要的动作是什么？", "options": ["复盘", "删除", "跳过"], "answer": "复盘"}]}, {"ranks": {"1": 500, "2": 300, "3": 100}}),
            ("zhejiang-study", "浙江研学创作挑战", "contest", "记录一次浙江本地研学见闻，优秀作品可获得积分奖励。", {"checkin_reward": 20, "draw_reward": 80, "win_probability": 2000}, {"ranks": {"1": 800, "2": 500, "3": 300}}),
        )
        for campaign_id, title, campaign_type, summary, rules, reward_pool in samples:
            conn.execute(
                "INSERT INTO growth_campaigns (id, title, campaign_type, summary, rules_json, reward_pool_json, starts_at, ends_at, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?) ON CONFLICT(id) DO NOTHING",
                (campaign_id, title, campaign_type, summary, json.dumps(rules, ensure_ascii=False), json.dumps(reward_pool, ensure_ascii=False), now, now + 30 * 86400, now),
            )

    def _ensure_team(self, conn: sqlite3.Connection, owner_user_id: str, now: int) -> sqlite3.Row:
        usage = self._usage_balance(conn, owner_user_id)
        owner = conn.execute("SELECT username FROM users WHERE id = ?", (owner_user_id,)).fetchone()
        if owner is None:
            raise AccountNotFoundError("用户不存在")
        conn.execute(
            """
            INSERT INTO account_teams (id, owner_user_id, name, seat_limit, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(owner_user_id) DO UPDATE SET
                seat_limit = excluded.seat_limit, updated_at = excluded.updated_at
            """,
            (
                str(uuid.uuid4()), owner_user_id, f"{owner['username']}的团队",
                max(1, int(usage["team_seats"])), now, now,
            ),
        )
        team = conn.execute("SELECT * FROM account_teams WHERE owner_user_id = ?", (owner_user_id,)).fetchone()
        conn.execute(
            """
            INSERT INTO account_team_members (team_id, user_id, role, joined_at)
            VALUES (?, ?, 'owner', ?)
            ON CONFLICT(team_id, user_id) DO NOTHING
            """,
            (team["id"], owner_user_id, now),
        )
        return team

    def _team_payload(self, conn: sqlite3.Connection, user_id: str, now: int) -> dict:
        membership = conn.execute(
            """
            SELECT t.* FROM account_teams t
            JOIN account_team_members m ON m.team_id = t.id
            WHERE m.user_id = ?
            """,
            (user_id,),
        ).fetchone()
        if membership is None:
            membership = self._ensure_team(conn, user_id, now)
        members = conn.execute(
            """
            SELECT u.id AS user_id, u.username, u.display_name, m.role, m.joined_at
            FROM account_team_members m JOIN users u ON u.id = m.user_id
            WHERE m.team_id = ? ORDER BY m.role DESC, m.joined_at ASC
            """,
            (membership["id"],),
        ).fetchall()
        return {
            "id": membership["id"],
            "name": membership["name"],
            "owner_user_id": membership["owner_user_id"],
            "seat_limit": int(membership["seat_limit"]),
            "seats_used": len(members),
            "members": [dict(member) for member in members],
        }

    def _consume_auth_code(
        self,
        conn: sqlite3.Connection,
        channel: str,
        subject: str,
        code: str,
        purpose: str,
        now: int,
    ) -> None:
        clean_code = code.strip()
        if not re.fullmatch(r"\d{6}", clean_code):
            raise AccountAuthError("验证码无效或已过期")
        row = conn.execute(
            """
            SELECT id, code_hash, expires_at, attempts, max_attempts
            FROM auth_verification_codes
            WHERE channel = ? AND subject = ? AND purpose = ? AND consumed_at IS NULL
            ORDER BY created_at DESC LIMIT 1
            """,
            (channel, subject, purpose),
        ).fetchone()
        if row is None or int(row["expires_at"]) <= now:
            raise AccountAuthError("验证码无效或已过期")
        if int(row["attempts"]) >= int(row["max_attempts"]):
            raise AccountAuthError("验证码尝试次数过多，请重新获取")
        if not hmac.compare_digest(self._verification_code_hash(row["id"], clean_code), row["code_hash"]):
            conn.execute("UPDATE auth_verification_codes SET attempts = attempts + 1 WHERE id = ?", (row["id"],))
            conn.commit()
            raise AccountAuthError("验证码无效或已过期")
        conn.execute("UPDATE auth_verification_codes SET consumed_at = ? WHERE id = ?", (now, row["id"]))

    def _order(self, conn: sqlite3.Connection, order_id: str) -> dict:
        row = conn.execute(
            """
            SELECT o.id, o.user_id, u.username, p.code AS plan_code,
                   p.name AS plan_name, o.amount_cents, o.quota_amount, o.points,
                   o.construction_logs_unlocked, o.included_minutes,
                   o.ai_credits, o.team_seats, o.duration_days,
                   o.subscription_started_at, o.subscription_expires_at,
                   o.status, o.created_at, o.decided_at, o.decided_by
            FROM recharge_orders o
            JOIN users u ON u.id = o.user_id
            JOIN account_plans p ON p.id = o.plan_id
            WHERE o.id = ?
            """,
            (order_id,),
        ).fetchone()
        if row is None:
            raise AccountNotFoundError("充值订单不存在")
        payload = dict(row)
        payload["construction_logs_unlocked"] = bool(payload["construction_logs_unlocked"])
        return payload

    def _seed_plans(self, conn: sqlite3.Connection) -> None:
        if self.plans_path is None or not self.plans_path.is_file():
            return
        payload = json.loads(self.plans_path.read_text(encoding="utf-8"))
        if not isinstance(payload, list):
            raise RuntimeError("ACCOUNT_PLANS_PATH must contain a JSON array")
        now = int(time.time())
        configured_codes: list[str] = []
        for index, plan in enumerate(payload):
            code = str(plan.get("code", "")).strip()
            name = str(plan.get("name", "")).strip()
            price_cents = int(plan.get("price_cents", -1))
            raw_quota_amount = plan.get("quota_amount")
            legacy_quota_amount = int(raw_quota_amount or 0)
            included_minutes = int(plan.get("included_minutes", 0))
            ai_credits = int(plan.get("ai_credits", legacy_quota_amount))
            points = int(
                plan.get(
                    "points",
                    max(
                        legacy_quota_amount,
                        included_minutes * self.stt_points_per_minute
                        + ai_credits * self.ai_summary_points,
                    ),
                )
            )
            quota_amount = int(
                raw_quota_amount
                if raw_quota_amount is not None and int(raw_quota_amount) > 0
                else points
            )
            team_seats = int(plan.get("team_seats", 1))
            duration_days = int(plan.get("duration_days", 30))
            if not code or not name or price_cents < 0 or quota_amount < 1 or points < 1:
                raise RuntimeError(f"Invalid account plan at index {index}")
            if included_minutes < 0 or ai_credits < 0 or team_seats < 1 or duration_days < 1:
                raise RuntimeError(f"Invalid account billing values at index {index}")
            configured_codes.append(code)
            conn.execute(
                """
                INSERT INTO account_plans (
                    id, code, name, description, price_cents, quota_amount, points,
                    construction_logs_unlocked, included_minutes, ai_credits,
                    team_seats, duration_days, active, sort_order,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(code) DO UPDATE SET
                    name = excluded.name,
                    description = excluded.description,
                    price_cents = excluded.price_cents,
                    quota_amount = excluded.quota_amount,
                    points = excluded.points,
                    construction_logs_unlocked = excluded.construction_logs_unlocked,
                    included_minutes = excluded.included_minutes,
                    ai_credits = excluded.ai_credits,
                    team_seats = excluded.team_seats,
                    duration_days = excluded.duration_days,
                    active = excluded.active,
                    sort_order = excluded.sort_order,
                    updated_at = excluded.updated_at
                """,
                (
                    str(uuid.uuid4()),
                    code,
                    name,
                    str(plan.get("description", "")).strip(),
                    price_cents,
                    quota_amount,
                    points,
                    int(bool(plan.get("construction_logs_unlocked", False))),
                    included_minutes,
                    ai_credits,
                    team_seats,
                    duration_days,
                    int(bool(plan.get("active", True))),
                    int(plan.get("sort_order", index)),
                    now,
                    now,
                ),
            )
        if configured_codes:
            placeholders = ", ".join("?" for _ in configured_codes)
            conn.execute(
                f"UPDATE account_plans SET active = 0, updated_at = ? "
                f"WHERE code NOT IN ({placeholders}) AND active = 1",
                (now, *configured_codes),
            )
        else:
            conn.execute("UPDATE account_plans SET active = 0, updated_at = ?", (now,))

    @staticmethod
    def _plan_payload(row: sqlite3.Row) -> dict:
        payload = dict(row)
        payload["construction_logs_unlocked"] = bool(payload["construction_logs_unlocked"])
        return payload

    @staticmethod
    def _validate_username(username: str) -> str:
        clean = username.strip()
        if not clean:
            raise AccountError("用户名不能为空")
        if not all(char.isalnum() or char == "_" for char in clean):
            raise AccountError("用户名只能包含文字、数字和下划线")
        return clean

    @staticmethod
    def _validate_password(password: str) -> None:
        if not 8 <= len(password) <= 128:
            raise AccountError("密码长度必须为 8-128 个字符")

    @staticmethod
    def _normalize_identity(channel: str, identifier: str) -> tuple[str, str]:
        clean_channel = channel.strip().lower()
        value = identifier.strip()
        if clean_channel == "email":
            value = value.casefold()
            if len(value) > 254 or not re.fullmatch(
                r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+",
                value,
            ):
                raise AccountError("邮箱地址格式无效")
            return clean_channel, value
        if clean_channel == "phone":
            digits = re.sub(r"[\s-]", "", value)
            if digits.startswith("+86"):
                digits = digits[3:]
            elif digits.startswith("0086"):
                digits = digits[4:]
            if not re.fullmatch(r"1[3-9]\d{9}", digits):
                raise AccountError("手机号格式无效")
            return clean_channel, "+86" + digits
        raise AccountError("仅支持邮箱或手机验证码")

    def _verification_code_hash(self, code_id: str, code: str) -> str:
        return hmac.new(
            self.token_secret.encode("utf-8"),
            f"{code_id}:{code}".encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()

    @staticmethod
    def _mask_identity(provider: str, subject: str) -> str:
        if provider == "email" and "@" in subject:
            local, domain = subject.split("@", 1)
            visible = local[:2] if len(local) > 2 else local[:1]
            return f"{visible}***@{domain}"
        if provider == "phone":
            digits = subject[-11:]
            return f"{digits[:3]}****{digits[-4:]}"
        return subject

    @staticmethod
    def _identity_username(channel: str, subject: str, user_id: str) -> str:
        if channel == "phone":
            return f"用户{subject[-4:]}_{user_id[:6]}"
        local = re.sub(r"[^A-Za-z0-9_]", "_", subject.split("@", 1)[0])[:12]
        return f"{local or 'email'}_{user_id[:8]}"

    @staticmethod
    def _password_hash(password: str, salt: bytes) -> str:
        return hashlib.scrypt(
            password.encode("utf-8"),
            salt=salt,
            n=2**14,
            r=8,
            p=1,
            dklen=32,
            maxmem=64 * 1024 * 1024,
        ).hex()

    @staticmethod
    def _token_hash(token: str) -> str:
        return hashlib.sha256(token.encode("utf-8")).hexdigest()

    def _agent_token(self, user_id: str) -> str:
        digest = hmac.new(
            self.token_secret.encode("utf-8"),
            user_id.encode("utf-8"),
            hashlib.sha256,
        ).digest()
        encoded = base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")
        return "mn_agent_user_" + encoded

    @staticmethod
    def _agent_token_id(user_id: str) -> str:
        return f"user:{user_id}"

    @staticmethod
    def _require_admin(principal: AccountPrincipal) -> None:
        if not principal.is_admin:
            raise AccountPermissionError("需要管理员权限")
