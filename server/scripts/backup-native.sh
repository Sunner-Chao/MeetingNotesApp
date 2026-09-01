#!/usr/bin/env bash
set -Eeuo pipefail

APP_ROOT="/opt/meetingnotes-stt"
STATE_ROOT="/var/lib/meetingnotes-stt"
CONFIG_ROOT="/etc/meetingnotes-stt"
BACKUP_ROOT="/var/backups/meetingnotes-stt"
QUIET=0

if [[ "${1:-}" == "--quiet" ]]; then
  QUIET=1
fi
[[ $EUID -eq 0 ]] || { echo "Run as root (sudo)." >&2; exit 1; }

install -d -m 0750 "$BACKUP_ROOT"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RELEASE="$(basename "$(readlink -f "$APP_ROOT/current" 2>/dev/null || echo unknown)")"
ARCHIVE="${BACKUP_ROOT}/meetingnotes-${RELEASE}-${TIMESTAMP}.tar.gz"

STAGE="$(mktemp -d "${BACKUP_ROOT}/.backup.XXXXXX")"
cleanup() {
  rm -rf -- "$STAGE"
}
trap cleanup EXIT
INCLUDES=()
if [[ -f "$CONFIG_ROOT/stt.env" ]]; then
  install -D -m 0640 "$CONFIG_ROOT/stt.env" "$STAGE/etc/meetingnotes-stt/stt.env"
  INCLUDES+=("etc/meetingnotes-stt/stt.env")
fi
if [[ -f "$STATE_ROOT/app-update.json" ]]; then
  install -D -m 0644 "$STATE_ROOT/app-update.json" "$STAGE/var/lib/meetingnotes-stt/app-update.json"
  INCLUDES+=("var/lib/meetingnotes-stt/app-update.json")
fi
if [[ -f "$STATE_ROOT/downloads/ZhiWuBen-Android.apk" ]]; then
  install -D -m 0644 "$STATE_ROOT/downloads/ZhiWuBen-Android.apk" "$STAGE/var/lib/meetingnotes-stt/downloads/ZhiWuBen-Android.apk"
  INCLUDES+=("var/lib/meetingnotes-stt/downloads/ZhiWuBen-Android.apk")
fi
shopt -s nullglob
for APK_PATH in "$STATE_ROOT"/downloads/ZhiWuBen-Android-[0-9]*.apk; do
  APK_NAME="$(basename "$APK_PATH")"
  install -D -m 0644 "$APK_PATH" "$STAGE/var/lib/meetingnotes-stt/downloads/$APK_NAME"
  INCLUDES+=("var/lib/meetingnotes-stt/downloads/$APK_NAME")
done
if [[ -f "$STATE_ROOT/backend/meeting_notes.db" ]]; then
  install -d "$STAGE/var/lib/meetingnotes-stt/backend"
  python3.11 - "$STATE_ROOT/backend/meeting_notes.db" "$STAGE/var/lib/meetingnotes-stt/backend/meeting_notes.db" <<'PY'
import sqlite3
import sys

source = sqlite3.connect(sys.argv[1], timeout=30)
target = sqlite3.connect(sys.argv[2])
try:
    source.backup(target)
finally:
    target.close()
    source.close()
PY
  MEDIA_ROOT="$STATE_ROOT/backend/community-media"
  MANIFEST_PATH="$STAGE/var/lib/meetingnotes-stt/backend/community-media-manifest.json"
  install -d "$STAGE/var/lib/meetingnotes-stt/backend/community-media"
  if [[ -d "$MEDIA_ROOT" ]]; then
    cp -a "$MEDIA_ROOT/." "$STAGE/var/lib/meetingnotes-stt/backend/community-media/"
  fi
  ACCOUNT_MEDIA_ROOT="$STATE_ROOT/backend/account-media"
  if [[ -d "$ACCOUNT_MEDIA_ROOT" ]]; then
    cp -a "$ACCOUNT_MEDIA_ROOT/." "$STAGE/var/lib/meetingnotes-stt/backend/account-media/"
  fi
  if [[ -f "$STATE_ROOT/backend/accounts.db" ]]; then
    python3.11 - "$STATE_ROOT/backend/accounts.db" "$STAGE/var/lib/meetingnotes-stt/backend/accounts.db" <<'PY'
import sqlite3
import sys

source = sqlite3.connect(sys.argv[1], timeout=30)
target = sqlite3.connect(sys.argv[2])
try:
    source.backup(target)
finally:
    target.close()
    source.close()
PY
  fi
  python3.11 "$APP_ROOT/current/scripts/verify_community_backup.py" \
    "$STAGE/var/lib/meetingnotes-stt/backend/meeting_notes.db" \
    --media-root "$STAGE/var/lib/meetingnotes-stt/backend/community-media" \
    --write-manifest "$MANIFEST_PATH" >/dev/null
  INCLUDES+=("var/lib/meetingnotes-stt/backend")
fi
if [[ ${#INCLUDES[@]} -eq 0 ]]; then
  echo "Nothing to back up." >&2
  exit 1
fi
tar -czf "$ARCHIVE" -C "$STAGE" "${INCLUDES[@]}"
chmod 0640 "$ARCHIVE"

find "$BACKUP_ROOT" -maxdepth 1 -type f -name 'meetingnotes-*.tar.gz' -mtime +30 -delete
if [[ "$QUIET" -eq 0 ]]; then
  echo "$ARCHIVE"
fi
