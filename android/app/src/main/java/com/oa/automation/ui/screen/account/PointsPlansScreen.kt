package com.oa.automation.ui.screen.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsPlansScreen(
    onNavigateBack: () -> Unit,
    onNavigateToOrders: () -> Unit,
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

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // The ViewModel is shared with the orders screen; refresh on entry so the
    // balance and pending states reflect payments made on the other screen.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    AlipayPaymentLauncher(
        pendingPayment = uiState.pendingPayment,
        paymentAttempt = uiState.paymentAttempt,
        onLaunchPermitted = viewModel::tryMarkPaymentLaunched,
        onHandled = viewModel::paymentHandled,
        onConfirm = viewModel::confirmPayment
    )

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
                    IconButton(onClick = onNavigateToOrders) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = "充值订单")
                    }
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
        // Compose forbids early returns inside content lambdas: they unbalance the
        // composition groups and crash on the next measure pass.
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
            // Everything shares the viewport: the plan list flexes so the page never scrolls.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                PointsBalanceStrip(uiState)
                PaymentMethodRow()
                if (uiState.plans.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "暂时没有可用的积分套餐",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    uiState.plans.forEach { plan ->
                        val pendingForPlan = uiState.orders.any {
                            isPendingOrder(it) && it.planCode == plan.code
                        }
                        val blockedByOtherPlan = uiState.orders.any { order ->
                            isPendingOrder(order) && order.planCode != plan.code
                        }
                        PointsPlanRow(
                            plan = plan,
                            modifier = Modifier.weight(1f),
                            enabled = uiState.processingPlanCode == null && !blockedByOtherPlan,
                            isPending = pendingForPlan,
                            isProcessing = uiState.processingPlanCode == plan.code,
                            onSubmit = { viewModel.submit(plan.code) }
                        )
                    }
                    Text(
                        text = "到账以服务端确认结果为准",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun PointsBalanceStrip(uiState: PointsPlansUiState) {
    val points = uiState.profile?.usage?.pointsRemaining ?: 0
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp),
        shape = RoundedCornerShape(14.dp),
        color = PointsAccent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(11.dp),
                color = Color.White.copy(alpha = 0.16f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Payments,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "当前可用积分",
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "${points.coerceAtLeast(0)} 分",
                    color = Color.White,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 27.sp
                )
            }
        }
    }
}

@Composable
private fun PointsPlanRow(
    plan: AccountPlan,
    modifier: Modifier,
    enabled: Boolean,
    isPending: Boolean,
    isProcessing: Boolean,
    onSubmit: () -> Unit
) {
    val points = plan.points.takeIf { it > 0 } ?: plan.quotaAmount
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = localizedPlanName(plan),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${points.coerceAtLeast(0)} 分",
                    color = PointsAccent,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 25.sp
                )
                Text(
                    text = buildString {
                        append("有效期 ${plan.durationDays.coerceAtLeast(1)} 天")
                        if (plan.teamSeats > 1) append(" · ${plan.teamSeats} 个协作席位")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = formatPlanPrice(plan.priceCents),
                    color = PointsAccent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Button(
                    onClick = onSubmit,
                    enabled = enabled,
                    modifier = Modifier.widthIn(min = 104.dp),
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp,
                        vertical = 8.dp
                    )
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            text = when {
                                isPending -> "继续支付"
                                enabled -> "立即支付"
                                else -> "处理中"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
