package com.oa.automation.ui.screen.report

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Expand
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.MeetingMode
import com.oa.automation.domain.model.ForumParticipant
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.JourneyStageStatus
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.ReportWorkspaceBlocks
import com.oa.automation.domain.model.RiskItem
import com.oa.automation.domain.model.extractRiskItems
import com.oa.automation.domain.model.InteractionSignal
import com.oa.automation.domain.model.extractInteractionSignals
import com.oa.automation.domain.model.normalizeReportWorkspaceOrder
import com.oa.automation.domain.model.ReportTitleResolver
import com.oa.automation.domain.model.isForumMeetingTemplate
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudio
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudioPlaybackSource
import com.oa.automation.infrastructure.export.ReportDocumentFormatter
import com.oa.automation.infrastructure.image.OrientedImageDecoder
import com.oa.automation.ui.component.FlowingProgressBorder
import com.oa.automation.ui.theme.LocalAppIsDarkTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The coordinates below are calibrated from redesigned_meeting_summary_ui.png
 * after removing the device frame. On a 360 x 796 reference viewport the
 * sequence is: title 95, audio 140, images 250, report 445, controls 702.
 */
private val ReferenceInk = Color(0xFFF7F8FF)
private val ReferenceMuted = Color(0xFFD2E4F4)
private val ReferenceBorder = Color(0xFF8CC8FF).copy(alpha = 0.32f)
private val ReferenceGlassTop = Color(0xFFB9DDF5).copy(alpha = 0.16f)
private val ReferenceGlassBottom = Color(0xFF0F3554).copy(alpha = 0.20f)
private val ReferenceLavender = Color(0xFF8CC8FF)
private val ReferenceSky = Color(0xFF60CDFF)
private val ReferencePink = Color(0xFF3A96DD)
private val ReferenceMint = Color(0xFF99D6FF)
private val ReferenceStatusBar = Color(0xFF0F3554)

private data class ReferenceMetrics(
    val horizontalInset: Dp,
    val topGap: Dp,
    val itemGap: Dp,
    val headerHeight: Dp,
    val audioHeight: Dp,
    val imageHeight: Dp,
    val reportHeight: Dp,
    val controlHeight: Dp,
    val controlBottom: Dp
)

private fun referenceMetrics(maxHeight: Dp) = if (maxHeight < 700.dp) {
    ReferenceMetrics(10.dp, 4.dp, 6.dp, 44.dp, 94.dp, 178.dp, 224.dp, 68.dp, 12.dp)
} else {
    ReferenceMetrics(10.dp, 5.dp, 7.dp, 47.dp, 104.dp, 204.dp, 249.dp, 74.dp, 18.dp)
}

@Composable
internal fun ReportReferenceFrame(content: @Composable () -> Unit) {
    ReferenceSystemBars()
    Box(modifier = Modifier.fillMaxSize()) {
        ReferenceBackdrop(modifier = Modifier.fillMaxSize())
        content()
    }
}

@Composable
private fun ReferenceSystemBars() {
    val view = LocalView.current
    val restoreLightIcons = !LocalAppIsDarkTheme.current
    DisposableEffect(view, restoreLightIcons) {
        val window = (view.context as? Activity)?.window
        val previousStatus = window?.statusBarColor
        val previousNavigation = window?.navigationBarColor
        if (window != null) {
            window.statusBarColor = ReferenceStatusBar.toArgb()
            window.navigationBarColor = Color(0xFF0A243A).toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose {
            if (window != null && previousStatus != null && previousNavigation != null) {
                window.statusBarColor = previousStatus
                window.navigationBarColor = previousNavigation
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = restoreLightIcons
                    isAppearanceLightNavigationBars = restoreLightIcons
                }
            }
        }
    }
}

@Composable
private fun ReferenceBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF0A243A),
                    Color(0xFF123B5D),
                    Color(0xFF0F4C75),
                    Color(0xFF163A5F),
                    Color(0xFF0B1F33)
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF60CDFF).copy(alpha = .32f), Color.Transparent),
                center = Offset(size.width * .47f, size.height * .22f),
                radius = size.width * .58f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF3A96DD).copy(alpha = .24f), Color.Transparent),
                center = Offset(size.width * 1.02f, size.height * .60f),
                radius = size.width * .72f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF8CC8FF).copy(alpha = .22f), Color.Transparent),
                center = Offset(size.width * .09f, size.height * .79f),
                radius = size.width * .68f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF106EBE).copy(alpha = .20f), Color.Transparent),
                center = Offset(size.width * .52f, size.height * .93f),
                radius = size.width * .64f
            )
        )
    }
}

@Composable
internal fun ReportReferenceTopBar(
    onNavigateBack: () -> Unit,
    onShare: () -> Unit,
    shareMenu: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        ReferenceCircleButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回上一页",
            modifier = Modifier.align(Alignment.CenterStart),
            onClick = onNavigateBack
        )
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            ReferenceCircleButton(
                icon = Icons.Default.MoreHoriz,
                contentDescription = "更多操作",
                onClick = onShare
            )
            shareMenu()
        }
    }
}

@Composable
private fun ReferenceCircleButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.size(34.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = .18f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .22f))
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription, tint = ReferenceInk, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
internal fun ReportReferenceContent(
    uiState: ReportUiState,
    onToggleTranscript: () -> Unit,
    onExportTranscript: () -> Unit,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    onDeleteAttachment: (MeetingAttachment) -> Unit,
    onAddImages: () -> Unit,
    onCaptureImage: () -> Unit,
    onRefreshAudio: () -> Unit,
    onPrepareAudioPlayback: suspend (ArchivedMeetingAudio) -> Result<ArchivedMeetingAudioPlaybackSource>,
    onShareAudio: (ArchivedMeetingAudio) -> Unit,
    onDeleteAudio: (ArchivedMeetingAudio) -> Unit,
    onWorkspaceOrderChanged: (List<String>) -> Unit,
    onPreviewFullReport: () -> Unit = {},
    showTemplatePicker: Boolean,
    onShowTemplatePicker: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val report = uiState.report ?: return
    val templateName = report.templateName.ifBlank { uiState.reportTemplate.selectedName }
    val isStudyReport = templateName.contains("研学") ||
        templateName.contains("参观考察") ||
        templateName.contains("游记") ||
        templateName.contains("文旅") ||
        uiState.journeyStages.isNotEmpty()
    val isForumReport = templateName.isForumMeetingTemplate()
    val riskItems = remember(report.rawContent) { extractRiskItems(report.rawContent) }
    val interactionSignals = remember(report.rawContent) { extractInteractionSignals(report.rawContent) }
    var showTemplatePreview by remember { mutableStateOf(false) }
    var previewTemplate by remember { mutableStateOf<PresetReportTemplate?>(null) }

    if (showTemplatePicker) {
        TemplatePickerDialog(
            templates = uiState.presetTemplates,
            selectedTemplateName = uiState.reportTemplate.selectedName,
            onSelect = {
                onSelectTemplate(it)
                onShowTemplatePicker(false)
            },
            onPreview = {
                previewTemplate = it
                showTemplatePreview = true
            },
            onDismiss = { onShowTemplatePicker(false) }
        )
    }
    if (showTemplatePreview) {
        previewTemplate?.let { TemplatePreviewDialog(template = it, onDismiss = { showTemplatePreview = false }) }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val metrics = referenceMetrics(maxHeight)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = metrics.horizontalInset,
                top = metrics.topGap,
                end = metrics.horizontalInset,
                bottom = 18.dp
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.itemGap)
        ) {
            item {
                ReferenceSummaryHeader(
                    title = ReportTitleResolver.resolve(report, uiState.meetingTitle),
                    createdAt = if (isStudyReport) {
                        uiState.journeyStartedAt.takeIf { it > 0L }
                            ?: uiState.meetingCreatedAt.takeIf { it > 0L }
                            ?: report.generatedAt
                    } else {
                        uiState.meetingCreatedAt.takeIf { it > 0L } ?: report.generatedAt
                    },
                    endedAt = uiState.journeyEndedAt.takeIf { isStudyReport },
                    durationMs = uiState.archivedAudio.firstOrNull()?.durationSec
                            ?.times(1_000.0)
                            ?.toLong()
                            ?.takeIf { it > 0L }
                        ?: uiState.meetingDurationMs,
                    height = metrics.headerHeight
                )
            }
            if (isStudyReport) {
                item {
                    StudyJourneyArticleExperience(
                        report = report,
                        meetingTitle = uiState.meetingTitle,
                        attachments = uiState.attachments,
                        journeyStages = uiState.journeyStages,
                        isProcessing = uiState.isGenerating,
                        onDeleteAttachment = onDeleteAttachment,
                        onAddImages = onAddImages,
                        onCaptureImage = onCaptureImage
                    )
                }
                item {
                    ReferenceAudioCard(
                        audio = uiState.archivedAudio.firstOrNull(),
                        isLoading = uiState.isLoadingAudio,
                        height = metrics.audioHeight,
                        onRefresh = onRefreshAudio,
                        onPreparePlayback = onPrepareAudioPlayback,
                        onShare = { uiState.archivedAudio.firstOrNull()?.let(onShareAudio) },
                        onDelete = { uiState.archivedAudio.firstOrNull()?.let(onDeleteAudio) }
                    )
                }
            } else {
                item {
                    StructuredReportWorkspace(
                        report = report,
                        uiState = uiState,
                        isForumReport = isForumReport,
                        riskItems = riskItems,
                        interactionSignals = interactionSignals,
                        metrics = metrics,
                        onDeleteAttachment = onDeleteAttachment,
                        onAddImages = onAddImages,
                        onCaptureImage = onCaptureImage,
                        onRefreshAudio = onRefreshAudio,
                        onPrepareAudioPlayback = onPrepareAudioPlayback,
                        onShareAudio = onShareAudio,
                        onDeleteAudio = onDeleteAudio,
                        onPreviewFullReport = onPreviewFullReport,
                        onToggleTranscript = onToggleTranscript,
                        onExportTranscript = onExportTranscript,
                        onWorkspaceOrderChanged = onWorkspaceOrderChanged
                    )
                }
            }
            if (isStudyReport && uiState.showTranscript && uiState.transcriptText.isNotBlank()) {
                item {
                    ReferenceTranscriptCard(
                        text = uiState.transcriptText,
                        onCollapse = onToggleTranscript,
                        onExport = onExportTranscript
                    )
                }
            }
        }

    }
}

@Composable
private fun StructuredReportWorkspace(
    report: Report,
    uiState: ReportUiState,
    isForumReport: Boolean,
    riskItems: List<RiskItem>,
    interactionSignals: List<InteractionSignal>,
    metrics: ReferenceMetrics,
    onDeleteAttachment: (MeetingAttachment) -> Unit,
    onAddImages: () -> Unit,
    onCaptureImage: () -> Unit,
    onRefreshAudio: () -> Unit,
    onPrepareAudioPlayback: suspend (ArchivedMeetingAudio) -> Result<ArchivedMeetingAudioPlaybackSource>,
    onShareAudio: (ArchivedMeetingAudio) -> Unit,
    onDeleteAudio: (ArchivedMeetingAudio) -> Unit,
    onPreviewFullReport: () -> Unit,
    onToggleTranscript: () -> Unit,
    onExportTranscript: () -> Unit,
    onWorkspaceOrderChanged: (List<String>) -> Unit
) {
    val available = buildList {
        if (isForumReport) add(ReportWorkspaceBlocks.PARTICIPANTS)
        add(ReportWorkspaceBlocks.AUDIO)
        add(ReportWorkspaceBlocks.IMAGES)
        add(ReportWorkspaceBlocks.REPORT)
        if (uiState.showTranscript && uiState.transcriptText.isNotBlank()) {
            add(ReportWorkspaceBlocks.TRANSCRIPT)
        }
        if (riskItems.isNotEmpty()) add(ReportWorkspaceBlocks.RISKS)
        if (interactionSignals.isNotEmpty()) add(ReportWorkspaceBlocks.INTERACTION_SIGNALS)
    }
    val persisted = report.workspaceBlockOrder
    var order by remember(report.id, persisted, available) {
        mutableStateOf(
            normalizeReportWorkspaceOrder(persisted, available)
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(metrics.itemGap)) {
        order.forEachIndexed { index, blockId ->
            key(blockId) {
                DraggableWorkspaceBlock(
                    blockId = blockId,
                    index = index,
                    order = order,
                    onMove = { from, to ->
                        if (from == to || to !in order.indices) return@DraggableWorkspaceBlock
                        val next = order.toMutableList().apply { add(to, removeAt(from)) }
                        order = next
                        onWorkspaceOrderChanged(next)
                    }
                ) {
                    when (blockId) {
                        ReportWorkspaceBlocks.PARTICIPANTS -> ReferenceForumParticipantWall(uiState.forumParticipants)
                        ReportWorkspaceBlocks.AUDIO -> ReferenceAudioCard(
                            audio = uiState.archivedAudio.firstOrNull(),
                            isLoading = uiState.isLoadingAudio,
                            height = metrics.audioHeight,
                            onRefresh = onRefreshAudio,
                            onPreparePlayback = onPrepareAudioPlayback,
                            onShare = { uiState.archivedAudio.firstOrNull()?.let(onShareAudio) },
                            onDelete = { uiState.archivedAudio.firstOrNull()?.let(onDeleteAudio) }
                        )
                        ReportWorkspaceBlocks.IMAGES -> ReferenceImagesCard(
                            attachments = uiState.attachments,
                            height = metrics.imageHeight,
                            onDelete = onDeleteAttachment,
                            onAddImages = onAddImages,
                            onCaptureImage = onCaptureImage,
                            isStudyReport = false
                        )
                        ReportWorkspaceBlocks.REPORT -> ReferenceReportCard(
                            report = report,
                            initiatorName = uiState.initiatorName,
                            initiatorAvatarDataUrl = uiState.initiatorAvatarDataUrl,
                            isStudyReport = false,
                            isForumReport = isForumReport,
                            isProcessing = uiState.isGenerating,
                            height = metrics.reportHeight,
                            onPreviewFullReport = onPreviewFullReport
                        )
                        ReportWorkspaceBlocks.RISKS -> ReferenceRiskCard(riskItems)
                        ReportWorkspaceBlocks.INTERACTION_SIGNALS -> ReferenceInteractionSignalsCard(interactionSignals)
                        ReportWorkspaceBlocks.TRANSCRIPT -> ReferenceTranscriptCard(
                            text = uiState.transcriptText,
                            onCollapse = onToggleTranscript,
                            onExport = onExportTranscript
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceRiskCard(items: List<RiskItem>) {
    ReferenceGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 118.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, null, tint = ReferencePink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("风险与阻塞", color = ReferenceInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${items.size} 项", color = ReferenceMuted, fontSize = 10.sp)
        }
        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.take(8).forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        modifier = Modifier.padding(top = 4.dp).size(6.dp),
                        shape = CircleShape,
                        color = ReferencePink
                    ) {}
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.content, color = ReferenceInk, fontSize = 12.sp, lineHeight = 17.sp)
                        val meta = listOfNotNull(item.detail, item.status)
                            .filter(String::isNotBlank)
                            .joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(meta, color = ReferenceMuted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
            if (items.size > 8) {
                Text("其余 ${items.size - 8} 项可在完整纪要中查看", color = ReferenceMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ReferenceInteractionSignalsCard(items: List<InteractionSignal>) {
    ReferenceGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 118.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.GraphicEq, null, tint = ReferenceSky, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("互动信号", color = ReferenceInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${items.size} 项", color = ReferenceMuted, fontSize = 10.sp)
        }
        Text(
            text = "仅展示可核对的语速、停顿、打断等现象，不代表情绪结论",
            color = ReferenceMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
        Column(
            modifier = Modifier.padding(top = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items.take(8).forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        modifier = Modifier.padding(top = 5.dp).size(6.dp),
                        shape = CircleShape,
                        color = ReferenceSky
                    ) {}
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.content, color = ReferenceInk, fontSize = 12.sp, lineHeight = 17.sp)
                        val meta = listOfNotNull(item.detail, item.source)
                            .filter(String::isNotBlank)
                            .joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(meta, color = ReferenceMuted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
            if (items.size > 8) {
                Text("其余 ${items.size - 8} 项可在完整纪要中查看", color = ReferenceMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun DraggableWorkspaceBlock(
    blockId: String,
    index: Int,
    order: List<String>,
    onMove: (Int, Int) -> Unit,
    content: @Composable () -> Unit
) {
    var dragging by remember(blockId) { mutableStateOf(false) }
    var dragDistance by remember(blockId) { mutableStateOf(0f) }
    val currentIndex by rememberUpdatedState(index)
    val currentOrder by rememberUpdatedState(order)
    val moveBlock by rememberUpdatedState(onMove)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .animateContentSize()
            .pointerInput(blockId) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        dragDistance = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        dragDistance = 0f
                    },
                    onDragEnd = {
                        dragging = false
                        dragDistance = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consumePositionChange()
                        dragDistance += dragAmount.y
                        val step = 96f
                        while (dragDistance > step && currentIndex < currentOrder.lastIndex) {
                            moveBlock(currentIndex, currentIndex + 1)
                            dragDistance -= step
                        }
                        while (dragDistance < -step && currentIndex > 0) {
                            moveBlock(currentIndex, currentIndex - 1)
                            dragDistance += step
                        }
                    }
                )
            },
        shape = RoundedCornerShape(12.dp),
        color = if (dragging) Color.White.copy(alpha = .16f) else Color.Transparent,
        border = if (dragging) BorderStroke(1.dp, ReferenceSky.copy(alpha = .7f)) else null
    ) {
        Column(modifier = Modifier.padding(if (dragging) 2.dp else 0.dp)) { content() }
    }
}

internal data class ReferenceJourneyStageSummary(
    val sequenceNumber: Int,
    val attachmentCount: Int,
    val locationCount: Int,
    val id: String = "",
    val title: String = "",
    val status: JourneyStageStatus = JourneyStageStatus.SAVED,
    val startedAt: Long = 0L,
    val savedAt: Long? = null
)

internal fun referenceJourneyStageSummaries(
    attachments: List<MeetingAttachment>,
    journeyStages: List<JourneyStage> = emptyList()
): List<ReferenceJourneyStageSummary> {
    val attachmentsByStage = attachments
        .filter { !it.journeyStageId.isNullOrBlank() }
        .groupBy { it.journeyStageId.orEmpty() }
    if (journeyStages.isNotEmpty()) {
        return journeyStages.sortedBy(JourneyStage::sequenceNumber).map { stage ->
            val stageAttachments = attachmentsByStage[stage.id].orEmpty()
            ReferenceJourneyStageSummary(
                sequenceNumber = stage.sequenceNumber,
                attachmentCount = stageAttachments.size,
                locationCount = stageAttachments.count { it.latitude != null && it.longitude != null },
                id = stage.id,
                title = stage.title,
                status = stage.status,
                startedAt = stage.startedAt,
                savedAt = stage.savedAt
            )
        }
    }
    if (attachments.isEmpty()) return emptyList()
    val staged = attachments
        .filter { !it.journeyStageId.isNullOrBlank() }
        .groupBy { it.journeyStageId.orEmpty() }
        .values
        .sortedBy { group -> group.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE }
    val groups = if (staged.isNotEmpty()) {
        staged
    } else {
        listOf(attachments)
    }
    return groups.mapIndexed { index, group ->
        ReferenceJourneyStageSummary(
            sequenceNumber = index + 1,
            attachmentCount = group.size,
            locationCount = group.count { it.latitude != null && it.longitude != null }
        )
    }
}

internal fun referenceJourneyStageAnchors(
    attachments: List<MeetingAttachment>
): List<String> = attachments
    .filter { !it.markerTranscriptAnchor.isNullOrBlank() }
    .sortedWith(
        compareBy<MeetingAttachment> { it.markerTimestampMs ?: Long.MAX_VALUE }
            .thenBy { it.createdAt }
    )
    .mapNotNull { it.markerTranscriptAnchor?.trim()?.takeIf(String::isNotBlank) }
    .distinct()

@Composable
private fun ReferenceStudyJourneyCard(
    journeyStages: List<JourneyStage>,
    attachments: List<MeetingAttachment>,
    onStageSelected: (ReferenceJourneyStageSummary) -> Unit
) {
    val stages = remember(journeyStages, attachments) {
        referenceJourneyStageSummaries(attachments, journeyStages)
    }
    val locationCount = attachments.count { it.latitude != null && it.longitude != null }
    ReferenceGlassCard(modifier = Modifier.fillMaxWidth().height(112.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Timeline, null, tint = ReferenceInk, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("考察轨迹", color = ReferenceInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${stages.size} 段 · ${attachments.size} 条影像 · $locationCount 个地点",
                color = ReferenceMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            itemsIndexed(
                items = stages,
                key = { _, stage -> stage.id.ifBlank { "stage-${stage.sequenceNumber}" } }
            ) { index, stage ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height(2.dp)
                                .background(ReferenceMint.copy(alpha = .72f), RoundedCornerShape(50))
                        )
                    }
                    Column(
                        modifier = Modifier
                            .width(92.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onStageSelected(stage) }
                            .padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    if (stage.status == JourneyStageStatus.ACTIVE) ReferenceSky else ReferenceMint,
                                    CircleShape
                                )
                                .border(2.dp, ReferenceMint.copy(alpha = .28f), CircleShape)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stage.title.ifBlank { "第${stage.sequenceNumber}段" },
                            color = ReferenceInk,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${if (stage.status == JourneyStageStatus.ACTIVE) "记录中" else "已暂存"} · ${stage.attachmentCount}影像",
                            color = ReferenceMuted,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                        referenceJourneyStageTime(stage)?.let { time ->
                            Text(
                                text = time,
                                color = ReferenceMuted.copy(alpha = .78f),
                                fontSize = 8.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceJourneyStageDialog(
    stage: ReferenceJourneyStageSummary,
    transcript: String,
    attachments: List<MeetingAttachment>,
    onOpenAttachments: () -> Unit,
    onDismiss: () -> Unit
) {
    val locationCount = attachments.count { it.latitude != null && it.longitude != null }
    val anchors = remember(attachments) { referenceJourneyStageAnchors(attachments) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stage.title.ifBlank { "第${stage.sequenceNumber}段" },
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = buildString {
                        append(if (stage.status == JourneyStageStatus.ACTIVE) "记录中" else "已暂存")
                        referenceJourneyStageTime(stage)?.let { append(" · $it") }
                        append(" · ${attachments.size} 条影像 · $locationCount 个地点")
                    },
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                )
                Text("阶段转写", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                SelectionContainer {
                    Text(
                        text = transcript.ifBlank { "本段没有可显示的转写文本" },
                        color = if (transcript.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontSize = 13.sp,
                        lineHeight = 21.sp
                    )
                }
                if (anchors.isNotEmpty()) {
                    Text("图文锚点", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    anchors.take(3).forEachIndexed { index, anchor ->
                        Text(
                            text = "（${index + 1}）$anchor",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                    if (anchors.size > 3) {
                        Text("另有 ${anchors.size - 3} 个锚点", fontSize = 11.sp)
                    }
                }
                if (attachments.isNotEmpty()) {
                    TextButton(onClick = onOpenAttachments) {
                        Icon(Icons.Default.Image, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("查看本段影像")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

private fun referenceJourneyStageTime(stage: ReferenceJourneyStageSummary): String? {
    if (stage.startedAt <= 0L) return null
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    val start = formatter.format(Date(stage.startedAt))
    val end = stage.savedAt?.takeIf { it >= stage.startedAt }?.let { formatter.format(Date(it)) }
    return if (end == null) start else "$start-$end"
}

@Composable
private fun ReferenceSummaryHeader(
    title: String,
    createdAt: Long,
    endedAt: Long? = null,
    durationMs: Long,
    height: Dp
) {
    Column(
        modifier = Modifier.fillMaxWidth().height(height),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = ReferenceInk,
                fontSize = 20.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = .15f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .28f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = ReferenceInk, modifier = Modifier.size(13.dp))
                    Text("生成完成", color = ReferenceInk, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Text(
            text = formatMeetingMeta(createdAt, durationMs, endedAt),
            color = ReferenceMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReferenceAudioCard(
    audio: ArchivedMeetingAudio?,
    isLoading: Boolean,
    height: Dp,
    onRefresh: () -> Unit,
    onPreparePlayback: suspend (ArchivedMeetingAudio) -> Result<ArchivedMeetingAudioPlaybackSource>,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var moreExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPreparing by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableStateOf(0L) }
    var playbackDurationMs by remember { mutableStateOf(0L) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    fun releasePlayer() {
        mediaPlayer?.let { player -> runCatching { player.release() } }
        mediaPlayer = null
        isPreparing = false
        isPrepared = false
        isPlaying = false
        playbackPositionMs = 0L
        playbackDurationMs = 0L
    }

    fun togglePlayback() {
        val selectedAudio = audio ?: return
        val currentPlayer = mediaPlayer
        if (currentPlayer != null && isPrepared) {
            if (isPlaying) {
                runCatching { currentPlayer.pause() }
                isPlaying = false
            } else {
                runCatching { currentPlayer.start() }
                    .onSuccess { isPlaying = true }
                    .onFailure { playbackError = it.message ?: "播放失败" }
            }
            return
        }
        if (isPreparing) return

        isPreparing = true
        playbackError = null
        scope.launch {
            onPreparePlayback(selectedAudio).fold(
                onSuccess = { source ->
                    val player = MediaPlayer()
                    runCatching {
                        player.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        player.setDataSource(context, source.uri, source.headers)
                        player.setOnPreparedListener { prepared ->
                            isPreparing = false
                            isPrepared = true
                            playbackDurationMs = prepared.duration.toLong().coerceAtLeast(0L)
                            prepared.start()
                            isPlaying = true
                        }
                        player.setOnCompletionListener { completed ->
                            isPlaying = false
                            runCatching { completed.seekTo(0) }
                            playbackPositionMs = 0L
                        }
                        player.setOnErrorListener { failed, _, _ ->
                            if (mediaPlayer === failed) releasePlayer()
                            playbackError = "音频播放失败，请重试"
                            true
                        }
                        mediaPlayer = player
                        player.prepareAsync()
                    }.onFailure { error ->
                        runCatching { player.release() }
                        if (mediaPlayer === player) mediaPlayer = null
                        isPreparing = false
                        isPrepared = false
                        playbackError = error.message ?: "音频播放失败"
                    }
                },
                onFailure = { error ->
                    isPreparing = false
                    playbackError = error.message ?: "无法获取会议音频"
                }
            )
        }
    }

    DisposableEffect(audio?.id) {
        onDispose { releasePlayer() }
    }
    LaunchedEffect(mediaPlayer, isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let { player ->
                playbackPositionMs = runCatching { player.currentPosition.toLong() }
                    .getOrDefault(playbackPositionMs)
                val measuredDuration = runCatching { player.duration.toLong() }.getOrDefault(0L)
                if (measuredDuration > 0L) playbackDurationMs = measuredDuration
            }
            delay(250)
        }
    }

    val knownDurationMs = playbackDurationMs.takeIf { it > 0L }
        ?: audio?.durationSec?.times(1_000.0)?.toLong()?.coerceAtLeast(0L)
        ?: 0L
    val playbackProgress = if (knownDurationMs > 0L) {
        (playbackPositionMs.toFloat() / knownDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("删除会议音频") },
            text = { Text("音频将从服务器永久删除，确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("取消") } }
        )
    }
    ReferenceGlassCard(modifier = Modifier.fillMaxWidth().height(height)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, null, tint = ReferenceInk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("会议音频", color = ReferenceInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = when {
                        playbackError != null -> playbackError.orEmpty()
                        isPreparing -> "正在准备播放"
                        isPrepared && knownDurationMs > 0L -> {
                            "${formatAudioDuration(playbackPositionMs / 1_000.0)} / " +
                                formatAudioDuration(knownDurationMs / 1_000.0)
                        }
                        audio != null -> audio.durationSec?.let(::formatAudioDuration) ?: "时长读取中"
                        isLoading -> "正在加载"
                        else -> "暂无音频"
                    },
                    color = if (playbackError == null) ReferenceMuted else ReferencePink,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            Box {
                IconButton(onClick = { moreExpanded = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Expand, "音频操作", tint = ReferenceInk, modifier = Modifier.size(17.dp))
                }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("刷新音频") },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        enabled = !isLoading,
                        onClick = { moreExpanded = false; onRefresh() }
                    )
                    if (audio != null) {
                        DropdownMenuItem(
                            text = { Text("分享音频") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = { moreExpanded = false; onShare() }
                        )
                        DropdownMenuItem(
                            text = { Text("删除音频", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { moreExpanded = false; showDeleteConfirmation = true }
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = .96f),
                modifier = Modifier
                    .size(32.dp)
                    .clickable(enabled = audio != null && !isLoading, onClick = ::togglePlayback)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        isPreparing -> CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            color = Color(0xFF4D5E85),
                            strokeWidth = 2.dp
                        )
                        isPlaying -> Icon(
                            Icons.Default.Pause,
                            "暂停会议音频",
                            tint = Color(0xFF4D5E85),
                            modifier = Modifier.size(19.dp)
                        )
                        else -> Icon(
                            Icons.Default.PlayArrow,
                            "播放会议音频",
                            tint = Color(0xFF4D5E85),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(9.dp))
            ReferenceWaveform(
                progress = playbackProgress,
                playbackActive = isPrepared || isPreparing,
                modifier = Modifier.weight(1f).height(42.dp)
            )
        }
    }
}

@Composable
private fun ReferenceWaveform(
    progress: Float,
    playbackActive: Boolean,
    modifier: Modifier = Modifier
) {
    val bars = remember {
        listOf(10, 16, 12, 24, 18, 30, 16, 38, 23, 15, 31, 18, 40, 25, 15, 34, 20, 42, 28, 16, 37, 25, 44, 22, 18, 32, 27, 20, 38, 17, 31, 22, 28, 18, 34, 20, 26, 17, 30, 20)
    }
    Canvas(modifier = modifier) {
        val step = size.width / bars.size
        bars.forEachIndexed { index, halfHeight ->
            val barColor = when {
                index < bars.size / 3 -> ReferenceSky
                index < bars.size * 2 / 3 -> ReferenceLavender
                else -> ReferencePink
            }
            val x = step * index + step / 2f
            val played = index.toFloat() / bars.lastIndex.coerceAtLeast(1) <= progress
            drawLine(
                color = barColor.copy(alpha = if (!playbackActive || played) 1f else .24f),
                start = Offset(x, size.height / 2f - halfHeight),
                end = Offset(x, size.height / 2f + halfHeight),
                strokeWidth = 2.4f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun ReferenceImagesCard(
    attachments: List<MeetingAttachment>,
    height: Dp,
    onDelete: (MeetingAttachment) -> Unit,
    onAddImages: () -> Unit,
    onCaptureImage: () -> Unit,
    isStudyReport: Boolean
) {
    var galleryIndex by remember { mutableStateOf<Int?>(null) }
    galleryIndex?.let { selectedIndex ->
        ReferenceImageGalleryDialog(
            attachments = attachments,
            initialIndex = selectedIndex,
            onDelete = onDelete,
            isStudyReport = isStudyReport,
            onDismiss = { galleryIndex = null }
        )
    }
    ReferenceGlassCard(modifier = Modifier.fillMaxWidth().height(height)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Image, null, tint = ReferenceInk, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                if (isStudyReport) "影像集锦" else "会议图片",
                color = ReferenceInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCaptureImage, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.PhotoCamera, "拍摄照片", tint = ReferenceInk, modifier = Modifier.size(17.dp))
            }
            IconButton(onClick = onAddImages, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.AddPhotoAlternate, "从相册选择", tint = ReferenceInk, modifier = Modifier.size(17.dp))
            }
            Text(
                text = "查看全部图片  ›",
                color = if (attachments.isEmpty()) ReferenceMuted.copy(alpha = .55f) else ReferenceMuted,
                fontSize = 10.sp,
                modifier = Modifier.clickable(enabled = attachments.isNotEmpty()) { galleryIndex = 0 }
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(2) { rowIndex ->
                Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(3) { columnIndex ->
                        val attachment = attachments.getOrNull(rowIndex * 3 + columnIndex)
                        if (attachment == null) {
                            ReferenceImagePlaceholder(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                addAction = true,
                                onAddImages = onAddImages,
                                onCaptureImage = if (rowIndex * 3 + columnIndex == attachments.size) {
                                    onCaptureImage
                                } else {
                                    null
                                }
                            )
                        } else {
                            ReferenceAttachmentCell(
                                attachment = attachment,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onOpen = { galleryIndex = rowIndex * 3 + columnIndex },
                                onDelete = { onDelete(attachment) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceImagePlaceholder(
    modifier: Modifier = Modifier,
    addAction: Boolean = false,
    onAddImages: (() -> Unit)? = null,
    onCaptureImage: (() -> Unit)? = null
) {
    val interactiveModifier = if (onCaptureImage == null && onAddImages != null) {
        modifier.clickable(onClick = onAddImages)
    } else {
        modifier
    }
    Box(
        modifier = interactiveModifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = .075f)),
        contentAlignment = Alignment.Center
    ) {
        if (onCaptureImage != null && onAddImages != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ReferenceImageActionButton(
                    icon = Icons.Default.PhotoCamera,
                    contentDescription = "拍摄照片",
                    onClick = onCaptureImage
                )
                ReferenceImageActionButton(
                    icon = Icons.Default.AddPhotoAlternate,
                    contentDescription = "从相册选择",
                    onClick = onAddImages
                )
            }
        } else {
            Icon(
                if (addAction) Icons.Default.AddPhotoAlternate else Icons.Default.Image,
                if (addAction) "添加图片" else null,
                tint = ReferenceInk.copy(alpha = if (addAction) .56f else .34f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ReferenceImageActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = .12f)
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription, tint = ReferenceInk, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun ReferenceAttachmentCell(
    attachment: MeetingAttachment,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmap = remember(attachment.localPath) {
        OrientedImageDecoder.decode(File(attachment.localPath), maximumDimension = 720)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
    ) {
        if (bitmap == null) {
            ReferenceImagePlaceholder(Modifier.fillMaxSize())
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(26.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = .58f)
        ) {
            IconButton(onClick = onDelete, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "删除图片",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
internal fun ReferenceImageGalleryDialog(
    attachments: List<MeetingAttachment>,
    initialIndex: Int,
    onDelete: (MeetingAttachment) -> Unit,
    isStudyReport: Boolean,
    title: String? = null,
    onDismiss: () -> Unit
) {
    var selectedIndex by remember(initialIndex, attachments) {
        mutableStateOf(initialIndex.coerceIn(0, (attachments.lastIndex).coerceAtLeast(0)))
    }
    val selected = attachments.getOrNull(selectedIndex)
    val selectedBitmap = remember(selected?.localPath) {
        selected?.let { OrientedImageDecoder.decode(File(it.localPath), maximumDimension = 1_600) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(.94f)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF17284C),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .28f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        title ?: if (isStudyReport) "影像集锦" else "会议图片",
                        color = ReferenceInk,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${selectedIndex + 1}/${attachments.size}", color = ReferenceMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Close, "关闭图片浏览", tint = ReferenceInk, modifier = Modifier.size(19.dp))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(370.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = .22f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedBitmap != null) {
                        Image(
                            bitmap = selectedBitmap.asImageBitmap(),
                            contentDescription = selected?.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        ReferenceImagePlaceholder(Modifier.fillMaxSize())
                    }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(attachments, key = { _, item -> item.id }) { index, item ->
                        val thumbnail = remember(item.localPath) {
                            OrientedImageDecoder.decode(File(item.localPath), maximumDimension = 240)
                        }
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color.White.copy(alpha = .1f))
                                .border(
                                    width = if (index == selectedIndex) 2.dp else 1.dp,
                                    color = if (index == selectedIndex) ReferencePink else Color.White.copy(alpha = .2f),
                                    shape = RoundedCornerShape(9.dp)
                                )
                                .clickable { selectedIndex = index }
                        ) {
                            if (thumbnail != null) {
                                Image(
                                    bitmap = thumbnail.asImageBitmap(),
                                    contentDescription = item.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
                if (selected != null) {
                    TextButton(
                        onClick = {
                            onDelete(selected)
                            if (attachments.size <= 1) onDismiss()
                            else selectedIndex = selectedIndex.coerceAtMost(attachments.lastIndex - 1)
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.DeleteOutline, null, tint = ReferencePink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除图片", color = ReferencePink, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceReportCard(
    report: Report,
    initiatorName: String,
    initiatorAvatarDataUrl: String?,
    isStudyReport: Boolean,
    isForumReport: Boolean,
    isProcessing: Boolean,
    height: Dp,
    onPreviewFullReport: () -> Unit
) {
    val attendees = remember(initiatorName) { listOf(initiatorName.trim()).filter(String::isNotBlank) }
    FlowingProgressBorder(
        active = isProcessing,
        modifier = Modifier.fillMaxWidth().height(height),
        cornerRadius = 17.dp,
        inset = 1.dp,
        strokeWidth = 1.8.dp,
        colors = listOf(ReferenceSky, ReferencePink, ReferenceLavender)
    ) {
    ReferenceGlassCard(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, null, tint = ReferenceInk, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                if (isStudyReport) "参观纪要" else "会议纪要",
                color = ReferenceInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
        }
        val contentScrollState = rememberScrollState()
        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 7.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 8.dp)
                    .verticalScroll(contentScrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ReferenceSectionLabel("纪要全文")
                SelectionContainer {
                    Text(
                        text = reportPreviewDocument(report),
                        color = ReferenceInk.copy(alpha = .91f),
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
            ReferenceVerticalScrollIndicator(
                scrollState = contentScrollState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        if (!isForumReport) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("参会人员（${attendees.size}人）", color = ReferenceMuted, fontSize = 10.sp)
                if (attendees.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    ReferenceAttendeeStrip(attendees, initiatorAvatarDataUrl)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "查看完整纪要  ›",
                    color = ReferenceMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable(onClick = onPreviewFullReport)
                )
            }
        }
    }
    }
}

@Composable
private fun ReferenceForumParticipantWall(
    participants: List<ForumParticipant>
) {
    val visibleParticipants = participants.take(24)
    ReferenceGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 148.dp)
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Person, null, tint = ReferenceInk, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("论坛参会名录", color = ReferenceInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (participants.size > visibleParticipants.size) {
                    "照片墙 · ${visibleParticipants.size}/${participants.size} 人"
                } else {
                    "照片墙 · ${participants.size} 人"
                },
                color = ReferenceMuted,
                fontSize = 10.sp
            )
        }
        if (participants.isEmpty()) {
            Text(
                text = "暂未从转写或已确认资料中提取参会人员",
                color = ReferenceMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 20.dp)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                visibleParticipants.chunked(4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { participant ->
                            ReferenceForumParticipantCell(
                                participant = participant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceForumParticipantCell(
    participant: ForumParticipant,
    modifier: Modifier = Modifier
) {
    val image = remember(participant.avatarDataUrl, participant.photoAuthorized) {
        if (!participant.photoAuthorized) return@remember null
        runCatching {
            val encoded = participant.avatarDataUrl
                ?.substringAfter("base64,", "")
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = ReferenceSky,
            border = BorderStroke(1.dp, ReferenceSky.copy(alpha = .5f))
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "${participant.name}的授权头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = participant.name.take(1),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Text(
            text = participant.name,
            color = ReferenceInk,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val meta = listOf(participant.role, participant.organization)
            .filter(String::isNotBlank)
            .joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                color = ReferenceMuted,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReferenceSectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ReferenceLavender)
        )
        Text(text, color = ReferencePink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ReferencePointRow(point: String, index: Int) {
    val pointColor = listOf(ReferencePink, ReferenceSky, ReferenceMint, ReferenceLavender)[index % 4]
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(pointColor)
        )
        Text(
            text = point.cleanReportText(),
            color = ReferenceMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ReferenceAttendeeStrip(attendees: List<String>, avatarDataUrl: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        attendees.forEach { attendee ->
            ReferenceAttendeeAvatar(
                name = attendee,
                avatarDataUrl = avatarDataUrl,
                size = 20.dp,
                showBorder = true
            )
        }
    }
}

@Composable
private fun ReferenceAttendeeDialog(
    attendees: List<String>,
    avatarDataUrl: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("参会人员") },
        text = {
            if (attendees.isEmpty()) {
                Text("暂未记录参会人员信息")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    attendees.forEach { attendee ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ReferenceAttendeeAvatar(
                                name = attendee,
                                avatarDataUrl = avatarDataUrl,
                                size = 32.dp,
                                showBorder = false
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(attendee)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ReferenceAttendeeAvatar(
    name: String,
    avatarDataUrl: String?,
    size: Dp,
    showBorder: Boolean
) {
    val image = remember(avatarDataUrl) {
        runCatching {
            val encoded = avatarDataUrl
                ?.substringAfter("base64,", "")
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = ReferenceSky,
        border = if (showBorder) BorderStroke(1.dp, Color.White.copy(alpha = .62f)) else null
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "${name}的头像",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name.take(1),
                    color = Color.White,
                    fontSize = if (size <= 20.dp) 8.sp else 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ReferenceVerticalScrollIndicator(scrollState: ScrollState, modifier: Modifier = Modifier) {
    if (scrollState.maxValue <= 0) return
    Canvas(modifier = modifier.width(3.dp).fillMaxHeight()) {
        val viewport = size.height
        val contentHeight = viewport + scrollState.maxValue
        val minThumb = 24.dp.toPx()
        val thumbHeight = (viewport * viewport / contentHeight).coerceIn(minThumb, viewport)
        val top = (viewport - thumbHeight) * scrollState.value / scrollState.maxValue
        drawRoundRect(
            color = Color.White.copy(alpha = .7f),
            topLeft = Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, thumbHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f)
        )
    }
}

@Composable
private fun ReferenceTranscriptCard(text: String, onCollapse: () -> Unit, onExport: () -> Unit) {
    ReferenceGlassCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("转写原文", color = ReferenceInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onExport, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("分享", color = ReferencePink, fontSize = 11.sp)
            }
            IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "收起转写原文", tint = ReferenceMuted, modifier = Modifier.size(16.dp))
            }
        }
        SelectionContainer {
            Text(
                text = text,
                color = ReferenceMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.heightIn(max = 280.dp).padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ReferenceGlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(ReferenceGlassTop, ReferenceGlassBottom),
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            )
            .border(1.dp, ReferenceBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun ReferenceBottomAction(
    icon: ImageVector,
    label: String,
    height: Dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .width(102.dp)
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = 2.dp.toPx()
            drawRoundRect(
                brush = Brush.linearGradient(listOf(ReferenceSky.copy(alpha = .95f), ReferencePink.copy(alpha = .95f))),
                topLeft = Offset(inset, inset),
                size = size.copy(width = size.width - inset * 2, height = size.height - inset * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                style = Stroke(width = 2.dp.toPx())
            )
            drawRoundRect(
                color = ReferenceSky.copy(alpha = .16f),
                topLeft = Offset(-8.dp.toPx(), -8.dp.toPx()),
                size = size.copy(width = size.width + 16.dp.toPx(), height = size.height + 16.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f + 10.dp.toPx()),
                style = Stroke(width = 8.dp.toPx())
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            shape = shape,
            color = Color(0xFF005A9E).copy(alpha = .54f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .70f))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, label, tint = ReferenceInk, modifier = Modifier.size(27.dp))
                Spacer(Modifier.height(2.dp))
                Text(label, color = ReferenceInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Displays the exact PDF generated by the export pipeline. Rendering the
 * exported pages (instead of reimplementing Markdown layout in Compose) keeps
 * this preview in lockstep with the downloadable document, including image
 * placement and page breaks.
 */
@Composable
internal fun ReportPdfPreviewDialog(
    file: File,
    onDismiss: () -> Unit
) {
    var pages by remember(file.absolutePath) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var error by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    var loading by remember(file.absolutePath) { mutableStateOf(true) }
    val latestPages by rememberUpdatedState(pages)

    LaunchedEffect(file.absolutePath) {
        val rendered = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        buildList {
                            for (index in 0 until renderer.pageCount) {
                                renderer.openPage(index).use { page ->
                                    val scale = (1_200f / page.width.toFloat()).coerceAtMost(2f)
                                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    add(bitmap)
                                }
                            }
                        }
                    }
                }
            }.getOrElse { throwable ->
                error = throwable.message ?: "无法生成预览"
                emptyList()
            }
        }
        pages = rendered
        loading = false
    }

    DisposableEffect(file.absolutePath) {
        onDispose {
            latestPages.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(.96f)
                .heightIn(max = 760.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF17284C),
            border = BorderStroke(1.dp, ReferenceBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Description, null, tint = ReferenceInk, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "完整纪要预览",
                        color = ReferenceInk,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    if (!loading && pages.isNotEmpty()) {
                        Text("${pages.size} 页", color = ReferenceMuted, fontSize = 11.sp)
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "关闭完整纪要预览", tint = ReferenceInk, modifier = Modifier.size(18.dp))
                    }
                }
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ReferenceSky)
                    }
                    error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(error.orEmpty(), color = Color(0xFF8E2C3B), fontSize = 13.sp)
                    }
                    pages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无可预览页面", color = ReferenceMuted, fontSize = 13.sp)
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        itemsIndexed(pages, key = { index, _ -> index }) { _, bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "纪要 PDF 页面",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFFD7DFEA), RoundedCornerShape(6.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-text rendering used by the compact report card. Unlike the previous
 * summary-only rendering this keeps every section, list item and table row so
 * the card is an honest preview of the document body. Photo anchors are
 * omitted because the generated PDF places those images beside the content.
 */
internal fun reportPreviewDocument(report: Report): String {
    if (report.rawContent.isBlank()) {
        return buildString {
            appendLine("会议概述")
            appendLine(report.summary.ifBlank { "暂无概述" })
            if (report.keyPoints.isNotEmpty()) {
                appendLine()
                appendLine("关键要点")
                report.keyPoints.forEachIndexed { index, value ->
                    appendLine("（${index + 1}）${value.cleanReportText()}")
                }
            }
            if (report.decisions.isNotEmpty()) {
                appendLine()
                appendLine("决策事项")
                report.decisions.forEachIndexed { index, value ->
                    appendLine("（${index + 1}）${value.cleanReportText()}")
                }
            }
            if (report.tasks.isNotEmpty()) {
                appendLine()
                appendLine("待办任务")
                report.tasks.forEachIndexed { index, task ->
                    val state = if (task.completed) "已完成" else "待办"
                    appendLine("（${index + 1}）${task.content.cleanReportText()} · ${task.assignee.orEmpty()} · ${task.due.orEmpty()} · ${task.priority.orEmpty()} · $state")
                }
            }
            if (report.actionItems.isNotEmpty()) {
                appendLine()
                appendLine("行动项")
                report.actionItems.forEachIndexed { index, value ->
                    appendLine("（${index + 1}）${value.cleanReportText()}")
                }
            }
        }.trim()
    }

    val source = if (MeetingMode.fromTemplateName(report.templateName) == MeetingMode.PROGRESS ||
        (report.templateName.contains("孔爵") && report.templateName.contains("表格"))
    ) {
        ReportDocumentFormatter.normalizeProjectManagementSections(report.rawContent)
    } else {
        report.rawContent
    }
    val photoAnchor = Regex("^\\s*\\[照片\\s*[:：]\\s*图\\s*\\d+(?:\\s*[|｜]\\s*[^]]+?)?]\\s*$")
    return ReportDocumentFormatter.normalizeLists(source)
        .lineSequence()
        .filterNot { photoAnchor.matches(it.trim()) }
        .joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#") -> trimmed.replaceFirst(Regex("^#{1,6}\\s*"), "")
                trimmed.startsWith("|") -> trimmed.trim('|').split('|')
                    .joinToString("  ") { it.cleanReportText() }
                else -> trimmed.cleanReportText()
            }
        }
        .trim()
        .ifBlank { "本次会议已完成记录，暂无可显示的纪要内容。" }
}

private fun reportPreviewSummary(report: Report): String = report.summary.cleanReportText().ifBlank {
    report.rawContent.lineSequence()
        .map(String::cleanReportText)
        .firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("|") }
        .orEmpty()
}.ifBlank { "本次会议已完成记录，暂无主题摘要。" }

private fun reportPreviewPoints(report: Report): List<String> {
    val structured = (report.keyPoints + report.decisions + report.actionItems + report.tasks.map { it.content })
        .map(String::cleanReportText)
        .filter(String::isNotBlank)
    if (structured.isNotEmpty()) return structured.distinct()
    return report.rawContent.lineSequence()
        .map(String::cleanReportText)
        .filter { it.isNotBlank() && it.length > 3 && !it.startsWith("#") && !it.startsWith("|") }
        .distinct()
        .take(4)
        .toList()
}

private fun String.cleanReportText(): String = trim()
    .removePrefix("-")
    .removePrefix("*")
    .replace("**", "")
    .replace("__", "")
    .trim()

internal fun formatMeetingMeta(createdAt: Long, durationMs: Long, endedAt: Long? = null): String {
    val date = Date(createdAt)
    val today = Date()
    val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.SIMPLIFIED_CHINESE)
    val isToday = dayFormat.format(date) == dayFormat.format(today)
    val dateLabel = if (isToday) "今天" else {
        SimpleDateFormat("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE).format(date)
    }
    val includeSeconds = durationMs in 1L until 60_000L
    val timeFormat = if (includeSeconds) "HH:mm:ss" else "HH:mm"
    val start = SimpleDateFormat(timeFormat, Locale.SIMPLIFIED_CHINESE).format(date)
    val resolvedEndAt = endedAt?.takeIf { it > createdAt }
        ?: durationMs.takeIf { it > 0L }?.let(createdAt::plus)
    val endDate = resolvedEndAt?.let(::Date)
    val sameDay = endDate?.let { dayFormat.format(date) == dayFormat.format(it) } ?: true
    val sameYear = endDate?.let {
        val calendar = Calendar.getInstance().apply { time = date }
        val endCalendar = Calendar.getInstance().apply { time = it }
        calendar.get(Calendar.YEAR) == endCalendar.get(Calendar.YEAR)
    } ?: true
    val end = endDate?.let {
        when {
            sameDay -> SimpleDateFormat(timeFormat, Locale.SIMPLIFIED_CHINESE).format(it)
            sameYear -> SimpleDateFormat("M月d日 $timeFormat", Locale.SIMPLIFIED_CHINESE).format(it)
            else -> SimpleDateFormat("yyyy年M月d日 $timeFormat", Locale.SIMPLIFIED_CHINESE).format(it)
        }
    }
    val duration = durationMs.takeIf { it > 0L }?.let {
        val totalSeconds = it / 1_000L
        if (totalSeconds >= 60L) " · ${totalSeconds / 60L} 分钟"
        else " · ${totalSeconds.coerceAtLeast(1L)} 秒"
    }.orEmpty()
    return buildString {
        append(dateLabel).append(' ').append(start)
        end?.let { append(" - ").append(it) }
        append(duration)
    }
}

private fun Long?.orZero(): Long = this ?: 0L

private fun formatAudioDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remaining = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.US, hours, minutes, remaining)
    else "%02d:%02d".format(Locale.US, minutes, remaining)
}
