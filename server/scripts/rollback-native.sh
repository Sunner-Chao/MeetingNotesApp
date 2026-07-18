#!/usr/bin/env bash
set -Eeuo pipefail

APP_ROOT="/opt/meetingnotes-stt"
CURRENT="$APP_ROOT/current"
CURRENT_VENV="$APP_ROOT/current-venv"
PREVIOUS="$APP_ROOT/previous"
PREVIOUS_VENV="$APP_ROOT/previous-venv"
CONFIG_FILE="/etc/meetingnotes-stt/stt.env"

[[ $EUID -eq 0 ]] || { echo "Run as root (sudo)." >&2; exit 1; }
[[ -L "$PREVIOUS" && -L "$PREVIOUS_VENV" ]] || { echo "No previous release is available." >&2; exit 1; }

atomic_link() {
  local target="$1"
  local link="$2"
  local temporary="${link}.new.$$"
  rm -f -- "$temporary"
  ln -s "$target" "$temporary"
  mv -Tf "$temporary" "$link"
}

OLD_CURRENT="$(readlink -f "$CURRENT")"
OLD_CURRENT_VENV="$(readlink -f "$CURRENT_VENV")"
TARGET="$(readlink -f "$PREVIOUS")"
TARGET_VENV="$(readlink -f "$PREVIOUS_VENV")"
[[ -f "$TARGET/systemd/meetingnotes-stt.service" ]] || { echo "Target STT unit is missing." >&2; exit 1; }
[[ -f "$TARGET/systemd/meetingnotes-backend.service" ]] || { echo "Target Backend unit is missing." >&2; exit 1; }
[[ -x "$TARGET_VENV/bin/python" ]] || { echo "Target virtualenv is invalid." >&2; exit 1; }

bash "$CURRENT/scripts/backup-native.sh" --quiet || true
systemctl stop meetingnotes-backend.service 2>/dev/null || true
systemctl stop meetingnotes-stt.service
atomic_link "$TARGET" "$CURRENT"
atomic_link "$TARGET_VENV" "$CURRENT_VENV"
atomic_link "$OLD_CURRENT" "$PREVIOUS"
atomic_link "$OLD_CURRENT_VENV" "$PREVIOUS_VENV"
install -m 0644 "$TARGET/systemd/meetingnotes-stt.service" /etc/systemd/system/meetingnotes-stt.service
install -m 0644 "$TARGET/systemd/meetingnotes-backend.service" /etc/systemd/system/meetingnotes-backend.service

ROLLBACK_OK=1
if ! systemctl daemon-reload; then ROLLBACK_OK=0; fi
if ! systemctl enable meetingnotes-stt.service >/dev/null; then ROLLBACK_OK=0; fi
if ! systemctl restart meetingnotes-stt.service; then ROLLBACK_OK=0; fi
if [[ -f "$TARGET/BACKEND_ENABLED" ]]; then
  if ! systemctl enable meetingnotes-backend.service >/dev/null; then ROLLBACK_OK=0; fi
  if ! systemctl restart meetingnotes-backend.service; then ROLLBACK_OK=0; fi
else
  systemctl disable --now meetingnotes-backend.service >/dev/null 2>&1 || true
fi
if [[ "$ROLLBACK_OK" -eq 1 ]]; then
  PORT="$(sed -n 's/^STT_PORT=//p' "$CONFIG_FILE" | tail -n1)"
  PORT="${PORT:-8888}"
  STT_READY=0
  for _ in $(seq 1 180); do
    if curl --fail --silent --max-time 5 "http://127.0.0.1:${PORT}/ready" >/dev/null; then
      STT_READY=1
      break
    fi
    sleep 5
  done
  if [[ "$STT_READY" -ne 1 ]]; then ROLLBACK_OK=0; fi
fi
if [[ "$ROLLBACK_OK" -eq 1 ]] && bash "$CURRENT/scripts/verify-native.sh"; then
  echo "[OK] Rolled back to $(cat "$CURRENT/RELEASE")."
  exit 0
fi

echo "[ERROR] Target release failed; restoring the release active before rollback." >&2
systemctl stop meetingnotes-backend.service 2>/dev/null || true
systemctl stop meetingnotes-stt.service 2>/dev/null || true
atomic_link "$OLD_CURRENT" "$CURRENT"
atomic_link "$OLD_CURRENT_VENV" "$CURRENT_VENV"
atomic_link "$TARGET" "$PREVIOUS"
atomic_link "$TARGET_VENV" "$PREVIOUS_VENV"
install -m 0644 "$OLD_CURRENT/systemd/meetingnotes-stt.service" /etc/systemd/system/meetingnotes-stt.service
install -m 0644 "$OLD_CURRENT/systemd/meetingnotes-backend.service" /etc/systemd/system/meetingnotes-backend.service
systemctl daemon-reload || true
systemctl enable meetingnotes-stt.service >/dev/null 2>&1 || true
systemctl restart meetingnotes-stt.service || true
if [[ -f "$OLD_CURRENT/BACKEND_ENABLED" ]]; then
  systemctl enable meetingnotes-backend.service >/dev/null 2>&1 || true
  systemctl restart meetingnotes-backend.service || true
else
  systemctl disable --now meetingnotes-backend.service >/dev/null 2>&1 || true
fi
exit 1
