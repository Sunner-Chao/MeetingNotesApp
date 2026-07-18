# MeetingNotesApp

智能会议录音、语音转写和 AI 纪要生成项目。仓库已经拆分为两个可独立处理的工程：

| 工程 | 目录 | 用途 | 独立入口 |
|---|---|---|---|
| Android 客户端 | [`android/`](android/) | 录音、实时预览、最终转写、报告生成、导出和本地数据管理 | [`android/README.md`](android/README.md) |
| Server 服务端 | [`server/`](server/) | Faster-Whisper STT、并发调度、可选调试 Backend、Ubuntu systemd 部署 | [`server/README.md`](server/README.md) |

两个目录不再共享源码、Gradle 配置、Python 环境、模型目录或运行数据。`server/` 可以单独复制到 Ubuntu/Windows 服务器；`android/` 可以单独用 Android Studio 打开。

## 当前调用关系

```text
Android App
  |-- HTTPS/WSS --> STT Service (/transcribe, /ws/transcribe-stream)
  |-- HTTP/HTTPS -> Ollama 或云端 LLM API
  `-- Room ------> Android 本地会议、转写和报告数据库

Server
  |-- STT Service ----> Faster-Whisper / SenseVoice 模型
  `-- Backend Service -> 独立 SQLite + Web 调试台
```

重要边界：

- Android 已接入 `server/stt-service`，支持文件转写、WebSocket 实时预览和 Bearer Token。
- Android 的 LLM 请求直接发送给 Ollama 或云端 API，不经过本仓库 Backend。
- Android 尚未调用 `server/backend-service` 的会议/报告 API；两边数据库当前相互独立，不会自动同步。
- 当前登录只保存本地用户名，不是服务端账号认证。

## 快速开始

### Android

```powershell
cd android
.\gradlew.bat lintDebug assembleDebug
```

APK 输出：`android/app/build/outputs/apk/debug/app-debug.apk`。

### Ubuntu Server

```bash
cd server
bash deploy-ubuntu.sh
```

生产冻结版为 4 核 4 GB、无 GPU 的 CPU `int8` 配置，不使用 Docker。Windows 到 Ubuntu 的 SSH 同步发布、端口、Token、备份和回滚参见 [`server/DEPLOY_UBUNTU.md`](server/DEPLOY_UBUNTU.md)。

## 根目录内容

```text
MeetingNotesApp/
|-- android/          # 独立 Android Gradle 工程
|-- server/           # 独立 Python/systemd 服务端工程，包含 models/
|-- git_shell/        # Windows Git 辅助脚本
|-- git_shell_linux/  # Linux Git 辅助脚本
`-- README.md         # 双端边界与入口
```

`build/`、`data/`、`dist/` 和 `.gradle/` 是历史或本机生成目录，不属于两个工程的源码边界。
