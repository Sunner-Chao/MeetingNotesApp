package com.oa.automation.infrastructure.account

import kotlinx.coroutines.sync.Mutex
import java.util.Base64

private const val ACCOUNT_STT_TOKEN_PREFIX = "mn_stt_user_v1."

/** Serializes session renewal so parallel background work shares one result. */
internal class AccountSessionRefreshGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

internal fun Throwable.isAuthenticationFailure(): Boolean {
    val visited = HashSet<Throwable>()
    val pending = ArrayDeque<Throwable>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue
        val message = current.message.orEmpty().lowercase()
        if (message.contains("401") ||
            message.contains("令牌无效") ||
            message.contains("令牌已过期") ||
            message.contains("鉴权状态异常") ||
            message.contains("访问令牌无效") ||
            message.contains("访问令牌未配置")
        ) return true
        current.cause?.let(pending::addLast)
        current.suppressed.forEach(pending::addLast)
    }
    return false
}

/**
 * Reads the expiry claim from the stateless account STT token.
 *
 * The signature is still verified by the STT service. The client only uses the
 * non-sensitive expiry claim to avoid treating an expired, non-empty token as
 * usable until the first request returns 401.
 */
internal fun sttTokenExpiresAt(token: String?): Long? {
    val cleanToken = token?.trim().orEmpty()
    if (!cleanToken.startsWith(ACCOUNT_STT_TOKEN_PREFIX)) return null
    val payload = cleanToken.removePrefix(ACCOUNT_STT_TOKEN_PREFIX)
        .substringBefore('.', missingDelimiterValue = "")
    if (payload.isBlank()) return null
    return runCatching {
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        Regex("\\\"exp\\\"\\s*:\\s*(\\d+)")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLong()
    }.getOrNull()
}
