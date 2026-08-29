#!/usr/bin/env bash
set -Eeuo pipefail

APP_NAME="meetingnotes-stt"
APP_USER="meetingnotes"
APP_GROUP="meetingnotes"
APP_ROOT="/opt/${APP_NAME}"
STATE_ROOT="/var/lib/${APP_NAME}"
CONFIG_ROOT="/etc/${APP_NAME}"
CONFIG_FILE="${CONFIG_ROOT}/stt.env"
BACKUP_ROOT="/var/backups/${APP_NAME}"

SOURCE_DIR=""
ARCHIVE=""
MODELS_SOURCE=""
MODELS_ARCHIVE=""
INPUT_CONFIG=""
RELEASE_ID=""
WITH_BACKEND=0
SKIP_PACKAGES=0
OPEN_FIREWALL=0

usage() {
  cat <<'EOF'
Usage: install-native.sh [options]

Exactly one source option is required:
  --source DIR             Install from an unpacked server directory
  --archive FILE           Install from a .tar.gz release archive

Optional:
  --release-id ID          Immutable release id (default: VERSION-timestamp)
  --models-source DIR      Import the local models directory when needed
  --models-archive FILE    Import a models .tar archive when needed
  --config FILE            Replace the managed production environment file
  --with-backend           Enable the optional Backend Service
  --skip-packages          Do not run apt package installation
  --open-firewall          Add an allow rule for STT_PORT when UFW is active
EOF
}

fail() {
  echo "[ERROR] $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source) SOURCE_DIR="${2:?missing value}"; shift 2 ;;
    --archive) ARCHIVE="${2:?missing value}"; shift 2 ;;
    --release-id) RELEASE_ID="${2:?missing value}"; shift 2 ;;
    --models-source) MODELS_SOURCE="${2:?missing value}"; shift 2 ;;
    --models-archive) MODELS_ARCHIVE="${2:?missing value}"; shift 2 ;;
    --config) INPUT_CONFIG="${2:?missing value}"; shift 2 ;;
    --with-backend) WITH_BACKEND=1; shift ;;
    --skip-packages) SKIP_PACKAGES=1; shift ;;
    --open-firewall) OPEN_FIREWALL=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "Unknown option: $1" ;;
  esac
done

[[ $EUID -eq 0 ]] || fail "Run this installer as root (sudo)."
[[ -n "$SOURCE_DIR" || -n "$ARCHIVE" ]] || fail "Use --source or --archive."
[[ -z "$SOURCE_DIR" || -z "$ARCHIVE" ]] || fail "Use only one of --source or --archive."

if [[ -r /etc/os-release ]]; then
  # shellcheck disable=SC1091
  source /etc/os-release
fi
[[ "${ID:-}" == "ubuntu" ]] || fail "This frozen release supports Ubuntu only."

CPU_COUNT="$(nproc)"
MEMORY_MB="$(awk '/MemTotal/ {print int($2 / 1024)}' /proc/meminfo)"
DISK_MB="$(df -Pm /opt | awk 'NR == 2 {print $4}')"
# The multi-GB headroom only protects the model import, which cannot run unless the
# caller supplies a models source or archive. A code-only deploy needs the release
# tree plus one Python venv, so hold it to a threshold sized for that.
if [[ -n "$MODELS_ARCHIVE" || -n "$MODELS_SOURCE" ]]; then
  REQUIRED_DISK_MB=6144
  DISK_SCOPE="release, venv and model import"
else
  REQUIRED_DISK_MB=2048
  DISK_SCOPE="release and venv only"
fi
[[ "$CPU_COUNT" -ge 4 ]] || fail "At least 4 CPU cores are required; found ${CPU_COUNT}."
[[ "$MEMORY_MB" -ge 3500 ]] || fail "At least 3500 MB RAM is required; found ${MEMORY_MB} MB."
[[ "$DISK_MB" -ge "$REQUIRED_DISK_MB" ]] || fail "At least ${REQUIRED_DISK_MB} MB free disk is required for ${DISK_SCOPE}; found ${DISK_MB} MB."
echo "[PREFLIGHT] Ubuntu ${VERSION_ID:-unknown}, CPU=${CPU_COUNT}, RAM=${MEMORY_MB}MB, free=${DISK_MB}MB, required=${REQUIRED_DISK_MB}MB (${DISK_SCOPE})"

export DEBIAN_FRONTEND=noninteractive
if [[ "$SKIP_PACKAGES" -eq 0 ]]; then
  apt-get update
  apt-get install -y --no-install-recommends ca-certificates curl ffmpeg libgomp1 libsndfile1 software-properties-common
  if ! command -v python3.11 >/dev/null 2>&1; then
    add-apt-repository -y ppa:deadsnakes/ppa
    apt-get update
  fi
  apt-get install -y --no-install-recommends python3.11 python3.11-venv
fi
command -v python3.11 >/dev/null 2>&1 || fail "Python 3.11 is unavailable."

if ! getent group "$APP_GROUP" >/dev/null; then
  groupadd --system "$APP_GROUP"
fi
if ! id "$APP_USER" >/dev/null 2>&1; then
  useradd --system --gid "$APP_GROUP" --home-dir "$STATE_ROOT" --shell /usr/sbin/nologin "$APP_USER"
fi

install -d -m 0755 "$APP_ROOT" "$APP_ROOT/releases" "$APP_ROOT/venvs"
install -d -o "$APP_USER" -g "$APP_GROUP" -m 0750 "$STATE_ROOT" "$STATE_ROOT/models" "$STATE_ROOT/tmp" "$STATE_ROOT/backend" "$STATE_ROOT/logs" "$STATE_ROOT/data" "$STATE_ROOT/downloads"
install -d -o "$APP_USER" -g "$APP_GROUP" -m 0700 "$STATE_ROOT/agent-tasks" "$STATE_ROOT/audio-archive" "$STATE_ROOT/.codex" "$STATE_ROOT/.claude"
install -d -m 0750 "$CONFIG_ROOT" "$BACKUP_ROOT"

WORK_DIR="$(mktemp -d "${APP_ROOT}/.install.XXXXXX")"
cleanup() {
  rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT
STAGE_DIR="${WORK_DIR}/release"
mkdir -p "$STAGE_DIR"
OLD_RELEASE=""
OLD_VENV=""
if [[ -L "$APP_ROOT/current" ]]; then
  OLD_RELEASE="$(readlink -e "$APP_ROOT/current" 2>/dev/null || true)"
fi
if [[ -L "$APP_ROOT/current-venv" ]]; then
  OLD_VENV="$(readlink -e "$APP_ROOT/current-venv" 2>/dev/null || true)"
fi
OLD_BACKEND_ENABLED=0
if systemctl is-enabled meetingnotes-backend.service >/dev/null 2>&1; then
  OLD_BACKEND_ENABLED=1
fi
CONFIG_SNAPSHOT=""
if [[ -f "$CONFIG_FILE" ]]; then
  CONFIG_SNAPSHOT="${WORK_DIR}/stt.env.previous"
  cp -a "$CONFIG_FILE" "$CONFIG_SNAPSHOT"
fi
if [[ -n "$OLD_RELEASE" && -f "$OLD_RELEASE/scripts/backup-native.sh" ]]; then
  bash "$OLD_RELEASE/scripts/backup-native.sh" --quiet || true
fi

validate_archive() {
  local archive="$1"
  if tar -tf "$archive" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    fail "Archive contains an unsafe path: ${archive}"
  fi
}

if [[ -n "$ARCHIVE" ]]; then
  [[ -f "$ARCHIVE" ]] || fail "Release archive not found: ${ARCHIVE}"
  validate_archive "$ARCHIVE"
  tar -xf "$ARCHIVE" -C "$STAGE_DIR"
else
  [[ -d "$SOURCE_DIR" ]] || fail "Source directory not found: ${SOURCE_DIR}"
  tar \
    --exclude='./.env' \
    --exclude='./models' \
    --exclude='./data' \
    --exclude='./logs' \
    --exclude='./.venv*' \
    --exclude='./stt-service/pip' \
    --exclude='./stt-service/.switch_pending' \
    --exclude='./tunnel_*.txt' \
    --exclude='./tunnel_*.log' \
    --exclude='./*/runtime' \
    --exclude='./*/__pycache__' \
    -C "$SOURCE_DIR" -cf - . | tar -C "$STAGE_DIR" -xf -
fi

[[ -f "$STAGE_DIR/VERSION" ]] || fail "VERSION is missing from the release."
VERSION="$(tr -d '[:space:]' < "$STAGE_DIR/VERSION")"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Invalid VERSION: ${VERSION}"
if [[ -z "$RELEASE_ID" ]]; then
  RELEASE_ID="${VERSION}-$(date -u +%Y%m%d%H%M%S)"
fi
[[ "$RELEASE_ID" =~ ^[A-Za-z0-9._+-]+$ ]] || fail "Invalid release id: ${RELEASE_ID}"
[[ "$RELEASE_ID" == "${VERSION}"* ]] || fail "Release id must start with VERSION (${VERSION})."

FINAL_RELEASE="${APP_ROOT}/releases/${RELEASE_ID}"
FINAL_VENV="${APP_ROOT}/venvs/${RELEASE_ID}"
[[ ! -e "$FINAL_RELEASE" ]] || fail "Release already exists and is immutable: ${FINAL_RELEASE}"
[[ ! -e "$FINAL_VENV" ]] || fail "Virtualenv already exists: ${FINAL_VENV}"
printf '%s\n' "$RELEASE_ID" > "$STAGE_DIR/RELEASE"
if [[ "$WITH_BACKEND" -eq 1 ]]; then
  touch "$STAGE_DIR/BACKEND_ENABLED"
fi

MODEL_STAGE=""
model_is_valid() {
  [[ -d "$STATE_ROOT/models" ]] && (cd "$STATE_ROOT/models" && sha256sum -c "$STAGE_DIR/model-manifest.sha256" >/dev/null 2>&1)
}

if ! model_is_valid; then
  MODEL_STAGE="${WORK_DIR}/models"
  mkdir -p "$MODEL_STAGE"
  if [[ -n "$MODELS_ARCHIVE" ]]; then
    [[ -f "$MODELS_ARCHIVE" ]] || fail "Models archive not found: ${MODELS_ARCHIVE}"
    validate_archive "$MODELS_ARCHIVE"
    tar -xf "$MODELS_ARCHIVE" -C "$MODEL_STAGE"
  elif [[ -n "$MODELS_SOURCE" ]]; then
    [[ -d "$MODELS_SOURCE" ]] || fail "Models source not found: ${MODELS_SOURCE}"
    cp -a "$MODELS_SOURCE/." "$MODEL_STAGE/"
  else
    fail "The frozen STT models are missing or invalid; provide --models-source or --models-archive."
  fi
  (cd "$MODEL_STAGE" && sha256sum -c "$STAGE_DIR/model-manifest.sha256")
fi

mv "$STAGE_DIR" "$FINAL_RELEASE"
chown -R root:root "$FINAL_RELEASE"
chmod -R go-w "$FINAL_RELEASE"

python3.11 -m venv "$FINAL_VENV"
"$FINAL_VENV/bin/python" -m pip install --disable-pip-version-check --no-cache-dir -r "$FINAL_RELEASE/stt-service/requirements-core.lock.txt"
if [[ "$WITH_BACKEND" -eq 1 ]]; then
  "$FINAL_VENV/bin/python" -m pip install --disable-pip-version-check --no-cache-dir -r "$FINAL_RELEASE/backend-service/requirements.lock.txt"
fi
"$FINAL_VENV/bin/python" -m pip check
"$FINAL_VENV/bin/python" -c 'import ctranslate2, fastapi, faster_whisper, tencentcloud.asr.v20190614, uvicorn; print("[PYTHON] runtime imports OK")'
chown -R root:root "$FINAL_VENV"
chmod -R go-w "$FINAL_VENV"

generate_token() {
  python3.11 -c 'import secrets; print(secrets.token_hex(32))'
}

set_env_value() {
  local key="$1"
  local value="$2"
  if grep -q "^${key}=" "$CONFIG_FILE"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$CONFIG_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$CONFIG_FILE"
  fi
}

if [[ -n "$INPUT_CONFIG" ]]; then
  [[ -f "$INPUT_CONFIG" ]] || fail "Config file not found: ${INPUT_CONFIG}"
  install -m 0640 -o root -g "$APP_GROUP" "$INPUT_CONFIG" "$CONFIG_FILE"
elif [[ ! -f "$CONFIG_FILE" ]]; then
  install -m 0640 -o root -g "$APP_GROUP" "$FINAL_RELEASE/config/stt.env.example" "$CONFIG_FILE"
fi
APP_UPDATE_CONFIG_FILE="${STATE_ROOT}/app-update.json"
if [[ ! -f "$APP_UPDATE_CONFIG_FILE" && -f "$FINAL_RELEASE/config/app-update.json" ]]; then
  install -m 0644 -o "$APP_USER" -g "$APP_GROUP" "$FINAL_RELEASE/config/app-update.json" "$APP_UPDATE_CONFIG_FILE"
fi
sed -i 's/\r$//' "$CONFIG_FILE"
if ! grep -Eq '^STT_API_TOKEN=.+$' "$CONFIG_FILE"; then
  set_env_value STT_API_TOKEN "$(generate_token)"
fi
if ! grep -Eq '^WEB_API_TOKEN=.+$' "$CONFIG_FILE"; then
  set_env_value WEB_API_TOKEN "$(generate_token)"
fi
if ! grep -Eq '^WEB_API_USERNAME=.+$' "$CONFIG_FILE"; then
  set_env_value WEB_API_USERNAME "admin"
fi
if ! grep -Eq '^AGENT_API_TOKEN=.+$' "$CONFIG_FILE"; then
  set_env_value AGENT_API_TOKEN "$(generate_token)"
fi
if ! grep -Eq '^AGENT_ENABLED=.+$' "$CONFIG_FILE"; then
  set_env_value AGENT_ENABLED "1"
fi
if ! grep -Eq '^AGENT_DEFAULT_REQUEST_LIMIT=.+$' "$CONFIG_FILE"; then
  set_env_value AGENT_DEFAULT_REQUEST_LIMIT "1000"
fi
if ! grep -Eq '^AGENT_MAX_TEXT_CHARS=.+$' "$CONFIG_FILE"; then
  set_env_value AGENT_MAX_TEXT_CHARS "0"
fi
if ! grep -Eq '^AGENT_MAX_REQUEST_JSON_BYTES=.+$' "$CONFIG_FILE"; then
  set_env_value AGENT_MAX_REQUEST_JSON_BYTES "0"
fi
if ! grep -Eq '^ACCOUNT_TOKEN_SECRET=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_TOKEN_SECRET "$(generate_token)"
fi
if ! grep -Eq '^ACCOUNT_ADMIN_USERNAME=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_ADMIN_USERNAME "admin"
fi
if ! grep -Eq '^ACCOUNT_ADMIN_REQUEST_LIMIT=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_ADMIN_REQUEST_LIMIT "10000000"
fi
if ! grep -Eq '^ACCOUNT_SESSION_TTL_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_SESSION_TTL_SEC "2592000"
fi
if ! grep -Eq '^ACCOUNT_FREE_REQUEST_LIMIT=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_FREE_REQUEST_LIMIT "10"
fi
if ! grep -Eq '^ACCOUNT_FREE_PLAN_CODE=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_FREE_PLAN_CODE "free"
fi
if ! grep -Eq '^ACCOUNT_DB_PATH=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_DB_PATH "$STATE_ROOT/backend/accounts.db"
fi
CURRENT_ACCOUNT_FREE_PLAN_NAME="$(sed -n 's/^ACCOUNT_FREE_PLAN_NAME=//p' "$CONFIG_FILE" | tail -n1)"
if [[ -z "$CURRENT_ACCOUNT_FREE_PLAN_NAME" || "$CURRENT_ACCOUNT_FREE_PLAN_NAME" == "Free" ]]; then
  set_env_value ACCOUNT_FREE_PLAN_NAME "免费账户"
fi
CURRENT_ACCOUNT_FREE_STT_MINUTES="$(sed -n 's/^ACCOUNT_FREE_STT_MINUTES=//p' "$CONFIG_FILE" | tail -n1)"
if [[ -z "$CURRENT_ACCOUNT_FREE_STT_MINUTES" || "$CURRENT_ACCOUNT_FREE_STT_MINUTES" == "120" ]]; then
  set_env_value ACCOUNT_FREE_STT_MINUTES "0"
fi
CURRENT_ACCOUNT_FREE_AI_CREDITS="$(sed -n 's/^ACCOUNT_FREE_AI_CREDITS=//p' "$CONFIG_FILE" | tail -n1)"
if [[ -z "$CURRENT_ACCOUNT_FREE_AI_CREDITS" || "$CURRENT_ACCOUNT_FREE_AI_CREDITS" == "5" ]]; then
  set_env_value ACCOUNT_FREE_AI_CREDITS "0"
fi
if ! grep -Eq '^ACCOUNT_FREE_POINTS=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_FREE_POINTS "1000"
fi
if ! grep -Eq '^ACCOUNT_STT_POINTS_PER_MINUTE=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_STT_POINTS_PER_MINUTE "10"
fi
if ! grep -Eq '^ACCOUNT_AI_SUMMARY_POINTS=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_AI_SUMMARY_POINTS "30"
fi
if ! grep -Eq '^ACCOUNT_AI_CHAT_POINTS=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_AI_CHAT_POINTS "10"
fi
if ! grep -Eq '^ACCOUNT_STT_TOKEN_TTL_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value ACCOUNT_STT_TOKEN_TTL_SEC "43200"
fi
# Alipay stays off until the operator injects production credentials. Pinning the
# environment to production also stops this host from ever reading a sandbox key file.
if ! grep -Eq '^ALIPAY_ENABLED=.+$' "$CONFIG_FILE"; then
  set_env_value ALIPAY_ENABLED "false"
fi
if ! grep -Eq '^ALIPAY_ENVIRONMENT=.+$' "$CONFIG_FILE"; then
  set_env_value ALIPAY_ENVIRONMENT "production"
fi
if ! grep -Eq '^STT_LOG_PATH=.+$' "$CONFIG_FILE"; then
  set_env_value STT_LOG_PATH "$STATE_ROOT/logs/stt.log"
fi
if ! grep -Eq '^APP_UPDATE_CONFIG_PATH=.+$' "$CONFIG_FILE" || \
  grep -Eq '^APP_UPDATE_CONFIG_PATH=/etc/meetingnotes-stt/app-update\.json$' "$CONFIG_FILE"; then
  set_env_value APP_UPDATE_CONFIG_PATH "$APP_UPDATE_CONFIG_FILE"
fi
if ! grep -Eq '^APP_UPDATE_ANDROID_APK_PATH=.+$' "$CONFIG_FILE"; then
  set_env_value APP_UPDATE_ANDROID_APK_PATH "$STATE_ROOT/downloads/ZhiWuBen-Android.apk"
fi
if ! grep -Eq '^STT_AUDIO_ARCHIVE_ENABLED=.+$' "$CONFIG_FILE"; then
  set_env_value STT_AUDIO_ARCHIVE_ENABLED "1"
fi
if ! grep -Eq '^STT_AUDIO_ARCHIVE_DIR=.+$' "$CONFIG_FILE"; then
  set_env_value STT_AUDIO_ARCHIVE_DIR "$STATE_ROOT/audio-archive"
fi
if ! grep -Eq '^STT_AUDIO_ARCHIVE_RETENTION_DAYS=.+$' "$CONFIG_FILE"; then
  set_env_value STT_AUDIO_ARCHIVE_RETENTION_DAYS "30"
fi
if ! grep -Eq '^STT_AUDIO_ARCHIVE_MAX_GB=.+$' "$CONFIG_FILE"; then
  set_env_value STT_AUDIO_ARCHIVE_MAX_GB "10"
fi
if ! grep -Eq '^STT_FINAL_CONDITION_ON_PREVIOUS_TEXT=.+$' "$CONFIG_FILE"; then
  set_env_value STT_FINAL_CONDITION_ON_PREVIOUS_TEXT "1"
fi
CURRENT_FINAL_PROMPT="$(sed -n 's/^STT_FINAL_INITIAL_PROMPT=//p' "$CONFIG_FILE" | tail -n1)"
if [[ -z "$CURRENT_FINAL_PROMPT" || "$CURRENT_FINAL_PROMPT" == "以下是普通话会议记录，请使用规范中文标点。" ]]; then
  set_env_value STT_FINAL_INITIAL_PROMPT ""
fi
if ! grep -Eq '^STT_FINAL_RESTORE_PUNCTUATION=.+$' "$CONFIG_FILE"; then
  set_env_value STT_FINAL_RESTORE_PUNCTUATION "1"
fi
if ! grep -Eq '^STT_FINAL_PUNCTUATION_PAUSE_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value STT_FINAL_PUNCTUATION_PAUSE_SEC "0.8"
fi
CURRENT_STT_MAX_UPLOAD_MB="$(sed -n 's/^STT_MAX_UPLOAD_MB=//p' "$CONFIG_FILE" | tail -n1)"
if [[ -z "$CURRENT_STT_MAX_UPLOAD_MB" || "$CURRENT_STT_MAX_UPLOAD_MB" == "256" ]]; then
  set_env_value STT_MAX_UPLOAD_MB "1024"
fi
if ! grep -Eq '^STT_LONG_AUDIO_CHUNK_THRESHOLD_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value STT_LONG_AUDIO_CHUNK_THRESHOLD_SEC "2700"
fi
if ! grep -Eq '^STT_LONG_AUDIO_CHUNK_SECONDS=.+$' "$CONFIG_FILE"; then
  set_env_value STT_LONG_AUDIO_CHUNK_SECONDS "1800"
fi
if ! grep -Eq '^STT_LONG_AUDIO_CHUNK_OVERLAP_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value STT_LONG_AUDIO_CHUNK_OVERLAP_SEC "3"
fi
if ! grep -Eq '^TENCENT_ASR_ENABLED=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_ENABLED "0"
fi
if ! grep -Eq '^TENCENT_ASR_BASE_URL=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_BASE_URL "https://asr.cloud.tencent.com/asr/flash/v1"
fi
if ! grep -Eq '^TENCENT_ASR_ENGINE_TYPE=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_ENGINE_TYPE "16k_zh_en"
fi
if ! grep -Eq '^TENCENT_ASR_TIMEOUT_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_TIMEOUT_SEC "900"
fi
if ! grep -Eq '^TENCENT_ASR_MAX_UPLOAD_MB=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_MAX_UPLOAD_MB "100"
fi
if ! grep -Eq '^TENCENT_ASR_MAX_CONCURRENT=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_MAX_CONCURRENT "2"
fi
if ! grep -Eq '^TENCENT_ASR_CHUNK_SECONDS=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_CHUNK_SECONDS "2400"
fi
if ! grep -Eq '^TENCENT_ASR_CHUNK_OVERLAP_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_CHUNK_OVERLAP_SEC "3"
fi
if ! grep -Eq '^TENCENT_REALTIME_ASR_ENABLED=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_REALTIME_ASR_ENABLED "0"
fi
if ! grep -Eq '^TENCENT_REALTIME_ASR_BASE_URL=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_REALTIME_ASR_BASE_URL "wss://asr.cloud.tencent.com/asr/v2"
fi
if ! grep -Eq '^TENCENT_REALTIME_ASR_ENGINE_TYPE=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_REALTIME_ASR_ENGINE_TYPE "16k_zh_en"
fi
if ! grep -Eq '^TENCENT_REALTIME_ASR_MAX_CONCURRENT=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_REALTIME_ASR_MAX_CONCURRENT "16"
fi
if ! grep -Eq '^TENCENT_REALTIME_ASR_CONNECT_TIMEOUT_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_REALTIME_ASR_CONNECT_TIMEOUT_SEC "15"
fi
if ! grep -Eq '^TENCENT_REALTIME_ASR_FINAL_TIMEOUT_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_REALTIME_ASR_FINAL_TIMEOUT_SEC "15"
fi
if ! grep -Eq '^TENCENT_REALTIME_ASR_SIGNATURE_TTL_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_REALTIME_ASR_SIGNATURE_TTL_SEC "3600"
fi
if ! grep -Eq '^TENCENT_REALTIME_ASR_FRAME_MS=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_REALTIME_ASR_FRAME_MS "200"
fi
if ! grep -Eq '^TENCENT_REALTIME_ASR_QUEUE_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_REALTIME_ASR_QUEUE_SEC "6"
fi
# The generic Tencent switches are retained only for legacy configuration
# compatibility. Explicit tiers below are the only routes used by new clients.
if ! grep -Eq '^TENCENT_STANDARD_ASR_ENABLED=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_STANDARD_ASR_ENABLED "0"
fi
if ! grep -Eq '^TENCENT_STANDARD_REALTIME_ASR_ENABLED=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_STANDARD_REALTIME_ASR_ENABLED "0"
fi
if ! grep -Eq '^TENCENT_STANDARD_ASR_ENGINE_TYPE=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_STANDARD_ASR_ENGINE_TYPE "16k_zh"
fi
if ! grep -Eq '^TENCENT_STANDARD_REALTIME_ASR_ENGINE_TYPE=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_STANDARD_REALTIME_ASR_ENGINE_TYPE "16k_zh"
fi
if ! grep -Eq '^TENCENT_STANDARD_MONTHLY_LIMIT_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_STANDARD_MONTHLY_LIMIT_SEC "18000"
fi
if ! grep -Eq '^TENCENT_LEGACY_USAGE_TIER=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_LEGACY_USAGE_TIER "precision"
fi
if ! grep -Eq '^TENCENT_PRECISION_ASR_ENABLED=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_PRECISION_ASR_ENABLED "0"
fi
if ! grep -Eq '^TENCENT_PRECISION_REALTIME_ASR_ENABLED=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_PRECISION_REALTIME_ASR_ENABLED "0"
fi
if ! grep -Eq '^TENCENT_PRECISION_ASR_ENGINE_TYPE=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_PRECISION_ASR_ENGINE_TYPE "16k_zh_en"
fi
if ! grep -Eq '^TENCENT_PRECISION_REALTIME_ASR_ENGINE_TYPE=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_PRECISION_REALTIME_ASR_ENGINE_TYPE "16k_zh_en"
fi
if ! grep -Eq '^TENCENT_PRECISION_MONTHLY_LIMIT_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_PRECISION_MONTHLY_LIMIT_SEC "0"
fi
if ! grep -Eq '^TENCENT_ASR_USAGE_ENABLED=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_USAGE_ENABLED "1"
fi
if ! grep -Eq '^TENCENT_ASR_USAGE_API_ENDPOINT=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_USAGE_API_ENDPOINT "asr.tencentcloudapi.com"
fi
if ! grep -Eq '^TENCENT_ASR_USAGE_REGION=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_USAGE_REGION "ap-guangzhou"
fi
if ! grep -Eq '^TENCENT_ASR_USAGE_TIMEZONE=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_USAGE_TIMEZONE "Asia/Shanghai"
fi
if ! grep -Eq '^TENCENT_ASR_USAGE_CACHE_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_USAGE_CACHE_SEC "300"
fi
USAGE_LEDGER_PATH="$(sed -n 's/^TENCENT_ASR_USAGE_LEDGER_PATH=//p' "$CONFIG_FILE" | tail -n1)"
if [[ -z "$USAGE_LEDGER_PATH" || "$USAGE_LEDGER_PATH" != /* ]]; then
  set_env_value TENCENT_ASR_USAGE_LEDGER_PATH "$STATE_ROOT/data/tencent-asr-usage.db"
fi
if ! grep -Eq '^TENCENT_ASR_USAGE_LEDGER_ENABLED=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_ASR_USAGE_LEDGER_ENABLED "1"
fi
if ! grep -Eq '^TENCENT_REALTIME_MONTHLY_FREE_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_REALTIME_MONTHLY_FREE_SEC "18000"
fi
if ! grep -Eq '^TENCENT_FLASH_MONTHLY_FREE_SEC=.+$' "$CONFIG_FILE"; then
  set_env_value TENCENT_FLASH_MONTHLY_FREE_SEC "18000"
fi
chown root:"$APP_GROUP" "$CONFIG_FILE"
chmod 0640 "$CONFIG_FILE"

if [[ -n "$MODEL_STAGE" ]]; then
  systemctl stop meetingnotes-stt.service 2>/dev/null || true
  rm -rf -- "$STATE_ROOT/models"
  install -d -o "$APP_USER" -g "$APP_GROUP" -m 0750 "$STATE_ROOT/models"
  cp -a "$MODEL_STAGE/." "$STATE_ROOT/models/"
fi
chown -R "$APP_USER":"$APP_GROUP" "$STATE_ROOT"
if [[ ! -f "$STATE_ROOT/logs/stt.log" ]]; then
  install -o "$APP_USER" -g "$APP_GROUP" -m 0640 /dev/null "$STATE_ROOT/logs/stt.log"
else
  chown "$APP_USER":"$APP_GROUP" "$STATE_ROOT/logs/stt.log"
  chmod 0640 "$STATE_ROOT/logs/stt.log"
fi
(cd "$STATE_ROOT/models" && sha256sum -c "$FINAL_RELEASE/model-manifest.sha256")

install -m 0644 "$FINAL_RELEASE/systemd/meetingnotes-stt.service" /etc/systemd/system/meetingnotes-stt.service
install -m 0644 "$FINAL_RELEASE/systemd/meetingnotes-backend.service" /etc/systemd/system/meetingnotes-backend.service

atomic_link() {
  local target="$1"
  local link="$2"
  local temporary="${link}.new.$$"
  rm -f -- "$temporary"
  ln -s "$target" "$temporary"
  mv -Tf "$temporary" "$link"
}

systemctl stop meetingnotes-backend.service 2>/dev/null || true
systemctl stop meetingnotes-stt.service 2>/dev/null || true
if [[ -n "$OLD_RELEASE" ]]; then
  atomic_link "$OLD_RELEASE" "$APP_ROOT/previous"
fi
if [[ -n "$OLD_VENV" ]]; then
  atomic_link "$OLD_VENV" "$APP_ROOT/previous-venv"
fi
atomic_link "$FINAL_RELEASE" "$APP_ROOT/current"
atomic_link "$FINAL_VENV" "$APP_ROOT/current-venv"

START_OK=1
if ! systemctl daemon-reload; then START_OK=0; fi
if ! systemctl enable meetingnotes-stt.service >/dev/null; then START_OK=0; fi
if ! systemctl restart meetingnotes-stt.service; then START_OK=0; fi
if [[ "$WITH_BACKEND" -eq 1 ]]; then
  if ! systemctl enable meetingnotes-backend.service >/dev/null; then START_OK=0; fi
  if ! systemctl restart meetingnotes-backend.service; then START_OK=0; fi
else
  systemctl disable --now meetingnotes-backend.service >/dev/null 2>&1 || true
fi

PORT="$(sed -n 's/^STT_PORT=//p' "$CONFIG_FILE" | tail -n1)"
PORT="${PORT:-8888}"
READY=0
if [[ "$START_OK" -eq 1 ]]; then
  for _ in $(seq 1 180); do
    if curl --fail --silent --max-time 5 "http://127.0.0.1:${PORT}/ready" >/dev/null; then
      READY=1
      break
    fi
    sleep 5
  done
fi
if [[ "$READY" -eq 1 && "$WITH_BACKEND" -eq 1 ]]; then
  BACKEND_PORT="$(sed -n 's/^WEB_BACKEND_PORT=//p' "$CONFIG_FILE" | tail -n1)"
  BACKEND_PORT="${BACKEND_PORT:-8090}"
  BACKEND_READY=0
  for _ in $(seq 1 12); do
    if curl --fail --silent --max-time 5 "http://127.0.0.1:${BACKEND_PORT}/health" >/dev/null; then
      BACKEND_READY=1
      break
    fi
    sleep 5
  done
  if [[ "$BACKEND_READY" -ne 1 ]]; then READY=0; fi
  if [[ "$BACKEND_READY" -eq 1 ]]; then
    AGENT_TOKEN="$(sed -n 's/^AGENT_API_TOKEN=//p' "$CONFIG_FILE" | tail -n1)"
    if [[ -z "$AGENT_TOKEN" ]] || ! curl --fail --silent --max-time 15 \
      -H "Authorization: Bearer ${AGENT_TOKEN}" \
      "http://127.0.0.1:${BACKEND_PORT}/api/agent/health" >/dev/null; then
      READY=0
    fi
  fi
fi

if [[ "$READY" -ne 1 ]]; then
  echo "[ERROR] New release failed readiness; restoring previous release." >&2
  journalctl -u meetingnotes-stt.service -n 100 --no-pager >&2 || true
  if [[ "$WITH_BACKEND" -eq 1 ]]; then
    journalctl -u meetingnotes-backend.service -n 100 --no-pager >&2 || true
  fi
  systemctl stop meetingnotes-backend.service 2>/dev/null || true
  systemctl stop meetingnotes-stt.service 2>/dev/null || true
  if [[ -n "$CONFIG_SNAPSHOT" ]]; then
    install -m 0640 -o root -g "$APP_GROUP" "$CONFIG_SNAPSHOT" "$CONFIG_FILE"
  fi
  if [[ -n "$OLD_RELEASE" && -n "$OLD_VENV" ]]; then
    atomic_link "$OLD_RELEASE" "$APP_ROOT/current"
    atomic_link "$OLD_VENV" "$APP_ROOT/current-venv"
    install -m 0644 "$OLD_RELEASE/systemd/meetingnotes-stt.service" /etc/systemd/system/meetingnotes-stt.service
    install -m 0644 "$OLD_RELEASE/systemd/meetingnotes-backend.service" /etc/systemd/system/meetingnotes-backend.service
    systemctl daemon-reload || true
    systemctl enable meetingnotes-stt.service >/dev/null 2>&1 || true
    systemctl restart meetingnotes-stt.service || true
    if [[ "$OLD_BACKEND_ENABLED" -eq 1 ]]; then
      systemctl enable meetingnotes-backend.service >/dev/null 2>&1 || true
      systemctl restart meetingnotes-backend.service || true
    else
      systemctl disable --now meetingnotes-backend.service >/dev/null 2>&1 || true
    fi
  else
    systemctl disable --now meetingnotes-stt.service >/dev/null 2>&1 || true
    rm -f -- "$APP_ROOT/current" "$APP_ROOT/current-venv"
  fi
  exit 1
fi

if [[ "$OPEN_FIREWALL" -eq 1 ]] && command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then
  if ! ufw allow "${PORT}/tcp"; then
    echo "[WARN] Service is ready, but the UFW rule could not be added." >&2
  fi
fi

echo "[OK] MeetingNotesApp Server ${VERSION} release ${RELEASE_ID} is ready."
curl --fail --silent --show-error "http://127.0.0.1:${PORT}/health"
echo
echo "[INFO] Logs: journalctl -u meetingnotes-stt.service -f"
