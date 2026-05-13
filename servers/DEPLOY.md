# MeetingNotesApp 服务端部署指南

## 部署架构

```
┌─────────────────────────────────────────────────────────┐
│                    Windows 服务器                         │
│  ┌──────────────────┐      ┌──────────────────────────┐ │
│  │   STT 服务        │      │   Backend 服务           │ │
│  │   端口: 8888      │◄────►│   端口: 8090             │ │
│  │   Faster-Whisper  │      │   FastAPI + SQLite       │ │
│  └──────────────────┘      └──────────────────────────┘ │
│           ▲                          ▲                   │
└───────────┼──────────────────────────┼───────────────────┘
            │                          │
            ▼                          ▼
     ┌─────────────┐           ┌─────────────┐
     │ 模型文件     │           │ Android App │
     │ models/     │           │ (局域网访问) │
     └─────────────┘           └─────────────┘
```

## 需要复制的文件

### 方式一：完整复制（推荐）

复制整个 `servers` 和 `models` 目录：

```
MeetingNotesApp/
├── models/                    # 模型文件 (~464MB)
│   └── faster-whisper/
│       └── small/
└── servers/                   # 服务端代码
    ├── start-all.bat
    ├── stop-all.bat
    ├── init-server.bat
    ├── README.md
    ├── shared/
    │   └── data/             # 数据库目录（自动创建）
    ├── stt-service/
    │   ├── stt_server.py
    │   ├── requirements.txt
    │   ├── start.bat
    │   └── stop.bat
    └── backend-service/
        ├── web_backend.py
        ├── requirements.txt
        ├── start.bat
        └── stop.bat
```

**注意**：不要复制 `runtime/` 目录（Python 虚拟环境），在目标机器上重新创建。

### 方式二：最小复制

仅复制必要文件：

```
models/faster-whisper/small/   # 模型文件
servers/
├── start-all.bat
├── stop-all.bat
├── init-server.bat
├── shared/
├── stt-service/
│   ├── stt_server.py
│   ├── requirements.txt
│   ├── start.bat
│   └── stop.bat
└── backend-service/
    ├── web_backend.py
    ├── requirements.txt
    ├── start.bat
    └── stop.bat
```

## 部署步骤

### 1. 环境要求

- Windows 10/11 或 Windows Server 2016+
- Python 3.10+ (推荐 3.11)
- 内存: 最低 4GB，推荐 8GB+
- 磁盘: 至少 2GB 可用空间

### 2. 安装 Python

从 https://www.python.org/downloads/ 下载安装

安装时勾选 **"Add Python to PATH"**

验证安装：
```cmd
python --version
pip --version
```

### 3. 复制文件

将 `servers` 和 `models` 目录复制到目标服务器，例如：
```
D:\MeetingNotesApp\
├── models\
└── servers\
```

### 4. 初始化环境

```cmd
cd D:\MeetingNotesApp\servers
init-server.bat
```

这会自动：
- 创建 Python 虚拟环境
- 安装所有依赖包

### 5. 启动服务

```cmd
cd D:\MeetingNotesApp\servers
start-all.bat
```

### 6. 验证服务

打开浏览器访问：
- STT 健康检查: http://localhost:8888/health
- Backend 健康检查: http://localhost:8090/health
- 调试界面: http://localhost:8090/web

## 防火墙配置

### Windows 防火墙

以管理员身份运行 PowerShell：

```powershell
# 开放 STT 服务端口
New-NetFirewallRule -DisplayName "MeetingNotes STT" -Direction Inbound -LocalPort 8888 -Protocol TCP -Action Allow

# 开放 Backend 服务端口
New-NetFirewallRule -DisplayName "MeetingNotes Backend" -Direction Inbound -LocalPort 8090 -Protocol TCP -Action Allow
```

### 云服务器安全组

如果部署在云服务器（阿里云、腾讯云等），需要在安全组中开放：
- 端口 8888 (TCP)
- 端口 8090 (TCP)

## Android 客户端配置

在 App 设置中配置服务器地址：

| 配置项 | 值 |
|--------|-----|
| STT 服务地址 | `http://<服务器IP>:8888` |
| Backend 地址 | `http://<服务器IP>:8090` |

示例：
- 如果服务器 IP 是 `192.168.1.100`
- STT 地址: `http://192.168.1.100:8888`
- Backend 地址: `http://192.168.1.100:8090`

## 开机自启动（可选）

### 方式一：任务计划程序

1. 打开 "任务计划程序"
2. 创建基本任务
3. 触发器: "计算机启动时"
4. 操作: "启动程序"
   - 程序: `cmd.exe`
   - 参数: `/c D:\MeetingNotesApp\servers\start-all.bat`
   - 起始位置: `D:\MeetingNotesApp\servers`

### 方式二：启动文件夹

创建快捷方式到：
```
shell:startup
```

## 常见问题

### 1. Python 未找到

确保 Python 已添加到 PATH，或修改 `init-server.bat` 中的 Python 路径。

### 2. 模型加载失败

检查 `models/faster-whisper/small/` 目录是否存在模型文件。

### 3. 端口被占用

```cmd
netstat -ano | findstr "8888"
netstat -ano | findstr "8090"
```

修改 `start-all.bat` 中的端口号。

### 4. 手机无法连接

- 确保手机和服务器在同一局域网
- 检查防火墙是否开放端口
- 使用服务器局域网 IP，不要用 localhost

## 服务管理命令

```cmd
# 启动所有服务
start-all.bat

# 停止所有服务
stop-all.bat

# 仅启动 STT (Faster-Whisper)
start-stt-faster-whisper.bat

# 仅启动 STT (SenseVoice)
start-stt-sensevoice.bat
```

## 目录结构说明

```
servers/
├── start-all.bat          # 一键启动所有服务
├── stop-all.bat           # 停止所有服务
├── init-server.bat        # 初始化 Python 环境
├── shared/                # 共享数据
│   └── data/             # SQLite 数据库
├── stt-service/           # 语音转文本服务
│   ├── stt_server.py     # 主程序
│   ├── runtime/          # Python 虚拟环境（自动创建）
│   └── logs/             # 日志（自动创建）
└── backend-service/       # Web 后端服务
    ├── web_backend.py    # 主程序
    ├── runtime/          # Python 虚拟环境（自动创建）
    └── logs/             # 日志（自动创建）
```
