## 1.2.56

- 全量复核 Android、用户端 Web 与 Server 的当前工作区更新，统一版本为 `1.2.56/10256`。
- Android 实时转录支持说话人分段的可修订预览与持久化，暂停/恢复及后台结束流程继续保留完整录音和结构化转写。
- 服务端新增账户会议图片的持久化、账户隔离、备份覆盖与路径约束；用户端 Web 保持实时转录预览、图片归档、批量下载和系统分享。
- 通知中心、活动福利、社区内容和用户端 Web 的标准 HTTPS 路由完成一致性复核；旧 `/web` 管理台地址继续重定向到 `/admin/`。
- 通过 Android 单测、Release/Lint、用户端 Web 测试/类型检查/生产构建及 Server 全量测试后，使用固定签名 APK 走 OTA 原子发布。

## 1.2.55

- 全量复核 Android、用户端 Web 与 Server 更新内容，保持通知中心福利群入口、录音后台驻留提示、Web 实时转录预览、媒体导出分享和持久化修复的一致性。
- 重新执行 Android 单测、Web 单测/生产构建和 Server 全量测试；固定签名 Release APK 通过证书、版本、非 debuggable 与 SHA-256 发布前校验。
- 固定签名 Android Release 版本号升级为 `1.2.55/10255`（10254 已发布，不可复用）。

## 1.2.54

- 通知中心恢复福利群独立入口，活动、通知和福利群按顶部标签管理；福利群继续遵循联系群主、审核后展示二维码的流程。
- 录音页补充后台驻留返回提示，确保离开主页或切换应用时能明确当前录音状态。
- 用户端 Web 接入实时转录预览，录音断线时保留本地分片，最终稿继续通过完整音频上传；会议图片支持批量下载和系统分享。
- 服务端 STT 与会议媒体持久化、恢复链路和测试覆盖同步更新。
- 固定签名 Android Release 版本号升级为 `1.2.54/10254`（10253 已发布，不可复用）。

## 1.2.53

- 用户端 Web 录音接入实时转录预览：浏览器以 AudioContext 采集 16 位 PCM，通过 WSS 连接后的首条鉴权消息复用 STT 流式服务；令牌不进入 URL，最终稿仍以完整音频上传为准，并新增会议图片批量导出/系统分享。
- 支付可靠性修复（代码审查跟进）：
  - 两个支付页面共享同一 ViewModel（MainGraph 作用域），在订单页付款后返回积分套餐页，余额与按钮状态即时同步；进入页面自动刷新。
  - 支付宝收银台防重复调起：调起判定移入 ViewModel（跨页面/跨重建仲裁），同一笔待支付订单只会打开一次收银台；重试通过 paymentAttempt 显式触发。
  - `out_trade_no` 死锁修复：APP 支付显式 `timeout_express=30m`；超过 35 分钟或已关闭/失败的交易在"继续支付"时轮换新单号，订单不再永久卡死。
  - 商家收款账号（`ALIPAY_SELLER_ID`）纳入就绪校验：缺失时支付直接报"配置不完整"，而不是收款后异步通知被静默丢弃；状态接口新增 `seller_configured`。
  - 异步通知处理增加拒绝原因日志（`meetingnotes.payment`）：验签失败、业务字段不匹配、处理异常均可在服务端日志定位。
- 设置页整体重构为单屏布局：外观分段控件、语音转文本/智悟模型双卡、服务地址卡、悬浮球行与底部版本条；详细配置移入底部弹层。移除"云端智悟增强模型·臻享"档位与局域网扫描发现；Agent 服务地址/令牌仅调试构建可见；新增小悟/小智特点说明。
- 首页通知铃铛在有未读时播放摇铃动画；通知中心 tab 下方置顶"智悟本福利群"入口，福利页入群面板移至首位。
- 网页端邀请落地页：带邀请码打开时显示邀请横幅，提供"网页版立即试用"与"下载 Android 版"双入口；登录页常驻 APK 下载链接。
- 支付宝 Android SDK AAR 提交入库，干净检出可直接构建。
- 恢复默认设置增加确认弹窗。
- 固定签名 Android Release 版本号升级为 `1.2.53/10253`（10252 已发布，不可复用）。

## 1.2.52

- 福利群入口改为“先联系群主、提交申请、审核后入群”的流程，未审核用户不会获得群二维码、入群链接或短链。
- 福利页首屏展示群主企业微信名片，支持放大预览与保存到相册；运营管理台支持审核申请及替换名片素材。
- 审核通过、拒绝等结果写入通知中心，用户可在通知中心查看处理进度和结果。
- 二维码文件接口增加审核状态校验，避免绕过前端直接访问未授权资源。
- 固定签名 Android Release 版本号升级为 `1.2.52/10252`；发布时 OTA 仅保留 `10252` 与 `10251`。

## 1.2.51

- 支付不可用提示不再写死"沙箱"：`AlipayConfig.unavailable_reason()` 按实际 `ALIPAY_ENVIRONMENT` 生成文案，并区分"未启用"与"配置不完整（缺少 XX）"。1.2.50 已切到生产环境但仍返回"支付宝沙箱支付尚未启用"，属于误导性提示。
- Android 新增独立的"充值订单"页面（`AccountRechargeOrders` 路由），从"我的"与积分套餐页顶栏均可进入；待支付订单可直接继续支付。
- 积分套餐页改为单屏展示：套餐行按 `weight` 平分视口高度，不再需要下拉。
- 支付方式区改用支付宝官方标识（`ic_alipay_official`，Simple Icons / CC0），并预留置灰的"微信支付 · 即将支持"位。
- 登录页"其他登录方式"改为 `Popup` 浮层覆盖展示，入口行固定高度（紧凑 44dp / 常规 48dp），展开不再顶高页面或引发滚动；浮层空间不足时自动翻转到入口上方并水平收边。
- 修复 Compose 早退崩溃：`return@Scaffold` / `return@Box` 会打乱 composition group，导致订单页进入即 `IntStack.peek2` 抛 `ArrayIndexOutOfBoundsException`。三处早退全部改为 if/else。
- 固定签名 Android Release 版本号升级为 `1.2.51/10251`，SHA-256 为 `2d24faa2d5a638fb0bf46cd965589e6165039b33a8c148285fbd6f07b18beb90`。

## 1.2.50

- 修复支付宝支付无法调起：客户端此前直接调用 `PayTask.payV2` 而从未设置 SDK 运行环境，沙箱订单串被送往生产网关，收银台完全打不开。现在按服务端返回的 `environment` 精确切换，未知或空环境一律走生产网关。
- 服务端支付宝 APP 支付接口首次上线：下单、交易查询、退款、退款查询、关闭交易与异步通知验签。
- 异步通知路径 `/api/payment/alipay/notify` 免 Web 凭据放行；支付宝回调不带任何凭据，此前会被鉴权网关判为 401 导致付款永远无法确认。新增回归测试同时校验放行范围仅限该精确路径。
- 安装脚本为支付宝补齐安全默认值：`ALIPAY_ENABLED=false`、`ALIPAY_ENVIRONMENT=production`，生产主机不会读取任何沙箱密钥文件。
- 临时上线 `points_paylink_probe` 联调测试套餐（¥0.01 / 1 积分），仅用于真机支付联调，结束后立即下线。
- 固定签名 Android Release 版本号升级为 `1.2.50/10250`，SHA-256 为 `465c06c1c6a2ced1b5ae1b5a53ee97051571667189366c9e1ab5b2b6eb166408`。

## 1.2.46

- Android 新增“活动与福利”中心，统一承载邀请码、礼品码与兑换码、每日签到、答题、抽奖、排行榜和动态福利群二维码。
- 首页公告区域与“我的”页面增加活动入口；邮箱注册支持选填好友邀请码，邀请链接可复制和分享。
- 悬浮球升级为蓝色半透明水滴样式，保持录音后台状态入口一致。
- 固定签名 Android Release 版本号升级为 `1.2.46/10246`，SHA-256 为 `9c7f76f1ce4d587b027592abef90c059ad8502336417a9d34cee4a4f2a961f6f`；Server release 为 `1.2.46-20260826151113978`，OTA 仅保留 `10246` 与 `10245`。

## 1.2.44

- 发布本地 STT 连接修复与录音页交互优化：AVD 使用本机入口，真实设备保留 IPv6 本地入口并由腾讯云 IPv4 中继兜底。
- 本地 STT 鉴权刷新不再阻塞录音启动；本地失败时快速重试并显示云端接管状态。
- 暂存录音从录音页正文移入右上角“更多”弹窗，保持实时转录区域宽度与 simple and effective 布局。
- 固定签名 APK `1.2.44/10244` 已通过 OTA 原子发布，SHA-256 为 `a1a0ca51a20c6fdb4e28f38ee371ccd7bd2e226172f908008050de77b949839b`；Server release 为 `1.2.44-20260826120217398`。

## 1.2.43

- 暂停录音后立即允许生成已有内容的会议纪要，暂停期间停止本地 PCM 写入并保留 WAV 会话。
- 实时识别界面根据实际路由同步本地/云端状态；本地失败切换腾讯云时显示云端已接管，重新进入会议仍保留上次偏好。

## 1.2.42

- 研学考察完成页将“会议图片”统一改为“影像集锦”，将“会议纪要”统一改为“参观纪要”；普通会议仍保留原有名称。
- 固定签名 Android Release 版本号升级为 `1.2.42/10242`，SHA-256 为 `c02aac222c1dd32e722ff96de8a2c279348fec2d38e2a8d2d6d04b5f9f38f967`；发布时服务器仅保留最新版本与上一版本。

## 1.2.41

- 修复悬浮球开关在首次申请覆盖层权限后没有保存开启状态的问题，并在权限返回时刷新开关状态。
- 服务设置中的“背景悬浮球”改为“悬浮球”，明确说明录音进入后台时显示。
- Codex CLI 和 Claude CLI 仅更换为用户可见名称“智能体小悟”和“智能体小智”，底层请求标识保持不变。

## 1.2.40

- Android `1.2.40/10240`：录音页未选模板提示改为单行；会议恢复时保留模板和本地/云端识别模式。

## 1.2.39

- Android 图片导入不再设置固定数量上限；录音页新增插图管理，支持逐张删除和确认后一键清空，报告页可继续追加图片。
- 顷刻成稿将“应用导入”改为“其他应用打开”，微信、飞书、QQ入口使用系统文件浏览器，选中文件后自动分流文本或音频导入。
- 移除录音右上角保存/分享音频菜单和顷刻成稿底部语音/生成/导入组控件；自动保存的录音在页面内保留分享图标。
- Android `1.2.39/10239` 固定签名 release 已通过 OTA 原子发布，SHA-256 为 `509547797dd20dd018718063a80db34bb1eb0b86e5153f21dee359be30b91f81`，服务器保留 `10239` 与 `10238`。

## 1.2.38

- 修复转写请求在纪要/转写失败时只留下临时音频的问题：未完成正式归档的音频会原子保存到恢复目录，并记录账号、会议 ID、归档键、文件哈希和失败原因。
- 恢复 2026-08-20 下午管理员“高老师”研学考察的原会议 ID、音频归档与已生成纪要；原始图片二进制未找到，保留报告中的图片说明并明确标注不可恢复。

## 1.2.37

- 会议音频和图片素材统一使用应用持久目录，启动时原子迁移历史缓存引用；旧版裸 PCM 录音可恢复为标准 WAV。
- 结束录音时实时转写为空会自动排队最终音频转写，转写任务失败或中断时可在会议页恢复，不再导致音频、文字和纪要入口丢失。
- 固定签名 APK `1.2.37/10237` 发布前执行证书、SHA-256、非 debuggable、OTA 元数据和双版本留存检查。

## 1.2.36

- 社区近期活动改为五条浙江地区互动吧真实活动详情页，所有链接已跟随跳转验证为 HTTPS 200，并增加外部页面无法调起时的中文提示。
- 待完善会议继续恢复已保存的模板选择，插图位置预览与“取消/确认”交互保持可续接。
- 固定签名 APK `1.2.36/10236` 非 debuggable，SHA-256 为 `f29d7eb54811ab2cd9884484a962dc1880564486d9ab8996c7378936fd2ddbe7`。
- 正式 release：`1.2.36-20260820105035277`。

## 1.2.35

- “我的”页积分账户卡片仅显示总积分，按用户要求将“转写每分钟 10 分”保留在积分规则中。
- “积分明细”移除“可用智能体”信息，避免展示与积分账户无关的能力列表。
- 删除与头像个人资料跳转重复的“账户管理”入口，头像仍可进入个人资料。
- 固定签名 APK `1.2.35/10235` 非 debuggable，SHA-256 为 `073d992cd090a17a00f8a5f606af4cd64a76902976438bdb36e53b39cd9f09de`。
- 正式 release：`1.2.35-20260820094242296`。公网 metadata、完整下载摘要、Range、目录页和旧版 `10233=404` 均通过；服务器仅保留 `10235` 与 `10234`。

## 1.2.34

- 邮箱注册流程收敛为“邮箱注册 / 手机号注册”两个入口；手机号注册保持禁用，登录统一为“邮箱/用户名 + 密码”，邮箱验证码通过生产 SMTP 发送。
- 使用固定品牌资源替换微信登录图标；用户名不设最大长度，游客登录和“我的成长记录”文案保持一致。
- 待完善会议恢复时回写兼容模板选择，重新进入录音页会保留之前选中的模板；新建会议仍不默认选中模板并保留录音门禁提示。
- 社区发现页整理为图文卡片布局，删除未经请求的示例披露和虚构互动文案，资料参考改为国内来源，并增加互动吧公开活动预告入口。
- 固定签名 APK `1.2.34/10234` 非 debuggable，SHA-256 为 `76da70da2543755a63068249766447d7462a0d6af441e472254ebfbc16803359`。
- 正式 release：`1.2.34-20260820050000`。公网 metadata、完整下载摘要、Range、目录页和旧版 `10232=404` 均通过；服务器仅保留 `10234` 与 `10233`。

## 1.2.33

- 账户与社区数据正式统一落入独立账户 SQLite；旧主库社区帖子、媒体及关联表支持事务迁移、SHA-256 校验、冲突预检和严格幂等，避免新邮箱账户发布社区内容时触发外键失败。
- Android 直连 STT 的本地文件、腾讯云文件识别和流式最终稿统一由 STT Service 调用账户积分账本；按实际音频时长向上取整，每分钟 10 分，成功后结算，重试不重复扣分。
- 统一 `X-Usage-Key` 规范键并隔离账户命名空间；余额耗尽时允许同一成功事件幂等重试，跨账户复用、停用账户和无法确定时长的请求均失败关闭。
- PWA 代理信任上游用量结果，兼容滚动升级期间旧版 `duration_ms` 响应，不重复扣费；账户令牌密钥配置后不再允许匿名穿透。
- Server 全量测试 181 项、PWA 单元测试 9 项、Android Release JVM 测试 219 项通过；固定签名 APK `1.2.33/10233` 非 debuggable，SHA-256 为 `ec79fc5989e4a50efdaef79728740374e63422cf1718a340a735cdeef7b96954`。
- 正式 release：`1.2.33-20260819160801290`。生产健康、模型校验、Backend/STT 状态、OTA metadata、目录页、完整下载、Range 和旧版 404 均通过；服务器只保留 `10233` 与 `10232`。

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
## 1.2.34

- 注册页 Tab 文案统一为“邮箱注册”和“手机号注册”；手机号保持禁用，登录页统一使用“邮箱/用户名 + 密码”，邮箱验证码通过正式服务器 SMTP 环境变量发送。
- 微信登录图标替换为 WeChat 官方品牌路径资源，登录与注册入口共用同一图标。
- 待完善会议恢复逐会议模板；兼容升级前已有录音、音频或转写内容的旧会议，并将恢复结果回写，避免重新进入后丢失模板选择。
- 社区活动预告入口固定展示互动吧公开活动，公开资料链接改为中国国家地理国内站点，移除不需要的示例披露、互动免责声明和研学示例说明卡片。
- “我的”和会议整理相关文案移除“小Woo”品牌称呼，改为中性中文服务名称。
- 固定签名 APK `1.2.34/10234` 已完成 Release 构建，SHA-256：`76da70da2543755a63068249766447d7462a0d6af441e472254ebfbc16803359`。
## 1.2.47

- 活动与福利中心重新整理首屏层级：增加“福利正在进行”聚焦横幅，强化积分总览、兑换中心、邀请好友、福利群和限时活动的操作分组，并加入轻量脉冲提示。
- 消息中心升级为置顶活动、会议动态分组，支持“全部 / 待处理”筛选、未读数量、单条已读和“全部已读”；活动已读状态通过 DataStore 持久化，首页消息徽标同步活动未读。
- 固定签名 Android Release 版本号升级为 `1.2.47/10247`，SHA-256 为 `9f9657a1fc92f1ee9d6da78a7d71b2776e6bd2ebd5ac29dd96140403918ff1e6`；发布后 OTA 仅保留 `10247` 与 `10246`。

## 1.2.48

- Android 与 PWA 新增微信、QQ、飞书、Telegram、WhatsApp 和 Instagram 第三方登录入口；后端提供 OAuth state、可配置 PKCE、一次性 ticket、回调白名单及 Telegram 签名校验。平台凭证、端点和回调均使用动态配置，未配置或未通过平台审核的提供商保持禁用并显示原因。
- 邀请码从消息中心迁移到“我的 > 邀请好友”，分享链接统一使用 `/app/?ref=CODE`；注册过程记录邀请来源、推荐关系和社交身份，奖励积分通过 `ACCOUNT_REFERRAL_REWARD_POINTS` 动态配置。
- 管理后台增加用户邀请关系、第三方身份及认证审计信息，便于核对获客来源与异常登录；消息中心聚焦通知和活动，不再重复承载邀请入口。
- 固定签名 Android Release 版本号升级为 `1.2.48/10248`，SHA-256 为 `a11c9a255873d2e746665022f80f5cddcec6ca6b14d13b09f5ba6007eceb754b`；发布后 OTA 仅保留 `10248` 与 `10247`。

## 1.2.49

- Android 与 PWA 的第三方登录入口默认收起为“其他登录方式”，点击后平滑展开六个平台，减少登录页首屏占用。
- 微信、QQ、飞书、Telegram、WhatsApp 和 Instagram 均替换为本地随包的真实品牌 Logo，不再使用汉字或字母占位；未启用平台仍保留明确状态提示。
- 服务端新增 `auth-providers.defaults.json`，内置六个平台的官方公开授权、令牌和用户信息端点；私有配置与环境变量可以逐项覆盖，Client ID、Secret、Bot Token 不进入源码。
- 固定签名 Android Release 版本号升级为 `1.2.49/10249`，SHA-256 为 `40a3020152c416d0d4a5ce81d1c849a3bae5771360c667eab7f7158ab20f1cea`；发布后 OTA 仅保留 `10249` 与 `10248`。
