package com.oa.automation.infrastructure.account

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AccountSessionCredentials
import kotlinx.coroutines.flow.first

class AccountSessionSynchronizer(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) {
    suspend fun refresh(): Result<AccountSessionCredentials> {
        val session = configDataStore.authSessionFlow.first()
            ?: return Result.failure(IllegalStateException("用户尚未登录"))
        val endpoint = configDataStore.accountEndpointFlow.first()
        val result = accountApiService.refreshSession(endpoint, session.accessToken)
        val credentials = result.getOrNull() ?: return result
        configDataStore.updateAccountSession(credentials)
        return Result.success(credentials)
    }
}
