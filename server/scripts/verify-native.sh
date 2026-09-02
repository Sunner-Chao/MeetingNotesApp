#!/usr/bin/env bash
set -Eeuo pipefail

APP_ROOT="/opt/meetingnotes-stt"
STATE_ROOT="/var/lib/meetingnotes-stt"
CONFIG_FILE="/etc/meetingnotes-stt/stt.env"

[[ -f "$CONFIG_FILE" ]] || { echo "Missing ${CONFIG_FILE}" >&2; exit 1; }
[[ -L "$APP_ROOT/current" ]] || { echo "Missing current release symlink" >&2; exit 1; }
[[ -L "$APP_ROOT/current-venv" ]] || { echo "Missing current virtualenv symlink" >&2; exit 1; }

PORT="$(sed -n 's/^STT_PORT=//p' "$CONFIG_FILE" | tail -n1)"
PORT="${PORT:-8888}"
CURRENT="$(readlink -f "$APP_ROOT/current")"

ACCOUNT_MEDIA_DIR_VALUE="$(sed -n 's/^ACCOUNT_MEDIA_DIR=//p' "$CONFIG_FILE" | tail -n1)"
[[ -n "$ACCOUNT_MEDIA_DIR_VALUE" && "$ACCOUNT_MEDIA_DIR_VALUE" == "$STATE_ROOT/"* ]] || {
  echo "ACCOUNT_MEDIA_DIR must be inside the managed persistent state root." >&2
  exit 1
}
[[ -d "$ACCOUNT_MEDIA_DIR_VALUE" ]] || {
  echo "Missing account media directory: ${ACCOUNT_MEDIA_DIR_VALUE}" >&2
  exit 1
}
(cd "$STATE_ROOT/models" && sha256sum -c "$CURRENT/model-manifest.sha256")
"$APP_ROOT/current-venv/bin/python" -m pip check
systemctl is-enabled meetingnotes-stt.service
systemctl is-active meetingnotes-stt.service
systemctl show meetingnotes-stt.service \
  --property=MemoryHigh \
  --property=MemoryMax \
  --property=CPUQuotaPerSecUSec \
  --property=TasksMax \
  --no-pager
curl --fail --silent --show-error "http://127.0.0.1:${PORT}/ready"
echo
curl --fail --silent --show-error "http://127.0.0.1:${PORT}/health"
echo
if [[ -f "$CURRENT/BACKEND_ENABLED" ]]; then
  BACKEND_PORT="$(sed -n 's/^WEB_BACKEND_PORT=//p' "$CONFIG_FILE" | tail -n1)"
  BACKEND_PORT="${BACKEND_PORT:-8090}"
  systemctl is-enabled meetingnotes-backend.service
  systemctl is-active meetingnotes-backend.service
  curl --fail --silent --show-error "http://127.0.0.1:${BACKEND_PORT}/health"
  echo
  AGENT_TOKEN="$(sed -n 's/^AGENT_API_TOKEN=//p' "$CONFIG_FILE" | tail -n1)"
  [[ -n "$AGENT_TOKEN" ]] || { echo "Missing AGENT_API_TOKEN" >&2; exit 1; }
  curl --fail --silent --show-error \
    -H "Authorization: Bearer ${AGENT_TOKEN}" \
    "http://127.0.0.1:${BACKEND_PORT}/api/agent/health"
  echo

  UPDATE_CONFIG="$(sed -n 's/^APP_UPDATE_CONFIG_PATH=//p' "$CONFIG_FILE" | tail -n1)"
  UPDATE_CONFIG="${UPDATE_CONFIG:-$STATE_ROOT/app-update.json}"
  UPDATE_APK="$(sed -n 's/^APP_UPDATE_ANDROID_APK_PATH=//p' "$CONFIG_FILE" | tail -n1)"
  UPDATE_APK="${UPDATE_APK:-$STATE_ROOT/downloads/ZhiWuBen-Android.apk}"
  UPDATE_DOWNLOADS="$(dirname "$UPDATE_APK")"
  if compgen -G "$UPDATE_DOWNLOADS/ZhiWuBen-Android*.apk" >/dev/null; then
    "$APP_ROOT/current-venv/bin/python" - "$UPDATE_CONFIG" "$UPDATE_DOWNLOADS" "$BACKEND_PORT" <<'PY'
import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

config_path = Path(sys.argv[1])
downloads_dir = Path(sys.argv[2])
backend_port = int(sys.argv[3])
manifest = json.loads(config_path.read_text(encoding="utf-8"))
version_code = int(manifest["version_code"])
expected_sha256 = str(manifest.get("sha256", "")).strip().lower()
expected_filename = f"ZhiWuBen-Android-{version_code}.apk"
if manifest.get("apk_filename") != expected_filename:
    raise SystemExit("published Android manifest does not name the current versioned APK")

artifacts: list[tuple[int, Path]] = []
for artifact in downloads_dir.glob("ZhiWuBen-Android-*.apk"):
    match = re.fullmatch(r"ZhiWuBen-Android-([0-9]+)\.apk", artifact.name)
    if match:
        artifacts.append((int(match.group(1)), artifact))
artifacts.sort(reverse=True)
if not artifacts or artifacts[0][0] != version_code:
    raise SystemExit("latest retained APK does not match the published Android manifest")
if len(artifacts) > 2:
    raise SystemExit("more than two Android APK releases are retained")
if (downloads_dir / "ZhiWuBen-Android.apk").exists():
    raise SystemExit("legacy unversioned Android APK must not remain after publication")

base_url = f"http://127.0.0.1:{backend_port}"
metadata_request = urllib.request.Request(
    f"{base_url}/api/app-update/android",
    headers={"Cache-Control": "no-cache"},
)
with urllib.request.urlopen(metadata_request, timeout=15) as response:
    if response.headers.get("Cache-Control") != "no-store":
        raise SystemExit("Android update metadata is missing Cache-Control: no-store")
    metadata = json.load(response)
if int(metadata["version_code"]) != version_code:
    raise SystemExit("Android update endpoint does not expose the latest version")
if str(metadata.get("sha256") or "").lower() != expected_sha256:
    raise SystemExit("Android update endpoint SHA-256 differs from the published manifest")

digest = hashlib.sha256()
with urllib.request.urlopen(metadata["download_url"], timeout=60) as response:
    while chunk := response.read(1024 * 1024):
        digest.update(chunk)
if not expected_sha256 or digest.hexdigest() != expected_sha256:
    raise SystemExit("downloaded latest Android APK failed SHA-256 verification")

if len(artifacts) == 2:
    previous_version = artifacts[1][0]
    with urllib.request.urlopen(
        f"{base_url}/api/app-update/android/apk/{previous_version}",
        timeout=60,
    ) as response:
        if not response.read(1):
            raise SystemExit("retained previous Android APK is empty")

print(f"Android OTA verified: current={version_code}, retained={len(artifacts)}")
PY
  fi
fi

# Light Enjoy is an independent installable product and therefore has its own
# manifest, artifact directory, retention policy, and metadata endpoint.
LIGHT_UPDATE_CONFIG="$(sed -n 's/^APP_UPDATE_LIGHT_CONFIG_PATH=//p' "$CONFIG_FILE" | tail -n1)"
LIGHT_UPDATE_CONFIG="${LIGHT_UPDATE_CONFIG:-$STATE_ROOT/app-update-light.json}"
LIGHT_UPDATE_APK="$(sed -n 's/^APP_UPDATE_LIGHT_ANDROID_APK_PATH=//p' "$CONFIG_FILE" | tail -n1)"
LIGHT_UPDATE_APK="${LIGHT_UPDATE_APK:-$STATE_ROOT/downloads-light/ZhiWuBen-Android.apk}"
LIGHT_UPDATE_DOWNLOADS="$(dirname "$LIGHT_UPDATE_APK")"
if [[ -f "$CURRENT/BACKEND_ENABLED" && -f "$LIGHT_UPDATE_CONFIG" ]] && compgen -G "$LIGHT_UPDATE_DOWNLOADS/ZhiWuBen-Android-*.apk" >/dev/null; then
  "$APP_ROOT/current-venv/bin/python" - "$LIGHT_UPDATE_CONFIG" "$LIGHT_UPDATE_DOWNLOADS" "$BACKEND_PORT" <<'PY'
import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

config_path = Path(sys.argv[1])
downloads_dir = Path(sys.argv[2])
backend_port = int(sys.argv[3])
manifest = json.loads(config_path.read_text(encoding="utf-8"))
version_code = int(manifest["version_code"])
expected_sha256 = str(manifest.get("sha256", "")).strip().lower()
expected_filename = f"ZhiWuBen-Android-{version_code}.apk"
if manifest.get("apk_filename") != expected_filename:
    raise SystemExit("published Light Enjoy manifest does not name the current versioned APK")

artifacts: list[tuple[int, Path]] = []
for artifact in downloads_dir.glob("ZhiWuBen-Android-*.apk"):
    match = re.fullmatch(r"ZhiWuBen-Android-([0-9]+)\.apk", artifact.name)
    if match:
        artifacts.append((int(match.group(1)), artifact))
artifacts.sort(reverse=True)
if not artifacts or artifacts[0][0] != version_code:
    raise SystemExit("latest retained Light Enjoy APK does not match its manifest")
if len(artifacts) > 2:
    raise SystemExit("more than two Light Enjoy APK releases are retained")
if (downloads_dir / "ZhiWuBen-Android.apk").exists():
    raise SystemExit("legacy unversioned Light Enjoy APK must not remain after publication")

base_url = f"http://127.0.0.1:{backend_port}"
metadata_request = urllib.request.Request(
    f"{base_url}/api/app-update/android/light",
    headers={"Cache-Control": "no-cache"},
)
with urllib.request.urlopen(metadata_request, timeout=15) as response:
    if response.headers.get("Cache-Control") != "no-store":
        raise SystemExit("Light Enjoy metadata is missing Cache-Control: no-store")
    metadata = json.load(response)
if int(metadata["version_code"]) != version_code:
    raise SystemExit("Light Enjoy update endpoint does not expose the latest version")
if str(metadata.get("sha256") or "").lower() != expected_sha256:
    raise SystemExit("Light Enjoy endpoint SHA-256 differs from its manifest")

digest = hashlib.sha256()
with urllib.request.urlopen(metadata["download_url"], timeout=60) as response:
    while chunk := response.read(1024 * 1024):
        digest.update(chunk)
if not expected_sha256 or digest.hexdigest() != expected_sha256:
    raise SystemExit("downloaded Light Enjoy APK failed SHA-256 verification")

if len(artifacts) == 2:
    previous_version = artifacts[1][0]
    with urllib.request.urlopen(
        f"{base_url}/api/app-update/android/light/apk/{previous_version}",
        timeout=60,
    ) as response:
        if not response.read(1):
            raise SystemExit("retained previous Light Enjoy APK is empty")

print(f"Light Enjoy OTA verified: current={version_code}, retained={len(artifacts)}")
PY
fi
