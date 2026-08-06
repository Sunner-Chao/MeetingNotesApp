package com.oa.automation.ui.screen.recording

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.oa.automation.domain.model.STTLanguage
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
    background = listOf(Color(0xFF080D23), Color(0xFF0D1631), Color(0xFF071A31)),
    card = Color(0xFF263650).copy(alpha = 0.64f),
    cardRaised = Color(0xFF344563).copy(alpha = 0.70f),
    text = Color(0xFFF5F6FF),
    muted = Color(0xFFBFC7DA),
    border = Color(0xFFB8CBE5).copy(alpha = 0.34f),
    control = Color(0xFFB4C3DA).copy(alpha = 0.18f),
    controlBorder = Color(0xFFE4ECFF).copy(alpha = 0.35f),
    cyan = Color(0xFF37E7FF),
    pink = Color(0xFFFF79C8),
    violet = Color(0xFFB883FF),
    red = Color(0xFFFF5A5F)
)

private val SiriLightPalette = SiriRecorderPalette(
    background = listOf(Color(0xFFE8F3FF), Color(0xFFF9F1FF), Color(0xFFE3FBF7)),
    card = Color.White.copy(alpha = 0.68f),
    cardRaised = Color.White.copy(alpha = 0.84f),
    text = Color(0xFF172139),
    muted = Color(0xFF657188),
    border = Color(0xFF718AA7).copy(alpha = 0.30f),
    control = Color.White.copy(alpha = 0.48f),
    controlBorder = Color.White.copy(alpha = 0.78f),
    cyan = Color(0xFF00AFC7),
    pink = Color(0xFFEE5FA9),
    violet = Color(0xFF8D5DEB),
    red = Color(0xFFE54850)
)

@Composable
internal fun SiriRecorderContent(
    uiState: RecordingUiState,
    onNavigateBack: () -> Unit,
    onOpenReport: () -> Unit,
    hasExistingReport: Boolean,
    onEditTitle: () -> Unit,
    onOpenService: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAudio: () -> Unit,
    onSwitchToImport: () -> Unit,
    onAbandonRecording: () -> Unit,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onAddMarker: () -> Unit,
    onStartJourney: () -> Unit,
    onSaveCurrentJourneyStage: () -> Unit,
    onPauseJourney: () -> Unit,
    onContinueJourney: () -> Unit,
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
    onSttLanguageSelected: (STTLanguage) -> Unit,
    onDismissError: () -> Unit
) {
    val palette = if (com.oa.automation.ui.theme.LocalAppIsDarkTheme.current) {
        SiriDarkPalette
    } else {
        SiriLightPalette
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    val canGenerate = !uiState.isRecording &&
        !uiState.isTranscribing &&
        !uiState.isGeneratingReport &&
        uiState.hasRecording &&
        uiState.liveTranscript.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(palette.background))
    ) {
        SiriAmbientBackdrop(palette = palette, modifier = Modifier.matchParentSize())
        SiriEdgeGlow(palette = palette, modifier = Modifier.matchParentSize())
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
                onOpenService = onOpenService,
                onOpenImages = onOpenImages,
                onOpenAudio = onOpenAudio,
                onSwitchToImport = onSwitchToImport,
                onAbandonRecording = onAbandonRecording,
                onGenerateReport = onGenerateReport,
                canGenerate = canGenerate,
                journey = uiState.journey,
                currentJourneyStage = uiState.currentJourneyStage,
                latestSavedJourneyStage = uiState.latestSavedJourneyStage,
                latestStageDraft = uiState.latestStageDraft,
                isGeneratingStageDraft = uiState.isGeneratingStageDraft,
                latestJourneyEdition = uiState.latestJourneyEdition,
                isGeneratingJourneyEdition = uiState.isGeneratingJourneyEdition,
                latestPublishedPost = uiState.latestPublishedPost,
                isCreatingPublishedPost = uiState.isCreatingPublishedPost,
                selectedTemplateName = uiState.reportTemplate.selectedName,
                journeyActionEnabled = !uiState.isJourneyActionPending &&
                    !uiState.isRecording &&
                    !uiState.isRecordingActionPending &&
                    !uiState.isTranscribing &&
                    !uiState.isGeneratingReport,
                onStartJourney = onStartJourney,
                onSaveCurrentJourneyStage = onSaveCurrentJourneyStage,
                onPauseJourney = onPauseJourney,
                onContinueJourney = onContinueJourney,
                onGenerateStageDraft = onGenerateStageDraft,
                onOpenStageDraft = onOpenStageDraft,
                onGenerateJourneyEdition = onGenerateJourneyEdition,
                onOpenJourneyEdition = onOpenJourneyEdition,
                onCreatePublishedPost = onCreatePublishedPost,
                onOpenPublishedPost = onOpenPublishedPost,
                onPublishPublishedPost = onPublishPublishedPost,
                onWithdrawPublishedPost = onWithdrawPublishedPost
            )
            if (uiState.journey != null) {
                Spacer(Modifier.height(6.dp))
                SiriJourneyStrip(
                    journey = uiState.journey,
                    currentStage = uiState.currentJourneyStage,
                    latestSavedStage = uiState.latestSavedJourneyStage,
                    latestStageDraft = uiState.latestStageDraft,
                    latestJourneyEdition = uiState.latestJourneyEdition,
                    latestPublishedPost = uiState.latestPublishedPost,
                    statusMessage = uiState.journeyStatusMessage,
                    palette = palette
                )
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(8.dp))
            }
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
                selectedTemplateName = uiState.reportTemplate.selectedName,
                palette = palette,
                onSelectTemplate = onSelectTemplate
            )
            Spacer(Modifier.height(10.dp))
            SiriTranscriptCard(
                transcript = uiState.liveTranscript,
                durationSeconds = uiState.recordingDuration,
                isRecording = uiState.isRecording,
                isPaused = uiState.isPaused,
                isTranscribing = uiState.isTranscribing,
                isGeneratingReport = uiState.isGeneratingReport,
                progressPercent = if (uiState.isTranscribing) {
                    uiState.transcriptionProgressPercent
                } else {
                    uiState.reportProgressPercent
                },
                status = uiState.transcriptPreviewMode,
                language = uiState.sttLanguage,
                languageExpanded = languageExpanded,
                palette = palette,
                onLanguageExpandedChange = { languageExpanded = it },
                onSttLanguageSelected = onSttLanguageSelected,
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
                markerCount = uiState.recordingMarkers.size,
                onAddMarker = onAddMarker,
                onTogglePause = onTogglePause,
                onMainAction = if (uiState.isRecording) onStopRecording else onStartRecording
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
    onOpenService: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAudio: () -> Unit,
    onSwitchToImport: () -> Unit,
    onAbandonRecording: () -> Unit,
    onGenerateReport: () -> Unit,
    canGenerate: Boolean,
    journey: Journey?,
    currentJourneyStage: JourneyStage?,
    latestSavedJourneyStage: JourneyStage?,
    latestStageDraft: StageDraftVersion?,
    isGeneratingStageDraft: Boolean,
    latestJourneyEdition: JourneyEdition?,
    isGeneratingJourneyEdition: Boolean,
    latestPublishedPost: PublishedPost?,
    isCreatingPublishedPost: Boolean,
    selectedTemplateName: String,
    journeyActionEnabled: Boolean,
    onStartJourney: () -> Unit,
    onSaveCurrentJourneyStage: () -> Unit,
    onPauseJourney: () -> Unit,
    onContinueJourney: () -> Unit,
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
                SiriMenuItem(Icons.AutoMirrored.Filled.ArrowBack, "返回", onNavigateBack, onMenuExpandedChange)
                SiriMenuItem(Icons.Default.Edit, "修改会议名称", onEditTitle, onMenuExpandedChange)
                SiriMenuItem(Icons.Default.Settings, "识别设置", onOpenService, onMenuExpandedChange)
                SiriMenuItem(Icons.Default.AddPhotoAlternate, "会议图片", onOpenImages, onMenuExpandedChange)
                SiriMenuItem(Icons.Default.Headphones, "保存或分享音频", onOpenAudio, onMenuExpandedChange)
                SiriMenuItem(Icons.Default.Apps, "导入文本或音频", onSwitchToImport, onMenuExpandedChange)
                if (journey == null && selectedTemplateName == "研学考察") {
                    SiriMenuItem(
                        icon = Icons.Default.AutoAwesome,
                        text = "开始研学旅程",
                        onClick = onStartJourney,
                        onExpandedChange = onMenuExpandedChange,
                        enabled = journeyActionEnabled
                    )
                }
                if (journey != null) {
                    HorizontalDivider(color = palette.border)
                    when (journey.status) {
                        JourneyStatus.ACTIVE -> {
                            if (currentJourneyStage != null) {
                                SiriMenuItem(
                                    icon = Icons.Default.BookmarkBorder,
                                    text = "暂存本段",
                                    onClick = onSaveCurrentJourneyStage,
                                    onExpandedChange = onMenuExpandedChange,
                                    enabled = journeyActionEnabled
                                )
                            } else {
                                SiriMenuItem(
                                    icon = Icons.Default.PlayArrow,
                                    text = "开始下一段",
                                    onClick = onContinueJourney,
                                    onExpandedChange = onMenuExpandedChange,
                                    enabled = journeyActionEnabled
                                )
                                SiriMenuItem(
                                    icon = Icons.Default.Pause,
                                    text = "暂停旅程",
                                    onClick = onPauseJourney,
                                    onExpandedChange = onMenuExpandedChange,
                                    enabled = journeyActionEnabled
                                )
                            }
                        }

                        JourneyStatus.PAUSED -> SiriMenuItem(
                            icon = Icons.Default.PlayArrow,
                            text = "继续旅程",
                            onClick = onContinueJourney,
                            onExpandedChange = onMenuExpandedChange,
                            enabled = journeyActionEnabled
                        )

                        JourneyStatus.COMPLETED -> Unit
                    }
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
                if (canGenerate) {
                    SiriMenuItem(Icons.Default.Summarize, "生成会议纪要", onGenerateReport, onMenuExpandedChange)
                }
                if (hasExistingReport && !isRecording) {
                    SiriMenuItem(Icons.Default.Description, "查看会议纪要", onOpenReport, onMenuExpandedChange)
                }
                if (isRecording) {
                    SiriMenuItem(Icons.Default.Stop, "放弃本次录音", onAbandonRecording, onMenuExpandedChange)
                }
            }
        }
    }
}

@Composable
private fun SiriMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        enabled = enabled,
        onClick = {
            onExpandedChange(false)
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = palette.control,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = Icons.Default.BookmarkBorder,
                contentDescription = null,
                tint = palette.cyan,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "研学旅程",
                color = palette.text,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = progressLabel,
                color = palette.muted,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
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
    val listState = rememberLazyListState()
    val selectedIndex = templates.indexOfFirst { it.name == selectedTemplateName }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            listState.animateScrollToItem(selectedIndex)
        }
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = templates,
                key = { _, template -> template.name }
            ) { index, template ->
                val selected = template.name == selectedTemplateName
                SiriTemplateChip(
                    template = template,
                    selected = selected,
                    icon = siriTemplateIcon(index),
                    palette = palette,
                    modifier = Modifier.width(template.siriChipWidth()),
                    onClick = { onSelectTemplate(template) }
                )
            }
        }
    }
}

private fun PresetReportTemplate.siriChipWidth() = when {
    name.length >= 8 -> 140.dp
    name.length >= 6 -> 116.dp
    else -> 102.dp
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

@Composable
private fun SiriTranscriptCard(
    transcript: String,
    durationSeconds: Long,
    isRecording: Boolean,
    isPaused: Boolean,
    isTranscribing: Boolean,
    isGeneratingReport: Boolean,
    progressPercent: Int?,
    status: String,
    language: STTLanguage,
    languageExpanded: Boolean,
    palette: SiriRecorderPalette,
    onLanguageExpandedChange: (Boolean) -> Unit,
    onSttLanguageSelected: (STTLanguage) -> Unit,
    onCancelTranscription: () -> Unit,
    onCancelReport: () -> Unit,
    onDismissError: () -> Unit,
    error: String?,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isDark = com.oa.automation.ui.theme.LocalAppIsDarkTheme.current
    LaunchedEffect(transcript) { scrollState.animateScrollTo(scrollState.maxValue) }
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
                Box {
                    Surface(
                        onClick = { onLanguageExpandedChange(true) },
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                language.displayName,
                                color = palette.muted,
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)
                            )
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "识别语言",
                                tint = palette.muted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { onLanguageExpandedChange(false) }
                    ) {
                        STTLanguage.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayName) },
                                trailingIcon = if (option == language) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    onSttLanguageSelected(option)
                                    onLanguageExpandedChange(false)
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = palette.red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2)
                    TextButton(onClick = onDismissError) { Text("关闭", color = palette.red) }
                }
            }
            if (isTranscribing || isGeneratingReport) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = status.ifBlank { if (isTranscribing) "正在整理录音" else "正在生成纪要" },
                        color = palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = if (isTranscribing) onCancelTranscription else onCancelReport) {
                        Text("终止", color = palette.red)
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
            if (transcript.isBlank()) {
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
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    transcript.split(Regex("\\n+"))
                        .filter { it.isNotBlank() }
                        .forEach { paragraph ->
                            Text(
                                text = paragraph.trim(),
                                color = palette.text,
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 16.sp,
                                lineHeight = 26.sp
                            )
                        }
                }
                }
            }
        }
    }
}

@Composable
private fun SiriBottomControls(
    palette: SiriRecorderPalette,
    isRecording: Boolean,
    isPaused: Boolean,
    audioLevel: Float,
    actionEnabled: Boolean,
    markerCount: Int,
    onAddMarker: () -> Unit,
    onTogglePause: () -> Unit,
    onMainAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(166.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        SiriRoundAction(
            palette = palette,
            icon = Icons.Default.BookmarkBorder,
            label = if (markerCount > 0) "标记 $markerCount" else "标记",
            enabled = isRecording,
            onClick = onAddMarker
        )
        SiriMicOrb(
            palette = palette,
            active = isRecording,
            audioLevel = audioLevel,
            enabled = actionEnabled,
            onClick = onMainAction
        )
        SiriRoundAction(
            palette = palette,
            icon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
            label = if (isPaused) "继续" else "暂停",
            enabled = isRecording,
            onClick = onTogglePause
        )
    }
}

@Composable
private fun SiriRoundAction(
    palette: SiriRecorderPalette,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    active: Boolean,
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
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val core = size.minDimension * 0.35f
            val pulse = if (active) 1f + 0.10f * sin(phase * (PI * 2).toFloat()) else 1f
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
            if (active) {
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
        if (active) {
            AudioReactiveMicGlyph(
                palette = palette,
                level = audioLevel,
                modifier = Modifier.size(42.dp)
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

@Composable
private fun SiriEdgeGlow(palette: SiriRecorderPalette, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "siriEdge")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "siriEdgePhase"
    )
    Canvas(modifier.padding(1.dp)) {
        val inset = 1.dp.toPx()
        val size = Size(size.width - inset * 2, size.height - inset * 2)
        val corner = CornerRadius(36.dp.toPx(), 36.dp.toPx())
        val shift = phase * size.width
        val brush = Brush.linearGradient(
            colors = listOf(palette.pink, palette.violet, palette.cyan, palette.pink),
            start = Offset(shift - size.width, 0f),
            end = Offset(shift + size.width, size.height)
        )
        drawRoundRect(
            brush = brush,
            topLeft = Offset(inset, inset),
            size = size,
            cornerRadius = corner,
            style = Stroke(width = 4.dp.toPx())
        )
        drawRoundRect(
            brush = brush,
            topLeft = Offset(inset, inset),
            size = size,
            cornerRadius = corner,
            style = Stroke(width = 1.2.dp.toPx())
        )
    }
}

private fun formatSiriDuration(seconds: Long): String {
    val value = seconds.coerceAtLeast(0L)
    return "%02d:%02d:%02d".format(value / 3600, value / 60 % 60, value % 60)
}
