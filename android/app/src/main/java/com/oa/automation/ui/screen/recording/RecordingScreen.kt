package com.oa.automation.ui.screen.recording

import android.content.ClipData
import android.content.Intent
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.STTLanguage
import com.oa.automation.domain.model.TencentAsrQuotaWarningLevel
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudio
import com.oa.automation.infrastructure.image.OrientedImageDecoder
import com.oa.automation.infrastructure.textimport.ExternalTextSource
import com.oa.automation.ui.location.ImageLocationPermission
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

/**
 * RecordingScreen - 精简版录音页面
 * 设计原则：核心操作突出，次要信息折叠
 */
enum class RecordingLaunchAction {
    STANDARD,
    START_RECORDING,
    OPEN_IMPORT;

    companion object {
        fun from(value: String): RecordingLaunchAction = when (value) {
            "IMPORT_TEXT_FILE", "IMPORT_AUDIO_FILE" -> OPEN_IMPORT
            else -> entries.firstOrNull { it.name == value } ?: STANDARD
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    meetingId: String,
    launchAction: RecordingLaunchAction = RecordingLaunchAction.STANDARD,
    onNavigateBack: () -> Unit,
    onNavigateToReport: (String) -> Unit,
    viewModel: RecordingViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImageImportUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingAudioSave by remember { mutableStateOf<PendingMeetingAudioExport?>(null) }
    var launchActionConsumed by rememberSaveable(meetingId, launchAction) {
        mutableStateOf(false)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val uris = pendingImageImportUris
        pendingImageImportUris = emptyList()
        if (uris.isNotEmpty()) {
            viewModel.importImages(uris, captureLocation = permissions.values.any { it })
        }
    }

    fun importImagesWithOptionalLocation(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (ImageLocationPermission.isGranted(context)) {
            viewModel.importImages(uris, captureLocation = true)
        } else {
            pendingImageImportUris = uris
            locationPermissionLauncher.launch(ImageLocationPermission.requestedPermissions)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> importImagesWithOptionalLocation(uris) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        if (saved) pendingCameraUri?.let { importImagesWithOptionalLocation(listOf(it)) }
        pendingCameraUri = null
    }
    val audioSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/*")
    ) { destination ->
        val pending = pendingAudioSave
        pendingAudioSave = null
        if (destination != null && pending != null) {
            viewModel.savePreparedAudio(pending, destination)
        }
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

    fun navigateBackAndStopRecording() {
        viewModel.handleScreenExit()
        onNavigateBack()
    }

    BackHandler(onBack = ::navigateBackAndStopRecording)

    LaunchedEffect(meetingId) {
        viewModel.loadMeeting(meetingId)
    }

    LaunchedEffect(uiState.reportReadyToOpen) {
        if (uiState.reportReadyToOpen) {
            viewModel.consumeReportNavigation()
            onNavigateToReport(meetingId)
        }
    }

    LaunchedEffect(uiState.pendingAudioExport) {
        uiState.pendingAudioExport?.let { export ->
            when (export.action) {
                MeetingAudioExportAction.SAVE -> {
                    pendingAudioSave = export
                    viewModel.consumeAudioExport()
                    audioSaveLauncher.launch(export.prepared.displayName)
                }

                MeetingAudioExportAction.SHARE -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = export.prepared.mimeType
                        putExtra(Intent.EXTRA_STREAM, export.prepared.uri)
                        clipData = ClipData.newRawUri(
                            export.prepared.displayName,
                            export.prepared.uri
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享会议音频"))
                    viewModel.consumeAudioExport()
                }
            }
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
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(viewModel::importTextDocument) }
    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(viewModel::importAudioDocument) }

    LaunchedEffect(meetingId, launchAction, uiState.meetingTitle, launchActionConsumed) {
        if (launchActionConsumed || uiState.meetingTitle.isBlank()) return@LaunchedEffect
        when (launchAction) {
            RecordingLaunchAction.STANDARD -> Unit
            RecordingLaunchAction.START_RECORDING -> viewModel.startRecording()
            RecordingLaunchAction.OPEN_IMPORT -> viewModel.switchToImportMode()
        }
        launchActionConsumed = true
    }

    val displayedUiState = if (
        launchAction == RecordingLaunchAction.OPEN_IMPORT && !launchActionConsumed
    ) {
        uiState.copy(inputMode = InputMode.IMPORT)
    } else {
        uiState
    }

    RecordingReferenceScaffold(
        uiState = displayedUiState,
        onNavigateBack = ::navigateBackAndStopRecording,
        onNavigateToReport = { onNavigateToReport(meetingId) },
        onTitleChange = viewModel::onMeetingTitleChange,
        onSaveTitle = viewModel::saveMeetingTitle,
        onSelectTemplate = viewModel::selectReportTemplate,
        onSttEngineSelected = viewModel::switchSttEngine,
        onSttLanguageSelected = viewModel::switchSttLanguage,
        onSwitchToVoice = viewModel::switchToVoiceMode,
        onSwitchToImport = viewModel::switchToImportMode,
        onStartRecording = viewModel::startRecording,
        onStopRecording = viewModel::stopRecording,
        onTogglePause = viewModel::togglePauseRecording,
        onAddMarker = viewModel::addRecordingMarker,
        onStartJourney = viewModel::startJourney,
        onSaveCurrentJourneyStage = viewModel::saveCurrentJourneyStage,
        onPauseJourney = viewModel::pauseJourney,
        onContinueJourney = viewModel::continueJourney,
        onGenerateStageDraft = viewModel::generateLatestStageDraft,
        onOpenStageDraft = viewModel::openLatestStageDraft,
        onSaveStageDraftContent = viewModel::saveStageDraftContent,
        onConfirmStageDraft = viewModel::confirmStageDraft,
        onDismissStageDraftEditor = viewModel::dismissStageDraftEditor,
        onGenerateJourneyEdition = viewModel::generateJourneyEdition,
        onOpenJourneyEdition = viewModel::openLatestJourneyEdition,
        onSaveJourneyEditionContent = viewModel::saveJourneyEditionContent,
        onConfirmJourneyEdition = viewModel::confirmJourneyEdition,
        onDismissJourneyEditionEditor = viewModel::dismissJourneyEditionEditor,
        onCreatePublishedPost = viewModel::createPublishedPostSnapshot,
        onOpenPublishedPost = viewModel::openPublishedPostReview,
        onSavePublishedPostReview = viewModel::savePublishedPostReview,
        onMarkPublishedPostReady = viewModel::markPublishedPostReady,
        onWithdrawPublishedPost = viewModel::withdrawPublishedPost,
        onDismissPublishedPostReview = viewModel::dismissPublishedPostReview,
        onAbandonRecording = viewModel::abandonRecording,
        onGenerateReport = viewModel::generateReport,
        onCancelTranscription = viewModel::cancelTranscription,
        onCancelReport = viewModel::cancelReportGeneration,
        onTextChange = viewModel::updateManualText,
        onPasteText = viewModel::importClipboardText,
        onOpenExternalTextSource = viewModel::openExternalTextSource,
        onImportTextFile = { filePickerLauncher.launch("text/*") },
        onImportAudioFile = { audioPickerLauncher.launch("audio/*") },
        onGenerateFromImport = viewModel::generateFromImport,
        onTakePhoto = ::launchCamera,
        onPickImages = { galleryLauncher.launch("image/*") },
        onDeleteAttachment = viewModel::deleteAttachment,
        onRefreshAudio = viewModel::refreshArchivedAudio,
        onSaveAudio = viewModel::saveArchivedAudio,
        onShareAudio = viewModel::shareArchivedAudio,
        onDismissError = viewModel::clearError,
        onSelectStreamingTranscript = viewModel::selectStreamingTranscript,
        onSelectBackendTranscript = viewModel::selectBackendTranscript,
        onDismissTranscriptPicker = viewModel::dismissTranscriptPicker
    )
}

@Composable
internal fun MeetingAudioExportCard(
    items: List<ArchivedMeetingAudio>,
    isLoading: Boolean,
    busyAudioId: String?,
    statusMessage: String,
    isTranscribing: Boolean,
    onRefresh: () -> Unit,
    onSave: (ArchivedMeetingAudio) -> Unit,
    onShare: (ArchivedMeetingAudio) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "会议音频",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading && busyAudioId == null,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新会议音频",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                Text(
                    text = when {
                        isLoading -> "正在查找服务器归档音频"
                        isTranscribing -> "正在准备会议音频"
                        else -> "暂无可导出的会议音频"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                items.forEach { audio ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = formatRecordingAudioTime(audio.createdAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = listOfNotNull(
                                        audio.durationSec?.let(::formatRecordingAudioDuration),
                                        formatRecordingAudioSize(audio.bytes)
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (busyAudioId == audio.id) {
                                Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            } else {
                                IconButton(
                                    onClick = { onSave(audio) },
                                    enabled = busyAudioId == null,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.SaveAlt, contentDescription = "保存会议音频")
                                }
                                IconButton(
                                    onClick = { onShare(audio) },
                                    enabled = busyAudioId == null,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "分享会议音频")
                                }
                            }
                        }
                    }
                }
            }

            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (statusMessage.contains("失败")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

@Composable
private fun CompactInputModeToggle(
    inputMode: InputMode,
    onSwitchToVoice: () -> Unit,
    onSwitchToImport: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
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
                text = "导入",
                icon = Icons.Default.FolderOpen,
                selected = inputMode == InputMode.IMPORT,
                onClick = onSwitchToImport,
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
        shape = MaterialTheme.shapes.small,
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
                    shape = RoundedCornerShape(8.dp),
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
            shape = MaterialTheme.shapes.large
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
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
                                    },
                                    onLongClick = { previewTemplate = template }
                                ),
                            shape = RoundedCornerShape(8.dp),
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
                                        text = templateSelectionHint(template),
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

private fun templateSelectionHint(template: PresetReportTemplate): String {
    return template.subtitle
        .takeIf { it.isNotBlank() }
        ?.let { "$it · 长按预览" }
        ?: "长按预览内容"
}

@Composable
private fun TencentQuotaWarningBanner(
    warning: String,
    warningLevel: TencentAsrQuotaWarningLevel
) {
    val critical = warningLevel == TencentAsrQuotaWarningLevel.CRITICAL ||
        warningLevel == TencentAsrQuotaWarningLevel.EXHAUSTED
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = if (critical) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (critical) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun CompactErrorBanner(
    error: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
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
internal fun RuntimeServiceSwitcher(
    sttEngineType: STTEngineType,
    sttLanguage: STTLanguage,
    isSwitchingStt: Boolean,
    isSwitchingLanguage: Boolean,
    onSttEngineSelected: (STTEngineType) -> Unit,
    onSttLanguageSelected: (STTLanguage) -> Unit
) {
    var sttMenuExpanded by remember { mutableStateOf(false) }
    val sttLabel = if (sttEngineType == STTEngineType.TENCENT_HYBRID) {
        "智悟增强云模型"
    } else {
        "智悟本地模型"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Hub,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "实时识别",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (isSwitchingStt || isSwitchingLanguage) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                STTLanguage.entries.forEach { language ->
                    val selected = language == sttLanguage
                    OutlinedButton(
                        onClick = { onSttLanguageSelected(language) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        enabled = !isSwitchingLanguage,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            }
                        )
                    ) {
                        Text(language.displayName, maxLines = 1)
                        if (selected) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { sttMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = !isSwitchingStt,
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(
                        if (sttEngineType == STTEngineType.TENCENT_HYBRID) {
                            Icons.Default.Cloud
                        } else {
                            Icons.Default.Speed
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(sttLabel, maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = sttMenuExpanded,
                    onDismissRequest = { sttMenuExpanded = false }
                ) {
                    listOf(
                        STTEngineType.FASTER_WHISPER to "智悟本地模型",
                        STTEngineType.TENCENT_HYBRID to "智悟增强云模型"
                    ).forEach { (engine, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = {
                                Icon(
                                    if (engine == STTEngineType.TENCENT_HYBRID) {
                                        Icons.Default.Cloud
                                    } else {
                                        Icons.Default.Speed
                                    },
                                    contentDescription = null
                                )
                            },
                            trailingIcon = {
                                if (
                                    engine == sttEngineType ||
                                    engine == STTEngineType.FASTER_WHISPER &&
                                    sttEngineType == STTEngineType.SENSE_VOICE
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                sttMenuExpanded = false
                                onSttEngineSelected(engine)
                            }
                        )
                    }
                }
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
            statusChips = null
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
        modifier = modifier.border(
            width = 1.dp,
            color = statusColor.copy(alpha = if (enabled) 0.55f else 0.2f),
            shape = MaterialTheme.shapes.medium
        ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = if (enabled) onClick else {{}}
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .scale(iconScale)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (enabled) statusColor else statusColor.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(21.dp)
                )
            }

            // 标题
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
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
                    if (transcript.isBlank()) {
                        Text(
                            text = "暂无内容",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = transcript,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
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
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
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
                            shape = RoundedCornerShape(8.dp)
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
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        if (transcript.isBlank()) {
                            Text(
                                text =
                                if (isRecording) previewMode.ifBlank { "实时预览处理中" }
                                else "开始录音后显示转写内容",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            SelectionContainer {
                                Text(
                                    text = transcript,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
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
    }
}

@Composable
internal fun MeetingImagesSection(
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
                        OrientedImageDecoder.decode(File(attachment.localPath), maximumDimension = 512)
                            ?.asImageBitmap()
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
    importStatus: String,
    externalSources: List<ExternalTextSource>,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onOpenExternalSource: (ExternalTextSource) -> Unit,
    onImportFile: () -> Unit
) {
    var importMenuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onPaste) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("粘贴", style = MaterialTheme.typography.labelSmall)
                    }
                    Box {
                        IconButton(onClick = { importMenuExpanded = true }) {
                            Icon(Icons.Default.Apps, contentDescription = "选择导入来源")
                        }
                        DropdownMenu(
                            expanded = importMenuExpanded,
                            onDismissRequest = { importMenuExpanded = false }
                        ) {
                            externalSources.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(source.label) },
                                    leadingIcon = {
                                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                                    },
                                    onClick = {
                                        importMenuExpanded = false
                                        onOpenExternalSource(source)
                                    }
                                )
                            }
                            if (externalSources.isNotEmpty()) HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("本地文本文件") },
                                leadingIcon = {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                },
                                onClick = {
                                    importMenuExpanded = false
                                    onImportFile()
                                }
                            )
                        }
                    }
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
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )
            if (importStatus.isNotBlank()) {
                Text(
                    text = importStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatRecordingAudioTime(value: String): String = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(Date.from(Instant.parse(value)))
}.getOrDefault(value)

private fun formatRecordingAudioDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remainingSeconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, remainingSeconds)
    } else {
        "%02d:%02d".format(Locale.US, minutes, remainingSeconds)
    }
}

private fun formatRecordingAudioSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
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
        shape = RoundedCornerShape(8.dp),
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
