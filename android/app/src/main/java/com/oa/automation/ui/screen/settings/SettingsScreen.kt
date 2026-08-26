package com.oa.automation.ui.screen.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.oa.automation.BuildConfig
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.oa.automation.domain.model.CloudApiFormat
import com.oa.automation.domain.model.AppThemeMode
import com.oa.automation.domain.model.AgentProvider
import com.oa.automation.domain.model.ClaudeReasoningEffort
import com.oa.automation.domain.model.CodexReasoningEffort
import com.oa.automation.domain.model.DiscoveredSTTServer
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.TencentAsrBudgetPolicy
import com.oa.automation.domain.model.TencentAsrTier
import com.oa.automation.domain.model.TencentAsrTierPolicy
import com.oa.automation.ui.theme.LocalAppIsDarkTheme

private data class SettingsPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceStrong: Color,
    val text: Color,
    val mutedText: Color,
    val purple: Color,
    val blue: Color,
    val cyan: Color,
    val pink: Color,
    val error: Color,
    val outline: Color
)

private val DarkSettingsPalette = SettingsPalette(
    isDark = true,
    background = Color(0xFF1B1A19),
    surface = Color(0xE8252423),
    surfaceStrong = Color(0xF2323130),
    text = Color(0xFFF3F2F1),
    mutedText = Color(0xFFC8C6C4),
    purple = Color(0xFF60CDFF),
    blue = Color(0xFF0078D4),
    cyan = Color(0xFF8CC8FF),
    pink = Color(0xFF3A96DD),
    error = Color(0xFFFF7B8A),
    outline = Color(0xFF605E5C)
)

private val LightSettingsPalette = SettingsPalette(
    isDark = false,
    background = Color(0xFFF5F5F5),
    surface = Color(0xF8FFFFFF),
    surfaceStrong = Color(0xFFFFFFFF),
    text = Color(0xFF242424),
    mutedText = Color(0xFF605E5C),
    purple = Color(0xFF0078D4),
    blue = Color(0xFF0067B8),
    cyan = Color(0xFF2B88B9),
    pink = Color(0xFF4F8FB7),
    error = Color(0xFFBA1A1A),
    outline = Color(0xFFD2D0CE)
)

private val LocalSettingsPalette = staticCompositionLocalOf { DarkSettingsPalette }
private val SettingsSurface: Color @Composable get() = LocalSettingsPalette.current.surface
private val SettingsSurfaceStrong: Color @Composable get() = LocalSettingsPalette.current.surfaceStrong
private val SettingsText: Color @Composable get() = LocalSettingsPalette.current.text
private val SettingsMutedText: Color @Composable get() = LocalSettingsPalette.current.mutedText
private val SettingsPurple: Color @Composable get() = LocalSettingsPalette.current.purple
private val SettingsBlue: Color @Composable get() = LocalSettingsPalette.current.blue
private val SettingsCyan: Color @Composable get() = LocalSettingsPalette.current.cyan
private val SettingsPink: Color @Composable get() = LocalSettingsPalette.current.pink
private val SettingsError: Color @Composable get() = LocalSettingsPalette.current.error
private val SettingsGlassShape = RoundedCornerShape(24.dp)

private fun settingsColorScheme(palette: SettingsPalette): ColorScheme = if (palette.isDark) {
    darkColorScheme(
        primary = palette.blue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF004578),
        onPrimaryContainer = Color(0xFFC7E9FF),
        secondary = palette.cyan,
        onSecondary = Color(0xFF001F23),
        secondaryContainer = Color(0xFF0F4160),
        onSecondaryContainer = Color(0xFFC7E9FF),
        tertiary = palette.pink,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFF334E68),
        onTertiaryContainer = Color(0xFFD7E9FA),
        error = palette.error,
        onError = Color(0xFF3B0710),
        errorContainer = Color(0xFF5A1C29),
        onErrorContainer = Color(0xFFFFD9DE),
        background = palette.background,
        onBackground = palette.text,
        surface = palette.surfaceStrong,
        onSurface = palette.text,
        surfaceVariant = Color(0xFF323130),
        onSurfaceVariant = palette.mutedText,
        outline = Color(0xFF8A8886),
        outlineVariant = palette.outline
    )
} else {
    lightColorScheme(
        primary = palette.blue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE5F1FB),
        onPrimaryContainer = Color(0xFF00395D),
        secondary = palette.cyan,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDDEBF7),
        onSecondaryContainer = Color(0xFF00395D),
        tertiary = palette.pink,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE1ECF5),
        onTertiaryContainer = Color(0xFF19344D),
        error = palette.error,
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = palette.background,
        onBackground = palette.text,
        surface = palette.surfaceStrong,
        onSurface = palette.text,
        surfaceVariant = Color(0xFFF0F0F0),
        onSurfaceVariant = palette.mutedText,
        outline = Color(0xFF8A8886),
        outlineVariant = palette.outline
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val systemIsDark = isSystemInDarkTheme()
    val settingsPalette = if (uiState.themeMode.usesDarkColors(systemIsDark)) {
        DarkSettingsPalette
    } else {
        LightSettingsPalette
    }
    val view = LocalView.current
    val activity = view.context as? Activity
    val appIsDark = LocalAppIsDarkTheme.current
    val appBackground = MaterialTheme.colorScheme.background

    DisposableEffect(activity, appIsDark, appBackground) {
        onDispose {
            activity?.window?.let { window ->
                window.statusBarColor = appBackground.toArgb()
                window.navigationBarColor = appBackground.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !appIsDark
                    isAppearanceLightNavigationBars = !appIsDark
                }
            }
        }
    }
    SideEffect {
        activity?.window?.let { window ->
            window.statusBarColor = settingsPalette.background.toArgb()
            window.navigationBarColor = settingsPalette.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !settingsPalette.isDark
                isAppearanceLightNavigationBars = !settingsPalette.isDark
            }
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            kotlinx.coroutines.delay(3000)
            viewModel.clearMessage()
        }
    }

    CompositionLocalProvider(LocalSettingsPalette provides settingsPalette) {
        MaterialTheme(colorScheme = settingsColorScheme(settingsPalette)) {
        val palette = LocalSettingsPalette.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = if (palette.isDark) {
                                listOf(Color(0xFF163A5A), palette.background, Color(0xFF102A43))
                            } else {
                                listOf(Color(0xFFE6F2FC), palette.background, Color(0xFFEAF2F8))
                            },
                            start = Offset.Zero,
                            end = Offset(size.width, size.height)
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(palette.blue.copy(alpha = 0.14f), Color.Transparent),
                            center = Offset(0f, size.height * 0.08f),
                            radius = size.width * 0.95f
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(palette.cyan.copy(alpha = 0.10f), Color.Transparent),
                            center = Offset(size.width, size.height * 0.30f),
                            radius = size.width * 0.90f
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(palette.blue.copy(alpha = 0.08f), Color.Transparent),
                            center = Offset(size.width * 0.85f, size.height),
                            radius = size.width
                        )
                    )
                }
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = "设置",
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold,
                                color = SettingsText
                            )
                        },
                        navigationIcon = {
                            Surface(
                                onClick = onNavigateBack,
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .size(44.dp),
                                shape = CircleShape,
                                color = SettingsSurfaceStrong.copy(alpha = 0.78f),
                                border = BorderStroke(
                                    1.dp,
                                    if (settingsPalette.isDark) Color.White.copy(alpha = 0.18f)
                                    else settingsPalette.outline
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回",
                                        tint = SettingsText,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent
                        )
                    )
                },
                snackbarHost = {
                    uiState.message?.let { message ->
                        Snackbar(
                            modifier = Modifier.padding(16.dp),
                            shape = RoundedCornerShape(12.dp),
                            containerColor = if (message.contains("成功")) {
                                Color(0xFF0F4160)
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            },
                            contentColor = SettingsText
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (message.contains("成功")) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (message.contains("成功")) SettingsCyan else SettingsError
                                )
                                Text(message)
                            }
                        }
                    }
                }
            ) { paddingValues ->
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SettingsCyan)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        AppearanceAndUpdateSection(
                            themeMode = uiState.themeMode,
                            floatingBallEnabled = uiState.floatingBallEnabled,
                            isCheckingUpdate = uiState.isCheckingUpdate,
                            isDownloadingUpdate = uiState.isDownloadingUpdate,
                            updateProgress = uiState.updateProgress,
                            availableVersion = uiState.availableUpdate?.versionName,
                            releaseNotes = uiState.availableUpdate?.releaseNotes.orEmpty(),
                            onThemeModeChange = viewModel::updateThemeMode,
                            onFloatingBallChange = viewModel::updateFloatingBallEnabled,
                            onCheckUpdate = viewModel::checkForAppUpdate,
                            onDownloadUpdate = viewModel::downloadAndInstallUpdate
                        )

                        STTConfigSection(
                            config = uiState.appConfig.sttConfig,
                            isTesting = uiState.isTestingSTT,
                            isScanning = uiState.isScanningSTT,
                            isSwitching = uiState.isSwitchingSTT,
                            isLoadingTencentAsrPolicy = uiState.isLoadingTencentAsrPolicy,
                            tencentAsrPolicy = uiState.tencentAsrPolicy,
                            tencentAsrPolicyError = uiState.tencentAsrPolicyError,
                            discoveredServers = uiState.discoveredServers,
                            onEngineTypeChange = viewModel::updateSTTEngineType,
                            onLocalEndpointChange = viewModel::updateSTTLocalEndpoint,
                            onLocalModelChange = viewModel::updateSTTLocalModel,
                            onAudioEnhancementChange = viewModel::updateSTTAudioEnhancement,
                            onSpeakerDiarizationChange = viewModel::updateSTTSpeakerDiarization,
                            onApiTokenChange = viewModel::updateSTTApiToken,
                            onTencentTierChange = viewModel::updateTencentAsrTier,
                            onTestConnection = viewModel::testSTTConnection,
                            onScanServers = viewModel::scanSTTServers,
                            onRefreshTencentAsrPolicy = viewModel::refreshTencentAsrStatus,
                            onApplyServer = viewModel::applyDiscoveredServer,
                            onClearServers = viewModel::clearDiscoveredServers
                        )

                        LLMConfigSection(
                            config = uiState.appConfig.llmConfig,
                            isTesting = uiState.isTestingLLM,
                            onEngineTypeChange = viewModel::updateLLMEngineType,
                            onAgentEndpointChange = viewModel::updateAgentEndpoint,
                            onAgentAccessTokenChange = viewModel::updateAgentAccessToken,
                            onAgentProviderChange = viewModel::updateAgentProvider,
                            onCodexReasoningEffortChange = viewModel::updateCodexReasoningEffort,
                            onClaudeReasoningEffortChange = viewModel::updateClaudeReasoningEffort,
                            onLocalEndpointChange = viewModel::updateLLMLocalEndpoint,
                            onLocalModelChange = viewModel::updateLLMLocalModel,
                            onCloudEndpointChange = viewModel::updateLLMCloudEndpoint,
                            onCloudApiKeyChange = viewModel::updateLLMCloudApiKey,
                            onCloudModelChange = viewModel::updateLLMCloudModel,
                            onCloudApiFormatChange = viewModel::updateLLMCloudApiFormat,
                            onTestConnection = viewModel::testLLMConnection
                        )

                        if (BuildConfig.DEBUG) {
                            DevelopmentTestDataSection(
                                isUpdating = uiState.isUpdatingDemoData,
                                onSeed = viewModel::seedDemoData,
                                onClear = viewModel::clearDemoData
                            )
                        }

                        ResetSettingsCard(onClick = viewModel::resetToDefault)
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
}

@Composable
private fun AppearanceAndUpdateSection(
    themeMode: AppThemeMode,
    floatingBallEnabled: Boolean,
    isCheckingUpdate: Boolean,
    isDownloadingUpdate: Boolean,
    updateProgress: Int?,
    availableVersion: String?,
    releaseNotes: String,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onFloatingBallChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var overlayGranted by remember(context) { mutableStateOf(Settings.canDrawOverlays(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val palette = LocalSettingsPalette.current
    GlassSurface(modifier = Modifier.fillMaxWidth(), accent = SettingsCyan) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(
                title = "显示与更新",
                icon = Icons.Default.LightMode,
                description = null,
                accentStart = SettingsPurple,
                accentEnd = SettingsPink
            )
            Text(
                "外观模式",
                style = MaterialTheme.typography.titleMedium,
                color = SettingsText,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ThemeModeButton(
                    text = "自动",
                    icon = Icons.Default.BrightnessAuto,
                    selected = themeMode == AppThemeMode.SYSTEM,
                    modifier = Modifier.weight(1f)
                ) {
                    onThemeModeChange(AppThemeMode.SYSTEM)
                }
                ThemeModeButton(
                    text = "浅色",
                    icon = Icons.Default.LightMode,
                    selected = themeMode == AppThemeMode.LIGHT,
                    modifier = Modifier.weight(1f)
                ) {
                    onThemeModeChange(AppThemeMode.LIGHT)
                }
                ThemeModeButton(
                    text = "深色",
                    icon = Icons.Default.DarkMode,
                    selected = themeMode == AppThemeMode.DARK,
                    modifier = Modifier.weight(1f)
                ) {
                    onThemeModeChange(AppThemeMode.DARK)
                }
            }
            SettingsDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "悬浮球",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SettingsText
                    )
                    Text(
                        text = if (overlayGranted) "录音进入后台时显示悬浮球，点击返回会议" else "请允许智悟本显示在其他应用上层",
                        style = MaterialTheme.typography.bodySmall,
                        color = SettingsMutedText
                    )
                }
                Switch(
                    checked = floatingBallEnabled && overlayGranted,
                    onCheckedChange = { enabled ->
                        if (enabled && !overlayGranted) {
                            // Preserve the user's intent while the system permission screen is open.
                            onFloatingBallChange(true)
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } else {
                            onFloatingBallChange(enabled)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = SettingsPurple,
                        uncheckedThumbColor = if (palette.isDark) SettingsMutedText else Color(0xFF7A849B),
                        uncheckedTrackColor = if (palette.isDark) Color(0xFF343B52) else Color(0xFFD8DEEB),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
            SettingsDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "当前版本",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SettingsText
                    )
                    Text(
                        availableVersion?.let { "发现新版本 $it" }
                            ?: "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SettingsMutedText
                    )
                }
                Button(
                    onClick = if (availableVersion == null) onCheckUpdate else onDownloadUpdate,
                    enabled = !isCheckingUpdate && !isDownloadingUpdate,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (palette.isDark) Color(0xFF25304A) else Color.White,
                        contentColor = if (palette.isDark) SettingsText else SettingsPurple,
                        disabledContainerColor = if (palette.isDark) Color(0xFF20283D) else Color(0xFFE5E8F0),
                        disabledContentColor = SettingsMutedText
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp)
                ) {
                    if (isCheckingUpdate || isDownloadingUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(
                            imageVector = if (availableVersion != null) Icons.Default.Download else Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        when {
                            isCheckingUpdate -> "检查中"
                            isDownloadingUpdate -> "下载 ${updateProgress ?: 0}%"
                            availableVersion != null -> "更新"
                            else -> "检查更新"
                        }
                    )
                }
            }
            if (releaseNotes.isNotBlank()) {
                Text(
                    "更新说明：$releaseNotes",
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsMutedText
                )
            }
        }
    }
}

@Composable
private fun ThemeModeButton(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val palette = LocalSettingsPalette.current
    Box(
        modifier = modifier
            .height(112.dp)
            .clip(shape)
            .background(
                if (selected) {
                    if (palette.isDark) {
                        Brush.linearGradient(listOf(Color(0xFF164566), Color(0xFF0F2A40)))
                    } else {
                        Brush.linearGradient(listOf(Color(0xFFDCEFFA), Color(0xFFEDF5FA)))
                    }
                } else {
                    if (palette.isDark) {
                        Brush.verticalGradient(listOf(Color(0xFF1D2437), Color(0xFF151B2B)))
                    } else {
                        Brush.verticalGradient(listOf(Color.White, Color(0xFFF0F3FA)))
                    }
                }
            )
            .border(
                BorderStroke(
                    1.5.dp,
                    if (selected) Brush.linearGradient(listOf(SettingsBlue, SettingsCyan))
                    else if (palette.isDark) {
                        Brush.linearGradient(listOf(Color(0xFF59647F), Color(0xFF31394F)))
                    } else {
                        Brush.linearGradient(listOf(Color(0xFFB9C3DD), Color(0xFFDCE2F0)))
                    }
                ),
                shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        when {
                            selected -> if (palette.isDark) {
                                Brush.linearGradient(listOf(Color(0xFFD6F0FF), Color(0xFF7ABFE4)))
                            } else {
                                Brush.linearGradient(listOf(Color.White, Color(0xFFD9EEF8)))
                            }
                            text == "浅色" -> Brush.linearGradient(listOf(Color(0xFFF4F5FA), Color(0xFFE0E5F1)))
                            else -> if (palette.isDark) {
                                Brush.linearGradient(listOf(Color(0xFF303747), Color(0xFF171D2C)))
                            } else {
                                Brush.linearGradient(listOf(Color(0xFFE9ECF5), Color(0xFFDDE3F0)))
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (text == "浅色") Color(0xFF6C7690)
                    else if (selected) Color(0xFF656E88)
                    else if (palette.isDark) Color(0xFFC6CAD7) else Color(0xFF65718C),
                    modifier = Modifier.size(29.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = SettingsText
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    description: String? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    accentStart: Color,
    accentEnd: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(accentStart, accentEnd))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(29.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = SettingsText
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsMutedText
                )
            }
        }
        if (expanded != null) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = Color(0xFFD3D7E6),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun GlassSurface(
    modifier: Modifier = Modifier,
    accent: Color,
    content: @Composable () -> Unit
) {
    val palette = LocalSettingsPalette.current
    val shape = SettingsGlassShape
    Surface(
        modifier = modifier.border(
            BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.82f),
                        if (palette.isDark) Color.White.copy(alpha = 0.22f)
                        else palette.outline.copy(alpha = 0.70f),
                        SettingsCyan.copy(alpha = 0.72f)
                    )
                )
            ),
            shape
        ),
        shape = shape,
        color = SettingsSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content
    )
}

@Composable
private fun SettingsDivider() {
    val palette = LocalSettingsPalette.current
    HorizontalDivider(
        color = if (palette.isDark) Color.White.copy(alpha = 0.16f) else palette.outline.copy(alpha = 0.72f),
        thickness = 1.dp
    )
}

@Composable
private fun SettingsSummaryRow(
    label: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalSettingsPalette.current
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (palette.isDark) Color(0xFF202A42).copy(alpha = 0.68f) else Color(0xFFF0F3FA),
        border = BorderStroke(1.dp, if (palette.isDark) Color.White.copy(alpha = 0.12f) else Color(0xFFD5DCEB))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = SettingsMutedText)
            Spacer(Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = SettingsText,
                maxLines = 1
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = Color(0xFFD3D7E6),
                modifier = Modifier.padding(start = 8.dp).size(24.dp)
            )
        }
    }
}

@Composable
private fun DevelopmentTestDataSection(
    isUpdating: Boolean,
    onSeed: () -> Unit,
    onClear: () -> Unit
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), accent = SettingsPink) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeader(
                title = "开发测试数据",
                icon = Icons.Default.Science,
                description = "仅 debug 构建可用；显式注入、稳定 ID、可重复且可清理。",
                accentStart = SettingsPink,
                accentEnd = SettingsCyan
            )
            Button(
                onClick = onSeed,
                enabled = !isUpdating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isUpdating) "处理中…" else "注入演示数据")
            }
            OutlinedButton(
                onClick = onClear,
                enabled = !isUpdating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清理演示数据")
            }
        }
    }
}

@Composable
private fun ResetSettingsCard(onClick: () -> Unit) {
    val palette = LocalSettingsPalette.current
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        if (palette.isDark) {
                            listOf(SettingsError.copy(alpha = 0.9f), Color(0xFFFFB3B9).copy(alpha = 0.7f))
                        } else {
                            listOf(SettingsError.copy(alpha = 0.8f), Color(0xFFFFA7AF).copy(alpha = 0.7f))
                        }
                    )
                ),
                SettingsGlassShape
            ),
        shape = SettingsGlassShape,
        color = if (palette.isDark) Color(0xFF241A31).copy(alpha = 0.9f) else Color(0xFFFFF1F3)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 21.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(
                        if (palette.isDark) Color(0xFF5B304A).copy(alpha = 0.58f)
                        else Color(0xFFFFDDE3)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, tint = SettingsError, modifier = Modifier.size(31.dp))
            }
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("恢复默认设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SettingsError)
                Spacer(Modifier.height(4.dp))
                Text("清除所有个性化设置，恢复至初始状态", style = MaterialTheme.typography.bodyMedium, color = SettingsMutedText)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color(0xFFD3D7E6), modifier = Modifier.size(28.dp))
        }
    }
}

// ──────────────────────────────────────────────
// STT Config Section
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun STTConfigSection(
    config: com.oa.automation.domain.model.STTConfig,
    isTesting: Boolean,
    isScanning: Boolean,
    isSwitching: Boolean,
    isLoadingTencentAsrPolicy: Boolean,
    tencentAsrPolicy: TencentAsrBudgetPolicy?,
    tencentAsrPolicyError: String?,
    discoveredServers: List<DiscoveredSTTServer>,
    onEngineTypeChange: (STTEngineType) -> Unit,
    onLocalEndpointChange: (String) -> Unit,
    onLocalModelChange: (String) -> Unit,
    onAudioEnhancementChange: (Boolean) -> Unit,
    onSpeakerDiarizationChange: (Boolean) -> Unit,
    onApiTokenChange: (String?) -> Unit,
    onTencentTierChange: (TencentAsrTier) -> Unit,
    onTestConnection: () -> Unit,
    onScanServers: () -> Unit,
    onRefreshTencentAsrPolicy: () -> Unit,
    onApplyServer: (DiscoveredSTTServer) -> Unit,
    onClearServers: () -> Unit
) {
    var engineExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var showServerList by remember { mutableStateOf(false) }
    var cardExpanded by remember { mutableStateOf(false) }

    val modelOptions = when (config.engineType) {
        STTEngineType.FASTER_WHISPER -> listOf("large-v3-turbo", "large-v3", "medium", "small", "base", "tiny")
        STTEngineType.TENCENT_HYBRID -> emptyList()
    }

    var localEndpoint by remember(config.localEndpoint) { mutableStateOf(config.localEndpoint) }
    var localModel by remember(config.localModel) { mutableStateOf(config.localModel) }
    var apiToken by remember(config.apiToken) { mutableStateOf(config.apiToken ?: "") }
    LaunchedEffect(discoveredServers) {
        showServerList = discoveredServers.isNotEmpty()
    }

    GlassSurface(modifier = Modifier.fillMaxWidth(), accent = SettingsBlue) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeader(
                title = "语音转文本 (STT)",
                icon = Icons.Default.Mic,
                description = "配置语音识别服务",
                expanded = cardExpanded,
                onToggle = { cardExpanded = !cardExpanded },
                accentStart = SettingsBlue,
                accentEnd = SettingsCyan
            )
            SettingsSummaryRow(
                label = "当前引擎",
                value = config.engineType.displayName,
                expanded = cardExpanded,
                onClick = { cardExpanded = !cardExpanded }
            )

            AnimatedVisibility(
                visible = cardExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsDivider()

                    ListItem(
                        headlineContent = {
                            Text(
                                text = "智能语音增强",
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        supportingContent = {
                            Text("录音时自动抑制环境噪声并优化人声")
                        },
                        leadingContent = {
                            Icon(Icons.Default.GraphicEq, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = config.audioEnhancementEnabled,
                                onCheckedChange = onAudioEnhancementChange
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    ListItem(
                        headlineContent = {
                            Text(
                                text = "说话人分离",
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        supportingContent = {
                            Text("最终稿按说话人分段显示")
                        },
                        leadingContent = {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = config.speakerDiarizationEnabled,
                                onCheckedChange = onSpeakerDiarizationChange
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    // Engine Type Selection
                    EngineTypeDropdown(
                        currentType = config.engineType,
                        expanded = engineExpanded,
                        enabled = !isSwitching,
                        onExpandedChange = { engineExpanded = it },
                        onSelect = onEngineTypeChange
                    )

                    // Switching progress
                    AnimatedVisibility(visible = isSwitching) {
                        SwitchingIndicator()
                    }

                    if (config.engineType == STTEngineType.TENCENT_HYBRID) {
                        TencentAsrTierSelector(
                            selectedTier = config.tencentAsrTier,
                            policy = tencentAsrPolicy,
                            enabled = !isSwitching,
                            onSelect = onTencentTierChange
                        )
                        TencentAsrServiceStatusPanel(
                            policy = tencentAsrPolicy,
                            isLoading = isLoadingTencentAsrPolicy,
                            error = tencentAsrPolicyError,
                            onRefresh = onRefreshTencentAsrPolicy
                        )
                    } else {
                        if (modelOptions.isNotEmpty() && localModel !in modelOptions) {
                            localModel = modelOptions.first()
                            onLocalModelChange(localModel)
                        }

                        // Endpoint Card
                        EndpointCard(
                            localEndpoint = localEndpoint,
                            onEndpointChange = {
                                localEndpoint = it
                                onLocalEndpointChange(it)
                            },
                            isScanning = isScanning,
                            onScanServers = onScanServers
                        )

                        OutlinedTextField(
                            value = apiToken,
                            onValueChange = {
                                apiToken = it
                                onApiTokenChange(it.ifBlank { null })
                            },
                            label = { Text("服务访问令牌") },
                            leadingIcon = {
                                Icon(Icons.Default.Key, contentDescription = null)
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        // Discovered Servers
                        AnimatedVisibility(
                            visible = showServerList && discoveredServers.isNotEmpty(),
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            DiscoveredServersCard(
                                servers = discoveredServers,
                                onApply = { server ->
                                    onApplyServer(server)
                                    showServerList = false
                                },
                                onDismiss = {
                                    showServerList = false
                                    onClearServers()
                                }
                            )
                        }

                        // Model Selection
                        ModelDropdown(
                            currentModel = localModel,
                            options = modelOptions,
                            expanded = modelExpanded,
                            onExpandedChange = { modelExpanded = it },
                            onSelect = { option ->
                                localModel = option
                                onLocalModelChange(option)
                            }
                        )
                        TestConnectionButton(
                            isTesting = isTesting,
                            onClick = onTestConnection
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TencentAsrTierSelector(
    selectedTier: TencentAsrTier,
    policy: TencentAsrBudgetPolicy?,
    enabled: Boolean,
    onSelect: (TencentAsrTier) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "智悟增强云模型档位",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        TencentAsrTier.entries.forEach { tier ->
            val serverTier = policy?.tierFor(tier)
            val selectable = enabled && (!tier.isPaid || serverTier?.isAvailable == true)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = selectable) { onSelect(tier) },
                shape = RoundedCornerShape(8.dp),
                color = if (selectedTier == tier) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                border = if (selectedTier == tier) {
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.secondary
                    )
                } else {
                    null
                }
            ) {
                ListItem(
                    headlineContent = { Text(tier.displayName) },
                    supportingContent = {
                        Text(
                            when {
                                tier.isPaid && serverTier?.isAvailable != true ->
                                    "服务端未授权启用，无法选择"
                                tier.isPaid -> "高精度识别服务当前可用"
                                else -> "标准普通话识别服务当前可用"
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (tier.isPaid) Icons.Default.Payments else Icons.Default.CloudDone,
                            contentDescription = null
                        )
                    },
                    trailingContent = {
                        RadioButton(
                            selected = selectedTier == tier,
                            onClick = if (selectable) ({ onSelect(tier) }) else null
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun TencentAsrServiceStatusPanel(
    policy: TencentAsrBudgetPolicy?,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "智悟增强云模型服务状态",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isLoading) "正在查询服务可用性" else "查看实时转写与终稿识别是否可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新云模型服务状态")
                }
            }
        }

        if (policy != null) {
            policy.tiers.forEach { tier -> TencentAsrTierPolicyRow(tier) }
        } else if (!isLoading && error != null) {
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else if (!isLoading) {
            Text(
                text = "暂未获取服务状态，请点击刷新重试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "云模型档位仅表示服务可用性，不影响积分结算；所有转写统一按积分规则扣除。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TencentAsrTierPolicyRow(tier: TencentAsrTierPolicy) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tier.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (tier.isAvailable) "已启用" else "未启用",
                style = MaterialTheme.typography.labelMedium,
                color = if (tier.isAvailable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "实时转写${if (tier.realtimeEnabled) "可用" else "不可用"} · " +
                "终稿识别${if (tier.flashEnabled) "可用" else "不可用"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ──────────────────────────────────────────────
// LLM Config Section
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LLMConfigSection(
    config: com.oa.automation.domain.model.LLMConfig,
    isTesting: Boolean,
    onEngineTypeChange: (LLMEngineType) -> Unit,
    onAgentEndpointChange: (String) -> Unit,
    onAgentAccessTokenChange: (String?) -> Unit,
    onAgentProviderChange: (AgentProvider) -> Unit,
    onCodexReasoningEffortChange: (CodexReasoningEffort) -> Unit,
    onClaudeReasoningEffortChange: (ClaudeReasoningEffort) -> Unit,
    onLocalEndpointChange: (String) -> Unit,
    onLocalModelChange: (String) -> Unit,
    onCloudEndpointChange: (String?) -> Unit,
    onCloudApiKeyChange: (String?) -> Unit,
    onCloudModelChange: (String?) -> Unit,
    onCloudApiFormatChange: (CloudApiFormat) -> Unit,
    onTestConnection: () -> Unit
) {
    var engineExpanded by remember { mutableStateOf(false) }
    var apiFormatExpanded by remember { mutableStateOf(false) }
    var cardExpanded by remember { mutableStateOf(false) }

    var localEndpoint by remember(config.localEndpoint) { mutableStateOf(config.localEndpoint) }
    var localModel by remember(config.localModel) { mutableStateOf(config.localModel) }
    var agentEndpoint by remember(config.agentEndpoint) { mutableStateOf(config.agentEndpoint) }
    var agentAccessToken by remember(config.agentAccessToken) { mutableStateOf(config.agentAccessToken.orEmpty()) }
    var agentTokenDirty by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    var cloudEndpoint by remember(config.cloudEndpoint) { mutableStateOf(config.cloudEndpoint ?: "") }
    var cloudApiKey by remember(config.cloudApiKey) { mutableStateOf(config.cloudApiKey ?: "") }
    var cloudModel by remember(config.cloudModel) { mutableStateOf(config.cloudModel ?: "") }

    GlassSurface(modifier = Modifier.fillMaxWidth(), accent = SettingsPink) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeader(
                title = "大语言模型 (LLM)",
                icon = Icons.Default.SmartToy,
                description = "LLM 与 STT 配置彼此独立",
                expanded = cardExpanded,
                onToggle = { cardExpanded = !cardExpanded },
                accentStart = SettingsPink,
                accentEnd = SettingsPurple
            )
            SettingsSummaryRow(
                label = "当前模型",
                value = when (config.engineType) {
                    LLMEngineType.AGENT_GATEWAY -> "${config.agentProvider.displayName} Agent"
                    LLMEngineType.LOCAL_OLLAMA -> config.localModel.ifBlank { config.engineType.displayName }
                    LLMEngineType.CLOUD_API -> config.cloudModel?.ifBlank { null } ?: config.engineType.displayName
                },
                expanded = cardExpanded,
                onClick = { cardExpanded = !cardExpanded }
            )

            AnimatedVisibility(
                visible = cardExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsDivider()

                    // Engine Type
                    LLMEngineTypeDropdown(
                        currentType = config.engineType,
                        expanded = engineExpanded,
                        onExpandedChange = { engineExpanded = it },
                        onSelect = onEngineTypeChange
                    )

                    if (config.engineType == LLMEngineType.AGENT_GATEWAY) {
                        OutlinedTextField(
                            value = agentEndpoint,
                            onValueChange = {
                                agentEndpoint = it
                                onAgentEndpointChange(it)
                            },
                            label = { Text("Agent 服务地址") },
                            leadingIcon = {
                                Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 2,
                            shape = RoundedCornerShape(8.dp)
                        )

                        OutlinedTextField(
                            value = agentAccessToken,
                            onValueChange = {
                                agentAccessToken = it
                                agentTokenDirty = true
                            },
                            label = { Text("Agent 访问令牌") },
                            leadingIcon = {
                                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    onAgentAccessTokenChange(agentAccessToken.ifBlank { null })
                                    agentTokenDirty = false
                                    focusManager.clearFocus()
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    if (!state.isFocused && agentTokenDirty) {
                                        onAgentAccessTokenChange(agentAccessToken.ifBlank { null })
                                        agentTokenDirty = false
                                    }
                                },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )

                        AgentProviderDropdown(
                            currentProvider = config.agentProvider,
                            onSelect = onAgentProviderChange
                        )

                        when (config.agentProvider) {
                            AgentProvider.CODEX_CLI -> ReasoningEffortDropdown(
                                label = "智能体小悟推理强度",
                                current = config.codexReasoningEffort,
                                options = CodexReasoningEffort.entries,
                                displayName = CodexReasoningEffort::displayName,
                                onSelect = onCodexReasoningEffortChange
                            )
                            AgentProvider.CLAUDE_CLI -> ReasoningEffortDropdown(
                                label = "智能体小智推理强度",
                                current = config.claudeReasoningEffort,
                                options = ClaudeReasoningEffort.entries,
                                displayName = ClaudeReasoningEffort::displayName,
                                onSelect = onClaudeReasoningEffortChange
                            )
                        }
                    }

                    if (config.engineType == LLMEngineType.LOCAL_OLLAMA) {
                        OutlinedTextField(
                            value = localEndpoint,
                            onValueChange = {
                                localEndpoint = it
                                onLocalEndpointChange(it)
                            },
                            label = { Text("Ollama 服务地址") },
                            placeholder = { Text("http://localhost:11434") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )

                        OutlinedTextField(
                            value = localModel,
                            onValueChange = {
                                localModel = it
                                onLocalModelChange(it)
                            },
                            label = { Text("本地模型名称") },
                            placeholder = { Text("qwen2.5:7b / llama3:8b") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = { Text("建议使用 qwen2.5:7b / llama3:8b 等中杯模型") },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    if (config.engineType == LLMEngineType.CLOUD_API) {
                        OutlinedTextField(
                            value = cloudEndpoint,
                            onValueChange = {
                                cloudEndpoint = it
                                onCloudEndpointChange(it.ifEmpty { null })
                            },
                            label = { Text("云端 API 地址") },
                            placeholder = { Text("https://api.minimaxi.com/anthropic") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CloudQueue,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )

                        OutlinedTextField(
                            value = cloudApiKey,
                            onValueChange = {
                                cloudApiKey = it
                                onCloudApiKeyChange(it.ifEmpty { null })
                            },
                            label = { Text("云端 API Key") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )

                        OutlinedTextField(
                            value = cloudModel,
                            onValueChange = {
                                cloudModel = it
                                onCloudModelChange(it.ifEmpty { null })
                            },
                            label = { Text("云端模型名称") },
                            placeholder = { Text("Qwen/Qwen2.5-7B-Instruct") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )

                        CloudApiFormatDropdown(
                            currentFormat = config.cloudApiFormat,
                            expanded = apiFormatExpanded,
                            onExpandedChange = { apiFormatExpanded = it },
                            onSelect = onCloudApiFormatChange
                        )
                    }

                    // Test Connection
                    TestConnectionButton(
                        isTesting = isTesting,
                        onClick = onTestConnection
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Extracted sub-components
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineTypeDropdown(
    currentType: STTEngineType,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (STTEngineType) -> Unit
) {
    val engineOptions = if (BuildConfig.STT_REMOTE_SWITCH_ENABLED) {
        STTEngineType.entries
    } else {
        listOf(
            STTEngineType.FASTER_WHISPER,
            STTEngineType.TENCENT_HYBRID
        )
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) onExpandedChange(it) }
    ) {
        OutlinedTextField(
            value = currentType.displayName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("转写引擎") },
            leadingIcon = {
                Icon(
                    imageVector = when (currentType) {
                        STTEngineType.FASTER_WHISPER -> Icons.Default.Speed
                        STTEngineType.TENCENT_HYBRID -> Icons.Default.Cloud
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(8.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            engineOptions.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayName) },
                    leadingIcon = {
                        Icon(
                            imageVector = when (type) {
                                STTEngineType.FASTER_WHISPER -> Icons.Default.Speed
                                STTEngineType.TENCENT_HYBRID -> Icons.Default.Cloud
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        onSelect(type)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LLMEngineTypeDropdown(
    currentType: LLMEngineType,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (LLMEngineType) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = currentType.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("模型引擎") },
            leadingIcon = {
                Icon(
                    imageVector = when (currentType) {
                        LLMEngineType.AGENT_GATEWAY -> Icons.Default.Hub
                        LLMEngineType.LOCAL_OLLAMA -> Icons.Default.Computer
                        LLMEngineType.CLOUD_API -> Icons.Default.Cloud
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(8.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            LLMEngineType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayName) },
                    leadingIcon = {
                        Icon(
                            imageVector = when (type) {
                                LLMEngineType.AGENT_GATEWAY -> Icons.Default.Hub
                                LLMEngineType.LOCAL_OLLAMA -> Icons.Default.Computer
                                LLMEngineType.CLOUD_API -> Icons.Default.Cloud
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        onSelect(type)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentProviderDropdown(
    currentProvider: AgentProvider,
    onSelect: (AgentProvider) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = currentProvider.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Agent 提供方") },
            leadingIcon = {
                Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            shape = RoundedCornerShape(8.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AgentProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    onClick = {
                        onSelect(provider)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ReasoningEffortDropdown(
    label: String,
    current: T,
    options: List<T>,
    displayName: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = displayName(current),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            leadingIcon = {
                Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            shape = RoundedCornerShape(8.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(displayName(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    currentModel: String,
    options: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = localModelDisplayName(currentModel),
            onValueChange = {},
            readOnly = true,
            label = { Text("本地智悟通用模型") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            supportingText = {
                Text(
                    if (currentModel == "large-v3-turbo")
                        "中文会议优先，兼顾速度与准确度"
                    else
                        "平衡速度与准确度"
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(8.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(localModelDisplayName(option)) },
                    onClick = {
                        onSelect(option)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

private fun localModelDisplayName(model: String): String {
    return when (model) {
        "large-v3-turbo" -> "本地智悟通用模型 · Turbo"
        "tiny" -> "本地智悟通用模型 · 轻盈"
        "base" -> "本地智悟通用模型 · 标准"
        "small" -> "本地智悟通用模型 · 均衡"
        "medium" -> "本地智悟通用模型 · 进阶"
        "large-v3" -> "本地智悟通用模型 · 旗舰"
        else -> STTEngineType.FASTER_WHISPER.displayName
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudModelDropdown(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    val options = listOf("tencent-flash")
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = "智悟增强云模型",
            onValueChange = {},
            readOnly = true,
            label = { Text("云端模型") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            shape = MaterialTheme.shapes.medium
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            options.forEach { _ ->
                DropdownMenuItem(
                    text = { Text("智悟增强云模型") },
                    onClick = {
                        onSelect("tencent-flash")
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudApiFormatDropdown(
    currentFormat: CloudApiFormat,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (CloudApiFormat) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = currentFormat.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("云端 API 格式") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Api,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(8.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            CloudApiFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text(format.displayName) },
                    onClick = {
                        onSelect(format)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun SwitchingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = "正在应用转写引擎配置...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Composable
private fun EndpointCard(
    localEndpoint: String,
    onEndpointChange: (String) -> Unit,
    isScanning: Boolean,
    onScanServers: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "服务连接",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            OutlinedTextField(
                value = localEndpoint,
                onValueChange = onEndpointChange,
                label = { Text("服务地址") },
                placeholder = { Text("http://服务器地址:端口") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )

            // Scan Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isScanning) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        }
                    )
                    .clickable(enabled = !isScanning) { onScanServers() },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在扫描常用端口...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "自动扫描局域网内的 STT 服务",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Scan progress
            AnimatedVisibility(
                visible = isScanning,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val ports = listOf(8888, 8000)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "扫描当前网段 254 个地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "端口:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ports.forEach { port ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "$port",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredServersCard(
    servers: List<DiscoveredSTTServer>,
    onApply: (DiscoveredSTTServer) -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lan,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "发现 ${servers.size} 个服务",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            servers.forEach { server ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onApply(server) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sensors,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = server.endpoint,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Memory,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = if (server.engine.isNotBlank()) "${server.engine} · ${server.model}" else server.model.ifBlank { "未知模型" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "应用",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TestConnectionButton(
    isTesting: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = !isTesting,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        if (isTesting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("测试中...")
        } else {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("测试连接")
        }
    }
}
