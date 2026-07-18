package com.oa.automation.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oa.automation.domain.model.CloudApiFormat
import com.oa.automation.domain.model.AgentProvider
import com.oa.automation.domain.model.DiscoveredSTTServer
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.STTEngineType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            kotlinx.coroutines.delay(3000)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.isLoggedOut) {
        if (uiState.isLoggedOut) {
            onLogout()
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
                    shape = RoundedCornerShape(12.dp),
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
                // Tips Card
                TipsCard()

                // STT Configuration Section
                STTConfigSection(
                    config = uiState.appConfig.sttConfig,
                    isTesting = uiState.isTestingSTT,
                    isScanning = uiState.isScanningSTT,
                    isSwitching = uiState.isSwitchingSTT,
                    discoveredServers = uiState.discoveredServers,
                    onEngineTypeChange = viewModel::updateSTTEngineType,
                    onLocalEndpointChange = viewModel::updateSTTLocalEndpoint,
                    onLocalModelChange = viewModel::updateSTTLocalModel,
                    onApiTokenChange = viewModel::updateSTTApiToken,
                    onCloudEndpointChange = viewModel::updateSTTCloudEndpoint,
                    onCloudApiKeyChange = viewModel::updateSTTCloudApiKey,
                    onTestConnection = viewModel::testSTTConnection,
                    onScanServers = viewModel::scanSTTServers,
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
                    onLocalEndpointChange = viewModel::updateLLMLocalEndpoint,
                    onLocalModelChange = viewModel::updateLLMLocalModel,
                    onCloudEndpointChange = viewModel::updateLLMCloudEndpoint,
                    onCloudApiKeyChange = viewModel::updateLLMCloudApiKey,
                    onCloudModelChange = viewModel::updateLLMCloudModel,
                    onCloudApiFormatChange = viewModel::updateLLMCloudApiFormat,
                    onTestConnection = viewModel::testLLMConnection
                )

                // Logout Button
                var showLogoutDialog by remember { mutableStateOf(false) }

                if (showLogoutDialog) {
                    AlertDialog(
                        onDismissRequest = { showLogoutDialog = false },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        title = { Text("退出登录") },
                        text = { Text("确定要退出当前账号吗？") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showLogoutDialog = false
                                    viewModel.logout()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("退出")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = { showLogoutDialog = false },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("取消")
                            }
                        },
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("退出登录")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Reset Button
                OutlinedButton(
                    onClick = viewModel::resetToDefault,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
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
private fun TipsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "服务配置会即时保存。建议先测通 STT，再配置 LLM。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
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
    discoveredServers: List<DiscoveredSTTServer>,
    onEngineTypeChange: (STTEngineType) -> Unit,
    onLocalEndpointChange: (String) -> Unit,
    onLocalModelChange: (String) -> Unit,
    onApiTokenChange: (String?) -> Unit,
    onCloudEndpointChange: (String?) -> Unit,
    onCloudApiKeyChange: (String?) -> Unit,
    onTestConnection: () -> Unit,
    onScanServers: () -> Unit,
    onApplyServer: (DiscoveredSTTServer) -> Unit,
    onClearServers: () -> Unit
) {
    var engineExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var showServerList by remember { mutableStateOf(false) }
    var cardExpanded by remember { mutableStateOf(false) }

    val modelOptions = when (config.engineType) {
        STTEngineType.FASTER_WHISPER -> listOf("tiny", "base", "small", "medium", "large-v3")
        STTEngineType.SENSE_VOICE -> listOf("SenseVoiceSmall", "iic/SenseVoiceSmall")
        STTEngineType.CLOUD_ASR -> emptyList()
    }

    var localEndpoint by remember(config.localEndpoint) { mutableStateOf(config.localEndpoint) }
    var localModel by remember(config.localModel) { mutableStateOf(config.localModel) }
    var apiToken by remember(config.apiToken) { mutableStateOf(config.apiToken ?: "") }
    var cloudEndpoint by remember(config.cloudEndpoint) { mutableStateOf(config.cloudEndpoint ?: "") }
    var cloudApiKey by remember(config.cloudApiKey) { mutableStateOf(config.cloudApiKey ?: "") }

    LaunchedEffect(discoveredServers) {
        showServerList = discoveredServers.isNotEmpty()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                        onExpandedChange = { engineExpanded = it },
                        onSelect = onEngineTypeChange
                    )

                    // Switching progress
                    AnimatedVisibility(visible = isSwitching) {
                        SwitchingIndicator()
                    }

                    if (config.engineType != STTEngineType.CLOUD_ASR) {
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
                    }

                    if (config.engineType == STTEngineType.CLOUD_ASR) {
                        CloudAsrFields(
                            cloudEndpoint = cloudEndpoint,
                            cloudApiKey = cloudApiKey,
                            onEndpointChange = {
                                cloudEndpoint = it
                                onCloudEndpointChange(it.ifEmpty { null })
                            },
                            onApiKeyChange = {
                                cloudApiKey = it
                                onCloudApiKeyChange(it.ifEmpty { null })
                            }
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                            shape = RoundedCornerShape(12.dp)
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
                            shape = RoundedCornerShape(12.dp)
                        )

                        AgentProviderDropdown(
                            currentProvider = config.agentProvider,
                            onSelect = onAgentProviderChange
                        )
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
                            shape = RoundedCornerShape(12.dp)
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
                            shape = RoundedCornerShape(12.dp)
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
                            shape = RoundedCornerShape(12.dp)
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
                            shape = RoundedCornerShape(12.dp)
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
                            shape = RoundedCornerShape(12.dp)
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
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (STTEngineType) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = currentType.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("转写引擎") },
            leadingIcon = {
                Icon(
                    imageVector = when (currentType) {
                        STTEngineType.FASTER_WHISPER -> Icons.Default.Speed
                        STTEngineType.SENSE_VOICE -> Icons.Default.Language
                        STTEngineType.CLOUD_ASR -> Icons.Default.Cloud
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            STTEngineType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayName) },
                    leadingIcon = {
                        Icon(
                            imageVector = when (type) {
                                STTEngineType.FASTER_WHISPER -> Icons.Default.Speed
                                STTEngineType.SENSE_VOICE -> Icons.Default.Language
                                STTEngineType.CLOUD_ASR -> Icons.Default.Cloud
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
            shape = RoundedCornerShape(12.dp)
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
            shape = RoundedCornerShape(12.dp)
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
            value = currentModel,
            onValueChange = {},
            readOnly = true,
            label = { Text("本地模型") },
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
                        "SenseVoice 推荐: SenseVoiceSmall"
                    else
                        "Faster-Whisper 推荐: small"
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
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
            shape = RoundedCornerShape(12.dp)
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
            .clip(RoundedCornerShape(12.dp))
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
            text = "正在切换 PC 端 STT 服务并等待恢复...",
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
        shape = RoundedCornerShape(16.dp),
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
                placeholder = { Text("http://ecobim.cn:57414") },
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
                shape = RoundedCornerShape(12.dp)
            )

            // Scan Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
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
        shape = RoundedCornerShape(16.dp),
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
                    shape = RoundedCornerShape(12.dp),
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
                                    .clip(RoundedCornerShape(10.dp))
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
private fun CloudAsrFields(
    cloudEndpoint: String,
    cloudApiKey: String,
    onEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit
) {
    OutlinedTextField(
        value = cloudEndpoint,
        onValueChange = onEndpointChange,
        label = { Text("云端 API 地址") },
        placeholder = { Text("https://api.example.com/v1") },
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
        shape = RoundedCornerShape(12.dp)
    )

    OutlinedTextField(
        value = cloudApiKey,
        onValueChange = onApiKeyChange,
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
        shape = RoundedCornerShape(12.dp)
    )
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
        shape = RoundedCornerShape(12.dp),
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
