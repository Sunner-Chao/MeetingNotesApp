# 智悟本 Android UI、账户与 VIP 重构

## 1. 首页

首页是单屏会议工作台：日期、新会议、三项统计、搜索、状态筛选和自适应会议列表均在同一页面。

```text
TopAppBar：智悟本 / 会议工作台
工作区：日期 + 新会议
数据条：全部 / 待完善 / 纪要完成
搜索 + 分段筛选
LazyVerticalGrid：会议档案
NavigationBar：会议 / VIP / 我的
```

- 新建会议只保留工作区右上入口。
- 底部不再显示重复的新建按钮。
- 底栏三项各占三分之一宽度并居中对齐。
- “模板”更名为 `VIP`；设置统一从“我的”进入。

底栏由 `MainWorkspaceScreen` 持有，三个选项是同一 `Home` 导航节点内的 Compose Tab：

- 点击只更新 `selectedTab`，不调用 `NavController.navigate()`。
- 内容使用 140ms 淡入/100ms 淡出，不产生页面压栈或横向页面跳转。
- `SaveableStateHolder` 分别保存会议、VIP、我的本地界面状态。
- 在 VIP/我的按系统返回键时先切回会议，不退出主工作台。
- `Vip` 与 `Account` 独立导航路由已删除，避免后续误用。

## 2. Material 3 主题

| 语义 | 浅色 | 深色 |
| --- | --- | --- |
| Primary | `#0B6B5F` | `#8BD4C7` |
| Secondary | `#B84F3A` | `#FFB4A4` |
| Tertiary | `#3D5F9E` | `#B5C7F9` |
| Background | `#F5F6F2` | `#101412` |
| Surface | `#FCFDF9` | `#171B19` |

默认关闭壁纸动态取色。形状采用 `3/6/8/12/16dp`，常规卡片 8dp 圆角、1dp 边界和 0dp 默认海拔；所有排版字距为 `0.sp`。

## 3. 会议组件

`MeetingCard` 使用左侧状态轨道，展示真实标题、时间、时长和纪要状态。

- 待完善：继续记录、改名、删除。
- 已完成：查看纪要、继续录音、重新生成、改名、删除。
- 保留左滑删除和二次确认。

录音页、报告页、设置页和 VIP 页统一为实色表面、细边界和低圆角，不再以渐变大卡片作为主视觉。

## 4. 真实用户体系

Android 登录和注册已接入服务端：

```http
POST /api/auth/register
POST /api/auth/login
POST /api/account/logout
GET  /api/account/me
```

服务端 SQLite 保存用户，密码使用随机盐 `scrypt` 哈希，登录签发 30 天 Bearer 会话。Android DataStore 保存会话和服务端签发的用户 Agent 令牌；旧版本仅保存用户名的本地假登录不再视为有效会话。

`AccountScreen` 展示：

- 用户名和真实角色。
- 管理员 / VIP / 普通用户状态。
- AI 总额度、已用、剩余和 Agent 权限。
- VIP、服务设置和退出登录。

## 5. VIP 与充值

VIP 页面按角色展示：

- 管理员：默认 VIP、默认施工日志权益、充足管理额度，并显示待审批充值订单。
- 普通用户：显示套餐、自己的订单状态和充值申请按钮。
- 已获批用户：显示专业施工日志与纪要模板。

套餐来自 `server/config/account-plans.json`，价格和额度不硬编码在 Kotlin/Python 业务逻辑中。

```http
GET  /api/account/plans
GET  /api/account/orders
POST /api/account/orders
GET  /api/admin/accounts/orders
POST /api/admin/accounts/orders/{id}/approve
POST /api/admin/accounts/orders/{id}/reject
```

批准订单时，服务端在同一 SQLite 事务中：

1. 把订单从 `pending` 改为 `approved`。
2. 增加用户 Agent `request_limit`。
3. 开启 VIP。
4. 解锁施工日志/专业纪要模板。

当前未接微信/支付宝支付回调，因此按钮明确为“提交充值申请”，只有管理员批准后才入账。服务端 Agent 仍通过 429 强制额度边界。

## 6. 架构

```text
Login/Register Screen
    -> Login/Register ViewModel
    -> AccountApiService
    -> FastAPI Account API
    -> AccountService
    -> SQLite users/sessions/entitlements/plans/orders

AuthSession.agentAccessToken
    -> ConfigDataStore
    -> LLMConfig
    -> AgentGatewayEngine
    -> /api/agent + agent_tokens quota

VipScreen
    -> VipViewModel
    -> AccountApiService + AgentQuotaService
```

管理员密码和账户签名密钥只存在于服务器 `/etc/meetingnotes-stt/stt.env`，不进入源码、APK、发布文档或 Obsidian。

## 7. 验证

- Server：41/41 测试通过。
- Android：13/13 单元测试通过。
- Android Lint：通过。
- Android Debug APK：构建通过。
- Ubuntu 原生部署：`1.2.0-20260720055304351`，健康检查通过。
- 生产管理员登录、默认 VIP、施工日志权限、10,000,000 剩余额度和两项套餐加载均通过。
- 最后一个用户会话注销后 Agent 令牌同步过期；管理员停用用户会撤销全部会话和 Agent 权限。
- Claude CLI 未返回最终正文时不会再把内部 JSONL/思考事件显示或导出为会议纪要；带图片的请求可进入已授权的 Codex 降级路径。
- Android 默认 Agent 已迁移为 Codex，Server 默认推理强度为可动态覆盖的 `high`。
- 无中断录音在停止后复用 Server 已接收的 PCM 生成最终稿，不再重复上传完整 WAV；连接异常自动回退原文件上传。
- 最新 APK 构建后真机 ADB 已断开，尚未覆盖安装；此前 UI 版本的首页和录音页真机复核无重叠。

APK：`android/app/build/outputs/apk/debug/app-debug.apk`
