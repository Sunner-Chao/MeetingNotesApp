package com.oa.automation.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.domain.model.SocialAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RegisterUiState(
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val passwordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isRegistered: Boolean = false,
    val authProviders: List<SocialAuthProvider> = defaultSocialAuthProviders()
)

class RegisterViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
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

    fun updateUsername(value: String) = _uiState.update { it.copy(username = value, errorMessage = null) }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }
    fun updateConfirmPassword(value: String) =
        _uiState.update { it.copy(confirmPassword = value, errorMessage = null) }

    fun togglePasswordVisibility() =
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun register() {
        val state = _uiState.value
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
            accountApiService.register(endpoint, username, state.password).fold(
                onSuccess = { session ->
                    configDataStore.saveAuthSession(session, endpoint)
                    _uiState.update {
                        it.copy(
                            password = "",
                            confirmPassword = "",
                            isLoading = false,
                            isRegistered = true
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "注册失败"
                        )
                    }
                }
            )
        }
    }
}
