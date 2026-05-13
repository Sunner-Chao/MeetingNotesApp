package com.oa.automation.ui.screen.recording

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ReportTemplateConfig
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    meetingId: String,
    onNavigateBack: () -> Unit,
    onNavigateToReport: (String) -> Unit,
    viewModel: RecordingViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler {
        viewModel.handleScreenExit()
        onNavigateBack()
    }

    LaunchedEffect(meetingId) {
        viewModel.loadMeeting(meetingId)
    }

    DisposableEffect(meetingId) {
        onDispose {
            viewModel.handleScreenExit()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            kotlinx.coroutines.delay(5000)
            viewModel.clearError()
        }
    }

    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val text = context.contentResolver.openInputStream(it)
                    ?.bufferedReader()
                    ?.readText() ?: ""
                viewModel.updateManualText(text)
            } catch (_: Exception) {
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.meetingTitle.ifBlank { "会议录音" },
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.handleScreenExit()
                            onNavigateBack()
                        }
                    ) {
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
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            InputModeToggleSection(
                inputMode = uiState.inputMode,
                onSwitchToVoice = viewModel::switchToVoiceMode,
                onSwitchToText = viewModel::switchToTextMode,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (uiState.inputMode == InputMode.VOICE) {
                // Status Hero Section
                StatusHeroSection(
                    isRecording = uiState.isRecording,
                    isTranscribing = uiState.isTranscribing,
                    previewMode = uiState.transcriptPreviewMode,
                    sttEngineLabel = uiState.sttEngineLabel
                )
            }

            // Error Banner
            AnimatedVisibility(
                visible = uiState.error != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                uiState.error?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.inputMode == InputMode.VOICE) {
                // Transcript Section
                TranscriptSection(
                    transcript = uiState.liveTranscript,
                    previewMode = uiState.transcriptPreviewMode,
                    isRecording = uiState.isRecording,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                TemplateSection(
                    templates = uiState.presetTemplates,
                    config = uiState.reportTemplate,
                    onSelectTemplate = viewModel::selectReportTemplate,
                    onContentChange = viewModel::updateReportTemplateContent,
                    onReset = viewModel::resetReportTemplate,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (uiState.inputMode == InputMode.TEXT) {
                Spacer(modifier = Modifier.height(12.dp))
                TextInputSection(
                    text = uiState.manualTextInput,
                    onTextChange = viewModel::updateManualText,
                    onImportFile = { filePickerLauncher.launch(arrayOf("text/plain", "application/msword")) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Control Section
            ControlSection(
                isRecording = uiState.isRecording,
                isTranscribing = uiState.isTranscribing,
                hasRecording = if (uiState.inputMode == InputMode.TEXT) uiState.manualTextInput.isNotBlank() else uiState.hasRecording,
                onRecordClick = {
                    if (uiState.isRecording) {
                        viewModel.stopRecording()
                    } else {
                        viewModel.startRecording()
                    }
                },
                onGenerateReport = {
                    if (uiState.inputMode == InputMode.TEXT) {
                        viewModel.saveTextAsTranscript { onNavigateToReport(meetingId) }
                    } else {
                        onNavigateToReport(meetingId)
                    }
                },
                inputMode = uiState.inputMode,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // 转写文本选择器
        if (uiState.showTranscriptPicker) {
            TranscriptPickerDialog(
                streamingText = uiState.pendingStreamingText,
                backendText = uiState.pendingBackendText,
                onSelectStreaming = viewModel::selectStreamingTranscript,
                onSelectBackend = viewModel::selectBackendTranscript,
                onDismiss = viewModel::dismissTranscriptPicker
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateSection(
    templates: List<PresetReportTemplate>,
    config: ReportTemplateConfig,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    onContentChange: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showEditor by remember { androidx.compose.runtime.mutableStateOf(false) }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
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
                        imageVector = Icons.Default.EditNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "纪要模板",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (config.isCustom) "${config.selectedName} · 已自定义" else config.selectedName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onReset) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "重置模板"
                    )
                }
            }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = config.selectedName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("预制模板") },
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
                    templates.forEach { template ->
                        DropdownMenuItem(
                            text = { Text(template.name) },
                            onClick = {
                                onSelectTemplate(template)
                                expanded = false
                            }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Text(
                text = config.content.ifBlank { "模板内容为空" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (showEditor) Int.MAX_VALUE else 8
            )

            AnimatedVisibility(visible = showEditor) {
                OutlinedTextField(
                    value = config.content,
                    onValueChange = onContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    label = { Text("自定义模板 Markdown") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = { showEditor = !showEditor },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (showEditor) "收起编辑" else "预览 / 编辑")
                }
                Button(
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("恢复预设")
                }
            }
        }
    }
}

@Composable
private fun StatusHeroSection(
    isRecording: Boolean,
    isTranscribing: Boolean,
    previewMode: String,
    sttEngineLabel: String
) {
    val statusColor by animateColorAsState(
        targetValue = when {
            isTranscribing -> MaterialTheme.colorScheme.tertiary
            isRecording -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(300),
        label = "statusColor"
    )

    val micScale by animateFloatAsState(
        targetValue = if (isRecording) 1.1f else 1f,
        animationSpec = tween(600),
        label = "micScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            statusColor.copy(alpha = 0.15f),
                            statusColor.copy(alpha = 0.05f)
                        )
                    )
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Status Indicator
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .scale(micScale)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                statusColor.copy(alpha = 0.3f),
                                statusColor.copy(alpha = 0.1f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Status Text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        isTranscribing -> "正在整理最终转写"
                        isRecording -> "录音进行中"
                        else -> "准备开始录音"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isRecording) {
                        "边录边预览，停止后生成最终稿"
                    } else {
                        "确认设置后点击开始录音"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Info Chips Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                InfoChip(
                    label = previewMode,
                    isActive = isRecording,
                    modifier = Modifier.weight(1f)
                )
                InfoChip(
                    label = if (isTranscribing) "整理中" else if (isRecording) "录音中" else "待开始",
                    isActive = isRecording,
                    modifier = Modifier.weight(1f)
                )
                if (sttEngineLabel.isNotBlank()) {
                    InfoChip(
                        label = sttEngineLabel,
                        isActive = false,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoChip(
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val chipColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        animationSpec = tween(300),
        label = "chipColor"
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = chipColor.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(chipColor)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = chipColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TranscriptSection(
    transcript: String,
    previewMode: String,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    var showFullPreview by remember { androidx.compose.runtime.mutableStateOf(false) }

    if (showFullPreview) {
        AlertDialog(
            onDismissRequest = { showFullPreview = false },
            title = { Text("完整预览文本") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    Text(
                        text = transcript.ifBlank { "暂无转写内容" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFullPreview = false }) {
                    Text("关闭")
                }
            }
        )
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
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
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "转写内容",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = previewMode,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (transcript.isNotBlank()) {
                FilledTonalButton(
                    onClick = { showFullPreview = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("查看完整预览")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            )

            if (transcript.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isRecording) {
                            "正在建立流式连接并等待首段识别..."
                        } else {
                            "开始录音后，这里会显示流式预览"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Fixed-height scrollable container so long transcripts don't push layout down
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                ) {
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlSection(
    isRecording: Boolean,
    isTranscribing: Boolean,
    hasRecording: Boolean,
    onRecordClick: () -> Unit,
    onGenerateReport: () -> Unit,
    inputMode: InputMode = InputMode.VOICE,
    modifier: Modifier = Modifier
) {
    val recordButtonColor by animateColorAsState(
        targetValue = if (isRecording) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(300),
        label = "recordButtonColor"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Button Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (inputMode == InputMode.VOICE) {
                    // Record Button
                    Button(
                        onClick = onRecordClick,
                        enabled = !isTranscribing,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = recordButtonColor
                        )
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRecording) "停止并转写" else "开始录音",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Generate Report Button
                FilledTonalButton(
                    onClick = onGenerateReport,
                    enabled = hasRecording && !isRecording && !isTranscribing,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = if (hasRecording) Icons.Default.Summarize else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "生成纪要",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Hint text
            Text(
                text = if (inputMode == InputMode.TEXT) {
                    "输入施工信息后点击生成纪要"
                } else if (isRecording) {
                    "点击停止即可生成最终稿"
                } else if (hasRecording) {
                    "录音已完成，可以生成会议纪要"
                } else {
                    "录音完成后可生成会议纪要"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun InputModeToggleSection(
    inputMode: InputMode,
    onSwitchToVoice: () -> Unit,
    onSwitchToText: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.EditNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "输入方式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onSwitchToVoice,
                    modifier = Modifier.weight(1f),
                    colors = if (inputMode == InputMode.VOICE)
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    else
                        ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(Icons.Default.Mic, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("语音输入")
                }
                FilledTonalButton(
                    onClick = onSwitchToText,
                    modifier = Modifier.weight(1f),
                    colors = if (inputMode == InputMode.TEXT)
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    else
                        ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(Icons.Default.Description, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("文本输入")
                }
            }
        }
    }
}

@Composable
private fun TextInputSection(
    text: String,
    onTextChange: (String) -> Unit,
    onImportFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
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
                        Icons.Default.Description,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "文本输入",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                FilledTonalButton(onClick = onImportFile) {
                    Icon(Icons.Default.OpenInFull, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导入文件", style = MaterialTheme.typography.labelSmall)
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 400.dp),
                label = { Text("请输入或粘贴施工信息") },
                placeholder = { Text("支持手动输入或从 TXT 文件导入...") },
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences
                )
            )
        }
    }
}

@Composable
fun TranscriptPickerDialog(
    streamingText: String,
    backendText: String,
    onSelectStreaming: () -> Unit,
    onSelectBackend: () -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { androidx.compose.runtime.mutableStateOf<TranscriptSource?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("选择转写文本", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "请选择作为最终稿的转写文本，点击卡片可展开查看全文：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TranscriptOptionCard(
                    label = "流式预览",
                    description = "实时转写，保留语气和口语化表达",
                    text = streamingText,
                    isSelected = selected == TranscriptSource.STREAMING,
                    onClick = { selected = TranscriptSource.STREAMING }
                )

                TranscriptOptionCard(
                    label = "后台转写（推荐）",
                    description = "完整音频文件转写，精度更高",
                    text = backendText,
                    isSelected = selected == TranscriptSource.BACKEND,
                    onClick = { selected = TranscriptSource.BACKEND }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (selected) {
                        TranscriptSource.STREAMING -> onSelectStreaming()
                        TranscriptSource.BACKEND -> onSelectBackend()
                        null -> {}
                    }
                },
                enabled = selected != null
            ) {
                Text("确定使用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun TranscriptOptionCard(
    label: String,
    description: String,
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }

    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已选择",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = if (expanded) "收起" else "查看全文",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            HorizontalDivider(
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                else
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
            Text(
                text = text.ifBlank { "(空)" },
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = if (expanded) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (expanded) 400.dp else 180.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}
