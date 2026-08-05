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
import androidx.compose.material.icons.filled.ContentPaste
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
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.STTLanguage
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudio
import com.oa.automation.infrastructure.textimport.ExternalTextSource
import com.oa.automation.ui.component.FlowingProgressBorder
import com.oa.automation.ui.component.ProcessingStatusRow
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
    canvas = Color(0xFF040C12),           // deeper space-black
    surface = Color(0xFF071520),
    surfaceRaised = Color(0xFF0C1F28),
    border = Color(0xFF1C3A44),
    ink = Color(0xFFF0F7F9),
    muted = Color(0xFF6E9098),
    primary = Color(0xFF1FD396),           // vivid cyan-mint
    blue = Color(0xFF38B2D1),
    mint = Color(0xFF4DDEAF),              // brighter mint for wave accents
    orange = Color(0xFFFFAA48),
    red = Color(0xFFFF5E56),
    pageGradient = listOf(Color(0xFF040C12), Color(0xFF051521), Color(0xFF040C12)),
    selectedSurface = Color(0xFF0A2C27),
    transcriptSurface = Color(0xFF051018),
    strongSelectedSurface = Color(0xFF115544),
    countSurface = Color(0xFF0F342D),
    disabledSurface = Color(0xFF14252C),
    micGradient = listOf(Color(0xFF168969), Color(0xFF074038), Color(0xFF041E1C)), // dark→core
    micInner = Color(0xFF073630),
    blueIconSurface = Color(0xFF0D2635),
    greenIconSurface = Color(0xFF0B2E28),
    orangeIconSurface = Color(0xFF2C2218)
)

private val LightRecordingColors = RecordingColors(
    canvas = Color(0xFFF3F7FB),           // crisp cool-white base
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEEF3F8),
    border = Color(0xFFD9E4EC),           // blue-tinted border
    ink = Color(0xFF141C28),
    muted = Color(0xFF64737E),
    primary = Color(0xFF0CAC7F),          // restrained mint-green
    blue = Color(0xFF2288AA),
    mint = Color(0xFF14A67A),
    orange = Color(0xFFD07D22),
    red = Color(0xFFDC4B45),
    pageGradient = listOf(Color(0xFFF3F7FB), Color(0xFFECF7F2), Color(0xFFF3F7FB)),
    selectedSurface = Color(0xFFDEF5EC),  // clear mint tint for selection
    transcriptSurface = Color(0xFFF0FBF7), // barely-tinted mint for transcript bg
    strongSelectedSurface = Color(0xFFCAEFE1),
    countSurface = Color(0xFFDCF5EA),
    disabledSurface = Color(0xFFE1E8EE),
    micGradient = listOf(Color(0xFF3DDEAD), Color(0xFF12B386), Color(0xFF087660)), // vivid clean gradient
    micInner = Color(0xFF0EA57B),
    blueIconSurface = Color(0xFFDFF2F9),
    greenIconSurface = Color(0xFFDAF5EE),
    orangeIconSurface = Color(0xFFFFF0D8)
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
    onNavigateBack: () -> Unit,
    onNavigateToReport: () -> Unit,
    onTitleChange: (String) -> Unit,
    onSaveTitle: () -> Unit,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    onSttEngineSelected: (STTEngineType) -> Unit,
    onSttLanguageSelected: (STTLanguage) -> Unit,
    onSwitchToVoice: () -> Unit,
    onSwitchToImport: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onAddMarker: () -> Unit,
    onStartJourney: () -> Unit,
    onSaveCurrentJourneyStage: () -> Unit,
    onPauseJourney: () -> Unit,
    onContinueJourney: () -> Unit,
    onAbandonRecording: () -> Unit,
    onGenerateReport: () -> Unit,
    onCancelTranscription: () -> Unit,
    onCancelReport: () -> Unit,
    onTextChange: (String) -> Unit,
    onPasteText: () -> Unit,
    onOpenExternalTextSource: (ExternalTextSource) -> Unit,
    onImportTextFile: () -> Unit,
    onImportAudioFile: () -> Unit,
    onGenerateFromImport: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickImages: () -> Unit,
    onDeleteAttachment: (MeetingAttachment) -> Unit,
    onRefreshAudio: () -> Unit,
    onSaveAudio: (ArchivedMeetingAudio) -> Unit,
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
    var audioDialogVisible by remember { mutableStateOf(false) }
    var abandonDialogVisible by remember { mutableStateOf(false) }
    val recordingColors = if (LocalAppIsDarkTheme.current) DarkRecordingColors else LightRecordingColors

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
                sttEngineType = uiState.sttEngineType,
                sttLanguage = uiState.sttLanguage,
                isSwitchingStt = uiState.isSwitchingSttEngine,
                isSwitchingLanguage = uiState.isSwitchingSttLanguage,
                onSttEngineSelected = onSttEngineSelected,
                onSttLanguageSelected = onSttLanguageSelected
            )
        }
    }

    if (imageDialogVisible) {
        UtilityDialog(title = "会议图片", onDismiss = { imageDialogVisible = false }) {
            MeetingImagesSection(
                attachments = uiState.attachments,
                onTakePhoto = onTakePhoto,
                onPickImages = onPickImages,
                onDelete = onDeleteAttachment
            )
        }
    }

    LaunchedEffect(audioDialogVisible) {
        if (audioDialogVisible) onRefreshAudio()
    }
    if (audioDialogVisible) {
        UtilityDialog(title = "会议音频", onDismiss = { audioDialogVisible = false }) {
            MeetingAudioExportCard(
                items = uiState.archivedAudio,
                isLoading = uiState.isLoadingAudio,
                busyAudioId = uiState.audioExportBusyId,
                statusMessage = uiState.audioExportMessage,
                isTranscribing = uiState.isTranscribing,
                onRefresh = onRefreshAudio,
                onSave = onSaveAudio,
                onShare = onShareAudio
            )
        }
    }

    if (abandonDialogVisible) {
        AlertDialog(
            onDismissRequest = { abandonDialogVisible = false },
            title = { Text("放弃本次录音") },
            text = { Text("本次尚未提交的录音和实时预览将被丢弃，是否继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        abandonDialogVisible = false
                        onAbandonRecording()
                    }
                ) {
                    Text("放弃", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { abandonDialogVisible = false }) { Text("继续录音") }
            },
            shape = RoundedCornerShape(18.dp)
        )
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

    FlowingProgressBorder(
        active = uiState.isTranscribing || uiState.isGeneratingReport,
        modifier = Modifier.fillMaxSize(),
        cornerRadius = 18.dp,
        inset = 2.dp,
        strokeWidth = 2.dp
    ) {
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
                            RecordingMenuItem(
                                icon = Icons.Default.Headphones,
                                text = "保存或分享音频",
                                onClick = {
                                    moreMenuExpanded = false
                                    audioDialogVisible = true
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
                            if (uiState.isRecording) {
                                HorizontalDivider()
                                RecordingMenuItem(
                                    icon = Icons.Default.Close,
                                    text = "放弃本次录音",
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = {
                                        moreMenuExpanded = false
                                        abandonDialogVisible = true
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
            if (uiState.inputMode == InputMode.VOICE) {
                SiriRecorderContent(
                    uiState = uiState,
                    onNavigateBack = onNavigateBack,
                    onOpenReport = onNavigateToReport,
                    hasExistingReport = uiState.hasReport,
                    onEditTitle = {
                        titleDraft = uiState.meetingTitle
                        titleEditorVisible = true
                    },
                    onOpenService = { serviceDialogVisible = true },
                    onOpenImages = { imageDialogVisible = true },
                    onOpenAudio = { audioDialogVisible = true },
                    onSwitchToImport = onSwitchToImport,
                    onAbandonRecording = { abandonDialogVisible = true },
                    onSelectTemplate = onSelectTemplate,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    onTogglePause = onTogglePause,
                    onAddMarker = onAddMarker,
                    onStartJourney = onStartJourney,
                    onSaveCurrentJourneyStage = onSaveCurrentJourneyStage,
                    onPauseJourney = onPauseJourney,
                    onContinueJourney = onContinueJourney,
                    onGenerateReport = onGenerateReport,
                    onCancelTranscription = onCancelTranscription,
                    onCancelReport = onCancelReport,
                    onSttLanguageSelected = onSttLanguageSelected,
                    onDismissError = onDismissError
                )
            } else {
                ImportRecordingContent(
                    uiState = uiState,
                    layout = layout,
                    onSelectTemplate = onSelectTemplate,
                    onSwitchToVoice = onSwitchToVoice,
                    onSwitchToImport = onSwitchToImport,
                    onTextChange = onTextChange,
                    onPasteText = onPasteText,
                    onOpenExternalTextSource = onOpenExternalTextSource,
                    onImportTextFile = onImportTextFile,
                    onImportAudioFile = onImportAudioFile,
                    onGenerateFromImport = onGenerateFromImport,
                    onCancelTranscription = onCancelTranscription,
                    onCancelReport = onCancelReport,
                    onDismissError = onDismissError
                )
            }
        }
    }
    }
}
}

@Composable
private fun VoiceRecordingContent(
    uiState: RecordingUiState,
    layout: RecordingLayoutSpec,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    onSttEngineSelected: (STTEngineType) -> Unit,
    onSttLanguageSelected: (STTLanguage) -> Unit,
    onSwitchToVoice: () -> Unit,
    onSwitchToImport: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
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
            selectedTemplateName = uiState.reportTemplate.selectedName,
            cardHeight = layout.templateCardHeight,
            onSelectTemplate = onSelectTemplate
        )
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
            onMainAction = if (uiState.isRecording) onStopRecording else onStartRecording,
            onCancelProcessing = if (uiState.isTranscribing) onCancelTranscription else onCancelReport
        )
        ReferenceTranscriptPanel(
            transcript = uiState.liveTranscript,
            previewMode = uiState.transcriptPreviewMode,
            isRecording = uiState.isRecording,
            sttEngineType = uiState.sttEngineType,
            sttLanguage = uiState.sttLanguage,
            isSwitchingStt = uiState.isSwitchingSttEngine,
            isSwitchingLanguage = uiState.isSwitchingSttLanguage,
            onSttEngineSelected = onSttEngineSelected,
            onSttLanguageSelected = onSttLanguageSelected,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = layout.transcriptMinHeight)
        )
        MeetingQuickActions(
            attachmentCount = uiState.attachments.size,
            isRecording = uiState.isRecording,
            isTranscribing = uiState.isTranscribing,
            isGeneratingReport = uiState.isGeneratingReport,
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
            mainActionEnabled = !uiState.isTranscribing && !uiState.isGeneratingReport,
            height = layout.controlHeight,
            onVoice = onSwitchToVoice,
            onImport = onSwitchToImport,
            onMainAction = if (uiState.isRecording) onStopRecording else onStartRecording
        )
    }
}

@Composable
private fun ImportRecordingContent(
    uiState: RecordingUiState,
    layout: RecordingLayoutSpec,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    onSwitchToVoice: () -> Unit,
    onSwitchToImport: () -> Unit,
    onTextChange: (String) -> Unit,
    onPasteText: () -> Unit,
    onOpenExternalTextSource: (ExternalTextSource) -> Unit,
    onImportTextFile: () -> Unit,
    onImportAudioFile: () -> Unit,
    onGenerateFromImport: () -> Unit,
    onCancelTranscription: () -> Unit,
    onCancelReport: () -> Unit,
    onDismissError: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = layout.pagePadding),
        verticalArrangement = Arrangement.spacedBy(if (layout.compact) 8.dp else 11.dp)
    ) {
        MeetingTemplateStrip(
            templates = uiState.presetTemplates,
            selectedTemplateName = uiState.reportTemplate.selectedName,
            cardHeight = layout.templateCardHeight,
            onSelectTemplate = onSelectTemplate
        )
        AnimatedVisibility(visible = uiState.error != null) {
            uiState.error?.let { CompactErrorBanner(error = it, onDismiss = onDismissError) }
        }
        ReferenceTextInputPanel(
            text = uiState.manualTextInput,
            audioTranscript = uiState.liveTranscript,
            importStatus = uiState.textImportStatus,
            importedAudioDisplayName = uiState.importedAudioDisplayName,
            isAudioBusy = uiState.isImportingAudio || uiState.isTranscribing,
            externalSources = uiState.externalTextSources,
            onTextChange = onTextChange,
            onPaste = onPasteText,
            onOpenExternalSource = onOpenExternalTextSource,
            onImportTextFile = onImportTextFile,
            onImportAudioFile = onImportAudioFile,
            modifier = Modifier.weight(1f)
        )
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
        val hasImportContent = uiState.manualTextInput.isNotBlank() ||
            (uiState.hasRecording && uiState.liveTranscript.isNotBlank())
        val isImportBusy = uiState.isImportingAudio ||
            uiState.isTranscribing ||
            uiState.isGeneratingReport
        RecordingBottomControls(
            inputMode = InputMode.IMPORT,
            isBusy = isImportBusy,
            mainActionEnabled = isImportBusy || hasImportContent,
            height = layout.controlHeight,
            onVoice = onSwitchToVoice,
            onImport = onSwitchToImport,
            onMainAction = when {
                uiState.isImportingAudio || uiState.isTranscribing -> onCancelTranscription
                uiState.isGeneratingReport -> onCancelReport
                else -> onGenerateFromImport
            }
        )
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
    var allTemplatesVisible by remember { mutableStateOf(false) }
    var previewTemplate by remember { mutableStateOf<PresetReportTemplate?>(null) }
    val compactCards = cardHeight <= 104.dp

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

    if (allTemplatesVisible) {
        AlertDialog(
            onDismissRequest = { allTemplatesVisible = false },
            title = { Text("全部纪要模板") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(templates, key = { _, item -> item.name }) { index, template ->
                        val selected = template.name == selectedTemplateName
                        Surface(
                            onClick = {
                                onSelectTemplate(template)
                                allTemplatesVisible = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) LocalRecordingColors.current.selectedSurface else RecordingSurfaceRaised,
                            border = BorderStroke(
                                1.dp,
                                if (selected) RecordingPurple else RecordingBorder
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                TemplateIcon(index, template.name, 36.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(template.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        template.subtitle.ifBlank { "会议纪要模板" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = RecordingMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (selected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = RecordingPurple)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { allTemplatesVisible = false }) { Text("关闭") }
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
            Surface(
                onClick = { allTemplatesVisible = true },
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
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
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "查看全部模板",
                        tint = RecordingMuted,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                templates.forEachIndexed { index, template ->
                    val selected = template.name == selectedTemplateName
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .combinedClickable(
                                onClick = { onSelectTemplate(template) },
                                onLongClick = { previewTemplate = template }
                            ),
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) LocalRecordingColors.current.selectedSurface else RecordingSurfaceRaised,
                        border = BorderStroke(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) RecordingPurple else RecordingBorder
                        ),
                        shadowElevation = if (selected) 5.dp else 0.dp
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 6.dp, vertical = 7.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TemplateIcon(index, template.name, if (compactCards) 27.dp else 30.dp)
                                Text(
                                    text = template.name,
                                    color = RecordingInk,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip
                                )
                                Text(
                                    text = template.subtitle.ifBlank { "会议纪要" },
                                    color = RecordingMuted,
                                    fontSize = 9.sp,
                                    lineHeight = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Clip
                                )
                            }
                            if (selected) {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(5.dp)
                                        .size(18.dp),
                                    shape = CircleShape,
                                    color = RecordingPurple
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = RecordingCanvas,
                                            modifier = Modifier.size(12.dp)
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
    sttEngineType: STTEngineType,
    sttLanguage: STTLanguage,
    isSwitchingStt: Boolean,
    isSwitchingLanguage: Boolean,
    onSttEngineSelected: (STTEngineType) -> Unit,
    onSttLanguageSelected: (STTLanguage) -> Unit,
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
            RuntimeQuickSelectors(
                sttEngineType = sttEngineType,
                sttLanguage = sttLanguage,
                isSwitchingStt = isSwitchingStt,
                isSwitchingLanguage = isSwitchingLanguage,
                onSttEngineSelected = onSttEngineSelected,
                onSttLanguageSelected = onSttLanguageSelected
            )
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

@Composable
private fun RuntimeQuickSelectors(
    sttEngineType: STTEngineType,
    sttLanguage: STTLanguage,
    isSwitchingStt: Boolean,
    isSwitchingLanguage: Boolean,
    onSttEngineSelected: (STTEngineType) -> Unit,
    onSttLanguageSelected: (STTLanguage) -> Unit
) {
    val normalizedEngine = if (sttEngineType == STTEngineType.TENCENT_HYBRID) {
        STTEngineType.TENCENT_HYBRID
    } else {
        STTEngineType.FASTER_WHISPER
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = RecordingSurfaceRaised,
            border = BorderStroke(1.dp, RecordingBorder)
        ) {
            Row(
                modifier = Modifier.padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                STTLanguage.entries.forEach { language ->
                    val selected = language == sttLanguage
                    Surface(
                        onClick = { if (!isSwitchingLanguage) onSttLanguageSelected(language) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected) LocalRecordingColors.current.strongSelectedSurface else Color.Transparent,
                        border = if (selected) BorderStroke(1.dp, RecordingPurple.copy(alpha = 0.72f)) else null
                    ) {
                        Box(
                            modifier = Modifier.size(width = 38.dp, height = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected && isSwitchingLanguage) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = RecordingPurple
                                )
                            } else {
                                Text(
                                    text = if (language == STTLanguage.CHINESE) "CN" else "EN",
                                    color = if (selected) RecordingPurple else RecordingMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(9.dp),
            color = RecordingSurfaceRaised,
            border = BorderStroke(1.dp, RecordingBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                listOf(
                    STTEngineType.FASTER_WHISPER to "智悟本地模型",
                    STTEngineType.TENCENT_HYBRID to "智悟增强云模型"
                ).forEach { (engine, label) ->
                    val selected = normalizedEngine == engine
                    Surface(
                        onClick = { if (!isSwitchingStt) onSttEngineSelected(engine) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected) LocalRecordingColors.current.strongSelectedSurface else Color.Transparent,
                        border = if (selected) BorderStroke(1.dp, RecordingPurple.copy(alpha = 0.72f)) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .padding(horizontal = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (selected && isSwitchingStt) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.4.dp,
                                    color = RecordingPurple
                                )
                                Spacer(Modifier.width(3.dp))
                            }
                            Text(
                                text = label,
                                color = if (selected) RecordingPurple else RecordingMuted,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                        }
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
    externalSources: List<ExternalTextSource>,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onOpenExternalSource: (ExternalTextSource) -> Unit,
    onImportTextFile: () -> Unit,
    onImportAudioFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    var importExpanded by remember { mutableStateOf(false) }
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
                Box {
                    TextButton(onClick = { importExpanded = true }) {
                        Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("应用导入", fontSize = 11.sp)
                    }
                    DropdownMenu(expanded = importExpanded, onDismissRequest = { importExpanded = false }) {
                        externalSources.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.label) },
                                onClick = {
                                    importExpanded = false
                                    onOpenExternalSource(source)
                                }
                            )
                        }
                        if (externalSources.isNotEmpty()) HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("本地文本文件") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                            onClick = {
                                importExpanded = false
                                onImportTextFile()
                            }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ImportSourceButton(
                    text = "粘贴",
                    icon = Icons.Default.ContentPaste,
                    onClick = onPaste,
                    modifier = Modifier.weight(1f)
                )
                ImportSourceButton(
                    text = "文本文件",
                    icon = Icons.Default.FolderOpen,
                    onClick = onImportTextFile,
                    modifier = Modifier.weight(1f)
                )
                ImportSourceButton(
                    text = "音频文件",
                    icon = Icons.Default.Headphones,
                    enabled = !isAudioBusy,
                    onClick = onImportAudioFile,
                    modifier = Modifier.weight(1f)
                )
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
                    placeholder = { Text("粘贴文字，或选择文本/音频文件") },
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
private fun ImportSourceButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) RecordingSurfaceRaised else RecordingSurfaceRaised.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, RecordingBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (enabled) RecordingPurple else RecordingMuted
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = if (enabled) RecordingInk else RecordingMuted
            )
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
    isTranscribing: Boolean,
    isGeneratingReport: Boolean,
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
                onClick = onTakePhoto
            )
            MeetingMediaButton(
                icon = Icons.Default.AddPhotoAlternate,
                label = "上传",
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
            Button(
                onClick = if (isGeneratingReport) onCancelReport else onGenerateReport,
                enabled = isGeneratingReport || hasTranscript && !isRecording && !isTranscribing,
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGeneratingReport) RecordingRed else RecordingPurple,
                    contentColor = RecordingCanvas,
                    disabledContainerColor = LocalRecordingColors.current.disabledSurface,
                    disabledContentColor = RecordingMuted
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
            ) {
                if (isTranscribing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.8.dp,
                        color = RecordingMuted
                    )
                } else {
                    Icon(
                        imageVector = if (isGeneratingReport) Icons.Default.Stop else Icons.Default.Summarize,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = when {
                        isGeneratingReport -> "终止生成"
                        isTranscribing -> "整理中"
                        else -> "生成纪要"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun MeetingMediaButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
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
                tint = RecordingPurple,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = RecordingInk,
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
