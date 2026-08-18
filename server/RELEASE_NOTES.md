## 1.2.21

- 本地转写正式统一为“智悟本地通用模型”和“智悟本地灵听模型”，云端回退统一为“智悟增强云模型”。
- Debug/AVD 与 Release STT 地址分离；正式包使用 `https://lstwin.space`，构建阶段拒绝缺失或非 HTTPS 的发布地址，避免升级时重新带入本地调试地址。
- 完善研学考察和论坛会议的图文纪要、人工记录、现场拍摄、时间标记与阶段回看流程。
- 修复本地转写回退、设置页主题状态和 Android 更新检查相关问题。
- Android OTA 继续由服务端原子发布，严格递增版本号并只保留最新版本和上一版本。

## 1.2.20

- 账号入口改为国内移动端优先：手机号和邮箱验证码为主入口，微信为个人快捷入口，用户名密码保留给旧账号，QQ 不再下发，飞书标记为团队版入口。
- 游客无需注册即可进入主工作区并完成本地录音；首次云端转写或 AI 纪要会要求登录，音频、转写草稿和 Room 数据不会被清理，登录后自动将本机会议归属并同步到账号。
- 新增验证码哈希存储、发送冷却、过期时间、最大尝试次数和一次性消费；生产环境通过私有 Webhook 接入短信/邮件供应商，调试验证码默认不返回也不写日志。
- 商业计费由“纪要次数”迁移为“转写分钟 + AI Credits + 团队席位”；STT 仅成功后按实际 `duration_ms` 结算，Agent 预留 Credit、失败退款，同会议 24 小时内三次重新生成免费。
- 新增幂等用量账本与客户端 `X-Usage-Key`/`usage_key`，防止网络重试重复扣费；旧请求次数字段和旧登录接口继续作为兼容视图保留。
- 项目管理报告第八项统一为“后续研究与储备事项”；Word/PDF 结构化导出继续兼容“后续沉淀事项”“后续研究及储备事项”和 Backlog 等旧标题。
- 修复研学考察暂停后立即继续时的阶段串段：暂停或结束瞬间冻结当前段的转写、时长、标记数和图片数，异步保存不再读取继续录音后的可变内容。
- 研学纪要完成页改为读取真实 Journey Stage 记录，完整展示每一段的名称、状态、时间和影像数量；没有图片的有效阶段也会保留，阶段较多时仅在时间线内部横向滚动。
- 研学完成页阶段节点支持点击回看，详情显示该段状态、时间、影像/地点数量和可选中复制的阶段转写；图片继续由统一照片卡片管理，避免重复入口。
- 阶段详情增加按标记时间排序、去重的图文锚点，并可直接打开仅包含本段附件的统一图片浏览器；图片查看与删除继续复用原有附件链路。
- 批量图片导入增加顺序处理、逐张进度、重复触发防护和失败汇总；相机、相册及位置权限页面重建后继续保留原标记与研学阶段归属，切换会议会取消旧导入。
- 修复快速会议新建后回灌上一场实时转写的问题：只有会议 ID 匹配且仍处于启动、录音或停止过程的会话，才允许恢复流式预览。
- 实时预览降低体感延迟：本地模型首轮按最小音频窗口启动识别，后续仍保持原滚动步长；Android 活动录音只排版最近转写窗口并取消连续滚动动画，完整文本继续保存且停止后完整显示。
- Android 更新统一由服务端 OTA 通道发布：版本号必须严格递增，服务器强制只保留最新版和紧邻上一版；客户端前台检查强制绕过缓存，连续发布多个版本时只提示最后一次成功发布的最高版本。
- 首页快捷区由四宫格收敛为“快速会议、文件导入”两张全宽主卡，移除预定会议和常用设置入口，并按参考稿统一蓝青渐变、立体图形、说明和胶囊操作按钮；应用图标生成链路裁除重复白色圆角中间层，仅保留最外围圆角。

## 1.2.18

- 修正实际生效的 Siri 录音布局，四类纪要模板固定为一屏 2×2 自适应网格，不再因自动滚动隐藏通用会议。
- 右上角菜单移除与左上角重复的“返回”和独立“会议图片”入口；现场拍摄与相册导入统一由左下角图文标记发起，保留时间戳、转写锚点和导入成功后自动闭合关联。

## 1.2.17

- 全局视觉收敛为微软 Fluent 蓝与中性灰，首页快捷区、录音页、纪要页和设置页不再使用粉紫青橙的装饰性炫彩组合；红色和警示色仅用于错误等语义状态。
- 录音页四类纪要模板改为固定 2×2 自适应网格，通用会议、项目管理、论坛会议和研学考察在一个页面内完整展示，保留点击选择与长按查看说明。
- 转写、纪要生成和重新生成使用贴合卡片真实圆角的微软蓝环绕轨迹，移除突兀的白色粗光头和多色尾迹，卡片与全屏处理状态采用同一细线动效语言。

## 1.2.16

- 录音页“标记”升级为显式图文锚点：自动提取最近一句转写并红色高亮，随后可拍摄现场照片或从相册导入；图片以固定标记 ID、录音时间和文字锚点绑定，导入成功后自动闭合本次配图状态。
- 右上角会议图片入口复用同一标记链路，录音中拍照或导图不再隐式猜测最后一个未使用标记；取消选择时保留文字标记，可稍后继续配图。
- 研学考察不再依赖右上角“开始/暂停旅程”操作：开始录音自动建立首段，暂停时按新增转写、标记或图片证据判断是否暂存，继续录音自动建立下一段，空暂停不会创建空阶段。
- 研学阶段只保存阶段转写增量，整场录音继续写入同一个 WAV；完整会议转写优先用于总纪要，阶段快照仅用于阶段笔记，避免重复总结同一段内容。

## 1.2.15

- Android 的智悟本地通用模型、智悟本地灵听模型和智悟增强云模型统一采用动态 STT 超时配置；默认允许最终稿等待 4 小时、录音上传 1 小时，避免 3 至 4 小时论坛录音在服务端完成前被客户端提前断开。

## 1.2.14

- 会议纪要主类型统一为通用会议、项目管理、论坛会议、研学考察四类；通用会议由 AI 根据行政、头脑风暴、杂谈、讲座沙龙等真实内容动态调整章节。
- 项目管理模板将“backlog 候选（可沉淀）”改为“后续沉淀事项”，表格字段改用通俗中文。
- 新增论坛会议模板，重点保留主持人、串场、议程时间线、主题演讲、圆桌讨论和现场问答。
- 长音频上传上限默认提升至 1 GiB；超过 45 分钟的本地 Faster-Whisper 录音按 30 分钟分段，腾讯云按 40 分钟或单次体积限制分段，段间保留 3 秒并去重合并。
- 长音频分段阈值、段长、重叠时长和上传上限均支持服务端环境变量动态覆盖，健康检查会公开当前生效策略。

## 1.2.13

- 修复 100 MiB 以上录音调用腾讯云最终转写时返回 HTTP 413：服务端接收上限提高到 256 MiB，超出腾讯单次请求限制时自动按 40 分钟分段、保留 3 秒上下文重叠并合并云端结果；该分段路径不回退本地模型。
- 修复 PWA 登录/注册字段约束与服务端不一致：用户名统一为 3 至 32 个字符、密码统一为 8 至 128 个字符，不再把可解释的 FastAPI 校验错误显示成笼统 HTTP 422。
- PWA 结束录音后先持久化音频，再自动刷新账户令牌并生成最终转写；首页和会议页导入音频同样自动启动 STT，失败时保留音频与手动重试入口。
- 增加认证字段校验、服务端错误翻译和音频自动转写浏览器回归测试。

## 1.2.12

- 新增智悟本轻享版 PWA，通过同源 HTTPS 复用生产账号、会员额度、Agent、STT 和云服务器 SQLite 数据库，可由 iPhone Safari 添加到主屏幕。
- 会议标题、模板、转写与纪要采用离线优先同步；以用户和会议隔离，使用更新时间合并与删除墓碑，避免离线旧设备恢复已删除记录。
- PWA 可跨设备查看并恢复账户所属的服务端会议音频；服务端以短期账户令牌代理 STT，不向浏览器暴露管理密钥。
- 原生部署脚本会先构建 PWA，并将 `pwa-dist` 与 Backend 放入同一不可变 release；Nginx 安装脚本幂等发布 `/app/`、`/api/`、`/health` 与 `/web`。

## 1.2.11

- 移除实时识别的应用侧额度预留、月度额度保护和 1,800 秒（30 分钟）会话上限。会议持续期间，服务端只受腾讯官方规则、网络连接和服务端并发资源约束。
- 标准免费档的实时和录音文件识别均不使用应用侧额度账本；仅管理员显式开启的臻享付费档保留录音文件时长账本。
- 原生安装脚本补齐两档云模型和额度账本参数的安全默认值，默认不启用付费高精度路径。

## 1.2.10

- 腾讯云 ASR 拆分为“腾讯标准云端（免费额度）”与“腾讯高精度云端（付费）”两档：标准档固定使用 `16k_zh`，高精度档使用 `16k_zh_en`。
- 旧版 `tencent-flash`、`tencent-realtime` 协议一律映射到标准档，无法再意外进入大模型付费路径。
- 服务端新增 SQLite 原子额度预留账本：录音文件按实际时长预留；结束、取消、异常均结算或释放。实时会话不使用该账本。
- 高精度档必须同时启用并设置正数月度上限才可用，默认关闭；Android 改为显示服务端保护账本，不再把腾讯查询结果误作免费额度保证。

## 1.2.9

- 新增受账户令牌保护的腾讯云 ASR 月度用量接口，按账户级统计实时识别与录音文件识别极速版时长，并以 5 分钟缓存降低查询频率。
- Android 服务设置显示两项腾讯云免费额度进度和手动刷新入口；会议页在剩余不足 1 小时、15 分钟或已耗尽时给出分级提醒。
- 本地 Faster-Whisper 最终稿默认不再注入通用中文标点提示，避免短音频把提示词回显成转写；旧提示词会在部署时自动迁移，最终稿仍按分段停顿保守恢复标点。
- 修复本地实时预览从临时文本提升为稳定文本时丢弃累计内容的问题；Android 同时防御同一流式会话内的灾难性文本回退，字数不再重新从零增长。
- Android 会议记录页在生成纪要前即可读取服务器归档音频，并分别通过系统文档选择器保存或通过系统分享面板发送；纪要详情页原有分享入口继续保留。
- 腾讯云额度上限、区域、时区、缓存时长及本地终稿标点参数全部支持服务端环境动态覆盖，腾讯云密钥仍不下发 Android。
- Android Gradle 仓库支持通过属性或环境变量临时注入镜像，默认仍使用官方仓库。
- Release: `1.2.9-20260722105917547`

## 1.2.8

- 新增腾讯云混合识别：16 kHz 单声道 PCM 按 200 ms 分帧中继到实时语音识别，稳态和可修订文本继续复用现有 Android 预览协议。
- 录音结束后复用服务端已落盘 WAV 调用录音文件识别极速版，无需手机再次上传整段音频；实时或极速版异常时分别回退到本地预览和 Faster-Whisper 最终稿。
- 腾讯云实时地址、引擎、并发、签名时效、连接/结束超时、帧周期和排队窗口全部由环境动态配置，AppID 与密钥继续仅保留在服务端。
- Android 新增独立“腾讯云混合识别”选项，启动前刷新账户 STT 令牌；旧版托管腾讯最终稿配置会自动迁移到混合模式，第三方云端 ASR 配置保持不变。
- Release: `1.2.8-20260722054650393`

## 1.2.7

- Android 文本导入改为大文本友好链路：原始 Clipboard/URI 读取、IO 文件解析、实际字数显示，并加入飞书、微信、QQ 动态快捷入口与 ACTION_VIEW 文本打开支持。
- 流式预览按 Server `session_id` 保留重连前片段，修复网络重连后预览清空、总字数重新增长的问题；最终 WAV 转录策略不变。
- 会议音频由写入 Downloads 改为 FileProvider 缓存后调起系统分享面板，不在公共下载目录保留副本。
- 新增腾讯云录音文件识别极速版服务端中继，兼容 Android 现有 OpenAI transcription 协议；AppID、SecretID 和 SecretKey 仅从 Server 环境读取。
- 腾讯云中继默认关闭，提供安全交互配置脚本；推荐 `16k_zh_en` 大模型用于中英粤、方言及高噪声/远场会议。
- Release: `1.2.7-20260722003332838`

## 1.2.6

- 服务端新增按账户和会议隔离的原始会议音频归档，保留期、目录及容量上限均由环境配置；Android 纪要页可列出并下载对应会议音频。
- 录音支持直接放弃并删除未完成文件；最终转录和纪要生成可从页面或后台通知立即终止，客户端网络请求会同步取消。
- 文本粘贴不再受 Agent 网关固定字符数和请求 JSON 大小限制；可通过环境变量重新设置部署上限，`0` 表示不设置应用层上限。
- Android 注册为文本分享接收方，可从飞书、微信、QQ 等应用分享文字或文本文件导入；文本页改为剪贴板优先、文件选择作为备用入口。
- Cloud ASR 保留通用适配代码但继续作为待办能力，不纳入默认转写流程。
- Release: `1.2.6-20260721234853473`

## 1.2.5

- STT 共享模型切换改为管理员静态令牌专用；普通账户短期 STT 令牌仅可用于健康检查、流式预览和最终转写。
- 对已加载的同一 STT 引擎与模型返回无操作结果，避免手机端重复选择时重新加载模型并长时间阻塞服务。
- Android 设置页默认关闭共享服务器模型切换，改为稳定的 Faster-Whisper 与云端 ASR 选项；切换失败时保留原配置，等待上限可通过构建环境配置。
- Android 云端 ASR 完成 OpenAI 兼容最终转写：支持根地址、`/v1` 或完整 `/audio/transcriptions` 地址，模型与超时均可动态配置；云端模式录音结束后上传最终稿，不再错误连接本地实时 WebSocket。
- 转写原文在录音页、完整内容弹窗和报告页支持长按选中复制。
- Release: `1.2.5-20260721100038690`

## 1.2.4

- 新增管理员永久删除普通用户 API；同步清理账户会话、会员权益、充值记录、用户 Agent 令牌和 Agent 任务。
- Android “我的/用户管理”增加删除按钮与不可撤销操作二次确认；管理员账号和当前管理员自身禁止删除。
- 录音页选择纪要模板后保持模板列表展开，由用户手动收起。
- “悟”字应用图标改为楷书笔意，并增加暖金灵光识别点；自适应图标、启动页、首页和 legacy PNG 保持一致。
- Release: `1.2.4-20260721034304125`

## 1.2.3

- 普通用户注册后自动获得 Free 套餐和 10 次一次性 AI 试用额度；旧零额度普通用户在服务初始化时幂等补齐。
- 新增账户会话凭证刷新 API，审批后 Android 无需退出登录即可同步套餐、Agent 额度和 STT 令牌。
- STT 改为同时接受服务端静态管理令牌与短期 HMAC 用户令牌；全局 STT 密钥不再下发到客户端。
- Android 在 VIP/我的手动刷新及每次录音启动前刷新账户凭证，修复充值后 STT 令牌无效。
- 应用图标单字由“智”调整为“悟”。
- Release: `1.2.3-20260721030607927`

## 1.2.2

- VIP 套餐调整为轻享月卡 30 次和专业月卡 150 次 AI 处理额度。
- Android VIP 页面去除与“我的”重复的额度卡，合并专业模板与记录入口并压缩为单页主视图。
- Android 启动动画统一使用应用图标，首页品牌文案调整为“智慧”“领悟”“本源”。

## 1.2.1

- Android PDF 导出将 Markdown 表格渲染为带表头、边框、自动换行和分页的规范表格。
- Codex 与 Claude 推理强度改为独立字段，默认均为 `medium`，支持 Android 服务设置逐提供方调整。
- Android 最终转录和会议纪要后台任务透出阶段、确定进度和服务端不确定进度状态。

# MeetingNotesApp Server 1.2.0

This native Ubuntu release adds database-backed Android accounts and VIP entitlements.

## Changes from 1.1.11

- Adds SQLite users, scrypt password hashes, expiring Bearer sessions, registration, login, logout, and account profile APIs.
- Bootstraps the administrator from deployment-only environment variables; credentials are never embedded in source or APKs.
- Issues a deterministic per-user Agent token from a server-side HMAC secret, preserving the existing Agent gateway enforcement path.
- Adds configurable VIP plans, pending recharge orders, administrator approval/rejection, atomic quota grants, and construction-log entitlement unlocks.
- Couples per-user Agent-token expiry to active login sessions and revokes sessions plus Agent access when an administrator disables a user.
- Prevents Claude CLI stream metadata and internal thinking events from being returned as report content; an empty final answer now fails cleanly and permits the authorized image fallback path.
- Makes Codex the Android default Agent and applies a dynamically configurable `high` Codex reasoning effort on the Server.
- Reuses the complete PCM stream already received by the Server for final transcription, eliminating the second full WAV upload after an uninterrupted recording; interrupted streams retain the existing file-upload fallback.
- Keeps final recognition on validated `small + int8 + beam=5 + batch=1`; batch 4 remains opt-in after benchmarks showed a quality regression despite higher speed.
- Adds Android account, plan, order, administrator approval, and Agent-token integration tests.
- Pricing and quota amounts are loaded from `config/account-plans.json` and can be revised independently of application code.
- Payment-provider callbacks are not included; recharge orders require administrator approval.

# MeetingNotesApp Server 1.1.11

This native Ubuntu release keeps revisable preview text cumulative while reducing CPU pressure on the 4-core host.

## Changes from 1.1.10

- Continues revisable preview text across overlapping windows instead of replacing the visible history with only the latest candidate.
- Uses an 8-second snapshot, 4-second overlap, and 4-second step; `small + beam=1` stays ahead of real-time audio without the accuracy loss measured for `tiny`.
- Keeps a two-character provisional candidate available, with VAD, confidence, no-speech and hallucination safeguards; completed files still use `small + beam=5`.
- Release: `1.1.11-20260718023549710`

## 1.1.10

This native Ubuntu release restores visible revisable live preview while keeping the completed transcript on the final high-accuracy decoder.

## Changes from 1.1.9

- Uses the pinned `small` model with a no-retry `beam_size=1` decoder for preview; this avoids the final short-result retry blocking live updates.
- Starts preview after two seconds of audio and advances the rolling window every two seconds, with up to sixteen seconds of context and eight seconds of overlap.
- Allows a two-character high-confidence candidate as replaceable preview text; only repeated stable candidates are merged into the confirmed preview buffer.
- Keeps VAD, no-speech filtering, hallucination suppression, Simplified-Chinese normalization, and the final `small + beam_size=5` full-file transcription unchanged.
- Adds a privacy-safe CPU benchmark for comparing preview profiles without printing meeting content.
- Release: `1.1.10-20260718015538178`

## 1.1.9

This native Ubuntu release prevents short high-confidence fragments from appearing as noisy live preview text.

## Changes from 1.1.8

- Requires at least four effective characters for both confidence-gated and stability-gated preview output.
- Keeps shorter fragments in the visible processing state instead of rendering them as transcript text.
- Adds regression coverage for a high-confidence two-character fragment.
- Release: `1.1.9-20260718012524338`

## 1.1.8

This native Ubuntu release makes live preview and final file transcription share one Faster-Whisper decoding strategy.

## Changes from 1.1.7

- Uses the same `small` model instance or model artifact for preview and final transcription.
- Routes both paths through one decoder: beam size 5, VAD enabled, Chinese language, and no previous-text conditioning.
- Applies the same short-result retry, hallucination suppression, whitespace cleanup, and Simplified-Chinese conversion.
- Keeps confidence and cross-window stability as a display-only quality gate; neither changes model decoding nor affects the final transcript.
- Treats each bounded-window result as replaceable preview text; only the completed file transcription is authoritative.
- Waits for at least eight seconds of audio before the first high-accuracy preview inference.
- Holds unstable low-confidence candidates instead of rendering likely noise or misrecognition.
- Release: `1.1.8-20260718011120910`

## 1.1.7

This native Ubuntu release restores visible live transcription without allowing low-confidence preview text into the final transcript.

## Changes from 1.1.6

- Keeps strict segment filtering as the source of committed streaming text.
- After consecutive fully rejected windows, emits the current decoded window as replaceable `preview_text` only.
- Never merges fallback preview text into committed text or the final file transcription.
- Continues to suppress known streaming hallucination phrases.
- Adds regression coverage for the rejection threshold, Simplified-Chinese normalization, and hallucination suppression.
- Release: `1.1.7-20260717103556084`

## 1.1.6

This native Ubuntu release makes every Agent CLI invocation use a fresh, non-persistent session.

## Changes from 1.1.5

- Keeps Codex requests isolated with `codex exec --ephemeral` and an independent task directory.
- Gives every Claude request a newly generated UUID through `--session-id`.
- Keeps Claude session history off disk with `--no-session-persistence`.
- Adds regression coverage that rejects reused Claude session IDs and missing Codex ephemeral mode.
- Release: `1.1.6-20260717100508267`

## 1.1.5

This native Ubuntu release makes final Simplified-Chinese transcription and image report requests reliable.

## Changes from 1.1.4

- Extracts structured Claude CLI errors from `stream-json` stdout when stderr is empty.
- Falls back from a failed Claude image task to Codex when the request token permits Codex.
- Keeps text-only Claude requests on the explicitly selected provider.
- Release: `1.1.5-20260717092406537`

## Changes from 1.1.3

- Applies OpenCC to the final Faster-Whisper HTTP response, not only WebSocket previews.
- Extends the Nginx `/api/` upstream timeout to 660 seconds for Agent image reports.
- Marks orphaned queued/running Agent tasks as failed after a service restart.
- Keeps the bounded Agent worker profile for the 4-core/4-GB host.

## Frozen validation

- Release: `1.1.4-20260717091437148`
- Server tests: 25 passed
- Final STT and streaming STT both return Simplified Chinese
- Nginx Agent route timeout: 660 seconds
- Codex and Claude image adapters remain covered by tests

## Changes from 1.1.2

- Reuses the loaded Faster-Whisper `small` model for live preview and final transcription.
- Commits only the settled first half of overlapping windows; the unstable tail is decoded again with future context.
- Rejects low-confidence and high no-speech segments instead of failing open with raw model output.
- Normalizes STT preview and final output to Simplified Chinese on the server.
- Fixes Claude CLI image requests by using bidirectional `stream-json` and parsing the final result event.
- Maps Android Agent HTTP errors to actionable Chinese messages.

## 1.1.3 validation

- Release: `1.1.3-20260717080729073`
- Host: Ubuntu 22.04, 4 CPU, 3719 MB RAM, no GPU
- Real-time replay: 78.46 seconds of phone audio, 4 accepted preview updates
- Preview/final lengths: 30/26 characters; edit similarity improved from 7.2% to 67.9%
- Average/max preview inference: 2.92/12.48 seconds in local cold-state replay
- Codex image report and Claude stream-output adapter tests pass

## Changes included from 1.1.1

- Allows Codex CLI to load the isolated service account's custom provider configuration.
- Supports explicit relay credential environment variables for Codex CLI and Claude CLI.
- Reports relay-backed providers as authenticated without requiring first-party OAuth.
- Keeps relay keys in the root-managed production environment file and out of the release archive.

## Agent gateway included since 1.1.0

- Adds `/api/agent` with independent Bearer authentication.
- Adds per-token request quotas, provider permissions, expiry and disable controls.
- Adds a bounded single-worker Agent queue for the 4-core/4-GB server profile.
- Adds Codex CLI and Claude CLI adapters with image attachment support.
- Adds private task history and quota/health endpoints.
- Adds administrator token issue/list/disable endpoints under existing Web authentication.

## Runtime profile

- Faster-Whisper `small`, CPU `int8`
- Two concurrent inference slots with two CPU threads each
- Bounded FIFO queue with 16 waiting jobs
- One application process so the model is loaded only once
- systemd limits: 3 GB memory, 350% CPU, 128 tasks
- Backend Service enabled behind Nginx HTTPS
- Agent queue: one active CLI task and eight waiting tasks

## Security

- Backend port 8090 is localhost-only and should not be opened in the cloud security group.
- Nginx handles HTTPS and forwards `/web`, `/health` and `/api/*` to Backend.
- Web login uses `ubuntu` plus the separate Web token. SSH keys/passwords remain server access credentials only.
- Agent tokens are hashed in SQLite; plaintext is returned only when issued.
- CLI processes run as the isolated `meetingnotes` service account. OAuth credentials are never copied from the SSH account; explicitly configured relay API credentials are provisioned through the root-managed environment file.
## 1.2.25

- Android OTA 版本号升级为 `10225`。
- 修复移动蜂窝 IPv4 下本地 STT WebSocket 建连后音频传输中断的问题：正式包固定使用云端 IPv4 中继，OkHttp 心跳调整为 15 秒；切换腾讯云前等待目标服务返回有效会话 ID，避免把仅握手成功误报为可用。
- 云端 Nginx 前门关闭 PCM WebSocket 压缩并关闭代理缓冲，长连接读写超时统一为 3600 秒；已通过 `nginx -t` 和 reload。
- 发布时保留 `10225` 与上一版 `10224`，不得发布临时 debug APK。

## 1.2.24

- 修复移动蜂窝网络下 STT 可能绕过云端 IPv4 中继的问题：Release 包通过构建配置将 `lstwin.space` 的连接固定到云端 IPv4，仍保留域名 SNI、证书校验和 AAAA 记录。
- Debug/AVD 继续使用 `10.0.2.2`，WiFi、IPv6 直连和其他服务域名不受影响。
- Android OTA 版本号升级为 `10224`，发布时保留 `10224` 与上一版 `10223`。
