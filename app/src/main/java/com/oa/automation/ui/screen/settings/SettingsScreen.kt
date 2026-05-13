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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oa.automation.domain.model.CloudApiFormat
import com.oa.automation.domain.model.DiscoveredSTTServer
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.STTEngineType

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
    description: String? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp)
            )
        }
    }
}

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
    onCloudEndpointChange: (String?) -> Unit,
    onCloudApiKeyChange: (String?) -> Unit,
    onTestConnection: () -> Unit,
    onScanServers: () -> Unit,
    onApplyServer: (DiscoveredSTTServer) -> Unit,
    onClearServers: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var showServerList by remember { mutableStateOf(false) }

    val modelOptions = when (config.engineType) {
        STTEngineType.FASTER_WHISPER -> listOf("tiny", "base", "small", "medium", "large-v3")
        STTEngineType.SENSE_VOICE -> listOf("SenseVoiceSmall", "iic/SenseVoiceSmall")
        STTEngineType.CLOUD_ASR -> emptyList()
    }

    var localEndpoint by remember(config.localEndpoint) { mutableStateOf(config.localEndpoint) }
    var localModel by remember(config.localModel) { mutableStateOf(config.localModel) }
    var cloudEndpoint by remember(config.cloudEndpoint) { mutableStateOf(config.cloudEndpoint ?: "") }
    var cloudApiKey by remember(config.cloudApiKey) { mutableStateOf(config.cloudApiKey ?: "") }

    // Show server list when servers are discovered
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
                description = "配置语音识别服务"
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Engine Type Selection
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = config.engineType.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("转写引擎") },
                    leadingIcon = {
                        Icon(
                            imageVector = when (config.engineType) {
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
                    onDismissRequest = { expanded = false }
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
                                onEngineTypeChange(type)
                                expanded = false
                            }
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isSwitching) {
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

            if (config.engineType != STTEngineType.CLOUD_ASR) {
                if (modelOptions.isNotEmpty() && localModel !in modelOptions) {
                    localModel = modelOptions.first()
                    onLocalModelChange(localModel)
                }

                // Endpoint Section with Scan
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
                            onValueChange = {
                                localEndpoint = it
                                onLocalEndpointChange(it)
                            },
                            label = { Text("服务地址") },
                            placeholder = { Text("https://1154083nrki65.vicp.fun") },
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

                        // Scanning Progress Indicator
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

                // Discovered Servers List
                AnimatedVisibility(
                    visible = showServerList && discoveredServers.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
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
                                        text = "发现 ${discoveredServers.size} 个服务",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        showServerList = false
                                        onClearServers()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "关闭",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            discoveredServers.forEach { server ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onApplyServer(server)
                                            showServerList = false
                                        },
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

                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = !modelExpanded }
                ) {
                    OutlinedTextField(
                        value = localModel,
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
                                if (config.engineType == STTEngineType.SENSE_VOICE)
                                    "SenseVoice 推荐: SenseVoiceSmall"
                                else
                                    "Faster-Whisper 推荐: small"
                            )
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        modelOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    localModel = option
                                    onLocalModelChange(option)
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (config.engineType == STTEngineType.CLOUD_ASR) {
                OutlinedTextField(
                    value = cloudEndpoint,
                    onValueChange = {
                        cloudEndpoint = it
                        onCloudEndpointChange(it.ifEmpty { null })
                    },
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
            }

            // Test Connection Button
            Button(
                onClick = onTestConnection,
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
    }
}

@Composable
private fun LLMConfigSection(
    config: com.oa.automation.domain.model.LLMConfig,
    isTesting: Boolean,
    onEngineTypeChange: (LLMEngineType) -> Unit,
    onLocalEndpointChange: (String) -> Unit,
    onLocalModelChange: (String) -> Unit,
    onCloudEndpointChange: (String?) -> Unit,
    onCloudApiKeyChange: (String?) -> Unit,
    onCloudModelChange: (String?) -> Unit,
    onCloudApiFormatChange: (CloudApiFormat) -> Unit,
    onTestConnection: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var apiFormatExpanded by remember { mutableStateOf(false) }

    var localEndpoint by remember(config.localEndpoint) { mutableStateOf(config.localEndpoint) }
    var localModel by remember(config.localModel) { mutableStateOf(config.localModel) }
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
                description = "LLM 与 STT 配置彼此独立"
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Engine Type Selection
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = config.engineType.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("模型引擎") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (config.engineType == LLMEngineType.LOCAL_OLLAMA)
                                Icons.Default.Computer
                            else
                                Icons.Default.Cloud,
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
                    onDismissRequest = { expanded = false }
                ) {
                    LLMEngineType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.displayName) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (type == LLMEngineType.LOCAL_OLLAMA)
                                        Icons.Default.Computer
                                    else
                                        Icons.Default.Cloud,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                onEngineTypeChange(type)
                                expanded = false
                            }
                        )
                    }
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
                    placeholder = { Text("https://api.siliconflow.cn/v1") },
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

                ExposedDropdownMenuBox(
                    expanded = apiFormatExpanded,
                    onExpandedChange = { apiFormatExpanded = !apiFormatExpanded }
                ) {
                    OutlinedTextField(
                        value = config.cloudApiFormat.displayName,
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
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = apiFormatExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = apiFormatExpanded,
                        onDismissRequest = { apiFormatExpanded = false }
                    ) {
                        CloudApiFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format.displayName) },
                                onClick = {
                                    onCloudApiFormatChange(format)
                                    apiFormatExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Test Connection Button
            Button(
                onClick = onTestConnection,
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
    }
}
