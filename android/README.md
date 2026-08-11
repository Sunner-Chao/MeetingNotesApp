# MeetingNotesApp Android 客户端

## 工程定位

Android 客户端负责用户交互、麦克风录音、实时转写预览、最终稿保存、AI 报告生成以及报告导出。会议业务数据保存在设备本地 Room 数据库中。

客户端当前直接连接：

- MeetingNotesApp STT Server：文件上传和 WebSocket 流式转写。
- 本地 Ollama：报告生成和报告对话。
- 云端大模型 API：OpenAI Compatible 或 Claude Messages 格式。
- 腾讯云混合 ASR：标准免费档使用 `16k_zh`，高精度付费档使用 `16k_zh_en`；实时预览由自有 Server 中继腾讯 WebSocket，最终稿复用服务端 WAV 调用极速版，服务端负责额度预留与回退。
- 通用云端 ASR：通过 OpenAI 音频转写兼容接口配置地址、API Key 和模型后进行最终文件转写。
- Backend 账户与 Agent API：注册、登录、VIP、短期 STT 凭证和会议纪要 Agent 请求。

会议、转写和纪要业务数据仍保存在手机 Room，当前不与服务端会议 SQLite 自动同步。

## 已实现功能

### 1. 启动与导航

- Splash 启动页，根据本地保存的用户名进入登录页或主界面。
- 登录页包含用户名、密码、密码可见性和错误状态。
- 登录状态通过 DataStore 保存，可在设置页退出登录。
- Home、Recording、Report、Settings、VIP 专业模板页面使用 Navigation Compose 导航。
- 注册和登录使用服务端数据库账户；新注册普通用户自动获得 Free 套餐和 10 次一次性试用额度。
- 启动页、首页、登录页和桌面图标统一使用最新 W 形书页品牌图标，并针对圆角/圆形系统蒙版保留安全边距。

### 2. 会议管理

- 预定会议，设置日期、时间、纪要模板和提前提醒。
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
- 停止录音后由 WorkManager 执行最终文件转写，并显示上传、服务端识别和保存阶段进度。
- 支持纯文字输入模式，可跳过录音直接保存为会议转写。
- 支持放弃当前录音，立即关闭麦克风/WebSocket 并删除未完成文件。
- 最终转录和纪要生成可从页面进度条或后台通知终止，网络调用同步取消。
- 支持剪贴板优先导入，并可从飞书、微信、QQ 等应用通过系统分享导入文字或文本文件。
- 文本输入不设置应用层字符上限；实际处理量受设备内存和模型上下文限制。
- 支持修改并保存当前会议标题。

### 4. 实时与最终转写

- 录音 PCM 数据通过 WebSocket 实时发送到 `/ws/transcribe-stream`。
- 处理服务端 `partial`、`committed`、状态和错误消息。
- 页面显示已确认文本和实时预览文本。
- 连续流式会话停止后优先按 `session_id` 复用服务端 WAV；流中断或会话不可用时才上传本机完整 WAV。
- 最终稿可与会议已有转写合并，避免覆盖历史内容。
- STT HTTP 和 WebSocket 请求均支持 Bearer Token。
- 默认提供 Faster-Whisper、腾讯云混合识别和通用云端 ASR；SenseVoice 仅在管理员构建显式开启共享模型切换后提供。
- 最终转录进度在可测阶段显示百分比；服务端识别阶段显示不确定进度和当前处理阶段。

### 5. STT 设置与服务发现

- 配置 STT 引擎、模型、服务地址和访问 Token。
- 登录、注册、会员刷新和录音启动会自动获取当前账户的短期 STT 用户令牌；APK 不内置生产凭证，也不下发全局 STT 服务密钥。
- STT、Agent 和账户服务地址可在构建机的 `local.defaults.env` 中配置，示例见 `local.defaults.env.example`。
- 配置云端 ASR 根地址、`/v1` 地址或完整 `/audio/transcriptions` 地址，以及 API Key 与模型名。
- 腾讯云混合识别启动前刷新账户 STT 令牌，Android 不保存腾讯长期密钥；实时或最终云接口异常时由 Server 自动回退。
- 通用云端 ASR 仅在停止录音后上传生成最终稿，不建立本地 STT WebSocket 预览连接。
- 测试 STT 健康接口连通性。
- 扫描当前局域网常用端口，发现可用 STT Server。
- 应用发现结果后自动填写服务地址、引擎和模型。
- 共享服务器模型切换默认关闭；管理员构建开启后仅可使用服务器静态管理令牌，普通账户短期 STT 令牌不能切换全局模型。

### 6. AI 报告生成

- 从同一会议的全部转写文本生成报告。
- 支持本地 Ollama 模型，例如 `qwen2.5:7b`。
- 支持 OpenAI Compatible Chat Completions API。
- 支持 Claude Messages API，包括 Anthropic Header 格式。
- 报告包含概述、关键要点、决策、任务、负责人、截止时间和行动项。
- 支持重新生成、删除和保存报告。
- 支持显示/隐藏原始转写。
- 报告页内置多轮 AI 对话，用于润色、调整或询问当前报告。
- Agent 服务设置分别保存 Codex 推理强度和 Claude 推理强度，默认均为中。
- 纪要生成显示读取、Agent 分析、整理和保存阶段进度；Agent 服务端分析阶段使用不确定进度。
- 可测试当前 Ollama 或云端 LLM 配置是否可用。

### 7. 报告模板

- 主流程固定为四类：通用会议、项目管理、论坛会议、研学考察；另提供 2 个 VIP 专业版式。
- 通用会议覆盖行政会议、头脑风暴、杂谈、讲座沙龙等场景，由 AI 依据原始内容动态增删和重排章节，不机械套用固定结构。
- 论坛会议突出主持串场、主题演讲、圆桌讨论和现场问答；3 至 4 小时录音由服务端按动态配置分段转写并去重合并。
- VIP 专业模板：工程/建筑 施工/设计日志、监理会例会日志。
- 录音前可选择模板，并可编辑模板正文。
- 选择模板后列表保持展开，只有再次点击模板标题时才收起。
- 报告页可切换模板后重新生成。
- 未开通 VIP 的普通用户在 VIP 页面只显示会员权益宣传和月卡申请；VIP 显示专业模板，管理员额外显示充值审批。
- 管理员可在“我的/用户管理”启停或永久删除普通用户；删除前必须二次确认。
- VIP 页面可切换两个专业模板，并按日期创建对应记录。
- 模板配置通过 DataStore 持久化；v7 将旧工程施工/建筑设计模板选择迁移到合并模板。

### 8. 报告导出与分享

- 导出 Markdown (`.md`)。
- 导出纯文本 (`.txt`)。
- 导出标准 Office Open XML Word 文档 (`.docx`)，支持结构化表格、待办事项和会议图片。
- 使用 Android `PdfDocument` 生成多页 PDF (`.pdf`)，支持规范 Markdown 表格、表头、边框、自动换行、分页重复表头，以及图片方向校正、等比缩放、分页和图注。
- 纪要文件统一命名为“会议类型-报告标题-yyyyMMdd-HHmmss.扩展名”，时间使用设备当地时间。
- 通过 `FileProvider` 和系统分享面板分享导出文件。
- 可单独分享原始转写文本。
- 纪要页可按当前会议列出服务端归档音频，并一键调起系统分享面板发送到其他应用；音频仅暂存于应用私有缓存，不写入公共 `Downloads` 目录。

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
|-- app/src/main/assets/       # 主流程、VIP 专业及历史兼容模板资源
|-- gradle/                    # Gradle Wrapper
|-- settings.gradle.kts
`-- gradlew / gradlew.bat
```

## 构建

环境要求：JDK 17、Android SDK 34。SDK 路径由本机未跟踪的 `local.properties` 或构建环境动态提供。

首次在其他机器构建时，根据 `local.properties.example` 创建本机 `local.properties` 并填写 SDK 绝对路径；该文件不会提交到 Git。

可选的非敏感 Claude 默认地址/模型可写入 `local.defaults.env`，格式参见 `local.defaults.env.example`。API Key 不会编译进 APK，必须在应用设置中填写。

### APK 签名与覆盖升级

Android 覆盖升级要求 `applicationId` 和签名证书同时保持不变。不要让不同会话、电脑或发布脚本使用各自的临时 debug/release 密钥。以 `signing.properties.example` 为模板创建 `${user.home}/.meetingnotes/signing.properties`，为 `debug.*` 和 `release.*` 配置同一发布链路所需的稳定 keystore；真实 keystore、密码和该 properties 文件都不进入 Git。也可以通过 `MEETINGNOTES_DEBUG_STORE_FILE`、`MEETINGNOTES_DEBUG_STORE_PASSWORD`、`MEETINGNOTES_DEBUG_KEY_ALIAS`、`MEETINGNOTES_DEBUG_KEY_PASSWORD` 及对应的 `MEETINGNOTES_RELEASE_*` 环境变量注入，适合 CI 或正式发布机。

每次构建 APK 前，Gradle 会运行 `verifyDebugSigning` 或 `verifyReleaseSigning`，输出证书 SHA-256 指纹但不会输出密码，并与版本化的 `signing-fingerprints.properties` 对比。缺少稳定签名配置、未登记指纹或指纹不一致时，构建会直接失败，防止生成无法覆盖升级的 APK。正式发布必须使用 release keystore，不能把 debug keystore 上传到服务器；首次建立正式密钥时需将公开的 release SHA-256 写入指纹表并纳入评审。

```powershell
cd android
.\gradlew.bat verifySigningConfig testDebugUnitTest assembleRelease
```

显式检查两条签名链路：

```powershell
.\gradlew.bat verifySigningConfig
```

输出 APK：

```text
android/app/build/outputs/apk/release/app-release.apk
```

当前 JVM 单元测试已覆盖录音状态、模板/来源映射、图文标记、报告页面、更新提示和数据访问等路径；真机验证仍需按发布批次人工复核。

## 已知边界

- 登录、注册、会员和管理员流程使用服务端账户认证。
- 会议正文、音频和报告当前仍以 Android Room/私有文件为采集过程可信源，尚未完成全部会议成果云同步。
- 生产公网环境必须使用 HTTPS/WSS；当前为兼容局域网调试仍允许明文 HTTP。
- API Token 存在普通 DataStore Preferences 中，并未使用 Android Keystore 加密。
- 系统强杀应用进程时，无法保证录音最终稿继续转写。
- 如果设备上已经安装了不同证书的旧包，Android 无法无损迁移签名；只能找回旧 keystore，或在确认数据备份后做一次性卸载迁移。完成迁移后，后续 APK 必须持续使用同一稳定证书。
