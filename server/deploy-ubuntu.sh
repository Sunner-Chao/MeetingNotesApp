#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
INSTALLER="$SCRIPT_DIR/scripts/install-native.sh"
ARGS=(--source "$SCRIPT_DIR")

if [[ -d "$SCRIPT_DIR/models" ]]; then
  ARGS+=(--models-source "$SCRIPT_DIR/models")
fi

if [[ "${1:-}" == "cpu" ]]; then
  shift
elif [[ "${1:-}" == "gpu" ]]; then
  echo "The frozen 1.0.2 release is CPU-only; GPU mode is not supported." >&2
  exit 2
fi
ARGS+=("$@")

if [[ $EUID -eq 0 ]]; then
  exec bash "$INSTALLER" "${ARGS[@]}"
fi
command -v sudo >/dev/null 2>&1 || { echo "sudo is required." >&2; exit 1; }
exec sudo bash "$INSTALLER" "${ARGS[@]}"
