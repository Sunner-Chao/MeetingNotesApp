package com.oa.automation.ui.screen.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.oa.automation.BuildConfig
import com.oa.automation.domain.model.AgentProvider
import com.oa.automation.domain.model.AppThemeMode
import com.oa.automation.domain.model.ClaudeReasoningEffort
import com.oa.automation.domain.model.CodexReasoningEffort
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.TencentAsrBudgetPolicy
import com.oa.automation.ui.theme.LocalAppIsDarkTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
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

    var showSttSheet by remember { mutableStateOf(false) }
    var showLlmSheet by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

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

    // Populate the enhanced-cloud status chip once per entry.
    LaunchedEffect(Unit) {
        viewModel.refreshTencentAsrStatus()
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
                                    listOf(Color(0xFFE8F1FA), palette.background, Color(0xFFEAF2F8))
                                },
                                start = Offset.Zero,
                                end = Offset(size.width, size.height)
                            )
                        )
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(palette.blue.copy(alpha = 0.10f), Color.Transparent),
                                center = Offset(0f, size.height * 0.08f),
                                radius = size.width * 0.95f
                            )
                        )
                    }
            ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    topBar = {
                        SettingsTopBar(onNavigateBack = onNavigateBack)
                    },
                    snackbarHost = { SettingsMessageSnackbar(message = uiState.message) }
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
                                .padding(horizontal = 14.dp)
                                .padding(top = 2.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AppearanceRow(
                                themeMode = uiState.themeMode,
                                onThemeModeChange = viewModel::updateThemeMode
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Max),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SttOverviewCard(
                                    config = uiState.appConfig.sttConfig,
                                    policy = uiState.tencentAsrPolicy,
                                    isPolicyLoading = uiState.isLoadingTencentAsrPolicy,
                                    policyError = uiState.tencentAsrPolicyError,
                                    onOpenDetail = { showSttSheet = true },
                                    onAudioEnhancementChange = viewModel::updateSTTAudioEnhancement,
                                    onSpeakerDiarizationChange = viewModel::updateSTTSpeakerDiarization,
                                    onRefreshPolicy = viewModel::refreshTencentAsrStatus,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                                LlmOverviewCard(
                                    config = uiState.appConfig.llmConfig,
                                    onOpenDetail = { showLlmSheet = true },
                                    onCodexReasoningEffortChange = viewModel::updateCodexReasoningEffort,
                                    onClaudeReasoningEffortChange = viewModel::updateClaudeReasoningEffort,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                            NetworkCard(
                                config = uiState.appConfig.sttConfig,
                                isTesting = uiState.isTestingSTT,
                                onEndpointChange = viewModel::updateSTTLocalEndpoint,
                                onTestConnection = viewModel::testSTTConnection
                            )
                            FloatingBallRow(
                                enabled = uiState.floatingBallEnabled,
                                onChange = viewModel::updateFloatingBallEnabled
                            )
                            TemplateWorkflowMotionRow(
                                reducedMotion = uiState.templateWorkflowReducedMotion,
                                onChange = viewModel::updateTemplateWorkflowReducedMotion
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (BuildConfig.DEBUG) {
                                DebugDataRow(
                                    isUpdating = uiState.isUpdatingDemoData,
                                    onSeed = viewModel::seedDemoData,
                                    onClear = viewModel::clearDemoData
                                )
                            }
                            FooterStrip(
                                isCheckingUpdate = uiState.isCheckingUpdate,
                                isDownloadingUpdate = uiState.isDownloadingUpdate,
                                updateProgress = uiState.updateProgress,
                                availableVersion = uiState.availableUpdate?.versionName,
                                onCheckUpdate = viewModel::checkForAppUpdate,
                                onDownloadUpdate = viewModel::downloadAndInstallUpdate,
                                onReset = { showResetDialog = true }
                            )
                        }
                    }
                }

                if (showSttSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showSttSheet = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = SettingsSurfaceStrong
                    ) {
                        SttDetailSheetContent(
                            config = uiState.appConfig.sttConfig,
                            isTesting = uiState.isTestingSTT,
                            isSwitching = uiState.isSwitchingSTT,
                            isLoadingTencentAsrPolicy = uiState.isLoadingTencentAsrPolicy,
                            tencentAsrPolicy = uiState.tencentAsrPolicy,
                            tencentAsrPolicyError = uiState.tencentAsrPolicyError,
                            onEngineTypeChange = viewModel::updateSTTEngineType,
                            onLocalEndpointChange = viewModel::updateSTTLocalEndpoint,
                            onLocalModelChange = viewModel::updateSTTLocalModel,
                            onApiTokenChange = viewModel::updateSTTApiToken,
                            onTencentTierChange = viewModel::updateTencentAsrTier,
                            onTestConnection = viewModel::testSTTConnection,
                            onRefreshTencentAsrPolicy = viewModel::refreshTencentAsrStatus
                        )
                    }
                }

                if (showLlmSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showLlmSheet = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = SettingsSurfaceStrong
                    ) {
                        LlmDetailSheetContent(
                            config = uiState.appConfig.llmConfig,
                            isTesting = uiState.isTestingLLM,
                            onEngineTypeChange = viewModel::updateLLMEngineType,
                            onAgentEndpointChange = viewModel::updateAgentEndpoint,
                            onAgentAccessTokenChange = viewModel::updateAgentAccessToken,
                            onAgentProviderChange = viewModel::updateAgentProvider,
                            onCodexReasoningEffortChange = viewModel::updateCodexReasoningEffort,
                            onClaudeReasoningEffortChange = viewModel::updateClaudeReasoningEffort,
                            onTestConnection = viewModel::testLLMConnection
                        )
                    }
                }

                if (showResetDialog) {
                    AlertDialog(
                        onDismissRequest = { showResetDialog = false },
                        title = { Text("恢复默认设置") },
                        text = { Text("将清除所有个性化设置并恢复至初始状态，该操作无法撤销。") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showResetDialog = false
                                    viewModel.resetToDefault()
                                }
                            ) {
                                Text("恢复", color = SettingsError, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetDialog = false }) {
                                Text("取消")
                            }
                        }
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Top bar & snackbar
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onNavigateBack: () -> Unit) {
    val palette = LocalSettingsPalette.current
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = "设置",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = SettingsText
            )
        },
        navigationIcon = {
            Surface(
                onClick = onNavigateBack,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(42.dp),
                shape = CircleShape,
                color = SettingsSurfaceStrong.copy(alpha = 0.78f),
                border = BorderStroke(
                    1.dp,
                    if (palette.isDark) Color.White.copy(alpha = 0.18f) else palette.outline
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = SettingsText,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        },
        actions = {
            Surface(
                modifier = Modifier.padding(end = 14.dp),
                shape = RoundedCornerShape(50),
                color = palette.pillFill,
                border = BorderStroke(1.dp, palette.outline)
            ) {
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SettingsBlue
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        )
    )
}

@Composable
private fun SettingsMessageSnackbar(message: String?) {
    if (message != null) {
        val isSuccess = message.contains("成功")
        Snackbar(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = if (isSuccess) Color(0xFF0F4160) else MaterialTheme.colorScheme.errorContainer,
            contentColor = if (isSuccess) Color.White else MaterialTheme.colorScheme.onErrorContainer
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (isSuccess) SettingsCyan else SettingsError
                )
                Text(message)
            }
        }
    }
}

// ──────────────────────────────────────────────
// Appearance segmented row
// ──────────────────────────────────────────────

@Composable
private fun AppearanceRow(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "外观",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = SettingsText
        )
        SegmentedControl(
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            options = listOf(
                Triple("自动", Icons.Default.BrightnessAuto, AppThemeMode.SYSTEM),
                Triple("浅色", Icons.Default.LightMode, AppThemeMode.LIGHT),
                Triple("深色", Icons.Default.DarkMode, AppThemeMode.DARK)
            ),
            isSelected = { it == themeMode },
            onSelect = onThemeModeChange
        )
    }
}

@Composable
private fun <T> SegmentedControl(
    options: List<Triple<String, ImageVector?, T>>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalSettingsPalette.current
    val shape = SettingsPillShape
    Surface(
        modifier = modifier,
        shape = shape,
        color = palette.surface,
        border = BorderStroke(1.dp, palette.outline)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, (label, icon, value) ->
                val selected = isSelected(value)
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .padding(vertical = 8.dp)
                            .background(palette.outline)
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(if (selected) palette.selectedFill else Color.Transparent)
                        .then(
                            if (selected) Modifier.border(BorderStroke(1.5.dp, palette.blue), shape)
                            else Modifier
                        )
                        .clickable { onSelect(value) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (selected) palette.blue else SettingsMutedText,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) palette.blue else SettingsText
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Overview cards
// ──────────────────────────────────────────────

@Composable
private fun CardHeader(title: String, icon: ImageVector, onClick: () -> Unit) {
    val palette = LocalSettingsPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1E7FD4), if (palette.isDark) Color(0xFF0F5FA8) else Color(0xFF0A5FA8))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = SettingsText,
            maxLines = 1
        )
    }
}

@Composable
private fun ValuePill(label: String, value: String, onClick: () -> Unit) {
    val palette = LocalSettingsPalette.current
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = palette.pillFill
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.blue
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = SettingsText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CompactToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val palette = LocalSettingsPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (subtitle.isNullOrBlank()) 36.dp else 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = SettingsText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = SettingsMutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = palette.blue,
                uncheckedThumbColor = if (palette.isDark) SettingsMutedText else Color(0xFF7A849B),
                uncheckedTrackColor = if (palette.isDark) Color(0xFF343B52) else Color(0xFFD8DEEB),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun SttOverviewCard(
    config: STTConfig,
    policy: TencentAsrBudgetPolicy?,
    isPolicyLoading: Boolean,
    policyError: String?,
    onOpenDetail: () -> Unit,
    onAudioEnhancementChange: (Boolean) -> Unit,
    onSpeakerDiarizationChange: (Boolean) -> Unit,
    onRefreshPolicy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalSettingsPalette.current
    val isCloudEngine = config.engineType == com.oa.automation.domain.model.STTEngineType.TENCENT_HYBRID
    SettingsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CardHeader(title = "语音转文本", icon = Icons.Default.Mic, onClick = onOpenDetail)
            ValuePill(label = "当前引擎", value = config.engineType.displayName, onClick = onOpenDetail)
            CompactToggleRow(
                title = "语音增强",
                subtitle = "默认开启 · 降噪与音量优化",
                checked = config.audioEnhancementEnabled,
                onChange = onAudioEnhancementChange
            )
            CompactToggleRow(
                title = "说话人分离",
                subtitle = "实时按发言人整理",
                checked = config.speakerDiarizationEnabled,
                onChange = onSpeakerDiarizationChange
            )
            Spacer(modifier = Modifier.weight(1f))
            // The enhanced-cloud policy is only queryable while the cloud engine is active.
            val (statusText, statusColor) = when {
                !isCloudEngine -> "未启用" to SettingsMutedText
                isPolicyLoading -> "查询中" to SettingsMutedText
                policy != null && policy.tiers.any { it.isAvailable } -> "正常" to SettingsSuccess
                policy != null -> "受限" to palette.error
                policyError != null -> "异常" to palette.error
                else -> "未知" to SettingsMutedText
            }
            Surface(shape = RoundedCornerShape(12.dp), color = palette.pillFill) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = "增强服务",
                        style = MaterialTheme.typography.bodySmall,
                        color = SettingsText,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                    IconButton(
                        onClick = onRefreshPolicy,
                        enabled = !isPolicyLoading,
                        modifier = Modifier.size(26.dp)
                    ) {
                        if (isPolicyLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "刷新增强云服务状态",
                                tint = palette.blue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class EffortLevel(val label: String) { LOW("低"), MEDIUM("中"), HIGH("高") }

@Composable
private fun LlmOverviewCard(
    config: LLMConfig,
    onOpenDetail: () -> Unit,
    onCodexReasoningEffortChange: (CodexReasoningEffort) -> Unit,
    onClaudeReasoningEffortChange: (ClaudeReasoningEffort) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentModelLabel = when (config.engineType) {
        LLMEngineType.AGENT_GATEWAY -> config.agentProvider.displayName
        LLMEngineType.LOCAL_OLLAMA -> config.localModel.ifBlank { config.engineType.displayName }
        LLMEngineType.CLOUD_API -> config.cloudModel?.ifBlank { null } ?: config.engineType.displayName
    }
    val currentLevel = when (config.agentProvider) {
        AgentProvider.CODEX_CLI -> when (config.codexReasoningEffort) {
            CodexReasoningEffort.MINIMAL, CodexReasoningEffort.LOW -> EffortLevel.LOW
            CodexReasoningEffort.MEDIUM -> EffortLevel.MEDIUM
            CodexReasoningEffort.HIGH, CodexReasoningEffort.XHIGH -> EffortLevel.HIGH
        }
        AgentProvider.CLAUDE_CLI -> when (config.claudeReasoningEffort) {
            ClaudeReasoningEffort.LOW -> EffortLevel.LOW
            ClaudeReasoningEffort.MEDIUM -> EffortLevel.MEDIUM
            ClaudeReasoningEffort.HIGH, ClaudeReasoningEffort.MAX -> EffortLevel.HIGH
        }
    }
    SettingsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CardHeader(title = "智悟模型", icon = Icons.Default.SmartToy, onClick = onOpenDetail)
            ValuePill(label = "当前模型", value = currentModelLabel, onClick = onOpenDetail)
            Text(
                text = "推理强度",
                style = MaterialTheme.typography.labelMedium,
                color = SettingsMutedText
            )
            SegmentedControl(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                options = EffortLevel.entries.map { Triple(it.label, null, it) },
                isSelected = { it == currentLevel },
                onSelect = { level ->
                    when (config.agentProvider) {
                        AgentProvider.CODEX_CLI -> onCodexReasoningEffortChange(
                            when (level) {
                                EffortLevel.LOW -> CodexReasoningEffort.LOW
                                EffortLevel.MEDIUM -> CodexReasoningEffort.MEDIUM
                                EffortLevel.HIGH -> CodexReasoningEffort.HIGH
                            }
                        )
                        AgentProvider.CLAUDE_CLI -> onClaudeReasoningEffortChange(
                            when (level) {
                                EffortLevel.LOW -> ClaudeReasoningEffort.LOW
                                EffortLevel.MEDIUM -> ClaudeReasoningEffort.MEDIUM
                                EffortLevel.HIGH -> ClaudeReasoningEffort.HIGH
                            }
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(12.dp), color = LocalSettingsPalette.current.pillFill) {
                Text(
                    text = agentProviderIntro(config.agentProvider),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = SettingsMutedText
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Network card
// ──────────────────────────────────────────────

private fun endpointPort(endpoint: String): String = runCatching {
    val uri = java.net.URI(endpoint.trim())
    when {
        uri.port > 0 -> uri.port.toString()
        uri.scheme.equals("https", ignoreCase = true) -> "443"
        uri.scheme.equals("http", ignoreCase = true) -> "80"
        else -> null
    }
}.getOrNull() ?: "—"

@Composable
private fun NetworkCard(
    config: STTConfig,
    isTesting: Boolean,
    onEndpointChange: (String) -> Unit,
    onTestConnection: () -> Unit
) {
    val palette = LocalSettingsPalette.current
    var endpoint by remember(config.localEndpoint) { mutableStateOf(config.localEndpoint) }
    SettingsCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "服务地址",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SettingsText,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onTestConnection,
                    enabled = !isTesting,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("测试中", color = palette.blue, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("测试连接", color = palette.blue, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            OutlinedTextField(
                value = endpoint,
                onValueChange = {
                    endpoint = it
                    onEndpointChange(it)
                },
                placeholder = { Text("http://服务器地址:端口", color = SettingsMutedText) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LabeledValueBox(
                    label = "端口",
                    value = endpointPort(endpoint),
                    modifier = Modifier.weight(1f)
                )
                LabeledValueBox(
                    label = "连接协议",
                    value = if (endpoint.trim().startsWith("https", ignoreCase = true)) "HTTPS 加密" else "HTTP",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LabeledValueBox(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = LocalSettingsPalette.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = SettingsMutedText
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.outline)
        ) {
            Text(
                text = value,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = SettingsText,
                maxLines = 1
            )
        }
    }
}

// ──────────────────────────────────────────────
// Floating ball, debug tools & footer
// ──────────────────────────────────────────────

@Composable
private fun FloatingBallRow(
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    val palette = LocalSettingsPalette.current
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
    SettingsCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(palette.pillFill),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.WaterDrop,
                    contentDescription = null,
                    tint = palette.blue,
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "悬浮球",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SettingsText
                )
                Text(
                    text = if (overlayGranted) "录音进入后台时显示悬浮球，点击返回会议"
                    else "请允许智悟本显示在其他应用上层",
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsMutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = enabled && overlayGranted,
                modifier = Modifier.scale(0.8f),
                onCheckedChange = { turnedOn ->
                    if (turnedOn && !overlayGranted) {
                        // Preserve the user's intent while the system permission screen is open.
                        onChange(true)
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    } else {
                        onChange(turnedOn)
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = palette.blue,
                    uncheckedThumbColor = if (palette.isDark) SettingsMutedText else Color(0xFF7A849B),
                    uncheckedTrackColor = if (palette.isDark) Color(0xFF343B52) else Color(0xFFD8DEEB),
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun TemplateWorkflowMotionRow(
    reducedMotion: Boolean,
    onChange: (Boolean) -> Unit
) {
    val palette = LocalSettingsPalette.current
    SettingsCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(palette.pillFill),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = palette.blue,
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "模板流程动效",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SettingsText
                )
                Text(
                    text = if (reducedMotion) "已减少节点切换动画" else "录音前展示流程节点和轻量动画",
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsMutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = reducedMotion,
                modifier = Modifier.scale(0.8f),
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = palette.blue,
                    uncheckedThumbColor = if (palette.isDark) SettingsMutedText else Color(0xFF7A849B),
                    uncheckedTrackColor = if (palette.isDark) Color(0xFF343B52) else Color(0xFFD8DEEB),
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun DebugDataRow(
    isUpdating: Boolean,
    onSeed: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "开发测试数据",
            style = MaterialTheme.typography.labelSmall,
            color = SettingsMutedText,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onSeed, enabled = !isUpdating) {
            Text("注入", style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onClear, enabled = !isUpdating) {
            Text("清理", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun FooterStrip(
    isCheckingUpdate: Boolean,
    isDownloadingUpdate: Boolean,
    updateProgress: Int?,
    availableVersion: String?,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onReset: () -> Unit
) {
    val palette = LocalSettingsPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "当前版本",
                style = MaterialTheme.typography.labelSmall,
                color = SettingsMutedText
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = SettingsText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(
            onClick = if (availableVersion == null) onCheckUpdate else onDownloadUpdate,
            enabled = !isCheckingUpdate && !isDownloadingUpdate,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            if (isCheckingUpdate || isDownloadingUpdate) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = palette.blue,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = when {
                    isCheckingUpdate -> "检查中"
                    isDownloadingUpdate -> "下载 ${updateProgress ?: 0}%"
                    availableVersion != null -> "更新 $availableVersion"
                    else -> "检查更新"
                },
                color = palette.blue,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge
            )
        }
        TextButton(
            onClick = onReset,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            Text(
                text = "恢复默认设置",
                color = SettingsError,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
