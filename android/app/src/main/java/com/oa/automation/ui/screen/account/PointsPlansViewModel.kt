package com.oa.automation.ui.screen.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AccountPlan
import com.oa.automation.domain.model.AccountProfile
import com.oa.automation.domain.model.RechargeOrder
import com.oa.automation.infrastructure.account.AccountApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PointsPlansUiState(
    val profile: AccountProfile? = null,
    val plans: List<AccountPlan> = emptyList(),
    val orders: List<RechargeOrder> = emptyList(),
    val isLoading: Boolean = false,
    val processingPlanCode: String? = null,
    val error: String? = null,
    val message: String? = null
)

/** Loads the account's point plans and submits administrator-reviewed recharge requests. */
class PointsPlansViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(PointsPlansUiState())
    val uiState: StateFlow<PointsPlansUiState> = _uiState.asStateFlow()

    private var currentEndpoint = ""
    private var currentToken = ""

    init {
        viewModelScope.launch {
            combine(
                configDataStore.authSessionFlow,
                configDataStore.accountEndpointFlow
            ) { session, endpoint -> session to endpoint }
                .collectLatest { (session, endpoint) ->
                    currentEndpoint = endpoint
                    currentToken = session?.accessToken.orEmpty()
                    if (session == null) {
                        _uiState.value = PointsPlansUiState()
                    } else {
                        load(session.user)
                    }
                }
        }
    }

    fun refresh() {
        if (currentToken.isBlank()) return
        viewModelScope.launch { load(_uiState.value.profile) }
    }

    fun submit(planCode: String) {
        if (currentToken.isBlank() || _uiState.value.processingPlanCode != null) return
        if (_uiState.value.orders.any { it.status.equals("pending", ignoreCase = true) }) {
            _uiState.update { it.copy(error = "已有待确认的积分申请，请等待处理") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(processingPlanCode = planCode, error = null, message = null) }
            accountApiService.createOrder(currentEndpoint, currentToken, planCode).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            processingPlanCode = null,
                            message = "积分申请已提交，请等待管理员确认",
                            error = null
                        )
                    }
                    load(_uiState.value.profile)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            processingPlanCode = null,
                            error = error.message ?: "积分申请提交失败"
                        )
                    }
                }
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun load(previousProfile: AccountProfile?) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        val profileResult = accountApiService.profile(currentEndpoint, currentToken)
        val plansResult = accountApiService.plans(currentEndpoint, currentToken)
        val ordersResult = accountApiService.orders(currentEndpoint, currentToken)
        _uiState.update {
            it.copy(
                profile = profileResult.getOrNull() ?: previousProfile,
                plans = plansResult.getOrDefault(it.plans),
                orders = ordersResult.getOrDefault(it.orders),
                isLoading = false,
                error = profileResult.exceptionOrNull()?.message
                    ?: plansResult.exceptionOrNull()?.message
                    ?: ordersResult.exceptionOrNull()?.message
                    ?: it.error
            )
        }
    }
}
