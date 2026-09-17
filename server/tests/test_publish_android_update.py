import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread


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

    def publish(self, version_code: int, payload: bytes, *, sha256: str | None = None, metadata_url: str = "", shell_setup: str = "") -> subprocess.CompletedProcess[str]:
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
                "-c",
                shell_setup + '\nsource "$@"',
                "publish-test",
                str(PUBLISH_SCRIPT),
                "--apk",
                str(self.apk),
                "--manifest",
                str(self.manifest),
                "--downloads-dir",
                str(self.downloads),
                "--config",
                str(self.config),
                *(["--metadata-url", metadata_url] if metadata_url else []),
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

    @contextmanager
    def public_channel(self, *, stale_metadata: bool = False, corrupt_apk: bool = False):
        owner = self
        observed_versions = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                observed_versions.append(owner.published_versions())
                metadata = json.loads(owner.config.read_text(encoding="utf-8"))
                if self.path == "/metadata":
                    metadata["download_url"] = f"http://127.0.0.1:{self.server.server_port}/apk"
                    if stale_metadata:
                        metadata["version_code"] -= 1
                    body = json.dumps(metadata).encode()
                else:
                    body = b"corrupted" if corrupt_apk else (owner.downloads / metadata["apk_filename"]).read_bytes()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}/metadata", observed_versions
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_public_health_checks_run_before_retiring_old_apks(self) -> None:
        for version in (100, 101):
            self.assertEqual(self.publish(version, f"apk-{version}".encode()).returncode, 0)
        with self.public_channel() as (url, observed):
            result = self.publish(102, b"apk-102", metadata_url=url)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(observed, [[100, 101, 102], [100, 101, 102]])
        self.assertEqual(self.published_versions(), [101, 102])

    def test_public_metadata_failure_restores_previous_release(self) -> None:
        self.assertEqual(self.publish(101, b"apk-101").returncode, 0)
        before = self.config.read_bytes()
        with self.public_channel(stale_metadata=True) as (url, _):
            result = self.publish(102, b"apk-102", metadata_url=url)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("public metadata mismatch", result.stderr)
        self.assertEqual(self.config.read_bytes(), before)
        self.assertEqual(self.published_versions(), [101])

    def test_public_apk_failure_keeps_both_old_releases(self) -> None:
        for version in (100, 101):
            self.assertEqual(self.publish(version, f"apk-{version}".encode()).returncode, 0)
        before = self.config.read_bytes()
        with self.public_channel(corrupt_apk=True) as (url, _):
            result = self.publish(102, b"apk-102", metadata_url=url)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("public APK sha256 mismatch", result.stderr)
        self.assertEqual(self.config.read_bytes(), before)
        self.assertEqual(self.published_versions(), [100, 101])

    def test_multiple_releases_keep_only_latest_and_previous_by_version_code(self) -> None:
        for version_code in (100, 101, 102):
            result = self.publish(version_code, f"apk-{version_code}".encode())
            self.assertEqual(result.returncode, 0, result.stderr)

        self.assertEqual(self.published_versions(), [101, 102])
        published = json.loads(self.config.read_text(encoding="utf-8"))
        self.assertEqual(published["version_code"], 102)
        self.assertEqual(published["apk_filename"], "ZhiWuBen-Android-102.apk")

    def test_retirement_failure_restores_manifest_and_already_moved_apks(self) -> None:
        for version in (100, 101):
            self.assertEqual(self.publish(version, f"apk-{version}".encode()).returncode, 0)
        before = self.config.read_bytes()
        legacy_apk = self.downloads / "ZhiWuBen-Android.apk"
        legacy_apk.write_bytes(b"legacy-apk")
        # A shell function also intercepts mv when Git Bash prepends /usr/bin to PATH.
        failing_mv = (
            'mv() {\n'
            'if [[ "$2" == */ZhiWuBen-Android.apk && "$3" == */.android-update-retain.*/* ]]; then\n'
            '  echo "simulated retirement failure" >&2\n'
            '  return 1\n'
            'fi\n'
            'command mv "$@"\n'
            '}\n'
        )

        with self.public_channel() as (url, _):
            result = self.publish(102, b"apk-102", metadata_url=url, shell_setup=failing_mv)

        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertIn("simulated retirement failure", result.stderr)
        self.assertEqual(self.config.read_bytes(), before)
        self.assertEqual(self.published_versions(), [100, 101])
        self.assertEqual((self.downloads / "ZhiWuBen-Android-100.apk").read_bytes(), b"apk-100")
        self.assertEqual(legacy_apk.read_bytes(), b"legacy-apk")

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

    def test_light_channel_uses_independent_package_and_state_paths(self) -> None:
        self.assertIn('[ValidateSet("social", "light")]', self.script)
        self.assertIn('/var/lib/meetingnotes-stt/downloads-light', self.script)
        self.assertIn('/var/lib/meetingnotes-stt/app-update-light.json', self.script)
        self.assertIn('com.oa.automation.light', self.script)


if __name__ == "__main__":
    unittest.main()
