package com.oa.automation.ui.screen.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.ui.component.FirebaseUiTokens
import org.koin.androidx.compose.koinViewModel

@Composable
fun ForgotPasswordScreen(
    onNavigateBack: () -> Unit,
    viewModel: ForgotPasswordViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(uiState.completed) {
        if (uiState.completed) onNavigateBack()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val layout = authLayoutSpec(maxWidth, maxHeight)
        AuthBackground(layout)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AuthPageHeader(layout)
            Spacer(Modifier.height(layout.headerCardSpacing))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = FirebaseUiTokens.AuthContentMaxWidth)
                    .padding(horizontal = layout.cardOuterPadding),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = layout.cardHorizontalPadding,
                        vertical = layout.cardVerticalPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(layout.contentSpacing)
                ) {
                    Text("重置密码", style = MaterialTheme.typography.titleLarge)
                    AuthModeSelector(
                        mode = uiState.mode,
                        onModeChange = viewModel::updateMode,
                        modes = listOf(AuthEntryMode.PHONE, AuthEntryMode.EMAIL)
                    )
                    AuthIdentifierField(
                        value = uiState.identifier,
                        mode = uiState.mode,
                        onValueChange = viewModel::updateIdentifier,
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                        layout = layout
                    )
                    AuthVerificationCodeField(
                        value = uiState.code,
                        isSending = uiState.isSendingCode,
                        cooldownSeconds = uiState.codeCooldownSeconds,
                        onValueChange = viewModel::updateCode,
                        onSendCode = viewModel::requestCode,
                        onDone = { focusManager.moveFocus(FocusDirection.Down) },
                        layout = layout
                    )
                    AuthPasswordField(
                        value = uiState.password,
                        placeholder = "新密码",
                        visible = uiState.passwordVisible,
                        onValueChange = viewModel::updatePassword,
                        onToggleVisibility = viewModel::togglePasswordVisibility,
                        imeAction = ImeAction.Next,
                        onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                        supportingText = "8-128 个字符",
                        layout = layout
                    )
                    AuthPasswordField(
                        value = uiState.confirmPassword,
                        placeholder = "确认新密码",
                        visible = uiState.passwordVisible,
                        onValueChange = viewModel::updateConfirmPassword,
                        onToggleVisibility = viewModel::togglePasswordVisibility,
                        imeAction = ImeAction.Done,
                        onImeAction = viewModel::resetPassword,
                        layout = layout
                    )
                    if (uiState.codeSentTo.isNotBlank()) {
                        Text(
                            "验证码已发送至 ${uiState.codeSentTo}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnimatedVisibility(
                        visible = uiState.errorMessage != null,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Text(
                            uiState.errorMessage.orEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = viewModel::resetPassword,
                        enabled = !uiState.isSubmitting,
                        modifier = Modifier.fillMaxWidth().height(layout.buttonHeight),
                        shape = RoundedCornerShape(27.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (uiState.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("确认重置", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            TextButton(onClick = onNavigateBack) { Text("返回登录") }
            Spacer(Modifier.height(layout.bottomSpacing))
        }
    }
}
