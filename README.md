# OA助手 - 智能会议纪要

基于 Android 的智能会议纪要应用，支持语音转文本和 AI 报告生成。

## 功能特性

- 🎙️ **语音录制** - 会议录音
- 📝 **实时转录** - 语音实时转为文字 (Faster-Whisper / SenseVoice)
- 🤖 **智能纪要** - AI 自动生成会议纪要 (Ollama / 云端大模型)
- 📤 **导出分享** - 支持 Markdown/TXT 格式导出

## 技术架构

```
┌─────────────────────────────────────────────────┐
│                  Android App                      │
│  ┌─────────┐  ┌──────────┐  ┌───────────────┐   │
│  │  UI    │  │ UseCases │  │ Repository    │   │
│  │ (Compose)│  │          │  │ (in-memory)   │   │
│  └─────────┘  └──────────┘  └───────────────┘   │
│       │              │               │          │
│  ┌────────────────────────────────────────┐     │
│  │         Infrastructure Layer           │     │
│  │  STT Engines │ LLM Engines │ Exporter │     │
│  └────────────────────────────────────────┘     │
└─────────────────────────────────────────────────┘
        │                  │
        ▼                  ▼
┌──────────────┐   ┌──────────────────┐
│ STT Server   │   │ Ollama / Cloud  │
│ (Python)     │   │ LLM API         │
└──────────────┘   └──────────────────┘
```

## STT 引擎支持

| 引擎 | 类型 | 说明 |
|------|------|------|
| Faster-Whisper | 本地 (P0) | 推荐，中文效果好 |
| SenseVoice | 本地 (P1) | 阿里开源，中文优化 |
| 云端 ASR | 云端 (P2) | 硅基流动等 |

## LLM 引擎支持

| 引擎 | 类型 | 说明 |
|------|------|------|
| Ollama | 本地 (P0) | qwen2.5:7b 等 |
| 云端 API | 云端 (P1) | OpenAI 兼容接口 |

## 快速开始

### 1. 部署 STT 服务

```bash
cd server
pip install -r requirements.txt
python stt_server.py --port 8001
```

### 2. 部署 Ollama (本地 LLM)

```bash
# 安装 Ollama
curl -fsSL https://ollama.com/install.sh | sh

# 下载模型
ollama pull qwen2.5:7b

# 启动服务
ollama serve
```

### 3. 配置 OA助手

在 App 设置中配置:
- STT 服务地址: `http://localhost:8001`
- Ollama 地址: `http://localhost:11434`

### 4. 编译安装

```bash
# 设置 SDK
export ANDROID_HOME=/path/to/android-sdk

# 编译
./gradlew assembleDebug

# APK 位于
# app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
MeetingNotesApp/
├── app/
│   └── src/main/
│       └── java/com/oa/automation/
│           ├── application/usecase/   # 用例
│           ├── data/local/           # DataStore
│           ├── domain/model/         # 数据模型
│           ├── infrastructure/       # 引擎实现
│           │   ├── stt/             # 语音识别
│           │   ├── llm/             # 大模型
│           │   └── export/          # 导出
│           └── ui/                  # 界面
├── server/
│   ├── stt_server.py                # STT 服务
│   └── requirements.txt
└── OLLAMA_DEPLOY.md                # Ollama 部署指南
```

## 环境要求

- Android SDK 34+
- Kotlin 1.9+
- Python 3.10+ (STT 服务)
- Ollama 0.1+ (本地 LLM，可选)

## 许可证

MIT License
