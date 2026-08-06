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
from typing import Iterator


POST_STATUSES = frozenset({"private_draft", "published", "withdrawn"})
MODERATION_STATUSES = frozenset({"not_submitted", "pending"})
REVIEW_DECISIONS = frozenset({"approved", "rejected"})
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


class CommunityService:
    def __init__(
        self,
        db_path: Path,
        *,
        title_max_length: int = 200,
        content_max_length: int = 100_000,
    ) -> None:
        self.db_path = Path(db_path)
        self.title_max_length = max(1, title_max_length)
        self.content_max_length = max(1, content_max_length)

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
                """
            )

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
            return self._post_payload(row), True

    def get_post(self, user_id: str, post_id: str) -> dict:
        with self._connect() as conn:
            return self._post_payload(self._owned_post(conn, user_id, post_id))

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
            "items": [self._post_payload(row) for row in visible_rows],
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

    def list_public_posts(
        self,
        *,
        cursor: str | None = None,
        limit: int = 20,
    ) -> dict:
        normalized_limit = self._normalized_limit(limit)
        cursor_published_at, cursor_id = self._decode_cursor(cursor)
        query = """
            SELECT p.*, m.decision AS review_decision, m.reason AS review_reason,
                   m.reviewed_at AS review_reviewed_at
            FROM community_posts p
            JOIN community_moderation m ON m.post_id = p.id
            WHERE p.status = 'published' AND m.decision = 'approved'
        """
        params: list[object] = []
        if cursor_published_at is not None and cursor_id is not None:
            query += """
                AND (p.published_at < ? OR (p.published_at = ? AND p.id < ?))
            """
            params.extend([cursor_published_at, cursor_published_at, cursor_id])
        query += "ORDER BY p.published_at DESC, p.id DESC LIMIT ?"
        params.append(normalized_limit + 1)
        with self._connect() as conn:
            rows = conn.execute(query, params).fetchall()
        has_more = len(rows) > normalized_limit
        visible_rows = rows[:normalized_limit]
        return {
            "items": [self._public_post_payload(row) for row in visible_rows],
            "next_cursor": self._encode_cursor(visible_rows[-1], "published_at")
            if has_more and visible_rows
            else None,
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
            return self._public_post_payload(row)

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
        }

    @staticmethod
    def _validated_id(value: str, field_name: str) -> str:
        clean = value.strip()
        if not ID_PATTERN.fullmatch(clean):
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

    @classmethod
    def _public_post_payload(cls, row: sqlite3.Row) -> dict:
        payload = cls._post_payload(row)
        return {
            "id": payload["id"],
            "title": payload["title"],
            "content": payload["content"],
            "ai_assisted": payload["ai_assisted"],
            "redacted_coordinate_count": payload["redacted_coordinate_count"],
            "published_at": payload["published_at"],
            "author_label": "研学同行者",
        }
