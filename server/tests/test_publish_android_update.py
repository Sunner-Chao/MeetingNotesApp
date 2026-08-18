import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PUBLISH_SCRIPT = PROJECT_ROOT / "server" / "scripts" / "publish-android-update.sh"
DEPLOY_SCRIPT = PROJECT_ROOT / "server" / "deploy-remote.ps1"


def find_bash() -> str | None:
    candidates = (
        (r"C:\Program Files\Git\bin\bash.exe", shutil.which("bash"))
        if os.name == "nt"
        else (shutil.which("bash"),)
    )
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    return None


@unittest.skipUnless(find_bash(), "bash is required to test the Android update publisher")
class PublishAndroidUpdateTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.downloads = self.root / "downloads"
        self.config = self.root / "app-update.json"
        self.apk = self.root / "release.apk"
        self.manifest = self.root / "manifest.json"
        self.test_bin = self.root / "test-bin"
        self.test_bin.mkdir()
        if os.name == "nt":
            flock = self.test_bin / "flock"
            flock.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="ascii")
            flock.chmod(0o755)
            python3 = self.test_bin / "python3"
            python3.write_text(
                f'#!/usr/bin/env bash\n"{Path(sys.executable).as_posix()}" "$@" | tr -d "\\r"\nexit ${{PIPESTATUS[0]}}\n',
                encoding="ascii",
            )
            python3.chmod(0o755)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def publish(self, version_code: int, payload: bytes, *, sha256: str | None = None) -> subprocess.CompletedProcess[str]:
        self.apk.write_bytes(payload)
        self.manifest.write_text(
            json.dumps(
                {
                    "version_code": version_code,
                    "version_name": f"1.2.{version_code}",
                    "sha256": sha256 if sha256 is not None else hashlib.sha256(payload).hexdigest(),
                }
            ),
            encoding="utf-8",
        )
        return subprocess.run(
            [
                find_bash(),
                str(PUBLISH_SCRIPT),
                "--apk",
                str(self.apk),
                "--manifest",
                str(self.manifest),
                "--downloads-dir",
                str(self.downloads),
                "--config",
                str(self.config),
            ],
            check=False,
            text=True,
            capture_output=True,
            env={
                **os.environ,
                "LC_ALL": "C",
                "PATH": f"{self.test_bin}{os.pathsep}{os.environ.get('PATH', '')}",
            },
        )

    def published_versions(self) -> list[int]:
        return sorted(
            int(path.stem.rsplit("-", 1)[1])
            for path in self.downloads.glob("ZhiWuBen-Android-*.apk")
        )

    def test_multiple_releases_keep_only_latest_and_previous_by_version_code(self) -> None:
        for version_code in (100, 101, 102):
            result = self.publish(version_code, f"apk-{version_code}".encode())
            self.assertEqual(result.returncode, 0, result.stderr)

        self.assertEqual(self.published_versions(), [101, 102])
        published = json.loads(self.config.read_text(encoding="utf-8"))
        self.assertEqual(published["version_code"], 102)
        self.assertEqual(published["apk_filename"], "ZhiWuBen-Android-102.apk")

    def test_same_or_lower_version_cannot_overwrite_current_release(self) -> None:
        self.assertEqual(self.publish(102, b"apk-102").returncode, 0)
        before_manifest = self.config.read_bytes()
        before_versions = self.published_versions()

        for version_code in (102, 101):
            result = self.publish(version_code, f"replacement-{version_code}".encode())
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("non-increasing version_code", result.stderr)
            self.assertEqual(self.config.read_bytes(), before_manifest)
            self.assertEqual(self.published_versions(), before_versions)

    def test_invalid_sha_leaves_current_manifest_and_artifacts_untouched(self) -> None:
        self.assertEqual(self.publish(102, b"apk-102").returncode, 0)
        before_manifest = self.config.read_bytes()
        before_artifact = (self.downloads / "ZhiWuBen-Android-102.apk").read_bytes()

        result = self.publish(103, b"apk-103", sha256="0" * 64)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("sha256 does not match", result.stderr)
        self.assertEqual(self.config.read_bytes(), before_manifest)
        self.assertEqual(self.published_versions(), [102])
        self.assertEqual((self.downloads / "ZhiWuBen-Android-102.apk").read_bytes(), before_artifact)


class DeployRemoteSafetyContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.script = DEPLOY_SCRIPT.read_text(encoding="utf-8")

    def test_android_build_tools_support_standard_windows_extensions(self) -> None:
        self.assertIn('@(".bat", ".cmd", ".exe")', self.script)

    def test_android_publish_and_backend_restart_short_circuit_on_failure(self) -> None:
        self.assertIn('" && ${Privilege}bash /opt/meetingnotes-stt/current/scripts/publish-android-update.sh "', self.script)
        self.assertIn('"--owner meetingnotes:meetingnotes --retain 2 && "', self.script)


if __name__ == "__main__":
    unittest.main()
