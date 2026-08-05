package com.oa.automation.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.domain.model.SocialAuthProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isLoggedIn: Boolean = false,
    val passwordVisible: Boolean = false,
    val rememberUsername: Boolean = true,
    val authProviders: List<SocialAuthProvider> = defaultSocialAuthProviders()
)

sealed interface LoginEvent {
    data class UsernameChanged(val username: String) : LoginEvent
    data class PasswordChanged(val password: String) : LoginEvent
    data object TogglePasswordVisibility : LoginEvent
    data object ToggleRememberUsername : LoginEvent
    data object LoginClicked : LoginEvent
    data object RegisterClicked : LoginEvent
    data object ForgotPasswordClicked : LoginEvent
    data object ErrorDismissed : LoginEvent
}

class LoginViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
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
                val providersById = providers.associateBy(SocialAuthProvider::id)
                _uiState.update { state ->
                    state.copy(
                        authProviders = defaultSocialAuthProviders().map { default ->
                            providersById[default.id] ?: default
                        }
                    )
                }
            }
        }
    }

    fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.UsernameChanged -> {
                _uiState.update { it.copy(username = event.username, errorMessage = null) }
            }
            is LoginEvent.PasswordChanged -> {
                _uiState.update { it.copy(password = event.password, errorMessage = null) }
            }
            is LoginEvent.TogglePasswordVisibility -> {
                _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
            }
            is LoginEvent.ToggleRememberUsername -> {
                _uiState.update { it.copy(rememberUsername = !it.rememberUsername) }
            }
            is LoginEvent.LoginClicked -> performLogin()
            is LoginEvent.RegisterClicked -> { /* handled by navigation */ }
            is LoginEvent.ForgotPasswordClicked -> { /* handled by navigation */ }
            is LoginEvent.ErrorDismissed -> {
                _uiState.update { it.copy(errorMessage = null) }
            }
        }
    }

    private fun performLogin() {
        val username = _uiState.value.username.trim()
        val password = _uiState.value.password

        if (username.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请输入用户名") }
            return
        }
        if (password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请输入密码") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val endpoint = configDataStore.accountEndpointFlow.first()
            accountApiService.login(endpoint, username, password).fold(
                onSuccess = { session ->
                    configDataStore.saveAuthSession(session, endpoint)
                    if (!_uiState.value.rememberUsername) {
                        configDataStore.saveUsername("")
                    }
                    _uiState.update {
                        it.copy(password = "", isLoading = false, isLoggedIn = true)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "登录失败"
                        )
                    }
                }
            )
        }
    }
}

internal fun defaultSocialAuthProviders() = listOf(
    SocialAuthProvider(id = "wechat", name = "微信"),
    SocialAuthProvider(id = "qq", name = "QQ"),
    SocialAuthProvider(id = "feishu", name = "飞书")
)
