#!/usr/bin/env python3
"""Audit community media quarantine retention without deleting anything."""

from __future__ import annotations

import argparse
import json
import time
from dataclasses import dataclass
from pathlib import Path


DAY_MS = 24 * 60 * 60 * 1000
RUN_PREFIX = "cleanup-"


class CommunityMediaQuarantineError(RuntimeError):
    pass


@dataclass(frozen=True)
class QuarantineAudit:
    run_count: int
    file_count: int
    byte_count: int
    eligible_run_count: int
    eligible_file_count: int
    eligible_byte_count: int

    def as_dict(self) -> dict[str, int | str | bool]:
        return {
            "status": "ok",
            "dry_run": True,
            "run_count": self.run_count,
            "file_count": self.file_count,
            "byte_count": self.byte_count,
            "eligible_run_count": self.eligible_run_count,
            "eligible_file_count": self.eligible_file_count,
            "eligible_byte_count": self.eligible_byte_count,
        }


def _audit_run(run_root: Path) -> tuple[int, int]:
    file_count = 0
    byte_count = 0
    for path in run_root.rglob("*"):
        if path.is_symlink():
            raise CommunityMediaQuarantineError(
                "quarantine contains a symbolic link"
            )
        if path.is_dir():
            continue
        if not path.is_file():
            raise CommunityMediaQuarantineError(
                "quarantine contains a non-regular file"
            )
        file_count += 1
        byte_count += path.stat().st_size
    return file_count, byte_count


def audit_community_media_quarantine(
    quarantine_root: Path,
    *,
    retention_days: int = 90,
    now_ms: int | None = None,
) -> dict[str, int | str | bool]:
    if retention_days < 1:
        raise CommunityMediaQuarantineError("retention days must be positive")
    requested_root = quarantine_root.expanduser()
    if requested_root.is_symlink():
        raise CommunityMediaQuarantineError(
            "quarantine root must be a regular directory"
        )
    root = requested_root.resolve()
    if not root.exists():
        return QuarantineAudit(0, 0, 0, 0, 0, 0).as_dict()
    if not root.is_dir():
        raise CommunityMediaQuarantineError(
            "quarantine root must be a regular directory"
        )

    cutoff_ms = (now_ms if now_ms is not None else int(time.time() * 1000)) - (
        retention_days * DAY_MS
    )
    totals = [0, 0, 0, 0, 0, 0]
    for run_root in sorted(root.iterdir(), key=lambda path: path.name):
        if run_root.is_symlink():
            raise CommunityMediaQuarantineError(
                "quarantine contains a symbolic link"
            )
        if not run_root.is_dir() or not run_root.name.startswith(RUN_PREFIX):
            raise CommunityMediaQuarantineError(
                "quarantine contains an unexpected entry"
            )
        file_count, byte_count = _audit_run(run_root)
        totals[0] += 1
        totals[1] += file_count
        totals[2] += byte_count
        if int(run_root.stat().st_mtime * 1000) <= cutoff_ms:
            totals[3] += 1
            totals[4] += file_count
            totals[5] += byte_count

    return QuarantineAudit(*totals).as_dict()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Audit quarantine retention without deleting community media."
    )
    parser.add_argument("quarantine_root", type=Path)
    parser.add_argument("--retention-days", type=int, default=90)
    parser.add_argument("--now-ms", type=int)
    args = parser.parse_args()
    try:
        result = audit_community_media_quarantine(
            args.quarantine_root,
            retention_days=args.retention_days,
            now_ms=args.now_ms,
        )
    except (CommunityMediaQuarantineError, OSError) as exc:
        print(json.dumps({"status": "failed", "error": str(exc)}, ensure_ascii=False))
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
