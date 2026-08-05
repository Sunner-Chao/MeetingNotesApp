package com.oa.automation.ui.screen.vip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.application.usecase.StartRecordingUseCase
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.AccountPlan
import com.oa.automation.domain.model.AccountProfile
import com.oa.automation.domain.model.AuthSession
import com.oa.automation.domain.model.RechargeOrder
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.infrastructure.account.AccountSessionSynchronizer
import com.oa.automation.infrastructure.llm.AgentQuota
import com.oa.automation.infrastructure.llm.AgentQuotaService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VipUiState(
    val templates: List<PresetReportTemplate> = emptyList(),
    val activeTemplateName: String = "",
    val activeTemplateType: VipTemplateType = VipTemplateType.CONSTRUCTION_DESIGN,
    val isApplying: Boolean = false,
    val isStarting: Boolean = false,
    val quota: AgentQuota? = null,
    val isQuotaLoading: Boolean = false,
    val quotaError: String? = null,
    val tokenConfigured: Boolean = false,
    val profile: AccountProfile? = null,
    val plans: List<AccountPlan> = emptyList(),
    val orders: List<RechargeOrder> = emptyList(),
    val pendingAdminOrders: List<RechargeOrder> = emptyList(),
    val isAccountLoading: Boolean = false,
    val accountError: String? = null,
    val processingOrderId: String? = null,
    val pendingMeetingId: String? = null,
    val message: String? = null
)

enum class VipTemplateType(val displayName: String, val templateName: String) {
    CONSTRUCTION_DESIGN("工程/建筑 施工/设计日志", "工程/建筑 施工/设计日志"),
    SUPERVISION_MEETING("监理会例会日志", "监理会例会日志");

    companion object {
        fun fromTemplateName(name: String): VipTemplateType? = when (name) {
            "工程行业施工日志",
            "建筑专业设计日志" -> CONSTRUCTION_DESIGN
            else -> values().firstOrNull { it.templateName == name }
        }
    }
}

class VipViewModel(
    private val configDataStore: ConfigDataStore,
    private val startRecordingUseCase: StartRecordingUseCase,
    private val quotaService: AgentQuotaService,
    private val accountApiService: AccountApiService,
    private val accountSessionSynchronizer: AccountSessionSynchronizer
) : ViewModel() {

    private val _uiState = MutableStateFlow(VipUiState())
    val uiState: StateFlow<VipUiState> = _uiState.asStateFlow()
    private var currentLlmConfig: LLMConfig? = null
    private var currentSession: AuthSession? = null
    private var currentAccountEndpoint: String = ""

    init {
        viewModelScope.launch {
            // 加载VIP专用模板
            val templates = configDataStore.loadVipTemplates()
            combine(
                configDataStore.appConfigFlow,
                configDataStore.authSessionFlow,
                configDataStore.accountEndpointFlow
            ) { appConfig, session, endpoint -> Triple(appConfig, session, endpoint) }
                .collect { (appConfig, session, endpoint) ->
                currentSession = session
                currentAccountEndpoint = endpoint
                currentLlmConfig = appConfig.llmConfig
                val activeName = appConfig.reportTemplateConfig.selectedName
                val activeType = VipTemplateType.fromTemplateName(activeName)
                    ?: _uiState.value.activeTemplateType
                _uiState.update {
                    it.copy(
                        templates = templates,
                        activeTemplateName = activeName,
                        activeTemplateType = activeType,
                        tokenConfigured = !appConfig.llmConfig.agentAccessToken.isNullOrBlank()
                    )
                }
                if (session != null) loadMembership(endpoint, session)
                if (!appConfig.llmConfig.agentAccessToken.isNullOrBlank()) {
                    loadQuota(appConfig.llmConfig)
                } else {
                    _uiState.update {
                        it.copy(quota = null, quotaError = null, isQuotaLoading = false)
                    }
                }
            }
        }
    }

    fun refreshQuota() {
        currentLlmConfig?.let { config ->
            if (!config.agentAccessToken.isNullOrBlank()) {
                viewModelScope.launch { loadQuota(config) }
            }
        }
    }

    fun refreshMembership() {
        val session = currentSession ?: return
        viewModelScope.launch {
            accountSessionSynchronizer.refresh().onSuccess { credentials ->
                _uiState.update { it.copy(profile = credentials.user) }
            }
            loadMembership(currentAccountEndpoint, session)
            currentLlmConfig?.let { config ->
                if (!config.agentAccessToken.isNullOrBlank()) loadQuota(config)
            }
        }
    }

    fun submitRecharge(planCode: String) {
        val session = currentSession ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(processingOrderId = planCode, accountError = null) }
            accountApiService.createOrder(
                currentAccountEndpoint,
                session.accessToken,
                planCode
            ).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            processingOrderId = null,
                            message = "充值申请已提交，等待管理员确认"
                        )
                    }
                    loadMembership(currentAccountEndpoint, session)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            processingOrderId = null,
                            accountError = error.message ?: "充值申请失败"
                        )
                    }
                }
            )
        }
    }

    fun approveRecharge(orderId: String) = processAdminOrder(orderId, approve = true)

    fun rejectRecharge(orderId: String) = processAdminOrder(orderId, approve = false)

    fun selectTemplate(type: VipTemplateType) {
        _uiState.update { it.copy(activeTemplateType = type) }
    }

    /**
     * 切换模板启用/禁用状态
     */
    fun toggleTemplate() {
        viewModelScope.launch {
            val currentState = _uiState.value
            val isSelectedActive = currentState.activeTemplateName == currentState.activeTemplateType.templateName

            if (isSelectedActive) {
                // 禁用：切换回默认团队版模板
                val defaultTemplate = configDataStore.loadPresetTemplates().firstOrNull()
                if (defaultTemplate != null) {
                    _uiState.update { it.copy(isApplying = true, message = null) }
                    configDataStore.updateReportTemplate(
                        ReportTemplateConfig(
                            selectedName = defaultTemplate.name,
                            content = defaultTemplate.content,
                            isCustom = false
                        )
                    )
                    _uiState.update {
                        it.copy(
                            isApplying = false,
                            activeTemplateName = defaultTemplate.name,
                            message = "已禁用专业模板"
                        )
                    }
                }
            } else {
                // 启用：切换到选中的专业模板
                val template = currentState.templates.find {
                    it.name == currentState.activeTemplateType.templateName
                } ?: return@launch

                _uiState.update { it.copy(isApplying = true, message = null) }
                configDataStore.updateReportTemplate(
                    ReportTemplateConfig(
                        selectedName = template.name,
                        content = template.content,
                        isCustom = false
                    )
                )
                _uiState.update {
                    it.copy(
                        isApplying = false,
                        activeTemplateName = template.name,
                        message = "${currentState.activeTemplateType.displayName}已启用"
                    )
                }
            }
        }
    }

    fun startRecording() {
        viewModelScope.launch {
            val state = _uiState.value
            val hasAccess = state.profile?.let {
                it.isAdmin || it.constructionLogsUnlocked
            } == true
            if (!hasAccess || (state.quota?.requestsRemaining ?: 0) <= 0) {
                _uiState.update { it.copy(message = "VIP 权益或 AI 处理额度不可用") }
                return@launch
            }
            val type = _uiState.value.activeTemplateType
            val template = _uiState.value.templates.find { it.name == type.templateName } ?: return@launch
            _uiState.update { it.copy(isStarting = true, message = null) }
            configDataStore.updateReportTemplate(
                ReportTemplateConfig(
                    selectedName = template.name,
                    content = template.content,
                    isCustom = false
                )
            )
            val dateLabel = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val meetingTitle = "${type.displayName} $dateLabel"
            startRecordingUseCase(meetingTitle)
                .onSuccess { meeting ->
                    _uiState.update {
                        it.copy(
                            isStarting = false,
                            activeTemplateName = template.name,
                            pendingMeetingId = meeting.id
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isStarting = false,
                            message = "新建记录失败: ${error.message}"
                        )
                    }
                }
        }
    }

    fun clearPendingNavigation() {
        _uiState.update { it.copy(pendingMeetingId = null) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun loadQuota(config: LLMConfig) {
        _uiState.update { it.copy(isQuotaLoading = true, quotaError = null) }
        quotaService.fetch(config).fold(
            onSuccess = { quota ->
                _uiState.update { it.copy(quota = quota, isQuotaLoading = false) }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        quota = null,
                        isQuotaLoading = false,
                        quotaError = error.message ?: "额度查询失败"
                    )
                }
            }
        )
    }

    private suspend fun loadMembership(endpoint: String, session: AuthSession) {
        _uiState.update { it.copy(isAccountLoading = true, accountError = null) }
        val profileResult = accountApiService.profile(endpoint, session.accessToken)
        val plansResult = accountApiService.plans(endpoint, session.accessToken)
        val profile = profileResult.getOrNull()
        val ordersResult = if (profile?.isAdmin == true) {
            accountApiService.adminOrders(endpoint, session.accessToken)
        } else {
            accountApiService.orders(endpoint, session.accessToken)
        }
        _uiState.update {
            it.copy(
                profile = profile ?: it.profile,
                plans = plansResult.getOrDefault(it.plans),
                orders = if (profile?.isAdmin == true) emptyList()
                else ordersResult.getOrDefault(it.orders),
                pendingAdminOrders = if (profile?.isAdmin == true) {
                    ordersResult.getOrDefault(it.pendingAdminOrders)
                } else {
                    emptyList()
                },
                isAccountLoading = false,
                accountError = profileResult.exceptionOrNull()?.message
                    ?: plansResult.exceptionOrNull()?.message
                    ?: ordersResult.exceptionOrNull()?.message
            )
        }
    }

    private fun processAdminOrder(orderId: String, approve: Boolean) {
        val session = currentSession ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(processingOrderId = orderId, accountError = null) }
            val result = if (approve) {
                accountApiService.approveOrder(currentAccountEndpoint, session.accessToken, orderId)
            } else {
                accountApiService.rejectOrder(currentAccountEndpoint, session.accessToken, orderId)
            }
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            processingOrderId = null,
                            message = if (approve) "充值订单已批准" else "充值订单已拒绝"
                        )
                    }
                    loadMembership(currentAccountEndpoint, session)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            processingOrderId = null,
                            accountError = error.message ?: "订单处理失败"
                        )
                    }
                }
            )
        }
    }
}
