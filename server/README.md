# MeetingNotesApp Server

## 当前发布

- 当前生产发布：`1.2.56`（发布 ID 以 `server/.deployment-state.json` 与远端 systemd 为准）
- 生产部署：Ubuntu 原生 Python 3.11 + systemd
- 目标主机：4 核、4 GB 内存、无 GPU、5 Mbps 带宽
- 默认范围：STT 为核心组件；Backend Service 可选，当前远端实例已启用
- Docker：不再使用，部署链路和工程中均不依赖 Docker

目标 Ubuntu 22.04 主机的部署、鉴权、并发、过载、资源、服务重启、回滚、备份和恢复均已验收。云端识别按标准档和高精度档分开配置；实时会话由用户结束、网络断开、上游结束或并发资源决定，不设应用侧时长或额度限制。

## 已实现功能

### STT Service

- Faster-Whisper 文件转写：WAV、M4A、MP3、MP4、AAC、OGG、FLAC、WebM。
- WebSocket 实时 PCM 转写，支持 partial、committed、stop 和有界滑动音频窗口。
- 腾讯云混合模式分为标准档（`16k_zh`）与高精度付费档（`16k_zh_en`）；旧客户端协议只映射到标准档。
- 标准云模型不做应用侧额度预留或时长限制；实时会话正常停止或异常断开会立即关闭云端连接。仅管理员显式启用的臻享付费档保留录音文件时长账本。
- CPU `int8` 和 NVIDIA CUDA；本次冻结生产配置固定为 CPU `int8`。
- 本地模型优先，生产环境固定使用 `small`，启动时校验 `model.bin` SHA-256。
- 长录音按服务端动态策略分段处理：默认超过 45 分钟按 30 分钟片段、3 秒重叠顺序合并；腾讯云同时遵守单次 100 MiB 请求限制。上传接收上限默认 1 GiB，实际部署可通过环境变量调整。
- 上传按 1 MB 分块落盘，限制单文件大小，推理完成后清理临时文件。
- 可配置音频归档在最终推理前保存完整录音，按账户和会议隔离，并按保留天数和总容量清理。
- 启动及周期性清理服务专属的过期临时文件，不处理其他应用文件。
- Bearer Token 鉴权；生产配置禁止空 Token。
- 运行时模型切换接口会暂停接单并排空已经接受的任务。
- Windows 本地节点提供独立 Web 管理台，用 Basic 鉴权查看状态、事件和日志，并执行受控模型切换。

### 并发与过载保护

- 单进程加载一份模型，避免多进程重复占用内存。
- Faster-Whisper 使用 2 个推理 worker，每个 worker 使用 2 个 CPU 线程。
- 文件和流式任务共用有界 FIFO 调度器，默认等待队列为 16。
- 队满返回 HTTP 429 和 `Retry-After`，模型维护期间返回 HTTP 503。
- `/health` 暴露 active、queued、submitted、completed、failed、rejected、峰值和平均排队时间。
- 本机真实并发验证：6 个请求均为 HTTP 200，`peak_active=2`、`peak_queued=4`。

### Backend Service（可选）

- SQLite 表：meetings、transcripts、reports。
- 外键、级联删除、WAL、30 秒 busy timeout、事务回滚和报告稳定 ID upsert。
- 会议、转写和报告 API，以及仅绑定本机的 Web 调试台。
- 独立 HTML 运维控制台：服务健康、模型/队列指标、引擎切换、会议表格、流式事件和日志维护。
- Bearer Token 与可配置用户名的 HTTP Basic 鉴权；当前远端用户名为 `ubuntu`，密码为独立 `WEB_API_TOKEN`。
- 私有 Agent API 使用独立 Bearer Token、按令牌配额、提供方权限、有效期和停用控制。
- Agent 请求支持 Codex CLI、Claude CLI、任意数量图片附件（默认不设张数上限，仍受单图/总上传字节保护）、单任务执行和最多 8 个排队任务。
- Android 默认使用 Codex；Codex 与 Claude 推理强度可在 Android 服务设置中分别调整，服务端默认均为 `medium`。
- 实时 PCM 会在 Server 同步归档，正常停止后直接就地生成 beam=5 最终稿；连接异常时 Android 自动回退完整文件上传。浏览器用户端通过 WSS 连接后发送一次性鉴权消息建立同一实时预览链路（令牌不出现在 URL 或代理访问日志），原生客户端继续使用 Bearer 头。
- Android 已接入账户、短期凭证、Agent 和额度 API；会议正文数据仍以本地 Room 为采集过程可信源，完整会议成果云同步仍在演进。
- Web 会议图片归档到 `ACCOUNT_MEDIA_DIR`，按用户和会议隔离；部署备份必须同时覆盖该目录与 `ACCOUNT_DB_PATH`。
- Agent transcript/chat 字符数和请求 JSON 字节数使用独立环境变量；`0` 表示不设置应用层硬上限。

## 生产架构

```text
Android
   |-- Agent HTTPS --> Nginx :443 --> meetingnotes-backend.service
   |                                      |--> Codex CLI  --> 中转站 API
   |                                      `--> Claude CLI --> 中转站 API
   |
   `-- STT HTTP/WS --> :8888 --> meetingnotes-stt.service
                                      |--> 腾讯实时 ASR --> 可修订预览
                                      |--> 腾讯极速版   --> 最终稿
                                      `--> 有界 FIFO (active=2, queue=16)
                                           `--> Faster-Whisper small / CPU int8 回退
```

systemd 约束：

| 项目 | 值 |
|---|---:|
| `MemoryHigh` | 2700 MB |
| `MemoryMax` | 3000 MB |
| `CPUQuota` | 350% |
| `TasksMax` | 128 |
| Uvicorn 进程 | 1 |
| 推理并发 | 2 |
| 每 worker CPU 线程 | 2 |

## 一键部署

从当前 Windows 工作区发布到 Ubuntu：

```powershell
cd <项目根目录>\server
.\deploy-remote.ps1 `
  -ServerHost 服务器IP `
  -User 部署用户名 `
  -KeyPath C:\Users\你的用户名\.ssh\id_ed25519 `
  -OpenFirewall
```

脚本会完成源码打包、首次模型上传、Python 3.11/ffmpeg 安装、固定依赖安装、systemd 注册、健康等待和自动回退。远端管理配置会同步到本地 `.env.remote`，发布元数据会写入 `.deployment-state.json`；两者均被 Git 忽略。

发布 Android 更新时，先将 `server/config/app-update.json` 的 `version_code` 和 `version_name` 改为与 APK 一致的版本，再附加 `-WithBackend -AndroidApk <APK 路径>`。脚本会计算 SHA-256、按严格递增的版本号原子发布 APK，并强制只保留当前与上一版本各一份。手机端登录或回到前台时强制从服务端检查，只提示最后一次成功发布的最新版本；连续跨版本发布不会逐级提示中间版本。

已经在 Ubuntu 本机取得 `server/` 目录时：

```bash
cd server
bash deploy-ubuntu.sh
```

完整前提、参数、目录、备份和回滚见 [DEPLOY_UBUNTU.md](DEPLOY_UBUNTU.md)。

## Windows 本地复现

```bat
cd server
init-server.bat
start-all.bat
```

`init-server.bat` 使用 `uv` 管理 Python 3.11.15，并为 STT 和 Backend 分别重建 `runtime`。STT 使用与 Ubuntu 相同的 `requirements-core.lock.txt`，Backend 使用 `requirements.lock.txt`；启动脚本不再绕过 venv调用系统 Python。停止服务运行 `stop-all.bat`。

## 生产目录

```text
/opt/meetingnotes-stt/
|-- releases/<release-id>/   # 不可变源码
|-- venvs/<release-id>/      # 对应 Python 3.11 环境
|-- current -> releases/...  # 当前原子链接
|-- previous -> releases/... # 上一版本
`-- current-venv -> venvs/...

/var/lib/meetingnotes-stt/
|-- models/                  # 固定模型
|-- tmp/                     # 上传和推理临时文件
`-- backend/                 # 可选 SQLite 数据

/etc/meetingnotes-stt/stt.env
/var/backups/meetingnotes-stt/
```

## 常用运维

```bash
sudo systemctl status meetingnotes-stt.service
sudo journalctl -u meetingnotes-stt.service -f
sudo tail -f /var/lib/meetingnotes-stt/logs/stt.log
sudo bash /opt/meetingnotes-stt/current/scripts/verify-native.sh
sudo bash /opt/meetingnotes-stt/current/scripts/backup-native.sh
sudo bash /opt/meetingnotes-stt/current/scripts/rollback-native.sh
```

## Agent 中转站配置

后台以 `meetingnotes` 系统账号运行，不读取 SSH 用户的 OAuth 登录态。中转站配置放在 `/etc/meetingnotes-stt/stt.env`，Codex 的非敏感 provider 配置放在 `/var/lib/meetingnotes-stt/.codex/config.toml`：

```dotenv
AGENT_CODEX_AUTH_ENV=YUJIAN_API_KEY
AGENT_CODEX_REASONING_EFFORT=medium
AGENT_CLAUDE_EFFORT=medium
YUJIAN_API_KEY=中转站令牌
AGENT_CLAUDE_AUTH_ENV=ANTHROPIC_AUTH_TOKEN
ANTHROPIC_BASE_URL=https://中转站地址
ANTHROPIC_AUTH_TOKEN=中转站令牌
AGENT_MAX_IMAGES=0
AGENT_MAX_TEXT_CHARS=0
AGENT_MAX_REQUEST_JSON_BYTES=0
STT_AUDIO_ARCHIVE_ENABLED=1
STT_AUDIO_ARCHIVE_DIR=/var/lib/meetingnotes-stt/audio-archive
STT_AUDIO_ARCHIVE_RETENTION_DAYS=30
STT_AUDIO_ARCHIVE_MAX_GB=10
```

环境文件必须保持 `root:meetingnotes 0640`，Codex 配置必须保持 `meetingnotes:meetingnotes 0600`。修改后重启 `meetingnotes-backend.service`，通过 `/api/agent/health` 检查两个 provider 的 `authenticated` 和 `auth_method`。

## STT 运行策略

默认链路使用本地 Faster-Whisper `small`。Windows 临时服务使用 CPU `int8`，后续迁移到 LS-Server 后使用 Tesla V100 的 CUDA `float16`。腾讯云是唯一保留的云端方案，只在用户明确选择云端识别或本地链路失败时使用。

Windows 本机启动：

```bat
server\stt-service\start-windows-local.bat
```

Windows 本机 Caddy 继续承载 `lstwin.space` 的 IPv6 TLS 入口和本地 STT WebSocket；统一 Web/API/管理路径通过 WireGuard HTTPS 回源到 Backend VPS `10.77.0.1:443`。`/health` 与 `/ws/transcribe-stream` 保持本地 STT 语义，用户端 Web 使用 `/app/`，管理端使用 `/admin/`，API 使用 `/api/`。`lstwin.space` 使用 AliDNS DNS-01 自动续期的 Let’s Encrypt 公网证书，证书和 ACME 账户只保存在用户私有目录；管理账号从私有环境文件读取，不写入仓库。`lstwin.cloud` 仍按原链路运行。

> [!note] Web 入口迁移（2026-08-31）
> 统一域名已切换：`https://lstwin.space/app/` 为用户端 Web，`https://lstwin.space/admin/` 为 Backend 管理端，`https://lstwin.space/api/` 为同源 API；`https://118.25.43.185/app/` 与 `/web` 保留为兼容入口。Windows Caddy 通过 WireGuard 回源 Web/API，`/health` 和 `/ws/transcribe-stream` 仍保留本地 STT 兼容语义。

AVD 数据面使用 `http://10.0.2.2:8888` 访问 Windows Host；真机使用 Windows 工作站的局域网地址。正式 Android 包不内置 Caddy 私有 CA。长录音由服务端按重叠窗口分段并顺序去重合并，上传总上限默认 1 GiB。

## API

| 服务 | 方法与路径 | 鉴权 | 用途 |
|---|---|---|---|
| STT | `GET /health` | 无 | 版本、模型、队列与运行指标 |
| STT | `GET /ready` | 无 | 模型就绪检查 |
| STT | `POST /transcribe` | Bearer | 完整音频转写 |
| STT | `WS /ws/transcribe-stream` | Bearer | 实时 PCM 转写 |
| STT | `POST /transcribe/stream/{session_id}` | Bearer | 对已完整上传的流式会话就地生成最终稿 |
| STT | `GET /audio-archive?meeting_id={id}` | Bearer | 列出当前账户和会议的归档音频 |
| STT | `GET/DELETE /audio-archive/{archive_id}` | Bearer | 下载或删除当前账户的归档音频 |
| STT | `POST /admin/stt/switch` | Bearer | 切换引擎/模型 |
| STT | `GET/DELETE /debug/stream-events` | Bearer | 流式调试事件 |
| STT Admin | `GET /admin/` | Web Basic | Windows 本地 STT 管理页面 |
| STT Admin | `GET /admin/api/status` | Web Basic | 服务、模型、设备、队列和会话状态 |
| STT Admin | `GET /admin/api/events` | Web Basic | 最近实时转写事件 |
| STT Admin | `GET /admin/api/logs` | Web Basic | 最近服务输出和错误日志 |
| STT Admin | `POST /admin/api/stt/switch` | Web Basic | 从管理台切换引擎和模型 |
| Backend | `GET /health` | 无 | 后端和数据库健康 |
| Backend | `/api/meetings` 等 | Bearer/Basic | 可选会议数据 API |
| Agent | `GET /api/agent/health` | 独立 Bearer | 提供方、中转凭证、队列和额度状态 |
| Agent | `POST /api/agent` | 独立 Bearer | 对话、报告生成和图片附件 |
| Agent | `GET /api/agent/quota` | 独立 Bearer | 当前令牌额度 |
| Agent | `/api/admin/agent/*` | Web 管理鉴权 | 令牌签发、停用和运行状态 |
| Account | `POST /api/auth/register` | 无 | 注册服务端用户并签发会话 |
| Account | `POST /api/auth/login` | 无 | 用户登录并签发用户/Agent令牌 |
| Account | `GET /api/account/me` | 用户 Bearer | 用户角色、VIP、模板权益和额度 |
| Account | `GET /api/account/session` | 用户 Bearer | 刷新资料、Agent 令牌和短期 STT 用户令牌 |
| Account | `GET /api/account/plans` | 用户 Bearer | 动态套餐列表 |
| Account | `GET/POST /api/account/orders` | 用户 Bearer | 查询或提交充值申请 |
| Account Admin | `GET/PATCH/DELETE /api/admin/accounts/users/{user_id}` | 管理员用户 Bearer | 用户列表、启停和永久删除普通用户 |
| Account Admin | `/api/admin/accounts/orders/*` | 管理员用户 Bearer | 订单列表、批准与拒绝 |

## 工程目录

```text
server/
|-- stt-service/             # STT FastAPI、调度器、固定依赖
|-- backend-service/         # 可选 Backend 和独立 dashboard.html 调试台
|-- config/                  # Ubuntu 生产配置模板
|-- systemd/                 # STT/Backend unit
|-- scripts/                 # 安装、验证、备份、恢复、回滚
|-- tests/                   # 调度器、数据库、运行时测试
|-- models/                  # 本地模型，不提交 Git
|-- deploy-ubuntu.sh         # Ubuntu 本机一键安装
|-- deploy-remote.ps1        # Windows -> Ubuntu 同步发布
|-- VERSION                  # 语义版本
|-- release-manifest.json    # 冻结配置和模型信息
`-- model-manifest.sha256    # 模型文件校验值
```

## 已知边界

- Android 已接入服务端注册、登录、用户会话、VIP 套餐、充值订单、管理员审批和 Agent 用户额度；会议业务数据仍保存在本机 Room。
- “本地与服务端同步”当前指源码、版本、模型和生产配置同步，不是 Android 业务数据双向同步。
- STT 管理调用保留共享服务 Token；Android 用户使用由账户服务签发的短期 HMAC STT 令牌，不接触全局 STT 密钥。
- 当前充值流程是“提交订单 -> 管理员确认入账”，尚未接入微信、支付宝或其他支付回调。
- 5 Mbps 公网适合实时 PCM 和压缩音频，不适合多人同时上传超大原始录音；客户端后续应增加断点、重试和压缩策略。
- Backend 公网控制台地址由 Nginx 与运行环境配置提供；Backend 进程只绑定内部监听地址，由 Nginx HTTPS 转发。
- Android STT 公网正式使用仍建议配置自己的域名和 HTTPS/WSS。
