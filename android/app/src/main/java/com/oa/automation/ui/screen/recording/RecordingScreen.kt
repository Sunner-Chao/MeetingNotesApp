package com.oa.automation.ui.screen.recording

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.PresetReportTemplate
import java.io.File
import org.koin.androidx.compose.koinViewModel

/**
 * RecordingScreen - 精简版录音页面
 * 设计原则：核心操作突出，次要信息折叠
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    meetingId: String,
    onNavigateBack: () -> Unit,
    onNavigateToReport: (String) -> Unit,
    viewModel: RecordingViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> viewModel.importImages(uris) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        if (saved) pendingCameraUri?.let { viewModel.importImages(listOf(it)) }
        pendingCameraUri = null
    }

    fun launchCamera() {
        val directory = File(context.cacheDir, "exports/camera").apply { mkdirs() }
        val target = File(directory, "meeting_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target
        )
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    BackHandler {
        viewModel.handleScreenExit()
        onNavigateBack()
    }

    LaunchedEffect(meetingId) {
        viewModel.loadMeeting(meetingId)
    }

    LaunchedEffect(uiState.reportReadyToOpen) {
        if (uiState.reportReadyToOpen) {
            viewModel.consumeReportNavigation()
            onNavigateToReport(meetingId)
        }
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

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val text = context.contentResolver.openInputStream(it)
                    ?.use { input -> input.bufferedReader().readText() }
                    .orEmpty()
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
                        maxLines = 1,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.handleScreenExit()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 模板选择器
            if (uiState.presetTemplates.isNotEmpty()) {
                TemplateSelectorCard(
                    templates = uiState.presetTemplates,
                    selectedTemplateName = uiState.reportTemplate.selectedName,
                    onSelectTemplate = viewModel::selectReportTemplate
                )
            }

            // 输入模式切换
            CompactInputModeToggle(
                inputMode = uiState.inputMode,
                onSwitchToVoice = viewModel::switchToVoiceMode,
                onSwitchToText = viewModel::switchToTextMode
            )

            MeetingImagesSection(
                attachments = uiState.attachments,
                onTakePhoto = ::launchCamera,
                onPickImages = { galleryLauncher.launch("image/*") },
                onDelete = viewModel::deleteAttachment
            )

            // 错误提示
            AnimatedVisibility(
                visible = uiState.error != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                uiState.error?.let { error ->
                    CompactErrorBanner(
                        error = error,
                        onDismiss = viewModel::clearError
                    )
                }
            }

            if (uiState.inputMode == InputMode.VOICE) {
                // 录音与生成纪要双卡片（核心操作区）
                DualActionCards(
                    isRecording = uiState.isRecording,
                    isTranscribing = uiState.isTranscribing,
                    isGeneratingReport = uiState.isGeneratingReport,
                    hasRecording = uiState.hasRecording,
                    sttEngineLabel = uiState.sttEngineLabel,
                    onRecordClick = {
                        if (uiState.isRecording) {
                            viewModel.stopRecording()
                        } else {
                            viewModel.startRecording()
                        }
                    },
                    onGenerateReport = viewModel::generateReport
                )

                // 转写内容（可折叠）
                CollapsibleTranscriptCard(
                    transcript = uiState.liveTranscript,
                    previewMode = uiState.transcriptPreviewMode,
                    isRecording = uiState.isRecording
                )
            }

            if (uiState.inputMode == InputMode.TEXT) {
                Spacer(modifier = Modifier.height(4.dp))

                // 文本输入卡片
                CompactTextInputCard(
                    text = uiState.manualTextInput,
                    onTextChange = viewModel::updateManualText,
                    onImportFile = { filePickerLauncher.launch(arrayOf("text/plain", "application/msword")) }
                )

                // 文本模式下的生成纪要卡片
                TextModeActionCard(
                    hasContent = uiState.manualTextInput.isNotBlank(),
                    isGeneratingReport = uiState.isGeneratingReport,
                    onGenerateReport = viewModel::saveTextAndGenerateReport
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CompactInputModeToggle(
    inputMode: InputMode,
    onSwitchToVoice: () -> Unit,
    onSwitchToText: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModeButton(
                text = "语音",
                icon = Icons.Default.Mic,
                selected = inputMode == InputMode.VOICE,
                onClick = onSwitchToVoice,
                modifier = Modifier.weight(1f)
            )
            ModeButton(
                text = "文本",
                icon = Icons.Default.Description,
                selected = inputMode == InputMode.TEXT,
                onClick = onSwitchToText,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateSelectorCard(
    templates: List<PresetReportTemplate>,
    selectedTemplateName: String,
    onSelectTemplate: (PresetReportTemplate) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var previewTemplate by remember { mutableStateOf<PresetReportTemplate?>(null) }

    // 模板预览弹窗
    if (previewTemplate != null) {
        AlertDialog(
            onDismissRequest = { previewTemplate = null },
            title = { Text(previewTemplate!!.name, fontWeight = FontWeight.SemiBold) },
            text = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Text(
                        text = previewTemplate!!.content.ifBlank { "模板内容为空" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { previewTemplate = null }) {
                    Text("关闭")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            // 标题栏 - 显示当前选中的模板
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(14.dp),
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
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "纪要模板",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = selectedTemplateName.ifBlank { "选择模板" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 展开的模板列表
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    templates.forEach { template ->
                        val isSelected = template.name == selectedTemplateName
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        onSelectTemplate(template)
                                        expanded = false
                                    },
                                    onLongClick = { previewTemplate = template }
                                ),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = template.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "长按预览内容",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactErrorBanner(
    error: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun DualActionCards(
    isRecording: Boolean,
    isTranscribing: Boolean,
    isGeneratingReport: Boolean,
    hasRecording: Boolean,
    sttEngineLabel: String,
    onRecordClick: () -> Unit,
    onGenerateReport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 录音卡片
        ActionCard(
            icon = if (isRecording) Icons.Default.MicOff else Icons.Default.Mic,
            title = when {
                isTranscribing -> "整理中"
                isRecording -> "录音中"
                else -> "录音"
            },
            subtitle = when {
                isTranscribing -> "请稍候..."
                isRecording -> "点击停止"
                else -> "点击开始"
            },
            isActive = isRecording || isTranscribing,
            isActiveColor = isRecording,
            enabled = !isTranscribing,
            onClick = onRecordClick,
            modifier = Modifier.weight(1f),
            statusChips = {
                if (sttEngineLabel.isNotBlank()) {
                    StatusChip(label = sttEngineLabel, isActive = false)
                }
            }
        )

        // 生成纪要卡片
        ActionCard(
            icon = Icons.Default.Summarize,
            title = "生成纪要",
            subtitle = if (isGeneratingReport) "后台处理生成中"
                      else if (hasRecording && !isRecording) "点击生成"
                      else if (isRecording) "录音中..."
                      else "需先录音",
            isActive = isGeneratingReport || (hasRecording && !isRecording),
            isActiveColor = false,
            enabled = hasRecording && !isRecording && !isTranscribing && !isGeneratingReport,
            onClick = onGenerateReport,
            modifier = Modifier.weight(1f),
            statusChips = null
        )
    }
}

@Composable
private fun TextModeActionCard(
    hasContent: Boolean,
    isGeneratingReport: Boolean,
    onGenerateReport: () -> Unit
) {
    ActionCard(
        icon = Icons.Default.Summarize,
        title = "生成纪要",
        subtitle = if (isGeneratingReport) "后台处理生成中" else if (hasContent) "点击生成" else "需输入内容",
        isActive = isGeneratingReport || hasContent,
        isActiveColor = false,
        enabled = hasContent && !isGeneratingReport,
        onClick = onGenerateReport,
        modifier = Modifier.fillMaxWidth(),
        statusChips = null
    )
}

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isActive: Boolean,
    isActiveColor: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusChips: (@Composable () -> Unit)? = null
) {
    val statusColor by animateColorAsState(
        targetValue = when {
            isActiveColor -> MaterialTheme.colorScheme.error
            isActive -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        },
        animationSpec = tween(300),
        label = "statusColor"
    )

    val iconScale by animateFloatAsState(
        targetValue = if (isActive) 1.05f else 1f,
        animationSpec = tween(600),
        label = "iconScale"
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        onClick = if (enabled) onClick else {{}}
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            statusColor.copy(alpha = if (enabled) 0.12f else 0.05f),
                            statusColor.copy(alpha = if (enabled) 0.04f else 0.02f)
                        )
                    )
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .scale(iconScale)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (enabled) statusColor else statusColor.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 标题
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) statusColor else statusColor.copy(alpha = 0.6f)
            )

            // 副标题
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 状态标签
            statusChips?.invoke()
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    isActive: Boolean
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isActive) MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
               else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CollapsibleTranscriptCard(
    transcript: String,
    previewMode: String,
    isRecording: Boolean
) {
    var expanded by remember { mutableStateOf(true) }
    var showFullDialog by remember { mutableStateOf(false) }
    val transcriptScrollState = rememberScrollState()

    LaunchedEffect(isRecording, transcript.isNotBlank()) {
        if (isRecording || transcript.isNotBlank()) {
            expanded = true
        }
    }

    LaunchedEffect(transcript) {
        if (transcript.isNotBlank()) {
            kotlinx.coroutines.delay(16)
            transcriptScrollState.scrollTo(transcriptScrollState.maxValue)
        }
    }

    if (showFullDialog) {
        AlertDialog(
            onDismissRequest = { showFullDialog = false },
            title = { Text("转写内容", fontWeight = FontWeight.SemiBold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                ) {
                    Text(
                        text = transcript.ifBlank { "暂无内容" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFullDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(14.dp),
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
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "转写内容",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (transcript.isNotBlank()) {
                        Text(
                            text = "$previewMode · ${transcript.length}字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 展开内容
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    if (transcript.isNotBlank()) {
                        FilledTonalButton(
                            onClick = { showFullDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.OpenInFull, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("查看完整内容", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = transcript.ifBlank {
                                if (isRecording) previewMode.ifBlank { "实时预览处理中" }
                                else "开始录音后显示转写内容"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (transcript.isBlank())
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(transcriptScrollState)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeetingImagesSection(
    attachments: List<MeetingAttachment>,
    onTakePhoto: () -> Unit,
    onPickImages: () -> Unit,
    onDelete: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("会议图片", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (attachments.isEmpty()) "可随时记录白板、投影和现场资料" else "已添加 ${attachments.size} 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = onTakePhoto) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "拍照")
                }
                IconButton(onClick = onPickImages) {
                    Icon(Icons.Default.Collections, contentDescription = "从相册添加")
                }
            }
        }

        if (attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(attachments, key = { it.id }) { attachment ->
                    val bitmap = remember(attachment.localPath) {
                        BitmapFactory.decodeFile(attachment.localPath)?.asImageBitmap()
                    }
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = attachment.displayName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.BrokenImage, contentDescription = "图片无法预览")
                        }
                        IconButton(
                            onClick = { onDelete(attachment) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(30.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "删除图片",
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun CompactTextInputCard(
    text: String,
    onTextChange: (String) -> Unit,
    onImportFile: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "文本输入",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(onClick = onImportFile) {
                    Text("导入文件", style = MaterialTheme.typography.labelSmall)
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 350.dp),
                label = { Text("请输入会议内容", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("请输入会议内容", style = MaterialTheme.typography.bodySmall) },
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun TranscriptPickerDialog(
    streamingText: String,
    backendText: String,
    onSelectStreaming: () -> Unit,
    onSelectBackend: () -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf<TranscriptSource?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择转写版本", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "请选择作为最终稿的转写文本：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TranscriptOptionItem(
                    label = "流式预览",
                    description = "实时转写",
                    isSelected = selected == TranscriptSource.STREAMING,
                    onClick = { selected = TranscriptSource.STREAMING }
                )

                TranscriptOptionItem(
                    label = "后台转写（推荐）",
                    description = "精度更高",
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
                Text("确定")
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
private fun TranscriptOptionItem(
    label: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
