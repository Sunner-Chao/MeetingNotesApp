package com.oa.automation.ui.screen.login

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.R
import com.oa.automation.BuildConfig
import com.oa.automation.domain.model.SocialAuthProvider
import com.oa.automation.ui.component.AppLauncherIcon
import com.oa.automation.ui.component.FirebaseUiTokens
import com.oa.automation.ui.theme.OAAutomationTheme

private val AuthPageBackground = Color(0xFFF6F8FB)
// The brand header is rendered over a fixed light illustration in both themes.
private val AuthHeaderTitle = Color(0xFF172033)
private val AuthHeaderSubtitle = Color(0xFF46556A)
internal data class AuthLayoutSpec(
    val compact: Boolean,
    val backgroundHeight: Dp,
    val headerTop: Dp,
    val iconSize: Dp,
    val iconTitleSpacing: Dp,
    val headerCardSpacing: Dp,
    val cardOuterPadding: Dp,
    val cardHorizontalPadding: Dp,
    val cardVerticalPadding: Dp,
    val contentSpacing: Dp,
    val tabHeight: Dp,
    val fieldHeight: Dp,
    val buttonHeight: Dp,
    val providerIconSize: Dp,
    val socialRowHeight: Dp,
    val agreementVerticalPadding: Dp,
    val benefitsVerticalPadding: Dp,
    val bottomSpacing: Dp
)

internal fun authLayoutSpec(maxWidth: Dp, maxHeight: Dp): AuthLayoutSpec {
    val compact = maxWidth < 400.dp || maxHeight < 840.dp
    return if (compact) {
        AuthLayoutSpec(
            compact = true,
            backgroundHeight = 340.dp,
            headerTop = 45.dp,
            iconSize = 70.dp,
            iconTitleSpacing = 6.dp,
            headerCardSpacing = 48.dp,
            cardOuterPadding = 26.dp,
            cardHorizontalPadding = 16.dp,
            cardVerticalPadding = 10.dp,
            contentSpacing = 8.dp,
            tabHeight = 42.dp,
            fieldHeight = 52.dp,
            buttonHeight = 50.dp,
            providerIconSize = 40.dp,
            socialRowHeight = 44.dp,
            agreementVerticalPadding = 8.dp,
            benefitsVerticalPadding = 8.dp,
            bottomSpacing = 0.dp
        )
    } else {
        AuthLayoutSpec(
            compact = false,
            backgroundHeight = 390.dp,
            headerTop = 34.dp,
            iconSize = 88.dp,
            iconTitleSpacing = 10.dp,
            headerCardSpacing = 18.dp,
            cardOuterPadding = 26.dp,
            cardHorizontalPadding = 20.dp,
            cardVerticalPadding = 16.dp,
            contentSpacing = 14.dp,
            tabHeight = 48.dp,
            fieldHeight = 58.dp,
            buttonHeight = 54.dp,
            providerIconSize = 48.dp,
            socialRowHeight = 48.dp,
            agreementVerticalPadding = 17.dp,
            benefitsVerticalPadding = 15.dp,
            bottomSpacing = 24.dp
        )
    }
}

@Composable
fun LoginScreen(
    onEvent: (LoginEvent) -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
    onLoginSuccess: () -> Unit,
    onContinueAsGuest: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel? = null
) {
    val uiState = viewModel?.uiState?.collectAsStateWithLifecycle()?.value ?: LoginUiState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) onLoginSuccess()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(AuthPageBackground)) {
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
                        selectedLogin = true,
                        onLogin = {},
                        onRegister = onNavigateToRegister,
                        layout = layout
                    )
                    AuthUsernameField(
                        value = uiState.username,
                        placeholder = "邮箱/用户名",
                        onValueChange = { onEvent(LoginEvent.UsernameChanged(it)) },
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                        layout = layout
                    )
                    AuthPasswordField(
                        value = uiState.password,
                        placeholder = "密码",
                        visible = uiState.passwordVisible,
                        onValueChange = { onEvent(LoginEvent.PasswordChanged(it)) },
                        onToggleVisibility = { onEvent(LoginEvent.TogglePasswordVisibility) },
                        imeAction = ImeAction.Done,
                        onImeAction = { onEvent(LoginEvent.LoginClicked) },
                        layout = layout
                    )
                    AnimatedVisibility(
                        visible = uiState.errorMessage != null,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.rememberUsername,
                            onCheckedChange = { onEvent(LoginEvent.ToggleRememberUsername) },
                            modifier = Modifier.size(40.dp),
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = "记住我",
                            modifier = Modifier.clickable { onEvent(LoginEvent.ToggleRememberUsername) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = onNavigateToForgotPassword
                        ) {
                            Text("忘记密码", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Button(
                        onClick = { onEvent(LoginEvent.LoginClicked) },
                        modifier = Modifier.fillMaxWidth().height(layout.buttonHeight),
                        enabled = !uiState.isLoading,
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
                            Text(
                                "登录",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    if (uiState.authProviders.isNotEmpty()) {
                        SocialLoginSection(
                            providers = uiState.authProviders,
                            onProviderClick = { launchSocialLogin(context, it) },
                            layout = layout
                        )
                    }
                }
            }
            TextButton(onClick = onContinueAsGuest) {
                Text("游客登录")
            }
            AuthAgreement(prefix = "登录", layout = layout)
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

@Composable
internal fun AuthBackground(layout: AuthLayoutSpec) {
    Image(
        painter = painterResource(R.drawable.auth_bamboo_background),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().height(layout.backgroundHeight),
        contentScale = if (layout.compact) ContentScale.FillWidth else ContentScale.Crop,
        alignment = Alignment.TopCenter
    )
}

@Composable
internal fun AuthPageHeader(layout: AuthLayoutSpec) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(layout.headerTop))
        AppLauncherIcon(
            modifier = Modifier.size(layout.iconSize),
            contentDescription = "智悟本应用图标"
        )
        Spacer(Modifier.height(layout.iconTitleSpacing))
        Text(
            text = "智悟本",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = AuthHeaderTitle
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HorizontalDivider(modifier = Modifier.width(22.dp), color = MaterialTheme.colorScheme.primary)
            Text(
                text = "ZHI WU BEN",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider(modifier = Modifier.width(22.dp), color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "我的成长记录",
            modifier = Modifier.padding(horizontal = 30.dp),
            color = AuthHeaderSubtitle,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun AuthTabs(
    selectedLogin: Boolean,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    layout: AuthLayoutSpec
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        AuthTab("登录", selectedLogin, onLogin, Modifier.weight(1f), layout)
        AuthTab("注册", !selectedLogin, onRegister, Modifier.weight(1f), layout)
    }
}

@Composable
private fun AuthTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    layout: AuthLayoutSpec
) {
    Column(
        modifier = modifier.clickable(onClick = onClick).height(layout.tabHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(7.dp))
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(3.dp)
                .background(
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(2.dp)
                )
        )
    }
}

@Composable
internal fun AuthModeSelector(
    mode: AuthEntryMode,
    onModeChange: (AuthEntryMode) -> Unit,
    modes: List<AuthEntryMode> = listOf(
        AuthEntryMode.PHONE,
        AuthEntryMode.EMAIL,
        AuthEntryMode.PASSWORD
    )
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            modes.forEach { item ->
                val label = when (item) {
                    AuthEntryMode.PHONE -> "手机号"
                    AuthEntryMode.EMAIL -> "邮箱"
                    AuthEntryMode.PASSWORD -> "密码"
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clickable { onModeChange(item) },
                    shape = RoundedCornerShape(6.dp),
                    color = if (mode == item) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        Color.Transparent
                    },
                    shadowElevation = if (mode == item) 1.dp else 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (mode == item) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AuthIdentifierField(
    value: String,
    mode: AuthEntryMode,
    onValueChange: (String) -> Unit,
    onNext: () -> Unit,
    layout: AuthLayoutSpec
) {
    val isPhone = mode == AuthEntryMode.PHONE
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().height(layout.fieldHeight),
        placeholder = { Text(if (isPhone) "请输入手机号" else "请输入邮箱") },
        leadingIcon = {
            Icon(
                if (isPhone) Icons.Default.PhoneAndroid else Icons.Default.Email,
                contentDescription = null
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(15.dp),
        colors = authFieldColors(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPhone) KeyboardType.Phone else KeyboardType.Email,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(onNext = { onNext() })
    )
}

@Composable
internal fun AuthVerificationCodeField(
    value: String,
    isSending: Boolean,
    cooldownSeconds: Int,
    onValueChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onDone: () -> Unit,
    layout: AuthLayoutSpec
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().height(layout.fieldHeight),
        placeholder = { Text("6 位验证码") },
        leadingIcon = { Icon(Icons.Default.Sms, contentDescription = null) },
        trailingIcon = {
            TextButton(
                onClick = onSendCode,
                enabled = !isSending && cooldownSeconds == 0
            ) {
                Text(
                    when {
                        isSending -> "发送中"
                        cooldownSeconds > 0 -> "${cooldownSeconds}s"
                        else -> "获取验证码"
                    },
                    maxLines = 1
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(15.dp),
        colors = authFieldColors(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() })
    )
}

@Composable
internal fun AuthUsernameField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onNext: () -> Unit,
    supportingText: String? = null,
    layout: AuthLayoutSpec
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(layout.fieldHeight),
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            colors = authFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() })
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun AuthPasswordField(
    value: String,
    placeholder: String,
    visible: Boolean,
    onValueChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    supportingText: String? = null,
    layout: AuthLayoutSpec
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(layout.fieldHeight),
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            colors = authFieldColors(),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (visible) "隐藏密码" else "显示密码"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onNext = { onImeAction() },
                onDone = { onImeAction() }
            )
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
    focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
    unfocusedLeadingIconColor = MaterialTheme.colorScheme.outline,
    focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
    unfocusedTrailingIconColor = MaterialTheme.colorScheme.outline
)

private val SocialPopupGap = 8.dp
private val SocialPopupCornerRadius = 18.dp
private const val SOCIAL_POPUP_COLUMNS = 3

/**
 * The entry row keeps a fixed height and the provider list opens in a [Popup] overlay, so the
 * auth page never grows or starts scrolling when the section is expanded.
 */
@Composable
internal fun SocialLoginSection(
    providers: List<SocialAuthProvider>,
    onProviderClick: (SocialAuthProvider) -> Unit,
    layout: AuthLayoutSpec
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "social-login-chevron"
    )
    val availableCount = providers.count { it.enabled }
    Box(modifier = Modifier.fillMaxWidth().height(layout.socialRowHeight)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { anchorWidth = with(density) { it.width.toDp() } }
                .clickable { expanded = !expanded },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "其他登录方式",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = if (availableCount > 0) "${availableCount} 项可用" else "平台接入中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起其他登录方式" else "展开其他登录方式",
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(20.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (expanded) {
            SocialProviderPopup(
                providers = providers,
                iconSize = layout.providerIconSize,
                width = anchorWidth,
                onDismiss = { expanded = false },
                onProviderClick = { provider ->
                    // Unavailable platforms only surface a toast, so keep the overlay open there.
                    if (provider.enabled) expanded = false
                    onProviderClick(provider)
                }
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SocialProviderPopup(
    providers: List<SocialAuthProvider>,
    iconSize: Dp,
    width: Dp,
    onDismiss: () -> Unit,
    onProviderClick: (SocialAuthProvider) -> Unit
) {
    val gapPx = with(LocalDensity.current) { SocialPopupGap.roundToPx() }
    val positionProvider = remember(gapPx) { SocialProviderPopupPositionProvider(gapPx) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = if (width > 0.dp) Modifier.width(width) else Modifier,
            shape = RoundedCornerShape(SocialPopupCornerRadius),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "选择其他登录方式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = SOCIAL_POPUP_COLUMNS,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    providers.forEach { provider ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            SocialProviderButton(
                                provider = provider,
                                iconSize = iconSize,
                                onClick = { onProviderClick(provider) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Places the overlay right below the anchor row, flipping above it when the bottom of the window
 * cannot fit the content, and always keeping it inside the window horizontally.
 */
internal class SocialProviderPopupPositionProvider(
    private val verticalGapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val below = anchorBounds.bottom + verticalGapPx
        val above = anchorBounds.top - verticalGapPx - popupContentSize.height
        val y = when {
            below + popupContentSize.height <= windowSize.height -> below
            above >= 0 -> above
            else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x = anchorBounds.left.coerceIn(0, maxX), y = y)
    }
}

@Composable
private fun SocialProviderButton(provider: SocialAuthProvider, iconSize: Dp, onClick: () -> Unit) {
    val iconResource = when (provider.id) {
        "wechat" -> R.drawable.ic_wechat_official
        "qq" -> R.drawable.ic_social_qq
        "feishu" -> R.drawable.ic_social_feishu
        "telegram" -> R.drawable.ic_social_telegram
        "whatsapp" -> R.drawable.ic_social_whatsapp
        "instagram" -> R.drawable.ic_social_instagram
        else -> R.drawable.ic_wechat_official
    }
    Column(
        modifier = Modifier.alpha(if (provider.enabled) 1f else 0.42f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(iconSize)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(iconResource),
                    contentDescription = provider.name,
                    modifier = Modifier.size(iconSize * 0.64f),
                    contentScale = ContentScale.Fit
                )
            }
        }
        Text(
            text = provider.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun AuthAgreement(prefix: String, layout: AuthLayoutSpec) {
    Text(
        text = buildAnnotatedString {
            append(prefix)
            append("即表示您同意 ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) {
                append("《用户协议》")
            }
            append(" 和 ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) {
                append("《隐私政策》")
            }
        },
        modifier = Modifier.padding(horizontal = 28.dp, vertical = layout.agreementVerticalPadding),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
internal fun AuthBenefits(layout: AuthLayoutSpec) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = FirebaseUiTokens.AuthContentMaxWidth)
            .padding(horizontal = 26.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = if (layout.compact) 8.dp else 12.dp,
                vertical = layout.benefitsVerticalPadding
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AuthBenefit(Icons.Default.VerifiedUser, "数据安全", "多重加密保护", Modifier.weight(1f))
            VerticalDivider(modifier = Modifier.height(42.dp), color = MaterialTheme.colorScheme.outlineVariant)
            AuthBenefit(Icons.Default.CloudDone, "云端同步", "随时随地访问", Modifier.weight(1f))
            VerticalDivider(modifier = Modifier.height(42.dp), color = MaterialTheme.colorScheme.outlineVariant)
            AuthBenefit(Icons.Default.Lightbulb, "灵感记录", "捕捉每个瞬间", Modifier.weight(1f))
        }
    }
}

@Composable
private fun AuthBenefit(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(6.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column {
            Text(
                text = title,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = subtitle,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

internal fun launchSocialLogin(
    context: Context,
    provider: SocialAuthProvider,
    referralCode: String = ""
) {
    if (!provider.enabled || provider.authorizationUrl.isBlank()) {
        val reason = provider.unavailableReason.ifBlank { "${provider.name}登录暂未开放" }
        Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        val target = Uri.parse(provider.authorizationUrl).buildUpon()
            .appendQueryParameter("client", "android")
            .appendQueryParameter("redirect_uri", BuildConfig.SOCIAL_AUTH_CALLBACK_URI)
            .apply {
                referralCode.trim().takeIf { it.isNotBlank() }?.let {
                    appendQueryParameter("ref", it.uppercase())
                }
            }
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, target))
    }.onFailure {
        Toast.makeText(context, "无法打开${provider.name}登录", Toast.LENGTH_SHORT).show()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun LoginScreenPreview() {
    OAAutomationTheme(darkTheme = false) {
        LoginScreen({}, {}, {}, {}, {})
    }
}
