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
fi
