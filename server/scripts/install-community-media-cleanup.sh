#!/usr/bin/env bash
set -Eeuo pipefail

APP_ROOT="/opt/meetingnotes-stt"
SYSTEMD_ROOT="/etc/systemd/system"
UNITS=(
  "meetingnotes-community-media-cleanup.service"
  "meetingnotes-community-media-cleanup.timer"
)

[[ $EUID -eq 0 ]] || { echo "Run as root (sudo)." >&2; exit 1; }
for unit in "${UNITS[@]}"; do
  source="${APP_ROOT}/current/systemd/${unit}"
  [[ -f "$source" ]] || { echo "Unit missing: ${unit}" >&2; exit 1; }
  install -m 0644 "$source" "${SYSTEMD_ROOT}/${unit}"
done

systemctl daemon-reload
systemctl enable --now meetingnotes-community-media-cleanup.timer >/dev/null
echo "[OK] Community media cleanup dry-run timer is enabled."
echo "[INFO] Apply cleanup manually only after backup review with COMMUNITY_WRITE_ENABLED=false."
