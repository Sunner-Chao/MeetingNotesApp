# Ubuntu 原生一键部署

## 1. 适用范围

`MeetingNotesApp Server 1.1.3` 的固定生产路径不使用 Docker，直接运行于 Ubuntu systemd。

要求：

- Ubuntu 22.04 或 24.04 x86_64
- 4 个 CPU 核心
- 至少 3500 MB 可见内存（标称 4 GB）
- 至少 6 GB 可用磁盘
- 无需 GPU
- 可访问 Ubuntu APT、deadsnakes PPA 和 Python 包源
- Windows 发布机具备 `ssh`、`scp`、`tar`

安装器会安装 Python 3.11、venv、ffmpeg、libgomp 和 libsndfile。不要在聊天中发送 SSH 密码或私钥内容；只提供服务器地址、端口、用户名和本地私钥路径。

## 2. SSH 部署账户

推荐创建临时部署账户，配置公钥登录和临时免密 sudo。部署冻结完成后撤销免密 sudo或停用该账户。`deploy-remote.ps1` 使用 `BatchMode=yes`，不会读取明文 SSH 密码，也不会弹出密码输入。

部署前验证：

```powershell
ssh -i C:\Users\你的用户名\.ssh\id_ed25519 -p 22 用户名@服务器IP "sudo -n true && uname -a"
```

若 `sudo -n true` 失败，需要服务器管理员先配置临时免密 sudo；使用 root 公钥登录时给发布脚本增加 `-NoSudo`。

## 3. Windows 一键发布

```powershell
cd <项目根目录>\server
.\deploy-remote.ps1 `
  -ServerHost 203.0.113.10 `
  -Port 22 `
  -User deploy `
  -KeyPath C:\Users\你的用户名\.ssh\id_ed25519 `
  -OpenFirewall
```

参数：

| 参数 | 说明 |
|---|---|
| `-ServerHost` | Ubuntu 公网 IP 或域名 |
| `-Port` | SSH 端口，默认 22 |
| `-User` | SSH 部署用户 |
| `-KeyPath` | 本地私钥文件路径 |
| `-ConfigFile` | 可选，上传指定生产配置并替换远端配置 |
| `-OpenFirewall` | UFW 已启用时开放 STT 端口 |
| `-WithBackend` | 同时安装可选 Backend；4 GB 主机默认不要启用 |
| `-AndroidApk` | 可选。与 `-WithBackend` 一起发布固定签名的非 debug release APK；校验证书、包版本和 SHA-256，并只保留最新与上一版 |
| `-SkipModels` | 不检测/上传模型；仅在远端模型已确认有效时使用 |
| `-SkipPackages` | 跳过 APT；仅在 Python 3.11 和系统库已安装时使用 |
| `-NoSudo` | SSH 用户本身为 root 时使用 |

首次只上传固定 `faster-whisper/small` 模型，约 486 MB；5 Mbps 上行理论约 13 分钟，考虑协议开销通常约 15-20 分钟。后续部署先校验远端 SHA-256，模型一致时不再上传。

Android OTA 只能通过 `-WithBackend -AndroidApk <release.apk>` 发布。服务端以单一 manifest 通知客户端，`version_code` 必须严格递增；连续发布多个版本时，手机只会发现最新版本。服务器按版本号仅保留最新 APK 与上一版 APK，不能手动上传或替换下载目录中的安装包。发布后的健康检查会实际请求 metadata 和两版下载地址，并校验最新版 SHA-256。

## 4. Ubuntu 本机安装

服务器上已经有完整 `server/` 目录时：

```bash
cd server
bash deploy-ubuntu.sh
```

可选 Backend：

```bash
bash deploy-ubuntu.sh --with-backend
```

GPU 参数会被明确拒绝，因为 `1.1.1` 冻结配置是无 GPU CPU 版。

## 5. 发布过程

安装器按以下顺序执行：

1. 校验 Ubuntu、CPU、内存和磁盘。
2. 安装 Python 3.11 与音频运行库。
3. 解包到新的不可变 release 目录。
4. 验证四个模型文件 SHA-256。
5. 创建版本专属 venv并安装精确锁定依赖。
6. 运行 `pip check` 和核心模块导入测试。
7. 备份当前配置与 SQLite。
8. 原子切换 `current` 和 `current-venv`。
9. 重启 systemd并最长等待 15 分钟模型就绪。
10. 新版本失败时恢复旧配置、旧源码、旧 venv和 Backend 启用状态。

发布 ID 格式为 `1.1.1-UTC时间`，健康接口同时返回 `version` 和 `release`，可与本地 `.deployment-state.json` 对照。

## 6. 目录与权限

| 路径 | 所有者 | 用途 |
|---|---|---|
| `/opt/meetingnotes-stt/releases` | root | 不可变源码发布 |
| `/opt/meetingnotes-stt/venvs` | root | 固定 Python 环境 |
| `/var/lib/meetingnotes-stt/models` | meetingnotes | 模型与缓存 |
| `/var/lib/meetingnotes-stt/tmp` | meetingnotes | 临时音频 |
| `/var/lib/meetingnotes-stt/backend` | meetingnotes | 可选 SQLite |
| `/var/lib/meetingnotes-stt/logs/stt.log` | meetingnotes | STT 标准输出，供 Backend 控制台读取 |
| `/etc/meetingnotes-stt/stt.env` | root:meetingnotes 0640 | Token 与生产配置 |
| `/var/backups/meetingnotes-stt` | root 0750 | 30 天滚动备份 |

systemd 服务以无登录权限的 `meetingnotes` 用户运行，启用只读系统目录、空 capability、私有设备、地址族限制和 `NoNewPrivileges`。

## 7. 验证与日志

```bash
sudo systemctl status meetingnotes-stt.service
sudo journalctl -u meetingnotes-stt.service -f
sudo tail -f /var/lib/meetingnotes-stt/logs/stt.log
sudo bash /opt/meetingnotes-stt/current/scripts/verify-native.sh
curl http://127.0.0.1:8888/ready
curl http://127.0.0.1:8888/health
```

`verify-native.sh` 会验证模型、Python 依赖、systemd 启用/运行状态、资源限制和健康接口。

### 社区媒体维护验收

Backend 与社区媒体目录已经存在时，先安装仅预演的每日 timer，再运行一次显式 dry-run 验收：

```bash
sudo bash /opt/meetingnotes-stt/current/scripts/install-community-media-cleanup.sh
sudo bash /opt/meetingnotes-stt/current/scripts/verify-community-media-maintenance.sh --run-dry-run
sudo systemctl list-timers meetingnotes-community-media-cleanup.timer
```

预检会对两个 unit 运行 `systemd-analyze verify`，确认已安装文件与当前 release 一致、timer 已启用且处于活动状态；`--run-dry-run` 额外启动一次只读清理预演并输出隔离区聚合统计。该过程不带 `--apply`，不会移动或删除媒体。实际清理必须先完成 P4-B13 的备份/只读窗口步骤；恢复演练应在隔离环境完成，不能用唯一线上数据库直接验证。

隔离区不可逆处置不接入 timer。先由工具生成待审批请求，并由外部工单/IAM 流程补齐两名不同审核人的批准记录：

```bash
sudo -u meetingnotes /opt/meetingnotes-stt/current-venv/bin/python \
  /opt/meetingnotes-stt/current/scripts/purge_community_media_quarantine.py \
  /var/lib/meetingnotes-stt/backend/community-media-quarantine \
  --backup-archive /var/backups/meetingnotes-stt/verified-backup.tar.gz \
  --prepare-request /var/lib/meetingnotes-stt/backend/community-media-purge-request.json \
  --restore-drill-id restore-drill-2026-08-07
```

审批文件由两名不同审核人确认后，先不带 `--apply` 验证库存和备份摘要；只有维护窗口已设置 `COMMUNITY_WRITE_ENABLED=false`、请求 ID 已人工核对且恢复/备份证据仍有效时，才允许执行 `--apply`。工具会在 `community-media-purge-receipts/` 保存不含媒体路径的清除收据。该 JSON 流程本身不提供密码学身份认证，生产环境必须由外部工单或 IAM 记录审核人身份，不能只靠本地 root 编辑 reviewer 字段。

Backend 控制台进程监听 `127.0.0.1:8090`，由 Nginx HTTPS 反向代理提供公网访问。当前兼容地址为 `https://118.25.43.185/web`，统一域名管理员入口为 `https://lstwin.space/admin/`，用户端入口为 `https://lstwin.space/app/`；Windows Caddy 通过 WireGuard 回源 Web/API，同时保留本地 STT 的 `/health` 与 `/ws/transcribe-stream`。SSH 隧道仍可用于本机维护：

```powershell
ssh -F NUL -i $HOME\.ssh\meetingnotes_stt_118_25_43_185 `
  -L 8090:127.0.0.1:8090 ubuntu@服务器IP
```

然后打开 `http://127.0.0.1:8090/web`，或直接打开公网 HTTPS 地址。当前登录用户名是 `ubuntu`，密码为独立 `WEB_API_TOKEN`；不复用 Ubuntu SSH 密码。

Agent CLI 由无登录权限的 `meetingnotes` 账号运行。中转站 API 必须配置在 `/etc/meetingnotes-stt/stt.env`，不能只写在 `/home/ubuntu/.bashrc`：

```dotenv
AGENT_CODEX_AUTH_ENV=YUJIAN_API_KEY
YUJIAN_API_KEY=中转站令牌
AGENT_CLAUDE_AUTH_ENV=ANTHROPIC_AUTH_TOKEN
ANTHROPIC_BASE_URL=https://中转站地址
ANTHROPIC_AUTH_TOKEN=中转站令牌
```

Codex 自定义 provider 的 `config.toml` 放在 `/var/lib/meetingnotes-stt/.codex/config.toml`，所有者和权限为 `meetingnotes:meetingnotes 0600`。这种配置不需要运行 `codex login` 或 `claude auth login`。

## 8. 并发调优

生产默认值：

```dotenv
STT_MAX_CONCURRENT=2
STT_CPU_THREADS=2
STT_MAX_QUEUE=16
STT_MAX_STREAMS=12
STT_MAX_UPLOAD_MB=1024
STT_LONG_AUDIO_CHUNK_THRESHOLD_SEC=2700
STT_LONG_AUDIO_CHUNK_SECONDS=1800
STT_LONG_AUDIO_CHUNK_OVERLAP_SEC=3
TENCENT_ASR_MAX_UPLOAD_MB=100
TENCENT_ASR_CHUNK_SECONDS=2400
TENCENT_ASR_CHUNK_OVERLAP_SEC=3
```

生产 STT 默认使用 Faster-Whisper `small`。当前 Windows 临时服务使用 CPU `int8`；迁移到 LS-Server 后使用 Tesla V100 的 CUDA `float16`，不要配置 BF16。腾讯云是唯一云端识别供应商，长期密钥仅写入 `/etc/meetingnotes-stt/stt.env`。

超过腾讯单次请求上限的录音由服务端规范化为 16 kHz 单声道 WAV，按 40 分钟和 3 秒重叠分段提交腾讯云，结果按顺序去重合并。本地长录音同样按重叠窗口分段处理。不要把 Uvicorn 改为多进程 worker，多进程会重复加载模型和推理状态。在 `/health` 中观察：

- `inference.active`：当前执行数，最大为 2。
- `inference.queued`：等待数，最大为 16。
- `inference.rejected`：队满拒绝累计数。
- `inference.average_queue_wait_ms`：平均排队时间。

如果持续出现 429，应先减少 Android 上传频率、压缩音频或升级 CPU，而不是在 4 GB 主机上增加模型实例。

## 9. 备份、恢复、回滚

创建一致性备份：

```bash
sudo bash /opt/meetingnotes-stt/current/scripts/backup-native.sh
```

SQLite 使用 Python 在线 backup API 生成一致快照，不直接打包正在变化的 WAL。备份保留 30 天。

恢复指定备份：

```bash
sudo bash /opt/meetingnotes-stt/current/scripts/restore-native.sh \
  /var/backups/meetingnotes-stt/meetingnotes-1.1.1-时间.tar.gz
```

回滚上一发布：

```bash
sudo bash /opt/meetingnotes-stt/current/scripts/rollback-native.sh
```

回滚会先备份当前数据，交换 `current/previous` 和对应 venv，随后重新验证服务。

## 10. 公网访问

- 云安全组需要放行 STT 端口；`-OpenFirewall` 只处理 Ubuntu UFW，不能修改云厂商安全组。
- Backend 绑定 `127.0.0.1:8090`；远端 Nginx 在 443 端口提供 IP 兼容入口和 `lstwin.space` IPv4 回源，Windows Caddy 通过 WireGuard 提供 IPv6 用户端 `https://lstwin.space/app/`、管理员端 `https://lstwin.space/admin/` 与同源 API。
- STT 的转写、WebSocket、模型切换和调试接口都要求 Bearer Token。
- `/health` 和 `/ready` 匿名开放，便于探活。
- 当前 IP HTTPS 证书已通过续期演练；正式使用仍建议配置独立域名，并将 Android STT 迁移到 HTTPS/WSS。

Android 配置中的服务地址使用 `http://服务器IP:8888`（联调）或后续 HTTPS 域名，Token 取远端 `/etc/meetingnotes-stt/stt.env` 中的 `STT_API_TOKEN`；Windows 发布脚本会将同一配置同步为本地 `server/.env.remote`。
