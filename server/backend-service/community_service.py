#!/usr/bin/env python3
"""Owner-scoped storage and state transitions for community post snapshots."""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterator, Mapping

from community_media import CommunityMediaFormatError, sanitize_image_bytes


POST_STATUSES = frozenset({"private_draft", "published", "withdrawn"})
MODERATION_STATUSES = frozenset({"not_submitted", "pending"})
REVIEW_DECISIONS = frozenset({"approved", "rejected"})
REPORT_CATEGORIES = frozenset({"privacy", "copyright", "safety", "spam", "other"})
DEFAULT_ACTION_RATE_LIMITS: dict[str, tuple[int, int]] = {
    "interaction": (60, 60),
    "comment": (10, 60),
    "report": (10, 60 * 60),
}
MAX_METADATA_ITEMS = 50
MEDIA_MIME_TYPES = frozenset({"image/jpeg", "image/png", "image/webp"})
MEDIA_VARIANTS = frozenset({"original", "thumbnail"})
ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
PRECISE_COORDINATE_PAIR_PATTERN = re.compile(
    r"(?<![\d.])"
    r"[-+]?(?:[0-8]?\d(?:\.\d{4,})?|90(?:\.0{4,})?)"
    r"\s*[,，]\s*"
    r"[-+]?(?:1[0-7]\d(?:\.\d{4,})?|\d?\d(?:\.\d{4,})?|180(?:\.0{4,})?)"
    r"(?![\d.])"
)
COORDINATE_FIELD_PATTERN = re.compile(
    r"(?i)(?:latitude|longitude|\blat\b|\blng\b|\blon\b|纬度|经度)\s*[:=：]\s*[-+]?\d{1,3}\.\d{4,}"
)


class CommunityError(Exception):
    status_code = 400


class CommunityConflictError(CommunityError):
    status_code = 409


class CommunityNotFoundError(CommunityError):
    status_code = 404


class CommunityPermissionError(CommunityError):
    status_code = 403


class CommunityRateLimitError(CommunityError):
    status_code = 429

    def __init__(self, retry_after_seconds: int) -> None:
        self.retry_after_seconds = max(1, retry_after_seconds)
        super().__init__(f"操作过于频繁，请在 {self.retry_after_seconds} 秒后重试")


@dataclass(frozen=True)
class CommunityDraftInput:
    client_snapshot_id: str
    journey_id: str
    journey_edition_id: str
    source_edition_version: int
    title: str
    content: str
    ai_assisted: bool
    redacted_coordinate_count: int
    privacy_reviewed: bool
    rights_confirmed: bool
    destination: str = ""
    travel_date: str = ""
    travel_days: int = 0
    stage_titles: tuple[str, ...] = ()
    tags: tuple[str, ...] = ()
    pois: tuple[str, ...] = ()


@dataclass(frozen=True)
class CommunityMediaManifestInput:
    client_media_id: str
    display_name: str
    mime_type: str
    original_bytes: int
    original_sha256: str
    thumbnail_bytes: int
    thumbnail_sha256: str


@dataclass(frozen=True)
class CommunityReportInput:
    category: str
    reason: str = ""


class CommunityService:
    def __init__(
        self,
        db_path: Path,
        *,
        title_max_length: int = 200,
        content_max_length: int = 100_000,
        media_root: Path | None = None,
        media_quota_bytes: int = 512 * 1024 * 1024,
        media_max_asset_bytes: int = 24 * 1024 * 1024,
        media_max_thumbnail_bytes: int = 2 * 1024 * 1024,
        media_max_chunk_bytes: int = 1024 * 1024,
        action_rate_limits: Mapping[str, tuple[int, int]] | None = None,
        now_ms_provider: Callable[[], int] | None = None,
    ) -> None:
        self.db_path = Path(db_path)
        self.title_max_length = max(1, title_max_length)
        self.content_max_length = max(1, content_max_length)
        self.media_root = Path(media_root) if media_root else self.db_path.parent / "community-media"
        self.media_quota_bytes = max(1, media_quota_bytes)
        self.media_max_asset_bytes = max(1, media_max_asset_bytes)
        self.media_max_thumbnail_bytes = max(1, media_max_thumbnail_bytes)
        self.media_max_chunk_bytes = max(1, media_max_chunk_bytes)
        self.action_rate_limits = {
            **DEFAULT_ACTION_RATE_LIMITS,
            **(dict(action_rate_limits) if action_rate_limits is not None else {}),
        }
        for action, (limit, window_seconds) in self.action_rate_limits.items():
            if action not in DEFAULT_ACTION_RATE_LIMITS or limit < 1 or window_seconds < 1:
                raise ValueError("community action rate limit is invalid")
        self._now_ms = now_ms_provider or (lambda: int(time.time() * 1000))

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

    def initialize(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.media_root.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS community_posts (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    client_snapshot_id TEXT NOT NULL,
                    journey_id TEXT NOT NULL,
                    journey_edition_id TEXT NOT NULL,
                    source_edition_version INTEGER NOT NULL CHECK(source_edition_version > 0),
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    ai_assisted INTEGER NOT NULL CHECK(ai_assisted IN (0, 1)),
                    redacted_coordinate_count INTEGER NOT NULL DEFAULT 0
                        CHECK(redacted_coordinate_count >= 0),
                    privacy_reviewed INTEGER NOT NULL CHECK(privacy_reviewed = 1),
                    rights_confirmed INTEGER NOT NULL CHECK(rights_confirmed = 1),
                    request_hash TEXT NOT NULL,
                    status TEXT NOT NULL
                        CHECK(status IN ('private_draft', 'published', 'withdrawn')),
                    moderation_status TEXT NOT NULL
                        CHECK(moderation_status IN ('not_submitted', 'pending')),
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    published_at INTEGER,
                    withdrawn_at INTEGER,
                    UNIQUE(user_id, client_snapshot_id),
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_posts_owner_updated
                    ON community_posts(user_id, updated_at DESC);

                CREATE INDEX IF NOT EXISTS idx_community_posts_public_published
                    ON community_posts(status, published_at DESC, id DESC);

                CREATE TABLE IF NOT EXISTS community_moderation (
                    post_id TEXT PRIMARY KEY,
                    decision TEXT NOT NULL CHECK(decision IN ('approved', 'rejected')),
                    reason TEXT NOT NULL DEFAULT '',
                    reviewed_by TEXT NOT NULL,
                    reviewed_at INTEGER NOT NULL,
                    FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_moderation_decision
                    ON community_moderation(decision, reviewed_at DESC);

                CREATE TABLE IF NOT EXISTS community_post_media (
                    id TEXT PRIMARY KEY,
                    post_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    client_media_id TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    mime_type TEXT NOT NULL CHECK(mime_type IN ('image/jpeg', 'image/png', 'image/webp')),
                    original_total_bytes INTEGER NOT NULL CHECK(original_total_bytes > 0),
                    original_sha256 TEXT NOT NULL,
                    original_received_bytes INTEGER NOT NULL DEFAULT 0
                        CHECK(original_received_bytes >= 0),
                    thumbnail_total_bytes INTEGER NOT NULL CHECK(thumbnail_total_bytes > 0),
                    thumbnail_sha256 TEXT NOT NULL,
                    thumbnail_received_bytes INTEGER NOT NULL DEFAULT 0
                        CHECK(thumbnail_received_bytes >= 0),
                    status TEXT NOT NULL CHECK(status IN ('pending', 'uploading', 'ready')),
                    original_storage_key TEXT,
                    thumbnail_storage_key TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(user_id, post_id, client_media_id),
                    FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_post_media_post
                    ON community_post_media(post_id, created_at ASC);
                CREATE INDEX IF NOT EXISTS idx_community_post_media_owner
                    ON community_post_media(user_id, status, updated_at DESC);

                CREATE TABLE IF NOT EXISTS community_reports (
                    id TEXT PRIMARY KEY,
                    post_id TEXT NOT NULL,
                    reporter_user_id TEXT NOT NULL,
                    category TEXT NOT NULL
                        CHECK(category IN ('privacy', 'copyright', 'safety', 'spam', 'other')),
                    reason TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'open'
                        CHECK(status IN ('open', 'resolved')),
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(post_id, reporter_user_id),
                    FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
                    FOREIGN KEY(reporter_user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_reports_post_status
                    ON community_reports(post_id, status, created_at DESC);
                CREATE INDEX IF NOT EXISTS idx_community_reports_status_created
                    ON community_reports(status, created_at DESC, id DESC);

                CREATE TABLE IF NOT EXISTS community_post_likes (
                    post_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY(post_id, user_id),
                    FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_post_likes_post
                    ON community_post_likes(post_id, created_at DESC);

                CREATE TABLE IF NOT EXISTS community_post_bookmarks (
                    post_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY(post_id, user_id),
                    FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_post_bookmarks_user
                    ON community_post_bookmarks(user_id, created_at DESC);

                CREATE TABLE IF NOT EXISTS community_comments (
                    id TEXT PRIMARY KEY,
                    post_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'visible'
                        CHECK(status IN ('visible', 'deleted')),
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_comments_post
                    ON community_comments(post_id, status, created_at DESC, id DESC);
                CREATE INDEX IF NOT EXISTS idx_community_comments_user
                    ON community_comments(user_id, status, updated_at DESC);

                CREATE TABLE IF NOT EXISTS community_comment_reports (
                    id TEXT PRIMARY KEY,
                    comment_id TEXT NOT NULL,
                    reporter_user_id TEXT NOT NULL,
                    category TEXT NOT NULL
                        CHECK(category IN ('privacy', 'copyright', 'safety', 'spam', 'other')),
                    reason TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'open'
                        CHECK(status IN ('open', 'resolved')),
                    resolution TEXT NOT NULL DEFAULT ''
                        CHECK(resolution IN ('', 'keep', 'delete', 'author_deleted')),
                    reviewed_by TEXT,
                    reviewed_at INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(comment_id, reporter_user_id),
                    FOREIGN KEY(comment_id) REFERENCES community_comments(id) ON DELETE CASCADE,
                    FOREIGN KEY(reporter_user_id) REFERENCES users(id) ON DELETE CASCADE,
                    FOREIGN KEY(reviewed_by) REFERENCES users(id) ON DELETE SET NULL
                );

                CREATE INDEX IF NOT EXISTS idx_community_comment_reports_status
                    ON community_comment_reports(status, created_at DESC, id DESC);
                CREATE INDEX IF NOT EXISTS idx_community_comment_reports_comment
                    ON community_comment_reports(comment_id, status, created_at DESC);

                CREATE TABLE IF NOT EXISTS community_action_rate_windows (
                    user_id TEXT NOT NULL,
                    action TEXT NOT NULL
                        CHECK(action IN ('interaction', 'comment', 'report')),
                    window_started_at INTEGER NOT NULL,
                    window_seconds INTEGER NOT NULL CHECK(window_seconds > 0),
                    action_count INTEGER NOT NULL CHECK(action_count > 0),
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(user_id, action),
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_action_rate_windows_updated
                    ON community_action_rate_windows(updated_at DESC);

                CREATE TABLE IF NOT EXISTS community_activity_metrics (
                    bucket_started_at INTEGER NOT NULL,
                    action TEXT NOT NULL
                        CHECK(action IN ('interaction', 'comment', 'report')),
                    outcome TEXT NOT NULL CHECK(outcome IN ('allowed', 'limited')),
                    attempt_count INTEGER NOT NULL CHECK(attempt_count > 0),
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(bucket_started_at, action, outcome)
                );

                CREATE INDEX IF NOT EXISTS idx_community_activity_metrics_bucket
                    ON community_activity_metrics(bucket_started_at DESC);

                CREATE TABLE IF NOT EXISTS community_post_index (
                    post_id TEXT PRIMARY KEY,
                    destination TEXT NOT NULL DEFAULT '',
                    travel_date TEXT NOT NULL DEFAULT '',
                    travel_days INTEGER NOT NULL DEFAULT 0
                        CHECK(travel_days >= 0 AND travel_days <= 31),
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_post_index_destination
                    ON community_post_index(destination, updated_at DESC);
                CREATE INDEX IF NOT EXISTS idx_community_post_index_travel
                    ON community_post_index(travel_date, travel_days, updated_at DESC);

                CREATE TABLE IF NOT EXISTS community_post_stages (
                    post_id TEXT NOT NULL,
                    sequence_number INTEGER NOT NULL CHECK(sequence_number > 0),
                    title TEXT NOT NULL,
                    PRIMARY KEY(post_id, sequence_number),
                    FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_post_stages_title
                    ON community_post_stages(title, post_id);

                CREATE TABLE IF NOT EXISTS community_post_tags (
                    post_id TEXT NOT NULL,
                    tag TEXT NOT NULL,
                    PRIMARY KEY(post_id, tag),
                    FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_post_tags_tag
                    ON community_post_tags(tag, post_id);

                CREATE TABLE IF NOT EXISTS community_post_pois (
                    post_id TEXT NOT NULL,
                    poi_name TEXT NOT NULL,
                    PRIMARY KEY(post_id, poi_name),
                    FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_community_post_pois_name
                    ON community_post_pois(poi_name, post_id);
                """
            )
            self._ensure_comment_report_columns(conn)

    @staticmethod
    def _ensure_comment_report_columns(conn: sqlite3.Connection) -> None:
        columns = {
            row[1]
            for row in conn.execute("PRAGMA table_info(community_comment_reports)").fetchall()
        }
        if "resolution" not in columns:
            conn.execute(
                "ALTER TABLE community_comment_reports ADD COLUMN resolution TEXT NOT NULL DEFAULT ''"
            )
        if "reviewed_by" not in columns:
            conn.execute(
                "ALTER TABLE community_comment_reports ADD COLUMN reviewed_by TEXT"
            )
        if "reviewed_at" not in columns:
            conn.execute(
                "ALTER TABLE community_comment_reports ADD COLUMN reviewed_at INTEGER"
            )

    def _consume_action_attempt(self, user_id: str, action: str) -> None:
        limit, window_seconds = self.action_rate_limits[action]
        now = self._now_ms()
        window_ms = window_seconds * 1000
        window_started_at = now - (now % window_ms)
        metric_bucket = now - (now % (60 * 60 * 1000))
        action_count = 1
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            current = conn.execute(
                "SELECT window_started_at, window_seconds, action_count "
                "FROM community_action_rate_windows WHERE user_id = ? AND action = ?",
                (user_id, action),
            ).fetchone()
            if (
                current is not None
                and current["window_started_at"] == window_started_at
                and current["window_seconds"] == window_seconds
            ):
                action_count = current["action_count"] + 1
                conn.execute(
                    "UPDATE community_action_rate_windows "
                    "SET action_count = ?, updated_at = ? WHERE user_id = ? AND action = ?",
                    (action_count, now, user_id, action),
                )
            else:
                conn.execute(
                    """
                    INSERT INTO community_action_rate_windows(
                        user_id, action, window_started_at, window_seconds, action_count, updated_at
                    ) VALUES (?, ?, ?, ?, 1, ?)
                    ON CONFLICT(user_id, action) DO UPDATE SET
                        window_started_at = excluded.window_started_at,
                        window_seconds = excluded.window_seconds,
                        action_count = 1,
                        updated_at = excluded.updated_at
                    """,
                    (user_id, action, window_started_at, window_seconds, now),
                )
            outcome = "limited" if action_count > limit else "allowed"
            conn.execute(
                """
                INSERT INTO community_activity_metrics(
                    bucket_started_at, action, outcome, attempt_count, updated_at
                ) VALUES (?, ?, ?, 1, ?)
                ON CONFLICT(bucket_started_at, action, outcome) DO UPDATE SET
                    attempt_count = attempt_count + 1,
                    updated_at = excluded.updated_at
                """,
                (metric_bucket, action, outcome, now),
            )
        if action_count > limit:
            retry_after_seconds = max(
                1,
                (window_started_at + window_ms - now + 999) // 1000,
            )
            raise CommunityRateLimitError(retry_after_seconds)

    def create_private_draft(
        self,
        user_id: str,
        snapshot: CommunityDraftInput,
    ) -> tuple[dict, bool]:
        clean_user_id = self._validated_id(user_id, "user_id")
        normalized = self._validated_snapshot(snapshot)
        request_hash = self._request_hash(normalized)
        now = int(time.time() * 1000)
        post_id = uuid.uuid4().hex
        values = {
            "id": post_id,
            "user_id": clean_user_id,
            **normalized,
            "request_hash": request_hash,
            "status": "private_draft",
            "moderation_status": "not_submitted",
            "created_at": now,
            "updated_at": now,
        }
        with self._connect() as conn:
            try:
                conn.execute(
                    """
                    INSERT INTO community_posts (
                        id, user_id, client_snapshot_id, journey_id, journey_edition_id,
                        source_edition_version, title, content, ai_assisted,
                        redacted_coordinate_count, privacy_reviewed, rights_confirmed,
                        request_hash, status, moderation_status, created_at, updated_at
                    ) VALUES (
                        :id, :user_id, :client_snapshot_id, :journey_id, :journey_edition_id,
                        :source_edition_version, :title, :content, :ai_assisted,
                        :redacted_coordinate_count, :privacy_reviewed, :rights_confirmed,
                        :request_hash, :status, :moderation_status, :created_at, :updated_at
                    )
                    """,
                    values,
                )
            except sqlite3.IntegrityError as exc:
                existing = conn.execute(
                    """
                    SELECT * FROM community_posts
                    WHERE user_id = ? AND client_snapshot_id = ?
                    """,
                    (clean_user_id, normalized["client_snapshot_id"]),
                ).fetchone()
                if existing is None:
                    raise CommunityConflictError("社区草稿无法保存") from exc
                if existing["request_hash"] != request_hash:
                    raise CommunityConflictError(
                        "同一客户端快照 ID 已对应不同内容"
                    ) from exc
                return self._post_payload(existing), False
            row = conn.execute(
                "SELECT * FROM community_posts WHERE id = ?",
                (post_id,),
            ).fetchone()
            self._replace_post_index(conn, post_id, normalized, now)
            return self._post_payload(row), True

    def get_post(self, user_id: str, post_id: str) -> dict:
        with self._connect() as conn:
            post = self._post_payload(self._owned_post(conn, user_id, post_id))
            post["media"] = self._owner_media_payloads(conn, post["id"])
            return post

    def list_owner_posts(
        self,
        user_id: str,
        *,
        cursor: str | None = None,
        limit: int = 20,
    ) -> dict:
        clean_user_id = self._validated_id(user_id, "user_id")
        normalized_limit = self._normalized_limit(limit)
        cursor_updated_at, cursor_id = self._decode_cursor(cursor)
        query = """
            SELECT p.*, m.decision AS review_decision, m.reason AS review_reason,
                   m.reviewed_at AS review_reviewed_at
            FROM community_posts p
            LEFT JOIN community_moderation m ON m.post_id = p.id
            WHERE p.user_id = ?
        """
        params: list[object] = [clean_user_id]
        if cursor_updated_at is not None and cursor_id is not None:
            query += """
                AND (p.updated_at < ? OR (p.updated_at = ? AND p.id < ?))
            """
            params.extend([cursor_updated_at, cursor_updated_at, cursor_id])
        query += "ORDER BY p.updated_at DESC, p.id DESC LIMIT ?"
        params.append(normalized_limit + 1)
        with self._connect() as conn:
            rows = conn.execute(query, params).fetchall()
            has_more = len(rows) > normalized_limit
            visible_rows = rows[:normalized_limit]
            return {
                "items": [
                    self._owner_post_payload(conn, row)
                    for row in visible_rows
                ],
                "next_cursor": self._encode_cursor(visible_rows[-1], "updated_at")
                if has_more and visible_rows
                else None,
            }

    def publish(self, user_id: str, post_id: str) -> dict:
        now = int(time.time() * 1000)
        with self._connect() as conn:
            row = self._owned_post(conn, user_id, post_id)
            if row["status"] == "withdrawn":
                raise CommunityConflictError("已撤回的社区内容不能重新发布")
            if row["status"] == "private_draft":
                conn.execute(
                    """
                    UPDATE community_posts
                    SET status = 'published', moderation_status = 'pending',
                        published_at = ?, updated_at = ?
                    WHERE id = ? AND user_id = ? AND status = 'private_draft'
                    """,
                    (now, now, row["id"], row["user_id"]),
                )
                row = self._owned_post(conn, user_id, post_id)
            return self._post_payload(row)

    def withdraw(self, user_id: str, post_id: str) -> dict:
        now = int(time.time() * 1000)
        with self._connect() as conn:
            row = self._owned_post(conn, user_id, post_id)
            if row["status"] != "withdrawn":
                conn.execute(
                    """
                    UPDATE community_posts
                    SET status = 'withdrawn', withdrawn_at = ?, updated_at = ?
                    WHERE id = ? AND user_id = ? AND status != 'withdrawn'
                    """,
                    (now, now, row["id"], row["user_id"]),
                )
                row = self._owned_post(conn, user_id, post_id)
            return self._post_payload(row)

    def create_media_manifest(
        self,
        user_id: str,
        post_id: str,
        media: CommunityMediaManifestInput,
    ) -> tuple[dict, bool]:
        clean_user_id = self._validated_id(user_id, "user_id")
        clean_post_id = self._validated_id(post_id, "post_id")
        normalized = self._validated_media_manifest(media)
        now = int(time.time() * 1000)
        media_id = uuid.uuid4().hex
        with self._connect() as conn:
            post = self._owned_post(conn, clean_user_id, clean_post_id)
            if post["status"] == "withdrawn":
                raise CommunityConflictError("已撤回内容不能新增媒体")
            existing = conn.execute(
                """
                SELECT * FROM community_post_media
                WHERE user_id = ? AND post_id = ? AND client_media_id = ?
                """,
                (clean_user_id, clean_post_id, normalized["client_media_id"]),
            ).fetchone()
            if existing is not None:
                if any(existing[key] != value for key, value in normalized.items() if key != "display_name"):
                    raise CommunityConflictError("同一媒体清单 ID 已对应不同文件")
                return self._media_payload(existing), False
            used = conn.execute(
                """
                SELECT COALESCE(SUM(original_total_bytes + thumbnail_total_bytes), 0) AS total
                FROM community_post_media WHERE user_id = ?
                """,
                (clean_user_id,),
            ).fetchone()["total"]
            requested = normalized["original_total_bytes"] + normalized["thumbnail_total_bytes"]
            if int(used) + requested > self.media_quota_bytes:
                raise CommunityConflictError("社区媒体存储配额不足")
            conn.execute(
                """
                INSERT INTO community_post_media (
                    id, post_id, user_id, client_media_id, display_name, mime_type,
                    original_total_bytes, original_sha256, original_received_bytes,
                    thumbnail_total_bytes, thumbnail_sha256, thumbnail_received_bytes,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 0, 'pending', ?, ?)
                """,
                (
                    media_id,
                    clean_post_id,
                    clean_user_id,
                    normalized["client_media_id"],
                    normalized["display_name"],
                    normalized["mime_type"],
                    normalized["original_total_bytes"],
                    normalized["original_sha256"],
                    normalized["thumbnail_total_bytes"],
                    normalized["thumbnail_sha256"],
                    now,
                    now,
                ),
            )
            row = conn.execute(
                "SELECT * FROM community_post_media WHERE id = ?",
                (media_id,),
            ).fetchone()
            return self._media_payload(row), True

    def list_owner_media(self, user_id: str, post_id: str) -> dict:
        with self._connect() as conn:
            post = self._owned_post(conn, user_id, post_id)
            return {
                "items": self._owner_media_payloads(conn, post["id"]),
                "quota": self._media_quota_payload(conn, post["user_id"]),
            }

    def append_media_chunk(
        self,
        user_id: str,
        post_id: str,
        media_id: str,
        variant: str,
        *,
        start: int,
        end: int,
        total: int,
        data: bytes,
        chunk_sha256: str,
    ) -> dict:
        clean_variant = variant.strip().lower()
        if clean_variant not in MEDIA_VARIANTS:
            raise CommunityError("媒体版本无效")
        if len(data) == 0 or len(data) > self.media_max_chunk_bytes:
            raise CommunityError("上传分片大小无效")
        if start < 0 or end < start or end - start + 1 != len(data):
            raise CommunityError("上传范围无效")
        if hashlib.sha256(data).hexdigest() != self._validated_sha256(chunk_sha256, "chunk_sha256"):
            raise CommunityError("上传分片校验失败")
        clean_media_id = self._validated_id(media_id, "media_id")
        with self._connect() as conn:
            self._owned_post(conn, user_id, post_id)
            row = self._owned_media(conn, user_id, post_id, clean_media_id)
            total_field = f"{clean_variant}_total_bytes"
            received_field = f"{clean_variant}_received_bytes"
            expected_total = int(row[total_field])
            received = int(row[received_field])
            if total != expected_total:
                raise CommunityConflictError("上传总大小与资源清单不一致")
            if start != received:
                raise CommunityConflictError(f"请从偏移量 {received} 继续上传")
            if end >= total:
                raise CommunityError("上传范围超出资源大小")
            partial_path = self._media_path(row, clean_variant, suffix=".part")
            partial_path.parent.mkdir(parents=True, exist_ok=True)
            with partial_path.open("ab") as stream:
                stream.write(data)
            updated_received = received + len(data)
            now = int(time.time() * 1000)
            storage_key: str | None = None
            if updated_received == expected_total:
                complete_data = partial_path.read_bytes()
                expected_hash = str(row[f"{clean_variant}_sha256"])
                if hashlib.sha256(complete_data).hexdigest() != expected_hash:
                    partial_path.unlink(missing_ok=True)
                    raise CommunityConflictError("完整媒体校验失败，请重新上传")
                try:
                    sanitized = sanitize_image_bytes(complete_data, str(row["mime_type"]))
                except CommunityMediaFormatError as exc:
                    partial_path.unlink(missing_ok=True)
                    raise CommunityError(str(exc)) from exc
                final_path = self._media_path(row, clean_variant)
                final_path.write_bytes(sanitized)
                partial_path.unlink(missing_ok=True)
                storage_key = str(final_path.relative_to(self.media_root)).replace("\\", "/")
            original_done = (
                clean_variant == "original" and updated_received == expected_total
            ) or (
                clean_variant != "original"
                and int(row["original_received_bytes"]) == int(row["original_total_bytes"])
            )
            thumbnail_done = (
                clean_variant == "thumbnail" and updated_received == expected_total
            ) or (
                clean_variant != "thumbnail"
                and int(row["thumbnail_received_bytes"]) == int(row["thumbnail_total_bytes"])
            )
            status = "ready" if original_done and thumbnail_done else "uploading"
            storage_field = f"{clean_variant}_storage_key"
            conn.execute(
                f"""
                UPDATE community_post_media
                SET {received_field} = ?, {storage_field} = COALESCE(?, {storage_field}),
                    status = ?, updated_at = ?
                WHERE id = ? AND user_id = ? AND post_id = ?
                """,
                (updated_received, storage_key, status, now, clean_media_id, user_id, post_id),
            )
            updated = conn.execute(
                "SELECT * FROM community_post_media WHERE id = ?",
                (clean_media_id,),
            ).fetchone()
            return self._media_payload(updated)

    def public_media_file(self, media_id: str, variant: str) -> tuple[Path, str]:
        clean_media_id = self._validated_id(media_id, "media_id")
        clean_variant = variant.strip().lower()
        if clean_variant not in MEDIA_VARIANTS:
            raise CommunityNotFoundError("媒体资源不存在")
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT m.* FROM community_post_media m
                JOIN community_posts p ON p.id = m.post_id
                JOIN community_moderation r ON r.post_id = p.id
                WHERE m.id = ? AND m.status = 'ready' AND p.status = 'published'
                    AND r.decision = 'approved'
                """,
                (clean_media_id,),
            ).fetchone()
            if row is None:
                raise CommunityNotFoundError("媒体资源不存在或暂不可查看")
            key = row[f"{clean_variant}_storage_key"]
            if not key:
                raise CommunityNotFoundError("媒体资源不存在或暂不可查看")
            path = self.media_root / key
            if not path.is_file():
                raise CommunityNotFoundError("媒体资源暂不可用")
            return path, str(row["mime_type"])

    def media_quota(self, user_id: str) -> dict:
        with self._connect() as conn:
            return self._media_quota_payload(conn, self._validated_id(user_id, "user_id"))

    def review_post(
        self,
        post_id: str,
        *,
        decision: str,
        reason: str,
        reviewed_by: str,
    ) -> dict:
        clean_post_id = self._validated_id(post_id, "post_id")
        clean_reviewer_id = self._validated_id(reviewed_by, "reviewed_by")
        clean_decision = decision.strip().lower()
        if clean_decision not in REVIEW_DECISIONS:
            raise CommunityError("审核结论无效")
        clean_reason = reason.strip()
        if len(clean_reason) > 500:
            raise CommunityError("审核说明不能超过 500 个字符")
        if clean_decision == "rejected" and not clean_reason:
            raise CommunityError("拒绝发布时必须填写审核说明")
        now = int(time.time() * 1000)
        with self._connect() as conn:
            post = conn.execute(
                "SELECT * FROM community_posts WHERE id = ?",
                (clean_post_id,),
            ).fetchone()
            if post is None:
                raise CommunityNotFoundError("社区内容不存在")
            if post["status"] != "published":
                raise CommunityConflictError("只有已发布内容可以审核")
            incomplete_media = conn.execute(
                """
                SELECT 1 FROM community_post_media
                WHERE post_id = ? AND status != 'ready' LIMIT 1
                """,
                (clean_post_id,),
            ).fetchone()
            if incomplete_media is not None:
                raise CommunityConflictError("图片资源尚未完成同步，暂不能审核")
            conn.execute(
                """
                INSERT INTO community_moderation (
                    post_id, decision, reason, reviewed_by, reviewed_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(post_id) DO UPDATE SET
                    decision = excluded.decision,
                    reason = excluded.reason,
                    reviewed_by = excluded.reviewed_by,
                    reviewed_at = excluded.reviewed_at
                """,
                (clean_post_id, clean_decision, clean_reason, clean_reviewer_id, now),
            )
            conn.execute(
                """
                UPDATE community_reports
                SET status = 'resolved', updated_at = ?
                WHERE post_id = ? AND status = 'open'
                """,
                (now, clean_post_id),
            )
            reviewed = conn.execute(
                """
                SELECT p.*, m.decision AS review_decision, m.reason AS review_reason,
                       m.reviewed_at AS review_reviewed_at
                FROM community_posts p
                JOIN community_moderation m ON m.post_id = p.id
                WHERE p.id = ?
                """,
                (clean_post_id,),
            ).fetchone()
            return self._post_payload(reviewed)

    def report_post(
        self,
        reporter_user_id: str,
        post_id: str,
        report: CommunityReportInput,
    ) -> tuple[dict, bool]:
        clean_reporter_id = self._validated_id(reporter_user_id, "reporter_user_id")
        clean_post_id = self._validated_id(post_id, "post_id")
        category, reason = self._validated_report(report)
        self._consume_action_attempt(clean_reporter_id, "report")
        now = int(time.time() * 1000)
        with self._connect() as conn:
            post = conn.execute(
                """
                SELECT p.id, p.user_id, p.status, m.decision AS review_decision
                FROM community_posts p
                LEFT JOIN community_moderation m ON m.post_id = p.id
                WHERE p.id = ?
                """,
                (clean_post_id,),
            ).fetchone()
            if post is None or post["status"] != "published" or post["review_decision"] != "approved":
                raise CommunityNotFoundError("社区内容不存在或暂不可举报")
            if post["user_id"] == clean_reporter_id:
                raise CommunityPermissionError("不能举报自己的社区内容")
            existing = conn.execute(
                """
                SELECT * FROM community_reports
                WHERE post_id = ? AND reporter_user_id = ?
                """,
                (clean_post_id, clean_reporter_id),
            ).fetchone()
            if existing is not None:
                return self._report_payload(existing), False
            report_id = uuid.uuid4().hex
            conn.execute(
                """
                INSERT INTO community_reports (
                    id, post_id, reporter_user_id, category, reason, status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'open', ?, ?)
                """,
                (report_id, clean_post_id, clean_reporter_id, category, reason, now, now),
            )
            created = conn.execute(
                "SELECT * FROM community_reports WHERE id = ?",
                (report_id,),
            ).fetchone()
            return self._report_payload(created), True

    def toggle_like(self, user_id: str, post_id: str) -> dict:
        clean_user_id = self._validated_id(user_id, "user_id")
        clean_post_id = self._validated_id(post_id, "post_id")
        self._consume_action_attempt(clean_user_id, "interaction")
        with self._connect() as conn:
            self._approved_post(conn, clean_post_id)
            existing = conn.execute(
                "SELECT 1 FROM community_post_likes WHERE post_id = ? AND user_id = ?",
                (clean_post_id, clean_user_id),
            ).fetchone()
            if existing is None:
                conn.execute(
                    "INSERT INTO community_post_likes(post_id, user_id, created_at) VALUES (?, ?, ?)",
                    (clean_post_id, clean_user_id, int(time.time() * 1000)),
                )
                liked = True
            else:
                conn.execute(
                    "DELETE FROM community_post_likes WHERE post_id = ? AND user_id = ?",
                    (clean_post_id, clean_user_id),
                )
                liked = False
            return self._interaction_payload(conn, clean_post_id, clean_user_id) | {
                "post_id": clean_post_id,
                "liked": liked,
            }

    def toggle_bookmark(self, user_id: str, post_id: str) -> dict:
        clean_user_id = self._validated_id(user_id, "user_id")
        clean_post_id = self._validated_id(post_id, "post_id")
        self._consume_action_attempt(clean_user_id, "interaction")
        with self._connect() as conn:
            self._approved_post(conn, clean_post_id)
            existing = conn.execute(
                "SELECT 1 FROM community_post_bookmarks WHERE post_id = ? AND user_id = ?",
                (clean_post_id, clean_user_id),
            ).fetchone()
            if existing is None:
                conn.execute(
                    "INSERT INTO community_post_bookmarks(post_id, user_id, created_at) VALUES (?, ?, ?)",
                    (clean_post_id, clean_user_id, int(time.time() * 1000)),
                )
                bookmarked = True
            else:
                conn.execute(
                    "DELETE FROM community_post_bookmarks WHERE post_id = ? AND user_id = ?",
                    (clean_post_id, clean_user_id),
                )
                bookmarked = False
            return self._interaction_payload(conn, clean_post_id, clean_user_id) | {
                "post_id": clean_post_id,
                "bookmarked": bookmarked,
            }

    def get_interactions(self, user_id: str, post_id: str) -> dict:
        clean_user_id = self._validated_id(user_id, "user_id")
        clean_post_id = self._validated_id(post_id, "post_id")
        with self._connect() as conn:
            self._approved_post(conn, clean_post_id)
            return self._interaction_payload(conn, clean_post_id, clean_user_id) | {
                "post_id": clean_post_id,
            }

    def create_comment(self, user_id: str, post_id: str, content: str) -> dict:
        clean_user_id = self._validated_id(user_id, "user_id")
        clean_post_id = self._validated_id(post_id, "post_id")
        clean_content = content.strip()
        if not clean_content:
            raise CommunityError("评论内容不能为空")
        if len(clean_content) > 1000:
            raise CommunityError("评论内容不能超过 1000 个字符")
        if PRECISE_COORDINATE_PAIR_PATTERN.search(clean_content) or COORDINATE_FIELD_PATTERN.search(clean_content):
            raise CommunityError("评论不能包含精确位置")
        self._consume_action_attempt(clean_user_id, "comment")
        now = int(time.time() * 1000)
        comment_id = uuid.uuid4().hex
        with self._connect() as conn:
            self._approved_post(conn, clean_post_id)
            conn.execute(
                """
                INSERT INTO community_comments(id, post_id, user_id, content, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'visible', ?, ?)
                """,
                (comment_id, clean_post_id, clean_user_id, clean_content, now, now),
            )
            row = conn.execute(
                "SELECT * FROM community_comments WHERE id = ?",
                (comment_id,),
            ).fetchone()
            return self._comment_payload(row, can_delete=True)

    def list_comments(
        self,
        post_id: str,
        *,
        cursor: str | None = None,
        limit: int = 50,
        viewer_user_id: str | None = None,
    ) -> dict:
        clean_post_id = self._validated_id(post_id, "post_id")
        normalized_limit = self._normalized_limit(limit)
        cursor_created_at, cursor_id = self._decode_cursor(cursor)
        clean_viewer_id = (
            self._validated_id(viewer_user_id, "user_id")
            if viewer_user_id is not None
            else None
        )
        with self._connect() as conn:
            self._approved_post(conn, clean_post_id)
            query = """
                SELECT * FROM community_comments
                WHERE post_id = ? AND status = 'visible'
            """
            params: list[object] = [clean_post_id]
            if cursor_created_at is not None and cursor_id is not None:
                query += " AND (created_at < ? OR (created_at = ? AND id < ?))"
                params.extend([cursor_created_at, cursor_created_at, cursor_id])
            query += " ORDER BY created_at DESC, id DESC LIMIT ?"
            params.append(normalized_limit + 1)
            rows = conn.execute(query, params).fetchall()
        has_more = len(rows) > normalized_limit
        visible_rows = rows[:normalized_limit]
        return {
            "items": [
                self._comment_payload(
                    row,
                    can_delete=clean_viewer_id is not None and row["user_id"] == clean_viewer_id,
                )
                for row in visible_rows
            ],
            "next_cursor": self._encode_cursor(visible_rows[-1], "created_at")
            if has_more and visible_rows
            else None,
        }

    def delete_comment(self, user_id: str, comment_id: str) -> dict:
        clean_user_id = self._validated_id(user_id, "user_id")
        clean_comment_id = self._validated_id(comment_id, "comment_id")
        now = int(time.time() * 1000)
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM community_comments WHERE id = ? AND user_id = ?",
                (clean_comment_id, clean_user_id),
            ).fetchone()
            if row is None:
                raise CommunityNotFoundError("评论不存在")
            if row["status"] == "deleted":
                return {"id": clean_comment_id, "status": "deleted"}
            conn.execute(
                "UPDATE community_comments SET status = 'deleted', updated_at = ? WHERE id = ?",
                (now, clean_comment_id),
            )
            conn.execute(
                "UPDATE community_comment_reports SET status = 'resolved', resolution = 'author_deleted', "
                "reviewed_by = ?, reviewed_at = ?, updated_at = ? "
                "WHERE comment_id = ? AND status = 'open'",
                (clean_user_id, now, now, clean_comment_id),
            )
            return {"id": clean_comment_id, "status": "deleted"}

    def report_comment(
        self,
        reporter_user_id: str,
        comment_id: str,
        report: CommunityReportInput,
    ) -> tuple[dict, bool]:
        clean_reporter_id = self._validated_id(reporter_user_id, "reporter_user_id")
        clean_comment_id = self._validated_id(comment_id, "comment_id")
        category, reason = self._validated_report(report)
        self._consume_action_attempt(clean_reporter_id, "report")
        now = int(time.time() * 1000)
        with self._connect() as conn:
            comment = conn.execute(
                """
                SELECT c.*, p.user_id AS post_owner_id, p.status AS post_status,
                       m.decision AS review_decision
                FROM community_comments c
                JOIN community_posts p ON p.id = c.post_id
                LEFT JOIN community_moderation m ON m.post_id = p.id
                WHERE c.id = ?
                """,
                (clean_comment_id,),
            ).fetchone()
            if (
                comment is None
                or comment["status"] != "visible"
                or comment["post_status"] != "published"
                or comment["review_decision"] != "approved"
            ):
                raise CommunityNotFoundError("评论不存在或暂不可举报")
            if comment["user_id"] == clean_reporter_id:
                raise CommunityPermissionError("不能举报自己的评论")
            existing = conn.execute(
                "SELECT * FROM community_comment_reports WHERE comment_id = ? AND reporter_user_id = ?",
                (clean_comment_id, clean_reporter_id),
            ).fetchone()
            if existing is not None:
                return self._comment_report_payload(existing), False
            report_id = uuid.uuid4().hex
            conn.execute(
                """
                INSERT INTO community_comment_reports(
                    id, comment_id, reporter_user_id, category, reason, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'open', ?, ?)
                """,
                (report_id, clean_comment_id, clean_reporter_id, category, reason, now, now),
            )
            created = conn.execute(
                "SELECT * FROM community_comment_reports WHERE id = ?",
                (report_id,),
            ).fetchone()
            return self._comment_report_payload(created), True

    def list_comment_reports(
        self,
        *,
        status: str = "open",
        cursor: str | None = None,
        limit: int = 20,
    ) -> dict:
        clean_status = status.strip().lower()
        if clean_status not in {"open", "resolved", "all"}:
            raise CommunityError("评论举报筛选无效")
        normalized_limit = self._normalized_limit(limit)
        cursor_created_at, cursor_id = self._decode_cursor(cursor)
        query = """
            SELECT r.*, c.post_id, c.content, c.status AS comment_status,
                   p.title AS post_title
            FROM community_comment_reports r
            JOIN community_comments c ON c.id = r.comment_id
            JOIN community_posts p ON p.id = c.post_id
            WHERE 1 = 1
        """
        params: list[object] = []
        if clean_status != "all":
            query += " AND r.status = ?"
            params.append(clean_status)
        if cursor_created_at is not None and cursor_id is not None:
            query += " AND (r.created_at < ? OR (r.created_at = ? AND r.id < ?))"
            params.extend([cursor_created_at, cursor_created_at, cursor_id])
        query += " ORDER BY r.created_at DESC, r.id DESC LIMIT ?"
        params.append(normalized_limit + 1)
        with self._connect() as conn:
            rows = conn.execute(query, params).fetchall()
        has_more = len(rows) > normalized_limit
        visible_rows = rows[:normalized_limit]
        return {
            "items": [self._comment_report_queue_payload(row) for row in visible_rows],
            "next_cursor": self._encode_cursor(visible_rows[-1], "created_at")
            if has_more and visible_rows
            else None,
        }

    def resolve_comment_report(
        self,
        report_id: str,
        *,
        decision: str,
        reviewed_by: str,
    ) -> dict:
        clean_report_id = self._validated_id(report_id, "report_id")
        clean_reviewer_id = self._validated_id(reviewed_by, "reviewed_by")
        clean_decision = decision.strip().lower()
        if clean_decision not in {"keep", "delete"}:
            raise CommunityError("评论举报处置无效")
        now = int(time.time() * 1000)
        with self._connect() as conn:
            report = conn.execute(
                "SELECT * FROM community_comment_reports WHERE id = ?",
                (clean_report_id,),
            ).fetchone()
            if report is None:
                raise CommunityNotFoundError("评论举报不存在")
            if clean_decision == "delete":
                conn.execute(
                    "UPDATE community_comments SET status = 'deleted', updated_at = ? WHERE id = ?",
                    (now, report["comment_id"]),
                )
                conn.execute(
                    "UPDATE community_comment_reports SET status = 'resolved', resolution = 'delete', "
                    "reviewed_by = ?, reviewed_at = ?, updated_at = ? "
                    "WHERE comment_id = ? AND status = 'open'",
                    (clean_reviewer_id, now, now, report["comment_id"]),
                )
            else:
                conn.execute(
                    "UPDATE community_comment_reports SET status = 'resolved', resolution = 'keep', "
                    "reviewed_by = ?, reviewed_at = ?, updated_at = ? WHERE id = ?",
                    (clean_reviewer_id, now, now, clean_report_id),
                )
            return {
                "id": clean_report_id,
                "comment_id": report["comment_id"],
                "status": "resolved",
                "decision": clean_decision,
                "reviewed_by": clean_reviewer_id,
            }

    def list_bookmarks(
        self,
        user_id: str,
        *,
        cursor: str | None = None,
        limit: int = 20,
    ) -> dict:
        clean_user_id = self._validated_id(user_id, "user_id")
        normalized_limit = self._normalized_limit(limit)
        cursor_created_at, cursor_id = self._decode_cursor(cursor)
        query = """
            SELECT p.*, m.decision AS review_decision, m.reason AS review_reason,
                   m.reviewed_at AS review_reviewed_at,
                   b.created_at AS bookmark_created_at
            FROM community_post_bookmarks b
            JOIN community_posts p ON p.id = b.post_id
            JOIN community_moderation m ON m.post_id = p.id
            WHERE b.user_id = ? AND p.status = 'published' AND m.decision = 'approved'
        """
        params: list[object] = [clean_user_id]
        if cursor_created_at is not None and cursor_id is not None:
            query += " AND (b.created_at < ? OR (b.created_at = ? AND b.post_id < ?))"
            params.extend([cursor_created_at, cursor_created_at, cursor_id])
        query += " ORDER BY b.created_at DESC, b.post_id DESC LIMIT ?"
        params.append(normalized_limit + 1)
        with self._connect() as conn:
            rows = conn.execute(query, params).fetchall()
            media_by_post = {
                row["id"]: self._public_media_payloads(conn, row["id"])
                for row in rows[:normalized_limit]
            }
            index_by_post = {
                row["id"]: self._public_index_payload(conn, row["id"])
                for row in rows[:normalized_limit]
            }
            interaction_by_post = {
                row["id"]: self._interaction_payload(conn, row["id"], clean_user_id)
                for row in rows[:normalized_limit]
            }
        has_more = len(rows) > normalized_limit
        visible_rows = rows[:normalized_limit]
        return {
            "items": [
                self._public_post_payload(
                    row,
                    media_by_post[row["id"]],
                    index_by_post[row["id"]],
                    interaction_by_post[row["id"]],
                )
                for row in visible_rows
            ],
            "next_cursor": self._encode_cursor(visible_rows[-1], "bookmark_created_at")
            if has_more and visible_rows
            else None,
        }

    def list_moderation_queue(
        self,
        *,
        status: str = "pending",
        cursor: str | None = None,
        limit: int = 20,
    ) -> dict:
        clean_status = status.strip().lower()
        if clean_status not in {"pending", "reported", "all"}:
            raise CommunityError("审核队列筛选无效")
        normalized_limit = self._normalized_limit(limit)
        cursor_published_at, cursor_id = self._decode_cursor(cursor)
        query = """
            SELECT p.*, m.decision AS review_decision, m.reason AS review_reason,
                   m.reviewed_at AS review_reviewed_at,
                   COALESCE(SUM(CASE WHEN r.status = 'open' THEN 1 ELSE 0 END), 0)
                       AS open_report_count
            FROM community_posts p
            LEFT JOIN community_moderation m ON m.post_id = p.id
            LEFT JOIN community_reports r ON r.post_id = p.id
            WHERE p.status = 'published'
        """
        params: list[object] = []
        if cursor_published_at is not None and cursor_id is not None:
            query += " AND (p.published_at < ? OR (p.published_at = ? AND p.id < ?))"
            params.extend([cursor_published_at, cursor_published_at, cursor_id])
        query += " GROUP BY p.id"
        if clean_status == "pending":
            query += " HAVING m.decision IS NULL"
        elif clean_status == "reported":
            query += " HAVING open_report_count > 0"
        query += " ORDER BY p.published_at DESC, p.id DESC LIMIT ?"
        params.append(normalized_limit + 1)
        with self._connect() as conn:
            rows = conn.execute(query, params).fetchall()
            has_more = len(rows) > normalized_limit
            visible_rows = rows[:normalized_limit]
            items = [self._moderation_queue_payload(conn, row) for row in visible_rows]
        return {
            "items": items,
            "next_cursor": self._encode_cursor(visible_rows[-1], "published_at")
            if has_more and visible_rows
            else None,
        }

    def activity_summary(self, *, hours: int = 24) -> dict:
        if hours < 1 or hours > 168:
            raise CommunityError("运行摘要时间范围必须在 1 到 168 小时之间")
        now = self._now_ms()
        hour_ms = 60 * 60 * 1000
        current_bucket = now - (now % hour_ms)
        cutoff = current_bucket - ((hours - 1) * hour_ms)
        with self._connect() as conn:
            metric_rows = conn.execute(
                """
                SELECT outcome, COALESCE(SUM(attempt_count), 0) AS total
                FROM community_activity_metrics
                WHERE bucket_started_at >= ?
                GROUP BY outcome
                """,
                (cutoff,),
            ).fetchall()
            metrics = {row["outcome"]: row["total"] for row in metric_rows}
            pending_posts = conn.execute(
                """
                SELECT COUNT(*) AS total
                FROM community_posts p
                LEFT JOIN community_moderation m ON m.post_id = p.id
                WHERE p.status = 'published' AND m.post_id IS NULL
                """
            ).fetchone()["total"]
            reported_posts = conn.execute(
                """
                SELECT COUNT(DISTINCT r.post_id) AS total
                FROM community_reports r
                JOIN community_posts p ON p.id = r.post_id
                WHERE r.status = 'open' AND p.status = 'published'
                """
            ).fetchone()["total"]
            open_comment_reports = conn.execute(
                "SELECT COUNT(*) AS total FROM community_comment_reports WHERE status = 'open'"
            ).fetchone()["total"]
        return {
            "window_hours": hours,
            "generated_at": now,
            "allowed_action_count": metrics.get("allowed", 0),
            "limited_action_count": metrics.get("limited", 0),
            "pending_post_count": pending_posts,
            "reported_post_count": reported_posts,
            "open_comment_report_count": open_comment_reports,
        }

    def list_public_posts(
        self,
        *,
        cursor: str | None = None,
        limit: int = 20,
        search_query: str = "",
        destination: str = "",
        tag: str = "",
        poi: str = "",
        min_days: int = 0,
        max_days: int = 0,
        has_media: bool = False,
    ) -> dict:
        normalized_limit = self._normalized_limit(limit)
        clean_query = search_query.strip()
        if len(clean_query) > 100:
            raise CommunityError("搜索关键词不能超过 100 个字符")
        clean_destination = destination.strip()
        clean_tag = tag.strip()
        clean_poi = poi.strip()
        if len(clean_destination) > 120 or len(clean_tag) > 80 or len(clean_poi) > 80:
            raise CommunityError("搜索筛选条件过长")
        if min_days < 0 or min_days > 31 or max_days < 0 or max_days > 31:
            raise CommunityError("行程天数筛选必须在 0 到 31 之间")
        if max_days and min_days and max_days < min_days:
            raise CommunityError("行程天数筛选范围无效")
        cursor_published_at, cursor_id = self._decode_cursor(cursor)
        query = """
            SELECT p.*, m.decision AS review_decision, m.reason AS review_reason,
                   m.reviewed_at AS review_reviewed_at
            FROM community_posts p
            JOIN community_moderation m ON m.post_id = p.id
            LEFT JOIN community_post_index i ON i.post_id = p.id
            WHERE p.status = 'published' AND m.decision = 'approved'
        """
        params: list[object] = []
        if clean_query:
            like_query = f"%{clean_query.casefold()}%"
            query += """
                AND (
                    LOWER(p.title) LIKE ? OR LOWER(p.content) LIKE ?
                    OR LOWER(COALESCE(i.destination, '')) LIKE ?
                    OR EXISTS (
                        SELECT 1 FROM community_post_tags t
                        WHERE t.post_id = p.id AND LOWER(t.tag) LIKE ?
                    )
                    OR EXISTS (
                        SELECT 1 FROM community_post_pois x
                        WHERE x.post_id = p.id AND LOWER(x.poi_name) LIKE ?
                    )
                    OR EXISTS (
                        SELECT 1 FROM community_post_stages s
                        WHERE s.post_id = p.id AND LOWER(s.title) LIKE ?
                    )
                )
            """
            params.extend([like_query] * 6)
        if clean_destination:
            query += " AND LOWER(COALESCE(i.destination, '')) = LOWER(?)"
            params.append(clean_destination)
        if clean_tag:
            query += """
                AND EXISTS (
                    SELECT 1 FROM community_post_tags t
                    WHERE t.post_id = p.id AND LOWER(t.tag) = LOWER(?)
                )
            """
            params.append(clean_tag)
        if clean_poi:
            query += """
                AND EXISTS (
                    SELECT 1 FROM community_post_pois x
                    WHERE x.post_id = p.id AND LOWER(x.poi_name) = LOWER(?)
                )
            """
            params.append(clean_poi)
        if min_days:
            query += " AND COALESCE(i.travel_days, 0) >= ?"
            params.append(min_days)
        if max_days:
            query += " AND COALESCE(i.travel_days, 0) <= ? AND COALESCE(i.travel_days, 0) > 0"
            params.append(max_days)
        if has_media:
            query += """
                AND EXISTS (
                    SELECT 1 FROM community_post_media media
                    WHERE media.post_id = p.id AND media.status = 'ready'
                )
            """
        if cursor_published_at is not None and cursor_id is not None:
            query += """
                AND (p.published_at < ? OR (p.published_at = ? AND p.id < ?))
            """
            params.extend([cursor_published_at, cursor_published_at, cursor_id])
        query += " ORDER BY p.published_at DESC, p.id DESC LIMIT ?"
        params.append(normalized_limit + 1)
        with self._connect() as conn:
            rows = conn.execute(query, params).fetchall()
            media_by_post = {
                row["id"]: self._public_media_payloads(conn, row["id"])
                for row in rows[:normalized_limit]
            }
            index_by_post = {
                row["id"]: self._public_index_payload(conn, row["id"])
                for row in rows[:normalized_limit]
            }
            interaction_by_post = {
                row["id"]: self._interaction_payload(conn, row["id"])
                for row in rows[:normalized_limit]
            }
            facets = self._public_facets(conn)
        has_more = len(rows) > normalized_limit
        visible_rows = rows[:normalized_limit]
        return {
            "items": [
                self._public_post_payload(
                    row,
                    media_by_post[row["id"]],
                    index_by_post[row["id"]],
                    interaction_by_post[row["id"]],
                )
                for row in visible_rows
            ],
            "next_cursor": self._encode_cursor(visible_rows[-1], "published_at")
            if has_more and visible_rows
            else None,
            "facets": facets,
        }

    def get_public_post(self, post_id: str) -> dict:
        clean_post_id = self._validated_id(post_id, "post_id")
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT p.*, m.decision AS review_decision, m.reason AS review_reason,
                       m.reviewed_at AS review_reviewed_at
                FROM community_posts p
                JOIN community_moderation m ON m.post_id = p.id
                WHERE p.id = ? AND p.status = 'published' AND m.decision = 'approved'
                """,
                (clean_post_id,),
            ).fetchone()
            if row is None:
                raise CommunityNotFoundError("社区内容不存在或暂不可查看")
            return self._public_post_payload(
                row,
                self._public_media_payloads(conn, row["id"]),
                self._public_index_payload(conn, row["id"]),
                self._interaction_payload(conn, row["id"]),
            )

    def _approved_post(self, conn: sqlite3.Connection, post_id: str) -> sqlite3.Row:
        row = conn.execute(
            """
            SELECT p.* FROM community_posts p
            JOIN community_moderation m ON m.post_id = p.id
            WHERE p.id = ? AND p.status = 'published' AND m.decision = 'approved'
            """,
            (post_id,),
        ).fetchone()
        if row is None:
            raise CommunityNotFoundError("社区内容不存在或暂不可互动")
        return row

    def _owned_post(
        self,
        conn: sqlite3.Connection,
        user_id: str,
        post_id: str,
    ) -> sqlite3.Row:
        clean_user_id = self._validated_id(user_id, "user_id")
        clean_post_id = self._validated_id(post_id, "post_id")
        row = conn.execute(
            """
            SELECT p.*, m.decision AS review_decision, m.reason AS review_reason,
                   m.reviewed_at AS review_reviewed_at
            FROM community_posts p
            LEFT JOIN community_moderation m ON m.post_id = p.id
            WHERE p.id = ? AND p.user_id = ?
            """,
            (clean_post_id, clean_user_id),
        ).fetchone()
        if row is None:
            raise CommunityNotFoundError("社区内容不存在")
        return row

    def _validated_snapshot(self, snapshot: CommunityDraftInput) -> dict:
        title = snapshot.title.strip()
        content = snapshot.content.strip()
        if not title:
            raise CommunityError("标题不能为空")
        if len(title) > self.title_max_length:
            raise CommunityError(f"标题不能超过 {self.title_max_length} 个字符")
        if not content:
            raise CommunityError("正文不能为空")
        if len(content) > self.content_max_length:
            raise CommunityError(f"正文不能超过 {self.content_max_length} 个字符")
        if not snapshot.privacy_reviewed:
            raise CommunityError("发布前必须完成隐私检查")
        if not snapshot.rights_confirmed:
            raise CommunityError("发布前必须确认内容与图片权利")
        if snapshot.source_edition_version <= 0:
            raise CommunityError("来源游记版本必须大于 0")
        if snapshot.redacted_coordinate_count < 0:
            raise CommunityError("坐标脱敏数量不能为负数")
        if PRECISE_COORDINATE_PAIR_PATTERN.search(content) or COORDINATE_FIELD_PATTERN.search(content):
            raise CommunityError("正文仍包含精确坐标，请先完成位置脱敏")
        destination = snapshot.destination.strip()
        if len(destination) > 120:
            raise CommunityError("目的地不能超过 120 个字符")
        travel_date = snapshot.travel_date.strip()
        if travel_date:
            try:
                parsed_date = time.strptime(travel_date, "%Y-%m-%d")
            except ValueError as exc:
                raise CommunityError("行程日期必须使用 YYYY-MM-DD 格式") from exc
            if time.strftime("%Y-%m-%d", parsed_date) != travel_date:
                raise CommunityError("行程日期必须使用 YYYY-MM-DD 格式")
        if snapshot.travel_days < 0 or snapshot.travel_days > 31:
            raise CommunityError("行程天数必须在 0 到 31 之间")

        def normalized_items(values: tuple[str, ...], field_name: str) -> list[str]:
            if len(values) > MAX_METADATA_ITEMS:
                raise CommunityError(f"{field_name}数量不能超过 {MAX_METADATA_ITEMS} 个")
            result: list[str] = []
            seen: set[str] = set()
            for value in values:
                clean = str(value).strip()
                if not clean:
                    continue
                if len(clean) > 80:
                    raise CommunityError(f"{field_name}单项不能超过 80 个字符")
                if clean.casefold() not in seen:
                    result.append(clean)
                    seen.add(clean.casefold())
            return result

        stage_titles = normalized_items(snapshot.stage_titles, "行程段")
        tags = normalized_items(snapshot.tags, "主题标签")
        pois = normalized_items(snapshot.pois, "POI")
        return {
            "client_snapshot_id": self._validated_id(
                snapshot.client_snapshot_id,
                "client_snapshot_id",
            ),
            "journey_id": self._validated_id(snapshot.journey_id, "journey_id"),
            "journey_edition_id": self._validated_id(
                snapshot.journey_edition_id,
                "journey_edition_id",
            ),
            "source_edition_version": snapshot.source_edition_version,
            "title": title,
            "content": content,
            "ai_assisted": int(snapshot.ai_assisted),
            "redacted_coordinate_count": snapshot.redacted_coordinate_count,
            "privacy_reviewed": 1,
            "rights_confirmed": 1,
            "destination": destination,
            "travel_date": travel_date,
            "travel_days": snapshot.travel_days,
            "stage_titles": stage_titles,
            "tags": tags,
            "pois": pois,
        }

    def _validated_media_manifest(self, media: CommunityMediaManifestInput) -> dict:
        display_name = media.display_name.strip()
        mime_type = media.mime_type.strip().lower()
        if not display_name or len(display_name) > 200:
            raise CommunityError("图片名称无效")
        if mime_type not in MEDIA_MIME_TYPES:
            raise CommunityError("仅支持 JPEG、PNG 或 WebP 图片")
        if not 0 < media.original_bytes <= self.media_max_asset_bytes:
            raise CommunityError("原图大小超出限制")
        if not 0 < media.thumbnail_bytes <= self.media_max_thumbnail_bytes:
            raise CommunityError("缩略图大小超出限制")
        return {
            "client_media_id": self._validated_id(media.client_media_id, "client_media_id"),
            "display_name": display_name,
            "mime_type": mime_type,
            "original_total_bytes": media.original_bytes,
            "original_sha256": self._validated_sha256(media.original_sha256, "original_sha256"),
            "thumbnail_total_bytes": media.thumbnail_bytes,
            "thumbnail_sha256": self._validated_sha256(media.thumbnail_sha256, "thumbnail_sha256"),
        }

    @staticmethod
    def _validated_report(report: CommunityReportInput) -> tuple[str, str]:
        category = report.category.strip().lower()
        if category not in REPORT_CATEGORIES:
            raise CommunityError("举报类别无效")
        reason = report.reason.strip()
        if len(reason) > 1000:
            raise CommunityError("举报说明不能超过 1000 个字符")
        return category, reason

    @staticmethod
    def _validated_id(value: str, field_name: str) -> str:
        clean = value.strip()
        if not ID_PATTERN.fullmatch(clean):
            raise CommunityError(f"{field_name} 格式无效")
        return clean

    @staticmethod
    def _validated_sha256(value: str, field_name: str) -> str:
        clean = value.strip().lower()
        if not re.fullmatch(r"[a-f0-9]{64}", clean):
            raise CommunityError(f"{field_name} 格式无效")
        return clean

    @staticmethod
    def _normalized_limit(limit: int) -> int:
        if limit < 1 or limit > 50:
            raise CommunityError("每页数量应在 1 到 50 之间")
        return limit

    def _decode_cursor(
        self,
        cursor: str | None,
    ) -> tuple[int | None, str | None]:
        if cursor is None or not cursor.strip():
            return None, None
        value = cursor.strip()
        timestamp_value, separator, post_id = value.partition(":")
        if not separator or not timestamp_value.isdigit():
            raise CommunityError("分页游标无效")
        self._validated_id(post_id, "cursor")
        timestamp = int(timestamp_value)
        if timestamp < 0:
            raise CommunityError("分页游标无效")
        return timestamp, post_id

    def _owned_media(
        self,
        conn: sqlite3.Connection,
        user_id: str,
        post_id: str,
        media_id: str,
    ) -> sqlite3.Row:
        row = conn.execute(
            """
            SELECT * FROM community_post_media
            WHERE id = ? AND user_id = ? AND post_id = ?
            """,
            (media_id, user_id, post_id),
        ).fetchone()
        if row is None:
            raise CommunityNotFoundError("社区媒体不存在")
        return row

    def _media_path(self, row: sqlite3.Row, variant: str, suffix: str = "") -> Path:
        extension = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}[row["mime_type"]]
        return self.media_root / row["user_id"] / row["post_id"] / f"{row['id']}-{variant}.{extension}{suffix}"

    def _owner_media_payloads(self, conn: sqlite3.Connection, post_id: str) -> list[dict]:
        rows = conn.execute(
            "SELECT * FROM community_post_media WHERE post_id = ? ORDER BY created_at ASC",
            (post_id,),
        ).fetchall()
        return [self._media_payload(row) for row in rows]

    @staticmethod
    def _replace_post_index(
        conn: sqlite3.Connection,
        post_id: str,
        normalized: dict,
        updated_at: int,
    ) -> None:
        conn.execute(
            """
            INSERT INTO community_post_index (
                post_id, destination, travel_date, travel_days, updated_at
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(post_id) DO UPDATE SET
                destination = excluded.destination,
                travel_date = excluded.travel_date,
                travel_days = excluded.travel_days,
                updated_at = excluded.updated_at
            """,
            (
                post_id,
                normalized["destination"],
                normalized["travel_date"],
                normalized["travel_days"],
                updated_at,
            ),
        )
        conn.execute("DELETE FROM community_post_stages WHERE post_id = ?", (post_id,))
        conn.execute("DELETE FROM community_post_tags WHERE post_id = ?", (post_id,))
        conn.execute("DELETE FROM community_post_pois WHERE post_id = ?", (post_id,))
        conn.executemany(
            "INSERT INTO community_post_stages (post_id, sequence_number, title) VALUES (?, ?, ?)",
            [(post_id, index, title) for index, title in enumerate(normalized["stage_titles"], 1)],
        )
        conn.executemany(
            "INSERT INTO community_post_tags (post_id, tag) VALUES (?, ?)",
            [(post_id, tag) for tag in normalized["tags"]],
        )
        conn.executemany(
            "INSERT INTO community_post_pois (post_id, poi_name) VALUES (?, ?)",
            [(post_id, poi) for poi in normalized["pois"]],
        )

    @staticmethod
    def _public_index_payload(conn: sqlite3.Connection, post_id: str) -> dict:
        index = conn.execute(
            "SELECT destination, travel_date, travel_days FROM community_post_index WHERE post_id = ?",
            (post_id,),
        ).fetchone()
        stages = conn.execute(
            """
            SELECT title FROM community_post_stages
            WHERE post_id = ? ORDER BY sequence_number ASC
            """,
            (post_id,),
        ).fetchall()
        tags = conn.execute(
            "SELECT tag FROM community_post_tags WHERE post_id = ? ORDER BY tag ASC",
            (post_id,),
        ).fetchall()
        pois = conn.execute(
            "SELECT poi_name FROM community_post_pois WHERE post_id = ? ORDER BY poi_name ASC",
            (post_id,),
        ).fetchall()
        return {
            "destination": index["destination"] if index else "",
            "travel_date": index["travel_date"] if index else "",
            "travel_days": int(index["travel_days"]) if index else 0,
            "stages": [row["title"] for row in stages],
            "tags": [row["tag"] for row in tags],
            "pois": [row["poi_name"] for row in pois],
        }

    def _public_media_payloads(self, conn: sqlite3.Connection, post_id: str) -> list[dict]:
        rows = conn.execute(
            """
            SELECT * FROM community_post_media
            WHERE post_id = ? AND status = 'ready' ORDER BY created_at ASC
            """,
            (post_id,),
        ).fetchall()
        return [
            {
                "id": row["id"],
                "thumbnail_url": f"/api/community/media/{row['id']}/thumbnail",
                "content_url": f"/api/community/media/{row['id']}/content",
                "mime_type": row["mime_type"],
            }
            for row in rows
        ]

    def _media_quota_payload(self, conn: sqlite3.Connection, user_id: str) -> dict:
        used = conn.execute(
            """
            SELECT COALESCE(SUM(original_total_bytes + thumbnail_total_bytes), 0) AS total
            FROM community_post_media WHERE user_id = ?
            """,
            (user_id,),
        ).fetchone()["total"]
        used_bytes = int(used)
        return {
            "used_bytes": used_bytes,
            "limit_bytes": self.media_quota_bytes,
            "remaining_bytes": max(0, self.media_quota_bytes - used_bytes),
        }

    @staticmethod
    def _media_payload(row: sqlite3.Row | None) -> dict:
        if row is None:
            raise CommunityNotFoundError("社区媒体不存在")
        return {
            "id": row["id"],
            "client_media_id": row["client_media_id"],
            "display_name": row["display_name"],
            "mime_type": row["mime_type"],
            "original_total_bytes": row["original_total_bytes"],
            "original_received_bytes": row["original_received_bytes"],
            "thumbnail_total_bytes": row["thumbnail_total_bytes"],
            "thumbnail_received_bytes": row["thumbnail_received_bytes"],
            "status": row["status"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        }

    def _owner_post_payload(self, conn: sqlite3.Connection, row: sqlite3.Row) -> dict:
        payload = self._post_payload(row)
        payload["media"] = self._owner_media_payloads(conn, payload["id"])
        return payload

    def _moderation_queue_payload(self, conn: sqlite3.Connection, row: sqlite3.Row) -> dict:
        payload = self._post_payload(row)
        reports = conn.execute(
            """
            SELECT category, reason, created_at
            FROM community_reports
            WHERE post_id = ? AND status = 'open'
            ORDER BY created_at ASC, id ASC
            """,
            (row["id"],),
        ).fetchall()
        return {
            "id": payload["id"],
            "title": payload["title"],
            "content": payload["content"],
            "published_at": payload["published_at"],
            "review": payload["review"],
            "open_report_count": int(row["open_report_count"]),
            "reports": [
                {
                    "category": report["category"],
                    "reason": report["reason"],
                    "created_at": report["created_at"],
                }
                for report in reports
            ],
        }

    @staticmethod
    def _encode_cursor(row: sqlite3.Row, timestamp_field: str) -> str:
        timestamp = row[timestamp_field]
        if timestamp is None:
            raise CommunityError("分页游标无效")
        return f"{timestamp}:{row['id']}"

    @staticmethod
    def _request_hash(values: dict) -> str:
        canonical = json.dumps(
            values,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return hashlib.sha256(canonical).hexdigest()

    @staticmethod
    def _post_payload(row: sqlite3.Row | None) -> dict:
        if row is None:
            raise CommunityNotFoundError("社区内容不存在")
        status = str(row["status"])
        moderation_status = str(row["moderation_status"])
        if status not in POST_STATUSES or moderation_status not in MODERATION_STATUSES:
            raise CommunityError("社区内容状态无效")
        payload = {
            "id": row["id"],
            "client_snapshot_id": row["client_snapshot_id"],
            "journey_id": row["journey_id"],
            "journey_edition_id": row["journey_edition_id"],
            "source_edition_version": row["source_edition_version"],
            "title": row["title"],
            "content": row["content"],
            "ai_assisted": bool(row["ai_assisted"]),
            "redacted_coordinate_count": row["redacted_coordinate_count"],
            "privacy_reviewed": bool(row["privacy_reviewed"]),
            "rights_confirmed": bool(row["rights_confirmed"]),
            "status": status,
            "moderation_status": moderation_status,
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
            "published_at": row["published_at"],
            "withdrawn_at": row["withdrawn_at"],
        }
        row_keys = set(row.keys())
        review_decision = row["review_decision"] if "review_decision" in row_keys else None
        payload["review"] = {
            "status": review_decision or moderation_status,
            "reason": row["review_reason"] if "review_reason" in row_keys else "",
            "reviewed_at": row["review_reviewed_at"]
            if "review_reviewed_at" in row_keys
            else None,
        }
        return payload

    @staticmethod
    def _report_payload(row: sqlite3.Row | None) -> dict:
        if row is None:
            raise CommunityNotFoundError("举报记录不存在")
        return {
            "id": row["id"],
            "post_id": row["post_id"],
            "category": row["category"],
            "reason": row["reason"],
            "status": row["status"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        }

    @staticmethod
    def _interaction_payload(
        conn: sqlite3.Connection,
        post_id: str,
        viewer_user_id: str | None = None,
    ) -> dict:
        like_count = int(
            conn.execute(
                "SELECT COUNT(*) FROM community_post_likes WHERE post_id = ?",
                (post_id,),
            ).fetchone()[0]
        )
        comment_count = int(
            conn.execute(
                "SELECT COUNT(*) FROM community_comments WHERE post_id = ? AND status = 'visible'",
                (post_id,),
            ).fetchone()[0]
        )
        liked = False
        bookmarked = False
        if viewer_user_id is not None:
            liked = conn.execute(
                "SELECT 1 FROM community_post_likes WHERE post_id = ? AND user_id = ?",
                (post_id, viewer_user_id),
            ).fetchone() is not None
            bookmarked = conn.execute(
                "SELECT 1 FROM community_post_bookmarks WHERE post_id = ? AND user_id = ?",
                (post_id, viewer_user_id),
            ).fetchone() is not None
        return {
            "like_count": like_count,
            "comment_count": comment_count,
            "liked": liked,
            "bookmarked": bookmarked,
        }

    @staticmethod
    def _comment_payload(row: sqlite3.Row | None, *, can_delete: bool) -> dict:
        if row is None:
            raise CommunityNotFoundError("评论不存在")
        return {
            "id": row["id"],
            "post_id": row["post_id"],
            "content": row["content"],
            "author_label": "研学同行者",
            "created_at": row["created_at"],
            "can_delete": can_delete,
        }

    @staticmethod
    def _comment_report_payload(row: sqlite3.Row | None) -> dict:
        if row is None:
            raise CommunityNotFoundError("评论举报不存在")
        return {
            "id": row["id"],
            "comment_id": row["comment_id"],
            "category": row["category"],
            "reason": row["reason"],
            "status": row["status"],
            "resolution": row["resolution"],
            "reviewed_at": row["reviewed_at"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        }

    @staticmethod
    def _comment_report_queue_payload(row: sqlite3.Row) -> dict:
        return {
            "id": row["id"],
            "comment_id": row["comment_id"],
            "post_id": row["post_id"],
            "post_title": row["post_title"],
            "content": row["content"],
            "comment_status": row["comment_status"],
            "category": row["category"],
            "reason": row["reason"],
            "status": row["status"],
            "resolution": row["resolution"],
            "reviewed_at": row["reviewed_at"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        }

    @staticmethod
    def _public_facets(conn: sqlite3.Connection) -> dict:
        destinations = conn.execute(
            """
            SELECT DISTINCT i.destination AS value
            FROM community_post_index i
            JOIN community_posts p ON p.id = i.post_id
            JOIN community_moderation m ON m.post_id = p.id
            WHERE p.status = 'published' AND m.decision = 'approved'
                AND i.destination != ''
            ORDER BY i.destination ASC LIMIT 50
            """
        ).fetchall()
        tags = conn.execute(
            """
            SELECT DISTINCT t.tag AS value
            FROM community_post_tags t
            JOIN community_posts p ON p.id = t.post_id
            JOIN community_moderation m ON m.post_id = p.id
            WHERE p.status = 'published' AND m.decision = 'approved'
            ORDER BY t.tag ASC LIMIT 50
            """
        ).fetchall()
        pois = conn.execute(
            """
            SELECT DISTINCT x.poi_name AS value
            FROM community_post_pois x
            JOIN community_posts p ON p.id = x.post_id
            JOIN community_moderation m ON m.post_id = p.id
            WHERE p.status = 'published' AND m.decision = 'approved'
            ORDER BY x.poi_name ASC LIMIT 50
            """
        ).fetchall()
        return {
            "destinations": [row["value"] for row in destinations],
            "tags": [row["value"] for row in tags],
            "pois": [row["value"] for row in pois],
        }

    @classmethod
    def _public_post_payload(
        cls,
        row: sqlite3.Row,
        media: list[dict],
        index: dict,
        interaction: dict,
    ) -> dict:
        payload = cls._post_payload(row)
        return {
            "id": payload["id"],
            "title": payload["title"],
            "content": payload["content"],
            "ai_assisted": payload["ai_assisted"],
            "redacted_coordinate_count": payload["redacted_coordinate_count"],
            "published_at": payload["published_at"],
            "author_label": "研学同行者",
            "media": media,
            "like_count": interaction["like_count"],
            "comment_count": interaction["comment_count"],
            **index,
        }
