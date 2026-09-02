package com.oa.automation.ui.screen.recording

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oa.automation.BuildConfig
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.JourneyStatus
import com.oa.automation.domain.model.StageDraftStatus
import com.oa.automation.domain.model.StageDraftVersion
import com.oa.automation.domain.model.JourneyEdition
import com.oa.automation.domain.model.JourneyEditionStatus
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.domain.model.ProductEdition
import com.oa.automation.domain.model.STTLanguage
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.ui.navigation.ProductEntryPolicy
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudio
import com.oa.automation.infrastructure.image.OrientedImageDecoder
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

private data class SiriRecorderPalette(
    val background: List<Color>,
    val card: Color,
    val cardRaised: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val control: Color,
    val controlBorder: Color,
    val cyan: Color,
    val pink: Color,
    val violet: Color,
    val red: Color
)

private val SiriDarkPalette = SiriRecorderPalette(
    background = listOf(Color(0xFF1B1A19), Color(0xFF202B33), Color(0xFF1B1A19)),
    card = Color(0xFF252423).copy(alpha = 0.82f),
    cardRaised = Color(0xFF323130).copy(alpha = 0.88f),
    text = Color(0xFFF3F2F1),
    muted = Color(0xFFC8C6C4),
    border = Color(0xFF8CC8FF).copy(alpha = 0.30f),
    control = Color(0xFF323130).copy(alpha = 0.72f),
    controlBorder = Color(0xFF8CC8FF).copy(alpha = 0.34f),
    cyan = Color(0xFF60CDFF),
    pink = Color(0xFF3A96DD),
    violet = Color(0xFF106EBE),
    red = Color(0xFFFF5A5F)
)

private val SiriLightPalette = SiriRecorderPalette(
    background = listOf(Color(0xFFF5F5F5), Color(0xFFEDF4F9), Color(0xFFF5F5F5)),
    card = Color.White.copy(alpha = 0.82f),
    cardRaised = Color.White.copy(alpha = 0.84f),
    text = Color(0xFF242424),
    muted = Color(0xFF605E5C),
    border = Color(0xFF0078D4).copy(alpha = 0.24f),
    control = Color.White.copy(alpha = 0.48f),
    controlBorder = Color(0xFF0078D4).copy(alpha = 0.28f),
    cyan = Color(0xFF0078D4),
    pink = Color(0xFF2B88B9),
    violet = Color(0xFF106EBE),
    red = Color(0xFFE54850)
)

@Composable
internal fun SiriRecorderContent(
    uiState: RecordingUiState,
    productPolicy: ProductEntryPolicy = ProductEntryPolicy.forEdition(ProductEdition.current),
    onNavigateBack: () -> Unit,
    onOpenReport: () -> Unit,
    hasExistingReport: Boolean,
    onEditTitle: () -> Unit,
    onManageImages: () -> Unit,
    onShareAudio: (ArchivedMeetingAudio) -> Unit,
    onSttEngineSelected: (STTEngineType) -> Unit,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    onStartRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onAddMarker: () -> Unit,
    onGenerateStageDraft: () -> Unit,
    onOpenStageDraft: () -> Unit,
    onGenerateJourneyEdition: () -> Unit,
    onOpenJourneyEdition: () -> Unit,
    onCreatePublishedPost: () -> Unit,
    onOpenPublishedPost: () -> Unit,
    onPublishPublishedPost: () -> Unit,
    onWithdrawPublishedPost: () -> Unit,
    onGenerateReport: () -> Unit,
    onCancelTranscription: () -> Unit,
    onCancelReport: () -> Unit,
    onDismissError: () -> Unit
) {
    val palette = if (com.oa.automation.ui.theme.LocalAppIsDarkTheme.current) {
        SiriDarkPalette
    } else {
        SiriLightPalette
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var savedAudioDialogVisible by remember { mutableStateOf(false) }
    var journeyDialogVisible by remember { mutableStateOf(false) }
    val canGenerate = canGenerateReportFromRecording(uiState)
    val savedAudio = uiState.archivedAudio.firstOrNull()
    val displayedSttEngine = effectiveSttEngineType(
        preferred = uiState.sttEngineType,
        route = uiState.realtimeSttRoute,
        isRecording = uiState.isRecording
    )

    if (savedAudioDialogVisible && savedAudio != null) {
        SiriSavedAudioDialog(
            audio = savedAudio,
            palette = palette,
            onShare = { onShareAudio(savedAudio) },
            onDismiss = { savedAudioDialogVisible = false }
        )
    }
    if (journeyDialogVisible && productPolicy.showStudyJourneyTemplate && uiState.journey != null) {
        AlertDialog(
            onDismissRequest = { journeyDialogVisible = false },
            title = { Text("旅程暂存", fontWeight = FontWeight.SemiBold) },
            text = {
                SiriJourneyStrip(
                    journey = uiState.journey,
                    currentStage = uiState.currentJourneyStage,
                    latestSavedStage = uiState.latestSavedJourneyStage,
                    latestStageDraft = uiState.latestStageDraft,
                    latestJourneyEdition = uiState.latestJourneyEdition,
                    latestPublishedPost = uiState.latestPublishedPost,
                    journeyStageCount = uiState.journeyStageCount,
                    attachmentCount = uiState.attachments.size,
                    publishedMediaCount = uiState.publishedPostMedia.size,
                    statusMessage = uiState.journeyStatusMessage,
                    palette = palette
                )
            },
            confirmButton = {
                TextButton(onClick = { journeyDialogVisible = false }) { Text("完成") }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(palette.background))
    ) {
        SiriAmbientBackdrop(palette = palette, modifier = Modifier.matchParentSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 4.dp)
        ) {
            SiriTopBar(
                palette = palette,
                isRecording = uiState.isRecording,
                durationSeconds = uiState.recordingDuration,
                onOpenReport = onOpenReport,
                hasExistingReport = hasExistingReport,
                menuExpanded = menuExpanded,
                onMenuExpandedChange = { menuExpanded = it },
                onNavigateBack = onNavigateBack,
                onEditTitle = onEditTitle,
                onManageImages = onManageImages,
                savedAudio = savedAudio,
                onOpenSavedAudio = { savedAudioDialogVisible = true },
                hasJourney = uiState.journey != null,
                showStudyJourney = productPolicy.showStudyJourneyTemplate,
                onOpenJourney = { journeyDialogVisible = true },
                journey = uiState.journey,
                latestSavedJourneyStage = uiState.latestSavedJourneyStage,
                latestStageDraft = uiState.latestStageDraft,
                isGeneratingStageDraft = uiState.isGeneratingStageDraft,
                latestJourneyEdition = uiState.latestJourneyEdition,
                isGeneratingJourneyEdition = uiState.isGeneratingJourneyEdition,
                latestPublishedPost = uiState.latestPublishedPost,
                isCreatingPublishedPost = uiState.isCreatingPublishedPost,
                journeyActionEnabled = !uiState.isJourneyActionPending &&
                    !uiState.isRecording &&
                    !uiState.isRecordingActionPending &&
                    !uiState.isFinalizingRecording &&
                    !uiState.isTranscribing &&
                    !uiState.isGeneratingReport,
                onGenerateStageDraft = onGenerateStageDraft,
                onOpenStageDraft = onOpenStageDraft,
                onGenerateJourneyEdition = onGenerateJourneyEdition,
                onOpenJourneyEdition = onOpenJourneyEdition,
                onCreatePublishedPost = onCreatePublishedPost,
                onOpenPublishedPost = onOpenPublishedPost,
                onPublishPublishedPost = onPublishPublishedPost,
                onWithdrawPublishedPost = onWithdrawPublishedPost
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "选择模板",
                color = palette.text,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp, lineHeight = 23.sp),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Spacer(Modifier.height(7.dp))
            SiriTemplateChips(
                templates = uiState.presetTemplates,
                selectedTemplateName = uiState.selectedRecordingTemplateName.orEmpty(),
                palette = palette,
                onSelectTemplate = onSelectTemplate
            )
            AnimatedVisibility(
                visible = !uiState.isRecording && !uiState.isTranscribing &&
                    !uiState.isGeneratingReport &&
                    !uiState.selectedRecordingTemplateName.isNullOrBlank(),
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                TemplateWorkflowExplainer(
                    templateName = uiState.selectedRecordingTemplateName.orEmpty(),
                    surfaceColor = palette.card,
                    raisedColor = palette.control,
                    inkColor = palette.text,
                    mutedColor = palette.muted,
                    accentColor = palette.cyan,
                    borderColor = palette.border
                )
            }
            Spacer(Modifier.height(10.dp))
            SiriTranscriptCard(
                transcript = uiState.liveTranscript,
                markerAnchors = uiState.recordingMarkerAnchors,
                attachments = uiState.attachments,
                hasActivePhotoMarker = uiState.activePhotoMarker != null,
                durationSeconds = uiState.recordingDuration,
                isRecording = uiState.isRecording,
                isPaused = uiState.isPaused,
                isFinalizingRecording = uiState.isFinalizingRecording,
                isTranscribing = uiState.isTranscribing,
                isGeneratingReport = uiState.isGeneratingReport,
                progressPercent = if (uiState.isTranscribing) {
                    uiState.transcriptionProgressPercent
                } else {
                    uiState.reportProgressPercent
                },
                status = uiState.transcriptPreviewMode,
                realtimeSttRoute = uiState.realtimeSttRoute,
                sttEngineType = displayedSttEngine,
                isSwitchingSttEngine = uiState.isSwitchingSttEngine,
                onSttEngineSelected = onSttEngineSelected,
                palette = palette,
                onCancelTranscription = onCancelTranscription,
                onCancelReport = onCancelReport,
                onDismissError = onDismissError,
                error = uiState.error,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(10.dp))
            SiriBottomControls(
                palette = palette,
                isRecording = uiState.isRecording,
                isPaused = uiState.isPaused,
                audioLevel = uiState.audioLevel,
                actionEnabled = isRecordingActionEnabled(uiState),
                hasSelectedTemplate = !uiState.selectedRecordingTemplateName.isNullOrBlank(),
                markerCount = uiState.recordingMarkers.size,
                hasActivePhotoMarker = uiState.activePhotoMarker != null,
                onAddMarker = onAddMarker,
                onMainAction = if (uiState.isRecording) onTogglePause else onStartRecording,
                canGenerate = canGenerate,
                isTranscribing = uiState.isTranscribing,
                isGeneratingReport = uiState.isGeneratingReport,
                onGenerateReport = onGenerateReport,
                onCancelReport = onCancelReport
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun SiriTopBar(
    palette: SiriRecorderPalette,
    isRecording: Boolean,
    durationSeconds: Long,
    onOpenReport: () -> Unit,
    hasExistingReport: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    onEditTitle: () -> Unit,
    onManageImages: () -> Unit,
    savedAudio: ArchivedMeetingAudio?,
    onOpenSavedAudio: () -> Unit,
    showStudyJourney: Boolean,
    hasJourney: Boolean,
    onOpenJourney: () -> Unit,
    journey: Journey?,
    latestSavedJourneyStage: JourneyStage?,
    latestStageDraft: StageDraftVersion?,
    isGeneratingStageDraft: Boolean,
    latestJourneyEdition: JourneyEdition?,
    isGeneratingJourneyEdition: Boolean,
    latestPublishedPost: PublishedPost?,
    isCreatingPublishedPost: Boolean,
    journeyActionEnabled: Boolean,
    onGenerateStageDraft: () -> Unit,
    onOpenStageDraft: () -> Unit,
    onGenerateJourneyEdition: () -> Unit,
    onOpenJourneyEdition: () -> Unit,
    onCreatePublishedPost: () -> Unit,
    onOpenPublishedPost: () -> Unit,
    onPublishPublishedPost: () -> Unit,
    onWithdrawPublishedPost: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SiriGlassIconButton(
            palette = palette,
            onClick = onNavigateBack,
            contentDescription = "返回上一页",
            icon = Icons.AutoMirrored.Filled.ArrowBack
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = palette.control,
            border = BorderStroke(1.dp, palette.controlBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier.size(7.dp).clip(CircleShape)
                        .background(if (isRecording) palette.red else palette.muted)
                )
                Text(
                    text = formatSiriDuration(durationSeconds),
                    color = palette.text,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp),
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Box {
            SiriGlassIconButton(
                palette = palette,
                onClick = { onMenuExpandedChange(true) },
                contentDescription = "更多功能",
                icon = Icons.Default.MoreHoriz
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { onMenuExpandedChange(false) }
            ) {
                SiriMenuItem(Icons.Default.Edit, "修改会议名称", onEditTitle, onMenuExpandedChange)
                SiriMenuItem(Icons.Default.PhotoLibrary, "管理插图", onManageImages, onMenuExpandedChange)
                if (savedAudio != null) {
                    SiriMenuItem(
                        Icons.Default.Headphones,
                        "暂存录音",
                        onOpenSavedAudio,
                        onMenuExpandedChange
                    )
                }
                if (showStudyJourney && hasJourney) {
                    SiriMenuItem(
                        Icons.Default.BookmarkBorder,
                        "旅程暂存",
                        onOpenJourney,
                        onMenuExpandedChange
                    )
                }
                if (showStudyJourney && journey != null) {
                    HorizontalDivider(color = palette.border)
                    latestSavedJourneyStage?.let { savedStage ->
                        when {
                            isGeneratingStageDraft -> SiriMenuItem(
                                icon = Icons.Default.AutoAwesome,
                                text = "正在生成阶段笔记",
                                onClick = {},
                                onExpandedChange = onMenuExpandedChange,
                                enabled = false
                            )

                            latestStageDraft == null -> SiriMenuItem(
                                icon = Icons.Default.AutoAwesome,
                                text = "生成${savedStage.title}笔记",
                                onClick = onGenerateStageDraft,
                                onExpandedChange = onMenuExpandedChange,
                                enabled = journeyActionEnabled
                            )

                            latestStageDraft.status == StageDraftStatus.DRAFT -> SiriMenuItem(
                                icon = Icons.Default.Edit,
                                text = "编辑${savedStage.title}笔记",
                                onClick = onOpenStageDraft,
                                onExpandedChange = onMenuExpandedChange,
                                enabled = !isGeneratingStageDraft
                            )

                            else -> {
                                SiriMenuItem(
                                    icon = Icons.Default.Description,
                                    text = "查看${savedStage.title}笔记",
                                    onClick = onOpenStageDraft,
                                    onExpandedChange = onMenuExpandedChange,
                                    enabled = !isGeneratingStageDraft
                                )
                                SiriMenuItem(
                                    icon = Icons.Default.AutoAwesome,
                                    text = "生成${savedStage.title}新版本",
                                    onClick = onGenerateStageDraft,
                                    onExpandedChange = onMenuExpandedChange,
                                    enabled = journeyActionEnabled
                                )
                            }
                        }
                    }
                    when {
                        isGeneratingJourneyEdition -> SiriMenuItem(
                            icon = Icons.Default.AutoAwesome,
                            text = "正在生成总游记",
                            onClick = {},
                            onExpandedChange = onMenuExpandedChange,
                            enabled = false
                        )

                        latestJourneyEdition == null -> SiriMenuItem(
                            icon = Icons.Default.Summarize,
                            text = "生成总游记",
                            onClick = onGenerateJourneyEdition,
                            onExpandedChange = onMenuExpandedChange,
                            enabled = journeyActionEnabled
                        )

                        latestJourneyEdition.status == JourneyEditionStatus.DRAFT -> SiriMenuItem(
                            icon = Icons.Default.Edit,
                            text = "编辑总游记",
                            onClick = onOpenJourneyEdition,
                            onExpandedChange = onMenuExpandedChange,
                            enabled = !isGeneratingJourneyEdition
                        )

                        else -> {
                            SiriMenuItem(
                                icon = Icons.Default.Description,
                                text = "查看总游记",
                                onClick = onOpenJourneyEdition,
                                onExpandedChange = onMenuExpandedChange,
                                enabled = !isGeneratingJourneyEdition
                            )
                            SiriMenuItem(
                                icon = Icons.Default.AutoAwesome,
                                text = "生成总游记新版本",
                                onClick = onGenerateJourneyEdition,
                                onExpandedChange = onMenuExpandedChange,
                                enabled = journeyActionEnabled
                            )
                        }
                    }
                    if (isCreatingPublishedPost) {
                        SiriMenuItem(
                            icon = Icons.Default.Share,
                            text = "正在准备发布快照",
                            onClick = {},
                            onExpandedChange = onMenuExpandedChange,
                            enabled = false
                        )
                    } else {
                        latestPublishedPost?.let { post ->
                            when (post.status) {
                                PublishedPostStatus.REVIEW -> SiriMenuItem(
                                    icon = Icons.Default.Share,
                                    text = "发布前检查",
                                    onClick = onOpenPublishedPost,
                                    onExpandedChange = onMenuExpandedChange
                                )

                                PublishedPostStatus.READY -> {
                                    SiriMenuItem(
                                        icon = Icons.Default.Share,
                                        text = "查看社区发布预览",
                                        onClick = onOpenPublishedPost,
                                        onExpandedChange = onMenuExpandedChange
                                    )
                                    SiriMenuItem(
                                        icon = Icons.Default.Cloud,
                                        text = "同步并发布社区内容",
                                        onClick = onPublishPublishedPost,
                                        onExpandedChange = onMenuExpandedChange,
                                        enabled = journeyActionEnabled
                                    )
                                    SiriMenuItem(
                                        icon = Icons.Default.Close,
                                        text = "撤回发布准备",
                                        onClick = onWithdrawPublishedPost,
                                        onExpandedChange = onMenuExpandedChange,
                                        enabled = journeyActionEnabled
                                    )
                                }

                                PublishedPostStatus.WITHDRAWN -> SiriMenuItem(
                                    icon = Icons.Default.Description,
                                    text = "查看已撤回快照",
                                    onClick = onOpenPublishedPost,
                                    onExpandedChange = onMenuExpandedChange
                                )
                            }
                        }
                        if (latestJourneyEdition?.status == JourneyEditionStatus.CONFIRMED &&
                            latestPublishedPost?.journeyEditionId != latestJourneyEdition.id
                        ) {
                            SiriMenuItem(
                                icon = Icons.Default.Share,
                                text = "创建社区发布预览",
                                onClick = onCreatePublishedPost,
                                onExpandedChange = onMenuExpandedChange,
                                enabled = journeyActionEnabled
                            )
                        }
                    }
                }
                if (hasExistingReport && !isRecording) {
                    SiriMenuItem(Icons.Default.Description, "查看会议纪要", onOpenReport, onMenuExpandedChange)
                }
            }
        }
    }
}

@Composable
private fun SiriSavedAudioDialog(
    audio: ArchivedMeetingAudio,
    palette: SiriRecorderPalette,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val audioMeta = buildList {
        audio.durationSec?.takeIf { it > 0.0 }?.let { add("时长 ${formatSiriAudioDuration(it)}") }
        add("大小 ${formatSiriAudioBytes(audio.bytes)}")
    }.joinToString("  ·  ")
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                color = palette.cyan.copy(alpha = 0.13f),
                border = BorderStroke(1.dp, palette.cyan.copy(alpha = 0.28f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Headphones,
                        contentDescription = null,
                        tint = palette.cyan,
                        modifier = Modifier.size(23.dp)
                    )
                }
            }
        },
        title = { Text("暂存录音", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "录音已自动保存，可继续用于转写和纪要生成。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = audio.filename,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = audioMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("分享")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        shape = RoundedCornerShape(18.dp)
    )
}

private fun formatSiriAudioDuration(durationSec: Double): String {
    val totalSeconds = durationSec.toLong().coerceAtLeast(0L)
    return formatSiriDuration(totalSeconds)
}

private fun formatSiriAudioBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes.toDouble() / (1024L * 1024L))
    bytes >= 1024L -> "%.0f KB".format(bytes.toDouble() / 1024L)
    else -> "$bytes B"
}

@Composable
private fun SiriMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    closeOnClick: Boolean = true
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        enabled = enabled,
        onClick = {
            if (closeOnClick) onExpandedChange(false)
            onClick()
        }
    )
}

@Composable
private fun SiriJourneyStrip(
    journey: Journey,
    currentStage: JourneyStage?,
    latestSavedStage: JourneyStage?,
    latestStageDraft: StageDraftVersion?,
    latestJourneyEdition: JourneyEdition?,
    latestPublishedPost: PublishedPost?,
    journeyStageCount: Int,
    attachmentCount: Int,
    publishedMediaCount: Int,
    statusMessage: String,
    palette: SiriRecorderPalette
) {
    val publishProgressLabel = when (latestPublishedPost?.status) {
        PublishedPostStatus.REVIEW -> "发布快照待检查"
        PublishedPostStatus.READY -> "社区发布预览已就绪"
        PublishedPostStatus.WITHDRAWN -> "发布准备已撤回"
        null -> null
    }
    val editionProgressLabel = when (latestJourneyEdition?.status) {
        JourneyEditionStatus.DRAFT -> "总游记待编辑"
        JourneyEditionStatus.CONFIRMED -> "总游记已确认"
        null -> null
    }
    val draftProgressLabel = latestSavedStage?.let { stage ->
        when (latestStageDraft?.status) {
            StageDraftStatus.DRAFT -> "${stage.title} · 阶段笔记待编辑"
            StageDraftStatus.CONFIRMED -> "${stage.title} · 阶段笔记已确认"
            null -> null
        }
    }
    val progressLabel = publishProgressLabel ?: editionProgressLabel ?: draftProgressLabel ?: statusMessage.ifBlank {
        when (journey.status) {
            JourneyStatus.PAUSED -> "旅程已暂停"
            JourneyStatus.COMPLETED -> "旅程已完成"
            JourneyStatus.ACTIVE -> currentStage?.let { "${it.title} · 进行中" } ?: "等待下一段"
        }
    }
    val stageCount = journeyStageCount.coerceAtLeast(0)
    val currentStageNumber = currentStage?.sequenceNumber?.coerceAtLeast(1)
        ?: latestSavedStage?.sequenceNumber?.coerceAtLeast(1)
        ?: stageCount
    val visibleStageCount = stageCount.coerceIn(1, 6)
    val journeyStateLabel = when (journey.status) {
        JourneyStatus.ACTIVE -> "进行中"
        JourneyStatus.PAUSED -> "已暂停"
        JourneyStatus.COMPLETED -> "已完成"
    }
    val stageNoteLabel = when (latestStageDraft?.status) {
        StageDraftStatus.DRAFT -> "阶段笔记待编辑"
        StageDraftStatus.CONFIRMED -> "阶段笔记已确认"
        null -> "阶段笔记待生成"
    }
    val editionLabel = when (latestJourneyEdition?.status) {
        JourneyEditionStatus.DRAFT -> "总游记待编辑"
        JourneyEditionStatus.CONFIRMED -> "总游记已确认"
        null -> "总游记待生成"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = palette.card.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, palette.border.copy(alpha = 0.72f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    color = palette.cyan.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, palette.cyan.copy(alpha = 0.38f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            tint = palette.cyan,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = journey.title.ifBlank { "参观考察" },
                        color = palette.text,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = progressLabel,
                        color = palette.muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SiriJourneyStatusPill(
                    text = journeyStateLabel,
                    palette = palette,
                    active = journey.status == JourneyStatus.ACTIVE
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "阶段",
                    color = palette.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    modifier = Modifier.width(27.dp)
                )
                SiriJourneyTimeline(
                    stageCount = visibleStageCount,
                    activeStage = currentStageNumber.coerceIn(0, visibleStageCount),
                    palette = palette,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${currentStageNumber}/${stageCount}",
                    color = palette.text,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SiriJourneyMetric(
                    icon = Icons.Default.PhotoLibrary,
                    text = "${maxOf(attachmentCount, publishedMediaCount)} 条影像",
                    palette = palette,
                    modifier = Modifier.weight(1f)
                )
                SiriJourneyMetric(
                    icon = Icons.Default.Description,
                    text = stageNoteLabel,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                )
                SiriJourneyMetric(
                    icon = Icons.Default.AutoAwesome,
                    text = editionLabel,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SiriJourneyStatusPill(
    text: String,
    palette: SiriRecorderPalette,
    active: Boolean
) {
    val tint = if (active) palette.cyan else palette.muted
    Surface(
        shape = RoundedCornerShape(50),
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(tint, CircleShape)
            )
            Text(
                text = text,
                color = tint,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SiriJourneyTimeline(
    stageCount: Int,
    activeStage: Int,
    palette: SiriRecorderPalette,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.height(18.dp)) {
        val count = stageCount.coerceAtLeast(1)
        val radius = 5.dp.toPx()
        val startX = radius
        val endX = size.width - radius
        val spacing = if (count > 1) (endX - startX) / (count - 1) else 0f
        val centerY = size.height / 2f
        repeat(count - 1) { index ->
            val segmentCompleted = index + 2 <= activeStage
            drawLine(
                color = if (segmentCompleted) palette.cyan.copy(alpha = 0.72f) else palette.border,
                start = Offset(startX + spacing * index, centerY),
                end = Offset(startX + spacing * (index + 1), centerY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        repeat(count) { index ->
            val completed = index + 1 <= activeStage
            val center = Offset(startX + spacing * index, centerY)
            drawCircle(
                color = if (completed) palette.cyan else palette.control,
                radius = if (completed) radius else 3.5.dp.toPx(),
                center = center
            )
            drawCircle(
                color = if (completed) palette.cyan.copy(alpha = 0.92f) else palette.border,
                radius = if (completed) radius else 3.5.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

@Composable
private fun SiriJourneyMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    palette: SiriRecorderPalette,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.muted,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = text,
            color = palette.muted,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SiriGlassIconButton(
    palette: SiriRecorderPalette,
    onClick: () -> Unit,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = palette.control,
        border = BorderStroke(1.dp, palette.controlBorder)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = palette.text, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SiriGlassEmblem(
    palette: SiriRecorderPalette,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = palette.control,
        border = BorderStroke(1.dp, palette.controlBorder)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = palette.text, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SiriTemplateChips(
    templates: List<PresetReportTemplate>,
    selectedTemplateName: String,
    palette: SiriRecorderPalette,
    onSelectTemplate: (PresetReportTemplate) -> Unit
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            templates.chunked(2).forEachIndexed { rowIndex, rowTemplates ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowTemplates.forEachIndexed { columnIndex, template ->
                        val index = rowIndex * 2 + columnIndex
                        SiriTemplateChip(
                            template = template,
                            selected = template.name == selectedTemplateName,
                            icon = siriTemplateIcon(index),
                            palette = palette,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelectTemplate(template) }
                        )
                    }
                    if (rowTemplates.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun siriTemplateIcon(index: Int) = when (index) {
    1 -> Icons.Default.Lightbulb
    2 -> Icons.Default.Groups
    3 -> Icons.Default.Apps
    else -> Icons.Default.Description
}

@Composable
private fun SiriTemplateChip(
    template: PresetReportTemplate,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    palette: SiriRecorderPalette,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "templateChipScale"
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) palette.cardRaised else palette.control,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "templateChipColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) palette.text.copy(alpha = 0.62f) else palette.border,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "templateChipBorder"
    )
    val checkAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "templateChipCheck"
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(38.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(50),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = palette.text, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(
                text = template.name,
                color = palette.text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = palette.text,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { alpha = checkAlpha }
                )
            }
        }
    }
}

private fun buildMarkerAwareTranscriptText(
    transcript: String,
    markerAnchors: List<String>,
    defaultColor: Color,
    markerColor: Color
): AnnotatedString = buildAnnotatedString {
    recordingMarkerTranscriptSegments(transcript, markerAnchors).forEach { segment ->
        withStyle(
            SpanStyle(
                color = if (segment.isMarker) markerColor else defaultColor,
                fontWeight = if (segment.isMarker) FontWeight.SemiBold else FontWeight.Normal,
                background = if (segment.isMarker) markerColor.copy(alpha = 0.08f) else Color.Transparent
            )
        ) {
            if (segment.isMarker) append("「")
            append(segment.text)
            if (segment.isMarker) append("」")
        }
    }
}

@Composable
private fun SiriTranscriptCard(
    transcript: String,
    markerAnchors: List<String>,
    attachments: List<MeetingAttachment>,
    hasActivePhotoMarker: Boolean,
    durationSeconds: Long,
    isRecording: Boolean,
    isPaused: Boolean,
    isFinalizingRecording: Boolean,
    isTranscribing: Boolean,
    isGeneratingReport: Boolean,
    progressPercent: Int?,
    status: String,
    realtimeSttRoute: com.oa.automation.infrastructure.service.RealtimeSttRouteState,
    sttEngineType: STTEngineType,
    isSwitchingSttEngine: Boolean,
    onSttEngineSelected: (STTEngineType) -> Unit,
    palette: SiriRecorderPalette,
    onCancelTranscription: () -> Unit,
    onCancelReport: () -> Unit,
    onDismissError: () -> Unit,
    error: String?,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isDark = com.oa.automation.ui.theme.LocalAppIsDarkTheme.current
    val visibleTranscript = remember(transcript, isRecording) {
        if (isRecording) {
            transcriptPreviewWindow(transcript, BuildConfig.TRANSCRIPT_PREVIEW_MAX_CHARS)
        } else {
            transcript
        }
    }
    LaunchedEffect(visibleTranscript) {
        kotlinx.coroutines.yield()
        scrollState.scrollTo(scrollState.maxValue)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        // Keep the dark-mode transcript area visually continuous with the
        // ambient page background; no translucent rectangle should surround it.
        color = if (isDark) Color.Transparent else palette.card,
        border = null,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SiriSignalGlyph(palette = palette, active = isRecording, modifier = Modifier.size(22.dp, 24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "实时转录",
                    color = palette.text,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 21.sp),
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                LocalCloudSttSegmentedControl(
                    sttEngineType = sttEngineType,
                    enabled = !isSwitchingSttEngine && !isTranscribing && !isGeneratingReport,
                    palette = palette,
                    onSttEngineSelected = onSttEngineSelected
                )
            }
            Spacer(Modifier.height(10.dp))
            if (isRecording) {
                RealtimeSttStatusBar(route = realtimeSttRoute)
                Spacer(Modifier.height(9.dp))
            }
            if (error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = palette.red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2)
                    TextButton(onClick = onDismissError) { Text("关闭", color = palette.red) }
                }
            }
            if (isFinalizingRecording) {
                Text(
                    text = status.ifBlank { "正在整理录音" },
                    color = palette.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
            } else if (isTranscribing || isGeneratingReport) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = status.ifBlank { if (isTranscribing) "正在整理录音" else "正在生成纪要" },
                        color = palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = if (isTranscribing) onCancelTranscription else onCancelReport) {
                        Text("取消", color = palette.red)
                    }
                }
                Spacer(Modifier.height(8.dp))
            } else if (isPaused) {
                Text("录音已暂停 · ${formatSiriDuration(durationSeconds)}", color = palette.muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            } else if (isRecording) {
                Text("${formatSiriDuration(durationSeconds)} · 正在聆听", color = palette.muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            if (hasActivePhotoMarker) {
                Text(
                    text = "红色「」已圈定插图位置，请拍照或上传图片",
                    color = palette.red,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
            }
            if (visibleTranscript.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Text(
                        text = if (isRecording) {
                            "请开始说话，智悟本会在这里实时记录..."
                        } else {
                            "开始录音后，实时转录内容会显示在这里"
                        },
                        color = palette.muted.copy(alpha = 0.84f),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 23.sp)
                    )
                }
            } else {
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Text(
                        text = buildMarkerAwareTranscriptText(
                            transcript = visibleTranscript,
                            markerAnchors = markerAnchors,
                            defaultColor = palette.text,
                            markerColor = palette.red
                        ),
                        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 16.sp,
                        lineHeight = 26.sp
                    )
                }
            }
            SiriIllustrationPreviewStrip(
                attachments = attachments,
                palette = palette
            )
        }
    }
}

@Composable
private fun LocalCloudSttSegmentedControl(
    sttEngineType: STTEngineType,
    enabled: Boolean,
    palette: SiriRecorderPalette,
    onSttEngineSelected: (STTEngineType) -> Unit
) {
    val selectedCloud = sttEngineType == STTEngineType.TENCENT_HYBRID
    Row(
        modifier = Modifier
            .width(126.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(palette.control)
            .border(1.dp, palette.controlBorder, RoundedCornerShape(17.dp))
            .padding(2.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.58f },
        verticalAlignment = Alignment.CenterVertically
    ) {
        SiriSttSegment(
            label = "云端",
            icon = Icons.Default.Cloud,
            selected = selectedCloud,
            enabled = enabled,
            palette = palette,
            modifier = Modifier.weight(1f),
            onClick = { onSttEngineSelected(STTEngineType.TENCENT_HYBRID) }
        )
        SiriSttSegment(
            label = "本地",
            icon = Icons.Default.Computer,
            selected = !selectedCloud,
            enabled = enabled,
            palette = palette,
            modifier = Modifier.weight(1f),
            onClick = { onSttEngineSelected(STTEngineType.FASTER_WHISPER) }
        )
    }
}

@Composable
private fun SiriSttSegment(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    palette: SiriRecorderPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (selected) palette.cyan else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "sttSegmentBackground"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) Color.White else palette.muted,
        animationSpec = tween(durationMillis = 180),
        label = "sttSegmentContent"
    )
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(15.dp))
            .background(background)
            .clickable(enabled = enabled && !selected, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 14.sp),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun SiriIllustrationPreviewStrip(
    attachments: List<MeetingAttachment>,
    palette: SiriRecorderPalette
) {
    val previewAttachments = remember(attachments) {
        attachments
            .filter { attachment -> File(attachment.localPath).isFile }
            .sortedWith(
                compareBy<MeetingAttachment> { it.markerTimestampMs ?: Long.MAX_VALUE }
                    .thenBy { it.createdAt }
            )
    }
    if (previewAttachments.isEmpty()) return

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            tint = palette.red,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "插图预览",
            color = palette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${previewAttachments.size} 张",
            color = palette.muted,
            fontSize = 10.sp
        )
    }
    Spacer(Modifier.height(6.dp))
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(82.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(previewAttachments, key = { it.id }) { attachment ->
            val bitmap = remember(attachment.localPath) {
                OrientedImageDecoder.decode(
                    File(attachment.localPath),
                    maximumDimension = 320
                )?.asImageBitmap()
            }
            Box(
                modifier = Modifier
                    .size(width = 116.dp, height = 82.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.cardRaised)
                    .border(
                        width = 1.dp,
                        color = if (attachment.recordingMarkerId != null) {
                            palette.red.copy(alpha = 0.72f)
                        } else {
                            palette.border
                        },
                        shape = RoundedCornerShape(10.dp)
                    ),
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
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "图片无法预览",
                        tint = palette.muted,
                        modifier = Modifier.size(24.dp)
                    )
                }
                attachment.markerTimestampMs?.let { markerTimestampMs ->
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = palette.red.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = formatSiriIllustrationTimestamp(markerTimestampMs),
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            color = Color.White,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    color = Color.Black.copy(alpha = 0.62f)
                ) {
                    Text(
                        text = attachment.markerTranscriptAnchor
                            ?.takeIf(String::isNotBlank)
                            ?: attachment.displayName,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        color = Color.White,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun formatSiriIllustrationTimestamp(timestampMs: Long): String {
    val totalSeconds = (timestampMs / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun SiriBottomControls(
    palette: SiriRecorderPalette,
    isRecording: Boolean,
    isPaused: Boolean,
    audioLevel: Float,
    actionEnabled: Boolean,
    hasSelectedTemplate: Boolean,
    markerCount: Int,
    hasActivePhotoMarker: Boolean,
    onAddMarker: () -> Unit,
    onMainAction: () -> Unit,
    canGenerate: Boolean,
    isTranscribing: Boolean,
    isGeneratingReport: Boolean,
    onGenerateReport: () -> Unit,
    onCancelReport: () -> Unit
) {
    val mainActionEnabled = actionEnabled && (isRecording || hasSelectedTemplate)
    Box(
        modifier = Modifier.fillMaxWidth().height(166.dp),
        contentAlignment = Alignment.Center
    ) {
        SiriRoundAction(
            palette = palette,
            icon = Icons.Default.PhotoCamera,
            label = when {
                hasActivePhotoMarker -> "待插图"
                markerCount > 0 -> "插图 $markerCount"
                else -> "插图"
            },
            enabled = isRecording && !isPaused && actionEnabled,
            onClick = onAddMarker,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Column(
            modifier = Modifier.width(190.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SiriMicOrb(
                palette = palette,
                isRecording = isRecording,
                isPaused = isPaused,
                audioLevel = audioLevel,
                enabled = mainActionEnabled,
                onClick = onMainAction
            )
            Text(
                text = when {
                    isRecording && isPaused -> "继续"
                    isRecording -> "暂停"
                    else -> "开始"
                },
                color = palette.muted,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)
            )
            if (!isRecording && !hasSelectedTemplate) {
                Text(
                    text = RECORDING_TEMPLATE_REQUIRED_MESSAGE,
                    color = palette.red,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        SiriReportAction(
            palette = palette,
            canGenerate = canGenerate,
            isTranscribing = isTranscribing,
            isGeneratingReport = isGeneratingReport,
            onGenerateReport = onGenerateReport,
            onCancelReport = onCancelReport,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun SiriReportAction(
    palette: SiriRecorderPalette,
    canGenerate: Boolean,
    isTranscribing: Boolean,
    isGeneratingReport: Boolean,
    onGenerateReport: () -> Unit,
    onCancelReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = isGeneratingReport || (canGenerate && !isTranscribing)
    val label = when {
        isGeneratingReport -> "停止生成"
        isTranscribing -> "整理录音"
        else -> "生成纪要"
    }
    val actionColor = when {
        isGeneratingReport -> palette.red.copy(alpha = 0.92f)
        else -> palette.control.copy(alpha = palette.control.alpha * if (enabled) 1f else 0.50f)
    }
    Column(
        modifier = modifier.width(82.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(66.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isGeneratingReport) {
                CircularProgressIndicator(
                    modifier = Modifier.size(66.dp),
                    color = palette.red,
                    strokeWidth = 3.dp
                )
            }
            Surface(
                onClick = if (isGeneratingReport) onCancelReport else onGenerateReport,
                enabled = enabled,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = actionColor,
                border = BorderStroke(
                    width = 1.dp,
                    color = when {
                        isGeneratingReport -> palette.red.copy(alpha = 0.80f)
                        else -> palette.controlBorder.copy(alpha = palette.controlBorder.alpha * if (enabled) 1f else 0.55f)
                    }
                ),
                shadowElevation = if (isGeneratingReport) 4.dp else 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isGeneratingReport) Icons.Default.Stop else Icons.Default.Summarize,
                        contentDescription = label,
                        tint = if (isGeneratingReport) Color.White else palette.text.copy(alpha = if (enabled) 1f else 0.44f),
                        modifier = Modifier.size(23.dp)
                    )
                }
            }
        }
        Text(
            text = label,
            color = palette.muted.copy(alpha = if (enabled) 1f else 0.52f),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)
        )
    }
}

@Composable
private fun SiriRoundAction(
    palette: SiriRecorderPalette,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = palette.control.copy(alpha = palette.control.alpha * if (enabled) 1f else 0.50f),
            border = BorderStroke(
                1.dp,
                palette.controlBorder.copy(alpha = palette.controlBorder.alpha * if (enabled) 1f else 0.55f)
            )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = palette.text.copy(alpha = if (enabled) 1f else 0.44f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            label,
            color = palette.muted.copy(alpha = if (enabled) 1f else 0.48f),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)
        )
    }
}

@Composable
private fun SiriMicOrb(
    palette: SiriRecorderPalette,
    isRecording: Boolean,
    isPaused: Boolean,
    audioLevel: Float,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "siriOrb")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "siriOrbPhase"
    )
    Box(
        modifier = Modifier
            .size(118.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.46f }
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val core = size.minDimension * 0.35f
            val pulse = if (isRecording && !isPaused) {
                1f + 0.10f * sin(phase * (PI * 2).toFloat())
            } else {
                1f
            }
            for (ring in 3 downTo 1) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            palette.violet.copy(alpha = 0.34f / ring),
                            palette.cyan.copy(alpha = 0.18f / ring),
                            Color.Transparent
                        ),
                        center = center,
                        radius = core * (1.12f + ring * 0.13f)
                    ),
                    radius = core * (1.12f + ring * 0.13f),
                    center = center
                )
            }
            if (isRecording && !isPaused) {
                // Each ring expands from the microphone like a water ripple. The
                // measured level controls both radius and opacity, while phase
                // keeps the feedback alive between PCM callbacks.
                repeat(4) { index ->
                    val progress = (phase + index / 4f) % 1f
                    val level = audioLevel.coerceIn(0f, 1f)
                    val radius = core * (1.18f + progress * (1.72f + level * 0.56f))
                    val alpha = (1f - progress) * (0.16f + level * 0.42f)
                    drawCircle(
                        color = if (index % 2 == 0) palette.cyan.copy(alpha = alpha)
                        else palette.violet.copy(alpha = alpha * 0.82f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = (1.1f + level * 2.1f).dp.toPx())
                    )
                }
            }
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(palette.cyan, palette.violet, palette.pink, palette.cyan),
                    center = center
                ),
                radius = core * pulse,
                center = center
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFFFFFFFF).copy(alpha = 0.96f), palette.cyan.copy(alpha = 0.52f), palette.violet.copy(alpha = 0.40f))),
                radius = core * 0.84f * pulse,
                center = center
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.30f),
                radius = core * 0.84f * pulse,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }
        if (isRecording) {
            Icon(
                if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = if (isPaused) "继续录音" else "暂停录音",
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
        } else {
            Icon(
                Icons.Default.Mic,
                contentDescription = "开始录音",
                tint = Color.White,
                modifier = Modifier.size(39.dp)
            )
        }
    }
}

@Composable
private fun AudioReactiveMicGlyph(
    palette: SiriRecorderPalette,
    level: Float,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "micAudioGlyph")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(760, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "micAudioGlyphPhase"
    )
    Canvas(modifier) {
        val levelValue = level.coerceIn(0f, 1f)
        val centerX = size.width / 2f
        val bodyWidth = size.width * 0.42f
        val bodyTop = size.height * 0.10f
        val bodyHeight = size.height * 0.51f
        val bodyLeft = centerX - bodyWidth / 2f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.96f),
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(bodyWidth / 2f)
        )
        val barWidth = size.width * 0.045f
        val barGap = size.width * 0.065f
        repeat(5) { index ->
            val wave = 0.5f + 0.5f * sin(phase * (PI * 2f).toFloat() + index * 1.15f)
            val barHeight = size.height * (0.12f + (0.12f + levelValue * 0.22f) * wave)
            val x = centerX + (index - 2) * barGap
            drawRoundRect(
                color = if (index % 2 == 0) palette.violet else palette.cyan,
                topLeft = Offset(x - barWidth / 2f, bodyTop + (bodyHeight - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
        val frameTop = bodyTop + bodyHeight * 0.46f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.94f),
            topLeft = Offset(centerX - size.width * 0.31f, frameTop),
            size = Size(size.width * 0.62f, size.height * 0.28f),
            cornerRadius = CornerRadius(size.width * 0.15f),
            style = Stroke(width = size.width * 0.055f)
        )
        drawLine(
            color = Color.White.copy(alpha = 0.94f),
            start = Offset(centerX, frameTop + size.height * 0.27f),
            end = Offset(centerX, size.height * 0.84f),
            strokeWidth = size.width * 0.055f,
            cap = StrokeCap.Round
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.94f),
            topLeft = Offset(centerX - size.width * 0.27f, size.height * 0.81f),
            size = Size(size.width * 0.54f, size.height * 0.075f),
            cornerRadius = CornerRadius(size.height * 0.0375f)
        )
    }
}

@Composable
private fun SiriSignalGlyph(
    palette: SiriRecorderPalette,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "siriSignal")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "siriSignalPhase"
    )
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * 4) / 5).coerceAtLeast(1f)
        repeat(5) { index ->
            val wave = if (active) 0.5f + 0.5f * sin(phase * (PI * 2).toFloat() + index) else 0.35f
            val height = size.height * (0.28f + wave * (0.30f + index * 0.07f))
            drawRoundRect(
                color = if (index % 2 == 0) palette.cyan else palette.text,
                topLeft = Offset(index * (barWidth + gap), (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth, barWidth)
            )
        }
    }
}

@Composable
private fun SiriAmbientBackdrop(palette: SiriRecorderPalette, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(
                    palette.pink.copy(alpha = 0.16f),
                    Color.Transparent,
                    palette.cyan.copy(alpha = 0.18f)
                )
            )
        )
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    Color(0xFFFFAE70).copy(alpha = 0.12f),
                    Color.Transparent,
                    palette.violet.copy(alpha = 0.10f)
                )
            )
        )
    }
}

private fun formatSiriDuration(seconds: Long): String {
    val value = seconds.coerceAtLeast(0L)
    return "%02d:%02d:%02d".format(value / 3600, value / 60 % 60, value % 60)
}
