#!/usr/bin/env bash
set -Eeuo pipefail

APP_ROOT="/opt/meetingnotes-stt"
STATE_ROOT="/var/lib/meetingnotes-stt"
SYSTEMD_ROOT="/etc/systemd/system"
APP_USER="meetingnotes"
RUN_DRY_RUN=0
UNITS=(
  "meetingnotes-community-media-cleanup.service"
  "meetingnotes-community-media-cleanup.timer"
)

usage() {
  echo "Usage: verify-community-media-maintenance.sh [--run-dry-run]"
}

if [[ "${1:-}" == "--run-dry-run" ]]; then
  RUN_DRY_RUN=1
elif [[ $# -ne 0 ]]; then
  usage >&2
  exit 2
fi

[[ $EUID -eq 0 ]] || { echo "Run as root (sudo)." >&2; exit 1; }
[[ -L "${APP_ROOT}/current" ]] || { echo "Missing current release symlink." >&2; exit 1; }
[[ -L "${APP_ROOT}/current-venv" ]] || { echo "Missing current virtualenv symlink." >&2; exit 1; }
CURRENT="$(readlink -f "${APP_ROOT}/current")"

bash -n "${CURRENT}/scripts/install-community-media-cleanup.sh"
bash -n "${CURRENT}/scripts/verify-community-media-maintenance.sh"
for unit in "${UNITS[@]}"; do
  source="${CURRENT}/systemd/${unit}"
  installed="${SYSTEMD_ROOT}/${unit}"
  [[ -f "$source" ]] || { echo "Release unit is missing: ${unit}" >&2; exit 1; }
  [[ -f "$installed" ]] || { echo "Installed unit is missing: ${unit}" >&2; exit 1; }
  cmp -s "$source" "$installed" || {
    echo "Installed unit differs from current release: ${unit}" >&2
    exit 1
  }
done
systemd-analyze verify \
  "${SYSTEMD_ROOT}/meetingnotes-community-media-cleanup.service" \
  "${SYSTEMD_ROOT}/meetingnotes-community-media-cleanup.timer"
systemctl is-enabled meetingnotes-community-media-cleanup.timer
systemctl is-active meetingnotes-community-media-cleanup.timer
systemctl show meetingnotes-community-media-cleanup.timer \
  --property=NextElapseUSecRealtime \
  --no-pager

if [[ "$RUN_DRY_RUN" -eq 1 ]]; then
  [[ -f "${STATE_ROOT}/backend/meeting_notes.db" ]] || {
    echo "Community database is not available for a dry run." >&2
    exit 1
  }
  [[ -d "${STATE_ROOT}/backend/community-media" ]] || {
    echo "Community media directory is not available for a dry run." >&2
    exit 1
  }
  systemctl start meetingnotes-community-media-cleanup.service
  [[ "$(systemctl show meetingnotes-community-media-cleanup.service --property=Result --value)" == "success" ]] || {
    echo "Community media dry run did not complete successfully." >&2
    exit 1
  }
  runuser -u "$APP_USER" -- \
    "${APP_ROOT}/current-venv/bin/python" \
    "${CURRENT}/scripts/audit_community_media_quarantine.py" \
    "${STATE_ROOT}/backend/community-media-quarantine" \
    --retention-days 90
fi

echo "[OK] Community media maintenance preflight passed."
