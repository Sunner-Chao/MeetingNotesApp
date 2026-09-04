package com.oa.automation.infrastructure.account

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AccountSessionCredentials
import kotlinx.coroutines.flow.first

class AccountSessionSynchronizer(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) {
    private val refreshGate = AccountSessionRefreshGate()
    suspend fun refresh(): Result<AccountSessionCredentials> = refreshGate.withLock {
        val session = configDataStore.authSessionFlow.first()
            ?: return@withLock Result.failure(IllegalStateException("用户尚未登录"))
        val endpoint = configDataStore.accountEndpointFlow.first()
        val result = accountApiService.refreshSession(endpoint, session.accessToken)
        val credentials = result.getOrNull() ?: return@withLock result
        configDataStore.updateAccountSession(credentials)
        Result.success(credentials)
    }

    /** Refresh only when the account session or its derived service tokens are near expiry. */
    suspend fun refreshIfNeeded(leewaySeconds: Long = 120L): Result<Boolean> {
        val session = configDataStore.authSessionFlow.first()
            ?: return Result.failure(IllegalStateException("用户尚未登录"))
        val now = System.currentTimeMillis() / 1_000L
        val sttExpiresAt = sttTokenExpiresAt(session.sttAccessToken)
        val needsRefresh = session.expiresAt <= now + leewaySeconds ||
            session.sttAccessToken.isNullOrBlank() ||
            sttExpiresAt == null ||
            sttExpiresAt <= now + leewaySeconds
        if (!needsRefresh) return Result.success(false)
        return refresh().map { true }
    }
}
