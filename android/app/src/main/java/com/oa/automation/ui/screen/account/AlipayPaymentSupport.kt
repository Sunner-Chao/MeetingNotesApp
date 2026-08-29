package com.oa.automation.ui.screen.account

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oa.automation.R
import com.oa.automation.domain.model.AccountPlan
import com.oa.automation.domain.model.AlipayAppPayment
import com.oa.automation.domain.model.RechargeOrder
import com.oa.automation.infrastructure.account.AlipaySdkClient
import java.util.Locale

internal val PointsAccent = androidx.compose.ui.graphics.Color(0xFF0078D4)

/**
 * Hands a server-signed order string to the Alipay SDK, then always asks the server
 * for the authoritative result. The synchronous status code is only a hint.
 */
@Composable
internal fun AlipayPaymentLauncher(
    pendingPayment: AlipayAppPayment?,
    paymentAttempt: Int,
    onLaunchPermitted: (String) -> Boolean,
    onHandled: () -> Unit,
    onConfirm: (orderId: String, resultStatus: String) -> Unit
) {
    val context = LocalContext.current

    // The launch claim lives in the shared ViewModel (onLaunchPermitted), not in
    // composition state: both payment screens observe the same pending payment
    // during navigation transitions, and an activity recreation must not
    // relaunch the cashier on top of one that is already open.
    LaunchedEffect(pendingPayment?.orderId, paymentAttempt) {
        val payment = pendingPayment ?: return@LaunchedEffect
        if (!onLaunchPermitted(payment.orderId)) return@LaunchedEffect
        val activity = context as? Activity
        if (activity == null || payment.orderString.isBlank()) {
            onHandled()
            return@LaunchedEffect
        }
        val result = runCatching {
            AlipaySdkClient.pay(activity, payment.orderString, payment.environment)
        }.getOrDefault(emptyMap())
        onHandled()
        onConfirm(payment.orderId, result["resultStatus"].orEmpty())
    }
}

/** Alipay is live; WeChat Pay keeps a visible placeholder until it is signed. */
@Composable
internal fun PaymentMethodRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PaymentMethodChip(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_alipay_official,
            name = "支付宝",
            caption = "支持花呗与余额",
            available = true
        )
        PaymentMethodChip(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_wechat_official,
            name = "微信支付",
            caption = "即将支持",
            available = false
        )
    }
}

@Composable
private fun PaymentMethodChip(
    modifier: Modifier,
    iconRes: Int,
    name: String,
    caption: String,
    available: Boolean
) {
    val contentColor = if (available) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.height(58.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (available) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            width = if (available) 1.5.dp else 1.dp,
            color = if (available) PointsAccent else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                colorFilter = if (available) {
                    null
                } else {
                    ColorFilter.tint(MaterialTheme.colorScheme.outline)
                }
            )
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

internal fun localizedPlanName(plan: AccountPlan): String =
    localizedPlanLabel(plan.code, plan.name)

internal fun localizedOrderPlanName(order: RechargeOrder): String =
    localizedPlanLabel(order.planCode, order.planName)

private fun localizedPlanLabel(code: String, fallback: String): String =
    when (code.lowercase(Locale.ROOT)) {
        "points_starter" -> "轻享积分包"
        "points_professional" -> "专业积分包"
        "points_team" -> "团队积分包"
        "points_paylink_probe" -> "支付联调测试包"
        else -> fallback.ifBlank { "积分套餐" }
    }

internal fun localizedOrderStatus(status: String): String = when (status.lowercase(Locale.ROOT)) {
    "pending" -> "待支付"
    "approved" -> "已入账"
    "rejected" -> "已拒绝"
    else -> "处理中"
}

internal fun formatPlanPrice(priceCents: Int): String {
    val safeCents = priceCents.coerceAtLeast(0)
    val yuan = safeCents / 100
    val cents = safeCents % 100
    return if (cents == 0) {
        "￥${String.format(Locale.CHINA, "%,d", yuan)}"
    } else {
        "￥${String.format(Locale.CHINA, "%,d.%02d", yuan, cents)}"
    }
}

internal fun isPendingOrder(order: RechargeOrder): Boolean =
    order.status.equals("pending", ignoreCase = true)
