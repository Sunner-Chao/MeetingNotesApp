package com.oa.automation.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
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
    val passwordVisible: Boolean = false
)

sealed interface LoginEvent {
    data class UsernameChanged(val username: String) : LoginEvent
    data class PasswordChanged(val password: String) : LoginEvent
    data object TogglePasswordVisibility : LoginEvent
    data object LoginClicked : LoginEvent
    data object RegisterClicked : LoginEvent
    data object ForgotPasswordClicked : LoginEvent
    data object ErrorDismissed : LoginEvent
}

class LoginViewModel(
    private val configDataStore: ConfigDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

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

            // TODO: Replace with real authentication API call
            // For now, accept any non-empty credentials
            kotlinx.coroutines.delay(800) // Simulate network delay

            configDataStore.saveUsername(username)
            _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
        }
    }
}
