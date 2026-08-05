package com.oa.automation.domain.model

enum class TencentAsrQuotaWarningLevel {
    NORMAL,
    LOW,
    CRITICAL,
    EXHAUSTED,
    UNKNOWN;

    companion object {
        fun fromWireValue(value: String): TencentAsrQuotaWarningLevel = when (value.lowercase()) {
            "normal" -> NORMAL
            "low" -> LOW
            "critical" -> CRITICAL
            "exhausted" -> EXHAUSTED
            else -> UNKNOWN
        }
    }
}

data class TencentAsrUsageService(
    val id: String,
    val displayName: String,
    val usedSeconds: Long,
    val freeSeconds: Long,
    val remainingSeconds: Long,
    val usageRatio: Float,
    val requestCount: Long,
    val pendingLocalSeconds: Long = 0,
    val pendingLocalRequestCount: Long = 0
)

data class TencentAsrUsage(
    val month: String,
    val timezone: String,
    val nextResetAt: String,
    val hybridRemainingSeconds: Long,
    val warningLevel: TencentAsrQuotaWarningLevel,
    val services: List<TencentAsrUsageService>,
    val source: String = "",
    val updatedAt: String = "",
    val isEstimated: Boolean = false
) {
    fun warningMessage(): String? = when (warningLevel) {
        TencentAsrQuotaWarningLevel.NORMAL,
        TencentAsrQuotaWarningLevel.UNKNOWN -> null
        TencentAsrQuotaWarningLevel.LOW ->
            "智悟增强云模型可用额度不足 1 小时，实时识别和终稿识别都会消耗云端资源。"
        TencentAsrQuotaWarningLevel.CRITICAL ->
            "智悟增强云模型可用额度不足 15 分钟，建议切换智悟本地模型或补充云端资源。"
        TencentAsrQuotaWarningLevel.EXHAUSTED ->
            "智悟增强云模型当前额度已用完，继续使用可能产生费用；余额不足时识别会失败。"
    }
}

data class TencentAsrBudgetService(
    val id: String,
    val displayName: String,
    val usedSeconds: Long,
    val reservedSeconds: Long,
    val limitSeconds: Long,
    val remainingSeconds: Long
)

data class TencentAsrTierPolicy(
    val id: String,
    val displayName: String,
    val isPaid: Boolean,
    val flashEnabled: Boolean,
    val realtimeEnabled: Boolean,
    val monthlyLimitSeconds: Long,
    val budgetEnforced: Boolean,
    val services: List<TencentAsrBudgetService>
) {
    val isAvailable: Boolean
        get() = flashEnabled && realtimeEnabled
}

data class TencentAsrBudgetPolicy(
    val month: String,
    val source: String,
    val tiers: List<TencentAsrTierPolicy>
) {
    fun tierFor(value: TencentAsrTier): TencentAsrTierPolicy? =
        tiers.firstOrNull { it.id == value.name.lowercase().substringBefore('_') }
}

fun formatTencentAsrDuration(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    if (safeSeconds == 0L) return "0 分钟"
    if (safeSeconds < 60L) return "不足 1 分钟"
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours} 小时 ${minutes} 分钟"
        hours > 0 -> "${hours} 小时"
        else -> "${minutes} 分钟"
    }
}
