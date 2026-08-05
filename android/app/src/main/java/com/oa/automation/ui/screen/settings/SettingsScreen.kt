package com.oa.automation.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.oa.automation.BuildConfig
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.oa.automation.domain.model.TencentAsrUsage
import com.oa.automation.domain.model.TencentAsrUsageService
import com.oa.automation.domain.model.formatTencentAsrDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            kotlinx.coroutines.delay(3000)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = {
            uiState.message?.let { message ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    containerColor = if (message.contains("成功")) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (message.contains("成功")) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (message.contains("成功")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
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
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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

                // STT Configuration Section
                STTConfigSection(
                    config = uiState.appConfig.sttConfig,
                    isTesting = uiState.isTestingSTT,
                    isScanning = uiState.isScanningSTT,
                    isSwitching = uiState.isSwitchingSTT,
                    isLoadingTencentAsrPolicy = uiState.isLoadingTencentAsrPolicy,
                    tencentAsrUsage = uiState.tencentAsrUsage,
                    tencentAsrUsageError = uiState.tencentAsrUsageError,
                    tencentAsrPolicy = uiState.tencentAsrPolicy,
                    tencentAsrPolicyError = uiState.tencentAsrPolicyError,
                    discoveredServers = uiState.discoveredServers,
                    onEngineTypeChange = viewModel::updateSTTEngineType,
                    onLocalEndpointChange = viewModel::updateSTTLocalEndpoint,
                    onLocalModelChange = viewModel::updateSTTLocalModel,
                    onApiTokenChange = viewModel::updateSTTApiToken,
                    onTencentTierChange = viewModel::updateTencentAsrTier,
                    onTestConnection = viewModel::testSTTConnection,
                    onScanServers = viewModel::scanSTTServers,
                    onRefreshTencentAsrPolicy = viewModel::refreshTencentAsrStatus,
                    onApplyServer = viewModel::applyDiscoveredServer,
                    onClearServers = viewModel::clearDiscoveredServers
                )

                // LLM Configuration Section
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

                // Reset Button
                OutlinedButton(
                    onClick = viewModel::resetToDefault,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重置为默认配置")
                }

                Spacer(modifier = Modifier.height(24.dp))
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
    val overlayGranted = Settings.canDrawOverlays(context)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader("显示与更新", Icons.Default.Palette)
            Text("主题", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeModeButton("跟随系统", themeMode == AppThemeMode.SYSTEM) {
                    onThemeModeChange(AppThemeMode.SYSTEM)
                }
                ThemeModeButton("浅色", themeMode == AppThemeMode.LIGHT) {
                    onThemeModeChange(AppThemeMode.LIGHT)
                }
                ThemeModeButton("深色", themeMode == AppThemeMode.DARK) {
                    onThemeModeChange(AppThemeMode.DARK)
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("后台悬浮球", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (overlayGranted) "切换到后台时显示，点击可快速回到智悟本" else "需要允许智悟本显示在其他应用上层",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = floatingBallEnabled && overlayGranted,
                    onCheckedChange = { enabled ->
                        if (enabled && !overlayGranted) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } else {
                            onFloatingBallChange(enabled)
                        }
                    }
                )
            }
            if (!overlayGranted) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("打开悬浮窗授权") }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("版本与更新", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        availableVersion?.let { "发现新版本 $it" } ?: "当前版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = if (availableVersion == null) onCheckUpdate else onDownloadUpdate,
                    enabled = !isCheckingUpdate && !isDownloadingUpdate,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isCheckingUpdate || isDownloadingUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
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
                    releaseNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ThemeModeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
    if (selected) {
        Button(onClick = onClick, shape = RoundedCornerShape(8.dp), colors = colors) {
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(8.dp), colors = colors) {
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    description: String? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null
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
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (expanded != null) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    tencentAsrUsage: TencentAsrUsage?,
    tencentAsrUsageError: String?,
    tencentAsrPolicy: TencentAsrBudgetPolicy?,
    tencentAsrPolicyError: String?,
    discoveredServers: List<DiscoveredSTTServer>,
    onEngineTypeChange: (STTEngineType) -> Unit,
    onLocalEndpointChange: (String) -> Unit,
    onLocalModelChange: (String) -> Unit,
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
    var cardExpanded by remember { mutableStateOf(true) }

    val modelOptions = when (config.engineType) {
        STTEngineType.FASTER_WHISPER -> listOf("tiny", "base", "small", "medium", "large-v3")
        STTEngineType.SENSE_VOICE -> listOf("SenseVoiceSmall", "iic/SenseVoiceSmall")
        STTEngineType.TENCENT_HYBRID -> emptyList()
    }

    var localEndpoint by remember(config.localEndpoint) { mutableStateOf(config.localEndpoint) }
    var localModel by remember(config.localModel) { mutableStateOf(config.localModel) }
    var apiToken by remember(config.apiToken) { mutableStateOf(config.apiToken ?: "") }
    LaunchedEffect(discoveredServers) {
        showServerList = discoveredServers.isNotEmpty()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                title = "语音转文本 (STT)",
                icon = Icons.Default.Mic,
                description = "配置语音识别服务",
                expanded = cardExpanded,
                onToggle = { cardExpanded = !cardExpanded }
            )

            AnimatedVisibility(
                visible = cardExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
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
                        TencentAsrPolicyPanel(
                            usage = tencentAsrUsage,
                            usageError = tencentAsrUsageError,
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
                            engineType = config.engineType,
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
                                tier.isPaid -> "臻享识别能力，请确认云端账户余额与服务状态"
                                else -> "标准普通话识别，按云端产品规则运行"
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
private fun TencentAsrPolicyPanel(
    usage: TencentAsrUsage?,
    usageError: String?,
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
                    text = "智悟增强云模型用量",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = usage?.let { "${it.month} · 云端官方账户统计（可能延迟）" }
                        ?: "正在读取云端官方账户统计",
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
                    Icon(Icons.Default.Refresh, contentDescription = "刷新云模型官方用量")
                }
            }
        }

        if (usage != null) {
            usage.services.forEach { service -> TencentAsrOfficialUsageRow(service) }
            Text(
                text = if (usage.isEstimated) {
                    "云端官方统计暂不可用，当前显示服务端估算；恢复后刷新即可对齐官方后台。"
                } else {
                    "统计范围为当前云端账户，本月数据可能延迟入账；手动刷新会跳过 5 分钟缓存。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (usage.isEstimated) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!isLoading && usageError != null) {
            Text(
                text = usageError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (policy != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                text = "服务档位状态",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            policy.tiers.forEach { tier -> TencentAsrTierPolicyRow(tier) }
            Text(
                text = "档位状态仅表示服务可用性与应用策略，不参与上方官方时长统计。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!isLoading && error != null) {
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TencentAsrOfficialUsageRow(service: TencentAsrUsageService) {
    val progress = if (service.freeSeconds > 0) service.usageRatio.coerceIn(0f, 1f) else 0f
    val displayName = when (service.id) {
        "realtime" -> "实时转写"
        "flash" -> "终稿识别"
        else -> service.displayName
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(displayName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                "已用 ${formatTencentAsrDuration(service.usedSeconds)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (service.remainingSeconds == 0L) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            text = "${service.requestCount} 次 · 剩余参考 ${formatTencentAsrDuration(service.remainingSeconds)} / ${formatTencentAsrDuration(service.freeSeconds)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (service.pendingLocalSeconds > 0 || service.pendingLocalRequestCount > 0) {
            Text(
                text = "待云端后台同步：约 ${formatTencentAsrDuration(service.pendingLocalSeconds)} · ${service.pendingLocalRequestCount} 次",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
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
            text = when {
                !tier.isAvailable -> "当前服务端未启用"
                tier.budgetEnforced -> "已启用服务端额度策略"
                else -> "已启用，不限制单次会议时长"
            },
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                title = "大语言模型 (LLM)",
                icon = Icons.Default.SmartToy,
                description = "LLM 与 STT 配置彼此独立",
                expanded = cardExpanded,
                onToggle = { cardExpanded = !cardExpanded }
            )

            AnimatedVisibility(
                visible = cardExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

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
                                label = "Codex 推理强度",
                                current = config.codexReasoningEffort,
                                options = CodexReasoningEffort.entries,
                                displayName = CodexReasoningEffort::displayName,
                                onSelect = onCodexReasoningEffortChange
                            )
                            AgentProvider.CLAUDE_CLI -> ReasoningEffortDropdown(
                                label = "Claude 推理强度",
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
                        STTEngineType.SENSE_VOICE -> Icons.Default.Language
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
                                STTEngineType.SENSE_VOICE -> Icons.Default.Language
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
    engineType: STTEngineType,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = localModelDisplayName(currentModel, engineType),
            onValueChange = {},
            readOnly = true,
            label = { Text("智悟本地模型") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            supportingText = {
                Text(
                    if (engineType == STTEngineType.SENSE_VOICE)
                        "中文语音优选"
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
                    text = { Text(localModelDisplayName(option, engineType)) },
                    onClick = {
                        onSelect(option)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

private fun localModelDisplayName(model: String, engineType: STTEngineType): String {
    if (engineType == STTEngineType.SENSE_VOICE) {
        return "智悟灵听 · 中文优选"
    }
    return when (model) {
        "tiny" -> "智悟本地 · 轻盈"
        "base" -> "智悟本地 · 标准"
        "small" -> "智悟本地 · 均衡"
        "medium" -> "智悟本地 · 进阶"
        "large-v3" -> "智悟本地 · 旗舰"
        else -> "智悟本地模型"
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
