package com.oa.automation.ui.screen.recording

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oa.automation.BuildConfig
import com.oa.automation.R
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.ProductEdition
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.CustomTemplateLayout
import com.oa.automation.domain.model.StageDraftStatus
import com.oa.automation.domain.model.StageDraftVersion
import com.oa.automation.domain.model.JourneyEdition
import com.oa.automation.domain.model.JourneyEditionStatus
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.STTLanguage
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudio
import com.oa.automation.ui.component.ProcessingStatusRow
import com.oa.automation.ui.navigation.ProductEntryPolicy
import com.oa.automation.ui.theme.LocalAppIsDarkTheme
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private data class RecordingColors(
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val ink: Color,
    val muted: Color,
    val primary: Color,
    val blue: Color,
    val mint: Color,
    val orange: Color,
    val red: Color,
    val pageGradient: List<Color>,
    val selectedSurface: Color,
    val transcriptSurface: Color,
    val strongSelectedSurface: Color,
    val countSurface: Color,
    val disabledSurface: Color,
    val micGradient: List<Color>,
    val micInner: Color,
    val blueIconSurface: Color,
    val greenIconSurface: Color,
    val orangeIconSurface: Color
)

private val DarkRecordingColors = RecordingColors(
    canvas = Color(0xFF1B1A19),
    surface = Color(0xFF252423),
    surfaceRaised = Color(0xFF323130),
    border = Color(0xFF484644),
    ink = Color(0xFFF3F2F1),
    muted = Color(0xFFC8C6C4),
    primary = Color(0xFF60CDFF),
    blue = Color(0xFF8CC8FF),
    mint = Color(0xFF60CDFF),
    orange = Color(0xFFA9C7E8),
    red = Color(0xFFFF5E56),
    pageGradient = listOf(Color(0xFF1B1A19), Color(0xFF202B33), Color(0xFF1B1A19)),
    selectedSurface = Color(0xFF123A55),
    transcriptSurface = Color(0xFF202326),
    strongSelectedSurface = Color(0xFF004578),
    countSurface = Color(0xFF123A55),
    disabledSurface = Color(0xFF323130),
    micGradient = listOf(Color(0xFF60CDFF), Color(0xFF0078D4), Color(0xFF004578)),
    micInner = Color(0xFF005A9E),
    blueIconSurface = Color(0xFF153A52),
    greenIconSurface = Color(0xFF17384D),
    orangeIconSurface = Color(0xFF273846)
)

private val LightRecordingColors = RecordingColors(
    canvas = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF0F0F0),
    border = Color(0xFFE1DFDD),
    ink = Color(0xFF242424),
    muted = Color(0xFF605E5C),
    primary = Color(0xFF0078D4),
    blue = Color(0xFF106EBE),
    mint = Color(0xFF2B88B9),
    orange = Color(0xFF486A8A),
    red = Color(0xFFDC4B45),
    pageGradient = listOf(Color(0xFFF5F5F5), Color(0xFFEDF4F9), Color(0xFFF5F5F5)),
    selectedSurface = Color(0xFFE5F1FB),
    transcriptSurface = Color(0xFFF7FAFC),
    strongSelectedSurface = Color(0xFFDDEBF7),
    countSurface = Color(0xFFE5F1FB),
    disabledSurface = Color(0xFFEDEBE9),
    micGradient = listOf(Color(0xFF60CDFF), Color(0xFF0078D4), Color(0xFF005A9E)),
    micInner = Color(0xFF0078D4),
    blueIconSurface = Color(0xFFE5F1FB),
    greenIconSurface = Color(0xFFEAF3F8),
    orangeIconSurface = Color(0xFFF0F3F5)
)

private val LocalRecordingColors = staticCompositionLocalOf { DarkRecordingColors }

private val RecordingCanvas: Color @Composable get() = LocalRecordingColors.current.canvas
private val RecordingSurface: Color @Composable get() = LocalRecordingColors.current.surface
private val RecordingSurfaceRaised: Color @Composable get() = LocalRecordingColors.current.surfaceRaised
private val RecordingBorder: Color @Composable get() = LocalRecordingColors.current.border
private val RecordingInk: Color @Composable get() = LocalRecordingColors.current.ink
private val RecordingMuted: Color @Composable get() = LocalRecordingColors.current.muted
private val RecordingPurple: Color @Composable get() = LocalRecordingColors.current.primary
private val RecordingBlue: Color @Composable get() = LocalRecordingColors.current.blue
private val RecordingMint: Color @Composable get() = LocalRecordingColors.current.mint
private val RecordingOrange: Color @Composable get() = LocalRecordingColors.current.orange
private val RecordingRed: Color @Composable get() = LocalRecordingColors.current.red

private data class RecordingLayoutSpec(
    val compact: Boolean,
    val pagePadding: Dp,
    val templateCardHeight: Dp,
    val recorderHeight: Dp,
    val microphoneSize: Dp,
    val transcriptMinHeight: Dp,
    val controlHeight: Dp
)

private fun recordingLayoutSpec(maxWidth: Dp, maxHeight: Dp): RecordingLayoutSpec {
    val compact = maxWidth < 400.dp || maxHeight < 740.dp
    return if (compact) {
        RecordingLayoutSpec(
            compact = true,
            pagePadding = 8.dp,
            templateCardHeight = 104.dp,
            recorderHeight = 186.dp,
            microphoneSize = 90.dp,
            transcriptMinHeight = 156.dp,
            controlHeight = 60.dp
        )
    } else {
        RecordingLayoutSpec(
            compact = false,
            pagePadding = 14.dp,
            templateCardHeight = 110.dp,
            recorderHeight = 232.dp,
            microphoneSize = 112.dp,
            transcriptMinHeight = 208.dp,
            controlHeight = 78.dp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingReferenceScaffold(
    uiState: RecordingUiState,
    productPolicy: ProductEntryPolicy = ProductEntryPolicy.forEdition(ProductEdition.current),
    onNavigateBack: () -> Unit,
    onNavigateToReport: () -> Unit,
    onTitleChange: (String) -> Unit,
    onSaveTitle: () -> Unit,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    templateWorkflowReducedMotion: Boolean,
    templateWorkflowSeen: Set<String>,
    onTemplateWorkflowSeen: (String) -> Unit,
    onCustomTemplateLayoutChange: (CustomTemplateLayout) -> Unit,
    onSttEngineSelected: (STTEngineType) -> Unit,
    onSttLanguageSelected: (STTLanguage) -> Unit,
    onStartRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onAddMarker: () -> Unit,
    onGenerateStageDraft: () -> Unit,
    onOpenStageDraft: () -> Unit,
    onSaveStageDraftContent: (String) -> Unit,
    onConfirmStageDraft: (String) -> Unit,
    onDismissStageDraftEditor: () -> Unit,
    onGenerateJourneyEdition: () -> Unit,
    onOpenJourneyEdition: () -> Unit,
    onSaveJourneyEditionContent: (String) -> Unit,
    onConfirmJourneyEdition: (String) -> Unit,
    onDismissJourneyEditionEditor: () -> Unit,
    onCreatePublishedPost: () -> Unit,
    onOpenPublishedPost: () -> Unit,
    onSavePublishedPostReview: (Boolean, Boolean) -> Unit,
    onUpdatePublishedPostMetadata: (String, String, Int, List<String>, List<String>) -> Unit,
    onSetPublishedPostMediaIncluded: (String, Boolean) -> Unit,
    onMarkPublishedPostReady: (Boolean, Boolean) -> Unit,
    onPublishPublishedPost: () -> Unit,
    onWithdrawPublishedPost: () -> Unit,
    onDismissPublishedPostReview: () -> Unit,
    onGenerateReport: () -> Unit,
    onCancelTranscription: () -> Unit,
    onCancelReport: () -> Unit,
    onTextChange: (String) -> Unit,
    onPickExternalFile: () -> Unit,
    onGenerateFromImport: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickImages: () -> Unit,
    onDeleteAttachment: (MeetingAttachment) -> Unit,
    onShareAudio: (ArchivedMeetingAudio) -> Unit,
    onDismissError: () -> Unit,
    onSelectStreamingTranscript: () -> Unit,
    onSelectBackendTranscript: () -> Unit,
    onDismissTranscriptPicker: () -> Unit
) {
    var titleEditorVisible by remember { mutableStateOf(false) }
    var titleDraft by remember(uiState.meetingTitle) { mutableStateOf(uiState.meetingTitle) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var serviceDialogVisible by remember { mutableStateOf(false) }
    var imageDialogVisible by remember { mutableStateOf(false) }
    val recordingColors = if (LocalAppIsDarkTheme.current) DarkRecordingColors else LightRecordingColors
    val visibleTemplates = uiState.presetTemplates.filter { template ->
        productPolicy.shouldShowMeetingTemplate(
            templateName = template.name,
            preserveSelectedLegacy = template.name == uiState.selectedRecordingTemplateName &&
                (uiState.hasRecording || uiState.hasReport || uiState.journey != null)
        )
    }
    val displayedUiState = if (visibleTemplates.size != uiState.presetTemplates.size) {
        uiState.copy(presetTemplates = visibleTemplates)
    } else {
        uiState
    }

    CompositionLocalProvider(LocalRecordingColors provides recordingColors) {
    if (titleEditorVisible) {
        AlertDialog(
            onDismissRequest = { titleEditorVisible = false },
            title = { Text("修改会议名称") },
            text = {
                OutlinedTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("会议名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onTitleChange(titleDraft)
                        onSaveTitle()
                        titleEditorVisible = false
                    },
                    enabled = titleDraft.isNotBlank() && !uiState.isSavingTitle
                ) {
                    if (uiState.isSavingTitle) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { titleEditorVisible = false }) { Text("取消") }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }

    if (serviceDialogVisible) {
        UtilityDialog(title = "识别设置", onDismiss = { serviceDialogVisible = false }) {
            RuntimeServiceSwitcher(
                sttEngineType = effectiveSttEngineType(
                    preferred = uiState.sttEngineType,
                    route = uiState.realtimeSttRoute,
                    isRecording = uiState.isRecording
                ),
                sttLanguage = uiState.sttLanguage,
                isSwitchingStt = uiState.isSwitchingSttEngine,
                isSwitchingLanguage = uiState.isSwitchingSttLanguage,
                onSttEngineSelected = onSttEngineSelected,
                onSttLanguageSelected = onSttLanguageSelected
            )
        }
    }

    if (imageDialogVisible) {
        UtilityDialog(title = "插图管理", onDismiss = { imageDialogVisible = false }) {
            MeetingImagesSection(
                attachments = uiState.attachments,
                isImporting = uiState.isImportingImages,
                importCompleted = uiState.imageImportCompleted,
                importTotal = uiState.imageImportTotal,
                onTakePhoto = onTakePhoto,
                onPickImages = onPickImages,
                onDelete = onDeleteAttachment
            )
        }
    }

    if (uiState.showTranscriptPicker) {
        TranscriptPickerDialog(
            streamingText = uiState.pendingStreamingText,
            backendText = uiState.pendingBackendText,
            onSelectStreaming = onSelectStreamingTranscript,
            onSelectBackend = onSelectBackendTranscript,
            onDismiss = onDismissTranscriptPicker
        )
    }

    val stageDraft = uiState.latestStageDraft
    val savedStage = uiState.latestSavedJourneyStage
    if (uiState.stageDraftEditorVisible && stageDraft != null && savedStage != null) {
        StageDraftEditorDialog(
            stage = savedStage,
            draft = stageDraft,
            isSaving = uiState.isSavingStageDraft,
            onSave = onSaveStageDraftContent,
            onConfirm = onConfirmStageDraft,
            onDismiss = onDismissStageDraftEditor
        )
    }

    uiState.latestJourneyEdition?.takeIf { uiState.journeyEditionEditorVisible }?.let { edition ->
        JourneyEditionEditorDialog(
            edition = edition,
            isSaving = uiState.isSavingJourneyEdition,
            onSave = onSaveJourneyEditionContent,
            onConfirm = onConfirmJourneyEdition,
            onDismiss = onDismissJourneyEditionEditor
        )
    }

    uiState.latestPublishedPost?.takeIf { uiState.publishedPostReviewVisible }?.let { post ->
        PublishedPostReviewDialog(
            post = post,
            media = uiState.publishedPostMedia,
            isSaving = uiState.isSavingPublishedPost,
            onSaveReview = onSavePublishedPostReview,
            onUpdateMetadata = onUpdatePublishedPostMetadata,
            onSetMediaIncluded = onSetPublishedPostMediaIncluded,
            onMarkReady = onMarkPublishedPostReady,
            onPublish = onPublishPublishedPost,
            onWithdraw = onWithdrawPublishedPost,
            onDismiss = onDismissPublishedPostReview
        )
    }

    Scaffold(
        containerColor = RecordingCanvas,
        topBar = {
            if (uiState.inputMode == InputMode.IMPORT) CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = uiState.meetingTitle.ifBlank { "会议记录" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                        IconButton(
                            onClick = {
                                titleDraft = uiState.meetingTitle
                                titleEditorVisible = true
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "修改会议名称",
                                tint = RecordingMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val importHasContent = uiState.manualTextInput.isNotBlank() ||
                        (uiState.hasRecording && uiState.liveTranscript.isNotBlank())
                    IconButton(
                        onClick = onGenerateFromImport,
                        enabled = importHasContent && !uiState.isImportingAudio &&
                            !uiState.isTranscribing && !uiState.isGeneratingReport
                    ) {
                        if (uiState.isGeneratingReport) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Summarize, contentDescription = "生成会议纪要")
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多功能")
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false }
                        ) {
                            RecordingMenuItem(
                                icon = Icons.Default.Settings,
                                text = "识别设置",
                                onClick = {
                                    moreMenuExpanded = false
                                    serviceDialogVisible = true
                                }
                            )
                            RecordingMenuItem(
                                icon = Icons.Default.AddPhotoAlternate,
                                text = "会议图片",
                                onClick = {
                                    moreMenuExpanded = false
                                    imageDialogVisible = true
                                }
                            )
                            if (uiState.hasReport && !uiState.isGeneratingReport) {
                                RecordingMenuItem(
                                    icon = Icons.Default.Description,
                                    text = "查看会议纪要",
                                    onClick = {
                                        moreMenuExpanded = false
                                        onNavigateToReport()
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = RecordingCanvas,
                    titleContentColor = RecordingInk,
                    navigationIconContentColor = RecordingInk,
                    actionIconContentColor = RecordingInk
                )
            )
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(
                    Brush.verticalGradient(
                        LocalRecordingColors.current.pageGradient
                    )
                )
        ) {
            val layout = recordingLayoutSpec(maxWidth, maxHeight)
            if (displayedUiState.inputMode == InputMode.VOICE) {
                SiriRecorderContent(
                    uiState = displayedUiState,
                    productPolicy = productPolicy,
                    onNavigateBack = onNavigateBack,
                    onOpenReport = onNavigateToReport,
                    hasExistingReport = uiState.hasReport,
                    onEditTitle = {
                        titleDraft = uiState.meetingTitle
                        titleEditorVisible = true
                    },
                    onManageImages = { imageDialogVisible = true },
                    onShareAudio = onShareAudio,
                    onSttEngineSelected = onSttEngineSelected,
                    onSelectTemplate = onSelectTemplate,
                    templateWorkflowReducedMotion = templateWorkflowReducedMotion,
                    templateWorkflowSeen = templateWorkflowSeen,
                    onTemplateWorkflowSeen = onTemplateWorkflowSeen,
                    onCustomTemplateLayoutChange = onCustomTemplateLayoutChange,
                    onStartRecording = onStartRecording,
                    onTogglePause = onTogglePause,
                    onAddMarker = onAddMarker,
                    onGenerateStageDraft = onGenerateStageDraft,
                    onOpenStageDraft = onOpenStageDraft,
                    onGenerateJourneyEdition = onGenerateJourneyEdition,
                    onOpenJourneyEdition = onOpenJourneyEdition,
                    onCreatePublishedPost = onCreatePublishedPost,
                    onOpenPublishedPost = onOpenPublishedPost,
                    onWithdrawPublishedPost = onWithdrawPublishedPost,
                    onPublishPublishedPost = onPublishPublishedPost,
                    onGenerateReport = onGenerateReport,
                    onCancelTranscription = onCancelTranscription,
                    onCancelReport = onCancelReport,
                    onDismissError = onDismissError
                )
            } else {
                ImportRecordingContent(
                    uiState = displayedUiState,
                    layout = layout,
                    onSelectTemplate = onSelectTemplate,
                    templateWorkflowReducedMotion = templateWorkflowReducedMotion,
                    templateWorkflowSeen = templateWorkflowSeen,
                    onTemplateWorkflowSeen = onTemplateWorkflowSeen,
                    onTextChange = onTextChange,
                    onPickExternalFile = onPickExternalFile,
                    onGenerateReport = onGenerateReport,
                    onCancelTranscription = onCancelTranscription,
                    onCancelReport = onCancelReport,
                    onDismissError = onDismissError
                )
            }
        }
    }
}
}

@Composable
private fun StageDraftEditorDialog(
    stage: com.oa.automation.domain.model.JourneyStage,
    draft: StageDraftVersion,
    isSaving: Boolean,
    onSave: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val editable = draft.status == StageDraftStatus.DRAFT
    var content by remember(draft.id, draft.updatedAt) { mutableStateOf(draft.content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${stage.title}阶段笔记")
                Text(
                    text = "版本 ${draft.versionNumber} · 转写 ${draft.evidenceTranscriptCount} 条 · 图片 ${draft.evidenceAttachmentCount} 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!editable) {
                    Text(
                        text = "已确认版本，仅供查看。重新生成会创建新版本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 380.dp),
                    readOnly = !editable,
                    enabled = !isSaving,
                    label = { Text("Markdown 阶段笔记") },
                    minLines = 9,
                    maxLines = 15
                )
            }
        },
        confirmButton = {
            if (editable) {
                Button(
                    onClick = { onConfirm(content) },
                    enabled = content.isNotBlank() && !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("确认阶段")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") }
                    TextButton(
                        onClick = { onSave(content) },
                        enabled = content.isNotBlank() && !isSaving
                    ) { Text("保存修改") }
                }
            }
        },
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun JourneyEditionEditorDialog(
    edition: JourneyEdition,
    isSaving: Boolean,
    onSave: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val editable = edition.status == JourneyEditionStatus.DRAFT
    var content by remember(edition.id, edition.updatedAt) { mutableStateOf(edition.content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("总游记")
                Text(
                    text = "版本 ${edition.versionNumber} · 已合并 ${edition.sourceStageCount} 个阶段",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!editable) {
                    Text(
                        text = "已确认版本，仅供查看。新的阶段稿确认后，可生成新的总游记版本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 380.dp),
                    readOnly = !editable,
                    enabled = !isSaving,
                    label = { Text("Markdown 总游记") },
                    minLines = 9,
                    maxLines = 15
                )
            }
        },
        confirmButton = {
            if (editable) {
                Button(
                    onClick = { onConfirm(content) },
                    enabled = content.isNotBlank() && !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("确认总游记")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") }
                    TextButton(
                        onClick = { onSave(content) },
                        enabled = content.isNotBlank() && !isSaving
                    ) { Text("保存修改") }
                }
            }
        },
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun PublishedPostReviewDialog(
    post: PublishedPost,
    media: List<PublishedPostMediaSummary>,
    isSaving: Boolean,
    onSaveReview: (Boolean, Boolean) -> Unit,
    onUpdateMetadata: (String, String, Int, List<String>, List<String>) -> Unit,
    onSetMediaIncluded: (String, Boolean) -> Unit,
    onMarkReady: (Boolean, Boolean) -> Unit,
    onPublish: () -> Unit,
    onWithdraw: () -> Unit,
    onDismiss: () -> Unit
) {
    val reviewable = post.status == PublishedPostStatus.REVIEW
    var privacyReviewed by remember(post.id, post.updatedAt) {
        mutableStateOf(post.privacyReviewed)
    }
    var rightsConfirmed by remember(post.id, post.updatedAt) {
        mutableStateOf(post.rightsConfirmed)
    }
    var destination by remember(post.id, post.updatedAt) { mutableStateOf(post.destination) }
    var travelDate by remember(post.id, post.updatedAt) { mutableStateOf(post.travelDate) }
    var travelDaysText by remember(post.id, post.updatedAt) { mutableStateOf(post.travelDays.takeIf { it > 0 }?.toString() ?: "") }
    var tagsText by remember(post.id, post.updatedAt) { mutableStateOf(post.tags.joinToString(", ") ) }
    var poisText by remember(post.id, post.updatedAt) { mutableStateOf(post.pois.joinToString(", ") ) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("社区发布预览")
                Text(
                    text = "快照 ${post.versionNumber} · 总游记 v${post.sourceEditionVersion} · 仅本机",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = when (post.status) {
                        PublishedPostStatus.REVIEW -> "发布前检查"
                        PublishedPostStatus.READY -> "已完成本地发布准备，尚未上传或公开。"
                        PublishedPostStatus.WITHDRAWN -> "该发布准备已撤回，尚未上传或公开。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "AI 辅助内容 · 已移除 ${post.redactedCoordinateCount} 处精确坐标 · 不包含原始音频或图片 EXIF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (reviewable) {
                    Text("发布检索信息", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("目的地") },
                        enabled = !isSaving
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = travelDate,
                            onValueChange = { travelDate = it.take(10) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("日期") },
                            placeholder = { Text("YYYY-MM-DD") },
                            enabled = !isSaving
                        )
                        OutlinedTextField(
                            value = travelDaysText,
                            onValueChange = { travelDaysText = it.filter(Char::isDigit).take(2) },
                            modifier = Modifier.width(92.dp),
                            singleLine = true,
                            label = { Text("天数") },
                            enabled = !isSaving
                        )
                    }
                    OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("主题标签") },
                        placeholder = { Text("用逗号分隔") },
                        enabled = !isSaving
                    )
                    OutlinedTextField(
                        value = poisText,
                        onValueChange = { poisText = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("地点/POI") },
                        placeholder = { Text("用逗号分隔，不填写精确坐标") },
                        enabled = !isSaving
                    )
                }
                if (media.isNotEmpty()) {
                    val includedCount = media.count { it.included }
                    Text(
                        text = "发布图片 $includedCount/${media.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    LazyColumn(modifier = Modifier.height(96.dp)) {
                        items(media, key = { it.id }) { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (item.included) "将同步至社区" else "已排除，不会上传",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (item.included) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        }
                                    )
                                }
                                if (reviewable) {
                                    IconButton(
                                        onClick = {
                                            onSetMediaIncluded(item.id, !item.included)
                                        },
                                        enabled = !isSaving
                                    ) {
                                        Icon(
                                            imageVector = if (item.included) {
                                                Icons.Default.Close
                                            } else {
                                                Icons.Default.AddPhotoAlternate
                                            },
                                            contentDescription = if (item.included) {
                                                "从发布快照排除 ${item.displayName}"
                                            } else {
                                                "恢复 ${item.displayName}"
                                            },
                                            tint = if (item.included) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (reviewable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = privacyReviewed,
                            onCheckedChange = { privacyReviewed = it },
                            enabled = !isSaving
                        )
                        Text(
                            "已检查正文，不含个人信息或不宜公开的精确位置",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = rightsConfirmed,
                            onCheckedChange = { rightsConfirmed = it },
                            enabled = !isSaving
                        )
                        Text(
                            "确认拥有正文及后续所选图片的发布权利",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                OutlinedTextField(
                    value = post.content,
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 180.dp),
                    readOnly = true,
                    label = { Text(post.title) },
                    minLines = 7,
                    maxLines = 11
                )
            }
        },
        confirmButton = {
            if (reviewable) {
                Button(
                    onClick = { onMarkReady(privacyReviewed, rightsConfirmed) },
                    enabled = privacyReviewed && rightsConfirmed && !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("完成发布准备")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            when (post.status) {
                PublishedPostStatus.REVIEW -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") }
                    TextButton(
                        onClick = {
                            onUpdateMetadata(
                                destination,
                                travelDate,
                                travelDaysText.toIntOrNull() ?: 0,
                                tagsText.split(',', '，').map(String::trim),
                                poisText.split(',', '，').map(String::trim)
                            )
                        },
                        enabled = !isSaving
                    ) { Text("保存元数据") }
                    TextButton(
                        onClick = { onSaveReview(privacyReviewed, rightsConfirmed) },
                        enabled = !isSaving
                    ) { Text("保存检查") }
                }

                PublishedPostStatus.READY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPublish, enabled = !isSaving) { Text("同步并发布") }
                    TextButton(
                        onClick = onWithdraw,
                        enabled = !isSaving
                    ) { Text("撤回准备", color = MaterialTheme.colorScheme.error) }
                }

                PublishedPostStatus.WITHDRAWN -> Unit
            }
        },
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun VoiceRecordingContent(
    uiState: RecordingUiState,
    layout: RecordingLayoutSpec,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    templateWorkflowReducedMotion: Boolean,
    templateWorkflowSeen: Set<String>,
    onTemplateWorkflowSeen: (String) -> Unit,
    onSwitchToVoice: () -> Unit,
    onSwitchToImport: () -> Unit,
    onStartRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickImages: () -> Unit,
    onOpenImages: () -> Unit,
    onGenerateReport: () -> Unit,
    onCancelTranscription: () -> Unit,
    onCancelReport: () -> Unit,
    onDismissError: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = layout.pagePadding),
        verticalArrangement = Arrangement.spacedBy(if (layout.compact) 5.dp else 10.dp)
    ) {
        MeetingTemplateStrip(
            templates = uiState.presetTemplates,
            selectedTemplateName = uiState.selectedRecordingTemplateName.orEmpty(),
            cardHeight = layout.templateCardHeight,
            onSelectTemplate = onSelectTemplate
        )
        if (!uiState.isRecording && !uiState.isTranscribing && !uiState.isGeneratingReport &&
            !uiState.selectedRecordingTemplateName.isNullOrBlank()
        ) {
            TemplateWorkflowExplainer(
                templateName = uiState.selectedRecordingTemplateName.orEmpty(),
                reducedMotion = templateWorkflowReducedMotion,
                hasBeenSeen = uiState.selectedRecordingTemplateName.orEmpty() in templateWorkflowSeen,
                onViewed = onTemplateWorkflowSeen,
                surfaceColor = RecordingSurface,
                raisedColor = RecordingSurfaceRaised,
                inkColor = RecordingInk,
                mutedColor = RecordingMuted,
                accentColor = RecordingPurple,
                borderColor = RecordingBorder
            )
        }
        AnimatedVisibility(
            visible = uiState.error != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            uiState.error?.let { CompactErrorBanner(error = it, onDismiss = onDismissError) }
        }
        CyberRecorderHero(
            isRecording = uiState.isRecording,
            isTranscribing = uiState.isTranscribing,
            isGeneratingReport = uiState.isGeneratingReport,
            actionEnabled = !uiState.isRecordingActionPending,
            durationSeconds = uiState.recordingDuration,
            status = recordingStatus(uiState),
            progressPercent = if (uiState.isTranscribing) {
                uiState.transcriptionProgressPercent
            } else {
                uiState.reportProgressPercent
            },
            processingStage = if (uiState.isTranscribing) {
                uiState.transcriptionProgressStage
            } else {
                uiState.reportProgressStage
            },
            height = layout.recorderHeight,
            microphoneSize = layout.microphoneSize,
            onMainAction = when (recordingMainAction(uiState)) {
                RecordingMainAction.START -> onStartRecording
                RecordingMainAction.TOGGLE_PAUSE -> onTogglePause
            },
            onCancelProcessing = if (uiState.isTranscribing) onCancelTranscription else onCancelReport
        )
        ReferenceTranscriptPanel(
            transcript = uiState.liveTranscript,
            previewMode = imageImportProgressLabel(uiState) ?: uiState.transcriptPreviewMode,
            isRecording = uiState.isRecording,
            realtimeSttRoute = uiState.realtimeSttRoute,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = layout.transcriptMinHeight)
        )
        MeetingQuickActions(
            attachmentCount = uiState.attachments.size,
            isRecording = uiState.isRecording,
            isPaused = uiState.isPaused,
            isTranscribing = uiState.isTranscribing,
            isGeneratingReport = uiState.isGeneratingReport,
            isImportingImages = uiState.isImportingImages,
            hasTranscript = uiState.hasRecording && uiState.liveTranscript.isNotBlank(),
            onTakePhoto = onTakePhoto,
            onPickImages = onPickImages,
            onOpenImages = onOpenImages,
            onGenerateReport = onGenerateReport,
            onCancelReport = onCancelReport
        )
        RecordingBottomControls(
            inputMode = InputMode.VOICE,
            isBusy = uiState.isTranscribing || uiState.isGeneratingReport,
            mainActionEnabled = !uiState.isFinalizingRecording &&
                !uiState.isTranscribing &&
                !uiState.isGeneratingReport,
            height = layout.controlHeight,
            onVoice = onSwitchToVoice,
            onImport = onSwitchToImport,
            onMainAction = when (recordingMainAction(uiState)) {
                RecordingMainAction.START -> onStartRecording
                RecordingMainAction.TOGGLE_PAUSE -> onTogglePause
            }
        )
    }
}

@Composable
private fun ImportRecordingContent(
    uiState: RecordingUiState,
    layout: RecordingLayoutSpec,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    templateWorkflowReducedMotion: Boolean,
    templateWorkflowSeen: Set<String>,
    onTemplateWorkflowSeen: (String) -> Unit,
    onTextChange: (String) -> Unit,
    onPickExternalFile: () -> Unit,
    onGenerateReport: () -> Unit,
    onCancelTranscription: () -> Unit,
    onCancelReport: () -> Unit,
    onDismissError: () -> Unit
) {
    val isDark = LocalAppIsDarkTheme.current
    val doodleSkin = rememberDoodleSkin(isDark)
    val palette = if (isDark) siriDarkPalette() else siriLightPalette()
    val isBusy = uiState.isImportingAudio || uiState.isTranscribing || uiState.isGeneratingReport
    val hasContent = uiState.manualTextInput.isNotBlank() || uiState.liveTranscript.isNotBlank()
    // Mirror 即刻倾听: the workflow canvas owns the page until there is real
    // content, then the editable panel takes over.
    val showWorkflowCanvas = !hasContent &&
        !isBusy &&
        !uiState.selectedRecordingTemplateName.isNullOrBlank()

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 26.dp, end = layout.pagePadding),
        verticalArrangement = Arrangement.spacedBy(if (layout.compact) 8.dp else 11.dp)
    ) {
        AnimatedVisibility(visible = uiState.error != null) {
            uiState.error?.let { CompactErrorBanner(error = it, onDismiss = onDismissError) }
        }
        if (showWorkflowCanvas) {
            ImportWorkflowDoodle(
                progressPercent = null,
                statusLabel = "选择音频或文档 · 开始转写",
                modifier = Modifier.weight(1f)
            )
            ImportSourceBar(onPickExternalFile = onPickExternalFile)
        } else {
            ReferenceTextInputPanel(
                text = uiState.manualTextInput,
                audioTranscript = uiState.liveTranscript,
                importStatus = uiState.textImportStatus,
                importedAudioDisplayName = uiState.importedAudioDisplayName,
                isAudioBusy = uiState.isImportingAudio || uiState.isTranscribing,
                onTextChange = onTextChange,
                onPickExternalFile = onPickExternalFile,
                modifier = Modifier.weight(1f)
            )
        }
        if (hasContent && !isBusy) {
            ImportGenerateButton(onGenerateReport = onGenerateReport, skin = doodleSkin)
        }
        if (uiState.isImportingAudio || uiState.isTranscribing) {
            ProcessingStatusRow(
                title = "导入音频转写",
                stage = uiState.transcriptionProgressStage.ifBlank { "正在处理会议音频" },
                actionLabel = "终止",
                onAction = onCancelTranscription
            )
        }
        if (uiState.isGeneratingReport) {
            ProcessingStatusRow(
                title = "生成会议纪要",
                stage = uiState.reportProgressStage.ifBlank { "会议纪要处理中" },
                actionLabel = "终止",
                onAction = onCancelReport
            )
        }
    }
        // Exactly the 即刻倾听 rail: hidden colour slivers, peek on touch,
        // slide to browse, long press to pin, tap to commit.
        TemplateBookmarkRail(
            templates = uiState.presetTemplates,
            selectedTemplateName = uiState.selectedRecordingTemplateName.orEmpty(),
            palette = palette,
            skin = doodleSkin,
            isDark = isDark,
            onSelectTemplate = onSelectTemplate,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .padding(top = 8.dp, bottom = 8.dp)
        )
    }
}

/** Compact import affordance shown under the workflow canvas. */
@Composable
private fun ImportSourceBar(onPickExternalFile: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = RecordingSurface,
        border = BorderStroke(1.dp, RecordingBorder),
        onClick = onPickExternalFile
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = RecordingPurple,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "选择音频或文档",
                    color = RecordingInk,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp)
                )
                Text(
                    text = "支持录音文件与文本，导入后自动转写",
                    color = RecordingMuted,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = RecordingMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Hand-drawn 生成纪要 action, matching the recording page's doodle chrome. */
@Composable
private fun ImportGenerateButton(onGenerateReport: () -> Unit, skin: DoodleSkin) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clickable(onClick = onGenerateReport),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(46.dp)) {
            doodleRoundRect(
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    14.dp.toPx(),
                    14.dp.toPx()
                ),
                color = skin.accentCyan,
                strokeWidth = skin.strokeWidth.toPx(),
                wobbleAmplitude = skin.wobbleAmplitude
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Summarize,
                contentDescription = null,
                tint = skin.accentCyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "生成纪要",
                color = skin.accentCyan,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MeetingTemplateStrip(
    templates: List<PresetReportTemplate>,
    selectedTemplateName: String,
    cardHeight: Dp,
    onSelectTemplate: (PresetReportTemplate) -> Unit
) {
    var previewTemplate by remember { mutableStateOf<PresetReportTemplate?>(null) }
    val rows = remember(templates) { templates.chunked(2) }
    val rowGap = 6.dp
    val rowHeight = (cardHeight - rowGap * (rows.size - 1).coerceAtLeast(0)) /
        rows.size.coerceAtLeast(1)

    previewTemplate?.let { template ->
        AlertDialog(
            onDismissRequest = { previewTemplate = null },
            title = { Text(template.name) },
            text = {
                Text(
                    text = template.content.ifBlank { "模板内容为空" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { previewTemplate = null }) { Text("关闭") }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = RecordingSurface,
        border = BorderStroke(1.dp, RecordingBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Assignment,
                    contentDescription = null,
                    tint = RecordingInk,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "纪要模板",
                    modifier = Modifier.weight(1f),
                    color = RecordingInk,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "长按查看说明",
                    color = RecordingMuted,
                    fontSize = 10.sp
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight),
                verticalArrangement = Arrangement.spacedBy(rowGap)
            ) {
                rows.forEachIndexed { rowIndex, rowTemplates ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(rowHeight),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        rowTemplates.forEachIndexed { columnIndex, template ->
                            val index = rowIndex * 2 + columnIndex
                            val selected = template.name == selectedTemplateName
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .combinedClickable(
                                        onClick = { onSelectTemplate(template) },
                                        onLongClick = { previewTemplate = template }
                                    ),
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) LocalRecordingColors.current.selectedSurface else RecordingSurfaceRaised,
                                border = BorderStroke(
                                    if (selected) 1.5.dp else 1.dp,
                                    if (selected) RecordingPurple else RecordingBorder
                                ),
                                shadowElevation = if (selected) 2.dp else 0.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TemplateIcon(index, template.name, 28.dp)
                                    Spacer(Modifier.width(7.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = template.name,
                                            color = RecordingInk,
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = template.subtitle.ifBlank { "会议纪要" },
                                            color = RecordingMuted,
                                            fontSize = 8.sp,
                                            lineHeight = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "已选择",
                                            tint = RecordingPurple,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (rowTemplates.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateIcon(index: Int, name: String, size: Dp) {
    val colors = LocalRecordingColors.current
    val visuals = when {
        name.contains("项目") -> Triple(Icons.AutoMirrored.Filled.Assignment, RecordingBlue, colors.blueIconSurface)
        name.contains("头脑") || name.contains("沙龙") -> Triple(Icons.Default.Lightbulb, RecordingMint, colors.greenIconSurface)
        name.contains("行政") || name.contains("团队") || name.contains("例会") ->
            Triple(Icons.Default.Groups, RecordingOrange, colors.orangeIconSurface)
        name.contains("客户") -> Triple(Icons.Default.Person, RecordingBlue, colors.blueIconSurface)
        else -> when (index % 4) {
            0 -> Triple(Icons.AutoMirrored.Filled.Assignment, RecordingPurple, colors.greenIconSurface)
            1 -> Triple(Icons.Default.Lightbulb, RecordingMint, colors.greenIconSurface)
            2 -> Triple(Icons.Default.Groups, RecordingOrange, colors.orangeIconSurface)
            else -> Triple(Icons.Default.AutoAwesome, RecordingBlue, colors.blueIconSurface)
        }
    }
    Surface(modifier = Modifier.size(size), shape = RoundedCornerShape(9.dp), color = visuals.third) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = visuals.first,
                contentDescription = null,
                tint = visuals.second,
                modifier = Modifier.size(size * 0.58f)
            )
        }
    }
}

@Composable
private fun RecorderHero(
    isRecording: Boolean,
    isTranscribing: Boolean,
    isGeneratingReport: Boolean,
    durationSeconds: Long,
    status: String,
    progressPercent: Int?,
    processingStage: String,
    height: Dp,
    microphoneSize: Dp,
    onMainAction: () -> Unit,
    onCancelProcessing: () -> Unit
) {
    val isBusy = isTranscribing || isGeneratingReport
    val colors = LocalRecordingColors.current
    val primary = colors.primary
    val mint = colors.mint
    val isDark = LocalAppIsDarkTheme.current
    val haptic = LocalHapticFeedback.current
    val animationScope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val impactProgress = remember { Animatable(1f) }
    val statePulseProgress = remember { Animatable(1f) }
    var previousRecordingState by remember { mutableStateOf(isRecording) }

    val infinite = rememberInfiniteTransition(label = "recorderEnergy")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "energyPhase"
    )
    val orbitDegrees by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "energyOrbit"
    )
    val energyLevel by animateFloatAsState(
        targetValue = when {
            isRecording -> 1f
            isBusy -> 0.82f
            else -> 0.18f
        },
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "energyLevel"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "microphonePress"
    )
    val micBrush = remember(colors.micGradient) {
        Brush.radialGradient(colors.micGradient)
    }

    LaunchedEffect(isRecording) {
        if (isRecording != previousRecordingState) {
            previousRecordingState = isRecording
            statePulseProgress.snapTo(0f)
            statePulseProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(760, easing = FastOutSlowInEasing)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerY = size.height / 2f
                val centerX = size.width / 2f
                val micRadius = microphoneSize.toPx() / 2f
                val safeGap = microphoneSize.toPx() * 0.61f
                val radians = phase * (PI * 2f).toFloat()
                val breathing = 0.5f + 0.5f * sin(radians)
                val active = isRecording || isBusy
                val activeAlpha = if (isDark) 1f else 0.78f

                // Broad energy field. Filled circles are cheaper than rebuilding
                // gradient brushes for every animation frame.
                if (active || isDark) {
                    drawCircle(
                        color = primary.copy(alpha = (0.025f + energyLevel * 0.035f) * activeAlpha),
                        radius = micRadius * (3.05f + breathing * 0.18f * energyLevel),
                        center = Offset(centerX, centerY)
                    )
                    drawCircle(
                        color = mint.copy(alpha = (0.035f + energyLevel * 0.05f) * activeAlpha),
                        radius = micRadius * (2.28f + breathing * 0.14f * energyLevel),
                        center = Offset(centerX, centerY)
                    )
                }

                // Side waveform grows from the central control without touching it.
                val barCount = 56
                val spacing = size.width / (barCount + 1)
                for (index in 1..barCount) {
                    val x = spacing * index
                    if (abs(x - centerX) < safeGap) continue
                    val distance = abs(x - centerX) / centerX
                    val centerWeight = (1f - distance).coerceIn(0.12f, 1f)
                    val texture = 0.54f + 0.46f * abs(sin(index * 1.17f + 0.4f))
                    val base = (5.dp.toPx() + 45.dp.toPx() * centerWeight) * texture
                    val motion = if (active) {
                        val wave = abs(sin(index * 0.73f + radians * 2.1f))
                        0.28f + wave * (0.82f + breathing * 0.35f) * energyLevel
                    } else {
                        0.24f + 0.30f * abs(sin(index * 0.63f))
                    }
                    val barHeight = (base * motion).coerceAtMost(size.height * 0.70f)
                    val barAlpha = if (isDark) {
                        if (index % 3 == 0) 0.96f else 0.68f
                    } else {
                        if (index % 3 == 0) 0.78f else 0.48f
                    }
                    drawRoundRect(
                        color = if (index % 3 == 0) mint.copy(alpha = barAlpha)
                        else primary.copy(alpha = barAlpha),
                        topLeft = Offset(x - 1.2.dp.toPx(), centerY - barHeight / 2f),
                        size = Size(2.4.dp.toPx(), barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                    )
                }

                // Three staggered ripples create depth while recording/processing.
                if (active) {
                    repeat(3) { index ->
                        val ripple = (phase + index / 3f) % 1f
                        val fade = 1f - ripple
                        val radius = micRadius * (1.22f + ripple * 1.78f)
                        drawCircle(
                            color = if (index == 1) {
                                mint.copy(alpha = fade * fade * 0.34f * activeAlpha)
                            } else {
                                primary.copy(alpha = fade * fade * 0.28f * activeAlpha)
                            },
                            radius = radius,
                            center = Offset(centerX, centerY),
                            style = Stroke(
                                width = (0.8.dp + 1.5.dp * fade).toPx()
                            )
                        )
                    }
                }

                val haloRadius = micRadius * (1.28f + breathing * 0.045f * energyLevel)
                val haloTopLeft = Offset(centerX - haloRadius, centerY - haloRadius)
                val haloSize = Size(haloRadius * 2f, haloRadius * 2f)
                drawCircle(
                    color = primary.copy(alpha = (0.14f + energyLevel * 0.16f) * activeAlpha),
                    radius = haloRadius,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 1.2.dp.toPx())
                )
                drawArc(
                    color = mint.copy(alpha = (0.34f + energyLevel * 0.48f) * activeAlpha),
                    startAngle = orbitDegrees,
                    sweepAngle = 78f,
                    useCenter = false,
                    topLeft = haloTopLeft,
                    size = haloSize,
                    style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = primary.copy(alpha = (0.26f + energyLevel * 0.38f) * activeAlpha),
                    startAngle = orbitDegrees + 180f,
                    sweepAngle = 48f,
                    useCenter = false,
                    topLeft = haloTopLeft,
                    size = haloSize,
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                )

                // Radial energy ticks make the active state feel responsive and alive.
                if (active) {
                    val tickCount = 28
                    repeat(tickCount) { index ->
                        val angle = index * (PI * 2f / tickCount).toFloat() +
                            orbitDegrees * PI.toFloat() / 900f
                        val wave = 0.5f + 0.5f * sin(index * 1.31f + radians * 2.4f)
                        val inner = micRadius * 1.38f
                        val outer = inner + (3.dp.toPx() + 9.dp.toPx() * wave * energyLevel)
                        drawLine(
                            color = if (index % 4 == 0) {
                                mint.copy(alpha = 0.72f * activeAlpha)
                            } else {
                                primary.copy(alpha = 0.36f * activeAlpha)
                            },
                            start = Offset(
                                centerX + inner * cos(angle),
                                centerY + inner * sin(angle)
                            ),
                            end = Offset(
                                centerX + outer * cos(angle),
                                centerY + outer * sin(angle)
                            ),
                            strokeWidth = if (index % 4 == 0) 1.6.dp.toPx() else 0.9.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Immediate tap burst keeps feedback perceptually connected to touch.
                if (impactProgress.value < 1f) {
                    val impact = impactProgress.value
                    val fade = (1f - impact) * (1f - impact)
                    val radius = micRadius * (1.08f + impact * 2.38f)
                    drawCircle(
                        color = mint.copy(alpha = fade * 0.86f * activeAlpha),
                        radius = radius,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = (0.6.dp + 3.1.dp * fade).toPx())
                    )
                    drawCircle(
                        color = primary.copy(alpha = fade * 0.36f * activeAlpha),
                        radius = radius * 1.17f,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = (0.5.dp + 1.5.dp * fade).toPx())
                    )
                }

                // Recording state changes add a second, slower confirmation wave.
                if (statePulseProgress.value < 1f) {
                    val pulse = statePulseProgress.value
                    val fade = 1f - pulse
                    drawCircle(
                        color = (if (isRecording) mint else colors.red).copy(
                            alpha = fade * fade * 0.72f * activeAlpha
                        ),
                        radius = micRadius * (1.12f + pulse * 1.92f),
                        center = Offset(centerX, centerY),
                        style = Stroke(width = (0.7.dp + 2.5.dp * fade).toPx())
                    )
                }

                if (isBusy) {
                    val progressRadius = micRadius * 1.42f
                    val progressTopLeft = Offset(
                        centerX - progressRadius,
                        centerY - progressRadius
                    )
                    val progressSize = Size(progressRadius * 2f, progressRadius * 2f)
                    drawArc(
                        color = primary.copy(alpha = 0.18f * activeAlpha),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = progressTopLeft,
                        size = progressSize,
                        style = Stroke(width = 3.dp.toPx())
                    )
                    drawArc(
                        color = mint.copy(alpha = if (isDark) 1f else 0.85f),
                        startAngle = if (progressPercent == null) orbitDegrees else -90f,
                        sweepAngle = progressPercent
                            ?.coerceIn(0, 100)
                            ?.times(3.6f)
                            ?: 105f,
                        useCenter = false,
                        topLeft = progressTopLeft,
                        size = progressSize,
                        style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            val micShadowColor = if (isDark) RecordingPurple.copy(alpha = 0.85f)
                                 else RecordingPurple.copy(alpha = 0.30f)
            val micBreathingScale = if (isRecording) {
                1f + 0.026f * (0.5f + 0.5f * sin(phase * (PI * 2f).toFloat()))
            } else {
                1f
            }
            Box(
                modifier = Modifier
                    .size(microphoneSize * 1.14f)
                    .graphicsLayer {
                        val scale = pressScale * micBreathingScale
                        scaleX = scale
                        scaleY = scale
                        alpha = if (isBusy) 0.86f else 1f
                    }
                    .shadow(
                        elevation = when {
                            isRecording && isDark -> 34.dp
                            isRecording -> 20.dp
                            isDark -> 24.dp
                            else -> 13.dp
                        },
                        shape = CircleShape,
                        ambientColor = micShadowColor,
                        spotColor = micShadowColor
                    )
                    .clip(CircleShape)
                    .background(micBrush)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = !isBusy,
                        role = Role.Button,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            animationScope.launch {
                                impactProgress.snapTo(0f)
                                impactProgress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(620, easing = FastOutSlowInEasing)
                                )
                            }
                            onMainAction()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.size(microphoneSize * 0.94f),
                    shape = CircleShape,
                    color = colors.micInner.copy(alpha = if (isDark) 0.92f else 0.88f),
                    border = BorderStroke(
                        width = if (isDark) 2.2.dp else 1.8.dp,
                        color = RecordingMint.copy(alpha = if (isDark) 1f else 0.7f)
                    ),
                    shadowElevation = if (isDark) 10.dp else 6.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                isGeneratingReport -> Icons.Default.AutoAwesome
                                isTranscribing -> Icons.Default.Description
                                isRecording -> Icons.Default.Stop
                                else -> Icons.Default.Mic
                            },
                            contentDescription = when {
                                isGeneratingReport -> "正在生成会议纪要"
                                isTranscribing -> "正在生成最终转录"
                                isRecording -> "结束录音"
                                else -> "开始录音"
                            },
                            tint = Color.White,
                            modifier = Modifier
                                .size(microphoneSize * 0.42f)
                                .graphicsLayer {
                                    scaleX = if (isPressed) 0.86f else 1f
                                    scaleY = if (isPressed) 0.86f else 1f
                                    rotationZ = if (isPressed) -4f else 0f
                                }
                        )
                    }
                }
            }
        }
        Text(
            text = formatDuration(durationSeconds),
            color = RecordingPurple,
            fontSize = 24.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                listOf(7, 13, 10, 17).forEachIndexed { index, barHeight ->
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(
                                (barHeight + if (isRecording || isBusy) {
                                (3 * abs(sin(phase + index))).toInt()
                                } else {
                                    0
                                }).dp
                            )
                            .clip(RoundedCornerShape(2.dp))
                            .background(RecordingPurple)
                    )
                }
            }
            Text(
                text = processingStage.ifBlank { status },
                color = RecordingInk.copy(alpha = 0.86f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isBusy && progressPercent != null) {
                Text(
                    text = "${progressPercent.coerceIn(0, 100)}%",
                    color = RecordingPurple,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (isBusy) {
            Surface(
                onClick = onCancelProcessing,
                shape = RoundedCornerShape(20.dp),
                color = RecordingSurfaceRaised,
                border = BorderStroke(1.dp, RecordingRed.copy(alpha = 0.7f))
            ) {
                Text(
                    text = if (isTranscribing) "终止最终转录" else "终止纪要生成",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    color = RecordingRed,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ReferenceTranscriptPanel(
    transcript: String,
    previewMode: String,
    isRecording: Boolean,
    realtimeSttRoute: com.oa.automation.infrastructure.service.RealtimeSttRouteState,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showFullTranscript by remember { mutableStateOf(false) }
    val visibleTranscript = remember(transcript) {
        transcriptPreviewWindow(transcript, BuildConfig.TRANSCRIPT_PREVIEW_MAX_CHARS)
    }
    val transcriptIsWindowed = visibleTranscript.length < transcript.length
    LaunchedEffect(visibleTranscript.length, isRecording) {
        if (isRecording) scrollState.scrollTo(scrollState.maxValue)
    }
    if (showFullTranscript) {
        AlertDialog(
            onDismissRequest = { showFullTranscript = false },
            title = { Text("完整转写", fontWeight = FontWeight.SemiBold) },
            text = {
                SelectionContainer {
                    Text(
                        text = transcript,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState()),
                        color = RecordingInk,
                        fontSize = 14.sp,
                        lineHeight = 23.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFullTranscript = false }) { Text("关闭") }
            },
            shape = RoundedCornerShape(18.dp),
            containerColor = RecordingSurfaceRaised,
            titleContentColor = RecordingInk,
            textContentColor = RecordingInk
        )
    }
    val isDark = LocalAppIsDarkTheme.current
    val panelShadow = if (isDark) Color.Transparent
                      else Color(0xFF000000).copy(alpha = 0.07f)
    val panelBorder: BorderStroke? = if (isDark) null else BorderStroke(
        width = 1.dp,
        color = RecordingBorder.copy(alpha = 0.7f)
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (isDark) 0.dp else 6.dp, RoundedCornerShape(16.dp), ambientColor = panelShadow),
        shape = RoundedCornerShape(16.dp),
        color = RecordingSurface,
        border = panelBorder
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = RecordingInk,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "转写内容",
                    color = RecordingInk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = previewMode,
                    color = RecordingMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${transcript.length} 字",
                    color = RecordingMuted,
                    fontSize = 9.sp
                )
                if (transcriptIsWindowed) {
                    IconButton(
                        onClick = { showFullTranscript = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.OpenInFull,
                            contentDescription = "查看完整转写",
                            tint = RecordingPurple,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            if (isRecording) {
                RealtimeSttStatusBar(route = realtimeSttRoute)
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(11.dp),
                color = if (isDark) RecordingSurface else LocalRecordingColors.current.transcriptSurface,
                border = if (isDark) null else BorderStroke(1.dp, RecordingBorder.copy(alpha = 0.7f))
            ) {
                if (transcript.isBlank()) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = RecordingMuted.copy(alpha = 0.48f),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (isRecording) "正在等待转写内容" else "暂无转写内容",
                                color = RecordingMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = visibleTranscript,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(12.dp),
                            color = RecordingInk,
                            fontSize = 14.sp,
                            lineHeight = 23.sp
                        )
                    }
                }
            }
        }
    }
}

internal fun transcriptPreviewWindow(transcript: String, maxChars: Int): String {
    if (maxChars <= 0 || transcript.length <= maxChars) return transcript

    val rawStart = transcript.length - maxChars
    val boundary = transcript.indexOfAny(
        chars = charArrayOf('\n', '。', '！', '？', '.', '!', '?'),
        startIndex = rawStart
    ).takeIf { it in rawStart until (rawStart + 160).coerceAtMost(transcript.length) }
    val start = if (boundary == null) rawStart else boundary + 1
    return "…\n${transcript.substring(start).trimStart()}"
}

@Composable
private fun ReferenceTextInputPanel(
    text: String,
    audioTranscript: String,
    importStatus: String,
    importedAudioDisplayName: String,
    isAudioBusy: Boolean,
    onTextChange: (String) -> Unit,
    onPickExternalFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalAppIsDarkTheme.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isDark) 4.dp else 5.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = if (isDark) Color.Black.copy(0.4f) else Color.Black.copy(0.06f)
            ),
        shape = RoundedCornerShape(18.dp),
        color = RecordingSurface,
        border = BorderStroke(1.dp, RecordingBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("导入内容", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                TextButton(onClick = onPickExternalFile) {
                        Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("其他应用打开", fontSize = 11.sp)
                }
            }
            if (importedAudioDisplayName.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = RecordingSurfaceRaised,
                    border = BorderStroke(1.dp, RecordingBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Headphones,
                            contentDescription = null,
                            tint = RecordingPurple,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = importedAudioDisplayName,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (isAudioBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "转写完成",
                                tint = RecordingMint,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
            val showAudioTranscript = text.isBlank() &&
                importedAudioDisplayName.isNotBlank() &&
                audioTranscript.isNotBlank()
            if (showAudioTranscript) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(11.dp),
                    color = RecordingSurfaceRaised,
                    border = BorderStroke(1.dp, RecordingBorder)
                ) {
                    SelectionContainer {
                        Text(
                            text = audioTranscript,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            color = RecordingInk,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    placeholder = { Text("输入文字，或从设备导入") },
                    shape = RoundedCornerShape(11.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = importStatus.ifBlank {
                        if (text.isBlank()) "支持文字与常见音频格式" else "已输入 ${text.length} 字"
                    },
                    modifier = Modifier.weight(1f),
                    color = RecordingMuted,
                    fontSize = 10.sp
                )
                Text(
                    text = if (importedAudioDisplayName.isNotBlank()) "音频转写" else "文字导入",
                    color = RecordingMuted,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun RecordingBottomControls(
    inputMode: InputMode,
    isBusy: Boolean,
    mainActionEnabled: Boolean,
    height: Dp,
    onVoice: () -> Unit,
    onImport: () -> Unit,
    onMainAction: () -> Unit
) {
    val isDark = LocalAppIsDarkTheme.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .shadow(
                elevation = if (isDark) 4.dp else 6.dp,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                ambientColor = if (isDark) Color.Black.copy(alpha = 0.5f)
                               else Color(0xFF000000).copy(alpha = 0.08f)
            ),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = RecordingSurface,
        border = BorderStroke(1.dp, RecordingBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RecordingModePill(
                text = "语音",
                icon = Icons.Default.Mic,
                selected = inputMode == InputMode.VOICE,
                onClick = onVoice
            )
            // 导入模式：中央生成按钮；语音模式：麦克风是唯一控制。
            if (inputMode == InputMode.IMPORT) {
                Surface(
                    onClick = onMainAction,
                    enabled = mainActionEnabled,
                    modifier = Modifier.size(54.dp),
                    shape = CircleShape,
                    color = if (isBusy) RecordingSurfaceRaised else RecordingPurple,
                    border = BorderStroke(
                        width = if (isDark) 1.5.dp else 2.dp,
                        color = RecordingPurple.copy(alpha = if (isDark) 0.8f else 0.6f)
                    ),
                    shadowElevation = if (isDark) 10.dp else 6.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isBusy) Icons.Default.Stop else Icons.Default.Summarize,
                            contentDescription = if (isBusy) "终止处理" else "生成会议纪要",
                            tint = if (isBusy) RecordingMuted else RecordingCanvas,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            RecordingModePill(
                text = "导入",
                icon = Icons.Default.FolderOpen,
                selected = inputMode == InputMode.IMPORT,
                onClick = onImport
            )
        }
    }
}

@Composable
private fun MeetingQuickActions(
    attachmentCount: Int,
    isRecording: Boolean,
    isPaused: Boolean,
    isTranscribing: Boolean,
    isGeneratingReport: Boolean,
    isImportingImages: Boolean,
    hasTranscript: Boolean,
    onTakePhoto: () -> Unit,
    onPickImages: () -> Unit,
    onOpenImages: () -> Unit,
    onGenerateReport: () -> Unit,
    onCancelReport: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = RecordingSurfaceRaised,
        border = BorderStroke(1.dp, RecordingBorder),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            MeetingMediaButton(
                icon = Icons.Default.PhotoCamera,
                label = "拍照",
                enabled = !isImportingImages,
                onClick = onTakePhoto
            )
            MeetingMediaButton(
                icon = Icons.Default.AddPhotoAlternate,
                label = if (isImportingImages) "导入中" else "上传",
                enabled = !isImportingImages,
                onClick = onPickImages
            )
            if (attachmentCount > 0) {
                Surface(
                    onClick = onOpenImages,
                    shape = RoundedCornerShape(8.dp),
                    color = LocalRecordingColors.current.countSurface,
                    border = BorderStroke(1.dp, RecordingPurple.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "$attachmentCount 张",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                        color = RecordingPurple,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            val canGenerate = hasTranscript && (!isRecording || isPaused) && !isTranscribing
            Surface(
                onClick = if (isGeneratingReport) onCancelReport else onGenerateReport,
                enabled = isGeneratingReport || canGenerate,
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                color = when {
                    isGeneratingReport -> RecordingRed
                    canGenerate -> RecordingPurple
                    else -> LocalRecordingColors.current.disabledSurface
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = when {
                        isGeneratingReport -> RecordingRed.copy(alpha = 0.8f)
                        canGenerate -> RecordingPurple.copy(alpha = 0.65f)
                        else -> RecordingBorder
                    }
                ),
                shadowElevation = if (canGenerate || isGeneratingReport) 4.dp else 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        isGeneratingReport -> CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.4.dp,
                            color = RecordingCanvas
                        )
                        isTranscribing -> CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp,
                            color = RecordingMuted
                        )
                        else -> Icon(
                            imageVector = Icons.Default.Summarize,
                            contentDescription = "生成会议纪要",
                            tint = if (canGenerate) RecordingCanvas else RecordingMuted,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeetingMediaButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        color = RecordingSurface,
        border = BorderStroke(1.dp, RecordingBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) RecordingPurple else RecordingMuted,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = if (enabled) RecordingInk else RecordingMuted,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RecordingModePill(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isDark = LocalAppIsDarkTheme.current
    Surface(
        onClick = onClick,
        modifier = Modifier.width(100.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) LocalRecordingColors.current.selectedSurface else Color.Transparent,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) RecordingPurple.copy(alpha = if (isDark) 0.65f else 0.5f)
                    else Color.Transparent
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) RecordingPurple else RecordingMuted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                color = if (selected) RecordingPurple else RecordingMuted,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun UtilityDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun RecordingMenuItem(
    icon: ImageVector,
    text: String,
    enabled: Boolean = true,
    tint: Color = RecordingInk,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(text, color = if (enabled) tint else RecordingMuted) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = if (enabled) tint else RecordingMuted) },
        enabled = enabled,
        onClick = onClick
    )
}

private fun recordingStatus(state: RecordingUiState): String = when {
    state.isFinalizingRecording -> "正在结束并保存录音"
    state.isRecording -> "正在录音..."
    state.isTranscribing -> state.transcriptionProgressStage.ifBlank { "正在生成最终转录" }
    state.isGeneratingReport -> state.reportProgressStage.ifBlank { "正在生成会议纪要" }
    state.hasRecording -> "录音已结束"
    else -> "点击麦克风开始录音"
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = safe % 3600 / 60
    val remaining = safe % 60
    return "%02d:%02d:%02d".format(Locale.US, hours, minutes, remaining)
}
