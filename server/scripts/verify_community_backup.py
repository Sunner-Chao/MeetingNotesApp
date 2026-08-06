#!/usr/bin/env python3
"""Verify a community SQLite backup without exposing community content."""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from pathlib import Path
from posixpath import normpath
from typing import Any


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


MEDIA_VARIANTS = ("original", "thumbnail")
IMAGE_EXTENSIONS = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}


def _safe_relative_path(value: str) -> str:
    normalized = normpath(str(value).replace("\\", "/"))
    path = Path(normalized)
    if not normalized or normalized in {".", ".."} or path.is_absolute() or normalized.startswith("../"):
        raise CommunityBackupVerificationError("media manifest contains an unsafe path")
    if any(part in {"", ".", ".."} for part in normalized.split("/")):
        raise CommunityBackupVerificationError("media manifest contains an unsafe path")
    return normalized


def _media_file_path(media_root: Path, relative_path: str) -> Path:
    root = media_root.expanduser().resolve()
    candidate = (root / Path(*relative_path.split("/"))).resolve()
    try:
        candidate.relative_to(root)
    except ValueError as exc:
        raise CommunityBackupVerificationError("media manifest path escapes media root") from exc
    return candidate


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _deterministic_media_path(row: sqlite3.Row, variant: str, suffix: str = "") -> str:
    extension = IMAGE_EXTENSIONS.get(str(row["mime_type"]))
    if extension is None:
        raise CommunityBackupVerificationError("community media has an unsupported MIME type")
    return _safe_relative_path(
        f"{row['user_id']}/{row['post_id']}/{row['id']}-{variant}.{extension}{suffix}"
    )


def _collect_media_state(db_path: Path, media_root: Path) -> tuple[list[dict], list[dict]]:
    root = media_root.expanduser().resolve()
    if not root.is_dir():
        raise CommunityBackupVerificationError("community media directory does not exist")
    resolved = db_path.expanduser().resolve()
    connection = sqlite3.connect(f"{resolved.as_uri()}?mode=ro", uri=True, timeout=30)
    connection.row_factory = sqlite3.Row
    try:
        rows = connection.execute(
            """
            SELECT id, user_id, post_id, mime_type, status,
                   original_received_bytes, original_total_bytes, original_storage_key,
                   thumbnail_received_bytes, thumbnail_total_bytes, thumbnail_storage_key
            FROM community_post_media
            """
        ).fetchall()
    finally:
        connection.close()

    references: list[dict] = []
    expected_paths: set[str] = set()
    for row in rows:
        for variant in MEDIA_VARIANTS:
            storage_key = str(row[f"{variant}_storage_key"] or "").strip()
            received = int(row[f"{variant}_received_bytes"])
            total = int(row[f"{variant}_total_bytes"])
            if storage_key:
                if received != total:
                    raise CommunityBackupVerificationError("media storage key has incomplete upload state")
                relative_path = _safe_relative_path(storage_key)
                expected_paths.add(relative_path)
                file_path = _media_file_path(root, relative_path)
                if not file_path.is_file() or file_path.is_symlink():
                    raise CommunityBackupVerificationError("database media reference is missing")
                references.append(
                    {
                        "media_id": str(row["id"]),
                        "variant": variant,
                        "storage_key": relative_path,
                        "bytes": file_path.stat().st_size,
                        "sha256": _sha256_file(file_path),
                    }
                )
            elif received == total and total > 0:
                raise CommunityBackupVerificationError("complete media is missing a storage key")
            elif 0 < received < total:
                expected_paths.add(_deterministic_media_path(row, variant, suffix=".part"))

        if str(row["status"]) == "ready" and len(
            [reference for reference in references if reference["media_id"] == str(row["id"])]
        ) != 2:
            raise CommunityBackupVerificationError("ready media is missing a stored variant")

    files: list[dict] = []
    for path in root.rglob("*"):
        if path.is_symlink():
            raise CommunityBackupVerificationError("community media contains a non-regular file")
        if not path.is_file():
            continue
        relative_path = _safe_relative_path(path.relative_to(root).as_posix())
        if relative_path not in expected_paths:
            raise CommunityBackupVerificationError("community media contains an unreferenced file")
        files.append(
            {
                "path": relative_path,
                "bytes": path.stat().st_size,
                "sha256": _sha256_file(path),
            }
        )
    files.sort(key=lambda item: item["path"])
    references.sort(key=lambda item: (item["media_id"], item["variant"]))
    return files, references


def build_community_media_manifest(db_path: Path, media_root: Path) -> dict:
    files, references = _collect_media_state(db_path, media_root)
    return {
        "version": 1,
        "files": files,
        "references": references,
    }


def _verify_media_manifest(db_path: Path, media_root: Path, manifest_path: Path | None) -> dict:
    current = build_community_media_manifest(db_path, media_root)
    if manifest_path is not None:
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise CommunityBackupVerificationError("media manifest is unreadable") from exc
        if manifest.get("version") != 1 or manifest.get("files") != current["files"]:
            raise CommunityBackupVerificationError("media manifest does not match restored files")
        if manifest.get("references") != current["references"]:
            raise CommunityBackupVerificationError("media manifest does not match database references")
    return {
        "file_count": len(current["files"]),
        "reference_count": len(current["references"]),
    }


def verify_community_database(
    db_path: Path,
    *,
    media_root: Path | None = None,
    manifest_path: Path | None = None,
) -> dict:
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
        result: dict[str, Any] = {
            "status": "ok",
            "integrity": "ok",
            "foreign_key_violations": 0,
            "table_counts": counts,
        }
        if media_root is not None:
            result["media"] = _verify_media_manifest(db_path, media_root, manifest_path)
        elif manifest_path is not None:
            raise CommunityBackupVerificationError("media root is required with a media manifest")
        return result
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Read-only integrity and schema verification for a community SQLite backup."
    )
    parser.add_argument("database", type=Path, help="Path to the restored SQLite database")
    parser.add_argument("--media-root", type=Path, help="Restored community-media directory")
    parser.add_argument("--manifest", type=Path, help="Private media manifest to compare")
    parser.add_argument("--write-manifest", type=Path, help="Write a private media manifest")
    args = parser.parse_args()
    try:
        if args.write_manifest is not None:
            if args.media_root is None:
                raise CommunityBackupVerificationError("media root is required to write a manifest")
            manifest = build_community_media_manifest(args.database, args.media_root)
            args.write_manifest.parent.mkdir(parents=True, exist_ok=True)
            args.write_manifest.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            result = {
                "status": "ok",
                "media": {
                    "file_count": len(manifest["files"]),
                    "reference_count": len(manifest["references"]),
                },
            }
        else:
            result = verify_community_database(
                args.database,
                media_root=args.media_root,
                manifest_path=args.manifest,
            )
    except (CommunityBackupVerificationError, sqlite3.Error) as exc:
        print(json.dumps({"status": "failed", "error": str(exc)}, ensure_ascii=False))
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
