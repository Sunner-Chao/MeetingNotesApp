package com.oa.automation.ui.screen.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AuthSession
import com.oa.automation.domain.model.GrowthActionResult
import com.oa.automation.domain.model.GrowthCampaignDetail
import com.oa.automation.domain.model.GrowthOverview
import com.oa.automation.domain.model.GrowthPrivateChannel
import com.oa.automation.domain.model.GrowthSystemMessage
import com.oa.automation.infrastructure.account.AccountApiService
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GrowthCenterUiState(
    val overview: GrowthOverview? = null,
    val isAuthenticated: Boolean = false,
    val pointsRemaining: Int = 0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val redeemCode: String = "",
    val isRedeeming: Boolean = false,
    val activeCampaignId: String? = null,
    val campaignDetail: GrowthCampaignDetail? = null,
    val isCampaignLoading: Boolean = false,
    val busyAction: String? = null,
    val inviteUrl: String = "",
    val qrImageBytes: ByteArray? = null,
    val managerCardImageBytes: ByteArray? = null,
    val isSubmittingApplication: Boolean = false,
    val seenCampaignIds: Set<String> = emptySet(),
    val systemMessages: List<GrowthSystemMessage> = emptyList(),
    val message: String? = null
)

class GrowthCenterViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(GrowthCenterUiState())
    val uiState: StateFlow<GrowthCenterUiState> = _uiState.asStateFlow()

    private var session: AuthSession? = null
    private var endpoint: String = ""

    init {
        viewModelScope.launch {
            configDataStore.seenGrowthCampaignIdsFlow.collectLatest { seenCampaignIds ->
                _uiState.update { it.copy(seenCampaignIds = seenCampaignIds) }
            }
        }
        viewModelScope.launch {
            combine(
                configDataStore.authSessionFlow,
                configDataStore.accountEndpointFlow
            ) { currentSession, currentEndpoint -> currentSession to currentEndpoint }
                .collectLatest { (currentSession, currentEndpoint) ->
                    session = currentSession
                    endpoint = currentEndpoint
                    if (currentSession == null) {
                        _uiState.update {
                            it.copy(
                                isAuthenticated = false,
                                isLoading = true,
                                errorMessage = null,
                                overview = null,
                                systemMessages = emptyList(),
                                qrImageBytes = null,
                                managerCardImageBytes = null
                            )
                        }
                        loadPublicChannel()
                    } else {
                        _uiState.update {
                            it.copy(
                                isAuthenticated = true,
                                pointsRemaining = currentSession.user.usage?.pointsRemaining ?: 0
                            )
                        }
                        loadOverview()
                    }
                }
        }
    }

    fun refresh() {
        if (session == null) return
        viewModelScope.launch { loadOverview() }
    }

    fun updateRedeemCode(value: String) {
        _uiState.update {
            it.copy(
                redeemCode = value.uppercase().filter { char -> char.isLetterOrDigit() || char == '-' }.take(64),
                errorMessage = null
            )
        }
    }

    fun redeem() {
        val currentSession = session ?: return
        val code = _uiState.value.redeemCode.trim()
        if (code.length < 4 || _uiState.value.isRedeeming) {
            _uiState.update { it.copy(errorMessage = "请输入有效的礼品码或兑换码") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRedeeming = true, errorMessage = null) }
            accountApiService.redeemGrowthCode(endpoint, currentSession.accessToken, code).fold(
                onSuccess = { result ->
                    configDataStore.updateAccountProfile(result.profile)
                    _uiState.update {
                        it.copy(
                            redeemCode = "",
                            isRedeeming = false,
                            pointsRemaining = result.profile.usage?.pointsRemaining ?: it.pointsRemaining,
                            message = result.message.ifBlank { "兑换成功" }
                        )
                    }
                    loadOverview(showLoading = false)
                    result.privateChannel?.let { channel ->
                        recordChannelEvent(channel.id, "redeem")
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isRedeeming = false,
                            errorMessage = error.message ?: "兑换失败"
                        )
                    }
                }
            )
        }
    }

    fun openCampaign(campaignId: String) {
        val currentSession = session ?: return
        if (campaignId.isBlank()) return
        markCampaignsRead(listOf(campaignId))
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeCampaignId = campaignId,
                    campaignDetail = null,
                    isCampaignLoading = true,
                    errorMessage = null
                )
            }
            accountApiService.growthCampaignDetail(
                endpoint,
                currentSession.accessToken,
                campaignId
            ).fold(
                onSuccess = { detail ->
                    _uiState.update { it.copy(campaignDetail = detail, isCampaignLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            activeCampaignId = null,
                            isCampaignLoading = false,
                            errorMessage = error.message ?: "活动加载失败"
                        )
                    }
                }
            )
        }
    }

    fun closeCampaign() {
        if (_uiState.value.busyAction != null) return
        _uiState.update {
            it.copy(activeCampaignId = null, campaignDetail = null, isCampaignLoading = false)
        }
    }

    fun joinCampaign() = runCampaignAction("join") { currentSession, campaignId ->
        accountApiService.joinGrowthCampaign(endpoint, currentSession.accessToken, campaignId)
    }

    fun checkinCampaign() = runCampaignAction("checkin") { currentSession, campaignId ->
        accountApiService.checkinGrowthCampaign(endpoint, currentSession.accessToken, campaignId)
    }

    fun drawCampaign() = runCampaignAction("draw") { currentSession, campaignId ->
        accountApiService.drawGrowthCampaign(endpoint, currentSession.accessToken, campaignId)
    }

    fun answerCampaign(questionKey: String, answer: String) =
        runCampaignAction("answer-$questionKey") { currentSession, campaignId ->
            accountApiService.answerGrowthCampaign(
                endpoint,
                currentSession.accessToken,
                campaignId,
                questionKey,
                answer
            )
        }

    fun recordChannelEvent(channelId: String, eventType: String) {
        val currentSession = session ?: return
        if (channelId.isBlank()) return
        viewModelScope.launch {
            accountApiService.recordGrowthChannelEvent(
                endpoint,
                currentSession.accessToken,
                channelId,
                eventType,
                _uiState.value.activeCampaignId
            )
        }
    }

    fun submitChannelApplication(answers: Map<String, String>) {
        val currentSession = session ?: run {
            _uiState.update { it.copy(errorMessage = "登录后才能提交入群申请") }
            return
        }
        val channel = _uiState.value.overview?.privateChannel ?: return
        if (_uiState.value.isSubmittingApplication) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingApplication = true, errorMessage = null) }
            accountApiService.submitGrowthChannelApplication(
                endpoint,
                currentSession.accessToken,
                channel.id,
                answers
            ).fold(
                onSuccess = { updatedChannel ->
                    _uiState.update { state ->
                        state.copy(
                            isSubmittingApplication = false,
                            overview = state.overview?.copy(privateChannel = updatedChannel),
                            message = "申请已提交，审核通过后会在通知中心提醒你"
                        )
                    }
                    loadChannelImages(updatedChannel)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSubmittingApplication = false,
                            errorMessage = error.message ?: "申请提交失败"
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
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun markCampaignsRead(campaignIds: Collection<String>) {
        val normalizedIds = campaignIds.filter { it.isNotBlank() }.toSet()
        if (normalizedIds.isEmpty()) return
        val updatedIds = _uiState.value.seenCampaignIds + normalizedIds
        _uiState.update { it.copy(seenCampaignIds = updatedIds) }
        viewModelScope.launch {
            configDataStore.saveSeenGrowthCampaignIds(updatedIds)
        }
    }

    fun markSystemMessagesRead(messageIds: Collection<String>) {
        val currentSession = session ?: return
        val normalizedIds = messageIds.filter { it.isNotBlank() }.toSet()
        if (normalizedIds.isEmpty()) return
        val readAt = System.currentTimeMillis() / 1000
        _uiState.update { state ->
            state.copy(
                systemMessages = state.systemMessages.map { item ->
                    if (item.id in normalizedIds) item.copy(readAt = readAt) else item
                }
            )
        }
        viewModelScope.launch {
            normalizedIds.forEach { messageId ->
                accountApiService.markGrowthSystemMessageRead(
                    endpoint,
                    currentSession.accessToken,
                    messageId
                )
            }
        }
    }

    private fun runCampaignAction(
        action: String,
        request: suspend (AuthSession, String) -> Result<GrowthActionResult>
    ) {
        val currentSession = session ?: return
        val campaignId = _uiState.value.activeCampaignId ?: return
        if (_uiState.value.busyAction != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(busyAction = action, errorMessage = null) }
            request(currentSession, campaignId).fold(
                onSuccess = { result ->
                    _uiState.update {
                        it.copy(
                            busyAction = null,
                            message = result.message.ifBlank { "操作成功" }
                        )
                    }
                    reloadCampaign(campaignId)
                    loadOverview(showLoading = false)
                    accountApiService.profile(endpoint, currentSession.accessToken).onSuccess { profile ->
                        configDataStore.updateAccountProfile(profile)
                        _uiState.update {
                            it.copy(pointsRemaining = profile.usage?.pointsRemaining ?: it.pointsRemaining)
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            busyAction = null,
                            errorMessage = error.message ?: "活动操作失败"
                        )
                    }
                }
            )
        }
    }

    private suspend fun reloadCampaign(campaignId: String) {
        val currentSession = session ?: return
        accountApiService.growthCampaignDetail(endpoint, currentSession.accessToken, campaignId)
            .onSuccess { detail -> _uiState.update { it.copy(campaignDetail = detail) } }
    }

    private suspend fun loadOverview(showLoading: Boolean = true) {
        val currentSession = session ?: return
        if (showLoading) _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        accountApiService.growthOverview(endpoint, currentSession.accessToken).fold(
            onSuccess = { overview ->
                _uiState.update {
                    it.copy(
                        overview = overview,
                        isLoading = false,
                        errorMessage = null,
                        inviteUrl = absoluteUrl(endpoint, overview.referral.sharePath)
                    )
                }
                loadChannelImages(overview.privateChannel)
                accountApiService.growthSystemMessages(endpoint, currentSession.accessToken)
                    .onSuccess { messages ->
                        _uiState.update { it.copy(systemMessages = messages) }
                    }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "邀请与活动加载失败"
                    )
                }
            }
        )
    }

    private suspend fun loadPublicChannel() {
        if (endpoint.isBlank()) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "福利群暂不可用") }
            return
        }
        accountApiService.publicGrowthPrivateChannel(endpoint).fold(
            onSuccess = { channel ->
                _uiState.update {
                    it.copy(
                        overview = GrowthOverview(privateChannel = channel),
                        isLoading = false,
                        errorMessage = null,
                        qrImageBytes = null
                    )
                }
                loadChannelImages(channel)
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "福利群暂不可用"
                    )
                }
            }
        )
    }

    private suspend fun loadChannelImages(channel: GrowthPrivateChannel?) {
        if (channel == null) return
        if (channel.qrImageUrl.isBlank()) {
            _uiState.update { it.copy(qrImageBytes = null) }
        } else {
            accountApiService.downloadGrowthAsset(endpoint, channel.qrImageUrl, session?.accessToken).onSuccess { bytes ->
                _uiState.update { it.copy(qrImageBytes = bytes) }
            }
        }
        if (channel.managerCardImageUrl.isBlank()) {
            _uiState.update { it.copy(managerCardImageBytes = null) }
        } else {
            accountApiService.downloadGrowthAsset(endpoint, channel.managerCardImageUrl).onSuccess { bytes ->
                _uiState.update { it.copy(managerCardImageBytes = bytes) }
            }
        }
    }

    private fun absoluteUrl(baseEndpoint: String, pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl
        if (pathOrUrl.isBlank()) return ""
        return runCatching {
            val uri = URI(baseEndpoint)
            "${uri.scheme}://${uri.rawAuthority}/${pathOrUrl.trimStart('/')}"
        }.getOrDefault(pathOrUrl)
    }
}
