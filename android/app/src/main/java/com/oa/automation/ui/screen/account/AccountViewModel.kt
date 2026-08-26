package com.oa.automation.ui.screen.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.oa.automation.BuildConfig
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AccountProfile
import com.oa.automation.domain.model.AuthSession
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.infrastructure.llm.AgentQuota
import com.oa.automation.infrastructure.llm.AgentQuotaService
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.infrastructure.account.AccountSessionSynchronizer
import com.oa.automation.infrastructure.account.ProfileAvatarCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountUiState(
    val username: String = "",
    val profile: AccountProfile? = null,
    val quota: AgentQuota? = null,
    val isQuotaLoading: Boolean = false,
    val quotaError: String? = null,
    val tokenConfigured: Boolean = false,
    val managedUsers: List<AccountProfile> = emptyList(),
    val isManagingUsers: Boolean = false,
    val processingUserId: String? = null,
    val managementError: String? = null,
    val isProfileEditorVisible: Boolean = false,
    val profileDraftDisplayName: String = "",
    val profileDraftAvatarDataUrl: String? = null,
    val isProfileImageProcessing: Boolean = false,
    val isProfileSaving: Boolean = false,
    val profileError: String? = null,
    val profileMessage: String? = null,
    val isLoggedOut: Boolean = false
)

class AccountViewModel(
    private val configDataStore: ConfigDataStore,
    private val quotaService: AgentQuotaService,
    private val accountApiService: AccountApiService,
    private val accountSessionSynchronizer: AccountSessionSynchronizer,
    private val profileAvatarCodec: ProfileAvatarCodec
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()
    private var currentLlmConfig: LLMConfig? = null
    private var currentSession: AuthSession? = null
    private var currentAccountEndpoint: String = ""

    init {
        viewModelScope.launch {
            combine(
                configDataStore.authSessionFlow,
                configDataStore.accountEndpointFlow,
                configDataStore.appConfigFlow
            ) { session, endpoint, config -> Triple(session, endpoint, config.llmConfig) }
                .collectLatest { (session, endpoint, llmConfig) ->
                    currentSession = session
                    currentAccountEndpoint = endpoint
                    currentLlmConfig = llmConfig
                    val hasToken = !llmConfig.agentAccessToken.isNullOrBlank()
                    _uiState.update {
                        it.copy(
                            username = session?.user?.username.orEmpty(),
                            profile = session?.user,
                            tokenConfigured = hasToken,
                            quota = if (hasToken) it.quota else null,
                            quotaError = null
                        )
                    }
                    if (session != null) loadProfile(endpoint, session.accessToken)
                    if (hasToken) loadQuota(llmConfig)
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

    fun refreshAccount() {
        val session = currentSession ?: return
        viewModelScope.launch {
            accountSessionSynchronizer.refresh().onSuccess { credentials ->
                _uiState.update {
                    it.copy(username = credentials.user.username, profile = credentials.user)
                }
            }
            loadProfile(currentAccountEndpoint, session.accessToken)
            currentLlmConfig?.let { config ->
                if (!config.agentAccessToken.isNullOrBlank()) loadQuota(config)
            }
        }
    }

    fun startProfileEdit() {
        val profile = _uiState.value.profile ?: return
        _uiState.update {
            it.copy(
                isProfileEditorVisible = true,
                profileDraftDisplayName = profile.displayName,
                profileDraftAvatarDataUrl = profile.avatarDataUrl,
                profileError = null
            )
        }
    }

    fun dismissProfileEdit() {
        if (_uiState.value.isProfileSaving) return
        _uiState.update {
            it.copy(
                isProfileEditorVisible = false,
                isProfileImageProcessing = false,
                profileError = null
            )
        }
    }

    fun updateProfileDisplayName(value: String) {
        _uiState.update {
            it.copy(
                profileDraftDisplayName = value.take(BuildConfig.PROFILE_NAME_MAX_LENGTH),
                profileError = null
            )
        }
    }

    fun selectProfileAvatar(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProfileImageProcessing = true, profileError = null) }
            profileAvatarCodec.encode(uri).fold(
                onSuccess = { avatar ->
                    _uiState.update {
                        it.copy(
                            profileDraftAvatarDataUrl = avatar,
                            isProfileImageProcessing = false
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isProfileImageProcessing = false,
                            profileError = error.message ?: "头像处理失败"
                        )
                    }
                }
            )
        }
    }

    fun clearProfileAvatar() {
        _uiState.update {
            it.copy(profileDraftAvatarDataUrl = null, profileError = null)
        }
    }

    fun saveProfile() {
        val session = currentSession ?: return
        val state = _uiState.value
        if (state.isProfileSaving || state.isProfileImageProcessing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isProfileSaving = true, profileError = null) }
            accountApiService.updateProfile(
                currentAccountEndpoint,
                session.accessToken,
                state.profileDraftDisplayName,
                state.profileDraftAvatarDataUrl
            ).fold(
                onSuccess = { profile ->
                    configDataStore.updateAccountProfile(profile)
                    _uiState.update {
                        it.copy(
                            username = profile.username,
                            profile = profile,
                            isProfileSaving = false,
                            isProfileEditorVisible = false,
                            profileMessage = "个人资料已更新"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isProfileSaving = false,
                            profileError = error.message ?: "个人资料保存失败"
                        )
                    }
                }
            )
        }
    }

    fun clearProfileMessage() {
        _uiState.update { it.copy(profileMessage = null) }
    }

    fun setUserEnabled(userId: String, enabled: Boolean) {
        val session = currentSession ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(processingUserId = userId, managementError = null)
            }
            accountApiService.setUserEnabled(
                currentAccountEndpoint,
                session.accessToken,
                userId,
                enabled
            ).fold(
                onSuccess = { loadManagedUsers(currentAccountEndpoint, session.accessToken) },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            processingUserId = null,
                            managementError = error.message ?: "用户状态更新失败"
                        )
                    }
                }
            )
        }
    }

    fun deleteUser(userId: String) {
        val session = currentSession ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(processingUserId = userId, managementError = null)
            }
            accountApiService.deleteUser(
                currentAccountEndpoint,
                session.accessToken,
                userId
            ).fold(
                onSuccess = { loadManagedUsers(currentAccountEndpoint, session.accessToken) },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            processingUserId = null,
                            managementError = error.message ?: "用户删除失败"
                        )
                    }
                }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            currentSession?.let { session ->
                accountApiService.logout(currentAccountEndpoint, session.accessToken)
            }
            configDataStore.clearUsername()
            _uiState.update { it.copy(isLoggedOut = true) }
        }
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
                        quotaError = error.message ?: "积分查询失败"
                    )
                }
            }
        )
    }

    private suspend fun loadProfile(endpoint: String, accessToken: String) {
        accountApiService.profile(endpoint, accessToken).fold(
            onSuccess = { profile ->
                configDataStore.updateAccountProfile(profile)
                _uiState.update { it.copy(username = profile.username, profile = profile) }
                if (profile.isAdmin) loadManagedUsers(endpoint, accessToken)
            },
            onFailure = { error ->
                if (error.message?.contains("会话") == true) {
                    configDataStore.clearUsername()
                    _uiState.update { it.copy(isLoggedOut = true) }
                }
            }
        )
    }

    private suspend fun loadManagedUsers(endpoint: String, accessToken: String) {
        _uiState.update { it.copy(isManagingUsers = true, managementError = null) }
        accountApiService.adminUsers(endpoint, accessToken).fold(
            onSuccess = { users ->
                _uiState.update {
                    it.copy(
                        managedUsers = users,
                        isManagingUsers = false,
                        processingUserId = null
                    )
                }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        isManagingUsers = false,
                        processingUserId = null,
                        managementError = error.message ?: "用户列表加载失败"
                    )
                }
            }
        )
    }
}
