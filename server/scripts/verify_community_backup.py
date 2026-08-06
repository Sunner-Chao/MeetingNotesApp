#!/usr/bin/env python3
"""Verify a community SQLite backup without exposing community content."""

from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path


CORE_COMMUNITY_TABLES = (
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
)


class CommunityBackupVerificationError(RuntimeError):
    pass


def verify_community_database(db_path: Path) -> dict:
    resolved = db_path.expanduser().resolve()
    if not resolved.is_file():
        raise CommunityBackupVerificationError("database backup does not exist")

    connection = sqlite3.connect(f"{resolved.as_uri()}?mode=ro", uri=True, timeout=30)
    try:
        connection.execute("PRAGMA query_only = ON")
        integrity_rows = connection.execute("PRAGMA integrity_check").fetchall()
        if integrity_rows != [("ok",)]:
            raise CommunityBackupVerificationError("database integrity check failed")

        existing_tables = {
            str(row[0])
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).fetchall()
        }
        missing_tables = sorted(set(CORE_COMMUNITY_TABLES) - existing_tables)
        if missing_tables:
            raise CommunityBackupVerificationError(
                "missing community tables: " + ", ".join(missing_tables)
            )

        foreign_key_violations = len(connection.execute("PRAGMA foreign_key_check").fetchall())
        if foreign_key_violations:
            raise CommunityBackupVerificationError(
                f"database contains {foreign_key_violations} foreign key violation(s)"
            )

        counts = {
            table: int(connection.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0])
            for table in CORE_COMMUNITY_TABLES
        }
        return {
            "status": "ok",
            "integrity": "ok",
            "foreign_key_violations": 0,
            "table_counts": counts,
        }
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Read-only integrity and schema verification for a community SQLite backup."
    )
    parser.add_argument("database", type=Path, help="Path to the restored SQLite database")
    args = parser.parse_args()
    try:
        result = verify_community_database(args.database)
    except (CommunityBackupVerificationError, sqlite3.Error) as exc:
        print(json.dumps({"status": "failed", "error": str(exc)}, ensure_ascii=False))
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
