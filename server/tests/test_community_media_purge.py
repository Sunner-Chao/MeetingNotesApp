from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SERVER_DIR = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = SERVER_DIR / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))

from purge_community_media_quarantine import (
    CommunityMediaPurgeError,
    prepare_purge_request,
    purge_community_media_quarantine,
)


DAY_MS = 24 * 60 * 60 * 1000


class CommunityMediaPurgeTests(unittest.TestCase):
    @staticmethod
    def _create_fixture(root: Path) -> tuple[Path, Path, Path, int]:
        quarantine = root / "community-media-quarantine"
        old_a = quarantine / "cleanup-old-a"
        old_b = quarantine / "cleanup-old-b"
        recent = quarantine / "cleanup-recent"
        for run in (old_a, old_b, recent):
            (run / "owner" / "post").mkdir(parents=True)
        (old_a / "owner" / "post" / "a.bin").write_bytes(b"old-a")
        (old_b / "owner" / "post" / "b.bin").write_bytes(b"old-b")
        (recent / "owner" / "post" / "c.bin").write_bytes(b"recent")
        now_ms = 100 * DAY_MS
        os.utime(old_a, (0, 0))
        os.utime(old_b, (0, 0))
        os.utime(recent, (now_ms / 1000, now_ms / 1000))
        backup = root / "meetingnotes-backup.tar.gz"
        backup.write_bytes(b"verified backup fixture")
        return quarantine, backup, recent, now_ms

    @staticmethod
    def _add_approvals(request_path: Path, now_ms: int, reviewers: tuple[str, str]) -> dict:
        payload = json.loads(request_path.read_text(encoding="utf-8"))
        payload["approvals"] = [
            {
                "reviewer_id": reviewer,
                "decision": "approve",
                "approved_at_ms": now_ms,
                "inventory_sha256": payload["inventory_sha256"],
                "backup_sha256": payload["backup_sha256"],
            }
            for reviewer in reviewers
        ]
        request_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return payload

    def test_prepare_verify_and_apply_dual_approved_purge(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            quarantine, backup, recent, now_ms = self._create_fixture(root)
            request_path = root / "purge-request.json"
            receipts = root / "purge-receipts"
            prepared = prepare_purge_request(
                quarantine,
                backup,
                request_path,
                restore_drill_id="restore-drill-001",
                request_id="purge-request-001",
                now_ms=now_ms,
            )
            self.assertEqual(prepared["eligible_run_count"], 2)
            approval = self._add_approvals(
                request_path,
                now_ms,
                ("reviewer-one", "reviewer-two"),
            )

            dry_run = purge_community_media_quarantine(
                quarantine,
                request_path,
                backup,
                now_ms=now_ms,
            )
            self.assertTrue(dry_run["dry_run"])
            self.assertEqual(dry_run["approved_run_count"], 2)
            self.assertEqual(dry_run["purged_run_count"], 0)
            self.assertEqual(set(dry_run), {
                "status",
                "dry_run",
                "approved_run_count",
                "approved_file_count",
                "approved_byte_count",
                "purged_run_count",
            })

            with patch.dict(os.environ, {"COMMUNITY_WRITE_ENABLED": "true"}):
                with self.assertRaises(CommunityMediaPurgeError):
                    purge_community_media_quarantine(
                        quarantine,
                        request_path,
                        backup,
                        apply=True,
                        confirm_request_id=approval["request_id"],
                        receipt_root=receipts,
                        now_ms=now_ms,
                    )
            with patch.dict(os.environ, {"COMMUNITY_WRITE_ENABLED": "false"}):
                applied = purge_community_media_quarantine(
                    quarantine,
                    request_path,
                    backup,
                    apply=True,
                    confirm_request_id=approval["request_id"],
                    receipt_root=receipts,
                    now_ms=now_ms,
                )

            self.assertFalse(applied["dry_run"])
            self.assertEqual(applied["purged_run_count"], 2)
            self.assertEqual([path.name for path in quarantine.iterdir()], [recent.name])
            receipt = json.loads(
                (receipts / "purge-purge-request-001.json").read_text(encoding="utf-8")
            )
            self.assertEqual(receipt["status"], "completed")
            self.assertEqual(receipt["purged_run_count"], 2)

    def test_rejects_duplicate_reviewers_and_changed_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            quarantine, backup, _, now_ms = self._create_fixture(root)
            request_path = root / "purge-request.json"
            prepare_purge_request(
                quarantine,
                backup,
                request_path,
                restore_drill_id="restore-drill-002",
                request_id="purge-request-002",
                now_ms=now_ms,
            )
            self._add_approvals(
                request_path,
                now_ms,
                ("same-reviewer", "same-reviewer"),
            )
            with self.assertRaisesRegex(
                CommunityMediaPurgeError,
                "distinct reviewers",
            ):
                purge_community_media_quarantine(
                    quarantine,
                    request_path,
                    backup,
                    now_ms=now_ms,
                )

            self._add_approvals(
                request_path,
                now_ms,
                ("reviewer-one", "reviewer-two"),
            )
            target = next((quarantine / "cleanup-old-a").rglob("*.bin"))
            target.write_bytes(target.read_bytes() + b"changed")
            with self.assertRaisesRegex(
                CommunityMediaPurgeError,
                "inventory does not match",
            ):
                purge_community_media_quarantine(
                    quarantine,
                    request_path,
                    backup,
                    now_ms=now_ms,
                )


if __name__ == "__main__":
    unittest.main()
