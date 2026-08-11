package com.oa.automation.ui.screen.report

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oa.automation.domain.model.ExportFormat
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.reportDocumentKind
import com.oa.automation.infrastructure.export.ReportExporter
import com.oa.automation.infrastructure.export.DocxReportExporter
import com.oa.automation.infrastructure.export.ReportExportFileNaming
import com.oa.automation.infrastructure.image.OrientedImageDecoder
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudio
import com.oa.automation.ui.location.ImageLocationPermission
import com.oa.automation.ui.component.ProcessingStatusRow
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    meetingId: String,
    onNavigateBack: () -> Unit,
    onContinueRecording: (String) -> Unit,
    viewModel: ReportViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var showExportMenu by remember { mutableStateOf(false) }
    var showChatPanel by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTemplatePicker by remember { mutableStateOf(false) }
    val documentTitle = uiState.report?.documentTitle() ?: "会议纪要"
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImageImportUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val uris = pendingImageImportUris
        pendingImageImportUris = emptyList()
        if (uris.isNotEmpty()) {
            viewModel.importImages(meetingId, uris, captureLocation = permissions.values.any { it })
        }
    }

    fun importImagesWithOptionalLocation(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (ImageLocationPermission.isGranted(context)) {
            viewModel.importImages(meetingId, uris, captureLocation = true)
        } else {
            pendingImageImportUris = uris
            locationPermissionLauncher.launch(ImageLocationPermission.requestedPermissions)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        importImagesWithOptionalLocation(uris)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        if (saved) pendingCameraUri?.let { uri -> importImagesWithOptionalLocation(listOf(uri)) }
        pendingCameraUri = null
    }

    fun launchCamera() {
        val captureDirectory = File(context.cacheDir, "exports/camera").apply { mkdirs() }
        val captureFile = File(captureDirectory, "meeting_${System.currentTimeMillis()}.jpg")
        val captureUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            captureFile
        )
        pendingCameraUri = captureUri
        cameraLauncher.launch(captureUri)
    }
    LaunchedEffect(meetingId) {
        viewModel.loadReport(meetingId)
    }

    // 显示消息
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.pendingAudioShare) {
        uiState.pendingAudioShare?.let { prepared ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = prepared.mimeType
                putExtra(Intent.EXTRA_STREAM, prepared.uri)
                clipData = ClipData.newRawUri(prepared.displayName, prepared.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享会议音频"))
            viewModel.consumeAudioShare()
        }
    }

    // Delete dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("删除${documentTitle}") },
            text = { Text("确定要删除此${documentTitle}吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteReport(meetingId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("取消")
                }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    ReportReferenceFrame {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ReportReferenceTopBar(
                onNavigateBack = onNavigateBack,
                onShare = { showExportMenu = true },
                shareMenu = {
                    DropdownMenu(
                        expanded = showExportMenu,
                        onDismissRequest = { showExportMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (showChatPanel) "收起智能优化" else "智能优化") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                            enabled = uiState.report != null,
                            onClick = {
                                showExportMenu = false
                                showChatPanel = !showChatPanel
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("重新生成纪要") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            enabled = uiState.report != null,
                            onClick = {
                                showExportMenu = false
                                viewModel.regenerateWithTemplate(meetingId)
                            }
                        )
                        HorizontalDivider()
                        listOf(ExportFormat.DOCX, ExportFormat.PDF).forEach { format ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = when (format) {
                                                ExportFormat.DOCX -> Icons.Default.Description
                                                ExportFormat.PDF -> Icons.Default.PictureAsPdf
                                                else -> Icons.Default.Description
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = if (format == ExportFormat.DOCX) "导出 Word" else "导出 PDF",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                },
                                onClick = {
                                    showExportMenu = false
                                    uiState.report?.let { report ->
                                        exportScope.launch {
                                            val attachments = viewModel.attachmentsForExport(meetingId)
                                            exportReport(
                                                context,
                                                report,
                                                uiState.meetingTitle,
                                                attachments,
                                                format
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("切换纪要模板") },
                            leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                            enabled = uiState.presetTemplates.isNotEmpty(),
                            onClick = {
                                showExportMenu = false
                                showTemplatePicker = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (uiState.showTranscript) "收起转写原文" else "查看转写原文") },
                            leadingIcon = { Icon(Icons.Default.TextSnippet, contentDescription = null) },
                            enabled = uiState.transcriptText.isNotBlank(),
                            onClick = {
                                showExportMenu = false
                                viewModel.toggleTranscript()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除纪要", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showExportMenu = false
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (uiState.isGenerating) {
                            ProcessingStatusRow(
                                title = "生成会议纪要",
                                stage = uiState.generationProgressStage.ifBlank { "会议纪要处理中" },
                                actionLabel = "终止",
                                onAction = { viewModel.cancelGeneration(meetingId) },
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp
                            )
                            Text(
                                text = "正在加载报告...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                uiState.error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Text(
                            text = "生成报告失败",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = uiState.error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { viewModel.loadReport(meetingId) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("重试")
                        }
                    }
                }
                uiState.report != null -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ReportReferenceContent(
                            uiState = uiState,
                            onContinueRecording = { onContinueRecording(meetingId) },
                            onToggleTranscript = viewModel::toggleTranscript,
                            onExportTranscript = {
                                exportTranscript(context, uiState.transcriptText, documentTitle)
                            },
                            onSelectTemplate = { template -> viewModel.selectReportTemplate(template) },
                            onRegenerateWithTemplate = { viewModel.regenerateWithTemplate(meetingId) },
                            onDeleteAttachment = viewModel::deleteAttachment,
                            onAddImages = { galleryLauncher.launch("image/*") },
                            onCaptureImage = ::launchCamera,
                            onRefreshAudio = { viewModel.refreshArchivedAudio(meetingId) },
                            onPrepareAudioPlayback = viewModel::prepareArchivedAudioPlayback,
                            onShareAudio = viewModel::shareArchivedAudio,
                            onDeleteAudio = viewModel::deleteArchivedAudio,
                            onShare = { showExportMenu = true },
                            showTemplatePicker = showTemplatePicker,
                            onShowTemplatePicker = { showTemplatePicker = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(bottom = if (showChatPanel) 0.dp else 0.dp)
                        )

                        // 聊天面板
                        if (showChatPanel) {
                            ChatPanel(
                                messages = uiState.chatMessages,
                                input = uiState.chatInput,
                                isLoading = uiState.isChatLoading,
                                error = uiState.chatError,
                                onInputChange = viewModel::updateChatInput,
                                onSend = viewModel::sendMessage,
                                onClear = viewModel::clearChat,
                                onMinimize = { showChatPanel = false },
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (uiState.generationCancelled) {
                                        Icons.Default.Close
                                    } else {
                                        Icons.Default.Description
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = if (uiState.generationCancelled) {
                                "纪要生成已终止"
                            } else {
                                "暂无${documentTitle}"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (uiState.generationCancelled) {
                            Button(onClick = { viewModel.regenerateReport(meetingId) }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("重新生成")
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
private fun ReportContent(
    report: Report,
    onRegenerate: () -> Unit,
    onContinueRecording: () -> Unit,
    transcriptText: String = "",
    showTranscript: Boolean = false,
    onToggleTranscript: () -> Unit = {},
    onExportTranscript: (String) -> Unit = {},
    presetTemplates: List<PresetReportTemplate> = emptyList(),
    currentTemplateName: String = "",
    onSelectTemplate: (PresetReportTemplate) -> Unit = {},
    onRegenerateWithTemplate: () -> Unit = {},
    attachments: List<MeetingAttachment> = emptyList(),
    onDeleteAttachment: (MeetingAttachment) -> Unit = {},
    archivedAudio: List<ArchivedMeetingAudio> = emptyList(),
    isLoadingAudio: Boolean = false,
    preparingAudioShareId: String? = null,
    onRefreshAudio: () -> Unit = {},
    onShareAudio: (ArchivedMeetingAudio) -> Unit = {},
    showTemplatePicker: Boolean = false,
    onShowTemplatePicker: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ReportHeaderCard(
            report = report,
            onRegenerate = onRegenerate,
            onContinueRecording = onContinueRecording,
            presetTemplates = presetTemplates,
            currentTemplateName = currentTemplateName,
            onSelectTemplate = onSelectTemplate,
            onRegenerateWithTemplate = onRegenerateWithTemplate,
            showTemplatePicker = showTemplatePicker,
            onShowTemplatePicker = onShowTemplatePicker
        )

        if (transcriptText.isNotBlank()) {
            TranscriptSectionCard(
                transcriptText = transcriptText,
                showTranscript = showTranscript,
                onToggle = onToggleTranscript,
                onExport = { onExportTranscript(transcriptText) }
            )
        }

        if (attachments.isNotEmpty()) {
            MeetingImageAttachments(
                attachments = attachments,
                onDelete = onDeleteAttachment
            )
        }

        MeetingAudioArchiveSection(
            items = archivedAudio,
            isLoading = isLoadingAudio,
            preparingShareId = preparingAudioShareId,
            onRefresh = onRefreshAudio,
            onShare = onShareAudio
        )

        if (report.rawContent.isNotBlank()) {
            MarkdownReportCard(content = report.rawContent)
        } else {
            StructuredReportContent(report = report)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun MeetingAudioArchiveSection(
    items: List<ArchivedMeetingAudio>,
    isLoading: Boolean,
    preparingShareId: String?,
    onRefresh: () -> Unit,
    onShare: (ArchivedMeetingAudio) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    imageVector = Icons.Default.AudioFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text("会议音频", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onRefresh, enabled = !isLoading, modifier = Modifier.size(36.dp)) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新会议音频", modifier = Modifier.size(18.dp))
                }
            }
        }

        if (items.isEmpty() && !isLoading) {
            Text(
                text = "暂无服务器归档音频",
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatArchivedAudioTime(audio.createdAt),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = listOfNotNull(
                                    audio.durationSec?.let(::formatAudioDuration),
                                    formatFileSize(audio.bytes)
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onShare(audio) },
                            enabled = preparingShareId == null,
                            modifier = Modifier.size(40.dp)
                        ) {
                            if (preparingShareId == audio.id) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Share, contentDescription = "分享会议音频")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeetingImageAttachments(
    attachments: List<MeetingAttachment>,
    onDelete: (MeetingAttachment) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Collections,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text("会议图片", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(attachments, key = { it.id }) { attachment ->
                AttachmentThumbnail(attachment = attachment, onDelete = { onDelete(attachment) })
            }
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    attachment: MeetingAttachment,
    onDelete: () -> Unit
) {
    val bitmap = remember(attachment.localPath) {
        OrientedImageDecoder.decode(File(attachment.localPath), maximumDimension = 512)
            ?.asImageBitmap()
    }
    Column(modifier = Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = attachment.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
            ) {
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "删除图片",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Text(
            text = attachment.displayName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReportHeaderCard(
    report: Report,
    onRegenerate: () -> Unit,
    onContinueRecording: () -> Unit,
    presetTemplates: List<PresetReportTemplate> = emptyList(),
    currentTemplateName: String = "",
    onSelectTemplate: (PresetReportTemplate) -> Unit = {},
    onRegenerateWithTemplate: () -> Unit = {},
    showTemplatePicker: Boolean = false,
    onShowTemplatePicker: (Boolean) -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status badge
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        text = "已生成完成",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Title
            Text(
                text = report.templateName.ifBlank { "会议纪要" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Time info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatReportTime(report.generatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Template selector chip
            if (presetTemplates.isNotEmpty()) {
                var showPreviewDialog by remember { mutableStateOf(false) }
                var previewTemplate by remember { mutableStateOf<PresetReportTemplate?>(null) }

                // Template picker dialog
                if (showTemplatePicker) {
                    TemplatePickerDialog(
                        templates = presetTemplates,
                        selectedTemplateName = currentTemplateName,
                        onSelect = { template ->
                            onSelectTemplate(template)
                            onShowTemplatePicker(false)
                        },
                        onPreview = { template ->
                            previewTemplate = template
                            showPreviewDialog = true
                        },
                        onDismiss = { onShowTemplatePicker(false) }
                    )
                }

                // Template preview dialog
                if (showPreviewDialog && previewTemplate != null) {
                    TemplatePreviewDialog(
                        template = previewTemplate!!,
                        onDismiss = { showPreviewDialog = false }
                    )
                }

                Surface(
                    onClick = { onShowTemplatePicker(true) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "切换模板",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onContinueRecording,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("继续录音", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = onRegenerateWithTemplate,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重新生成", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TemplatePickerDialog(
    templates: List<PresetReportTemplate>,
    selectedTemplateName: String,
    onSelect: (PresetReportTemplate) -> Unit,
    onPreview: (PresetReportTemplate) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("选择纪要模板", fontWeight = FontWeight.SemiBold)
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(templates) { template ->
                    val isSelected = template.name == selectedTemplateName
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onSelect(template) },
                                onLongClick = { onPreview(template) }
                            ),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = template.name,
                                    style = MaterialTheme.typography.titleSmall,
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
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        shape = MaterialTheme.shapes.large
    )
}

private fun templateSelectionHint(template: PresetReportTemplate): String {
    return template.subtitle
        .takeIf { it.isNotBlank() }
        ?.let { "$it · 长按预览" }
        ?: "长按预览内容"
}

@Composable
internal fun TemplatePreviewDialog(
    template: PresetReportTemplate,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(template.name, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Text(
                    text = template.content.ifBlank { "模板内容为空" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun MarkdownReportCard(content: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionHeader(
                icon = Icons.Default.Article,
                title = "正文内容",
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            content.lines().forEach { line ->
                MarkdownLine(line)
            }
        }
    }
}

@Composable
private fun MarkdownLine(line: String) {
    val trimmed = line.trim()
    val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)
    when {
        heading != null -> {
            val level = heading.groupValues[1].length
            val headingStyle = when (level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            }
            Text(
                text = cleanMarkdownDisplayText(heading.groupValues[2]),
                style = headingStyle,
                fontWeight = if (level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                color = if (level == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = if (level == 1) 4.dp else 8.dp)
            )
        }
        trimmed.startsWith(">") -> Text(
            text = cleanMarkdownDisplayText(trimmed.removePrefix(">")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        trimmed.startsWith("|") -> {
            val cells = trimmed.trim('|').split('|').map { cleanMarkdownDisplayText(it) }
            if (cells.all { it.matches(Regex(":?-{3,}:?")) }) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            } else {
                Text(
                    text = cells.joinToString("    "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        trimmed.isBlank() -> Spacer(modifier = Modifier.height(4.dp))
        trimmed == "---" -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        else -> Text(
            text = cleanMarkdownDisplayText(line),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun cleanMarkdownDisplayText(text: String): String = text
    .replace(Regex("^\\s*[-*+]\\s+"), "")
    .replace(Regex("^\\s*\\d+[.)、]\\s+"), "")
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("__(.+?)__"), "$1")
    .replace("`", "")
    .trim()

@Composable
private fun StructuredReportContent(report: Report) {
    // Summary
    ReportSectionCard(
        icon = Icons.Default.Summarize,
        title = "会议概述",
        iconTint = MaterialTheme.colorScheme.primary
    ) {
        Text(
            text = report.summary.ifEmpty { "暂无概述" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 24.sp
        )
    }

    // Key Points
    if (report.keyPoints.isNotEmpty()) {
        ReportSectionCard(
            icon = Icons.Default.Lightbulb,
            title = "关键要点",
            iconTint = Color(0xFFF59E0B)
        ) {
            report.keyPoints.forEachIndexed { index, point ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B)
                            )
                        }
                    }
                    Text(
                        text = point,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // Decisions
    if (report.decisions.isNotEmpty()) {
        ReportSectionCard(
            icon = Icons.Default.Gavel,
            title = "决策事项",
            iconTint = MaterialTheme.colorScheme.secondary
        ) {
            report.decisions.forEach { decision ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = decision,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // Tasks
    if (report.tasks.isNotEmpty()) {
        ReportSectionCard(
            icon = Icons.Default.Assignment,
            title = "待办任务",
            iconTint = MaterialTheme.colorScheme.primary
        ) {
            report.tasks.forEach { task ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (task.completed) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (task.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (task.assignee != null || task.due != null) {
                            Text(
                                text = listOfNotNull(
                                    task.assignee?.let { "负责人: $it" },
                                    task.due?.let { "截止: $it" }
                                ).joinToString(" | "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Action Items
    if (report.actionItems.isNotEmpty()) {
        ReportSectionCard(
            icon = Icons.Default.PlaylistAddCheck,
            title = "行动项",
            iconTint = MaterialTheme.colorScheme.tertiary
        ) {
            report.actionItems.forEach { item ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportSectionCard(
    icon: ImageVector,
    title: String,
    iconTint: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(icon = icon, title = title, color = iconTint)
            content()
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = color.copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = color
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TranscriptSectionCard(
    transcriptText: String,
    showTranscript: Boolean,
    onToggle: () -> Unit,
    onExport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(
                    icon = Icons.Default.Mic,
                    title = "转写原文",
                    color = MaterialTheme.colorScheme.tertiary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showTranscript) {
                        IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.IosShare,
                                contentDescription = "导出",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = onToggle) {
                        Text(
                            if (showTranscript) "收起" else "展开查看",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showTranscript,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = transcriptText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatPanel(
    messages: List<ChatMessageUi>,
    input: String,
    isLoading: Boolean,
    error: String?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 350.dp)
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = "AI 润色",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = onClear) {
                        Text("清空", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "收起",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Messages
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageItem(message = message)
                }
                if (isLoading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "AI 正在思考...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Error
            if (error != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = {
                        Text(
                            "输入润色要求...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = input.isNotBlank() && !isLoading,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageItem(message: ChatMessageUi) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isUser) "我" else "AI 助手",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

private suspend fun exportReport(
    context: Context,
    report: Report,
    meetingTitle: String,
    attachments: List<MeetingAttachment>,
    format: ExportFormat
) {
    try {
        val exportFile = withContext(Dispatchers.IO) {
            when (format) {
                ExportFormat.MARKDOWN, ExportFormat.TXT -> {
                    val content = when (format) {
                        ExportFormat.MARKDOWN -> ReportExporter.exportToMarkdown(report)
                        ExportFormat.TXT -> ReportExporter.exportToText(report)
                        else -> throw UnsupportedOperationException("不支持的格式")
                    }
                    val fileName = ReportExportFileNaming.build(
                        report,
                        meetingTitle,
                        format.extension
                    )
                    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                    File(exportDir, fileName).apply {
                        writeText(content, Charsets.UTF_8)
                    }
                }
                ExportFormat.DOCX -> DocxReportExporter.export(
                    context,
                    report,
                    attachments,
                    meetingTitle
                )
                ExportFormat.PDF -> {
                    ReportExporter.exportToPdf(context, report, attachments, meetingTitle)
                }
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, report.documentTitle())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享${report.documentTitle()}"))
    } catch (e: Exception) {
        Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun exportTranscript(context: Context, transcriptText: String, title: String) {
    try {
        val fileName = "transcript_${title}_${System.currentTimeMillis()}.txt"
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val exportFile = File(exportDir, fileName)
        exportFile.writeText(transcriptText, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "转写文本 - $title")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享转写文本"))
    } catch (e: Exception) {
        Toast.makeText(context, "导出转写失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun formatReportTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun formatArchivedAudioTime(value: String): String = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date.from(Instant.parse(value)))
}.getOrDefault(value)

private fun formatAudioDuration(seconds: Double): String {
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

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

private fun Report.documentTitle(): String = reportDocumentKind(templateName).documentTitle
