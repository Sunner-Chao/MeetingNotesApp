# Windows Server 部署

本文用于 Windows 本地开发。Ubuntu 生产环境使用原生 systemd，见 [`DEPLOY_UBUNTU.md`](DEPLOY_UBUNTU.md)。

## 目录要求

只需复制完整的 `server/` 目录，不再依赖 Android 工程或仓库根目录：

```text
server/
|-- backend-service/
|-- stt-service/
|-- models/
|-- shared/data/
|-- init-server.bat
|-- start-all.bat
`-- stop-all.bat
```

## 环境要求

- Windows 10/11 或 Windows Server 2019+
- `uv`；初始化脚本自动安装/使用 Python 3.11.15
- FFmpeg，并加入 `PATH`
- 建议至少 4 核 CPU、8 GB 内存
- GPU 模式需要兼容的 NVIDIA 驱动和 Python CUDA 依赖

## 初始化

在命令提示符中进入服务端目录：

```bat
cd D:\MeetingNotesApp\server
init-server.bat
```

脚本会清理并重建 STT/Backend 各自的 `runtime`，使用精确锁文件安装依赖。生产 STT 统一使用 Faster-Whisper，GPU 主机默认加载 `large-v3-turbo`。

## 启动与停止

启动 Faster-Whisper 和 Backend：

```bat
start-all.bat
```

单独启动 STT：

```bat
start-stt-faster-whisper.bat
```

停止：

```bat
stop-all.bat
```

默认地址：

| 服务 | 地址 |
|---|---|
| STT | `http://127.0.0.1:8888` |
| Backend | `http://127.0.0.1:8090` |
| 调试台 | `http://127.0.0.1:8090/web` |

## 模型目录

模型统一位于 `server/models/`：

```text
server/models/
|-- faster-whisper/large-v3-turbo/
|   |-- model.bin
|   |-- config.json
|   |-- tokenizer.json
|   `-- vocabulary.txt
```

本地模型不存在时，Faster-Whisper 会按模型名称下载到该目录。

## 生成独立部署包

```bat
package.bat
```

脚本在 `server/dist/` 下生成独立 Server 包，包含模型但排除 `.env`、数据库、日志、虚拟环境和 Python 缓存。

## 防火墙与公网

- Android 只需要访问 STT 端口 8888。
- Backend 8090 默认用于本机调试，不建议直接开放公网。
- 公网部署应使用 HTTPS/WSS 反向代理并配置 Token。
- Windows 批处理启动方式主要用于本地或内网；生产环境固定使用 Ubuntu Python 3.11 + systemd。
