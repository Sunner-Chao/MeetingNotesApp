#!/usr/bin/env bash
set -Eeuo pipefail

APK=""
MANIFEST=""
DOWNLOADS_DIR="/var/lib/meetingnotes-stt/downloads"
CONFIG="/var/lib/meetingnotes-stt/app-update.json"
OWNER=""
RETAIN_COUNT=2

usage() {
  cat <<'EOF'
Usage: publish-android-update.sh --apk PATH --manifest PATH [options]

Options:
  --downloads-dir PATH  Directory for versioned APKs.
  --config PATH         Published update manifest path.
  --owner USER:GROUP    Ownership for the published files.
  --retain COUNT        Keep the newest COUNT APKs, including the current one (default: 2).
EOF
}

fail() {
  echo "Android update publish failed: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) APK="${2:-}"; shift 2 ;;
    --manifest) MANIFEST="${2:-}"; shift 2 ;;
    --downloads-dir) DOWNLOADS_DIR="${2:-}"; shift 2 ;;
    --config) CONFIG="${2:-}"; shift 2 ;;
    --owner) OWNER="${2:-}"; shift 2 ;;
    --retain) RETAIN_COUNT="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "Unknown argument: $1" ;;
  esac
done

[[ -f "$APK" && -s "$APK" ]] || fail "APK is missing or empty"
[[ -f "$MANIFEST" ]] || fail "Manifest is missing"
[[ "$RETAIN_COUNT" =~ ^[1-9][0-9]*$ ]] || fail "--retain must be at least 1"

if [[ -n "$OWNER" && ! "$OWNER" =~ ^[A-Za-z_][A-Za-z0-9_-]*:[A-Za-z_][A-Za-z0-9_-]*$ ]]; then
  fail "--owner must use USER:GROUP format"
fi

CONFIG_DIR="$(dirname "$CONFIG")"
mkdir -p "$DOWNLOADS_DIR" "$CONFIG_DIR"
if [[ -n "$OWNER" ]]; then
  chown "$OWNER" "$DOWNLOADS_DIR" "$CONFIG_DIR"
fi
chmod 0750 "$DOWNLOADS_DIR"

CONFIG_STAGE="$(mktemp "$CONFIG_DIR/.app-update.XXXXXX.json")"
APK_STAGE="$(mktemp "$DOWNLOADS_DIR/.android-update.XXXXXX.apk")"
cleanup() {
  rm -f -- "$CONFIG_STAGE" "$APK_STAGE"
}
trap cleanup EXIT

IFS=$'\t' read -r VERSION_CODE APK_FILENAME < <(
  python3 - "$MANIFEST" "$APK" "$CONFIG_STAGE" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path

manifest_path, apk_path, output_path = map(Path, sys.argv[1:])
try:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError) as exc:
    raise SystemExit(f"invalid manifest: {exc}")
if not isinstance(manifest, dict):
    raise SystemExit("manifest must be a JSON object")
try:
    version_code = int(manifest["version_code"])
except (KeyError, TypeError, ValueError) as exc:
    raise SystemExit("manifest version_code must be a positive integer") from exc
if version_code <= 0:
    raise SystemExit("manifest version_code must be a positive integer")
if not str(manifest.get("version_name", "")).strip():
    raise SystemExit("manifest version_name is required")

sha256 = hashlib.sha256(apk_path.read_bytes()).hexdigest()
expected = str(manifest.get("sha256", "")).strip().lower()
if expected and (not re.fullmatch(r"[0-9a-f]{64}", expected) or expected != sha256):
    raise SystemExit("manifest sha256 does not match the APK")

apk_filename = f"ZhiWuBen-Android-{version_code}.apk"
manifest["sha256"] = sha256
manifest["apk_filename"] = apk_filename
output_path.write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(f"{version_code}\t{apk_filename}")
PY
)

[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || fail "Manifest validation did not return a version code"
[[ "$APK_FILENAME" =~ ^ZhiWuBen-Android-[1-9][0-9]*\.apk$ ]] || fail "Manifest validation returned an unsafe APK name"

install -m 0644 "$APK" "$APK_STAGE"
if [[ -n "$OWNER" ]]; then
  chown "$OWNER" "$APK_STAGE" "$CONFIG_STAGE"
fi
mv -f "$APK_STAGE" "$DOWNLOADS_DIR/$APK_FILENAME"
mv -f "$CONFIG_STAGE" "$CONFIG"

mapfile -t VERSIONED_APKS < <(
  find "$DOWNLOADS_DIR" -maxdepth 1 -type f -name 'ZhiWuBen-Android-*.apk' ! -name "$APK_FILENAME" -printf '%T@ %p\n' |
    sort -rn |
    sed 's/^[^ ]* //'
)
KEEP_PREVIOUS=$((RETAIN_COUNT - 1))
for ((index = KEEP_PREVIOUS; index < ${#VERSIONED_APKS[@]}; index++)); do
  rm -f -- "${VERSIONED_APKS[$index]}"
done
rm -f -- "$DOWNLOADS_DIR/ZhiWuBen-Android.apk"
find "$DOWNLOADS_DIR" -maxdepth 1 -type f -name '.android-update.*.apk' -delete

echo "Published Android update ${VERSION_CODE}; retaining ${RETAIN_COUNT} APK release(s)."
