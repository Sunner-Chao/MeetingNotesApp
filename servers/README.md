# MeetingNotesApp 服务器部署

## 目录结构

```
servers/
├── README.md              # 本文件
├── start-all.bat         # 一键启动所有服务
├── stop-all.bat          # 停止所有服务
├── shared/               # 共享资源
│   └── data/            # 数据库文件
├── stt-service/         # STT 语音转写服务
│   ├── stt_server.py    # STT 服务器主程序
│   ├── requirements.txt # Python 依赖
│   ├── start.bat        # 启动脚本
│   ├── stop.bat         # 停止脚本
│   └── logs/           # 日志目录 (自动创建)
└── backend-service/     # Web 后端服务
    ├── web_backend.py   # 后端服务器主程序
    ├── requirements.txt  # Python 依赖
    ├── start.bat        # 启动脚本
    ├── stop.bat         # 停止脚本
    └── logs/            # 日志目录 (自动创建)
```

## 快速启动

### 方式一：一键启动 (推荐)
```
双击运行 start-all.bat
```

### 方式二：选择 STT 引擎启动
两个 STT 引擎都固定使用 `8888` 端口，便于 Android 端统一连接；同一时间只能启动其中一个。

```
start-stt-faster-whisper.bat
start-stt-sensevoice.bat
```

### 方式三：分别启动
1. 先启动 STT 服务：`stt-service\start.bat faster-whisper small` 或 `stt-service\start.bat sensevoice SenseVoiceSmall`
2. 再启动后端服务：`backend-service\start.bat`

## 服务地址

| 服务 | 地址 | 说明 |
|------|------|------|
| STT 服务 | http://localhost:8888 | 语音转写服务 |
| Web 后端 | http://localhost:8090 | API 服务 |
| 调试页面 | http://localhost:8090/web | Web 调试界面 |

## 配置说明

### STT 服务配置
通过环境变量配置：
- `STT_ENGINE`: STT 引擎类型 (faster-whisper / sense-voice)
- `STT_MODEL`: 模型名称 (tiny / base / small / medium / large-v3)
- `STT_MODEL_ROOT`: 模型文件根目录
- `STT_PORT`: 服务端口 (默认 8888)

### 后端服务配置
通过环境变量配置：
- `STT_SERVICE_BASE_URL`: STT 服务地址 (默认 http://127.0.0.1:8888)
- `WEB_BACKEND_PORT`: 后端服务端口 (默认 8090)
- `WEB_BACKEND_DB_PATH`: 数据库文件路径

## 停止服务

### 方式一
```
双击运行 stop-all.bat
```

### 方式二
分别停止：
```
stt-service\stop.bat
backend-service\stop.bat
```

## 常见问题

### 1. STT 服务启动失败
- 检查 Python 是否安装: `python --version`
- 检查模型文件是否存在
- 查看日志: `stt-service\logs\stt.err.log`

### 2. 后端无法连接 STT
- 确认 STT 服务已启动
- 检查端口是否被占用: `netstat -ano | findstr 8888`
- 确认防火墙允许访问

### 3. 手机无法连接
- 确保手机和服务器在同一局域网
- 使用服务器的局域网 IP 地址 (如 http://192.168.1.100:8888)
- 在 Android 设备的 STT 设置中填入正确的地址

## 模型文件

STT 模型文件应放在项目根目录的 `models` 文件夹下：

```
MeetingNotesApp/
├── models/
│   └── faster-whisper/
│       └── small/
│           └── model.bin
└── servers/
    └── stt-service/
        └── stt_server.py
```

## 部署到 Windows 服务器

1. 复制整个 `servers` 文件夹到服务器
2. 确保服务器已安装 Python 3.8+
3. 运行 `start-all.bat` 启动所有服务
4. 配置防火墙允许 8090 和 8888 端口访问
