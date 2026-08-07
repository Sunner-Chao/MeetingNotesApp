from __future__ import annotations

import tempfile
import unittest
import sys
from pathlib import Path


SERVER_DIR = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = SERVER_DIR / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))

from audit_community_media_quarantine import audit_community_media_quarantine
from cleanup_community_media import CommunityMediaCleanupError, _maintenance_lock


DAY_MS = 24 * 60 * 60 * 1000


class CommunityMediaOperationsTests(unittest.TestCase):
    def test_maintenance_lock_rejects_a_second_holder(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            lock_path = Path(temp_dir) / "community-media-maintenance.lock"
            with _maintenance_lock(lock_path):
                with self.assertRaises(CommunityMediaCleanupError):
                    with _maintenance_lock(lock_path):
                        pass

    def test_quarantine_audit_reports_retention_candidates_without_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "community-media-quarantine"
            old_run = root / "cleanup-old"
            new_run = root / "cleanup-new"
            (old_run / "owner" / "post").mkdir(parents=True)
            (new_run / "owner" / "post").mkdir(parents=True)
            (old_run / "owner" / "post" / "a.bin").write_bytes(b"old")
            (old_run / "owner" / "post" / "b.bin").write_bytes(b"older")
            (new_run / "owner" / "post" / "c.bin").write_bytes(b"new")
            old_seconds = 0
            new_seconds = 100 * 24 * 60 * 60
            import os

            os.utime(old_run, (old_seconds, old_seconds))
            os.utime(new_run, (new_seconds, new_seconds))

            result = audit_community_media_quarantine(
                root,
                retention_days=90,
                now_ms=100 * DAY_MS,
            )

            self.assertEqual(
                result,
                {
                    "status": "ok",
                    "dry_run": True,
                    "run_count": 2,
                    "file_count": 3,
                    "byte_count": 11,
                    "eligible_run_count": 1,
                    "eligible_file_count": 2,
                    "eligible_byte_count": 8,
                },
            )

    def test_cleanup_timer_contract_is_dry_run_only(self) -> None:
        service = (SERVER_DIR / "systemd" / "meetingnotes-community-media-cleanup.service").read_text(
            encoding="utf-8"
        )
        timer = (SERVER_DIR / "systemd" / "meetingnotes-community-media-cleanup.timer").read_text(
            encoding="utf-8"
        )
        installer = (SERVER_DIR / "scripts" / "install-community-media-cleanup.sh").read_text(
            encoding="utf-8"
        )
        verifier = (SERVER_DIR / "scripts" / "verify-community-media-maintenance.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("Environment=COMMUNITY_WRITE_ENABLED=true", service)
        self.assertIn("ConditionPathIsDirectory=/var/lib/meetingnotes-stt/backend/community-media", service)
        self.assertIn("--lock-file", service)
        self.assertNotIn("--apply", service)
        self.assertIn("Persistent=true", timer)
        self.assertIn("systemd-analyze verify", installer)
        self.assertIn("enable --now meetingnotes-community-media-cleanup.timer", installer)
        self.assertIn("bash -n", verifier)
        self.assertIn("systemd-analyze verify", verifier)
        self.assertIn("--run-dry-run", verifier)
        self.assertNotIn("--apply", verifier)
        self.assertIn("cmp -s", verifier)
        self.assertIn("systemctl start meetingnotes-community-media-cleanup.service", verifier)
        self.assertIn("--retention-days 90", verifier)


if __name__ == "__main__":
    unittest.main()
