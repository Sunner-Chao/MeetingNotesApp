# MeetingNotesApp Android 客户端

## 工程定位

Android 客户端负责用户交互、麦克风录音、实时转写预览、最终稿保存、AI 报告生成以及报告导出。会议业务数据保存在设备本地 Room 数据库中。

客户端当前直接连接：

- MeetingNotesApp STT Server：文件上传和 WebSocket 流式转写。
- 本地 Ollama：报告生成和报告对话。
- 云端大模型 API：OpenAI Compatible 或 Claude Messages 格式。
- 云端 ASR：配置自定义地址和 API Key 后进行文件转写。

客户端当前不连接 `server/backend-service`，因此服务端会议 SQLite 不会与手机自动同步。

## 已实现功能

### 1. 启动与导航

- Splash 启动页，根据本地保存的用户名进入登录页或主界面。
- 登录页包含用户名、密码、密码可见性和错误状态。
- 登录状态通过 DataStore 保存，可在设置页退出登录。
- Home、Recording、Report、Settings、VIP 专业模板页面使用 Navigation Compose 导航。
- 注册和忘记密码当前只有占位页面；登录当前接受任意非空用户名和密码。

### 2. 会议管理

- 新建会议，并按当前时间生成默认会议标题。
- 会议列表按创建时间倒序展示。
- 显示会议是否已有报告。
- 修改会议标题。
- 删除会议，同时事务化删除关联转写和报告。
- 从历史会议进入录音页或报告页。
- 对已有转写重新生成会议报告。

### 3. 录音与文字输入

- 申请并检查麦克风权限。
- 使用 `AudioRecord` 录制 16 kHz、单声道、16-bit PCM 音频。
- 录音期间写入 WAV 临时文件，结束时补写标准 WAV Header。
- 前台录音 Service 和常驻通知可在应用退到后台时维持录音会话。
- 页面重建后恢复当前录音 UI 和 WebSocket 回调。
- 停止录音后在应用级协程中执行最终文件转写。
- 支持纯文字输入模式，可跳过录音直接保存为会议转写。
- 支持修改并保存当前会议标题。

### 4. 实时与最终转写

- 录音 PCM 数据通过 WebSocket 实时发送到 `/ws/transcribe-stream`。
- 处理服务端 `partial`、`committed`、状态和错误消息。
- 页面显示已确认文本和实时预览文本。
- 停止录音后将完整 WAV 上传到 `/transcribe` 获取最终稿。
- 最终稿可与会议已有转写合并，避免覆盖历史内容。
- STT HTTP 和 WebSocket 请求均支持 Bearer Token。
- 支持 Faster-Whisper、SenseVoice 和云端 ASR 三种配置模式。

### 5. STT 设置与服务发现

- 配置 STT 引擎、模型、服务地址和访问 Token。
- 配置云端 ASR 地址与 API Key。
- 测试 STT 健康接口连通性。
- 扫描当前局域网常用端口，发现可用 STT Server。
- 应用发现结果后自动填写服务地址、引擎和模型。
- 从设置页调用服务端模型切换接口，并轮询健康状态等待模型就绪。

### 6. AI 报告生成

- 从同一会议的全部转写文本生成报告。
- 支持本地 Ollama 模型，例如 `qwen2.5:7b`。
- 支持 OpenAI Compatible Chat Completions API。
- 支持 Claude Messages API，包括 Anthropic Header 格式。
- 报告包含概述、关键要点、决策、任务、负责人、截止时间和行动项。
- 支持重新生成、删除和保存报告。
- 支持显示/隐藏原始转写。
- 报告页内置多轮 AI 对话，用于润色、调整或询问当前报告。
- 可测试当前 Ollama 或云端 LLM 配置是否可用。

### 7. 报告模板

- APK 内置 8 个 Markdown 模板。
- 常规模板：项目管理、通用会议、讲座论坛、政策解读、技术交流、学术报告。
- 专业模板：工程行业施工日志、建筑专业设计日志。
- 录音前可选择模板，并可编辑模板正文。
- 报告页可切换模板后重新生成。
- VIP 页面可启用/停用两个专业日志模板，并按日期创建对应记录。
- 模板配置通过 DataStore 持久化。

### 8. 报告导出与分享

- 导出 Markdown (`.md`)。
- 导出纯文本 (`.txt`)。
- 导出标准 Office Open XML Word 文档 (`.docx`)，支持结构化表格、待办事项和会议图片。
- 使用 Android `PdfDocument` 生成多页 PDF (`.pdf`)。
- 通过 `FileProvider` 和系统分享面板分享导出文件。
- 可单独分享原始转写文本。

### 9. 本地持久化

- Room 表：会议、转写、报告。
- 会议与转写、报告建立关联，删除会议时清理关联记录。
- 报告按 `meetingId` upsert，重新生成不会产生重复报告。
- Flow/StateFlow 驱动会议列表和报告状态实时刷新。
- DataStore 保存 STT、LLM、模板和登录用户名配置。
- 已禁止 Android Auto Backup 和设备迁移备份应用数据。

## 工程结构

```text
android/
|-- app/src/main/java/com/oa/automation/
|   |-- application/usecase/   # 创建会议、停止录音、生成报告
|   |-- data/local/            # DataStore 配置
|   |-- domain/                # Meeting、Transcript、Report 和仓库接口
|   |-- infrastructure/
|   |   |-- audio/             # WAV 录音和实时音频
|   |   |-- db/                # Room 数据库与 DAO
|   |   |-- export/            # MD/TXT/DOC/PDF 导出
|   |   |-- llm/               # Ollama/Cloud LLM
|   |   |-- service/           # 前台录音服务
|   |   `-- stt/               # STT HTTP/WebSocket 客户端
|   `-- ui/                    # Compose 页面和导航
|-- app/src/main/assets/       # 8 个报告模板
|-- gradle/                    # Gradle Wrapper
|-- settings.gradle.kts
`-- gradlew / gradlew.bat
```

## 构建

环境要求：JDK 17、Android SDK 34。当前本机 SDK 配置为 `D:\pro_sunner\demo_vscode\android-sdk`。

首次在其他机器构建时，根据 `local.properties.example` 创建本机 `local.properties` 并填写 SDK 绝对路径；该文件不会提交到 Git。

可选的非敏感 Claude 默认地址/模型可写入 `local.defaults.env`，格式参见 `local.defaults.env.example`。API Key 不会编译进 APK，必须在应用设置中填写。

```powershell
cd android
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

输出 APK：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

当前 `testDebugUnitTest` 为 `NO-SOURCE`；已有构建和 Lint 验证，但尚未建立 Android 自动化单元测试。

## 已知边界

- 登录不是服务端身份认证。
- Android 业务数据仅在本地，不会同步到 Backend。
- 生产公网环境必须使用 HTTPS/WSS；当前为兼容局域网调试仍允许明文 HTTP。
- API Token 存在普通 DataStore Preferences 中，并未使用 Android Keystore 加密。
- 系统强杀应用进程时，无法保证录音最终稿继续转写。
