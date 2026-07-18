# MeetingNotesApp Server

## 当前发布

- 服务端版本：`1.1.1` 已冻结
- 生产部署：Ubuntu 原生 Python 3.11 + systemd
- 目标主机：4 核、4 GB 内存、无 GPU、5 Mbps 带宽
- 默认范围：STT 为核心组件；Backend Service 可选，当前远端实例已启用
- Docker：不再使用，部署链路和工程中均不依赖 Docker

目标 Ubuntu 22.04 主机的部署、鉴权、并发、过载、资源、服务重启、回滚、备份和恢复均已验收，`release-manifest.json` 状态为 `frozen`。1.1.5 修复最终稿简繁转换、Agent 图片请求代理超时，并为中转 Claude 图片故障提供受权限约束的 Codex 降级；1.1.6 强制每次 Codex/Claude CLI 调用使用全新的非持久化 Session；1.1.7 恢复可见的临时预览；1.1.8 让预览和最终稿共用同一个 Faster-Whisper 解码函数与核心参数；1.1.9 拦截不足四字的置信碎片；1.1.10 恢复可修订实时预览；1.1.11 让预览窗口以 4 秒节奏续接并降低 CPU 压力，同时保持完整文件最终稿使用 beam=5。此后服务端只接受明确的新版本升级，日常开发转向 Android。

## 已实现功能

### STT Service

- Faster-Whisper 文件转写：WAV、M4A、MP3、MP4、AAC、OGG、FLAC、WebM。
- WebSocket 实时 PCM 转写，支持 partial、committed、stop 和有界滑动音频窗口。
- CPU `int8` 和 NVIDIA CUDA；本次冻结生产配置固定为 CPU `int8`。
- 本地模型优先，生产环境固定使用 `small`，启动时校验 `model.bin` SHA-256。
- 上传按 1 MB 分块落盘，限制单文件大小，推理完成后清理临时文件。
- 启动及周期性清理服务专属的过期临时文件，不处理其他应用文件。
- Bearer Token 鉴权；生产配置禁止空 Token。
- 运行时模型切换接口会暂停接单并排空已经接受的任务。

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
- Agent 请求支持 Codex CLI、Claude CLI、图片附件、单任务执行和最多 8 个排队任务。
- Android 已接入 Agent API；会议数据 API 仍未接入 Android。

## 生产架构

```text
Android
   |-- Agent HTTPS --> Nginx :443 --> meetingnotes-backend.service
   |                                      |--> Codex CLI  --> 中转站 API
   |                                      `--> Claude CLI --> 中转站 API
   |
   `-- STT HTTP/WS --> :8888 --> meetingnotes-stt.service
                                      `--> 有界 FIFO (active=2, queue=16)
                                           `--> Faster-Whisper small / CPU int8
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
YUJIAN_API_KEY=中转站令牌
AGENT_CLAUDE_AUTH_ENV=ANTHROPIC_AUTH_TOKEN
ANTHROPIC_BASE_URL=https://中转站地址
ANTHROPIC_AUTH_TOKEN=中转站令牌
```

环境文件必须保持 `root:meetingnotes 0640`，Codex 配置必须保持 `meetingnotes:meetingnotes 0600`。修改后重启 `meetingnotes-backend.service`，通过 `/api/agent/health` 检查两个 provider 的 `authenticated` 和 `auth_method`。

## API

| 服务 | 方法与路径 | 鉴权 | 用途 |
|---|---|---|---|
| STT | `GET /health` | 无 | 版本、模型、队列与运行指标 |
| STT | `GET /ready` | 无 | 模型就绪检查 |
| STT | `POST /transcribe` | Bearer | 完整音频转写 |
| STT | `WS /ws/transcribe-stream` | Bearer | 实时 PCM 转写 |
| STT | `POST /admin/stt/switch` | Bearer | 切换引擎/模型 |
| STT | `GET/DELETE /debug/stream-events` | Bearer | 流式调试事件 |
| Backend | `GET /health` | 无 | 后端和数据库健康 |
| Backend | `/api/meetings` 等 | Bearer/Basic | 可选会议数据 API |
| Agent | `GET /api/agent/health` | 独立 Bearer | 提供方、中转凭证、队列和额度状态 |
| Agent | `POST /api/agent` | 独立 Bearer | 对话、报告生成和图片附件 |
| Agent | `GET /api/agent/quota` | 独立 Bearer | 当前令牌额度 |
| Agent | `/api/admin/agent/*` | Web 管理鉴权 | 令牌签发、停用和运行状态 |

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

- Android 已接入 STT 和 Agent，但尚未接入 Backend 会议数据 API。
- “本地与服务端同步”当前指源码、版本、模型和生产配置同步，不是 Android 业务数据双向同步。
- 当前是共享 STT Token，不是多租户账号系统。
- 5 Mbps 公网适合实时 PCM 和压缩音频，不适合多人同时上传超大原始录音；客户端后续应增加断点、重试和压缩策略。
- Backend 公网控制台使用 `https://118.25.43.185/web`；Backend 进程仅绑定 `127.0.0.1:8090`，由 Nginx HTTPS 转发。
- Android STT 公网正式使用仍建议配置自己的域名和 HTTPS/WSS。
