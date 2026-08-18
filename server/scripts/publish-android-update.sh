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

The OTA channel always retains exactly the latest release and its immediately
preceding release. Version codes must increase monotonically.

Options:
  --downloads-dir PATH  Directory for versioned APKs.
  --config PATH         Published update manifest path.
  --owner USER:GROUP    Ownership for the published files.
  --retain COUNT        Compatibility option; only 2 is accepted.
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
[[ "$RETAIN_COUNT" == "2" ]] || fail "The server-managed OTA channel always retains exactly 2 APKs"

if [[ -n "$OWNER" && ! "$OWNER" =~ ^[A-Za-z_][A-Za-z0-9_-]*:[A-Za-z_][A-Za-z0-9_-]*$ ]]; then
  fail "--owner must use USER:GROUP format"
fi

CONFIG_DIR="$(dirname "$CONFIG")"
mkdir -p "$DOWNLOADS_DIR" "$CONFIG_DIR"
if [[ -n "$OWNER" ]]; then
  chown "$OWNER" "$DOWNLOADS_DIR" "$CONFIG_DIR"
fi
chmod 0750 "$DOWNLOADS_DIR"

# Serializing publication prevents a slower upload from replacing a newer manifest.
exec 9>"$CONFIG_DIR/.android-update.publish.lock"
flock -x 9 || fail "Could not acquire the Android update publication lock"

CONFIG_STAGE="$(mktemp "$CONFIG_DIR/.app-update.XXXXXX.json")"
APK_STAGE="$(mktemp "$DOWNLOADS_DIR/.android-update.XXXXXX.apk")"
QUARANTINE_DIR=""
PUBLISHED_APK=""
PUBLISHED=0
cleanup() {
  local status=$?
  set +e
  rm -f -- "$CONFIG_STAGE" "$APK_STAGE"
  if [[ "$PUBLISHED" -ne 1 ]]; then
    [[ -n "$PUBLISHED_APK" ]] && rm -f -- "$PUBLISHED_APK"
    if [[ -n "$QUARANTINE_DIR" && -d "$QUARANTINE_DIR" ]]; then
      shopt -s nullglob
      for archived in "$QUARANTINE_DIR"/*; do
        mv -f -- "$archived" "$DOWNLOADS_DIR/"
      done
    fi
  fi
  [[ -n "$QUARANTINE_DIR" ]] && rm -rf -- "$QUARANTINE_DIR"
  exit "$status"
}
trap cleanup EXIT

IFS=$'\t' read -r VERSION_CODE APK_FILENAME < <(
  python3 - "$MANIFEST" "$APK" "$CONFIG_STAGE" "$CONFIG" <<'PY'
import hashlib
import json
import os
import re
import sys
from pathlib import Path

manifest_path, apk_path, output_path, current_path = map(Path, sys.argv[1:])
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

if current_path.exists():
    try:
        current = json.loads(current_path.read_text(encoding="utf-8"))
        current_version = int(current["version_code"])
    except (OSError, json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
        raise SystemExit(f"existing published manifest is invalid: {exc}")
    if version_code <= current_version:
        raise SystemExit(
            f"refusing non-increasing version_code {version_code}; current published version is {current_version}"
        )

sha256 = hashlib.sha256(apk_path.read_bytes()).hexdigest()
expected = str(manifest.get("sha256", "")).strip().lower()
if expected and (not re.fullmatch(r"[0-9a-f]{64}", expected) or expected != sha256):
    raise SystemExit("manifest sha256 does not match the APK")

apk_filename = f"ZhiWuBen-Android-{version_code}.apk"
manifest["sha256"] = sha256
manifest["apk_filename"] = apk_filename
with output_path.open("w", encoding="utf-8") as output:
    json.dump(manifest, output, ensure_ascii=False, indent=2)
    output.write("\n")
    output.flush()
    os.fsync(output.fileno())
print(f"{version_code}\t{apk_filename}")
PY
)

[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || fail "Manifest validation did not return a version code"
[[ "$APK_FILENAME" =~ ^ZhiWuBen-Android-[1-9][0-9]*\.apk$ ]] || fail "Manifest validation returned an unsafe APK name"
TARGET_APK="$DOWNLOADS_DIR/$APK_FILENAME"
[[ ! -e "$TARGET_APK" ]] || fail "APK version $VERSION_CODE already exists; publish a newer version_code instead"

mapfile -t EXISTING_APKS < <(
  find "$DOWNLOADS_DIR" -maxdepth 1 -type f -regextype posix-extended \
    -regex '.*/ZhiWuBen-Android-[0-9]+\.apk' -printf '%f\n'
)
for existing in "${EXISTING_APKS[@]}"; do
  existing_code="${existing#ZhiWuBen-Android-}"
  existing_code="${existing_code%.apk}"
  (( existing_code < VERSION_CODE )) || fail "Found version $existing_code that is not older than $VERSION_CODE"
done

install -m 0644 "$APK" "$APK_STAGE"
sync -f "$APK_STAGE"
sync -f "$CONFIG_STAGE"
if [[ -n "$OWNER" ]]; then
  chown "$OWNER" "$APK_STAGE" "$CONFIG_STAGE"
fi

# Move obsolete artifacts aside before publishing. The trap restores them if
# staging fails, so a bad release leaves the old manifest and retained APKs intact.
QUARANTINE_DIR="$(mktemp -d "$DOWNLOADS_DIR/.android-update-retain.XXXXXX")"
mapfile -t RETAINED_APKS < <(
  {
    printf '%s\n' "$APK_FILENAME"
    printf '%s\n' "${EXISTING_APKS[@]}"
  } | awk -F'[-.]' '{ print $(NF-1) "\t" $0 }' | sort -rn | head -n 2 | cut -f2-
)
for existing in "${EXISTING_APKS[@]}"; do
  if [[ ! " ${RETAINED_APKS[*]} " =~ " ${existing} " ]]; then
    mv -- "$DOWNLOADS_DIR/$existing" "$QUARANTINE_DIR/$existing"
  fi
done
if [[ -f "$DOWNLOADS_DIR/ZhiWuBen-Android.apk" ]]; then
  mv -- "$DOWNLOADS_DIR/ZhiWuBen-Android.apk" "$QUARANTINE_DIR/ZhiWuBen-Android.apk"
fi

mv -- "$APK_STAGE" "$TARGET_APK"
PUBLISHED_APK="$TARGET_APK"
sync -f "$TARGET_APK"
sync -f "$DOWNLOADS_DIR"
mv -f -- "$CONFIG_STAGE" "$CONFIG"
PUBLISHED=1
sync -f "$CONFIG"
sync -f "$CONFIG_DIR"

rm -rf -- "$QUARANTINE_DIR"
QUARANTINE_DIR=""
find "$DOWNLOADS_DIR" -maxdepth 1 -type f -name '.android-update.*.apk' -delete

echo "Published Android update ${VERSION_CODE}; retaining latest and previous APK releases."
