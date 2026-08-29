package com.oa.automation.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.oa.automation.BuildConfig
import com.oa.automation.domain.model.AgentProvider
import com.oa.automation.domain.model.ClaudeReasoningEffort
import com.oa.automation.domain.model.CloudApiFormat
import com.oa.automation.domain.model.CodexReasoningEffort
import com.oa.automation.domain.model.DiscoveredSTTServer
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.TencentAsrBudgetPolicy
import com.oa.automation.domain.model.TencentAsrTier
import com.oa.automation.domain.model.TencentAsrTierPolicy

// ──────────────────────────────────────────────
// STT detail sheet
// ──────────────────────────────────────────────

@Composable
internal fun SttDetailSheetContent(
    config: STTConfig,
    isTesting: Boolean,
    isSwitching: Boolean,
    isLoadingTencentAsrPolicy: Boolean,
    tencentAsrPolicy: TencentAsrBudgetPolicy?,
    tencentAsrPolicyError: String?,
    onEngineTypeChange: (STTEngineType) -> Unit,
    onLocalEndpointChange: (String) -> Unit,
    onLocalModelChange: (String) -> Unit,
    onApiTokenChange: (String?) -> Unit,
    onTencentTierChange: (TencentAsrTier) -> Unit,
    onTestConnection: () -> Unit,
    onRefreshTencentAsrPolicy: () -> Unit
) {
    var engineExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    val modelOptions = when (config.engineType) {
        STTEngineType.FASTER_WHISPER -> listOf("large-v3-turbo", "large-v3", "medium", "small", "base", "tiny")
        STTEngineType.TENCENT_HYBRID -> emptyList()
    }

    var localEndpoint by remember(config.localEndpoint) { mutableStateOf(config.localEndpoint) }
    var localModel by remember(config.localModel) { mutableStateOf(config.localModel) }
    var apiToken by remember(config.apiToken) { mutableStateOf(config.apiToken ?: "") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "语音转文本设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = SettingsText
        )

        EngineTypeDropdown(
            currentType = config.engineType,
            expanded = engineExpanded,
            enabled = !isSwitching,
            onExpandedChange = { engineExpanded = it },
            onSelect = onEngineTypeChange
        )

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

            OutlinedTextField(
                value = localEndpoint,
                onValueChange = {
                    localEndpoint = it
                    onLocalEndpointChange(it)
                },
                label = { Text("服务地址") },
                placeholder = { Text("http://服务器地址:端口") },
                leadingIcon = {
                    Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
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

            TestConnectionButton(isTesting = isTesting, onClick = onTestConnection)
        }
    }
}

// ──────────────────────────────────────────────
// LLM detail sheet
// ──────────────────────────────────────────────

@Composable
internal fun LlmDetailSheetContent(
    config: LLMConfig,
    isTesting: Boolean,
    onEngineTypeChange: (LLMEngineType) -> Unit,
    onAgentEndpointChange: (String) -> Unit,
    onAgentAccessTokenChange: (String?) -> Unit,
    onAgentProviderChange: (AgentProvider) -> Unit,
    onCodexReasoningEffortChange: (CodexReasoningEffort) -> Unit,
    onClaudeReasoningEffortChange: (ClaudeReasoningEffort) -> Unit,
    onTestConnection: () -> Unit
) {
    var engineExpanded by remember { mutableStateOf(false) }

    var agentEndpoint by remember(config.agentEndpoint) { mutableStateOf(config.agentEndpoint) }
    var agentAccessToken by remember(config.agentAccessToken) { mutableStateOf(config.agentAccessToken.orEmpty()) }
    var agentTokenDirty by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "智悟模型设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = SettingsText
        )

        LLMEngineTypeDropdown(
            currentType = config.engineType,
            expanded = engineExpanded,
            onExpandedChange = { engineExpanded = it },
            onSelect = onEngineTypeChange
        )

        AgentProviderDropdown(
            currentProvider = config.agentProvider,
            onSelect = onAgentProviderChange
        )

        AgentProviderIntro(currentProvider = config.agentProvider)

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

        // Gateway endpoint and token are operator-side configuration. They stay
        // editable only in debug builds; release users get the built-in values.
        if (BuildConfig.DEBUG) {
            OutlinedTextField(
                value = agentEndpoint,
                onValueChange = {
                    agentEndpoint = it
                    onAgentEndpointChange(it)
                },
                label = { Text("Agent 服务地址（仅调试构建可见）") },
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
                label = { Text("Agent 访问令牌（仅调试构建可见）") },
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
        }

        TestConnectionButton(isTesting = isTesting, onClick = onTestConnection)
    }
}

internal fun agentProviderIntro(provider: AgentProvider): String = when (provider) {
    AgentProvider.CODEX_CLI -> "推理更深入，纪要结构更完整，适合内容复杂的长会议"
    AgentProvider.CLAUDE_CLI -> "响应速度更快，适合快速出稿与日常整理"
}

@Composable
private fun AgentProviderIntro(currentProvider: AgentProvider) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = LocalSettingsPalette.current.pillFill
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AgentProvider.entries.forEach { provider ->
                val isCurrent = provider == currentProvider
                Text(
                    text = "${provider.displayName}：${agentProviderIntro(provider)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) SettingsText else SettingsMutedText
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Tencent ASR panels (unchanged behavior)
// ──────────────────────────────────────────────

@Composable
internal fun TencentAsrTierSelector(
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
        TencentAsrTier.entries.filterNot { it.isPaid }.forEach { tier ->
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
                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
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
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@Composable
internal fun TencentAsrServiceStatusPanel(
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
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新云模型服务状态")
                }
            }
        }

        if (policy != null) {
            policy.tiers.filterNot { it.isPaid }.forEach { tier -> TencentAsrTierPolicyRow(tier) }
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
// Dropdowns & shared controls
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EngineTypeDropdown(
    currentType: STTEngineType,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (STTEngineType) -> Unit
) {
    val engineOptions = if (BuildConfig.STT_REMOTE_SWITCH_ENABLED) {
        STTEngineType.entries
    } else {
        listOf(STTEngineType.FASTER_WHISPER, STTEngineType.TENCENT_HYBRID)
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
internal fun LLMEngineTypeDropdown(
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
            // Only the Zhiwu cloud gateway is offered to end users; legacy
            // Ollama/direct-cloud configs keep working but cannot be re-selected.
            listOf(LLMEngineType.AGENT_GATEWAY).forEach { type ->
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
internal fun AgentProviderDropdown(
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
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
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
internal fun <T> ReasoningEffortDropdown(
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
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
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
internal fun ModelDropdown(
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
                Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            supportingText = {
                Text(
                    if (currentModel == "large-v3-turbo") "中文会议优先，兼顾速度与准确度"
                    else "平衡速度与准确度"
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

internal fun localModelDisplayName(model: String): String {
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

@Composable
internal fun SwitchingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = "正在应用转写引擎配置...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Composable
internal fun TestConnectionButton(
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
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("测试连接")
        }
    }
}
