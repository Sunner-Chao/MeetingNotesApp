# Android UI 组件 API

## MeetingCard

路径：`ui/component/MeetingCard.kt`

```kotlin
@Composable
fun MeetingCard(
    meeting: Meeting,
    hasReport: Boolean = false,
    isRegenerating: Boolean = false,
    onClick: () -> Unit,
    onReportClick: () -> Unit = {},
    onContinueRecording: () -> Unit = {},
    onRegenerateReport: () -> Unit = {},
    onDelete: () -> Unit = {},
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier
)
```

状态轨道、直接操作、更多菜单和左滑删除都在组件内部；业务删除、导航和后台生成由调用方提供。

## QuotaUsageCard

路径：`ui/component/QuotaUsageCard.kt`

```kotlin
@Composable
fun QuotaUsageCard(
    quota: AgentQuota?,
    isLoading: Boolean,
    tokenConfigured: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier
)
```

该组件同时用于“我的”和“VIP会员”，只展示服务端返回的真实额度。

## AgentQuotaService

路径：`infrastructure/llm/AgentQuotaService.kt`

```kotlin
suspend fun fetch(config: LLMConfig): Result<AgentQuota>
```

服务地址和令牌全部来自运行时 `LLMConfig`，请求地址为 `{agentEndpoint}/quota`。

## 旧动画组件

`GradientButton`、`ShimmerEffect` 和 `AdvancedAnimations` 保留供既有页面兼容，但不再作为会议工作台的默认视觉方案。新业务组件优先使用 Material 3 实色表面和主题令牌。
