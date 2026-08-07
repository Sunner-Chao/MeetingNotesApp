#!/usr/bin/env python3
"""Plan or quarantine expired community media without exposing user content."""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from posixpath import normpath


DAY_MS = 24 * 60 * 60 * 1000
MEDIA_VARIANTS = ("original", "thumbnail")
IMAGE_EXTENSIONS = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}


class CommunityMediaCleanupError(RuntimeError):
    pass


@contextmanager
def _maintenance_lock(lock_path: Path):
    """Serialize cleanup with backup/restore-adjacent maintenance processes."""
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+b") as stream:
        stream.seek(0, 2)
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        try:
            if os.name == "nt":
                import msvcrt

                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise CommunityMediaCleanupError(
                "community media maintenance is already running"
            ) from exc
        try:
            yield
        finally:
            try:
                if os.name == "nt":
                    import msvcrt

                    stream.seek(0)
                    msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl

                    fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
            except OSError:
                pass


@dataclass(frozen=True)
class PartialReset:
    media_id: str
    variant: str
    file_path: Path | None


@dataclass(frozen=True)
class CleanupPlan:
    partial_resets: tuple[PartialReset, ...]
    withdrawn_media_ids: tuple[str, ...]
    withdrawn_files: tuple[Path, ...]
    orphan_files: tuple[Path, ...]

    @property
    def files_to_quarantine(self) -> tuple[Path, ...]:
        files = {
            *(reset.file_path for reset in self.partial_resets if reset.file_path is not None),
            *self.withdrawn_files,
            *self.orphan_files,
        }
        return tuple(sorted(files, key=lambda path: str(path)))


def _safe_relative_path(value: str) -> str:
    normalized = normpath(str(value).replace("\\", "/"))
    path = Path(normalized)
    if not normalized or normalized in {".", ".."} or path.is_absolute() or normalized.startswith("../"):
        raise CommunityMediaCleanupError("community media contains an unsafe path")
    if any(part in {"", ".", ".."} for part in normalized.split("/")):
        raise CommunityMediaCleanupError("community media contains an unsafe path")
    return normalized


def _media_path(media_root: Path, relative_path: str) -> Path:
    root = media_root.resolve()
    candidate = (root / Path(*relative_path.split("/"))).resolve()
    try:
        candidate.relative_to(root)
    except ValueError as exc:
        raise CommunityMediaCleanupError("community media path escapes media root") from exc
    return candidate


def _deterministic_path(row: sqlite3.Row, variant: str, suffix: str = "") -> str:
    extension = IMAGE_EXTENSIONS.get(str(row["mime_type"]))
    if extension is None:
        raise CommunityMediaCleanupError("community media has an unsupported MIME type")
    return _safe_relative_path(
        f"{row['user_id']}/{row['post_id']}/{row['id']}-{variant}.{extension}{suffix}"
    )


def _existing_regular_file(path: Path) -> Path | None:
    if not path.exists():
        return None
    if path.is_symlink() or not path.is_file():
        raise CommunityMediaCleanupError("community media contains a non-regular file")
    return path


def _build_cleanup_plan(
    connection: sqlite3.Connection,
    media_root: Path,
    *,
    now_ms: int,
    partial_retention_days: int,
    withdrawn_retention_days: int,
) -> CleanupPlan:
    partial_cutoff = now_ms - partial_retention_days * DAY_MS
    withdrawn_cutoff = now_ms - withdrawn_retention_days * DAY_MS
    rows = connection.execute(
        """
        SELECT m.*, p.status AS post_status, p.withdrawn_at
        FROM community_post_media m
        JOIN community_posts p ON p.id = m.post_id
        """
    ).fetchall()

    expected_files: set[Path] = set()
    planned_files: set[Path] = set()
    partial_resets: list[PartialReset] = []
    withdrawn_media_ids: list[str] = []
    withdrawn_files: set[Path] = set()

    for row in rows:
        withdrawn_at = int(row["withdrawn_at"] or 0)
        is_expired_withdrawal = (
            str(row["post_status"]) == "withdrawn"
            and withdrawn_at > 0
            and withdrawn_at <= withdrawn_cutoff
        )
        if is_expired_withdrawal:
            withdrawn_media_ids.append(str(row["id"]))
            for variant in MEDIA_VARIANTS:
                candidates = {
                    _deterministic_path(row, variant),
                    _deterministic_path(row, variant, suffix=".part"),
                }
                storage_key = str(row[f"{variant}_storage_key"] or "").strip()
                if storage_key:
                    candidates.add(_safe_relative_path(storage_key))
                for relative_path in candidates:
                    existing = _existing_regular_file(_media_path(media_root, relative_path))
                    if existing is not None:
                        withdrawn_files.add(existing)
                        planned_files.add(existing)
            continue

        for variant in MEDIA_VARIANTS:
            received = int(row[f"{variant}_received_bytes"])
            total = int(row[f"{variant}_total_bytes"])
            storage_key = str(row[f"{variant}_storage_key"] or "").strip()
            if storage_key:
                if received != total:
                    raise CommunityMediaCleanupError("stored media has incomplete upload state")
                final_path = _media_path(media_root, _safe_relative_path(storage_key))
                if _existing_regular_file(final_path) is None:
                    raise CommunityMediaCleanupError("database media reference is missing")
                expected_files.add(final_path)
            elif received == total and total > 0:
                raise CommunityMediaCleanupError("complete media is missing a storage key")

            if 0 < received < total:
                partial_path = _media_path(
                    media_root,
                    _deterministic_path(row, variant, suffix=".part"),
                )
                existing_partial = _existing_regular_file(partial_path)
                if int(row["updated_at"]) <= partial_cutoff:
                    partial_resets.append(
                        PartialReset(str(row["id"]), variant, existing_partial)
                    )
                    if existing_partial is not None:
                        planned_files.add(existing_partial)
                elif existing_partial is None:
                    raise CommunityMediaCleanupError("active partial upload file is missing")
                else:
                    expected_files.add(existing_partial)

    orphan_files: list[Path] = []
    for path in media_root.rglob("*"):
        if path.is_symlink():
            raise CommunityMediaCleanupError("community media contains a non-regular file")
        if not path.is_file():
            continue
        if path not in expected_files and path not in planned_files:
            orphan_files.append(path)

    return CleanupPlan(
        partial_resets=tuple(partial_resets),
        withdrawn_media_ids=tuple(sorted(set(withdrawn_media_ids))),
        withdrawn_files=tuple(sorted(withdrawn_files, key=lambda path: str(path))),
        orphan_files=tuple(sorted(orphan_files, key=lambda path: str(path))),
    )


def cleanup_community_media(
    db_path: Path,
    media_root: Path,
    quarantine_root: Path,
    *,
    partial_retention_days: int = 7,
    withdrawn_retention_days: int = 30,
    apply: bool = False,
    now_ms: int | None = None,
    lock_path: Path | None = None,
) -> dict:
    if partial_retention_days < 1 or withdrawn_retention_days < 1:
        raise CommunityMediaCleanupError("retention days must be positive")
    if apply and _writes_enabled():
        raise CommunityMediaCleanupError(
            "set COMMUNITY_WRITE_ENABLED=false before applying media cleanup"
        )
    database = db_path.expanduser().resolve()
    media = media_root.expanduser().resolve()
    quarantine = quarantine_root.expanduser().resolve()
    lock = (lock_path or database.parent / "community-media-maintenance.lock").expanduser().resolve()
    if not database.is_file() or not media.is_dir():
        raise CommunityMediaCleanupError("community database or media directory does not exist")
    try:
        quarantine.relative_to(media)
    except ValueError:
        pass
    else:
        raise CommunityMediaCleanupError("quarantine directory must be outside media root")
    try:
        lock.relative_to(media)
    except ValueError:
        pass
    else:
        raise CommunityMediaCleanupError("lock file must be outside media root")

    with _maintenance_lock(lock):
        connection = sqlite3.connect(database, timeout=30)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        moved_files: list[tuple[Path, Path]] = []
        effective_now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
        run_root: Path | None = None
        try:
            if apply:
                connection.execute("BEGIN IMMEDIATE")
            plan = _build_cleanup_plan(
                connection,
                media,
                now_ms=effective_now_ms,
                partial_retention_days=partial_retention_days,
                withdrawn_retention_days=withdrawn_retention_days,
            )
            if apply:
                run_root = quarantine / f"cleanup-{int(time.time())}-{uuid.uuid4().hex}"
                for source in plan.files_to_quarantine:
                    relative_path = source.relative_to(media)
                    destination = run_root / relative_path
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    if destination.exists():
                        raise CommunityMediaCleanupError("quarantine destination already exists")
                    source.replace(destination)
                    moved_files.append((source, destination))
                for reset in plan.partial_resets:
                    other_variant = "thumbnail" if reset.variant == "original" else "original"
                    connection.execute(
                        f"""
                        UPDATE community_post_media
                        SET {reset.variant}_received_bytes = 0,
                            status = CASE
                                WHEN {other_variant}_received_bytes = 0 THEN 'pending'
                                ELSE 'uploading'
                            END,
                            updated_at = ?
                        WHERE id = ?
                        """,
                        (effective_now_ms, reset.media_id),
                    )
                if plan.withdrawn_media_ids:
                    placeholders = ",".join("?" for _ in plan.withdrawn_media_ids)
                    connection.execute(
                        f"DELETE FROM community_post_media WHERE id IN ({placeholders})",
                        plan.withdrawn_media_ids,
                    )
                connection.commit()
            return {
                "status": "ok",
                "dry_run": not apply,
                "stale_partial_variant_count": len(plan.partial_resets),
                "withdrawn_media_count": len(plan.withdrawn_media_ids),
                "orphan_file_count": len(plan.orphan_files),
                "planned_quarantine_file_count": len(plan.files_to_quarantine),
                "quarantined_file_count": len(moved_files),
            }
        except Exception:
            connection.rollback()
            for source, destination in reversed(moved_files):
                source.parent.mkdir(parents=True, exist_ok=True)
                if destination.exists():
                    destination.replace(source)
            if run_root is not None and run_root.exists():
                for path in sorted(run_root.rglob("*"), key=lambda item: len(item.parts), reverse=True):
                    if path.is_dir():
                        try:
                            path.rmdir()
                        except OSError:
                            pass
                try:
                    run_root.rmdir()
                except OSError:
                    pass
            raise
        finally:
            connection.close()


def _writes_enabled() -> bool:
    value = os.getenv("COMMUNITY_WRITE_ENABLED", "true").strip().lower()
    return value not in {"0", "false", "no", "off"}


def main() -> int:
    parser = argparse.ArgumentParser(description="Dry-run or quarantine expired community media.")
    parser.add_argument("database", type=Path)
    parser.add_argument("--media-root", type=Path, required=True)
    parser.add_argument("--quarantine-root", type=Path, required=True)
    parser.add_argument("--partial-retention-days", type=int, default=7)
    parser.add_argument("--withdrawn-retention-days", type=int, default=30)
    parser.add_argument("--lock-file", type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    try:
        if args.apply and _writes_enabled():
            raise CommunityMediaCleanupError(
                "set COMMUNITY_WRITE_ENABLED=false before applying media cleanup"
            )
        result = cleanup_community_media(
            args.database,
            args.media_root,
            args.quarantine_root,
            partial_retention_days=args.partial_retention_days,
            withdrawn_retention_days=args.withdrawn_retention_days,
            apply=args.apply,
            lock_path=args.lock_file,
        )
    except (CommunityMediaCleanupError, OSError, sqlite3.Error) as exc:
        print(json.dumps({"status": "failed", "error": str(exc)}, ensure_ascii=False))
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
