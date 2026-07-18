#!/usr/bin/env bash
set -Eeuo pipefail

APP_USER="meetingnotes"
APP_GROUP="meetingnotes"
CONFIG_FILE="/etc/meetingnotes-stt/stt.env"
STATE_ROOT="/var/lib/meetingnotes-stt"

usage() {
  echo "Usage: restore-native.sh /var/backups/meetingnotes-stt/meetingnotes-*.tar.gz"
}

[[ $EUID -eq 0 ]] || { echo "Run as root (sudo)." >&2; exit 1; }
[[ $# -eq 1 ]] || { usage >&2; exit 2; }
ARCHIVE="$1"
[[ -f "$ARCHIVE" ]] || { echo "Backup not found: ${ARCHIVE}" >&2; exit 1; }

if tar -tzf "$ARCHIVE" | grep -Ev '^(etc/meetingnotes-stt/stt\.env|var/lib/meetingnotes-stt/backend(/.*)?|)$' >/dev/null; then
  echo "Backup contains a path outside the managed config/database directories." >&2
  exit 1
fi

BACKEND_ENABLED=0
if systemctl is-enabled meetingnotes-backend.service >/dev/null 2>&1; then
  BACKEND_ENABLED=1
fi
systemctl stop meetingnotes-backend.service 2>/dev/null || true
tar -xzf "$ARCHIVE" -C /

if [[ -f "$CONFIG_FILE" ]]; then
  chown root:"$APP_GROUP" "$CONFIG_FILE"
  chmod 0640 "$CONFIG_FILE"
fi
if [[ -d "$STATE_ROOT/backend" ]]; then
  chown -R "$APP_USER":"$APP_GROUP" "$STATE_ROOT/backend"
  chmod 0750 "$STATE_ROOT/backend"
fi

systemctl restart meetingnotes-stt.service
if [[ "$BACKEND_ENABLED" -eq 1 ]]; then
  systemctl restart meetingnotes-backend.service
fi
PORT="$(sed -n 's/^STT_PORT=//p' "$CONFIG_FILE" | tail -n1)"
PORT="${PORT:-8888}"
READY=0
for _ in $(seq 1 180); do
  if curl --fail --silent --max-time 5 "http://127.0.0.1:${PORT}/ready" >/dev/null; then
    READY=1
    break
  fi
  sleep 5
done
if [[ "$READY" -ne 1 ]]; then
  journalctl -u meetingnotes-stt.service -n 100 --no-pager >&2 || true
  exit 1
fi
bash /opt/meetingnotes-stt/current/scripts/verify-native.sh
echo "[OK] Restored ${ARCHIVE}."
