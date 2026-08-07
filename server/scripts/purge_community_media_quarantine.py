#!/usr/bin/env python3
"""Verify dual approval before permanently purging quarantined community media."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from cleanup_community_media import _maintenance_lock, _writes_enabled


DAY_MS = 24 * 60 * 60 * 1000
MAX_REQUEST_LIFETIME_MS = 7 * DAY_MS
SAFE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
RUN_NAME_PATTERN = re.compile(r"^cleanup-[A-Za-z0-9._-]{1,200}$")
SHA256_PATTERN = re.compile(r"^[a-f0-9]{64}$")


class CommunityMediaPurgeError(RuntimeError):
    pass


@dataclass(frozen=True)
class PurgeInventory:
    run_names: tuple[str, ...]
    run_paths: tuple[Path, ...]
    file_count: int
    byte_count: int
    sha256: str


@dataclass(frozen=True)
class ValidatedApproval:
    request_id: str
    backup_sha256: str
    inventory: PurgeInventory


def _safe_id(value: Any, label: str) -> str:
    normalized = str(value or "").strip()
    if not SAFE_ID_PATTERN.fullmatch(normalized):
        raise CommunityMediaPurgeError(f"{label} is invalid")
    return normalized


def _validated_sha256(value: Any, label: str) -> str:
    normalized = str(value or "").strip().lower()
    if not SHA256_PATTERN.fullmatch(normalized):
        raise CommunityMediaPurgeError(f"{label} is invalid")
    return normalized


def _required_int(value: Any, label: str) -> int:
    if isinstance(value, bool):
        raise CommunityMediaPurgeError(f"{label} is invalid")
    try:
        normalized = int(value)
    except (TypeError, ValueError) as exc:
        raise CommunityMediaPurgeError(f"{label} is invalid") from exc
    if normalized < 1:
        raise CommunityMediaPurgeError(f"{label} is invalid")
    return normalized


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _regular_file(path: Path, label: str) -> Path:
    requested = path.expanduser()
    if requested.is_symlink():
        raise CommunityMediaPurgeError(f"{label} must be a regular file")
    resolved = requested.resolve()
    if not resolved.is_file():
        raise CommunityMediaPurgeError(f"{label} must be a regular file")
    return resolved


def _quarantine_root(path: Path) -> Path:
    requested = path.expanduser()
    if requested.is_symlink():
        raise CommunityMediaPurgeError("quarantine root must be a regular directory")
    resolved = requested.resolve()
    if not resolved.is_dir():
        raise CommunityMediaPurgeError("quarantine root must be a regular directory")
    return resolved


def _ensure_outside(root: Path, path: Path, label: str) -> None:
    try:
        path.relative_to(root)
    except ValueError:
        return
    raise CommunityMediaPurgeError(f"{label} must be outside quarantine root")


def _eligible_runs(root: Path, *, retention_days: int, now_ms: int) -> dict[str, Path]:
    if retention_days < 1:
        raise CommunityMediaPurgeError("retention days must be positive")
    cutoff_ms = now_ms - retention_days * DAY_MS
    eligible: dict[str, Path] = {}
    for run_root in sorted(root.iterdir(), key=lambda item: item.name):
        if run_root.is_symlink():
            raise CommunityMediaPurgeError("quarantine contains a symbolic link")
        if not run_root.is_dir() or not RUN_NAME_PATTERN.fullmatch(run_root.name):
            raise CommunityMediaPurgeError("quarantine contains an unexpected entry")
        for path in run_root.rglob("*"):
            if path.is_symlink():
                raise CommunityMediaPurgeError("quarantine contains a symbolic link")
            if not path.is_dir() and not path.is_file():
                raise CommunityMediaPurgeError("quarantine contains a non-regular file")
        if int(run_root.stat().st_mtime * 1000) <= cutoff_ms:
            eligible[run_root.name] = run_root
    return eligible


def _build_inventory(eligible: dict[str, Path], run_names: list[str]) -> PurgeInventory:
    if not run_names or len(run_names) > 1000:
        raise CommunityMediaPurgeError("approval run list is invalid")
    normalized_names = sorted({_safe_run_name(name) for name in run_names})
    if len(normalized_names) != len(run_names):
        raise CommunityMediaPurgeError("approval run list contains duplicates")
    digest = hashlib.sha256(b"community-media-purge-inventory-v1\0")
    run_paths: list[Path] = []
    file_count = 0
    byte_count = 0
    for run_name in normalized_names:
        run_root = eligible.get(run_name)
        if run_root is None:
            raise CommunityMediaPurgeError("approved quarantine run is missing or not eligible")
        run_paths.append(run_root)
        digest.update(b"run\0" + run_name.encode("utf-8") + b"\0")
        for path in sorted(run_root.rglob("*"), key=lambda item: item.relative_to(run_root).as_posix()):
            if path.is_dir():
                continue
            relative_path = path.relative_to(run_root).as_posix()
            size = path.stat().st_size
            file_hash = _sha256_file(path)
            digest.update(
                b"file\0"
                + run_name.encode("utf-8")
                + b"\0"
                + relative_path.encode("utf-8")
                + b"\0"
                + str(size).encode("ascii")
                + b"\0"
                + file_hash.encode("ascii")
                + b"\0"
            )
            file_count += 1
            byte_count += size
    return PurgeInventory(
        run_names=tuple(normalized_names),
        run_paths=tuple(run_paths),
        file_count=file_count,
        byte_count=byte_count,
        sha256=digest.hexdigest(),
    )


def _safe_run_name(value: Any) -> str:
    normalized = str(value or "").strip()
    if not RUN_NAME_PATTERN.fullmatch(normalized):
        raise CommunityMediaPurgeError("approval run name is invalid")
    return normalized


def _private_json(path: Path) -> dict[str, Any]:
    resolved = _regular_file(path, "approval file")
    if os.name != "nt" and stat.S_IMODE(resolved.stat().st_mode) & 0o027:
        raise CommunityMediaPurgeError("approval file permissions are too broad")
    try:
        payload = json.loads(resolved.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CommunityMediaPurgeError("approval file is unreadable") from exc
    if not isinstance(payload, dict):
        raise CommunityMediaPurgeError("approval file is invalid")
    return payload


def _atomic_write_json(path: Path, payload: dict[str, Any], *, replace: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise CommunityMediaPurgeError("output file must not be a symbolic link")
    if not replace and path.exists():
        raise CommunityMediaPurgeError("output file already exists")
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        temporary.chmod(0o640)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def prepare_purge_request(
    quarantine_root: Path,
    backup_archive: Path,
    request_path: Path,
    *,
    restore_drill_id: str,
    retention_days: int = 90,
    expires_hours: int = 72,
    now_ms: int | None = None,
    request_id: str | None = None,
    lock_path: Path | None = None,
) -> dict[str, int | str | bool]:
    if expires_hours < 1 or expires_hours > 168:
        raise CommunityMediaPurgeError("request expiry must be between 1 and 168 hours")
    root = _quarantine_root(quarantine_root)
    backup = _regular_file(backup_archive, "backup archive")
    requested_output = request_path.expanduser()
    if requested_output.is_symlink():
        raise CommunityMediaPurgeError("request file must not be a symbolic link")
    output = requested_output.resolve()
    requested_lock = (
        lock_path or root.parent / "community-media-maintenance.lock"
    ).expanduser()
    if requested_lock.is_symlink():
        raise CommunityMediaPurgeError("lock file must not be a symbolic link")
    lock = requested_lock.resolve()
    _ensure_outside(root, backup, "backup archive")
    _ensure_outside(root, output, "request file")
    _ensure_outside(root, lock, "lock file")
    effective_now = now_ms if now_ms is not None else int(time.time() * 1000)
    with _maintenance_lock(lock):
        eligible = _eligible_runs(root, retention_days=retention_days, now_ms=effective_now)
        if not eligible:
            raise CommunityMediaPurgeError("no eligible quarantine runs")
        inventory = _build_inventory(eligible, list(eligible))
        normalized_request_id = _safe_id(request_id or uuid.uuid4().hex, "request id")
        normalized_drill_id = _safe_id(restore_drill_id, "restore drill id")
        backup_sha256 = _sha256_file(backup)
        _atomic_write_json(
            output,
            {
                "version": 1,
                "request_id": normalized_request_id,
                "created_at_ms": effective_now,
                "expires_at_ms": effective_now + expires_hours * 60 * 60 * 1000,
                "retention_days": retention_days,
                "run_names": list(inventory.run_names),
                "inventory_sha256": inventory.sha256,
                "backup_sha256": backup_sha256,
                "restore_drill_id": normalized_drill_id,
                "approvals": [],
            },
            replace=False,
        )
    return {
        "status": "ok",
        "dry_run": True,
        "request_written": True,
        "eligible_run_count": len(inventory.run_names),
        "eligible_file_count": inventory.file_count,
        "eligible_byte_count": inventory.byte_count,
    }


def _validated_approval(
    payload: dict[str, Any],
    *,
    root: Path,
    backup: Path,
    retention_days: int,
    now_ms: int,
) -> ValidatedApproval:
    required_keys = {
        "version",
        "request_id",
        "created_at_ms",
        "expires_at_ms",
        "retention_days",
        "run_names",
        "inventory_sha256",
        "backup_sha256",
        "restore_drill_id",
        "approvals",
    }
    if set(payload) != required_keys or payload.get("version") != 1:
        raise CommunityMediaPurgeError("approval file schema is invalid")
    request_id = _safe_id(payload["request_id"], "request id")
    _safe_id(payload["restore_drill_id"], "restore drill id")
    created_at = _required_int(payload["created_at_ms"], "created time")
    expires_at = _required_int(payload["expires_at_ms"], "expiry time")
    if (
        created_at > now_ms
        or expires_at < now_ms
        or expires_at <= created_at
        or expires_at - created_at > MAX_REQUEST_LIFETIME_MS
    ):
        raise CommunityMediaPurgeError("approval request is not currently valid")
    if _required_int(payload["retention_days"], "retention days") != retention_days:
        raise CommunityMediaPurgeError("approval retention period does not match")
    run_names = payload["run_names"]
    if not isinstance(run_names, list):
        raise CommunityMediaPurgeError("approval run list is invalid")
    eligible = _eligible_runs(root, retention_days=retention_days, now_ms=now_ms)
    inventory = _build_inventory(eligible, run_names)
    inventory_sha256 = _validated_sha256(payload["inventory_sha256"], "inventory digest")
    if inventory.sha256 != inventory_sha256:
        raise CommunityMediaPurgeError("approved inventory does not match quarantine")
    backup_sha256 = _validated_sha256(payload["backup_sha256"], "backup digest")
    if _sha256_file(backup) != backup_sha256:
        raise CommunityMediaPurgeError("approved backup does not match backup archive")
    approvals = payload["approvals"]
    if not isinstance(approvals, list) or len(approvals) < 2:
        raise CommunityMediaPurgeError("two approvals are required")
    reviewers: set[str] = set()
    approval_keys = {
        "reviewer_id",
        "decision",
        "approved_at_ms",
        "inventory_sha256",
        "backup_sha256",
    }
    for approval in approvals:
        if not isinstance(approval, dict) or set(approval) != approval_keys:
            raise CommunityMediaPurgeError("approval entry is invalid")
        reviewer_id = _safe_id(approval["reviewer_id"], "reviewer id")
        approved_at = _required_int(approval["approved_at_ms"], "approval time")
        if (
            approval["decision"] != "approve"
            or approved_at < created_at
            or approved_at > now_ms
            or _validated_sha256(approval["inventory_sha256"], "approval inventory digest")
            != inventory_sha256
            or _validated_sha256(approval["backup_sha256"], "approval backup digest")
            != backup_sha256
        ):
            raise CommunityMediaPurgeError("approval entry does not match request")
        reviewers.add(reviewer_id)
    if len(reviewers) < 2:
        raise CommunityMediaPurgeError("approvals must come from distinct reviewers")
    return ValidatedApproval(request_id, backup_sha256, inventory)


def purge_community_media_quarantine(
    quarantine_root: Path,
    approval_file: Path,
    backup_archive: Path,
    *,
    retention_days: int = 90,
    apply: bool = False,
    confirm_request_id: str | None = None,
    lock_path: Path | None = None,
    receipt_root: Path | None = None,
    now_ms: int | None = None,
) -> dict[str, int | str | bool]:
    if apply and _writes_enabled():
        raise CommunityMediaPurgeError(
            "set COMMUNITY_WRITE_ENABLED=false before applying quarantine purge"
        )
    root = _quarantine_root(quarantine_root)
    approval_path = _regular_file(approval_file, "approval file")
    backup = _regular_file(backup_archive, "backup archive")
    requested_lock = (
        lock_path or root.parent / "community-media-maintenance.lock"
    ).expanduser()
    requested_receipts = (
        receipt_root or root.parent / "community-media-purge-receipts"
    ).expanduser()
    if requested_lock.is_symlink():
        raise CommunityMediaPurgeError("lock file must not be a symbolic link")
    if requested_receipts.is_symlink():
        raise CommunityMediaPurgeError("receipt root must not be a symbolic link")
    lock = requested_lock.resolve()
    receipts = requested_receipts.resolve()
    _ensure_outside(root, approval_path, "approval file")
    _ensure_outside(root, backup, "backup archive")
    _ensure_outside(root, lock, "lock file")
    _ensure_outside(root, receipts, "receipt directory")
    effective_now = now_ms if now_ms is not None else int(time.time() * 1000)

    with _maintenance_lock(lock):
        approval = _validated_approval(
            _private_json(approval_path),
            root=root,
            backup=backup,
            retention_days=retention_days,
            now_ms=effective_now,
        )
        if not apply:
            return {
                "status": "ok",
                "dry_run": True,
                "approved_run_count": len(approval.inventory.run_names),
                "approved_file_count": approval.inventory.file_count,
                "approved_byte_count": approval.inventory.byte_count,
                "purged_run_count": 0,
            }
        if _safe_id(confirm_request_id, "confirmation request id") != approval.request_id:
            raise CommunityMediaPurgeError("confirmation request id does not match")

        if receipts.exists() and (receipts.is_symlink() or not receipts.is_dir()):
            raise CommunityMediaPurgeError("receipt root must be a regular directory")
        receipt_path = receipts / f"purge-{approval.request_id}.json"
        if receipt_path.exists() or receipt_path.is_symlink():
            raise CommunityMediaPurgeError("purge receipt already exists")
        receipt: dict[str, Any] = {
            "version": 1,
            "request_id": approval.request_id,
            "inventory_sha256": approval.inventory.sha256,
            "backup_sha256": approval.backup_sha256,
            "approved_run_count": len(approval.inventory.run_names),
            "approved_file_count": approval.inventory.file_count,
            "approved_byte_count": approval.inventory.byte_count,
            "purged_run_count": 0,
            "started_at_ms": effective_now,
            "completed_at_ms": None,
            "status": "started",
        }
        _atomic_write_json(receipt_path, receipt, replace=False)
        try:
            for run_path in approval.inventory.run_paths:
                shutil.rmtree(run_path)
                receipt["purged_run_count"] += 1
                _atomic_write_json(receipt_path, receipt, replace=True)
        except Exception:
            receipt["status"] = "failed"
            _atomic_write_json(receipt_path, receipt, replace=True)
            raise
        receipt["status"] = "completed"
        receipt["completed_at_ms"] = int(time.time() * 1000)
        _atomic_write_json(receipt_path, receipt, replace=True)
        return {
            "status": "ok",
            "dry_run": False,
            "approved_run_count": len(approval.inventory.run_names),
            "approved_file_count": approval.inventory.file_count,
            "approved_byte_count": approval.inventory.byte_count,
            "purged_run_count": receipt["purged_run_count"],
        }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Prepare or verify dual approval before purging community media quarantine."
    )
    parser.add_argument("quarantine_root", type=Path)
    parser.add_argument("--backup-archive", type=Path, required=True)
    parser.add_argument("--retention-days", type=int, default=90)
    parser.add_argument("--prepare-request", type=Path)
    parser.add_argument("--restore-drill-id")
    parser.add_argument("--request-id")
    parser.add_argument("--expires-hours", type=int, default=72)
    parser.add_argument("--approval-file", type=Path)
    parser.add_argument("--lock-file", type=Path)
    parser.add_argument("--receipt-root", type=Path)
    parser.add_argument("--confirm-request-id")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    try:
        if args.prepare_request is not None:
            if args.apply or args.approval_file is not None or not args.restore_drill_id:
                raise CommunityMediaPurgeError("prepare request arguments are invalid")
            result = prepare_purge_request(
                args.quarantine_root,
                args.backup_archive,
                args.prepare_request,
                restore_drill_id=args.restore_drill_id,
                retention_days=args.retention_days,
                expires_hours=args.expires_hours,
                request_id=args.request_id,
                lock_path=args.lock_file,
            )
        else:
            if args.approval_file is None:
                raise CommunityMediaPurgeError("approval file is required")
            result = purge_community_media_quarantine(
                args.quarantine_root,
                args.approval_file,
                args.backup_archive,
                retention_days=args.retention_days,
                apply=args.apply,
                confirm_request_id=args.confirm_request_id,
                lock_path=args.lock_file,
                receipt_root=args.receipt_root,
            )
    except (CommunityMediaPurgeError, OSError) as exc:
        print(json.dumps({"status": "failed", "error": str(exc)}, ensure_ascii=False))
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
