package com.oa.automation.ui.screen.vip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.AccountPlan
import com.oa.automation.domain.model.AccountProfile
import com.oa.automation.domain.model.RechargeOrder
import org.koin.androidx.compose.koinViewModel

internal enum class VipContentMode {
    NON_VIP,
    VIP,
    ADMIN
}

internal fun resolveVipContentMode(profile: AccountProfile?): VipContentMode = when {
    profile?.isAdmin == true -> VipContentMode.ADMIN
    profile?.constructionLogsUnlocked == true -> VipContentMode.VIP
    else -> VipContentMode.NON_VIP
}

/**
 * VipScreen - VIP专区
 * 根据会员权限展示月卡入口或专业模板工作区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VipScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRecording: (String) -> Unit,
    viewModel: VipViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.pendingMeetingId) {
        uiState.pendingMeetingId?.let { meetingId ->
            viewModel.clearPendingNavigation()
            onNavigateToRecording(meetingId)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VIP会员与专业能力", fontWeight = FontWeight.SemiBold)
                        Text(
                            "智能体 · 小Woo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回我的"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val profile = uiState.profile
            val contentMode = resolveVipContentMode(profile)
            val hasVipAccess = contentMode != VipContentMode.NON_VIP

            uiState.accountError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (hasVipAccess) {
                MembershipBanner(
                    profile = profile,
                    isLoading = uiState.isAccountLoading,
                    onRefresh = viewModel::refreshMembership
                )
                uiState.quotaError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (contentMode == VipContentMode.ADMIN) {
                    AdminRechargeSection(
                        orders = uiState.pendingAdminOrders,
                        processingOrderId = uiState.processingOrderId,
                        onApprove = viewModel::approveRecharge,
                        onReject = viewModel::rejectRecharge
                    )
                }
                ProfessionalTemplatesSection(
                    selectedType = uiState.activeTemplateType,
                    onSelect = viewModel::selectTemplate,
                    isStarting = uiState.isStarting,
                    quotaRemaining = profile?.usage?.aiCreditsRemaining
                        ?: uiState.quota?.requestsRemaining,
                    onStart = viewModel::startRecording,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                MembershipPromotion(
                    profile = profile,
                    isLoading = uiState.isAccountLoading,
                    onRefresh = viewModel::refreshMembership
                )
                RechargePlansSection(
                    plans = uiState.plans,
                    orders = uiState.orders,
                    processingOrderId = uiState.processingOrderId,
                    onSubmit = viewModel::submitRecharge
                )
            }

            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

@Composable
private fun MembershipPromotion(
    profile: AccountProfile?,
    isLoading: Boolean,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.WorkspacePremium,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "${profile?.planName ?: "Free"} 套餐",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "剩余 ${profile?.usage?.sttMinutesRemaining ?: 120.0} 分钟转写 · ${profile?.usage?.aiCreditsRemaining ?: 5} AI Credits",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新会员状态")
                }
            }
        }
    }
}

@Composable
private fun MembershipBanner(
    profile: AccountProfile?,
    isLoading: Boolean,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (profile?.vipEnabled == true || profile?.isAdmin == true) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (profile?.isAdmin == true) {
                    Icons.Default.AdminPanelSettings
                } else {
                    Icons.Default.WorkspacePremium
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        profile?.isAdmin == true -> "管理员 · 默认 VIP"
                        profile?.vipEnabled == true -> "VIP 权益已生效"
                        else -> "普通用户"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (profile?.constructionLogsUnlocked == true || profile?.isAdmin == true) {
                        "专业工程与监理模板已解锁"
                    } else {
                        "专业工程与监理模板未解锁"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新会员状态")
                }
            }
        }
    }
}

@Composable
private fun RechargePlansSection(
    plans: List<AccountPlan>,
    orders: List<RechargeOrder>,
    processingOrderId: String?,
    onSubmit: (String) -> Unit
) {
    val pendingOrder = orders.firstOrNull { it.status == "pending" }
    Text(
        text = "月卡套餐",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    pendingOrder?.let { order ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Text(
                text = "${order.planName}：待管理员确认",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
    plans.chunked(2).forEach { rowPlans ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rowPlans.forEach { plan ->
                CompactPlanCard(
                    plan = plan,
                    isProcessing = processingOrderId == plan.code,
                    enabled = pendingOrder == null && processingOrderId == null,
                    onSubmit = { onSubmit(plan.code) },
                    modifier = Modifier.weight(1f)
                )
            }
            if (rowPlans.size == 1) Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CompactPlanCard(
    plan: AccountPlan,
    isProcessing: Boolean,
    enabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = plan.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${plan.includedMinutes} 分钟",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "¥%.0f/${plan.durationDays}天".format(plan.priceCents / 100.0),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Text(
                text = buildString {
                    append("${plan.aiCredits} AI Credits")
                    if (plan.teamSeats > 1) append(" · ${plan.teamSeats} 席")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onSubmit,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("申请")
                }
            }
        }
    }
}

@Composable
private fun AdminRechargeSection(
    orders: List<RechargeOrder>,
    processingOrderId: String?,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    Text("充值审批", style = MaterialTheme.typography.titleMedium)
    if (orders.isEmpty()) {
        Text(
            text = "暂无待审批订单",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    orders.forEach { order ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(order.username, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${order.planName} · ${order.includedMinutes} 分钟 + ${order.aiCredits} Credits · ¥%.2f".format(order.amountCents / 100.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onReject(order.id) },
                        enabled = processingOrderId == null,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("拒绝")
                    }
                    Button(
                        onClick = { onApprove(order.id) },
                        enabled = processingOrderId == null,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (processingOrderId == order.id) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("批准入账")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfessionalTemplatesSection(
    selectedType: VipTemplateType,
    onSelect: (VipTemplateType) -> Unit,
    isStarting: Boolean,
    quotaRemaining: Int?,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WorkspacePremium,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "专业模板",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TemplateOption(
                    icon = Icons.Default.Construction,
                    title = "工程/建筑 施工/设计日志",
                    isSelected = selectedType == VipTemplateType.CONSTRUCTION_DESIGN,
                    onClick = { onSelect(VipTemplateType.CONSTRUCTION_DESIGN) },
                    modifier = Modifier.weight(1f)
                )
                TemplateOption(
                    icon = Icons.AutoMirrored.Filled.FactCheck,
                    title = "监理会例会日志",
                    isSelected = selectedType == VipTemplateType.SUPERVISION_MEETING,
                    onClick = { onSelect(VipTemplateType.SUPERVISION_MEETING) },
                    modifier = Modifier.weight(1f)
                )
            }
            ActionButtonsRow(
                isStarting = isStarting,
                quotaRemaining = quotaRemaining,
                onStart = onStart,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TemplateOption(
    icon: ImageVector,
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(72.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(17.dp)
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionButtonsRow(
    isStarting: Boolean,
    quotaRemaining: Int?,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onStart,
        enabled = !isStarting && (quotaRemaining ?: 0) > 0,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        if (isStarting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            when {
                (quotaRemaining ?: 0) > 0 -> "使用此模板开始记录"
                quotaRemaining == 0 -> "AI 处理额度已用尽"
                else -> "额度状态暂不可用"
            }
        )
    }
}
