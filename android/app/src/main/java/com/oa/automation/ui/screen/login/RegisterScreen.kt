package com.oa.automation.ui.screen.login

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.ui.component.FirebaseUiTokens
import org.koin.androidx.compose.koinViewModel

@Composable
fun RegisterScreen(
    onNavigateBack: () -> Unit,
    onRegisterSuccess: () -> Unit,
    viewModel: RegisterViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    LaunchedEffect(uiState.isRegistered) {
        if (uiState.isRegistered) onRegisterSuccess()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFFF6F8FB))) {
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
                    AuthTabs(
                        selectedLogin = false,
                        onLogin = onNavigateBack,
                        onRegister = {},
                        layout = layout
                    )
                    AuthUsernameField(
                        value = uiState.username,
                        placeholder = "用户名",
                        onValueChange = viewModel::updateUsername,
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                        supportingText = "3-32 个文字、数字或下划线",
                        layout = layout
                    )
                    AuthPasswordField(
                        value = uiState.password,
                        placeholder = "密码",
                        visible = uiState.passwordVisible,
                        onValueChange = viewModel::updatePassword,
                        onToggleVisibility = viewModel::togglePasswordVisibility,
                        imeAction = ImeAction.Next,
                        onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                        supportingText = "至少 8 个字符",
                        layout = layout
                    )
                    AuthPasswordField(
                        value = uiState.confirmPassword,
                        placeholder = "确认密码",
                        visible = uiState.passwordVisible,
                        onValueChange = viewModel::updateConfirmPassword,
                        onToggleVisibility = viewModel::togglePasswordVisibility,
                        imeAction = ImeAction.Done,
                        onImeAction = viewModel::register,
                        layout = layout
                    )
                    AnimatedVisibility(
                        visible = uiState.errorMessage != null,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Button(
                        onClick = viewModel::register,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth().height(layout.buttonHeight),
                        shape = RoundedCornerShape(27.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp)
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("注册并登录", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    SocialLoginSection(
                        providers = uiState.authProviders,
                        onProviderClick = { provider ->
                            if (provider.enabled && provider.authorizationUrl.isNotBlank()) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(provider.authorizationUrl))
                                    )
                                }.onFailure {
                                    Toast.makeText(
                                        context,
                                        "无法打开${provider.name}登录",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        layout = layout
                    )
                }
            }
            AuthAgreement(prefix = "注册", layout = layout)
            AuthBenefits(layout)
            Spacer(Modifier.height(layout.bottomSpacing))
        }
        AnimatedVisibility(
            visible = uiState.isLoading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
        }
    }
}
