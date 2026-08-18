package com.oa.automation.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AuthSession
import com.oa.automation.domain.model.SocialAuthProvider
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.infrastructure.account.LocalAccountDataMigrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RegisterUiState(
    val mode: AuthEntryMode = AuthEntryMode.PHONE,
    val identifier: String = "",
    val verificationCode: String = "",
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val passwordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isSendingCode: Boolean = false,
    val codeCooldownSeconds: Int = 0,
    val codeSentTo: String = "",
    val errorMessage: String? = null,
    val isRegistered: Boolean = false,
    val authProviders: List<SocialAuthProvider> = defaultSocialAuthProviders()
)

class RegisterViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService,
    private val localAccountDataMigrator: LocalAccountDataMigrator
) : ViewModel() {
    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val endpoint = configDataStore.accountEndpointFlow.first()
            accountApiService.authProviders(endpoint).onSuccess { providers ->
                _uiState.update {
                    it.copy(
                        authProviders = providers.filter { provider ->
                            provider.id == "wechat" && provider.tier != "team"
                        }
                    )
                }
            }
        }
    }

    fun updateMode(value: AuthEntryMode) = _uiState.update {
        it.copy(mode = value, errorMessage = null)
    }
    fun updateIdentifier(value: String) = _uiState.update {
        it.copy(identifier = value, errorMessage = null)
    }
    fun updateVerificationCode(value: String) = _uiState.update {
        it.copy(verificationCode = value.filter(Char::isDigit).take(6), errorMessage = null)
    }
    fun updateUsername(value: String) = _uiState.update {
        it.copy(username = value, errorMessage = null)
    }
    fun updatePassword(value: String) = _uiState.update {
        it.copy(password = value, errorMessage = null)
    }
    fun updateConfirmPassword(value: String) = _uiState.update {
        it.copy(confirmPassword = value, errorMessage = null)
    }
    fun togglePasswordVisibility() = _uiState.update {
        it.copy(passwordVisible = !it.passwordVisible)
    }

    fun requestCode() {
        val state = _uiState.value
        val channel = state.mode.channel ?: return
        val identifier = state.identifier.trim()
        validationError(channel, identifier)?.let { message ->
            _uiState.update { it.copy(errorMessage = message) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSendingCode = true, errorMessage = null) }
            val endpoint = configDataStore.accountEndpointFlow.first()
            accountApiService.requestAuthCode(endpoint, channel, identifier).fold(
                onSuccess = { result ->
                    _uiState.update {
                        it.copy(
                            isSendingCode = false,
                            codeSentTo = result.maskedIdentifier,
                            codeCooldownSeconds = result.retryAfter
                        )
                    }
                    while (_uiState.value.codeCooldownSeconds > 0) {
                        delay(1_000)
                        _uiState.update {
                            it.copy(codeCooldownSeconds = (it.codeCooldownSeconds - 1).coerceAtLeast(0))
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSendingCode = false,
                            errorMessage = error.message ?: "验证码发送失败"
                        )
                    }
                }
            )
        }
    }

    fun register() {
        val state = _uiState.value
        if (state.mode == AuthEntryMode.PASSWORD) {
            registerWithPassword(state)
            return
        }
        val channel = state.mode.channel ?: return
        val identifier = state.identifier.trim()
        validationError(channel, identifier)?.let { message ->
            _uiState.update { it.copy(errorMessage = message) }
            return
        }
        if (state.verificationCode.length != 6) {
            _uiState.update { it.copy(errorMessage = "请输入 6 位验证码") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val endpoint = configDataStore.accountEndpointFlow.first()
            accountApiService.verifyAuthCode(
                endpoint,
                channel,
                identifier,
                state.verificationCode
            ).fold(::completeRegistration, ::showError)
        }
    }

    private fun registerWithPassword(state: RegisterUiState) {
        val username = state.username.trim()
        when {
            username.length !in 3..32 -> {
                _uiState.update { it.copy(errorMessage = "用户名长度必须为 3-32 个字符") }
                return
            }
            state.password.length !in 8..128 -> {
                _uiState.update { it.copy(errorMessage = "密码长度必须为 8-128 个字符") }
                return
            }
            state.password != state.confirmPassword -> {
                _uiState.update { it.copy(errorMessage = "两次输入的密码不一致") }
                return
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val endpoint = configDataStore.accountEndpointFlow.first()
            accountApiService.register(endpoint, username, state.password)
                .fold(::completeRegistration, ::showError)
        }
    }

    private fun completeRegistration(session: AuthSession) {
        viewModelScope.launch {
            val endpoint = configDataStore.accountEndpointFlow.first()
            configDataStore.saveAuthSession(session, endpoint)
            localAccountDataMigrator.migrateAsync(endpoint, session)
            _uiState.update {
                it.copy(
                    password = "",
                    confirmPassword = "",
                    verificationCode = "",
                    isLoading = false,
                    isRegistered = true
                )
            }
        }
    }

    private fun showError(error: Throwable) {
        _uiState.update {
            it.copy(isLoading = false, errorMessage = error.message ?: "注册失败")
        }
    }
}
