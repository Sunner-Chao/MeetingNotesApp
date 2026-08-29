package com.oa.automation.ui.screen.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AccountPlan
import com.oa.automation.domain.model.AccountProfile
import com.oa.automation.domain.model.RechargeOrder
import com.oa.automation.domain.model.AlipayAppPayment
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
    val pendingPayment: AlipayAppPayment? = null,
    /** Bumped on every submit so retries of the same order re-trigger the launcher. */
    val paymentAttempt: Int = 0,
    val error: String? = null,
    val message: String? = null
)

/** Loads point plans and coordinates server-created Alipay APP payments. */
class PointsPlansViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(PointsPlansUiState())
    val uiState: StateFlow<PointsPlansUiState> = _uiState.asStateFlow()

    private var currentEndpoint = ""
    private var currentToken = ""

    // One ViewModel serves both payment screens, so during a navigation
    // transition two launchers may observe the same pending payment. This set
    // arbitrates so the Alipay cashier opens exactly once per attempt.
    private val launchedPaymentOrderIds = mutableSetOf<String>()

    /** Returns true only for the first launcher that claims this order's payment. */
    fun tryMarkPaymentLaunched(orderId: String): Boolean =
        synchronized(launchedPaymentOrderIds) { launchedPaymentOrderIds.add(orderId) }

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
        viewModelScope.launch {
            _uiState.update { it.copy(processingPlanCode = planCode, error = null, message = null) }
            val existingOrder = _uiState.value.orders.firstOrNull {
                it.planCode == planCode && it.status.equals("pending", ignoreCase = true)
            }
            val orderResult = existingOrder?.let { Result.success(it) }
                ?: accountApiService.createOrder(currentEndpoint, currentToken, planCode)
            orderResult.fold(
                onSuccess = { order ->
                    accountApiService.createAlipayPayment(currentEndpoint, currentToken, order.id).fold(
                        onSuccess = { payment ->
                            // A fresh attempt may reuse the same order id (retry after a
                            // lost cashier result); release the launch claim first.
                            synchronized(launchedPaymentOrderIds) {
                                launchedPaymentOrderIds.remove(payment.orderId)
                            }
                            _uiState.update {
                                it.copy(
                                    processingPlanCode = null,
                                    pendingPayment = payment,
                                    paymentAttempt = it.paymentAttempt + 1,
                                    error = null
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(processingPlanCode = null, error = error.message ?: "支付宝订单创建失败")
                            }
                        }
                    )
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

    fun paymentHandled() {
        _uiState.value.pendingPayment?.let { payment ->
            synchronized(launchedPaymentOrderIds) {
                launchedPaymentOrderIds.remove(payment.orderId)
            }
        }
        _uiState.update { it.copy(pendingPayment = null) }
    }

    fun confirmPayment(orderId: String, resultStatus: String) {
        if (currentToken.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            accountApiService.queryAlipayPayment(currentEndpoint, currentToken, orderId).fold(
                onSuccess = { query ->
                    val paid = query.payment.status.equals("paid", ignoreCase = true)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = when {
                                paid -> "支付成功，积分已到账"
                                resultStatus == "6001" -> "已取消支付，订单仍可继续支付"
                                resultStatus == "8000" -> "支付处理中，请稍后刷新确认"
                                else -> "支付结果待确认，请稍后刷新"
                            },
                            error = null
                        )
                    }
                    load(_uiState.value.profile)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "支付状态查询失败")
                    }
                    load(_uiState.value.profile)
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
