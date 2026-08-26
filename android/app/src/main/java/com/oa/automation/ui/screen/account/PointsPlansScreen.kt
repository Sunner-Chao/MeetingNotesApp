package com.oa.automation.ui.screen.account

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.AccountPlan
import com.oa.automation.domain.model.RechargeOrder
import com.oa.automation.ui.formatBeijingTime
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

private val PointsAccent = Color(0xFF0078D4)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsPlansScreen(
    onNavigateBack: () -> Unit,
    viewModel: PointsPlansViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("积分套餐", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !uiState.isLoading) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新积分套餐")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading && uiState.plans.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = PointsAccent)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PointsBalanceCard(uiState)
                ApplicationNotice()
                uiState.error?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Text(
                    text = "选择积分套餐",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (uiState.plans.isEmpty()) {
                    Text(
                        text = "暂时没有可用的积分套餐",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    uiState.plans.forEach { plan ->
                        PointsPlanCard(
                            plan = plan,
                            enabled = uiState.processingPlanCode == null &&
                                uiState.orders.none { it.status.equals("pending", ignoreCase = true) },
                            isProcessing = uiState.processingPlanCode == plan.code,
                            onSubmit = { viewModel.submit(plan.code) }
                        )
                    }
                }
                OrderHistory(uiState.orders)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun PointsBalanceCard(uiState: PointsPlansUiState) {
    val points = uiState.profile?.usage?.pointsRemaining ?: 0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = PointsAccent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(15.dp),
                color = Color.White.copy(alpha = 0.16f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Payments, contentDescription = null, tint = Color.White)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("当前可用积分", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${points.coerceAtLeast(0)} 分",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 36.sp
                )
                Text(
                    text = "转写每分钟 10 分 · 智能整理每次 30 分 · 智能问答每次 10 分",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ApplicationNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "提交申请后由管理员确认入账。当前仅支持积分申请，暂不在线支付。",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PointsPlanCard(
    plan: AccountPlan,
    enabled: Boolean,
    isProcessing: Boolean,
    onSubmit: () -> Unit
) {
    val points = plan.points.takeIf { it > 0 } ?: plan.quotaAmount
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = localizedPlanName(plan),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatPlanPrice(plan.priceCents),
                    color = PointsAccent,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "${points.coerceAtLeast(0)} 分",
                color = PointsAccent,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = plan.description.ifBlank { "适合日常记录与智能整理" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("有效期 ${plan.durationDays.coerceAtLeast(1)} 天", style = MaterialTheme.typography.labelMedium)
                if (plan.teamSeats > 1) {
                    Text("${plan.teamSeats} 个协作席位", style = MaterialTheme.typography.labelMedium)
                }
            }
            Button(
                onClick = onSubmit,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (enabled) "申请积分" else "已有待处理申请")
                }
            }
        }
    }
}

@Composable
private fun OrderHistory(orders: List<RechargeOrder>) {
    if (orders.isEmpty()) return
    Text(
        text = "申请记录",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            orders.take(5).forEachIndexed { index, order ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${localizedOrderPlanName(order)} · ${order.points.coerceAtLeast(0)} 分",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatBeijingTime(order.createdAt * 1000, "yyyy-MM-dd HH:mm"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = localizedOrderStatus(order.status),
                        style = MaterialTheme.typography.labelMedium,
                        color = when (order.status.lowercase(Locale.ROOT)) {
                            "approved" -> Color(0xFF16794A)
                            "rejected" -> MaterialTheme.colorScheme.error
                            else -> PointsAccent
                        }
                    )
                }
            }
        }
    }
}

private fun localizedPlanName(plan: AccountPlan): String = when (plan.code.lowercase(Locale.ROOT)) {
    "points_starter" -> "轻享积分包"
    "points_professional" -> "专业积分包"
    "points_team" -> "团队积分包"
    else -> plan.name.ifBlank { "积分套餐" }
}

private fun localizedOrderPlanName(order: RechargeOrder): String = when (order.planCode.lowercase(Locale.ROOT)) {
    "points_starter" -> "轻享积分包"
    "points_professional" -> "专业积分包"
    "points_team" -> "团队积分包"
    else -> order.planName.ifBlank { "积分套餐" }
}

private fun localizedOrderStatus(status: String): String = when (status.lowercase(Locale.ROOT)) {
    "pending" -> "待确认"
    "approved" -> "已入账"
    "rejected" -> "已拒绝"
    else -> "处理中"
}

private fun formatPlanPrice(priceCents: Int): String {
    val safeCents = priceCents.coerceAtLeast(0)
    val yuan = safeCents / 100
    val cents = safeCents % 100
    return if (cents == 0) {
        "￥${String.format(Locale.CHINA, "%,d", yuan)}"
    } else {
        "￥${String.format(Locale.CHINA, "%,d.%02d", yuan, cents)}"
    }
}
