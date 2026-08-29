#!/usr/bin/env python3
"""Database-backed accounts, sessions, plans, and manually approved recharge orders."""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import os
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
from typing import Callable, Iterator, Mapping

SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from common.account_stt_token import issue_account_stt_token


BEIJING_TIMEZONE = timezone(timedelta(hours=8), name="Asia/Shanghai")
REFERRAL_REWARD_POINTS = max(0, int(os.getenv("ACCOUNT_REFERRAL_REWARD_POINTS", "300")))
DEFAULT_PRIVATE_CHANNEL_REWARD_POINTS = 200
SOCIAL_PROVIDERS = {"wechat", "feishu", "qq", "telegram", "whatsapp", "instagram"}
# App-pay trades carry timeout_express=30m; rotate to a fresh out_trade_no only
# after the gateway has certainly closed the old trade, so both trade numbers
# can never be payable at the same time.
ALIPAY_TRADE_ROTATE_AFTER_SEC = 35 * 60


def beijing_day_start_epoch(timestamp: int) -> int:
    observed = datetime.fromtimestamp(timestamp, BEIJING_TIMEZONE)
    return int(observed.replace(hour=0, minute=0, second=0, microsecond=0).timestamp())


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

                CREATE TABLE IF NOT EXISTS alipay_transactions (
                    id TEXT PRIMARY KEY,
                    order_id TEXT NOT NULL UNIQUE,
                    user_id TEXT NOT NULL,
                    out_trade_no TEXT NOT NULL UNIQUE,
                    trade_no TEXT,
                    amount_cents INTEGER NOT NULL,
                    subject TEXT NOT NULL,
                    environment TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('created', 'paid', 'closed', 'refund_pending', 'refunded', 'failed')),
                    last_trade_status TEXT,
                    notify_id TEXT,
                    refund_request_no TEXT,
                    refund_amount_cents INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    paid_at INTEGER,
                    FOREIGN KEY(order_id) REFERENCES recharge_orders(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS alipay_notify_events (
                    id TEXT PRIMARY KEY,
                    notify_id TEXT UNIQUE,
                    payload_hash TEXT NOT NULL UNIQUE,
                    out_trade_no TEXT,
                    trade_no TEXT,
                    trade_status TEXT,
                    payload_json TEXT NOT NULL DEFAULT '{}',
                    processed INTEGER NOT NULL DEFAULT 0,
                    result TEXT NOT NULL DEFAULT '',
                    received_at INTEGER NOT NULL
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
                    provider TEXT NOT NULL CHECK(provider IN ('email', 'phone', 'wechat', 'feishu', 'qq', 'telegram', 'whatsapp', 'instagram', 'password')),
                    subject TEXT NOT NULL,
                    verified INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    UNIQUE(provider, subject),
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS account_registration_sources (
                    user_id TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    referral_code TEXT,
                    referrer_user_id TEXT,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
                    FOREIGN KEY(referrer_user_id) REFERENCES users(id) ON DELETE SET NULL
                );

                CREATE TABLE IF NOT EXISTS social_auth_states (
                    id TEXT PRIMARY KEY,
                    provider TEXT NOT NULL,
                    state_hash TEXT NOT NULL UNIQUE,
                    code_verifier TEXT NOT NULL DEFAULT '',
                    client TEXT NOT NULL DEFAULT 'pwa',
                    redirect_uri TEXT NOT NULL DEFAULT '',
                    referral_code TEXT NOT NULL DEFAULT '',
                    expires_at INTEGER NOT NULL,
                    consumed_at INTEGER,
                    created_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS social_auth_tickets (
                    id TEXT PRIMARY KEY,
                    ticket_hash TEXT NOT NULL UNIQUE,
                    user_id TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    redirect_uri TEXT NOT NULL DEFAULT '',
                    expires_at INTEGER NOT NULL,
                    consumed_at INTEGER,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS social_auth_audit (
                    id TEXT PRIMARY KEY,
                    provider TEXT NOT NULL,
                    event TEXT NOT NULL,
                    user_id TEXT,
                    success INTEGER NOT NULL DEFAULT 0,
                    detail TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE SET NULL
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
                    manager_card_image_url TEXT NOT NULL DEFAULT '',
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
                CREATE TABLE IF NOT EXISTS growth_channel_applications (
                    id TEXT PRIMARY KEY,
                    channel_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    answers_json TEXT NOT NULL DEFAULT '{}',
                    status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending', 'approved', 'rejected')),
                    review_note TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    reviewed_at INTEGER,
                    reviewed_by TEXT,
                    UNIQUE(channel_id, user_id),
                    FOREIGN KEY(channel_id) REFERENCES growth_private_channels(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
                    FOREIGN KEY(reviewed_by) REFERENCES users(id) ON DELETE SET NULL
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
                CREATE TABLE IF NOT EXISTS growth_system_messages (
                    id TEXT PRIMARY KEY,
                    user_id TEXT,
                    message_type TEXT NOT NULL DEFAULT 'system',
                    title TEXT NOT NULL,
                    body TEXT NOT NULL DEFAULT '',
                    campaign_id TEXT,
                    action_path TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    read_at INTEGER,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
                    FOREIGN KEY(campaign_id) REFERENCES growth_campaigns(id) ON DELETE SET NULL
                );
                CREATE TABLE IF NOT EXISTS growth_system_message_receipts (
                    message_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    read_at INTEGER NOT NULL,
                    PRIMARY KEY(message_id, user_id),
                    FOREIGN KEY(message_id) REFERENCES growth_system_messages(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
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
                CREATE INDEX IF NOT EXISTS index_registration_sources_referrer
                ON account_registration_sources(referrer_user_id, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_social_auth_states_expiry
                ON social_auth_states(expires_at, consumed_at);
                CREATE INDEX IF NOT EXISTS index_social_auth_tickets_expiry
                ON social_auth_tickets(expires_at, consumed_at);
                CREATE INDEX IF NOT EXISTS index_social_auth_audit_provider_created
                ON social_auth_audit(provider, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_auth_codes_subject_created
                ON auth_verification_codes(channel, subject, purpose, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_usage_events_user_created
                ON account_usage_events(user_id, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_usage_events_meeting_kind
                ON account_usage_events(user_id, meeting_id, kind, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_growth_events_channel_created
                ON growth_channel_events(channel_id, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_growth_channel_applications_status
                ON growth_channel_applications(channel_id, status, updated_at DESC);
                CREATE INDEX IF NOT EXISTS index_growth_claims_user_created
                ON growth_redemption_claims(user_id, created_at DESC);
                CREATE INDEX IF NOT EXISTS index_growth_actions_campaign_score
                ON growth_campaign_actions(campaign_id, score DESC, created_at ASC);
                CREATE INDEX IF NOT EXISTS index_growth_messages_user_created
                ON growth_system_messages(user_id, created_at DESC);
                """
            )
            self._ensure_profile_columns(conn)
            self._ensure_billing_columns(conn)
            self._ensure_auth_code_purpose_constraint(conn)
            self._ensure_identity_provider_constraint(conn)
            self._ensure_account_meeting_template_constraint(conn)
            self._ensure_growth_columns(conn)
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
            self._record_registration_source(conn, user_id, "password", referral_code, now)
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
            self._record_registration_source(conn, user_id, clean_channel, referral_code, now)
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
            self._record_registration_source(conn, user_id, clean_channel, None, now)
            return self._create_session(conn, user_id)

    def begin_social_auth(
        self,
        provider: str,
        *,
        client: str,
        redirect_uri: str,
        referral_code: str | None = None,
        ttl_sec: int = 10 * 60,
    ) -> dict:
        clean_provider = provider.strip().lower()
        if clean_provider not in SOCIAL_PROVIDERS:
            raise AccountError("不支持的第三方登录平台")
        now = int(time.time())
        raw_state = secrets.token_urlsafe(32)
        verifier = secrets.token_urlsafe(48)
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO social_auth_states (
                    id, provider, state_hash, code_verifier, client, redirect_uri,
                    referral_code, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    str(uuid.uuid4()), clean_provider, self._token_hash(raw_state),
                    verifier, client[:32], redirect_uri[:1000],
                    (referral_code or "").strip().upper()[:64], now + max(60, ttl_sec), now,
                ),
            )
            conn.execute("DELETE FROM social_auth_states WHERE expires_at <= ?", (now,))
        return {"state": raw_state, "code_verifier": verifier}

    def consume_social_auth_state(self, provider: str, raw_state: str) -> dict:
        clean_provider = provider.strip().lower()
        if not raw_state.strip():
            raise AccountAuthError("第三方登录状态无效")
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                """
                SELECT * FROM social_auth_states
                WHERE provider = ? AND state_hash = ? AND consumed_at IS NULL AND expires_at > ?
                """,
                (clean_provider, self._token_hash(raw_state), now),
            ).fetchone()
            if row is None:
                raise AccountAuthError("第三方登录状态无效或已过期")
            conn.execute(
                "UPDATE social_auth_states SET consumed_at = ? WHERE id = ?",
                (now, row["id"]),
            )
            return dict(row)

    def social_identity_login(
        self,
        provider: str,
        subject: str,
        *,
        display_name: str = "",
        avatar_data_url: str | None = None,
        referral_code: str | None = None,
        metadata: Mapping[str, object] | None = None,
    ) -> str:
        clean_provider = provider.strip().lower()
        clean_subject = subject.strip()[:512]
        if clean_provider not in SOCIAL_PROVIDERS or not clean_subject:
            raise AccountAuthError("第三方身份信息无效")
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            identity = conn.execute(
                "SELECT user_id FROM account_identities WHERE provider = ? AND subject = ?",
                (clean_provider, clean_subject),
            ).fetchone()
            if identity is not None:
                user_id = str(identity["user_id"])
                user = conn.execute("SELECT enabled FROM users WHERE id = ?", (user_id,)).fetchone()
                if user is None or not user["enabled"]:
                    self._record_social_audit(conn, clean_provider, "login", user_id, False, "账号已停用", now)
                    raise AccountAuthError("账号已停用")
                self._record_social_audit(conn, clean_provider, "login", user_id, True, "", now)
                return user_id

            user_id = str(uuid.uuid4())
            username_base = self._identity_username(clean_provider, clean_subject, user_id)
            username = username_base
            for index in range(10):
                try:
                    conn.execute(
                        """
                        INSERT INTO users (
                            id, username, username_normalized, password_hash,
                            password_salt, role, enabled, created_at
                        ) VALUES (?, ?, ?, '', '', 'user', 1, ?)
                        """,
                        (user_id, username, username.casefold(), now),
                    )
                    break
                except sqlite3.IntegrityError:
                    username = f"{username_base[:45]}_{index + 1}"
            else:
                raise AccountConflictError("无法创建第三方登录账户")
            conn.execute(
                "INSERT INTO user_entitlements (user_id, vip_enabled, construction_logs_unlocked, quota_granted, updated_at) VALUES (?, 0, 0, ?, ?)",
                (user_id, self.free_request_limit, now),
            )
            conn.execute(
                "INSERT INTO account_identities (id, user_id, provider, subject, verified, created_at) VALUES (?, ?, ?, ?, 1, ?)",
                (str(uuid.uuid4()), user_id, clean_provider, clean_subject, now),
            )
            if display_name or avatar_data_url:
                clean_name = display_name.strip()[: self.profile_name_max_length]
                clean_avatar = self._validate_avatar_data_url(avatar_data_url)
                conn.execute(
                    "UPDATE users SET display_name = ?, avatar_data_url = ? WHERE id = ?",
                    (clean_name, clean_avatar, user_id),
                )
            self._ensure_usage_balance(conn, user_id, now)
            self._record_registration_source(conn, user_id, clean_provider, referral_code, now, metadata)
            self._initialize_growth_user(conn, user_id, now, referral_code)
            self._record_social_audit(conn, clean_provider, "register", user_id, True, "", now)
            return user_id

    def issue_social_ticket(self, user_id: str, provider: str, redirect_uri: str, ttl_sec: int = 5 * 60) -> str:
        raw_ticket = secrets.token_urlsafe(32)
        now = int(time.time())
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO social_auth_tickets (
                    id, ticket_hash, user_id, provider, redirect_uri, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (str(uuid.uuid4()), self._token_hash(raw_ticket), user_id, provider[:32], redirect_uri[:1000], now + max(60, ttl_sec), now),
            )
            conn.execute("DELETE FROM social_auth_tickets WHERE expires_at <= ?", (now,))
        return raw_ticket

    def exchange_social_ticket(self, raw_ticket: str) -> dict:
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                "SELECT * FROM social_auth_tickets WHERE ticket_hash = ? AND consumed_at IS NULL AND expires_at > ?",
                (self._token_hash(raw_ticket.strip()), now),
            ).fetchone()
            if row is None:
                raise AccountAuthError("第三方登录票据无效或已过期")
            conn.execute("UPDATE social_auth_tickets SET consumed_at = ? WHERE id = ?", (now, row["id"]))
            return self._create_session(conn, row["user_id"])

    def record_social_audit(self, provider: str, event: str, success: bool, detail: str = "", user_id: str | None = None) -> None:
        with self._connect() as conn:
            self._record_social_audit(conn, provider, event, user_id, success, detail, int(time.time()))

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
            channel = self._private_channel(
                conn, user_id=principal.user_id, include_qr=True
            )
            return {
                "referral": {
                    "code": referral["code"],
                    "successful_invites": int(referral["used_count"]),
                    "pending_rewards": int(pending["total"]),
                    "reward_points": REFERRAL_REWARD_POINTS,
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
                "private_channel": self._private_channel(
                    conn, user_id=principal.user_id, include_qr=True
                ),
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

    def system_messages(self, principal: AccountPrincipal, limit: int = 50) -> list[dict]:
        with self._connect() as conn:
            self._settle_due_campaigns(conn, int(time.time()))
            rows = conn.execute(
                """
                SELECT m.id, m.message_type, m.title, m.body, m.campaign_id,
                       m.action_path, m.created_at,
                       COALESCE(m.read_at, r.read_at) AS read_at
                FROM growth_system_messages m
                LEFT JOIN growth_system_message_receipts r
                  ON r.message_id = m.id AND r.user_id = ?
                WHERE m.user_id IS NULL OR m.user_id = ?
                ORDER BY m.created_at DESC, m.id DESC
                LIMIT ?
                """,
                (principal.user_id, principal.user_id, max(1, min(100, limit))),
            ).fetchall()
            return [dict(row) for row in rows]

    def mark_system_message_read(
        self, principal: AccountPrincipal, message_id: str
    ) -> dict:
        now = int(time.time())
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT id, user_id FROM growth_system_messages
                WHERE id = ? AND (user_id IS NULL OR user_id = ?)
                """,
                (message_id, principal.user_id),
            ).fetchone()
            if row is None:
                raise AccountNotFoundError("系统消息不存在")
            if row["user_id"] is None:
                conn.execute(
                    """
                    INSERT INTO growth_system_message_receipts (message_id, user_id, read_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(message_id, user_id) DO UPDATE SET read_at = excluded.read_at
                    """,
                    (message_id, principal.user_id, now),
                )
            else:
                conn.execute(
                    "UPDATE growth_system_messages SET read_at = COALESCE(read_at, ?) WHERE id = ?",
                    (now, message_id),
                )
            return {"status": "read", "id": message_id, "read_at": now}

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
        action_date = datetime.fromtimestamp(now, BEIJING_TIMEZONE).strftime("%Y-%m-%d")
        with self._connect() as conn:
            campaign = self._campaign_payload(conn, campaign_id)
            self._assert_campaign_open(campaign, now)
            self._ensure_campaign_entry(conn, campaign_id, principal.user_id, now)
            reward = int(campaign["rules"].get("checkin_reward", 30))
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
            reward = int(campaign["rules"].get("answer_reward", 50)) if correct else 0
            if correct:
                self._increment_campaign_score(conn, campaign_id, principal.user_id, 1, now)
                self._grant_growth_reward(conn, principal.user_id, "campaign_answer", action_id, "points", reward, f"campaign-answer:{campaign_id}:{principal.user_id}:{clean_key}", now)
            return {"status": "accepted", "correct": correct, "quantity": reward, "message": f"回答{'正确' if correct else '错误'}" + (f"，获得 {reward} 积分" if reward else "")}

    def campaign_draw(self, principal: AccountPrincipal, campaign_id: str) -> dict:
        now = int(time.time())
        action_date = datetime.fromtimestamp(now, BEIJING_TIMEZONE).strftime("%Y-%m-%d")
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
            reward = int(campaign["rules"].get("draw_reward", 120)) if won else 0
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
            return self._private_channel(conn, include_qr=True)

    def public_private_channel(self) -> dict | None:
        with self._connect() as conn:
            return self._private_channel(conn, include_qr=False)

    def can_access_private_channel_asset(
        self, filename: str, principal: AccountPrincipal | None
    ) -> bool:
        with self._connect() as conn:
            manager_card = conn.execute(
                "SELECT 1 FROM growth_private_channels WHERE enabled = 1 AND manager_card_image_url LIKE ?",
                (f"%/{filename}",),
            ).fetchone()
            if manager_card is not None:
                return True
            if principal is None:
                return False
            if principal.is_admin:
                return True
            if filename == "default-qr":
                row = conn.execute(
                    "SELECT id FROM growth_private_channels WHERE id = 'default-welfare-group' AND enabled = 1"
                ).fetchone()
            else:
                row = conn.execute(
                    "SELECT id FROM growth_private_channels WHERE enabled = 1 AND qr_image_url LIKE ?",
                    (f"%/{filename}",),
                ).fetchone()
            if row is None:
                return False
            application = conn.execute(
                """
                SELECT 1 FROM growth_channel_applications
                WHERE channel_id = ? AND user_id = ? AND status = 'approved'
                """,
                (row["id"], principal.user_id),
            ).fetchone()
            return application is not None

    def submit_private_channel_application(
        self, principal: AccountPrincipal, channel_id: str, answers: dict
    ) -> dict:
        clean_channel_id = str(channel_id or "default-welfare-group").strip()[:128]
        if not isinstance(answers, dict):
            raise AccountError("请完整填写申请信息")
        cleaned: dict[str, str] = {}
        for key, value in answers.items():
            clean_key = str(key).strip()[:40]
            clean_value = str(value or "").strip()
            if clean_key and clean_value:
                cleaned[clean_key] = clean_value[:500]
        required = ("name", "city", "purpose")
        if any(not cleaned.get(key) for key in required):
            raise AccountError("请填写姓名、所在地区和加入目的")
        if len(json.dumps(cleaned, ensure_ascii=False)) > 8_000:
            raise AccountError("申请信息过长")
        now = int(time.time())
        with self._connect() as conn:
            channel = conn.execute(
                "SELECT id FROM growth_private_channels WHERE id = ? AND enabled = 1",
                (clean_channel_id,),
            ).fetchone()
            if channel is None:
                raise AccountNotFoundError("福利群入口不存在")
            existing = conn.execute(
                "SELECT id, status FROM growth_channel_applications WHERE channel_id = ? AND user_id = ?",
                (clean_channel_id, principal.user_id),
            ).fetchone()
            if existing is not None and str(existing["status"]) == "approved":
                raise AccountConflictError("你已通过审核，无需重复申请")
            if existing is not None and str(existing["status"]) == "pending":
                raise AccountConflictError("你的申请正在审核中")
            application_id = str(existing["id"]) if existing is not None else str(uuid.uuid4())
            if existing is None:
                conn.execute(
                    """
                    INSERT INTO growth_channel_applications
                    (id, channel_id, user_id, answers_json, status, review_note, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'pending', '', ?, ?)
                    """,
                    (application_id, clean_channel_id, principal.user_id, json.dumps(cleaned, ensure_ascii=False), now, now),
                )
            else:
                conn.execute(
                    """
                    UPDATE growth_channel_applications
                    SET answers_json = ?, status = 'pending', review_note = '',
                        updated_at = ?, reviewed_at = NULL, reviewed_by = NULL
                    WHERE id = ?
                    """,
                    (json.dumps(cleaned, ensure_ascii=False), now, application_id),
                )
            return {
                "application": self._channel_application_payload(
                    conn, application_id
                ),
                "channel": self._private_channel(
                    conn, channel_id=clean_channel_id, user_id=principal.user_id, include_qr=True
                ),
            }

    def private_channel_application(
        self, principal: AccountPrincipal, channel_id: str = "default-welfare-group"
    ) -> dict:
        with self._connect() as conn:
            channel = self._private_channel(
                conn, channel_id=channel_id, user_id=principal.user_id, include_qr=True
            )
            if channel is None:
                raise AccountNotFoundError("福利群入口不存在")
            application = conn.execute(
                "SELECT id FROM growth_channel_applications WHERE channel_id = ? AND user_id = ?",
                (channel_id, principal.user_id),
            ).fetchone()
            return {
                "application": self._channel_application_payload(conn, application["id"])
                if application
                else None,
                "channel": channel,
            }

    def admin_list_private_channel_applications(
        self, principal: AccountPrincipal, status: str | None = None
    ) -> list[dict]:
        self._require_admin(principal)
        allowed = {"pending", "approved", "rejected"}
        clean_status = str(status or "").strip().lower()
        if clean_status and clean_status not in allowed:
            raise AccountError("申请状态筛选无效")
        with self._connect() as conn:
            where = "WHERE a.status = ?" if clean_status else ""
            args = (clean_status,) if clean_status else ()
            rows = conn.execute(
                f"""
                SELECT a.id, a.channel_id, a.user_id, a.answers_json, a.status,
                       a.review_note, a.created_at, a.updated_at, a.reviewed_at,
                       a.reviewed_by, c.name AS channel_name,
                       COALESCE(NULLIF(u.display_name, ''), u.username) AS user_name,
                       u.username
                FROM growth_channel_applications a
                JOIN growth_private_channels c ON c.id = a.channel_id
                JOIN users u ON u.id = a.user_id
                {where}
                ORDER BY CASE a.status WHEN 'pending' THEN 0 ELSE 1 END, a.updated_at DESC
                """,
                args,
            ).fetchall()
            return [self._channel_application_payload(conn, row["id"], row) for row in rows]

    def admin_decide_private_channel_application(
        self, principal: AccountPrincipal, application_id: str, decision: str, note: str = ""
    ) -> dict:
        self._require_admin(principal)
        clean_decision = str(decision or "").strip().lower()
        if clean_decision not in {"approved", "rejected"}:
            raise AccountError("审核结果无效")
        now = int(time.time())
        clean_note = str(note or "").strip()[:500]
        with self._connect() as conn:
            row = conn.execute(
                "SELECT id, user_id, channel_id, status FROM growth_channel_applications WHERE id = ?",
                (application_id,),
            ).fetchone()
            if row is None:
                raise AccountNotFoundError("入群申请不存在")
            conn.execute(
                """
                UPDATE growth_channel_applications
                SET status = ?, review_note = ?, reviewed_at = ?, reviewed_by = ?, updated_at = ?
                WHERE id = ?
                """,
                (clean_decision, clean_note, now, principal.user_id, now, application_id),
            )
            title = "福利群入群申请已通过" if clean_decision == "approved" else "福利群入群申请未通过"
            body = (
                "审核已通过，现在可以回到通知中心福利页查看群二维码。"
                if clean_decision == "approved"
                else f"本次申请暂未通过。{clean_note or '如有需要，可补充信息后重新申请。'}"
            )
            conn.execute(
                """
                INSERT OR IGNORE INTO growth_system_messages
                (id, user_id, message_type, title, body, action_path, created_at)
                VALUES (?, ?, 'private_channel_review', ?, ?, '/growth/private-channel', ?)
                """,
                (f"private-channel-application:{application_id}:{clean_decision}", row["user_id"], title, body, now),
            )
            return self._channel_application_payload(conn, application_id)

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
        day_start = beijing_day_start_epoch(now)
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

    def admin_list_redemption_codes(
        self,
        principal: AccountPrincipal,
        batch_id: str,
        *,
        status: str | None = None,
        search: str = "",
        limit: int = 200,
        offset: int = 0,
    ) -> dict:
        self._require_admin(principal)
        allowed_statuses = {"unused", "partial", "used", "expired", "disabled"}
        clean_status = (status or "").strip().lower()
        if clean_status and clean_status not in allowed_statuses:
            raise AccountError("兑换码状态筛选无效")
        clean_search = search.strip().upper()[:64]
        page_limit = max(1, min(500, limit))
        page_offset = max(0, offset)
        now = int(time.time())
        with self._connect() as conn:
            batch = conn.execute(
                "SELECT id, name, enabled FROM growth_redemption_batches WHERE id = ?",
                (batch_id,),
            ).fetchone()
            if batch is None:
                raise AccountNotFoundError("兑换码批次不存在")
            conn.execute(
                """
                UPDATE growth_redemption_codes
                SET status = 'expired'
                WHERE batch_id = ? AND expires_at IS NOT NULL AND expires_at <= ?
                  AND disabled_at IS NULL AND used_count < max_uses
                """,
                (batch_id, now),
            )
            effective_status = """
                CASE
                    WHEN c.disabled_at IS NOT NULL OR b.enabled = 0 THEN 'disabled'
                    WHEN c.expires_at IS NOT NULL AND c.expires_at <= :now THEN 'expired'
                    WHEN c.used_count >= c.max_uses THEN 'used'
                    WHEN c.used_count > 0 THEN 'partial'
                    ELSE 'unused'
                END
            """
            where = ["c.batch_id = :batch_id"]
            params: dict[str, object] = {"batch_id": batch_id, "now": now}
            if clean_status:
                where.append(f"({effective_status}) = :status")
                params["status"] = clean_status
            if clean_search:
                where.append("c.code LIKE :search")
                params["search"] = f"%{clean_search}%"
            where_sql = " AND ".join(where)
            total = int(
                conn.execute(
                    f"SELECT COUNT(*) FROM growth_redemption_codes c JOIN growth_redemption_batches b ON b.id = c.batch_id WHERE {where_sql}",
                    params,
                ).fetchone()[0]
            )
            page_params = {**params, "limit": page_limit, "offset": page_offset}
            rows = conn.execute(
                f"""
                SELECT c.id, c.code, c.used_count, c.max_uses, c.expires_at,
                       c.created_at, c.disabled_at, ({effective_status}) AS effective_status
                FROM growth_redemption_codes c
                JOIN growth_redemption_batches b ON b.id = c.batch_id
                WHERE {where_sql}
                ORDER BY c.created_at DESC, c.code ASC
                LIMIT :limit OFFSET :offset
                """,
                page_params,
            ).fetchall()
            counts = {item: 0 for item in sorted(allowed_statuses)}
            for row in conn.execute(
                f"""
                SELECT ({effective_status}) AS effective_status, COUNT(*) AS total
                FROM growth_redemption_codes c
                JOIN growth_redemption_batches b ON b.id = c.batch_id
                WHERE c.batch_id = :batch_id
                GROUP BY effective_status
                """,
                {"batch_id": batch_id, "now": now},
            ).fetchall():
                counts[str(row["effective_status"])] = int(row["total"])
            code_ids = [str(row["id"]) for row in rows]
            claims_by_code: dict[str, list[dict]] = {code_id: [] for code_id in code_ids}
            if code_ids:
                placeholders = ",".join("?" for _ in code_ids)
                claim_rows = conn.execute(
                    f"""
                    SELECT cl.id, cl.code_id, cl.user_id,
                           COALESCE(NULLIF(u.display_name, ''), u.username) AS display_name,
                           u.username, cl.reward_type, cl.reward_payload_json,
                           cl.status, cl.created_at, cl.fulfilled_at
                    FROM growth_redemption_claims cl
                    JOIN users u ON u.id = cl.user_id
                    WHERE cl.code_id IN ({placeholders})
                    ORDER BY cl.created_at DESC
                    """,
                    code_ids,
                ).fetchall()
                for claim_row in claim_rows:
                    claim = self._json_row(claim_row, "reward_payload_json", "reward")
                    claims_by_code[str(claim["code_id"])].append(claim)
            items = []
            for row in rows:
                payload = dict(row)
                payload["status"] = payload.pop("effective_status")
                payload["claims"] = claims_by_code.get(str(payload["id"]), [])
                items.append(payload)
            return {
                "batch": dict(batch),
                "items": items,
                "total": total,
                "limit": page_limit,
                "offset": page_offset,
                "status_counts": counts,
            }

    def admin_list_campaigns(self, principal: AccountPrincipal) -> list[dict]:
        self._require_admin(principal)
        now = int(time.time())
        with self._connect() as conn:
            self._settle_due_campaigns(conn, now)
            rows = conn.execute(
                """
                SELECT c.id, c.title, c.campaign_type, c.summary, c.rules_json,
                       c.reward_pool_json, c.starts_at, c.ends_at, c.status,
                       c.created_at, c.settled_at,
                       COUNT(DISTINCT e.user_id) AS participant_count
                FROM growth_campaigns c
                LEFT JOIN growth_campaign_entries e ON e.campaign_id = c.id
                GROUP BY c.id
                ORDER BY c.created_at DESC, c.starts_at DESC
                """
            ).fetchall()
            result = []
            for row in rows:
                payload = self._json_row(row, "rules_json", "rules")
                try:
                    payload["reward_pool"] = json.loads(payload.pop("reward_pool_json") or "{}")
                except (TypeError, json.JSONDecodeError):
                    payload["reward_pool"] = {}
                result.append(payload)
            return result

    def admin_create_campaign(self, principal: AccountPrincipal, payload: dict) -> dict:
        self._require_admin(principal)
        normalized = self._validated_campaign_config(payload)
        campaign_id = str(payload.get("id") or uuid.uuid4())[:128]
        now = int(time.time())
        with self._connect() as conn:
            try:
                conn.execute(
                    """
                    INSERT INTO growth_campaigns (
                        id, title, campaign_type, summary, rules_json,
                        reward_pool_json, starts_at, ends_at, status,
                        created_by, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        campaign_id,
                        normalized["title"],
                        normalized["campaign_type"],
                        normalized["summary"],
                        json.dumps(normalized["rules"], ensure_ascii=False),
                        json.dumps(normalized["reward_pool"], ensure_ascii=False),
                        normalized["starts_at"],
                        normalized["ends_at"],
                        normalized["status"],
                        principal.user_id,
                        now,
                    ),
                )
            except sqlite3.IntegrityError as exc:
                raise AccountConflictError("活动 ID 已存在") from exc
            return self._campaign_payload(conn, campaign_id)

    def admin_update_campaign(
        self, principal: AccountPrincipal, campaign_id: str, payload: dict
    ) -> dict:
        self._require_admin(principal)
        normalized = self._validated_campaign_config(payload)
        with self._connect() as conn:
            existing = self._campaign_payload(conn, campaign_id)
            if existing["status"] == "settled":
                raise AccountConflictError("已结算活动不能修改")
            result = conn.execute(
                """
                UPDATE growth_campaigns
                SET title = ?, campaign_type = ?, summary = ?, rules_json = ?,
                    reward_pool_json = ?, starts_at = ?, ends_at = ?, status = ?
                WHERE id = ?
                """,
                (
                    normalized["title"],
                    normalized["campaign_type"],
                    normalized["summary"],
                    json.dumps(normalized["rules"], ensure_ascii=False),
                    json.dumps(normalized["reward_pool"], ensure_ascii=False),
                    normalized["starts_at"],
                    normalized["ends_at"],
                    normalized["status"],
                    campaign_id,
                ),
            )
            if result.rowcount != 1:
                raise AccountNotFoundError("活动不存在")
            return self._campaign_payload(conn, campaign_id)

    def admin_upsert_private_channel(self, principal: AccountPrincipal, payload: dict) -> dict:
        self._require_admin(principal)
        now = int(time.time())
        channel_id = str(payload.get("id") or "default-welfare-group")
        manager_card_url = str(payload.get("manager_card_image_url") or "/api/growth/private-channel/default-manager-card")[:500]
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO growth_private_channels (id, name, qr_image_url, manager_card_image_url, join_url, short_url, slogan, reward_type, reward_payload_json, valid_until, enabled, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET name=excluded.name, qr_image_url=excluded.qr_image_url,
                  manager_card_image_url=excluded.manager_card_image_url,
                  join_url=excluded.join_url, short_url=excluded.short_url, slogan=excluded.slogan,
                  reward_type=excluded.reward_type, reward_payload_json=excluded.reward_payload_json,
                  valid_until=excluded.valid_until, enabled=excluded.enabled, updated_by=excluded.updated_by,
                  updated_at=excluded.updated_at
                """,
                (channel_id, str(payload.get("name", "智悟本福利7群"))[:80], str(payload.get("qr_image_url", ""))[:500], manager_card_url, str(payload.get("join_url", ""))[:500], str(payload.get("short_url", ""))[:500], str(payload.get("slogan", ""))[:300], str(payload.get("reward_type", "points"))[:30], json.dumps(payload.get("reward", {"quantity": DEFAULT_PRIVATE_CHANNEL_REWARD_POINTS}), ensure_ascii=False), payload.get("valid_until"), int(bool(payload.get("enabled", True))), principal.user_id, now),
            )
            return self._private_channel(conn, channel_id, include_qr=True)

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

    def admin_social_auth_audit(
        self, principal: AccountPrincipal, limit: int = 200
    ) -> list[dict]:
        self._require_admin(principal)
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT a.id, a.provider, a.event, a.user_id, a.success,
                       a.detail, a.created_at,
                       COALESCE(NULLIF(u.display_name, ''), u.username) AS user_name
                FROM social_auth_audit a
                LEFT JOIN users u ON u.id = a.user_id
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT ?
                """,
                (max(1, min(500, limit)),),
            ).fetchall()
            return [
                {**dict(row), "success": bool(row["success"])}
                for row in rows
            ]

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
            self._approve_order_conn(conn, order, now, principal.user_id)
            return self._order(conn, order_id)

    def approve_order_from_alipay(self, order_id: str) -> dict:
        """Credit a paid order exactly once from a verified Alipay event."""
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            order = conn.execute(
                "SELECT * FROM recharge_orders WHERE id = ?", (order_id,)
            ).fetchone()
            if order is None:
                raise AccountNotFoundError("充值订单不存在")
            if order["status"] == "pending":
                self._approve_order_conn(conn, order, now, None)
            elif order["status"] != "approved":
                raise AccountConflictError("充值订单当前状态不支持支付入账")
            return self._order(conn, order_id)

    def get_order_for_payment(self, principal: AccountPrincipal, order_id: str) -> dict:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT user_id, status FROM recharge_orders WHERE id = ?", (order_id,)
            ).fetchone()
            if row is None or row["user_id"] != principal.user_id:
                raise AccountNotFoundError("充值订单不存在")
            if row["status"] != "pending":
                raise AccountConflictError("充值订单已处理，不能再次发起支付")
            return self._order(conn, order_id)

    def create_alipay_transaction(
        self,
        principal: AccountPrincipal,
        order_id: str,
        *,
        out_trade_no: str,
        subject: str,
        environment: str,
    ) -> dict:
        order = self.get_order_for_payment(principal, order_id)
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            existing = conn.execute(
                "SELECT * FROM alipay_transactions WHERE order_id = ?", (order_id,)
            ).fetchone()
            if existing is not None:
                if existing["amount_cents"] != order["amount_cents"]:
                    raise AccountConflictError("支付金额与充值订单不一致")
                reusable = existing["status"] == "paid" or (
                    existing["status"] == "created"
                    and now - int(existing["created_at"] or 0) < ALIPAY_TRADE_ROTATE_AFTER_SEC
                )
                if reusable:
                    return dict(existing)
                # The previous trade is closed, failed, or has passed the gateway
                # timeout window: re-signing the same out_trade_no would be
                # rejected by Alipay forever. Rotate to a fresh trade number so
                # the order stays payable. created_at tracks the current trade
                # attempt, which is what the reuse window above measures.
                conn.execute(
                    """
                    UPDATE alipay_transactions
                    SET out_trade_no = ?, status = 'created', trade_no = NULL,
                        last_trade_status = NULL, notify_id = NULL,
                        last_error = '', created_at = ?, updated_at = ?
                    WHERE order_id = ?
                    """,
                    (out_trade_no, now, now, order_id),
                )
                return dict(
                    conn.execute(
                        "SELECT * FROM alipay_transactions WHERE order_id = ?", (order_id,)
                    ).fetchone()
                )
            conn.execute(
                """
                INSERT INTO alipay_transactions (
                    id, order_id, user_id, out_trade_no, amount_cents, subject,
                    environment, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'created', ?, ?)
                """,
                (
                    str(uuid.uuid4()),
                    order_id,
                    principal.user_id,
                    out_trade_no,
                    int(order["amount_cents"]),
                    subject,
                    environment,
                    now,
                    now,
                ),
            )
            return dict(
                conn.execute(
                    "SELECT * FROM alipay_transactions WHERE order_id = ?", (order_id,)
                ).fetchone()
            )

    def alipay_transaction_for_user(self, principal: AccountPrincipal, order_id: str) -> dict:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM alipay_transactions WHERE order_id = ? AND user_id = ?",
                (order_id, principal.user_id),
            ).fetchone()
            if row is None:
                raise AccountNotFoundError("支付宝支付记录不存在")
            return dict(row)

    def alipay_transaction_for_admin(self, principal: AccountPrincipal, order_id: str) -> dict:
        self._require_admin(principal)
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM alipay_transactions WHERE order_id = ?", (order_id,)
            ).fetchone()
            if row is None:
                raise AccountNotFoundError("支付宝支付记录不存在")
            return dict(row)

    def transaction_by_out_trade_no_for_notify(self, out_trade_no: str) -> dict:
        clean = out_trade_no.strip()
        if not clean:
            raise AccountNotFoundError("支付宝商户订单号不存在")
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM alipay_transactions WHERE out_trade_no = ?", (clean,)
            ).fetchone()
            if row is None:
                raise AccountNotFoundError("支付宝支付记录不存在")
            return dict(row)

    def update_alipay_transaction(
        self,
        out_trade_no: str,
        *,
        status: str | None = None,
        trade_no: str | None = None,
        trade_status: str | None = None,
        notify_id: str | None = None,
        last_error: str | None = None,
        refund_request_no: str | None = None,
        refund_amount_cents: int | None = None,
    ) -> dict:
        allowed = {"created", "paid", "closed", "refund_pending", "refunded", "failed"}
        if status is not None and status not in allowed:
            raise AccountError("支付宝支付状态无效")
        fields: list[str] = ["updated_at = ?"]
        values: list[object] = [int(time.time())]
        for name, value in (
            ("status", status),
            ("trade_no", trade_no),
            ("last_trade_status", trade_status),
            ("notify_id", notify_id),
            ("last_error", last_error),
            ("refund_request_no", refund_request_no),
            ("refund_amount_cents", refund_amount_cents),
        ):
            if value is not None:
                fields.append(f"{name} = ?")
                values.append(value)
        if status == "paid":
            fields.append("paid_at = COALESCE(paid_at, ?)")
            values.append(int(time.time()))
        values.append(out_trade_no)
        with self._connect() as conn:
            result = conn.execute(
                f"UPDATE alipay_transactions SET {', '.join(fields)} WHERE out_trade_no = ?",
                tuple(values),
            )
            if result.rowcount != 1:
                raise AccountNotFoundError("支付宝支付记录不存在")
            row = conn.execute(
                "SELECT * FROM alipay_transactions WHERE out_trade_no = ?", (out_trade_no,)
            ).fetchone()
            return dict(row)

    def process_alipay_notification(
        self,
        *,
        out_trade_no: str,
        trade_no: str,
        trade_status: str,
        notify_id: str,
        payload_hash: str,
        payload_json: str,
        paid: bool,
    ) -> dict:
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            duplicate = conn.execute(
                "SELECT processed, result FROM alipay_notify_events WHERE notify_id = ? OR payload_hash = ?",
                (notify_id or None, payload_hash),
            ).fetchone()
            if duplicate is not None and int(duplicate["processed"]):
                return {"duplicate": True, "result": duplicate["result"] or "success"}
            tx = conn.execute(
                "SELECT * FROM alipay_transactions WHERE out_trade_no = ?", (out_trade_no,)
            ).fetchone()
            if tx is None:
                raise AccountNotFoundError("支付宝支付记录不存在")
            event_id = str(uuid.uuid4())
            conn.execute(
                """
                INSERT INTO alipay_notify_events (
                    id, notify_id, payload_hash, out_trade_no, trade_no,
                    trade_status, payload_json, processed, result, received_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, '', ?)
                ON CONFLICT(payload_hash) DO NOTHING
                """,
                (event_id, notify_id or None, payload_hash, out_trade_no, trade_no, trade_status, payload_json, now),
            )
            # A late payment notification must never undo a refund that has
            # already been requested or completed, nor reopen a closed order.
            can_mark_paid = paid and tx["status"] not in {"paid", "refund_pending", "refunded", "closed"}
            if can_mark_paid:
                order = conn.execute(
                    "SELECT * FROM recharge_orders WHERE id = ?", (tx["order_id"],)
                ).fetchone()
                if order is None:
                    raise AccountNotFoundError("充值订单不存在")
                if order["status"] == "pending":
                    self._approve_order_conn(conn, order, now, None)
                conn.execute(
                    """
                    UPDATE alipay_transactions
                    SET status = 'paid', trade_no = ?, last_trade_status = ?,
                        notify_id = ?, paid_at = COALESCE(paid_at, ?), updated_at = ?
                    WHERE out_trade_no = ?
                    """,
                    (trade_no, trade_status, notify_id or None, now, now, out_trade_no),
                )
            else:
                conn.execute(
                    """
                    UPDATE alipay_transactions
                    SET trade_no = COALESCE(?, trade_no), last_trade_status = ?,
                        notify_id = COALESCE(?, notify_id), updated_at = ?
                    WHERE out_trade_no = ?
                    """,
                    (trade_no or None, trade_status, notify_id or None, now, out_trade_no),
                )
            conn.execute(
                "UPDATE alipay_notify_events SET processed = 1, result = 'success' WHERE payload_hash = ?",
                (payload_hash,),
            )
            return {"duplicate": False, "result": "success", "order_id": tx["order_id"]}

    def _approve_order_conn(
        self,
        conn: sqlite3.Connection,
        order: sqlite3.Row,
        now: int,
        decided_by: str | None,
    ) -> None:
        current_entitlement = conn.execute(
            "SELECT vip_expires_at FROM user_entitlements WHERE user_id = ?",
            (order["user_id"],),
        ).fetchone()
        subscription_started_at = max(
            now,
            int(current_entitlement["vip_expires_at"] or 0) if current_entitlement else now,
        )
        subscription_expires_at = subscription_started_at + int(order["duration_days"]) * 24 * 60 * 60
        updated = conn.execute(
            """
            UPDATE recharge_orders
            SET status = 'approved', decided_at = ?, decided_by = ?
            WHERE id = ? AND status = 'pending'
            """,
            (now, decided_by, order["id"]),
        )
        if updated.rowcount != 1:
            return
        conn.execute(
            """
            UPDATE user_entitlements
            SET vip_enabled = 1, vip_expires_at = ?,
                construction_logs_unlocked = MAX(construction_logs_unlocked, ?),
                quota_granted = quota_granted + ?, updated_at = ?
            WHERE user_id = ?
            """,
            (subscription_expires_at, order["construction_logs_unlocked"], order["quota_amount"], now, order["user_id"]),
        )
        conn.execute(
            "UPDATE agent_tokens SET request_limit = request_limit + ? WHERE id = ?",
            (order["quota_amount"], self._agent_token_id(order["user_id"])),
        )
        # The usage row exists after initialization; this guard keeps migration-safe behavior.
        conn.execute(
            """
            INSERT INTO account_usage_balances (
                user_id, included_stt_seconds, stt_seconds_used,
                ai_credits_granted, ai_credits_used, points_granted, points_used,
                team_seats, period_start, period_end, updated_at
            ) VALUES (?, 0, 0, 0, 0, 0, 0, 1, ?, ?, ?)
            ON CONFLICT(user_id) DO NOTHING
            """,
            (order["user_id"], now, now + 30 * 24 * 60 * 60, now),
        )
        conn.execute(
            """
            UPDATE account_usage_balances
            SET included_stt_seconds = MAX(included_stt_seconds, ?),
                ai_credits_granted = MAX(ai_credits_granted, ?),
                points_granted = MAX(points_granted, ?),
                team_seats = MAX(team_seats, ?), updated_at = ?
            WHERE user_id = ?
            """,
            (int(order["included_minutes"]) * 60, int(order["ai_credits"]), int(order["points"] or order["quota_amount"]), int(order["team_seats"]), now, order["user_id"]),
        )
        conn.execute(
            "UPDATE recharge_orders SET subscription_started_at = ?, subscription_expires_at = ? WHERE id = ?",
            (subscription_started_at, subscription_expires_at, order["id"]),
        )
        self._ensure_team(conn, order["user_id"], now)

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
            "SELECT provider, subject, verified, created_at FROM account_identities WHERE user_id = ? ORDER BY created_at ASC",
            (user_id,),
        ).fetchall()
        registration = conn.execute(
            """
            SELECT s.source, s.referral_code, s.referrer_user_id, s.created_at,
                   COALESCE(NULLIF(referrer.display_name, ''), referrer.username) AS referrer_name
            FROM account_registration_sources s
            LEFT JOIN users referrer ON referrer.id = s.referrer_user_id
            WHERE s.user_id = ?
            """,
            (user_id,),
        ).fetchone()
        referral = conn.execute(
            "SELECT code, enabled, used_count FROM growth_referral_codes WHERE user_id = ?",
            (user_id,),
        ).fetchone()
        social_identities = [
            {
                "provider": identity["provider"],
                "subject": self._mask_identity(identity["provider"], identity["subject"]),
                "verified": bool(identity["verified"]),
                "bound_at": identity["created_at"],
            }
            for identity in identities
            if identity["provider"] in SOCIAL_PROVIDERS
        ]
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
            "social_identities": social_identities,
            "registration_source": registration["source"] if registration is not None else "legacy",
            "used_referral_code": registration["referral_code"] if registration is not None else None,
            "referrer": (
                {
                    "user_id": registration["referrer_user_id"],
                    "display_name": registration["referrer_name"] or "",
                }
                if registration is not None and registration["referrer_user_id"]
                else None
            ),
            "referral": (
                {
                    "code": referral["code"],
                    "enabled": bool(referral["enabled"]),
                    "successful_invites": int(referral["used_count"]),
                    "share_path": f"/app/?ref={referral['code']}",
                }
                if referral is not None
                else None
            ),
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

    @staticmethod
    def _ensure_growth_columns(conn: sqlite3.Connection) -> None:
        columns = {
            str(row["name"])
            for row in conn.execute("PRAGMA table_info(growth_private_channels)").fetchall()
        }
        if "manager_card_image_url" not in columns:
            conn.execute(
                "ALTER TABLE growth_private_channels ADD COLUMN manager_card_image_url TEXT NOT NULL DEFAULT ''"
            )

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
    def _ensure_identity_provider_constraint(conn: sqlite3.Connection) -> None:
        row = conn.execute(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'account_identities'"
        ).fetchone()
        definition = str(row["sql"] or "").casefold() if row is not None else ""
        if "telegram" in definition and "instagram" in definition:
            return
        conn.executescript(
            """
            DROP INDEX IF EXISTS index_account_identities_user;
            ALTER TABLE account_identities RENAME TO account_identities_legacy;
            CREATE TABLE account_identities (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                provider TEXT NOT NULL CHECK(provider IN ('email', 'phone', 'wechat', 'feishu', 'qq', 'telegram', 'whatsapp', 'instagram', 'password')),
                subject TEXT NOT NULL,
                verified INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                UNIQUE(provider, subject),
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            );
            INSERT INTO account_identities (id, user_id, provider, subject, verified, created_at)
            SELECT id, user_id, provider, subject, verified, created_at
            FROM account_identities_legacy;
            DROP TABLE account_identities_legacy;
            CREATE INDEX index_account_identities_user
            ON account_identities(user_id, created_at ASC);
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
        source_row = conn.execute(
            "SELECT source FROM account_registration_sources WHERE user_id = ?",
            (user_id,),
        ).fetchone()
        binding_source = str(source_row["source"] if source_row is not None else "register")[:64]
        conn.execute(
            "INSERT INTO growth_referral_bindings (id, referrer_user_id, referred_user_id, code, source, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            (binding_id, inviter["user_id"], user_id, clean_referral, binding_source, now),
        )
        conn.execute("UPDATE growth_referral_codes SET used_count = used_count + 1 WHERE user_id = ?", (inviter["user_id"],))
        # 注册绑定即记账，后续可由首次有效使用把 rewarded_at 标记为完成。
        self._grant_growth_reward(conn, inviter["user_id"], "referral", binding_id, "points", REFERRAL_REWARD_POINTS, f"referral:{binding_id}:inviter", now)
        self._grant_growth_reward(conn, user_id, "referral", binding_id, "points", REFERRAL_REWARD_POINTS, f"referral:{binding_id}:invitee", now)
        conn.execute("UPDATE growth_referral_bindings SET rewarded_at = ?, qualified_at = ? WHERE id = ?", (now, now, binding_id))

    def _record_registration_source(
        self,
        conn: sqlite3.Connection,
        user_id: str,
        source: str,
        referral_code: str | None,
        now: int,
        metadata: Mapping[str, object] | None = None,
    ) -> None:
        clean_referral = (referral_code or "").strip().upper().replace(" ", "")
        referrer = None
        if clean_referral:
            row = conn.execute(
                "SELECT user_id FROM growth_referral_codes WHERE code = ?",
                (clean_referral,),
            ).fetchone()
            referrer = str(row["user_id"]) if row is not None else None
        conn.execute(
            """
            INSERT INTO account_registration_sources (
                user_id, source, referral_code, referrer_user_id, metadata_json, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(user_id) DO NOTHING
            """,
            (
                user_id,
                source.strip().lower()[:64] or "unknown",
                clean_referral or None,
                referrer,
                json.dumps(dict(metadata or {}), ensure_ascii=False, separators=(",", ":")),
                now,
            ),
        )

    @staticmethod
    def _record_social_audit(
        conn: sqlite3.Connection,
        provider: str,
        event: str,
        user_id: str | None,
        success: bool,
        detail: str,
        now: int,
    ) -> None:
        conn.execute(
            """
            INSERT INTO social_auth_audit (
                id, provider, event, user_id, success, detail, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                str(uuid.uuid4()), provider[:32], event[:64], user_id,
                1 if success else 0, detail.strip()[:500], now,
            ),
        )

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
        winner_names = []
        for winner in winners:
            user = conn.execute(
                "SELECT COALESCE(NULLIF(display_name, ''), username) AS display_name FROM users WHERE id = ?",
                (winner["user_id"],),
            ).fetchone()
            display_name = str(user["display_name"]) if user else "获奖用户"
            winner_names.append(f"第{winner['rank']}名 {display_name}")
            conn.execute(
                """
                INSERT OR IGNORE INTO growth_system_messages (
                    id, user_id, message_type, title, body, campaign_id,
                    action_path, created_at
                ) VALUES (?, ?, 'campaign_reward', ?, ?, ?, ?, ?)
                """,
                (
                    f"campaign:{campaign_id}:winner:{winner['user_id']}",
                    winner["user_id"],
                    f"{campaign['title']}奖励已到账",
                    f"恭喜获得第{winner['rank']}名，{winner['quantity']} 积分已发放至账户。",
                    campaign_id,
                    f"/growth/campaigns/{campaign_id}",
                    now,
                ),
            )
        announcement_body = (
            "活动已完成结算。" + ("获奖名单：" + "、".join(winner_names) if winner_names else "本期暂无获奖名单。")
        )
        conn.execute(
            """
            INSERT OR IGNORE INTO growth_system_messages (
                id, user_id, message_type, title, body, campaign_id,
                action_path, created_at
            ) VALUES (?, NULL, 'campaign_announcement', ?, ?, ?, ?, ?)
            """,
            (
                f"campaign:{campaign_id}:announcement",
                f"{campaign['title']}获奖公告",
                announcement_body,
                campaign_id,
                f"/growth/campaigns/{campaign_id}",
                now,
            ),
        )
        return {"status": "settled", "campaign_id": campaign_id, "winners": winners}

    def _campaign_rows(self, conn: sqlite3.Connection, now: int) -> list[dict]:
        rows = conn.execute(
            """
            SELECT id, title, campaign_type, summary, rules_json,
                   reward_pool_json, starts_at, ends_at, status
            FROM growth_campaigns
            WHERE status IN ('active', 'running') AND ends_at >= ?
            ORDER BY starts_at ASC LIMIT 20
            """,
            (now,),
        ).fetchall()
        result = []
        for row in rows:
            payload = dict(row)
            payload["rules"] = json.loads(payload.pop("rules_json") or "{}")
            payload["reward_pool"] = json.loads(payload.pop("reward_pool_json") or "{}")
            result.append(payload)
        return result

    @staticmethod
    def _validated_campaign_config(payload: dict) -> dict:
        title = str(payload.get("title", "")).strip()
        campaign_type = str(payload.get("campaign_type", "")).strip().lower()
        summary = str(payload.get("summary", "")).strip()
        status = str(payload.get("status", "draft")).strip().lower()
        rules = payload.get("rules") if isinstance(payload.get("rules"), dict) else {}
        reward_pool = (
            payload.get("reward_pool")
            if isinstance(payload.get("reward_pool"), dict)
            else {}
        )
        try:
            starts_at = int(payload.get("starts_at", 0))
            ends_at = int(payload.get("ends_at", 0))
        except (TypeError, ValueError) as exc:
            raise AccountError("活动时间配置无效") from exc
        if not title or len(title) > 120:
            raise AccountError("活动标题长度需为 1 至 120 个字符")
        if campaign_type not in {"ranking", "quiz", "contest", "checkin", "draw"}:
            raise AccountError("活动类型无效")
        if len(summary) > 500:
            raise AccountError("活动说明不能超过 500 个字符")
        if status not in {"draft", "active", "running", "paused"}:
            raise AccountError("活动状态无效")
        if starts_at <= 0 or ends_at <= starts_at:
            raise AccountError("活动结束时间必须晚于开始时间")
        try:
            encoded_rules = json.dumps(rules, ensure_ascii=False)
            encoded_rewards = json.dumps(reward_pool, ensure_ascii=False)
        except (TypeError, ValueError) as exc:
            raise AccountError("活动规则必须为可序列化对象") from exc
        if len(encoded_rules) > 20_000 or len(encoded_rewards) > 10_000:
            raise AccountError("活动规则或奖励池内容过大")
        return {
            "title": title,
            "campaign_type": campaign_type,
            "summary": summary,
            "rules": rules,
            "reward_pool": reward_pool,
            "starts_at": starts_at,
            "ends_at": ends_at,
            "status": status,
        }

    def _private_channel(
        self,
        conn: sqlite3.Connection,
        channel_id: str | None = None,
        *,
        user_id: str | None = None,
        include_qr: bool = False,
    ) -> dict | None:
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
        application = None
        if user_id:
            application_row = conn.execute(
                "SELECT id FROM growth_channel_applications WHERE channel_id = ? AND user_id = ?",
                (payload["id"], user_id),
            ).fetchone()
            if application_row:
                application = self._channel_application_payload(conn, application_row["id"])
        payload["application"] = application
        payload["requires_application"] = True
        if (user_id and (application is None or application.get("status") != "approved")) or (
            not include_qr
        ):
            payload["qr_image_url"] = ""
            payload["join_url"] = ""
            payload["short_url"] = ""
        return payload

    @staticmethod
    def _channel_application_payload(
        conn: sqlite3.Connection,
        application_id: str,
        row: sqlite3.Row | None = None,
    ) -> dict:
        row = row or conn.execute(
            """
            SELECT a.*, c.name AS channel_name,
                   COALESCE(NULLIF(u.display_name, ''), u.username) AS user_name,
                   u.username
            FROM growth_channel_applications a
            JOIN growth_private_channels c ON c.id = a.channel_id
            JOIN users u ON u.id = a.user_id
            WHERE a.id = ?
            """,
            (application_id,),
        ).fetchone()
        if row is None:
            return {}
        payload = dict(row)
        try:
            payload["answers"] = json.loads(payload.pop("answers_json") or "{}")
        except (TypeError, json.JSONDecodeError):
            payload["answers"] = {}
        return payload

    @staticmethod
    def _ensure_growth_defaults(conn: sqlite3.Connection) -> None:
        now = int(time.time())
        conn.execute(
            "INSERT INTO growth_private_channels (id, name, qr_image_url, manager_card_image_url, slogan, reward_type, reward_payload_json, updated_at) VALUES ('default-welfare-group', '智悟本福利7群', '/api/growth/private-channel/default-qr', '/api/growth/private-channel/default-manager-card', '添加群主后提交申请，审核通过即可加入福利群', 'points', '{\"quantity\":200}', ?) ON CONFLICT(id) DO NOTHING",
            (now,),
        )
        conn.execute(
            "UPDATE growth_private_channels SET manager_card_image_url = '/api/growth/private-channel/default-manager-card' WHERE id = 'default-welfare-group' AND (manager_card_image_url IS NULL OR manager_card_image_url = '')"
        )
        conn.execute(
            "UPDATE growth_private_channels SET name = '智悟本福利7群', updated_at = ? WHERE id = 'default-welfare-group' AND name = '智悟本福利群'",
            (now,),
        )
        conn.execute(
            "UPDATE growth_private_channels SET reward_payload_json = '{\"quantity\":200}', updated_at = ? WHERE id = 'default-welfare-group' AND reward_payload_json = '{\"quantity\":50}'",
            (now,),
        )
        samples = (
            (
                "invitation-ranking", "邀请好友得积分排行赛", "ranking",
                "邀请好友注册，双方各得 300 积分，前 20 名额外获得兑换码。",
                {"checkin_reward": 30}, {"ranks": {"1": 2000, "2": 1200, "3": 600}},
                {"checkin_reward": 10}, {"ranks": {"1": 1000, "2": 600, "3": 300}},
            ),
            (
                "daily-quiz", "每日答题兑好礼", "quiz",
                "每天完成答题，答对即可获得积分并参与周榜。",
                {"checkin_reward": 30, "answer_reward": 50, "questions": [{"key": "q1", "question": "浙江省省会是哪里？", "options": ["杭州", "宁波", "温州"], "answer": "杭州"}, {"key": "q2", "question": "每天完成记录后最重要的动作是什么？", "options": ["复盘", "删除", "跳过"], "answer": "复盘"}]},
                {"ranks": {"1": 1000, "2": 600, "3": 300}},
                {"checkin_reward": 10, "answer_reward": 20, "questions": [{"key": "q1", "question": "浙江省省会是哪里？", "options": ["杭州", "宁波", "温州"], "answer": "杭州"}, {"key": "q2", "question": "每天完成记录后最重要的动作是什么？", "options": ["复盘", "删除", "跳过"], "answer": "复盘"}]},
                {"ranks": {"1": 500, "2": 300, "3": 100}},
            ),
            (
                "zhejiang-study", "浙江研学创作挑战", "contest",
                "记录一次浙江本地研学见闻，优秀作品可获得积分奖励。",
                {"checkin_reward": 50, "draw_reward": 200, "win_probability": 2000},
                {"ranks": {"1": 1500, "2": 1000, "3": 600}},
                {"checkin_reward": 20, "draw_reward": 80, "win_probability": 2000},
                {"ranks": {"1": 800, "2": 500, "3": 300}},
            ),
        )
        for campaign_id, title, campaign_type, summary, rules, reward_pool, old_rules, old_reward_pool in samples:
            conn.execute(
                "INSERT INTO growth_campaigns (id, title, campaign_type, summary, rules_json, reward_pool_json, starts_at, ends_at, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?) ON CONFLICT(id) DO NOTHING",
                (campaign_id, title, campaign_type, summary, json.dumps(rules, ensure_ascii=False), json.dumps(reward_pool, ensure_ascii=False), now, now + 30 * 86400, now),
            )
            conn.execute(
                "UPDATE growth_campaigns SET summary = ?, rules_json = ?, reward_pool_json = ? WHERE id = ? AND rules_json = ? AND reward_pool_json = ?",
                (
                    summary,
                    json.dumps(rules, ensure_ascii=False),
                    json.dumps(reward_pool, ensure_ascii=False),
                    campaign_id,
                    json.dumps(old_rules, ensure_ascii=False),
                    json.dumps(old_reward_pool, ensure_ascii=False),
                ),
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
        if channel == "email":
            local = re.sub(r"[^A-Za-z0-9_]", "_", subject.split("@", 1)[0])[:12]
            return f"{local or 'email'}_{user_id[:8]}"
        provider = re.sub(r"[^A-Za-z0-9_]", "_", channel)[:12] or "social"
        return f"{provider}_{user_id[:8]}"

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
