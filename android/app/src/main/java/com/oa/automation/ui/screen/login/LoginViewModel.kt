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

enum class AuthEntryMode(val channel: String?) {
    PHONE("phone"),
    EMAIL("email"),
    PASSWORD(null)
}

data class LoginUiState(
    val mode: AuthEntryMode = AuthEntryMode.PASSWORD,
    val identifier: String = "",
    val verificationCode: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val isSendingCode: Boolean = false,
    val codeCooldownSeconds: Int = 0,
    val codeSentTo: String = "",
    val errorMessage: String? = null,
    val isLoggedIn: Boolean = false,
    val passwordVisible: Boolean = false,
    val rememberUsername: Boolean = true,
    val authProviders: List<SocialAuthProvider> = defaultSocialAuthProviders()
)

sealed interface LoginEvent {
    data class ModeChanged(val mode: AuthEntryMode) : LoginEvent
    data class IdentifierChanged(val identifier: String) : LoginEvent
    data class VerificationCodeChanged(val code: String) : LoginEvent
    data class UsernameChanged(val username: String) : LoginEvent
    data class PasswordChanged(val password: String) : LoginEvent
    data object SendCodeClicked : LoginEvent
    data object TogglePasswordVisibility : LoginEvent
    data object ToggleRememberUsername : LoginEvent
    data object LoginClicked : LoginEvent
    data object RegisterClicked : LoginEvent
    data object ForgotPasswordClicked : LoginEvent
    data object ErrorDismissed : LoginEvent
}

class LoginViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService,
    private val localAccountDataMigrator: LocalAccountDataMigrator
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val savedUsername = configDataStore.usernameFlow.first().orEmpty()
            _uiState.update {
                it.copy(
                    username = savedUsername,
                    rememberUsername = savedUsername.isNotBlank()
                )
            }
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

    fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.ModeChanged -> _uiState.update {
                it.copy(mode = event.mode, errorMessage = null)
            }
            is LoginEvent.IdentifierChanged -> _uiState.update {
                it.copy(identifier = event.identifier, errorMessage = null)
            }
            is LoginEvent.VerificationCodeChanged -> _uiState.update {
                it.copy(
                    verificationCode = event.code.filter(Char::isDigit).take(6),
                    errorMessage = null
                )
            }
            is LoginEvent.UsernameChanged -> _uiState.update {
                it.copy(username = event.username, errorMessage = null)
            }
            is LoginEvent.PasswordChanged -> _uiState.update {
                it.copy(password = event.password, errorMessage = null)
            }
            LoginEvent.SendCodeClicked -> requestCode()
            LoginEvent.TogglePasswordVisibility -> _uiState.update {
                it.copy(passwordVisible = !it.passwordVisible)
            }
            LoginEvent.ToggleRememberUsername -> _uiState.update {
                it.copy(rememberUsername = !it.rememberUsername)
            }
            LoginEvent.LoginClicked -> performLogin()
            LoginEvent.RegisterClicked,
            LoginEvent.ForgotPasswordClicked -> Unit
            LoginEvent.ErrorDismissed -> _uiState.update { it.copy(errorMessage = null) }
        }
    }

    private fun requestCode() {
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
                    startCodeCountdown()
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

    private fun startCodeCountdown() {
        viewModelScope.launch {
            while (_uiState.value.codeCooldownSeconds > 0) {
                delay(1_000)
                _uiState.update {
                    it.copy(codeCooldownSeconds = (it.codeCooldownSeconds - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private fun performLogin() {
        val state = _uiState.value
        if (state.mode == AuthEntryMode.PASSWORD) {
            performPasswordLogin(state)
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
            ).fold(::completeLogin, ::showLoginError)
        }
    }

    private fun performPasswordLogin(state: LoginUiState) {
        val username = state.username.trim()
        if (username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请输入邮箱/用户名和密码") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val endpoint = configDataStore.accountEndpointFlow.first()
            accountApiService.login(endpoint, username, state.password)
                .fold(::completeLogin, ::showLoginError)
        }
    }

    private fun completeLogin(session: AuthSession) {
        viewModelScope.launch {
            val endpoint = configDataStore.accountEndpointFlow.first()
            configDataStore.saveAuthSession(session, endpoint)
            localAccountDataMigrator.migrateAsync(endpoint, session)
            if (!_uiState.value.rememberUsername) configDataStore.saveUsername("")
            _uiState.update {
                it.copy(
                    password = "",
                    verificationCode = "",
                    isLoading = false,
                    isLoggedIn = true
                )
            }
        }
    }

    private fun showLoginError(error: Throwable) {
        _uiState.update {
            it.copy(isLoading = false, errorMessage = error.message ?: "登录失败")
        }
    }
}

internal fun defaultSocialAuthProviders() = listOf(
    SocialAuthProvider(id = "wechat", name = "微信")
)

internal fun validationError(channel: String, identifier: String): String? = when (channel) {
    "phone" -> if (!identifier.replace(" ", "").replace("-", "")
            .matches(Regex("^(?:\\+?86)?1[3-9]\\d{9}$"))) {
        "请输入有效的中国大陆手机号"
    } else null
    "email" -> if (!identifier.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
        "请输入有效的邮箱地址"
    } else null
    else -> "登录方式无效"
}
