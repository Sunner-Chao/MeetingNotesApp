package com.oa.automation.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.infrastructure.account.AccountApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ForgotPasswordUiState(
    val mode: AuthEntryMode = AuthEntryMode.PHONE,
    val identifier: String = "",
    val code: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isSendingCode: Boolean = false,
    val isSubmitting: Boolean = false,
    val codeCooldownSeconds: Int = 0,
    val codeSentTo: String = "",
    val errorMessage: String? = null,
    val completed: Boolean = false,
    val passwordVisible: Boolean = false
)

class ForgotPasswordViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(ForgotPasswordUiState())
    val uiState: StateFlow<ForgotPasswordUiState> = _uiState.asStateFlow()

    fun updateMode(mode: AuthEntryMode) {
        if (mode != AuthEntryMode.PASSWORD) _uiState.update { it.copy(mode = mode, errorMessage = null) }
    }
    fun updateIdentifier(value: String) = _uiState.update { it.copy(identifier = value, errorMessage = null) }
    fun updateCode(value: String) = _uiState.update {
        it.copy(code = value.filter(Char::isDigit).take(6), errorMessage = null)
    }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }
    fun updateConfirmPassword(value: String) = _uiState.update { it.copy(confirmPassword = value, errorMessage = null) }
    fun togglePasswordVisibility() = _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }

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
            accountApiService.requestAuthCode(endpoint, channel, identifier, "reset_password").fold(
                onSuccess = { result ->
                    _uiState.update {
                        it.copy(
                            isSendingCode = false,
                            codeSentTo = result.maskedIdentifier,
                            codeCooldownSeconds = result.retryAfter.coerceAtLeast(1)
                        )
                    }
                    startCooldown()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSendingCode = false, errorMessage = error.message ?: "验证码发送失败")
                    }
                }
            )
        }
    }

    fun resetPassword() {
        val state = _uiState.value
        val channel = state.mode.channel ?: return
        val identifier = state.identifier.trim()
        val error = validationError(channel, identifier) ?: when {
            state.code.length != 6 -> "请输入 6 位验证码"
            state.password.length !in 8..128 -> "新密码长度必须为 8-128 个字符"
            state.password != state.confirmPassword -> "两次输入的密码不一致"
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(errorMessage = error) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val endpoint = configDataStore.accountEndpointFlow.first()
            accountApiService.resetPassword(
                endpoint, channel, identifier, state.code, state.password
            ).fold(
                onSuccess = { _uiState.update { it.copy(isSubmitting = false, completed = true) } },
                onFailure = { failure ->
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = failure.message ?: "密码重置失败")
                    }
                }
            )
        }
    }

    private fun startCooldown() = viewModelScope.launch {
        while (_uiState.value.codeCooldownSeconds > 0) {
            delay(1_000)
            _uiState.update {
                it.copy(codeCooldownSeconds = (it.codeCooldownSeconds - 1).coerceAtLeast(0))
            }
        }
    }
}
