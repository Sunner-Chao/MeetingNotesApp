package com.oa.automation.ui.screen.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.oa.automation.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.AgentProvider
import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.JourneyStageStatus
import com.oa.automation.domain.model.JourneyStatus
import com.oa.automation.domain.model.StageDraftStatus
import com.oa.automation.domain.model.StageDraftVersion
import com.oa.automation.domain.model.JourneyEdition
import com.oa.automation.domain.model.JourneyEditionStatus
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.domain.model.CommunitySyncStatus
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.repository.JourneyRepository
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.domain.repository.StageDraftRepository
import com.oa.automation.domain.repository.JourneyEditionRepository
import com.oa.automation.domain.repository.PublishedPostRepository
import com.oa.automation.domain.repository.CommunitySyncRepository
import com.oa.automation.application.usecase.GenerateStageDraftUseCase
import com.oa.automation.application.usecase.GenerateJourneyEditionUseCase
import com.oa.automation.application.usecase.CreatePublishedPostSnapshotUseCase
import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.Transcript
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.MeetingOrigin
import com.oa.automation.domain.model.RecordingMarker
import com.oa.automation.domain.model.canonicalMeetingTranscripts
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.STTLanguage
import com.oa.automation.domain.model.displayTitle
import com.oa.automation.infrastructure.attachment.MeetingAttachmentStore
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudio
import com.oa.automation.infrastructure.audio.MeetingAudioArchiveService
import com.oa.automation.infrastructure.audio.ImportedAudioStore
import com.oa.automation.infrastructure.audio.PreparedMeetingAudioShare
import com.oa.automation.infrastructure.background.BackgroundTaskScheduler
import com.oa.automation.infrastructure.background.BackgroundTaskState
import com.oa.automation.infrastructure.service.RecordingService
import com.oa.automation.infrastructure.service.RecordingSessionController
import com.oa.automation.infrastructure.service.RecordingSessionState
import com.oa.automation.infrastructure.service.RealtimeSttRouteState
import com.oa.automation.infrastructure.stt.StreamingTranscriptUpdate
import com.oa.automation.infrastructure.service.durationSecondsAt
import com.oa.automation.infrastructure.stt.STTServiceClient
import com.oa.automation.infrastructure.textimport.SharedTextImportCoordinator
import com.oa.automation.infrastructure.textimport.ExternalTextSource
import com.oa.automation.infrastructure.textimport.ExternalTextSourceLauncher
import com.oa.automation.infrastructure.community.PublishedPostMediaStore
import com.oa.automation.infrastructure.db.PublishedPostMediaEntity
import com.oa.automation.locale.SimplifiedChineseText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RecordingUiState(
    val meetingTitle: String = "",
    val sttEngineLabel: String = "",
    val sttEngineType: STTEngineType = STTEngineType.FASTER_WHISPER,
    val sttLanguage: STTLanguage = STTLanguage.CHINESE,
    val agentProvider: AgentProvider = AgentProvider.CODEX_CLI,
    val isSwitchingSttEngine: Boolean = false,
    val isSwitchingSttLanguage: Boolean = false,
    val isSavingAgentProvider: Boolean = false,
    val isRecordingActionPending: Boolean = false,
    val isFinalizingRecording: Boolean = false,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val hasRecording: Boolean = false,
    val hasReport: Boolean = false,
    val recordingDuration: Long = 0,
    val audioLevel: Float = 0f,
    val liveTranscript: String = "",
    val isTranscribing: Boolean = false,
    val transcriptionProgressPercent: Int? = null,
    val transcriptionProgressStage: String = "",
    val transcriptionProgressIndeterminate: Boolean = false,
    val isSavingTitle: Boolean = false,
    val transcriptPreviewMode: String = "流式预览",
    val realtimeSttRoute: RealtimeSttRouteState = RealtimeSttRouteState.IDLE,
    val presetTemplates: List<PresetReportTemplate> = emptyList(),
    val reportTemplate: ReportTemplateConfig = ReportTemplateConfig(),
    val selectedRecordingTemplateName: String? = null,
    val journey: Journey? = null,
    val currentJourneyStage: JourneyStage? = null,
    val latestSavedJourneyStage: JourneyStage? = null,
    val latestStageDraft: StageDraftVersion? = null,
    val isGeneratingStageDraft: Boolean = false,
    val isSavingStageDraft: Boolean = false,
    val stageDraftEditorVisible: Boolean = false,
    val latestJourneyEdition: JourneyEdition? = null,
    val isGeneratingJourneyEdition: Boolean = false,
    val isSavingJourneyEdition: Boolean = false,
    val journeyEditionEditorVisible: Boolean = false,
    val latestPublishedPost: PublishedPost? = null,
    val publishedPostMedia: List<PublishedPostMediaSummary> = emptyList(),
    val communitySyncState: com.oa.automation.domain.model.CommunitySyncState? = null,
    val isCreatingPublishedPost: Boolean = false,
    val isSavingPublishedPost: Boolean = false,
    val publishedPostReviewVisible: Boolean = false,
    val journeyStageCount: Int = 0,
    val isJourneyActionPending: Boolean = false,
    val journeyStatusMessage: String = "",
    val error: String? = null,
    val requiresLogin: Boolean = false,
    val hasPermission: Boolean = false,
    val inputMode: InputMode = InputMode.VOICE,
    val manualTextInput: String = "",
    val textImportStatus: String = "",
    val importedAudioDisplayName: String = "",
    val externalTextSources: List<ExternalTextSource> = emptyList(),
    // Transcript picker state
    val showTranscriptPicker: Boolean = false,
    val pendingStreamingText: String = "",
    val pendingBackendText: String = "",
    val attachments: List<MeetingAttachment> = emptyList(),
    val isImportingImages: Boolean = false,
    val imageImportCompleted: Int = 0,
    val imageImportTotal: Int = 0,
    val isGeneratingReport: Boolean = false,
    val reportProgressPercent: Int? = null,
    val reportProgressStage: String = "",
    val reportProgressIndeterminate: Boolean = false,
    val reportReadyToOpen: Boolean = false,
    val archivedAudio: List<ArchivedMeetingAudio> = emptyList(),
    val isLoadingAudio: Boolean = false,
    val audioExportBusyId: String? = null,
    val pendingAudioExport: PendingMeetingAudioExport? = null,
    val audioExportMessage: String = "",
    val isImportingAudio: Boolean = false,
    val recordingMarkers: List<Long> = emptyList(),
    val recordingMarkerAnchors: List<String> = emptyList(),
    val activePhotoMarker: RecordingMarker? = null
)

internal enum class RecordingMediaRequest {
    CHOOSE_SOURCE,
    CAMERA,
    GALLERY
}

internal data class RecordingUiEffect(
    val mediaRequest: RecordingMediaRequest,
    val recordingMarkerId: String? = null
)

internal data class StudyStageFinalizationSnapshot(
    val stageId: String,
    val transcript: String,
    val durationSeconds: Long,
    val markerCount: Int,
    val attachmentCount: Int
)

internal data class ResolvedStudyStageEvidence(
    val transcriptDelta: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val isMeaningful: Boolean
)

internal data class ImageImportTarget(
    val journeyStageId: String?,
    val recordingMarker: RecordingMarker?
)

internal data class ImageImportSummary(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val firstFailureMessage: String?
) {
    fun failureMessage(): String? = when {
        failed == 0 -> null
        succeeded == 0 -> "图片导入失败（$failed/$total）：${firstFailureMessage ?: "请稍后重试"}"
        else -> "已导入 $succeeded 张，$failed 张失败：${firstFailureMessage ?: "请稍后重试"}"
    }
}

internal fun shouldRecoverImportedTranscription(
    meeting: Meeting?,
    hasTranscript: Boolean,
    taskState: BackgroundTaskState,
    audioFileAvailable: Boolean,
    recoveryAlreadyAttempted: Boolean
): Boolean = meeting?.origin == MeetingOrigin.FILE_IMPORT &&
    !hasTranscript &&
    taskState == BackgroundTaskState.FAILED &&
    audioFileAvailable &&
    !recoveryAlreadyAttempted

data class PublishedPostMediaSummary(
    val id: String,
    val displayName: String,
    val included: Boolean
)

private fun List<PublishedPostMediaEntity>.toSummaries(): List<PublishedPostMediaSummary> =
    map {
        PublishedPostMediaSummary(
            id = it.id,
            displayName = it.displayName,
            included = it.status != PublishedPostMediaStore.EXCLUDED
        )
    }

internal fun recordingActionPending(session: RecordingSessionState): Boolean = when {
    session.error != null -> false
    session.isStarting || session.isStopping -> true
    session.isRecording -> false
    session.status == "录音启动已取消" -> false
    else -> false
}

internal fun isLiveRecordingFinalizing(session: RecordingSessionState): Boolean =
    session.isStopping || session.status == "正在保存实时转写"

internal fun isActiveRecordingSessionForMeeting(
    session: RecordingSessionState,
    meetingId: String
): Boolean = meetingId.isNotBlank() &&
    session.meetingId == meetingId &&
    (session.isRecording || session.isStarting || session.isStopping)

internal const val RECORDING_TEMPLATE_REQUIRED_MESSAGE =
    "请首先选择上方的模板，然后再进行录音！"

internal fun isRecordingActionEnabled(state: RecordingUiState): Boolean =
    !state.isRecordingActionPending &&
        !state.isFinalizingRecording &&
        !state.isJourneyActionPending &&
        !state.isGeneratingReport

internal fun isRecordingMainActionEnabled(state: RecordingUiState): Boolean =
    isRecordingActionEnabled(state) &&
        (state.isRecording || !state.selectedRecordingTemplateName.isNullOrBlank())

internal fun canStartImageImport(state: RecordingUiState): Boolean = !state.isImportingImages

internal fun imageImportProgressLabel(state: RecordingUiState): String? =
    if (state.isImportingImages && state.imageImportTotal > 0) {
        "正在导入图片 ${state.imageImportCompleted.coerceAtMost(state.imageImportTotal)}/${state.imageImportTotal}"
    } else {
        null
    }

internal fun resolveImageImportTarget(
    state: RecordingUiState,
    recordingMarkers: List<RecordingMarker>,
    recordingMarkerId: String?
): ImageImportTarget? {
    val marker = recordingMarkerId?.let { markerId ->
        recordingMarkers.firstOrNull { candidate -> candidate.id == markerId }
            ?: return null
    }
    return ImageImportTarget(
        journeyStageId = marker?.journeyStageId
            ?: state.currentJourneyStage?.id
            ?: state.latestSavedJourneyStage?.id,
        recordingMarker = marker
    )
}

internal fun summarizeImageImport(results: List<Result<*>>): ImageImportSummary {
    val succeeded = results.count { it.isSuccess }
    return ImageImportSummary(
        total = results.size,
        succeeded = succeeded,
        failed = results.size - succeeded,
        firstFailureMessage = results.firstNotNullOfOrNull { result ->
            result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
        }
    )
}

internal enum class RecordingMainAction {
    START,
    STOP
}

internal fun recordingMainAction(state: RecordingUiState): RecordingMainAction =
    if (state.isRecording) RecordingMainAction.STOP else RecordingMainAction.START

internal fun RecordingUiState.resetForMeetingChange(): RecordingUiState = copy(
    meetingTitle = "",
    isSwitchingSttEngine = false,
    isSwitchingSttLanguage = false,
    isSavingAgentProvider = false,
    isRecordingActionPending = false,
    isFinalizingRecording = false,
    isRecording = false,
    isPaused = false,
    hasRecording = false,
    hasReport = false,
    recordingDuration = 0,
    audioLevel = 0f,
    liveTranscript = "",
    isTranscribing = false,
    transcriptionProgressPercent = null,
    transcriptionProgressStage = "",
    transcriptionProgressIndeterminate = false,
    isSavingTitle = false,
    transcriptPreviewMode = "流式预览",
    realtimeSttRoute = RealtimeSttRouteState.IDLE,
    selectedRecordingTemplateName = null,
    journey = null,
    currentJourneyStage = null,
    latestSavedJourneyStage = null,
    latestStageDraft = null,
    isGeneratingStageDraft = false,
    isSavingStageDraft = false,
    stageDraftEditorVisible = false,
    latestJourneyEdition = null,
    isGeneratingJourneyEdition = false,
    isSavingJourneyEdition = false,
    journeyEditionEditorVisible = false,
    latestPublishedPost = null,
    publishedPostMedia = emptyList(),
    communitySyncState = null,
    isCreatingPublishedPost = false,
    isSavingPublishedPost = false,
    publishedPostReviewVisible = false,
    journeyStageCount = 0,
    isJourneyActionPending = false,
    journeyStatusMessage = "",
    error = null,
    requiresLogin = false,
    inputMode = InputMode.VOICE,
    manualTextInput = "",
    textImportStatus = "",
    importedAudioDisplayName = "",
    showTranscriptPicker = false,
    pendingStreamingText = "",
    pendingBackendText = "",
    attachments = emptyList(),
    isImportingImages = false,
    imageImportCompleted = 0,
    imageImportTotal = 0,
    isGeneratingReport = false,
    reportProgressPercent = null,
    reportProgressStage = "",
    reportProgressIndeterminate = false,
    reportReadyToOpen = false,
    archivedAudio = emptyList(),
    isLoadingAudio = false,
    audioExportBusyId = null,
    pendingAudioExport = null,
    audioExportMessage = "",
    isImportingAudio = false,
    recordingMarkers = emptyList(),
    recordingMarkerAnchors = emptyList(),
    activePhotoMarker = null
)

enum class InputMode { VOICE, IMPORT }
enum class TranscriptSource { STREAMING, BACKEND }
enum class MeetingAudioExportAction { SAVE, SHARE }

data class PendingMeetingAudioExport(
    val meetingId: String,
    val audioId: String,
    val prepared: PreparedMeetingAudioShare,
    val action: MeetingAudioExportAction
)

@OptIn(FlowPreview::class)
class RecordingViewModel(
    private val configDataStore: ConfigDataStore,
    private val meetingRepository: MeetingRepository,
    private val journeyRepository: JourneyRepository,
    private val stageDraftRepository: StageDraftRepository,
    private val journeyEditionRepository: JourneyEditionRepository,
    private val publishedPostRepository: PublishedPostRepository,
    private val reportRepository: ReportRepository,
    private val attachmentStore: MeetingAttachmentStore,
    private val recordingController: RecordingSessionController,
    private val taskScheduler: BackgroundTaskScheduler,
    private val sharedTextImportCoordinator: SharedTextImportCoordinator,
    private val externalTextSourceLauncher: ExternalTextSourceLauncher,
    private val audioArchiveService: MeetingAudioArchiveService,
    private val importedAudioStore: ImportedAudioStore,
    private val generateStageDraftUseCase: GenerateStageDraftUseCase,
    private val generateJourneyEditionUseCase: GenerateJourneyEditionUseCase,
    private val createPublishedPostSnapshotUseCase: CreatePublishedPostSnapshotUseCase,
    private val communitySyncRepository: CommunitySyncRepository,
    private val publishedPostMediaStore: PublishedPostMediaStore,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()
    private val _effects = MutableSharedFlow<RecordingUiEffect>(extraBufferCapacity = 8)
    internal val effects: SharedFlow<RecordingUiEffect> = _effects.asSharedFlow()

    private var currentMeetingId: String = ""
    private var latestStreamingText: String = ""
    private var committedStreamingText: String = ""
    private var previewStreamingText: String = ""
    private var preservedStreamingText: String = ""
    private var currentStreamingSessionId: String = ""
    private var existingTranscriptText: String = ""
    private var recordingMarkerRecords: List<RecordingMarker> = emptyList()
    private var attachmentCollectionJob: Job? = null
    private var journeyCollectionJob: Job? = null
    private var journeyStageCollectionJob: Job? = null
    private var stageDraftCollectionJob: Job? = null
    private var journeyEditionCollectionJob: Job? = null
    private var publishedPostCollectionJob: Job? = null
    private var publishedPostMediaCollectionJob: Job? = null
    private var communitySyncCollectionJob: Job? = null
    private var transcriptionCollectionJob: Job? = null
    private var reportCollectionJob: Job? = null
    private var pendingRecordingStartJob: Job? = null
    private var audioRefreshJob: Job? = null
    private var audioImportJob: Job? = null
    private var imageImportJob: Job? = null
    private var audioImportToken: String? = null
    private var pendingExternalTextMeetingId: String? = null
    private var recordingTimerJob: Job? = null
    private var activeTimerStartedAtMs: Long? = null
    private var studyJourneyAutomationJob: Job? = null
    private val studyJourneyAutomationMutex = Mutex()
    private var observedRecordingSession: RecordingSessionState? = null
    private var trackedStudyStageId: String? = null
    private var trackedStudyStageTranscriptBaseline: String = ""
    private var trackedStudyStageStartedDurationSeconds: Long = 0L
    private var pendingStudyStageTranscriptBaseline: String? = null
    private var trackedReportTaskId: java.util.UUID? = null
    private val recoveredImportMeetingIds = mutableSetOf<String>()

    init {
        _uiState.update {
            it.copy(externalTextSources = externalTextSourceLauncher.availableSources())
        }
        viewModelScope.launch {
            configDataStore.appConfigFlow.collect { config ->
                _uiState.update {
                    it.copy(
                        sttEngineLabel = config.sttConfig.engineType.displayName,
                        sttEngineType = config.sttConfig.engineType,
                        sttLanguage = config.sttConfig.language,
                        agentProvider = config.llmConfig.agentProvider
                    )
                }
            }
        }
        viewModelScope.launch {
            recordingController.state.collect { session ->
                if (session.meetingId.isBlank() || session.meetingId != currentMeetingId) return@collect
                val previousSession = observedRecordingSession
                observedRecordingSession = session
                val isFinalizing = isLiveRecordingFinalizing(session)
                val clearTranscriptionState = isFinalizing ||
                    session.error != null || session.status == "录音启动已取消"
                _uiState.update {
                    it.copy(
                        isRecording = session.isRecording && !session.isStopping,
                        isPaused = session.isPaused,
                        isRecordingActionPending = recordingActionPending(session) || isFinalizing,
                        isFinalizingRecording = isFinalizing,
                        recordingDuration = session.durationSecondsAt(SystemClock.elapsedRealtime()),
                        audioLevel = session.audioLevel,
                        transcriptPreviewMode = session.status,
                        realtimeSttRoute = session.realtimeSttRoute,
                        isTranscribing = if (clearTranscriptionState) false else it.isTranscribing,
                        transcriptionProgressPercent = if (clearTranscriptionState) {
                            null
                        } else {
                            it.transcriptionProgressPercent
                        },
                        transcriptionProgressStage = if (clearTranscriptionState) {
                            ""
                        } else {
                            it.transcriptionProgressStage
                        },
                        transcriptionProgressIndeterminate = if (clearTranscriptionState) {
                            false
                        } else {
                            it.transcriptionProgressIndeterminate
                        },
                        error = session.error ?: it.error,
                        requiresLogin = session.error == RecordingService.AUTH_REQUIRED_MESSAGE ||
                            it.requiresLogin
                    )
                }
                synchronizeRecordingTimer(session)
                handleStudyJourneyRecordingTransition(previousSession, session)
            }
        }
        viewModelScope.launch {
            recordingController.state
                .filter { session ->
                    session.meetingId.isNotBlank() && session.meetingId == currentMeetingId
                }
                .mapNotNull { it.streamUpdate }
                .distinctUntilChanged()
                .sample(BuildConfig.STT_STREAM_UI_UPDATE_INTERVAL_MS.toLong())
                .collect(::updateStreamingPreview)
        }
        viewModelScope.launch {
            sharedTextImportCoordinator.pending.collect { pending ->
                pending ?: return@collect
                val targetMeetingId = pendingExternalTextMeetingId ?: currentMeetingId
                appendImportedText(pending.text, targetMeetingId)
                pendingExternalTextMeetingId = null
                sharedTextImportCoordinator.consume(pending.id)
            }
        }
    }

    private fun updateState(transform: (RecordingUiState) -> RecordingUiState) {
        _uiState.update(transform)
    }

    private fun isCurrentMeeting(meetingId: String): Boolean =
        meetingId.isNotBlank() && currentMeetingId == meetingId

    private fun resetStreamingPreviewState() {
        latestStreamingText = ""
        committedStreamingText = ""
        previewStreamingText = ""
        preservedStreamingText = ""
        currentStreamingSessionId = ""
        existingTranscriptText = ""
    }

    private fun synchronizeRecordingTimer(session: RecordingSessionState) {
        val startedAt = session.startedAtElapsedRealtimeMs
        val timerActive = startedAt != null &&
            (session.isRecording || session.isStopping) &&
            session.meetingId == currentMeetingId
        if (!timerActive) {
            recordingTimerJob?.cancel()
            recordingTimerJob = null
            activeTimerStartedAtMs = null
            return
        }
        if (activeTimerStartedAtMs == startedAt && recordingTimerJob?.isActive == true) return

        recordingTimerJob?.cancel()
        activeTimerStartedAtMs = startedAt
        recordingTimerJob = viewModelScope.launch {
            while (isActive) {
                val latest = recordingController.state.value
                if (
                    latest.meetingId != currentMeetingId ||
                    latest.startedAtElapsedRealtimeMs != startedAt
                ) {
                    break
                }
                _uiState.update {
                    it.copy(recordingDuration = latest.durationSecondsAt(SystemClock.elapsedRealtime()))
                }
                delay(RECORDING_TIMER_REFRESH_MS)
            }
        }
    }

    fun loadMeeting(meetingId: String) {
        if (meetingId.isBlank()) return
        val meetingChanged = currentMeetingId != meetingId
        if (meetingChanged) {
            if (currentMeetingId.isNotBlank()) {
                requestRecordingStop(showNoRecordingError = false)
            }
            pendingRecordingStartJob?.cancel()
            pendingRecordingStartJob = null
            audioImportJob?.cancel()
            audioImportJob = null
            audioImportToken = null
            imageImportJob?.cancel()
            imageImportJob = null
            pendingExternalTextMeetingId = null
            recordingTimerJob?.cancel()
            recordingTimerJob = null
            activeTimerStartedAtMs = null
            studyJourneyAutomationJob?.cancel()
            studyJourneyAutomationJob = null
            observedRecordingSession = null
            resetStudyStageEvidenceTracking()
            trackedReportTaskId = null
            journeyCollectionJob?.cancel()
            journeyCollectionJob = null
            journeyStageCollectionJob?.cancel()
            journeyStageCollectionJob = null
            stageDraftCollectionJob?.cancel()
            stageDraftCollectionJob = null
            journeyEditionCollectionJob?.cancel()
            journeyEditionCollectionJob = null
            publishedPostCollectionJob?.cancel()
            publishedPostCollectionJob = null
            communitySyncCollectionJob?.cancel()
            communitySyncCollectionJob = null
            resetStreamingPreviewState()
            recordingMarkerRecords = emptyList()
            _uiState.value = _uiState.value.resetForMeetingChange()
        }
        currentMeetingId = meetingId
        refreshArchivedAudio(meetingId, showFailure = false)
        observeTranscriptionTask(meetingId)
        observeReportTask(meetingId)
        attachmentCollectionJob?.cancel()
        attachmentCollectionJob = viewModelScope.launch {
            attachmentStore.observe(meetingId).collect { attachments ->
                if (isCurrentMeeting(meetingId)) {
                    _uiState.update { it.copy(attachments = attachments) }
                }
            }
        }
        observeJourney(meetingId)

        viewModelScope.launch {
            val meeting = meetingRepository.findById(meetingId).getOrNull()
            val transcripts = meetingRepository.findTranscriptsByMeetingId(meetingId).getOrNull().orEmpty()
            val recordingMarkers = meetingRepository.findRecordingMarkersByMeetingId(meetingId)
                .getOrNull()
                .orEmpty()
            val transcriptText = SimplifiedChineseText.normalize(
                transcripts.canonicalMeetingTranscripts().joinToString("\n") { it.content }
            )
            val hasReport = reportRepository.findByMeetingId(meetingId).getOrNull() != null
            val transcriptionTask = taskScheduler.observeTranscription(meetingId).first()
            val recoverableAudioFile = meeting?.audioFilePath
                ?.let { path -> java.io.File(path) }
                ?.takeIf { it.isFile && it.length() > 44L }
            val shouldRecoverImport = shouldRecoverImportedTranscription(
                meeting = meeting,
                hasTranscript = transcriptText.isNotBlank(),
                taskState = transcriptionTask.state,
                audioFileAvailable = recoverableAudioFile != null,
                recoveryAlreadyAttempted = meetingId in recoveredImportMeetingIds
            )
            val appConfig = configDataStore.appConfigFlow.first()
            val sttConfig = appConfig.sttConfig
            val recordingState = recordingController.state.value
            val isGlobalRecording = isActiveRecordingSessionForMeeting(recordingState, meetingId)
            val restoredDurationSeconds = if (recordingState.meetingId == meetingId) {
                maxOf(
                    meeting?.durationMs?.div(1_000L) ?: 0L,
                    recordingState.durationSecondsAt(SystemClock.elapsedRealtime())
                )
            } else {
                meeting?.durationMs?.div(1_000L) ?: 0L
            }
            if (meeting != null && isCurrentMeeting(meetingId)) {
                recordingMarkerRecords = recordingMarkers
                existingTranscriptText = transcriptText
                updateState {
                    it.copy(
                        meetingTitle = meeting.displayTitle(),
                        inputMode = if (meeting.origin == MeetingOrigin.FILE_IMPORT) {
                            InputMode.IMPORT
                        } else {
                            InputMode.VOICE
                        },
                        sttEngineLabel = sttConfig.engineType.displayName,
                        sttLanguage = sttConfig.language,
                        isRecording = isGlobalRecording,
                        isPaused = recordingState.isPaused,
                        hasRecording = isGlobalRecording ||
                            transcriptText.isNotBlank() ||
                            recoverableAudioFile != null,
                        hasReport = hasReport,
                        recordingDuration = restoredDurationSeconds,
                        audioLevel = recordingState.audioLevel,
                        realtimeSttRoute = if (isGlobalRecording) {
                            recordingState.realtimeSttRoute
                        } else {
                            RealtimeSttRouteState.IDLE
                        },
                        liveTranscript = when {
                            isGlobalRecording && it.liveTranscript.isNotBlank() -> it.liveTranscript
                            else -> transcriptText
                        },
                        recordingMarkers = recordingMarkers
                            .map { marker -> marker.timestampMs / 1_000L }
                            .distinct(),
                        recordingMarkerAnchors = recordingMarkers
                            .mapNotNull { marker -> marker.transcriptAnchor.takeIf(String::isNotBlank) },
                        activePhotoMarker = null,
                        transcriptPreviewMode = when {
                            isGlobalRecording -> "WebSocket 流式预览"
                            transcriptText.isNotBlank() -> "已保存转写"
                            else -> "流式预览"
                        },
                        presetTemplates = configDataStore.loadPresetTemplates(),
                        reportTemplate = appConfig.reportTemplateConfig,
                        selectedRecordingTemplateName = appConfig.reportTemplateConfig.selectedName
                            .takeIf { isGlobalRecording }
                    )
                }
                if (isGlobalRecording) {
                    recordingState.streamUpdate?.let(::updateStreamingPreview)
                }
                synchronizeRecordingTimer(recordingState)
                if (shouldRecoverImport && recoverableAudioFile != null) {
                    recoveredImportMeetingIds += meetingId
                    taskScheduler.enqueueTranscription(
                        meetingId = meetingId,
                        audioFile = recoverableAudioFile
                    )
                    _uiState.update {
                        it.copy(
                            isTranscribing = true,
                            transcriptionProgressPercent = null,
                            transcriptionProgressStage = "正在恢复未完成的音频转写",
                            transcriptionProgressIndeterminate = true,
                            transcriptPreviewMode = "后台转写已重新排队",
                            textImportStatus = "检测到未完成的音频，正在恢复转写",
                            error = null
                        )
                    }
                }
            }
        }
    }

    fun updatePermissionState(hasPermission: Boolean) {
        _uiState.update { it.copy(hasPermission = hasPermission) }
    }

    private fun observeJourney(meetingId: String) {
        journeyCollectionJob?.cancel()
        journeyStageCollectionJob?.cancel()
        journeyStageCollectionJob = null
        stageDraftCollectionJob?.cancel()
        stageDraftCollectionJob = null
        journeyEditionCollectionJob?.cancel()
        journeyEditionCollectionJob = null
        publishedPostCollectionJob?.cancel()
        publishedPostCollectionJob = null
        communitySyncCollectionJob?.cancel()
        communitySyncCollectionJob = null
        journeyCollectionJob = viewModelScope.launch {
            journeyRepository.observeByMeetingId(meetingId).collect journeyUpdate@ { journey ->
                if (!isCurrentMeeting(meetingId)) return@journeyUpdate
                _uiState.update { state ->
                    state.copy(
                        journey = journey,
                        currentJourneyStage = null,
                        latestSavedJourneyStage = null,
                        latestStageDraft = null,
                        stageDraftEditorVisible = false,
                        isGeneratingStageDraft = false,
                        isSavingStageDraft = false,
                        latestJourneyEdition = null,
                        isGeneratingJourneyEdition = false,
                        isSavingJourneyEdition = false,
                        journeyEditionEditorVisible = false,
                        latestPublishedPost = null,
                        publishedPostMedia = emptyList(),
                        communitySyncState = null,
                        isCreatingPublishedPost = false,
                        isSavingPublishedPost = false,
                        publishedPostReviewVisible = false,
                        journeyStageCount = if (journey == null) 0 else state.journeyStageCount
                    )
                }
                publishedPostMediaCollectionJob?.cancel()
                publishedPostMediaCollectionJob = null
                journeyStageCollectionJob?.cancel()
                journeyStageCollectionJob = null
                if (journey == null) return@journeyUpdate

                val journeyId = journey.id
                journeyEditionCollectionJob?.cancel()
                journeyEditionCollectionJob = viewModelScope.launch {
                    journeyEditionRepository.observeLatest(journeyId).collect { edition ->
                        if (isCurrentMeeting(meetingId) && _uiState.value.journey?.id == journeyId) {
                            _uiState.update { it.copy(latestJourneyEdition = edition) }
                        }
                    }
                }
                publishedPostCollectionJob?.cancel()
                publishedPostCollectionJob = viewModelScope.launch {
                    publishedPostRepository.observeLatest(journeyId).collect { post ->
                        if (isCurrentMeeting(meetingId) && _uiState.value.journey?.id == journeyId) {
                            _uiState.update { it.copy(latestPublishedPost = post) }
                            publishedPostMediaCollectionJob?.cancel()
                            publishedPostMediaCollectionJob = post?.let { observedPost ->
                                viewModelScope.launch {
                                    val media = publishedPostMediaStore.list(observedPost.id)
                                    if (isCurrentMeeting(meetingId) &&
                                        _uiState.value.latestPublishedPost?.id == observedPost.id
                                    ) {
                                        _uiState.update {
                                            it.copy(publishedPostMedia = media.toSummaries())
                                        }
                                    }
                                }
                            }
                            communitySyncCollectionJob?.cancel()
                            communitySyncCollectionJob = post?.let { observedPost ->
                                viewModelScope.launch {
                                    communitySyncRepository.observe(observedPost.id).collect { syncState ->
                                        if (isCurrentMeeting(meetingId) &&
                                            _uiState.value.latestPublishedPost?.id == observedPost.id
                                        ) {
                                            _uiState.update {
                                                it.copy(
                                                    communitySyncState = syncState,
                                                    journeyStatusMessage = syncState?.status.toJourneyStatusMessage()
                                                        ?: it.journeyStatusMessage
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                journeyStageCollectionJob = viewModelScope.launch {
                    journeyRepository.observeStages(journeyId).collect stageUpdate@ { stages ->
                        if (!isCurrentMeeting(meetingId)) return@stageUpdate
                        val latestSavedStage = stages
                            .asSequence()
                            .filter { it.status == JourneyStageStatus.SAVED }
                            .maxByOrNull { it.sequenceNumber }
                        val observedStageId = latestSavedStage?.id
                        val previousSavedStageId = _uiState.value.latestSavedJourneyStage?.id
                        _uiState.update { state ->
                            if (state.journey?.id != journeyId) {
                                state
                            } else {
                                state.copy(
                                    currentJourneyStage = stages.firstOrNull {
                                        it.id == journey.currentStageId
                                    },
                                    latestSavedJourneyStage = latestSavedStage,
                                    latestStageDraft = if (previousSavedStageId == observedStageId) {
                                        state.latestStageDraft
                                    } else {
                                        null
                                    },
                                    journeyStageCount = stages.size
                                )
                            }
                        }
                        _uiState.value.currentJourneyStage?.let(::trackStudyStageEvidenceIfNeeded)
                        if (observedStageId == null) {
                            stageDraftCollectionJob?.cancel()
                            stageDraftCollectionJob = null
                        } else if (previousSavedStageId != observedStageId) {
                            stageDraftCollectionJob?.cancel()
                            stageDraftCollectionJob = viewModelScope.launch {
                                stageDraftRepository.observeLatest(observedStageId).collect { draft ->
                                    if (isCurrentMeeting(meetingId) &&
                                        _uiState.value.latestSavedJourneyStage?.id == observedStageId
                                    ) {
                                        _uiState.update { it.copy(latestStageDraft = draft) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun switchSttEngine(engineType: STTEngineType) {
        if (engineType == _uiState.value.sttEngineType || _uiState.value.isSwitchingSttEngine) {
            return
        }
        val requestedMeetingId = currentMeetingId
        viewModelScope.launch {
            val appConfig = configDataStore.appConfigFlow.first()
            val currentConfig = appConfig.sttConfig
            val usesTencent = engineType == STTEngineType.TENCENT_HYBRID
            val nextConfig = currentConfig.copy(
                engineType = engineType,
                localModel = engineType.defaultModel.ifBlank { currentConfig.localModel },
                cloudEndpoint = if (usesTencent) {
                    currentConfig.cloudEndpoint ?: STTConfig.DEFAULT_CLOUD_ENDPOINT
                } else {
                    currentConfig.cloudEndpoint
                },
                cloudApiKey = if (usesTencent) null else currentConfig.cloudApiKey,
                cloudModel = if (usesTencent) {
                    currentConfig.tencentAsrTier.cloudModel
                } else {
                    currentConfig.cloudModel
                }
            )
            _uiState.update {
                it.copy(
                    isSwitchingSttEngine = true,
                    transcriptPreviewMode = "正在切换至${engineType.displayName}",
                    error = null
                )
            }
            val session = recordingController.state.value
            val isCurrentMeetingRecording = session.meetingId == requestedMeetingId &&
                session.isRecording &&
                !session.isStopping
            if (isCurrentMeetingRecording) preserveCurrentStreamingSegment()
            val switchResult = if (isCurrentMeetingRecording) {
                recordingController.switchStreamingProvider(engineType)
            } else {
                Result.success(Unit)
            }
            switchResult.fold(
                onSuccess = {
                    configDataStore.updateSTTConfig(nextConfig)
                    if (isCurrentMeeting(requestedMeetingId)) {
                        _uiState.update {
                            it.copy(
                                sttEngineType = engineType,
                                sttEngineLabel = engineType.displayName,
                                isSwitchingSttEngine = false,
                                transcriptPreviewMode = if (isCurrentMeetingRecording) {
                                    "${engineType.displayName}实时预览"
                                } else {
                                    it.transcriptPreviewMode
                                },
                                error = null
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(requestedMeetingId)) {
                        _uiState.update {
                            it.copy(
                                isSwitchingSttEngine = false,
                                error = "识别引擎切换失败: ${error.message}"
                            )
                        }
                    }
                }
            )
        }
    }

    fun switchSttLanguage(language: STTLanguage) {
        if (language == _uiState.value.sttLanguage || _uiState.value.isSwitchingSttLanguage) {
            return
        }
        val requestedMeetingId = currentMeetingId
        viewModelScope.launch {
            val currentConfig = configDataStore.appConfigFlow.first().sttConfig
            _uiState.update {
                it.copy(
                    isSwitchingSttLanguage = true,
                    transcriptPreviewMode = "正在切换至${language.displayName}",
                    error = null
                )
            }
            val session = recordingController.state.value
            val isCurrentMeetingRecording = session.meetingId == requestedMeetingId &&
                session.isRecording &&
                !session.isStopping
            if (isCurrentMeetingRecording) preserveCurrentStreamingSegment()
            val switchResult = if (isCurrentMeetingRecording) {
                recordingController.switchStreamingLanguage(language)
            } else {
                Result.success(Unit)
            }
            switchResult.fold(
                onSuccess = {
                    configDataStore.updateSTTConfig(currentConfig.copy(language = language))
                    if (isCurrentMeeting(requestedMeetingId)) {
                        _uiState.update {
                            it.copy(
                                sttLanguage = language,
                                isSwitchingSttLanguage = false,
                                transcriptPreviewMode = if (isCurrentMeetingRecording) {
                                    "${language.displayName}实时预览"
                                } else {
                                    it.transcriptPreviewMode
                                },
                                error = null
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(requestedMeetingId)) {
                        _uiState.update {
                            it.copy(
                                isSwitchingSttLanguage = false,
                                error = "识别语言切换失败: ${error.message}"
                            )
                        }
                    }
                }
            )
        }
    }

    fun switchAgentProvider(provider: AgentProvider) {
        if (provider == _uiState.value.agentProvider || _uiState.value.isSavingAgentProvider) {
            return
        }
        val requestedMeetingId = currentMeetingId
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingAgentProvider = true, error = null) }
            runCatching {
                val appConfig = configDataStore.appConfigFlow.first()
                configDataStore.updateLLMConfig(
                    appConfig.llmConfig.copy(agentProvider = provider)
                )
            }.fold(
                onSuccess = {
                    if (isCurrentMeeting(requestedMeetingId)) {
                        _uiState.update {
                            it.copy(
                                agentProvider = provider,
                                isSavingAgentProvider = false,
                                transcriptPreviewMode = "智能体 · 小Woo 已切换至${provider.displayName}"
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(requestedMeetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingAgentProvider = false,
                                error = "智能体切换失败: ${error.message}"
                            )
                        }
                    }
                }
            )
        }
    }

    private fun preserveCurrentStreamingSegment() {
        preservedStreamingText = preserveStreamingSessionText(
            preservedText = preservedStreamingText,
            latestText = latestStreamingText,
            committedText = committedStreamingText,
            previewText = previewStreamingText
        )
        latestStreamingText = ""
        committedStreamingText = ""
        previewStreamingText = ""
    }

    fun onMeetingTitleChange(title: String) {
        _uiState.update { it.copy(meetingTitle = title) }
    }

    fun saveMeetingTitle() {
        val newTitle = _uiState.value.meetingTitle.trim()
        val meetingId = currentMeetingId
        if (meetingId.isBlank()) return
        if (newTitle.isBlank()) {
            _uiState.update { it.copy(error = "会议名称不能为空") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingTitle = true, error = null) }
            meetingRepository.updateTitle(meetingId, newTitle)
                .onSuccess { meeting ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                meetingTitle = meeting.title,
                                isSavingTitle = false
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                error = "保存会议名称失败: ${error.message}",
                                isSavingTitle = false
                            )
                        }
                    }
                }
        }
    }

    fun startRecording() {
        val meetingId = currentMeetingId
        if (meetingId.isBlank()) {
            _uiState.update { it.copy(error = "会议标识缺失") }
            return
        }
        val state = _uiState.value
        if (state.isRecordingActionPending || state.isJourneyActionPending) return
        if (state.selectedRecordingTemplateName.isNullOrBlank()) {
            _uiState.update {
                it.copy(error = RECORDING_TEMPLATE_REQUIRED_MESSAGE)
            }
            return
        }
        val active = recordingController.state.value
        if (active.isStopping) {
            if (pendingRecordingStartJob?.isActive == true) return
            val requestedMeetingId = meetingId
            _uiState.update {
                it.copy(
                    transcriptPreviewMode = "正在等待上一段录音结束",
                    error = null
                )
            }
            pendingRecordingStartJob = viewModelScope.launch {
                recordingController.state.first { session ->
                    !session.isRecording && !session.isStarting && !session.isStopping
                }
                if (isCurrentMeeting(requestedMeetingId)) {
                    if (isStudyJourneyState(_uiState.value)) {
                        startStudyJourneyRecording(requestedMeetingId)
                    } else {
                        startRecordingNow(requestedMeetingId)
                    }
                }
            }
            return
        }
        if (active.isRecording || active.isStarting) {
            _uiState.update { it.copy(error = "已经在录音中") }
            return
        }

        if (isStudyJourneyState(state)) {
            startStudyJourneyRecording(meetingId)
        } else {
            startRecordingNow(meetingId)
        }
    }

    private fun startRecordingNow(meetingId: String) {
        if (!isCurrentMeeting(meetingId)) return
        val studyJourney = isStudyJourneyState(_uiState.value)
        val currentTranscript = SimplifiedChineseText.normalize(_uiState.value.liveTranscript)
        existingTranscriptText = currentTranscript
        latestStreamingText = ""
        committedStreamingText = ""
        previewStreamingText = ""
        preservedStreamingText = ""
        currentStreamingSessionId = ""
        startForegroundService(
            meetingId = meetingId,
            journeyStageId = if (studyJourney) null else _uiState.value.currentJourneyStage?.id,
            autoGenerateReport = !studyJourney
        )
        _uiState.update {
            it.copy(
                isRecording = false,
                isPaused = false,
                isRecordingActionPending = true,
                isFinalizingRecording = false,
                hasRecording = currentTranscript.isNotBlank(),
                liveTranscript = currentTranscript,
                isTranscribing = false,
                recordingDuration = 0,
                transcriptPreviewMode = "正在启动后台录音",
                isJourneyActionPending = false,
                error = null
            )
        }
    }

    fun stopRecording() {
        requestRecordingStop(showNoRecordingError = false)
    }

    fun togglePauseRecording() {
        val state = _uiState.value
        val meetingId = currentMeetingId
        if (meetingId.isBlank() || !state.isRecording || state.isRecordingActionPending) return
        val isResuming = state.isPaused
        val action = if (state.isPaused) {
            RecordingService.ACTION_RESUME
        } else {
            RecordingService.ACTION_PAUSE
        }
        _uiState.update {
            it.copy(
                isRecordingActionPending = true,
                transcriptPreviewMode = if (isResuming) "正在继续录音" else "正在暂停录音",
                error = null
            )
        }
        if (isResuming && isStudyJourneyState(state) && state.currentJourneyStage == null) {
            studyJourneyAutomationJob?.cancel()
            studyJourneyAutomationJob = viewModelScope.launch {
                studyJourneyAutomationMutex.withLock {
                    ensureStudyJourneyStage(meetingId).fold(
                        onSuccess = { sendRecordingControlAction(action, meetingId) },
                        onFailure = { error ->
                            if (isCurrentMeeting(meetingId)) {
                                _uiState.update {
                                    it.copy(
                                        isRecordingActionPending = false,
                                        isJourneyActionPending = false,
                                        error = "开始下一段研学记录失败: ${error.message}"
                                    )
                                }
                            }
                        }
                    )
                }
            }
        } else {
            sendRecordingControlAction(action, meetingId)
        }
    }

    fun addRecordingMarker() {
        requestMarkerMedia(RecordingMediaRequest.CHOOSE_SOURCE)
    }

    fun requestPhotoCapture() {
        requestMarkerMedia(RecordingMediaRequest.CAMERA)
    }

    fun requestPhotoLibrary() {
        requestMarkerMedia(RecordingMediaRequest.GALLERY)
    }

    fun closeActivePhotoMarker() {
        _uiState.update {
            it.copy(
                activePhotoMarker = null,
                transcriptPreviewMode = "文字标记已保留"
            )
        }
    }

    fun onMarkerMediaPickerCancelled(recordingMarkerId: String?) {
        val marker = _uiState.value.activePhotoMarker
        if (recordingMarkerId != null && marker?.id == recordingMarkerId) {
            _uiState.update { it.copy(transcriptPreviewMode = "标记已保留，可再次添加图片") }
        }
    }

    private fun requestMarkerMedia(request: RecordingMediaRequest) {
        val state = _uiState.value
        val meetingId = currentMeetingId
        if (meetingId.isBlank()) return
        if (!state.isRecording) {
            emitMediaRequest(request, recordingMarkerId = null)
            return
        }
        if (state.isPaused) {
            _uiState.update { it.copy(error = "请继续录音后再创建图文标记") }
            return
        }
        state.activePhotoMarker?.let { activeMarker ->
            emitMediaRequest(request, activeMarker.id)
            return
        }
        val timestampSeconds = state.recordingDuration.coerceAtLeast(0L)
        val timestampMs = timestampSeconds * 1_000L
        val journeyStageId = state.currentJourneyStage?.id
        val existingMarker = recordingMarkerRecords.lastOrNull {
            it.journeyStageId == journeyStageId && it.timestampMs == timestampMs
        }
        if (existingMarker != null) {
            _uiState.update {
                it.copy(
                    activePhotoMarker = existingMarker,
                    transcriptPreviewMode = "已定位 ${formatRecordingMarker(timestampSeconds)} · 请选择图片"
                )
            }
            emitMediaRequest(request, existingMarker.id)
            return
        }
        val marker = RecordingMarker(
            id = java.util.UUID.randomUUID().toString(),
            meetingId = meetingId,
            journeyStageId = journeyStageId,
            timestampMs = timestampMs,
            transcriptAnchor = extractRecordingMarkerAnchor(state.liveTranscript)
        )
        recordingMarkerRecords = recordingMarkerRecords + marker
        _uiState.update {
            it.copy(
                recordingMarkers = (it.recordingMarkers + timestampSeconds).distinct(),
                recordingMarkerAnchors = (it.recordingMarkerAnchors + marker.transcriptAnchor)
                    .filter(String::isNotBlank)
                    .distinct(),
                activePhotoMarker = marker,
                transcriptPreviewMode = "已定位 ${formatRecordingMarker(timestampSeconds)} · 请选择图片"
            )
        }
        emitMediaRequest(request, marker.id)
        viewModelScope.launch {
            meetingRepository.saveRecordingMarker(marker).fold(
                onSuccess = {},
                onFailure = { error ->
                    if (isCurrentMeeting(meetingId)) {
                        recordingMarkerRecords = recordingMarkerRecords.filterNot { it.id == marker.id }
                        _uiState.update {
                            it.copy(
                                recordingMarkers = recordingMarkerRecords
                                    .map { item -> item.timestampMs / 1_000L }
                                    .distinct(),
                                recordingMarkerAnchors = recordingMarkerRecords
                                    .map { item -> item.transcriptAnchor }
                                    .filter(String::isNotBlank)
                                    .distinct(),
                                activePhotoMarker = it.activePhotoMarker?.takeUnless { active ->
                                    active.id == marker.id
                                },
                                error = "保存标记失败: ${error.message}"
                            )
                        }
                    }
                }
            )
        }
    }

    private fun emitMediaRequest(request: RecordingMediaRequest, recordingMarkerId: String?) {
        _effects.tryEmit(
            RecordingUiEffect(
                mediaRequest = request,
                recordingMarkerId = recordingMarkerId
            )
        )
    }

    private fun startStudyJourneyRecording(meetingId: String) {
        if (!isCurrentMeeting(meetingId) || studyJourneyAutomationJob?.isActive == true) return
        _uiState.update {
            it.copy(
                isRecordingActionPending = true,
                isJourneyActionPending = true,
                transcriptPreviewMode = "正在准备研学记录",
                error = null
            )
        }
        studyJourneyAutomationJob = viewModelScope.launch {
            studyJourneyAutomationMutex.withLock {
                ensureStudyJourneyStage(meetingId).fold(
                    onSuccess = {
                        if (isCurrentMeeting(meetingId)) startRecordingNow(meetingId)
                    },
                    onFailure = { error ->
                        if (isCurrentMeeting(meetingId)) {
                            _uiState.update {
                                it.copy(
                                    isRecordingActionPending = false,
                                    isJourneyActionPending = false,
                                    error = "准备研学记录失败: ${error.message}"
                                )
                            }
                        }
                    }
                )
            }
            studyJourneyAutomationJob = null
        }
    }

    private suspend fun ensureStudyJourneyStage(meetingId: String): Result<JourneyStage> = runCatching {
        require(isCurrentMeeting(meetingId)) { "会议已切换" }
        val state = _uiState.value
        state.currentJourneyStage?.takeIf { it.status == JourneyStageStatus.ACTIVE }?.let { stage ->
            trackStudyStageEvidenceIfNeeded(stage)
            _uiState.update { it.copy(isJourneyActionPending = false, error = null) }
            return@runCatching stage
        }

        val now = System.currentTimeMillis()
        val journey = state.journey
        if (journey == null) {
            val journeyId = java.util.UUID.randomUUID().toString()
            val initialStage = JourneyStage(
                id = java.util.UUID.randomUUID().toString(),
                journeyId = journeyId,
                sequenceNumber = 1,
                title = "第 1 段",
                startedAt = now,
                updatedAt = now
            )
            val createdJourney = Journey(
                id = journeyId,
                meetingId = meetingId,
                title = state.meetingTitle.ifBlank { STUDY_JOURNEY_TEMPLATE_NAME },
                currentStageId = initialStage.id,
                createdAt = now,
                updatedAt = now
            )
            journeyRepository.create(createdJourney, initialStage).getOrThrow()
            _uiState.update {
                it.copy(
                    journey = createdJourney,
                    currentJourneyStage = initialStage,
                    journeyStageCount = 1,
                    isJourneyActionPending = false,
                    journeyStatusMessage = "研学记录已开始 · 第 1 段",
                    error = null
                )
            }
            trackStudyStageEvidenceIfNeeded(initialStage)
            return@runCatching initialStage
        }

        require(journey.status != JourneyStatus.COMPLETED) { "研学记录已经完成" }
        val existingStages = journeyRepository.observeStages(journey.id).first()
        val nextSequence = (existingStages.maxOfOrNull { it.sequenceNumber } ?: 0) + 1
        val nextStage = JourneyStage(
            id = java.util.UUID.randomUUID().toString(),
            journeyId = journey.id,
            sequenceNumber = nextSequence,
            title = "第 $nextSequence 段",
            startedAt = now,
            updatedAt = now
        )
        val resumedJourney = journey.copy(
            status = JourneyStatus.ACTIVE,
            currentStageId = nextStage.id,
            updatedAt = now,
            pausedAt = null
        )
        journeyRepository.startNextStage(resumedJourney, nextStage).getOrThrow()
        _uiState.update {
            it.copy(
                journey = resumedJourney,
                currentJourneyStage = nextStage,
                journeyStageCount = maxOf(it.journeyStageCount, nextSequence),
                isJourneyActionPending = false,
                journeyStatusMessage = "已开始${nextStage.title}",
                error = null
            )
        }
        trackStudyStageEvidenceIfNeeded(nextStage)
        nextStage
    }

    private fun handleStudyJourneyRecordingTransition(
        previous: RecordingSessionState?,
        current: RecordingSessionState
    ) {
        if (!isStudyJourneyState(_uiState.value)) return
        val pausedNow = current.isRecording && current.isPaused
        val justPaused = pausedNow && previous?.isPaused != true
        val justResumed = current.isRecording && !current.isPaused && previous?.isPaused == true
        val justStopped = previous?.isRecording == true && !current.isRecording && !current.isStopping

        when {
            justPaused -> scheduleStudyStageFinalization(
                current.meetingId,
                "录音已暂停",
                captureStudyStageFinalizationSnapshot(current)
            )
            justStopped -> scheduleStudyStageFinalization(
                current.meetingId,
                "本次录音已结束",
                captureStudyStageFinalizationSnapshot(current)
            )
            justResumed && _uiState.value.currentJourneyStage == null -> {
                _uiState.update { it.copy(isJourneyActionPending = true, error = null) }
                viewModelScope.launch {
                    studyJourneyAutomationMutex.withLock {
                        ensureStudyJourneyStage(current.meetingId).onFailure { error ->
                            if (isCurrentMeeting(current.meetingId)) {
                                _uiState.update {
                                    it.copy(
                                        isJourneyActionPending = false,
                                        error = "续接研学记录失败: ${error.message}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun captureStudyStageFinalizationSnapshot(
        session: RecordingSessionState
    ): StudyStageFinalizationSnapshot? {
        val state = _uiState.value
        val stage = state.currentJourneyStage ?: return null
        val sessionTranscript = mergeTranscriptText(
            existingTranscriptText,
            SimplifiedChineseText.normalize(session.accumulatedTranscript)
        )
        return StudyStageFinalizationSnapshot(
            stageId = stage.id,
            transcript = sessionTranscript.ifBlank { state.liveTranscript },
            durationSeconds = session.durationSecondsAt(SystemClock.elapsedRealtime())
                .coerceAtLeast(state.recordingDuration),
            markerCount = recordingMarkerRecords.count { it.journeyStageId == stage.id },
            attachmentCount = state.attachments.count { it.journeyStageId == stage.id }
        )
    }

    private fun scheduleStudyStageFinalization(
        meetingId: String,
        reason: String,
        snapshot: StudyStageFinalizationSnapshot?
    ) {
        if (!isCurrentMeeting(meetingId)) return
        _uiState.update { it.copy(isJourneyActionPending = true, error = null) }
        viewModelScope.launch {
            studyJourneyAutomationMutex.withLock {
                finalizeStudyJourneyStageIfMeaningful(meetingId, reason, snapshot)
            }
        }
    }

    private suspend fun finalizeStudyJourneyStageIfMeaningful(
        meetingId: String,
        reason: String,
        snapshot: StudyStageFinalizationSnapshot?
    ) {
        if (!isCurrentMeeting(meetingId)) return
        val state = _uiState.value
        val journey = state.journey
        val stage = state.currentJourneyStage
        if (
            snapshot == null ||
            journey == null ||
            stage == null ||
            stage.id != snapshot.stageId ||
            journey.status == JourneyStatus.COMPLETED ||
            stage.status != JourneyStageStatus.ACTIVE
        ) {
            _uiState.update { it.copy(isJourneyActionPending = false) }
            return
        }

        trackStudyStageEvidenceIfNeeded(stage)
        val evidence = resolveStudyStageEvidence(
            baseline = trackedStudyStageTranscriptBaseline,
            startedDurationSeconds = trackedStudyStageStartedDurationSeconds,
            snapshot = snapshot
        )
        if (!evidence.isMeaningful) {
            _uiState.update {
                it.copy(
                    isJourneyActionPending = false,
                    journeyStatusMessage = "$reason · 未检测到新内容，继续沿用${stage.title}"
                )
            }
            return
        }

        val now = System.currentTimeMillis()
        runCatching {
            if (evidence.transcriptDelta.isNotBlank()) {
                meetingRepository.saveTranscript(
                    Transcript(
                        id = "journey-stage-${stage.id}",
                        meetingId = meetingId,
                        journeyStageId = stage.id,
                        content = evidence.transcriptDelta,
                        startTimeMs = evidence.startTimeMs,
                        endTimeMs = evidence.endTimeMs,
                        createdAt = stage.startedAt
                    )
                ).getOrThrow()
            }
            val savedStage = stage.copy(
                status = JourneyStageStatus.SAVED,
                updatedAt = now,
                savedAt = now
            )
            val savedJourney = journey.copy(
                status = JourneyStatus.ACTIVE,
                currentStageId = null,
                updatedAt = now
            )
            journeyRepository.saveCurrentStage(savedJourney, savedStage).getOrThrow()
            pendingStudyStageTranscriptBaseline = SimplifiedChineseText.normalize(snapshot.transcript)
            trackedStudyStageId = null
            trackedStudyStageTranscriptBaseline = ""
            trackedStudyStageStartedDurationSeconds = snapshot.durationSeconds
            _uiState.update {
                it.copy(
                    journey = savedJourney,
                    currentJourneyStage = null,
                    latestSavedJourneyStage = savedStage,
                    isJourneyActionPending = false,
                    journeyStatusMessage = "$reason · ${stage.title}已自动暂存",
                    error = null
                )
            }
            val liveSession = recordingController.state.value
            if (
                liveSession.meetingId == meetingId &&
                liveSession.isRecording &&
                !liveSession.isPaused &&
                !liveSession.isStopping
            ) {
                ensureStudyJourneyStage(meetingId).onFailure { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(error = "${stage.title}已保存，但续接下一段失败: ${error.message}")
                        }
                    }
                }
            }
        }.onFailure { error ->
            if (isCurrentMeeting(meetingId)) {
                _uiState.update {
                    it.copy(
                        isJourneyActionPending = false,
                        error = "自动暂存${stage.title}失败: ${error.message}"
                    )
                }
            }
        }
    }

    private fun trackStudyStageEvidenceIfNeeded(stage: JourneyStage) {
        if (trackedStudyStageId == stage.id) return
        trackedStudyStageId = stage.id
        trackedStudyStageTranscriptBaseline = pendingStudyStageTranscriptBaseline
            ?: SimplifiedChineseText.normalize(_uiState.value.liveTranscript)
        trackedStudyStageStartedDurationSeconds = _uiState.value.recordingDuration
        pendingStudyStageTranscriptBaseline = null
    }

    private fun resetStudyStageEvidenceTracking() {
        trackedStudyStageId = null
        trackedStudyStageTranscriptBaseline = ""
        trackedStudyStageStartedDurationSeconds = 0L
        pendingStudyStageTranscriptBaseline = null
    }

    private fun sendRecordingControlAction(action: String, meetingId: String) {
        appContext.startService(Intent(appContext, RecordingService::class.java).apply {
            this.action = action
            putExtra(RecordingService.EXTRA_MEETING_ID, meetingId)
        })
    }

    fun startJourney() {
        val state = _uiState.value
        val meetingId = currentMeetingId
        if (meetingId.isBlank()) return
        if (state.reportTemplate.selectedName != STUDY_JOURNEY_TEMPLATE_NAME) {
            _uiState.update { it.copy(error = "请先选择研学考察模板") }
            return
        }
        if (state.journey != null || state.isJourneyActionPending) return
        if (!canChangeJourneyStage(state)) {
            _uiState.update { it.copy(error = "请先完成当前录音或转写，再开始旅程") }
            return
        }

        val now = System.currentTimeMillis()
        val journeyId = java.util.UUID.randomUUID().toString()
        val initialStage = JourneyStage(
            id = java.util.UUID.randomUUID().toString(),
            journeyId = journeyId,
            sequenceNumber = 1,
            title = "第 1 段",
            startedAt = now,
            updatedAt = now
        )
        val journey = Journey(
            id = journeyId,
            meetingId = meetingId,
            title = state.meetingTitle.ifBlank { "研学考察" },
            currentStageId = initialStage.id,
            createdAt = now,
            updatedAt = now
        )
        _uiState.update { it.copy(isJourneyActionPending = true, error = null) }
        viewModelScope.launch {
            journeyRepository.create(journey, initialStage)
                .onSuccess {
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isJourneyActionPending = false,
                                journeyStatusMessage = "研学旅程已开始 · 第 1 段"
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isJourneyActionPending = false,
                                error = "开始研学旅程失败: ${error.message}"
                            )
                        }
                    }
                }
        }
    }

    fun saveCurrentJourneyStage() {
        val state = _uiState.value
        val journey = state.journey ?: return
        val stage = state.currentJourneyStage ?: run {
            _uiState.update { it.copy(error = "当前没有可暂存的旅程段") }
            return
        }
        if (state.isJourneyActionPending) return
        if (!canChangeJourneyStage(state)) {
            _uiState.update { it.copy(error = "请先完成本段录音和转写，再暂存") }
            return
        }
        if (journey.status != JourneyStatus.ACTIVE || stage.status != JourneyStageStatus.ACTIVE) {
            _uiState.update { it.copy(error = "当前旅程段无法暂存") }
            return
        }

        val now = System.currentTimeMillis()
        val savedStage = stage.copy(
            status = JourneyStageStatus.SAVED,
            updatedAt = now,
            savedAt = now
        )
        val savedJourney = journey.copy(currentStageId = null, updatedAt = now)
        _uiState.update { it.copy(isJourneyActionPending = true, error = null) }
        viewModelScope.launch {
            journeyRepository.saveCurrentStage(savedJourney, savedStage)
                .onSuccess {
                    if (isCurrentMeeting(journey.meetingId)) {
                        _uiState.update {
                            it.copy(
                                isJourneyActionPending = false,
                                journeyStatusMessage = "${stage.title}已暂存"
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (isCurrentMeeting(journey.meetingId)) {
                        _uiState.update {
                            it.copy(
                                isJourneyActionPending = false,
                                error = "暂存本段失败: ${error.message}"
                            )
                        }
                    }
                }
        }
    }

    fun pauseJourney() {
        val state = _uiState.value
        val journey = state.journey ?: return
        if (state.isJourneyActionPending) return
        if (!canChangeJourneyStage(state) || journey.currentStageId != null) {
            _uiState.update { it.copy(error = "请先暂存当前旅程段，再暂停旅程") }
            return
        }
        if (journey.status != JourneyStatus.ACTIVE) return

        val now = System.currentTimeMillis()
        val pausedJourney = journey.copy(
            status = JourneyStatus.PAUSED,
            updatedAt = now,
            pausedAt = now
        )
        saveJourneyLifecycle(pausedJourney, "旅程已暂停")
    }

    fun continueJourney() {
        val state = _uiState.value
        val journey = state.journey ?: return
        if (state.isJourneyActionPending) return
        if (!canChangeJourneyStage(state) || journey.currentStageId != null) {
            _uiState.update { it.copy(error = "请先结束当前录音或暂存当前旅程段") }
            return
        }
        if (journey.status == JourneyStatus.COMPLETED) {
            _uiState.update { it.copy(error = "已完成的旅程不能继续") }
            return
        }

        val now = System.currentTimeMillis()
        val nextStage = JourneyStage(
            id = java.util.UUID.randomUUID().toString(),
            journeyId = journey.id,
            sequenceNumber = state.journeyStageCount + 1,
            title = "第 ${state.journeyStageCount + 1} 段",
            startedAt = now,
            updatedAt = now
        )
        val resumedJourney = journey.copy(
            status = JourneyStatus.ACTIVE,
            currentStageId = nextStage.id,
            updatedAt = now,
            pausedAt = null
        )
        _uiState.update { it.copy(isJourneyActionPending = true, error = null) }
        viewModelScope.launch {
            journeyRepository.startNextStage(resumedJourney, nextStage)
                .onSuccess {
                    if (isCurrentMeeting(journey.meetingId)) {
                        _uiState.update {
                            it.copy(
                                isJourneyActionPending = false,
                                journeyStatusMessage = "已继续旅程 · ${nextStage.title}"
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (isCurrentMeeting(journey.meetingId)) {
                        _uiState.update {
                            it.copy(
                                isJourneyActionPending = false,
                                error = "继续旅程失败: ${error.message}"
                            )
                        }
                    }
                }
        }
    }

    fun generateLatestStageDraft() {
        val state = _uiState.value
        val stage = state.latestSavedJourneyStage
        when {
            state.reportTemplate.selectedName != STUDY_JOURNEY_TEMPLATE_NAME -> {
                _uiState.update { it.copy(error = "请先选择研学考察模板") }
            }
            stage == null -> {
                _uiState.update { it.copy(error = "请先暂存一个旅程段") }
            }
            state.currentJourneyStage != null -> {
                _uiState.update { it.copy(error = "请先暂存当前旅程段，再生成阶段笔记") }
            }
            state.isGeneratingStageDraft || state.isSavingStageDraft -> Unit
            state.latestStageDraft?.status == StageDraftStatus.DRAFT -> {
                _uiState.update { it.copy(stageDraftEditorVisible = true, error = null) }
            }
            else -> {
                val meetingId = currentMeetingId
                _uiState.update {
                    it.copy(
                        isGeneratingStageDraft = true,
                        journeyStatusMessage = "正在生成${stage.title}阶段笔记",
                        error = null
                    )
                }
                viewModelScope.launch {
                    generateStageDraftUseCase(stage).fold(
                        onSuccess = { draft ->
                            if (isCurrentMeeting(meetingId)) {
                                _uiState.update {
                                    it.copy(
                                        isGeneratingStageDraft = false,
                                        latestStageDraft = draft,
                                        stageDraftEditorVisible = true,
                                        journeyStatusMessage = "${stage.title}阶段笔记已生成"
                                    )
                                }
                            }
                        },
                        onFailure = { error ->
                            if (isCurrentMeeting(meetingId)) {
                                _uiState.update {
                                    it.copy(
                                        isGeneratingStageDraft = false,
                                        error = "生成阶段笔记失败: ${error.message ?: "未知错误"}"
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    fun openLatestStageDraft() {
        if (_uiState.value.latestStageDraft == null) {
            _uiState.update { it.copy(error = "当前还没有阶段笔记") }
            return
        }
        _uiState.update { it.copy(stageDraftEditorVisible = true, error = null) }
    }

    fun dismissStageDraftEditor() {
        _uiState.update { it.copy(stageDraftEditorVisible = false) }
    }

    fun saveStageDraftContent(content: String) {
        val draft = _uiState.value.latestStageDraft ?: return
        if (draft.status != StageDraftStatus.DRAFT) {
            _uiState.update { it.copy(error = "已确认的阶段笔记不可修改") }
            return
        }
        if (_uiState.value.isSavingStageDraft) return
        val meetingId = currentMeetingId
        _uiState.update { it.copy(isSavingStageDraft = true, error = null) }
        viewModelScope.launch {
            stageDraftRepository.saveDraft(draft.id, content).fold(
                onSuccess = { saved ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingStageDraft = false,
                                latestStageDraft = saved,
                                journeyStatusMessage = "阶段笔记修改已保存"
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingStageDraft = false,
                                error = "保存阶段笔记失败: ${error.message ?: "未知错误"}"
                            )
                        }
                    }
                }
            )
        }
    }

    fun confirmStageDraft(content: String) {
        val draft = _uiState.value.latestStageDraft ?: return
        if (draft.status != StageDraftStatus.DRAFT) {
            _uiState.update { it.copy(error = "阶段笔记已经确认") }
            return
        }
        if (_uiState.value.isSavingStageDraft) return
        val meetingId = currentMeetingId
        _uiState.update { it.copy(isSavingStageDraft = true, error = null) }
        viewModelScope.launch {
            val result = stageDraftRepository.saveDraft(draft.id, content).fold(
                onSuccess = { saved -> stageDraftRepository.confirmDraft(saved.id) },
                onFailure = { error -> Result.failure(error) }
            )
            result.fold(
                    onSuccess = { confirmed ->
                        if (isCurrentMeeting(meetingId)) {
                            _uiState.update {
                                it.copy(
                                    isSavingStageDraft = false,
                                    latestStageDraft = confirmed,
                                    stageDraftEditorVisible = false,
                                    journeyStatusMessage = "阶段笔记已确认"
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        if (isCurrentMeeting(meetingId)) {
                            _uiState.update {
                                it.copy(
                                    isSavingStageDraft = false,
                                    error = "确认阶段笔记失败: ${error.message ?: "未知错误"}"
                                )
                            }
                        }
                    }
                )
        }
    }

    fun generateJourneyEdition() {
        val state = _uiState.value
        val journey = state.journey ?: run {
            _uiState.update { it.copy(error = "请先开始研学旅程") }
            return
        }
        when {
            state.reportTemplate.selectedName != STUDY_JOURNEY_TEMPLATE_NAME -> {
                _uiState.update { it.copy(error = "请先选择研学考察模板") }
            }
            state.isGeneratingJourneyEdition || state.isSavingJourneyEdition -> Unit
            state.latestJourneyEdition?.status == JourneyEditionStatus.DRAFT -> {
                _uiState.update { it.copy(journeyEditionEditorVisible = true, error = null) }
            }
            else -> {
                val meetingId = currentMeetingId
                _uiState.update {
                    it.copy(
                        isGeneratingJourneyEdition = true,
                        journeyStatusMessage = "正在生成总游记",
                        error = null
                    )
                }
                viewModelScope.launch {
                    generateJourneyEditionUseCase(journey).fold(
                        onSuccess = { edition ->
                            if (isCurrentMeeting(meetingId)) {
                                _uiState.update {
                                    it.copy(
                                        isGeneratingJourneyEdition = false,
                                        latestJourneyEdition = edition,
                                        journeyEditionEditorVisible = true,
                                        journeyStatusMessage = "总游记已生成"
                                    )
                                }
                            }
                        },
                        onFailure = { error ->
                            if (isCurrentMeeting(meetingId)) {
                                _uiState.update {
                                    it.copy(
                                        isGeneratingJourneyEdition = false,
                                        error = "生成总游记失败: ${error.message ?: "未知错误"}"
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    fun openLatestJourneyEdition() {
        if (_uiState.value.latestJourneyEdition == null) {
            _uiState.update { it.copy(error = "当前还没有总游记") }
            return
        }
        _uiState.update { it.copy(journeyEditionEditorVisible = true, error = null) }
    }

    fun dismissJourneyEditionEditor() {
        _uiState.update { it.copy(journeyEditionEditorVisible = false) }
    }

    fun saveJourneyEditionContent(content: String) {
        val edition = _uiState.value.latestJourneyEdition ?: return
        if (edition.status != JourneyEditionStatus.DRAFT) {
            _uiState.update { it.copy(error = "已确认的总游记不可修改") }
            return
        }
        if (_uiState.value.isSavingJourneyEdition) return
        val meetingId = currentMeetingId
        _uiState.update { it.copy(isSavingJourneyEdition = true, error = null) }
        viewModelScope.launch {
            journeyEditionRepository.saveEdition(edition.id, content).fold(
                onSuccess = { saved ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingJourneyEdition = false,
                                latestJourneyEdition = saved,
                                journeyStatusMessage = "总游记修改已保存"
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingJourneyEdition = false,
                                error = "保存总游记失败: ${error.message ?: "未知错误"}"
                            )
                        }
                    }
                }
            )
        }
    }

    fun confirmJourneyEdition(content: String) {
        val edition = _uiState.value.latestJourneyEdition ?: return
        if (edition.status != JourneyEditionStatus.DRAFT) {
            _uiState.update { it.copy(error = "总游记已经确认") }
            return
        }
        if (_uiState.value.isSavingJourneyEdition) return
        val meetingId = currentMeetingId
        _uiState.update { it.copy(isSavingJourneyEdition = true, error = null) }
        viewModelScope.launch {
            val result = journeyEditionRepository.saveEdition(edition.id, content).fold(
                onSuccess = { saved -> journeyEditionRepository.confirmEdition(saved.id) },
                onFailure = { error -> Result.failure(error) }
            )
            result.fold(
                onSuccess = { confirmed ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingJourneyEdition = false,
                                latestJourneyEdition = confirmed,
                                journeyEditionEditorVisible = false,
                                journeyStatusMessage = "总游记已确认"
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingJourneyEdition = false,
                                error = "确认总游记失败: ${error.message ?: "未知错误"}"
                            )
                        }
                    }
                }
            )
        }
    }

    fun createPublishedPostSnapshot() {
        val state = _uiState.value
        val journey = state.journey ?: run {
            _uiState.update { it.copy(error = "请先开始研学旅程") }
            return
        }
        val edition = state.latestJourneyEdition ?: run {
            _uiState.update { it.copy(error = "请先生成并确认总游记") }
            return
        }
        if (edition.status != JourneyEditionStatus.CONFIRMED) {
            _uiState.update { it.copy(error = "请先确认总游记") }
            return
        }
        if (state.latestPublishedPost?.journeyEditionId == edition.id) {
            _uiState.update { it.copy(publishedPostReviewVisible = true, error = null) }
            return
        }
        if (state.isCreatingPublishedPost || state.isSavingPublishedPost) return

        val meetingId = currentMeetingId
        _uiState.update {
            it.copy(
                isCreatingPublishedPost = true,
                journeyStatusMessage = "正在准备发布快照",
                error = null
            )
        }
        viewModelScope.launch {
            createPublishedPostSnapshotUseCase(journey, edition).fold(
                onSuccess = { post ->
                    val media = publishedPostMediaStore.list(post.id)
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isCreatingPublishedPost = false,
                                latestPublishedPost = post,
                                publishedPostMedia = media.toSummaries(),
                                publishedPostReviewVisible = true,
                                journeyStatusMessage = "发布快照待检查"
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isCreatingPublishedPost = false,
                                error = "创建发布快照失败: ${error.message ?: "未知错误"}"
                            )
                        }
                    }
                }
            )
        }
    }

    fun openPublishedPostReview() {
        val post = _uiState.value.latestPublishedPost
        if (post == null) {
            _uiState.update { it.copy(error = "当前还没有发布快照") }
            return
        }
        val meetingId = currentMeetingId
        viewModelScope.launch {
            val media = publishedPostMediaStore.list(post.id)
            if (isCurrentMeeting(meetingId) && _uiState.value.latestPublishedPost?.id == post.id) {
                _uiState.update {
                    it.copy(
                        publishedPostMedia = media.toSummaries(),
                        publishedPostReviewVisible = true,
                        error = null
                    )
                }
            }
        }
    }

    fun dismissPublishedPostReview() {
        _uiState.update { it.copy(publishedPostReviewVisible = false) }
    }

    fun setPublishedPostMediaIncluded(mediaId: String, included: Boolean) {
        val post = _uiState.value.latestPublishedPost ?: return
        if (post.status != PublishedPostStatus.REVIEW || _uiState.value.isSavingPublishedPost) return
        val meetingId = currentMeetingId
        _uiState.update { it.copy(isSavingPublishedPost = true, error = null) }
        viewModelScope.launch {
            publishedPostMediaStore.setIncluded(post.id, mediaId, included).fold(
                onSuccess = { media ->
                    if (isCurrentMeeting(meetingId) &&
                        _uiState.value.latestPublishedPost?.id == post.id
                    ) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                publishedPostMedia = media.toSummaries(),
                                journeyStatusMessage = if (included) {
                                    "图片已恢复到发布快照"
                                } else {
                                    "图片已从发布快照排除"
                                }
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                error = "更新发布图片失败: ${error.message ?: "未知错误"}"
                            )
                        }
                    }
                }
            )
        }
    }

    fun savePublishedPostReview(privacyReviewed: Boolean, rightsConfirmed: Boolean) {
        val post = _uiState.value.latestPublishedPost ?: return
        if (post.status != PublishedPostStatus.REVIEW) {
            _uiState.update { it.copy(error = "当前发布快照不可修改检查项") }
            return
        }
        if (_uiState.value.isSavingPublishedPost) return
        val meetingId = currentMeetingId
        _uiState.update { it.copy(isSavingPublishedPost = true, error = null) }
        viewModelScope.launch {
            publishedPostRepository.saveReview(post.id, privacyReviewed, rightsConfirmed).fold(
                onSuccess = { saved ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                latestPublishedPost = saved,
                                journeyStatusMessage = "发布检查已保存"
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                error = "保存发布检查失败: ${error.message ?: "未知错误"}"
                            )
                        }
                    }
                }
            )
        }
    }

    fun updatePublishedPostMetadata(
        destination: String,
        travelDate: String,
        travelDays: Int,
        tags: List<String>,
        pois: List<String>
    ) {
        val post = _uiState.value.latestPublishedPost ?: return
        if (post.status != PublishedPostStatus.REVIEW || _uiState.value.isSavingPublishedPost) return
        val meetingId = currentMeetingId
        _uiState.update { it.copy(isSavingPublishedPost = true, error = null) }
        viewModelScope.launch {
            publishedPostRepository.updateMetadata(
                id = post.id,
                destination = destination,
                travelDate = travelDate,
                travelDays = travelDays,
                tags = tags,
                pois = pois
            ).fold(
                onSuccess = { saved ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                latestPublishedPost = saved,
                                journeyStatusMessage = "发布元数据已保存"
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                error = "保存发布元数据失败: ${error.message ?: "未知错误"}"
                            )
                        }
                    }
                }
            )
        }
    }

    fun markPublishedPostReady(privacyReviewed: Boolean, rightsConfirmed: Boolean) {
        val post = _uiState.value.latestPublishedPost ?: return
        if (post.status != PublishedPostStatus.REVIEW) return
        if (!privacyReviewed || !rightsConfirmed) {
            _uiState.update { it.copy(error = "请完成隐私与内容权利确认") }
            return
        }
        if (_uiState.value.isSavingPublishedPost) return
        val meetingId = currentMeetingId
        _uiState.update { it.copy(isSavingPublishedPost = true, error = null) }
        viewModelScope.launch {
            val result = publishedPostRepository.saveReview(
                post.id,
                privacyReviewed = true,
                rightsConfirmed = true
            ).fold(
                onSuccess = { saved -> publishedPostRepository.markReady(saved.id) },
                onFailure = { error -> Result.failure(error) }
            )
            result.fold(
                onSuccess = { ready ->
                    val syncResult = communitySyncRepository.enqueueUpload(ready.id)
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                latestPublishedPost = ready,
                                publishedPostReviewVisible = false,
                                journeyStatusMessage = if (syncResult.isSuccess) {
                                    "社区发布预览已就绪，等待同步"
                                } else {
                                    "社区发布预览已就绪"
                                }
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                error = "发布准备失败: ${error.message ?: "未知错误"}"
                            )
                        }
                    }
                }
            )
        }
    }

    fun withdrawPublishedPost() {
        val post = _uiState.value.latestPublishedPost ?: return
        if (post.status != PublishedPostStatus.READY || _uiState.value.isSavingPublishedPost) return
        val meetingId = currentMeetingId
        _uiState.update { it.copy(isSavingPublishedPost = true, error = null) }
        viewModelScope.launch {
            publishedPostRepository.withdraw(post.id).fold(
                onSuccess = { withdrawn ->
                    val syncResult = communitySyncRepository.requestWithdraw(withdrawn.id)
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                latestPublishedPost = withdrawn,
                                publishedPostReviewVisible = false,
                                journeyStatusMessage = if (syncResult.isSuccess) {
                                    "发布准备已撤回，正在同步撤回状态"
                                } else {
                                    "发布准备已撤回"
                                }
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                error = "撤回发布准备失败: ${error.message ?: "未知错误"}"
                            )
                        }
                    }
                }
            )
        }
    }

    fun publishPublishedPost() {
        val post = _uiState.value.latestPublishedPost ?: return
        if (post.status != PublishedPostStatus.READY || _uiState.value.isSavingPublishedPost) return
        val meetingId = currentMeetingId
        _uiState.update { it.copy(isSavingPublishedPost = true, error = null) }
        viewModelScope.launch {
            communitySyncRepository.requestPublish(post.id).fold(
                onSuccess = {
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                publishedPostReviewVisible = false,
                                journeyStatusMessage = "已加入社区发布队列"
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                isSavingPublishedPost = false,
                                error = "加入社区发布队列失败: ${error.message ?: "未知错误"}"
                            )
                        }
                    }
                }
            )
        }
    }

    private fun saveJourneyLifecycle(journey: Journey, statusMessage: String) {
        _uiState.update { it.copy(isJourneyActionPending = true, error = null) }
        viewModelScope.launch {
            journeyRepository.save(journey)
                .onSuccess {
                    if (isCurrentMeeting(journey.meetingId)) {
                        _uiState.update {
                            it.copy(
                                isJourneyActionPending = false,
                                journeyStatusMessage = statusMessage
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (isCurrentMeeting(journey.meetingId)) {
                        _uiState.update {
                            it.copy(
                                isJourneyActionPending = false,
                                error = "更新旅程状态失败: ${error.message}"
                            )
                        }
                    }
                }
        }
    }

    private fun canChangeJourneyStage(state: RecordingUiState): Boolean =
        !state.isRecording &&
            !state.isRecordingActionPending &&
            !state.isFinalizingRecording &&
            !state.isTranscribing &&
            !state.isGeneratingReport

    private fun isStudyJourneyState(state: RecordingUiState): Boolean =
        state.reportTemplate.selectedName == STUDY_JOURNEY_TEMPLATE_NAME || state.journey != null

    private fun isStudyJourneyAwaitingNextStage(state: RecordingUiState): Boolean =
        state.reportTemplate.selectedName == STUDY_JOURNEY_TEMPLATE_NAME &&
            state.journey != null &&
            state.currentJourneyStage == null

    private fun requestRecordingStop(showNoRecordingError: Boolean) {
        val meetingId = currentMeetingId
        if (meetingId.isBlank()) return
        val active = recordingController.state.value
        val belongsToCurrentMeeting = active.meetingId.isBlank() || active.meetingId == meetingId
        val hasCurrentRecording = _uiState.value.isRecording ||
            (belongsToCurrentMeeting && (active.isRecording || active.isStarting))
        if (!hasCurrentRecording) {
            _uiState.update {
                it.copy(
                    error = if (showNoRecordingError) "没有在录音" else null,
                    isRecordingActionPending = false,
                    isFinalizingRecording = false
                )
            }
            return
        }
        if (!recordingController.markStopRequested(meetingId)) {
            _uiState.update {
                it.copy(
                    isRecording = false,
                    isPaused = false,
                    isRecordingActionPending = false,
                    isFinalizingRecording = false,
                    isTranscribing = false,
                    transcriptionProgressPercent = null,
                    transcriptionProgressStage = "",
                    transcriptionProgressIndeterminate = false,
                    error = if (showNoRecordingError) "当前录音已结束" else null
                )
            }
            return
        }
        stopForegroundService(meetingId)
        _uiState.update {
            it.copy(
                isRecording = false,
                isPaused = false,
                isRecordingActionPending = true,
                isFinalizingRecording = true,
                isTranscribing = false,
                transcriptionProgressPercent = null,
                transcriptionProgressStage = "",
                transcriptionProgressIndeterminate = false,
                transcriptPreviewMode = "正在结束并保存录音",
                error = null
            )
        }
    }

    fun abandonRecording() {
        val meetingId = currentMeetingId
        if (meetingId.isBlank()) return
        val active = recordingController.state.value
        if (!active.isRecording && !active.isStarting && !_uiState.value.isRecording) return
        cancelForegroundService(meetingId)
        latestStreamingText = ""
        committedStreamingText = ""
        previewStreamingText = ""
        preservedStreamingText = ""
        currentStreamingSessionId = ""
        _uiState.update {
            it.copy(
                isRecording = false,
                isPaused = false,
                isRecordingActionPending = false,
                isFinalizingRecording = false,
                hasRecording = existingTranscriptText.isNotBlank(),
                liveTranscript = existingTranscriptText,
                isTranscribing = false,
                transcriptionProgressPercent = null,
                transcriptionProgressStage = "",
                transcriptionProgressIndeterminate = false,
                transcriptPreviewMode = "本次录音已放弃",
                error = null
            )
        }
    }

    fun cancelTranscription() {
        val meetingId = currentMeetingId
        val hasActiveImport = audioImportJob?.isActive == true
        if (meetingId.isBlank() || (!_uiState.value.isTranscribing && !hasActiveImport)) return
        audioImportJob?.cancel()
        audioImportJob = null
        audioImportToken = null
        taskScheduler.cancelTranscription(meetingId)
        _uiState.update {
            it.copy(
                isImportingAudio = false,
                isTranscribing = false,
                transcriptionProgressPercent = null,
                transcriptionProgressStage = "",
                transcriptionProgressIndeterminate = false,
                transcriptPreviewMode = "最终转录已终止",
                textImportStatus = if (it.importedAudioDisplayName.isNotBlank()) {
                    "${it.importedAudioDisplayName} · 转写已终止"
                } else {
                    it.textImportStatus
                },
                error = null
            )
        }
    }

    fun cancelReportGeneration() {
        val meetingId = currentMeetingId
        if (meetingId.isBlank() || !_uiState.value.isGeneratingReport) return
        taskScheduler.cancelReport(meetingId)
        trackedReportTaskId = null
        _uiState.update {
            it.copy(
                isGeneratingReport = false,
                reportProgressPercent = null,
                reportProgressStage = "",
                reportProgressIndeterminate = false,
                reportReadyToOpen = false,
                error = null
            )
        }
    }

    fun selectStreamingTranscript() {
        saveSelectedTranscript(TranscriptSource.STREAMING)
    }

    fun selectBackendTranscript() {
        saveSelectedTranscript(TranscriptSource.BACKEND)
    }

    private fun saveSelectedTranscript(source: TranscriptSource) {
        val meetingId = currentMeetingId
        if (meetingId.isBlank()) return
        if (isStudyJourneyAwaitingNextStage(_uiState.value)) {
            _uiState.update { it.copy(error = "请先开始下一段旅程，再保存本段转写") }
            return
        }
        val journeyStageId = _uiState.value.currentJourneyStage?.id
        val selectedText = when (source) {
            TranscriptSource.STREAMING -> _uiState.value.pendingStreamingText
            TranscriptSource.BACKEND -> _uiState.value.pendingBackendText
        }
        val finalText = existingTranscriptText
            .normalizePreviewText()
            .takeIf { it.isNotBlank() }
            ?.let { mergeTranscriptText(it, selectedText) }
            ?: selectedText

        viewModelScope.launch {
            meetingRepository.saveTranscript(
                Transcript(
                    id = java.util.UUID.randomUUID().toString(),
                    meetingId = meetingId,
                    journeyStageId = journeyStageId,
                    content = finalText
                )
            ).onSuccess {
                if (isCurrentMeeting(meetingId)) {
                    existingTranscriptText = finalText
                    updateState {
                        it.copy(
                            liveTranscript = finalText,
                            showTranscriptPicker = false,
                            pendingStreamingText = "",
                            pendingBackendText = ""
                        )
                    }
                }
            }.onFailure { error ->
                if (isCurrentMeeting(meetingId)) {
                    _uiState.update { it.copy(error = "保存转写失败: ${error.message}") }
                }
            }
        }
    }

    fun dismissTranscriptPicker() {
        _uiState.update {
            it.copy(
                showTranscriptPicker = false,
                pendingStreamingText = "",
                pendingBackendText = ""
            )
        }
    }

    fun handleScreenExit() {
        pendingRecordingStartJob?.cancel()
        pendingRecordingStartJob = null
        requestRecordingStop(showNoRecordingError = false)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun refreshArchivedAudio() {
        refreshArchivedAudio(currentMeetingId, showFailure = true)
    }

    private fun refreshArchivedAudio(meetingId: String, showFailure: Boolean) {
        if (meetingId.isBlank()) return
        audioRefreshJob?.cancel()
        audioRefreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAudio = true) }
            audioArchiveService.list(meetingId)
                .onSuccess { items ->
                    if (currentMeetingId == meetingId) {
                        _uiState.update {
                            it.copy(archivedAudio = items, isLoadingAudio = false)
                        }
                    }
                }
                .onFailure { error ->
                    if (currentMeetingId == meetingId) {
                        _uiState.update {
                            it.copy(
                                isLoadingAudio = false,
                                audioExportMessage = if (showFailure) {
                                    "会议音频加载失败: ${error.message}"
                                } else {
                                    it.audioExportMessage
                                }
                            )
                        }
                    }
                }
        }
    }

    fun saveArchivedAudio(audio: ArchivedMeetingAudio) {
        prepareArchivedAudio(audio, MeetingAudioExportAction.SAVE)
    }

    fun shareArchivedAudio(audio: ArchivedMeetingAudio) {
        prepareArchivedAudio(audio, MeetingAudioExportAction.SHARE)
    }

    private fun prepareArchivedAudio(
        audio: ArchivedMeetingAudio,
        action: MeetingAudioExportAction
    ) {
        val meetingId = currentMeetingId
        val meetingTitle = _uiState.value.meetingTitle
        if (meetingId.isBlank()) return
        if (_uiState.value.audioExportBusyId != null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(audioExportBusyId = audio.id, audioExportMessage = "")
            }
            audioArchiveService.prepareShare(audio, meetingTitle)
                .onSuccess { prepared ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                audioExportBusyId = null,
                                pendingAudioExport = PendingMeetingAudioExport(
                                    meetingId = meetingId,
                                    audioId = audio.id,
                                    prepared = prepared,
                                    action = action
                                )
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update {
                            it.copy(
                                audioExportBusyId = null,
                                audioExportMessage = "会议音频准备失败: ${error.message}"
                            )
                        }
                    }
                }
        }
    }

    fun consumeAudioExport() {
        _uiState.update { it.copy(pendingAudioExport = null) }
    }

    fun savePreparedAudio(export: PendingMeetingAudioExport, destination: Uri) {
        if (!isCurrentMeeting(export.meetingId)) return
        if (_uiState.value.audioExportBusyId != null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(audioExportBusyId = export.audioId, audioExportMessage = "")
            }
            audioArchiveService.savePrepared(export.prepared, destination)
                .onSuccess {
                    if (isCurrentMeeting(export.meetingId)) {
                        _uiState.update {
                            it.copy(
                                audioExportBusyId = null,
                                audioExportMessage = "会议音频已保存到所选位置"
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (isCurrentMeeting(export.meetingId)) {
                        _uiState.update {
                            it.copy(
                                audioExportBusyId = null,
                                audioExportMessage = "会议音频保存失败: ${error.message}"
                            )
                        }
                    }
                }
        }
    }

    fun selectReportTemplate(template: PresetReportTemplate) {
        val config = ReportTemplateConfig(
            selectedName = template.name,
            content = template.content,
            isCustom = false
        )
        _uiState.update {
            it.copy(
                reportTemplate = config,
                selectedRecordingTemplateName = template.name,
                error = it.error.takeUnless { message -> message == RECORDING_TEMPLATE_REQUIRED_MESSAGE }
            )
        }
        viewModelScope.launch {
            configDataStore.updateReportTemplate(config)
        }
    }

    fun updateReportTemplateContent(content: String) {
        val config = _uiState.value.reportTemplate.copy(
            content = content,
            isCustom = true
        )
        _uiState.update { it.copy(reportTemplate = config) }
        viewModelScope.launch {
            configDataStore.updateReportTemplate(config)
        }
    }

    fun resetReportTemplate() {
        viewModelScope.launch {
            configDataStore.resetReportTemplate()
            val config = configDataStore.appConfigFlow.first().reportTemplateConfig
            updateState { it.copy(reportTemplate = config) }
        }
    }

    fun switchToVoiceMode() {
        _uiState.update { it.copy(inputMode = InputMode.VOICE) }
    }

    fun switchToImportMode() {
        _uiState.update { it.copy(inputMode = InputMode.IMPORT) }
    }

    fun updateManualText(text: String) {
        _uiState.update {
            it.copy(
                manualTextInput = text,
                textImportStatus = if (text.isBlank()) "" else "已载入 ${text.length} 字"
            )
        }
    }

    fun importClipboardText() {
        val meetingId = currentMeetingId
        if (meetingId.isBlank()) return
        viewModelScope.launch {
            sharedTextImportCoordinator.readClipboard()
                .onSuccess { text -> appendImportedText(text, meetingId) }
                .onFailure { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update { it.copy(error = error.message ?: "读取剪贴板失败") }
                    }
                }
        }
    }

    fun importTextDocument(uri: Uri) {
        val meetingId = currentMeetingId
        if (meetingId.isBlank()) return
        viewModelScope.launch {
            sharedTextImportCoordinator.readDocument(uri)
                .onSuccess { text -> appendImportedText(text, meetingId) }
                .onFailure { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update { it.copy(error = error.message ?: "读取文本文件失败") }
                    }
                }
        }
    }

    fun importAudioDocument(uri: Uri) {
        val meetingId = currentMeetingId
        if (meetingId.isBlank()) {
            _uiState.update { it.copy(error = "会议标识缺失") }
            return
        }
        val state = _uiState.value
        if (isStudyJourneyAwaitingNextStage(state)) {
            _uiState.update { it.copy(error = "请先开始下一段旅程，再导入本段音频") }
            return
        }
        val journeyStageId = state.currentJourneyStage?.id
        if (state.isImportingAudio || state.isRecording || state.isTranscribing) return
        val importToken = java.util.UUID.randomUUID().toString()
        audioImportToken = importToken
        audioImportJob = viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isImportingAudio = true,
                        isTranscribing = true,
                        transcriptionProgressPercent = null,
                        transcriptionProgressIndeterminate = true,
                        transcriptionProgressStage = "正在导入会议音频",
                        transcriptPreviewMode = "正在导入音频",
                        importedAudioDisplayName = "",
                        textImportStatus = "正在读取音频文件",
                        error = null
                    )
                }
                importedAudioStore.import(uri).fold(
                    onSuccess = { imported ->
                        if (!isCurrentMeeting(meetingId)) return@fold
                        runCatching {
                            val meeting = meetingRepository.findById(meetingId).getOrNull()
                                ?: error("会议不存在")
                            meetingRepository.save(meeting.copy(audioFilePath = imported.file.absolutePath))
                                .getOrThrow()
                            taskScheduler.enqueueTranscription(
                                meetingId = meetingId,
                                audioFile = imported.file,
                                journeyStageId = journeyStageId
                            )
                        }.onSuccess {
                            if (isCurrentMeeting(meetingId)) {
                                _uiState.update {
                                    it.copy(
                                        isImportingAudio = false,
                                        hasRecording = true,
                                        inputMode = InputMode.IMPORT,
                                        importedAudioDisplayName = imported.displayName,
                                        textImportStatus = "${imported.displayName} · 正在生成最终转写",
                                        transcriptionProgressStage = "最终转录正在排队",
                                        transcriptPreviewMode = "正在识别导入音频"
                                    )
                                }
                            }
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            if (isCurrentMeeting(meetingId)) {
                                _uiState.update {
                                    it.copy(
                                        isImportingAudio = false,
                                        isTranscribing = false,
                                        transcriptionProgressPercent = null,
                                        transcriptionProgressIndeterminate = false,
                                        transcriptionProgressStage = "",
                                        textImportStatus = "音频导入失败",
                                        error = "音频导入失败: ${error.message ?: "未知错误"}"
                                    )
                                }
                            }
                        }
                    },
                    onFailure = { error ->
                        if (isCurrentMeeting(meetingId)) {
                            _uiState.update {
                                it.copy(
                                    isImportingAudio = false,
                                    isTranscribing = false,
                                    transcriptionProgressPercent = null,
                                    transcriptionProgressIndeterminate = false,
                                    transcriptionProgressStage = "",
                                    textImportStatus = "音频导入失败",
                                    error = "音频导入失败: ${error.message ?: "未知错误"}"
                                )
                            }
                        }
                    }
                )
            } finally {
                if (audioImportToken == importToken) {
                    audioImportJob = null
                    audioImportToken = null
                }
            }
        }
    }

    fun openExternalTextSource(source: ExternalTextSource) {
        val meetingId = currentMeetingId
        if (meetingId.isBlank()) return
        externalTextSourceLauncher.open(source)
            .onSuccess { pendingExternalTextMeetingId = meetingId }
            .onFailure { error ->
                if (isCurrentMeeting(meetingId)) {
                    _uiState.update { it.copy(error = error.message ?: "无法打开${source.label}") }
                }
            }
    }

    private fun appendImportedText(text: String, meetingId: String = currentMeetingId) {
        if (!isCurrentMeeting(meetingId)) return
        _uiState.update { state ->
            val merged = listOf(state.manualTextInput.trim(), text.trim())
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            state.copy(
                inputMode = InputMode.IMPORT,
                manualTextInput = merged,
                textImportStatus = "已载入 ${merged.length} 字",
                error = null
            )
        }
    }

    fun saveTextAndGenerateReport() {
        val text = _uiState.value.manualTextInput.trim()
        val meetingId = currentMeetingId
        if (isStudyJourneyAwaitingNextStage(_uiState.value)) {
            _uiState.update { it.copy(error = "请先开始下一段旅程，再保存人工文本") }
            return
        }
        val journeyStageId = _uiState.value.currentJourneyStage?.id
        if (text.isBlank() || meetingId.isBlank()) return

        viewModelScope.launch {
            val transcript = Transcript(
                id = java.util.UUID.randomUUID().toString(),
                meetingId = meetingId,
                journeyStageId = journeyStageId,
                content = text
            )
            meetingRepository.saveTranscript(transcript)
                .onSuccess {
                    if (isCurrentMeeting(meetingId)) {
                        existingTranscriptText = text
                        _uiState.update { it.copy(hasRecording = true, liveTranscript = text) }
                    }
                    if (configDataStore.authSessionFlow.first() == null) {
                        _uiState.update {
                            it.copy(
                                requiresLogin = true,
                                error = "登录后即可生成 AI 纪要，本地文本已保存"
                            )
                        }
                    } else {
                        enqueueReportGeneration(meetingId)
                    }
                }
                .onFailure { e ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update { it.copy(error = "保存文本失败: ${e.message}") }
                    }
                }
        }
    }

    fun generateFromImport() {
        if (_uiState.value.manualTextInput.isNotBlank()) {
            saveTextAndGenerateReport()
        } else {
            generateReport()
        }
    }

    fun generateReport() {
        val state = _uiState.value
        val meetingId = currentMeetingId
        when {
            meetingId.isBlank() -> _uiState.update { it.copy(error = "会议标识缺失") }
            state.isRecording -> _uiState.update { it.copy(error = "请先停止录音") }
            state.isTranscribing -> _uiState.update { it.copy(error = "请等待后台转写完成") }
            !state.hasRecording || state.liveTranscript.isBlank() -> {
                _uiState.update { it.copy(error = "请先录音或输入会议内容") }
            }
            state.isGeneratingReport -> Unit
            else -> viewModelScope.launch {
                if (configDataStore.authSessionFlow.first() == null) {
                    _uiState.update {
                        it.copy(
                            requiresLogin = true,
                            error = "登录后即可生成 AI 纪要"
                        )
                    }
                } else {
                    enqueueReportGeneration(meetingId)
                }
            }
        }
    }

    fun consumeLoginRequest() {
        _uiState.update { it.copy(requiresLogin = false) }
    }

    fun consumeReportNavigation() {
        _uiState.update { it.copy(reportReadyToOpen = false) }
    }

    fun importImages(
        uris: List<Uri>,
        captureLocation: Boolean = false,
        recordingMarkerId: String? = null
    ) {
        val meetingId = currentMeetingId
        if (meetingId.isBlank() || uris.isEmpty()) return
        val state = _uiState.value
        if (!canStartImageImport(state)) {
            _uiState.update { it.copy(error = "图片正在导入，请稍候") }
            return
        }
        val target = resolveImageImportTarget(
            state = state,
            recordingMarkers = recordingMarkerRecords,
            recordingMarkerId = recordingMarkerId
        )
        if (target == null) {
            _uiState.update { it.copy(error = "图文标记已失效，请重新标记") }
            return
        }
        _uiState.update {
            it.copy(
                isImportingImages = true,
                imageImportCompleted = 0,
                imageImportTotal = uris.size,
                error = null
            )
        }
        imageImportJob = viewModelScope.launch {
            try {
                val results = attachmentStore.importImages(
                    meetingId = meetingId,
                    sources = uris,
                    captureLocation = captureLocation,
                    journeyStageId = target.journeyStageId,
                    recordingMarker = target.recordingMarker,
                    onProgress = { completed, total ->
                        if (isCurrentMeeting(meetingId)) {
                            _uiState.update {
                                it.copy(
                                    imageImportCompleted = completed,
                                    imageImportTotal = total
                                )
                            }
                        }
                    }
                )
                if (!isCurrentMeeting(meetingId)) return@launch

                val summary = summarizeImageImport(results)
                val closePhotoMarker = shouldCloseActivePhotoMarker(
                    activeMarkerId = _uiState.value.activePhotoMarker?.id,
                    importedMarkerId = recordingMarkerId,
                    importedCount = summary.succeeded
                )
                _uiState.update {
                    it.copy(
                        activePhotoMarker = if (closePhotoMarker) null else it.activePhotoMarker,
                        transcriptPreviewMode = when {
                            closePhotoMarker -> "图片已关联到 ${formatRecordingMarker(target.recordingMarker!!.timestampMs / 1_000L)}"
                            summary.succeeded > 0 -> "已导入 ${summary.succeeded} 张图片"
                            else -> it.transcriptPreviewMode
                        },
                        error = summary.failureMessage()
                    )
                }
            } finally {
                if (isCurrentMeeting(meetingId)) {
                    _uiState.update {
                        it.copy(
                            isImportingImages = false,
                            imageImportCompleted = 0,
                            imageImportTotal = 0
                        )
                    }
                }
            }
        }
    }

    fun deleteAttachment(attachment: MeetingAttachment) {
        viewModelScope.launch {
            attachmentStore.delete(attachment).onFailure { error ->
                if (isCurrentMeeting(attachment.meetingId)) {
                    _uiState.update { it.copy(error = "图片删除失败: ${error.message}") }
                }
            }
        }
    }

    override fun onCleared() {
        attachmentCollectionJob?.cancel()
        journeyCollectionJob?.cancel()
        journeyStageCollectionJob?.cancel()
        stageDraftCollectionJob?.cancel()
        journeyEditionCollectionJob?.cancel()
        publishedPostCollectionJob?.cancel()
        communitySyncCollectionJob?.cancel()
        transcriptionCollectionJob?.cancel()
        reportCollectionJob?.cancel()
        audioRefreshJob?.cancel()
        audioImportJob?.cancel()
        imageImportJob?.cancel()
        audioImportToken = null
        pendingExternalTextMeetingId = null
        recordingTimerJob?.cancel()
        studyJourneyAutomationJob?.cancel()
        super.onCleared()
    }

    private fun CommunitySyncStatus?.toJourneyStatusMessage(): String? = when (this) {
        CommunitySyncStatus.PENDING -> "社区内容等待同步"
        CommunitySyncStatus.UPLOADING -> "正在同步社区私有草稿"
        CommunitySyncStatus.PRIVATE_DRAFT -> "社区私有草稿已同步"
        CommunitySyncStatus.PUBLISHING -> "正在发布社区内容"
        CommunitySyncStatus.PUBLISHED -> "社区内容已发布"
        CommunitySyncStatus.WITHDRAWING -> "正在同步撤回状态"
        CommunitySyncStatus.WITHDRAWN -> "社区内容已撤回"
        CommunitySyncStatus.FAILED -> "社区同步失败，可稍后重试"
        null -> null
    }

    private fun enqueueReportGeneration(meetingId: String) {
        val taskId = taskScheduler.enqueueReport(meetingId)
        if (isCurrentMeeting(meetingId)) {
            trackedReportTaskId = taskId
            _uiState.update {
                it.copy(
                    isGeneratingReport = true,
                    reportProgressPercent = null,
                    reportProgressStage = "会议纪要正在排队",
                    reportProgressIndeterminate = true,
                    reportReadyToOpen = false,
                    error = null
                )
            }
        }
    }

    private fun observeReportTask(meetingId: String) {
        reportCollectionJob?.cancel()
        reportCollectionJob = viewModelScope.launch {
            taskScheduler.observeReport(meetingId).collect { task ->
                if (!isCurrentMeeting(meetingId)) return@collect
                when (task.state) {
                    BackgroundTaskState.QUEUED, BackgroundTaskState.RUNNING -> {
                        trackedReportTaskId = task.id
                        _uiState.update {
                            it.copy(
                                isGeneratingReport = true,
                                reportProgressPercent = task.progressPercent,
                                reportProgressStage = task.progressStage.ifBlank {
                                    if (task.state == BackgroundTaskState.QUEUED) {
                                        "会议纪要正在排队"
                                    } else {
                                        "会议纪要处理中"
                                    }
                                },
                                reportProgressIndeterminate = task.progressIndeterminate ||
                                    task.progressPercent == null,
                                reportReadyToOpen = false,
                                error = null
                            )
                        }
                    }

                    BackgroundTaskState.SUCCEEDED -> {
                        if (trackedReportTaskId == task.id) {
                            trackedReportTaskId = null
                            _uiState.update {
                                it.copy(
                                    isGeneratingReport = false,
                                    hasReport = true,
                                    reportProgressPercent = null,
                                    reportProgressStage = "",
                                    reportProgressIndeterminate = false,
                                    reportReadyToOpen = true,
                                    error = null
                                )
                            }
                        }
                    }

                    BackgroundTaskState.FAILED -> {
                        if (trackedReportTaskId == task.id) {
                            trackedReportTaskId = null
                            _uiState.update {
                                it.copy(
                                    isGeneratingReport = false,
                                    reportProgressPercent = null,
                                    reportProgressStage = "",
                                    reportProgressIndeterminate = false,
                                    reportReadyToOpen = false,
                                    error = "会议纪要生成失败: ${task.error ?: "Agent 服务请求失败"}"
                                )
                            }
                        }
                    }

                    BackgroundTaskState.CANCELLED -> {
                        if (trackedReportTaskId == task.id) {
                            trackedReportTaskId = null
                            _uiState.update {
                                it.copy(
                                    isGeneratingReport = false,
                                    reportProgressPercent = null,
                                    reportProgressStage = "",
                                    reportProgressIndeterminate = false,
                                    reportReadyToOpen = false,
                                    error = null
                                )
                            }
                        }
                    }

                    BackgroundTaskState.NONE -> Unit
                }
            }
        }
    }

    private fun observeTranscriptionTask(meetingId: String) {
        transcriptionCollectionJob?.cancel()
        transcriptionCollectionJob = viewModelScope.launch {
            taskScheduler.observeTranscription(meetingId).collect { task ->
                if (!isCurrentMeeting(meetingId)) return@collect
                val recordingSession = recordingController.state.value
                val isActiveRecording = recordingSession.meetingId == meetingId &&
                    recordingSession.isRecording && !recordingSession.isStopping
                when (task.state) {
                    BackgroundTaskState.QUEUED, BackgroundTaskState.RUNNING -> {
                        _uiState.update {
                            it.copy(
                                // Final transcription is independent from the next
                                // live segment. Never let a background task hide an
                                // active microphone session.
                                isRecording = isActiveRecording,
                                isTranscribing = true,
                                transcriptionProgressPercent = task.progressPercent,
                                transcriptionProgressStage = task.progressStage.ifBlank {
                                    if (task.state == BackgroundTaskState.QUEUED) {
                                        "后台转写正在排队"
                                    } else {
                                        "最终转录处理中"
                                    }
                                },
                                transcriptionProgressIndeterminate = task.progressIndeterminate ||
                                    task.progressPercent == null,
                                transcriptPreviewMode = if (task.state == BackgroundTaskState.QUEUED) {
                                    "后台转写已排队"
                                } else {
                                    "后台转写中"
                                },
                                error = null
                            )
                        }
                    }

                    BackgroundTaskState.SUCCEEDED -> {
                        val transcripts = meetingRepository.findTranscriptsByMeetingId(meetingId)
                            .getOrNull()
                            .orEmpty()
                        if (!isCurrentMeeting(meetingId)) return@collect
                        val finalText = SimplifiedChineseText.normalize(
                            transcripts.canonicalMeetingTranscripts().joinToString("\n") { it.content }
                        )
                        val currentLiveTranscript = _uiState.value.liveTranscript
                        existingTranscriptText = finalText
                        preservedStreamingText = ""
                        currentStreamingSessionId = ""
                        _uiState.update {
                            it.copy(
                                isRecording = isActiveRecording,
                                hasRecording = finalText.isNotBlank() || isActiveRecording,
                                isTranscribing = false,
                                transcriptionProgressPercent = null,
                                transcriptionProgressStage = "",
                                transcriptionProgressIndeterminate = false,
                                // Keep the second segment's live preview visible
                                // while the first segment is being finalized.
                                liveTranscript = if (isActiveRecording) {
                                    currentLiveTranscript
                                } else {
                                    finalText
                                },
                                transcriptPreviewMode = "最终稿",
                                textImportStatus = if (it.importedAudioDisplayName.isNotBlank()) {
                                    "${it.importedAudioDisplayName} · 转写完成 ${finalText.length} 字"
                                } else {
                                    it.textImportStatus
                                },
                                error = null
                            )
                        }
                        refreshArchivedAudio(meetingId, showFailure = false)
                    }

                    BackgroundTaskState.FAILED -> {
                        val authRequired = task.error == RecordingService.AUTH_REQUIRED_MESSAGE
                        _uiState.update {
                            it.copy(
                                isRecording = isActiveRecording,
                                isTranscribing = false,
                                transcriptionProgressPercent = null,
                                transcriptionProgressStage = "",
                                transcriptionProgressIndeterminate = false,
                                transcriptPreviewMode = "后台转写失败",
                                textImportStatus = if (it.importedAudioDisplayName.isNotBlank()) {
                                    "${it.importedAudioDisplayName} · 转写失败"
                                } else {
                                    it.textImportStatus
                                },
                                error = if (authRequired) {
                                    task.error
                                } else {
                                    "后台转写失败: ${task.error ?: "未知错误"}"
                                },
                                requiresLogin = authRequired || it.requiresLogin
                            )
                        }
                        refreshArchivedAudio(meetingId, showFailure = false)
                    }

                    BackgroundTaskState.CANCELLED -> {
                        _uiState.update {
                            it.copy(
                                isRecording = isActiveRecording,
                                isTranscribing = false,
                                transcriptionProgressPercent = null,
                                transcriptionProgressStage = "",
                                transcriptionProgressIndeterminate = false,
                                transcriptPreviewMode = "后台转写已取消",
                                textImportStatus = if (it.importedAudioDisplayName.isNotBlank()) {
                                    "${it.importedAudioDisplayName} · 转写已取消"
                                } else {
                                    it.textImportStatus
                                }
                            )
                        }
                        refreshArchivedAudio(meetingId, showFailure = false)
                    }

                    BackgroundTaskState.NONE -> Unit
                }
            }
        }
    }

    private fun updateStreamingPreview(update: StreamingTranscriptUpdate) {
        val text = SimplifiedChineseText.normalize(update.text).normalizePreviewText()
        val committedText = SimplifiedChineseText.normalize(update.committedText).normalizePreviewText()
        val previewText = SimplifiedChineseText.normalize(update.previewText).normalizePreviewText()
        val incomingSessionId = update.sessionId.trim()
        if (text.isBlank() && committedText.isBlank() && previewText.isBlank()) return
        if (!hasStreamingPreviewChanged(
                text = text,
                committedText = committedText,
                previewText = previewText,
                latestText = latestStreamingText,
                latestCommittedText = committedStreamingText,
                latestPreviewText = previewStreamingText
            )
        ) return

        if (shouldRollStreamingSession(currentStreamingSessionId, incomingSessionId)) {
            preservedStreamingText = preserveStreamingSessionText(
                preservedText = preservedStreamingText,
                latestText = latestStreamingText,
                committedText = committedStreamingText,
                previewText = previewStreamingText
            )
            latestStreamingText = ""
            committedStreamingText = ""
            previewStreamingText = ""
        } else {
            val previousSessionText = currentStreamingSessionText(
                latestText = latestStreamingText,
                committedText = committedStreamingText,
                previewText = previewStreamingText
            )
            val incomingSessionText = currentStreamingSessionText(
                latestText = text,
                committedText = committedText,
                previewText = previewText
            )
            preservedStreamingText = preserveStreamingPreviewRegression(
                preservedText = preservedStreamingText,
                previousSessionText = previousSessionText,
                incomingSessionText = incomingSessionText
            )
        }
        if (incomingSessionId.isNotBlank()) currentStreamingSessionId = incomingSessionId

        latestStreamingText = text
        committedStreamingText = committedText
        previewStreamingText = previewText

        val currentSessionText = listOf(committedStreamingText, previewStreamingText)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { text }
        val allStreamingText = mergeTranscriptText(preservedStreamingText, currentSessionText)
        val displayText = mergeTranscriptText(existingTranscriptText, allStreamingText)

        _uiState.update {
            it.copy(
                liveTranscript = displayText,
                transcriptPreviewMode = "实时预览（可修订）"
            )
        }
    }

    private fun startForegroundService(
        meetingId: String,
        journeyStageId: String?,
        autoGenerateReport: Boolean
    ) {
        val intent = Intent(appContext, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_MEETING_ID, meetingId)
            putExtra(RecordingService.EXTRA_MEETING_TITLE, _uiState.value.meetingTitle)
            putExtra(RecordingService.EXTRA_JOURNEY_STAGE_ID, journeyStageId)
            putExtra(RecordingService.EXTRA_AUTO_GENERATE_REPORT, autoGenerateReport)
        }
        appContext.startForegroundService(intent)
    }

    private fun stopForegroundService(meetingId: String) {
        val intent = Intent(appContext, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
            putExtra(RecordingService.EXTRA_MEETING_ID, meetingId)
        }
        appContext.startService(intent)
    }

    private fun cancelForegroundService(meetingId: String) {
        val intent = Intent(appContext, RecordingService::class.java).apply {
            action = RecordingService.ACTION_CANCEL
            putExtra(RecordingService.EXTRA_MEETING_ID, meetingId)
        }
        appContext.startService(intent)
    }

    private fun formatRecordingMarker(seconds: Long): String =
        "%02d:%02d".format(seconds / 60, seconds % 60)

    companion object {
        private const val RECORDING_TIMER_REFRESH_MS = 250L
        private const val MARKER_TRANSCRIPT_ANCHOR_CHARS = 180
        private const val STUDY_JOURNEY_TEMPLATE_NAME = "研学考察"
    }
}

internal fun shouldRollStreamingSession(currentSessionId: String, incomingSessionId: String): Boolean =
    currentSessionId.isNotBlank() && incomingSessionId.isNotBlank() && currentSessionId != incomingSessionId

internal fun extractRecordingMarkerAnchor(
    transcript: String,
    maxChars: Int = 180
): String {
    val normalized = SimplifiedChineseText.normalize(transcript).trim()
    if (normalized.isBlank() || maxChars <= 0) return ""
    val searchText = normalized.takeLast(maxChars * 2)
    val boundaries = "。！？!?；;\n"
    var endExclusive = searchText.length
    while (endExclusive > 0 && searchText[endExclusive - 1].isWhitespace()) endExclusive--
    if (endExclusive <= 0) return ""

    val endsAtBoundary = searchText[endExclusive - 1] in boundaries
    val boundarySearchEnd = if (endsAtBoundary) endExclusive - 1 else endExclusive
    var start = -1
    for (index in boundarySearchEnd - 1 downTo 0) {
        if (searchText[index] in boundaries) {
            start = index
            break
        }
    }
    var candidate = searchText.substring(start + 1, endExclusive).trim()
    if (candidate.length < 4 && start > 0) {
        var previousStart = -1
        for (index in start - 1 downTo 0) {
            if (searchText[index] in boundaries) {
                previousStart = index
                break
            }
        }
        candidate = searchText.substring(previousStart + 1, endExclusive).trim()
    }
    return candidate.takeLast(maxChars).trim()
}

internal fun findRecordingMarkerAnchorRanges(
    transcript: String,
    anchors: List<String>
): List<IntRange> {
    if (transcript.isBlank()) return emptyList()
    val occupied = mutableListOf<IntRange>()
    anchors.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sortedByDescending(String::length)
        .forEach { anchor ->
            val start = transcript.lastIndexOf(anchor)
            if (start < 0) return@forEach
            val range = start..(start + anchor.length - 1)
            if (occupied.none { existing -> range.first <= existing.last && existing.first <= range.last }) {
                occupied += range
            }
        }
    return occupied.sortedBy(IntRange::first)
}

internal data class RecordingMarkerTranscriptSegment(
    val text: String,
    val isMarker: Boolean
)

internal fun recordingMarkerTranscriptSegments(
    transcript: String,
    anchors: List<String>
): List<RecordingMarkerTranscriptSegment> {
    if (transcript.isEmpty()) return emptyList()
    val ranges = findRecordingMarkerAnchorRanges(transcript, anchors)
    if (ranges.isEmpty()) return listOf(RecordingMarkerTranscriptSegment(transcript, isMarker = false))

    val segments = mutableListOf<RecordingMarkerTranscriptSegment>()
    var cursor = 0
    ranges.forEach { range ->
        if (cursor < range.first) {
            segments += RecordingMarkerTranscriptSegment(
                text = transcript.substring(cursor, range.first),
                isMarker = false
            )
        }
        segments += RecordingMarkerTranscriptSegment(
            text = transcript.substring(range.first, range.last + 1),
            isMarker = true
        )
        cursor = range.last + 1
    }
    if (cursor < transcript.length) {
        segments += RecordingMarkerTranscriptSegment(
            text = transcript.substring(cursor),
            isMarker = false
        )
    }
    return segments
}

internal fun studyStageTranscriptDelta(
    baseline: String,
    currentTranscript: String
): String {
    val current = SimplifiedChineseText.normalize(currentTranscript).trim()
    val base = SimplifiedChineseText.normalize(baseline).trim()
    if (current.isBlank()) return ""
    if (base.isBlank()) return current
    if (current.startsWith(base)) return current.removePrefix(base).trim()

    val commonPrefixLength = current.commonPrefixWith(base).length
    val substantialPrefix = commonPrefixLength >= minOf(base.length, maxOf(8, base.length * 3 / 4))
    return if (substantialPrefix) current.substring(commonPrefixLength).trim() else current
}

internal fun hasMeaningfulStudyStageEvidence(
    transcriptDelta: String,
    markerCount: Int,
    attachmentCount: Int
): Boolean = transcriptDelta.isNotBlank() || markerCount > 0 || attachmentCount > 0

internal fun resolveStudyStageEvidence(
    baseline: String,
    startedDurationSeconds: Long,
    snapshot: StudyStageFinalizationSnapshot
): ResolvedStudyStageEvidence {
    val transcriptDelta = studyStageTranscriptDelta(
        baseline = baseline,
        currentTranscript = snapshot.transcript
    )
    val startSeconds = startedDurationSeconds.coerceAtLeast(0L)
    val endSeconds = snapshot.durationSeconds.coerceAtLeast(startSeconds)
    return ResolvedStudyStageEvidence(
        transcriptDelta = transcriptDelta,
        startTimeMs = startSeconds * 1_000L,
        endTimeMs = endSeconds * 1_000L,
        isMeaningful = hasMeaningfulStudyStageEvidence(
            transcriptDelta = transcriptDelta,
            markerCount = snapshot.markerCount,
            attachmentCount = snapshot.attachmentCount
        )
    )
}

internal fun shouldCloseActivePhotoMarker(
    activeMarkerId: String?,
    importedMarkerId: String?,
    importedCount: Int
): Boolean = importedCount > 0 &&
    activeMarkerId != null &&
    activeMarkerId == importedMarkerId

internal fun preserveStreamingSessionText(
    preservedText: String,
    latestText: String,
    committedText: String,
    previewText: String
): String {
    val previousSessionText = committedText
        .ifBlank { latestText }
        .ifBlank { previewText }
    return mergeTranscriptText(preservedText, previousSessionText)
}

internal fun preserveStreamingPreviewRegression(
    preservedText: String,
    previousSessionText: String,
    incomingSessionText: String
): String {
    if (!isSevereStreamingPreviewRegression(previousSessionText, incomingSessionText)) {
        return preservedText
    }
    return mergeTranscriptText(preservedText, previousSessionText)
}

internal fun isSevereStreamingPreviewRegression(
    previousSessionText: String,
    incomingSessionText: String
): Boolean {
    val previous = previousSessionText.normalizePreviewText()
    val incoming = incomingSessionText.normalizePreviewText()
    if (previous.length < 16 || incoming.isBlank() || incoming.length >= previous.length) return false
    val minimumMaterialDrop = maxOf(12, previous.length / 4)
    return previous.length - incoming.length >= minimumMaterialDrop
}

private fun currentStreamingSessionText(
    latestText: String,
    committedText: String,
    previewText: String
): String = listOf(committedText, previewText)
    .filter { it.isNotBlank() }
    .joinToString("\n")
    .ifBlank { latestText }

private val previewTagPattern = Regex("<[^>\\r\\n]{0,120}>")
private val previewWhitespacePattern = Regex("\\s+")

private fun String.normalizePreviewText(): String =
    replace(previewTagPattern, " ")
        .replace(previewWhitespacePattern, " ")
        .trim()

internal fun hasStreamingPreviewChanged(
    text: String,
    committedText: String,
    previewText: String,
    latestText: String,
    latestCommittedText: String,
    latestPreviewText: String
): Boolean {
    if (text.isBlank() && committedText.isBlank() && previewText.isBlank()) return false
    return text != latestText ||
        committedText != latestCommittedText ||
        previewText != latestPreviewText
}

internal fun mergeTranscriptText(existing: String, update: String): String {
    val base = existing.normalizePreviewText()
    val next = update.normalizePreviewText()
    if (base.isBlank()) return next
    if (next.isBlank() || base.contains(next)) return base
    if (next.contains(base)) return next

    val overlap = longestTranscriptOverlap(base, next)
    if (overlap >= 4) {
        return (base + next.substring(overlap)).normalizePreviewText()
    }

    return dedupeTranscriptLines("$base\n$next")
}

internal fun longestTranscriptOverlap(base: String, next: String): Int {
    if (base.isEmpty() || next.isEmpty()) return 0

    val prefix = IntArray(next.length)
    var prefixLength = 0
    for (index in 1 until next.length) {
        while (prefixLength > 0 && next[index] != next[prefixLength]) {
            prefixLength = prefix[prefixLength - 1]
        }
        if (next[index] == next[prefixLength]) prefixLength++
        prefix[index] = prefixLength
    }

    var matched = 0
    val start = (base.length - next.length).coerceAtLeast(0)
    for (index in start until base.length) {
        val character = base[index]
        while (matched > 0 && character != next[matched]) {
            matched = prefix[matched - 1]
        }
        if (character == next[matched]) matched++
        if (matched == next.length && index != base.lastIndex) {
            matched = prefix[matched - 1]
        }
    }
    return matched
}

private fun dedupeTranscriptLines(text: String): String {
    // Split into sentences by Chinese sentence-end punctuation (。！？) + optional whitespace/newline
    // Do NOT split by individual punctuation — that creates fragments too short for similarity comparison
    val rawSentences = text.split(Regex("([。！？])\\s*"))
    val seen = LinkedHashSet<String>()
    val normalizedSeen = ArrayList<String>()
    return rawSentences
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filter { sentence ->
            val normalized = sentence.normalizeForDedupe()
            // Skip if a previously seen sentence already covers this one
            val dominated = normalizedSeen.any { existing ->
                existing.contains(normalized) || normalized.contains(existing)
            }
            if (!dominated) {
                seen.add(sentence)
                normalizedSeen.add(normalized)
            }
            !dominated
        }
        .joinToString("。\n")
        .trim()
}

private fun String.normalizeForDedupe(): String =
    replace(Regex("<[^>\\r\\n]{0,120}>"), "")
        .replace(Regex("\\s+"), "")
        .trim()
