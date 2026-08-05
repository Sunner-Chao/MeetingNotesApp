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
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.repository.JourneyRepository
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.domain.repository.StageDraftRepository
import com.oa.automation.application.usecase.GenerateStageDraftUseCase
import com.oa.automation.domain.model.Transcript
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.STTLanguage
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
import com.oa.automation.infrastructure.stt.StreamingTranscriptUpdate
import com.oa.automation.infrastructure.service.durationSecondsAt
import com.oa.automation.infrastructure.stt.STTServiceClient
import com.oa.automation.infrastructure.textimport.SharedTextImportCoordinator
import com.oa.automation.infrastructure.textimport.ExternalTextSource
import com.oa.automation.infrastructure.textimport.ExternalTextSourceLauncher
import com.oa.automation.locale.SimplifiedChineseText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val presetTemplates: List<PresetReportTemplate> = emptyList(),
    val reportTemplate: ReportTemplateConfig = ReportTemplateConfig(),
    val journey: Journey? = null,
    val currentJourneyStage: JourneyStage? = null,
    val latestSavedJourneyStage: JourneyStage? = null,
    val latestStageDraft: StageDraftVersion? = null,
    val isGeneratingStageDraft: Boolean = false,
    val isSavingStageDraft: Boolean = false,
    val stageDraftEditorVisible: Boolean = false,
    val journeyStageCount: Int = 0,
    val isJourneyActionPending: Boolean = false,
    val journeyStatusMessage: String = "",
    val error: String? = null,
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
    val recordingMarkers: List<Long> = emptyList()
)

internal fun recordingActionPending(session: RecordingSessionState): Boolean = when {
    session.error != null -> false
    session.isStarting || session.isStopping -> true
    session.isRecording -> false
    session.status == "录音启动已取消" -> false
    else -> false
}

internal fun isRecordingActionEnabled(state: RecordingUiState): Boolean =
    !state.isRecordingActionPending &&
        !state.isJourneyActionPending &&
        !state.isGeneratingReport

internal fun RecordingUiState.resetForMeetingChange(): RecordingUiState = copy(
    meetingTitle = "",
    isSwitchingSttEngine = false,
    isSwitchingSttLanguage = false,
    isSavingAgentProvider = false,
    isRecordingActionPending = false,
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
    journey = null,
    currentJourneyStage = null,
    latestSavedJourneyStage = null,
    latestStageDraft = null,
    isGeneratingStageDraft = false,
    isSavingStageDraft = false,
    stageDraftEditorVisible = false,
    journeyStageCount = 0,
    isJourneyActionPending = false,
    journeyStatusMessage = "",
    error = null,
    inputMode = InputMode.VOICE,
    manualTextInput = "",
    textImportStatus = "",
    importedAudioDisplayName = "",
    showTranscriptPicker = false,
    pendingStreamingText = "",
    pendingBackendText = "",
    attachments = emptyList(),
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
    recordingMarkers = emptyList()
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
    private val reportRepository: ReportRepository,
    private val attachmentStore: MeetingAttachmentStore,
    private val recordingController: RecordingSessionController,
    private val taskScheduler: BackgroundTaskScheduler,
    private val sharedTextImportCoordinator: SharedTextImportCoordinator,
    private val externalTextSourceLauncher: ExternalTextSourceLauncher,
    private val audioArchiveService: MeetingAudioArchiveService,
    private val importedAudioStore: ImportedAudioStore,
    private val generateStageDraftUseCase: GenerateStageDraftUseCase,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var currentMeetingId: String = ""
    private var latestStreamingText: String = ""
    private var committedStreamingText: String = ""
    private var previewStreamingText: String = ""
    private var preservedStreamingText: String = ""
    private var currentStreamingSessionId: String = ""
    private var existingTranscriptText: String = ""
    private var attachmentCollectionJob: Job? = null
    private var journeyCollectionJob: Job? = null
    private var journeyStageCollectionJob: Job? = null
    private var stageDraftCollectionJob: Job? = null
    private var transcriptionCollectionJob: Job? = null
    private var reportCollectionJob: Job? = null
    private var pendingRecordingStartJob: Job? = null
    private var audioRefreshJob: Job? = null
    private var audioImportJob: Job? = null
    private var audioImportToken: String? = null
    private var pendingExternalTextMeetingId: String? = null
    private var recordingTimerJob: Job? = null
    private var activeTimerStartedAtMs: Long? = null
    private var trackedReportTaskId: java.util.UUID? = null

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
                _uiState.update {
                    it.copy(
                        isRecording = session.isRecording && !session.isStopping,
                        isPaused = session.isPaused,
                        isRecordingActionPending = recordingActionPending(session),
                        recordingDuration = session.durationSecondsAt(SystemClock.elapsedRealtime()),
                        audioLevel = session.audioLevel,
                        transcriptPreviewMode = session.status,
                        isTranscribing = if (
                            session.error != null || session.status == "录音启动已取消"
                        ) false else it.isTranscribing,
                        transcriptionProgressPercent = if (
                            session.error != null || session.status == "录音启动已取消"
                        ) null else it.transcriptionProgressPercent,
                        transcriptionProgressStage = if (
                            session.error != null || session.status == "录音启动已取消"
                        ) "" else it.transcriptionProgressStage,
                        transcriptionProgressIndeterminate = if (
                            session.error != null || session.status == "录音启动已取消"
                        ) false else it.transcriptionProgressIndeterminate,
                        error = session.error ?: it.error
                    )
                }
                synchronizeRecordingTimer(session)
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
            pendingExternalTextMeetingId = null
            recordingTimerJob?.cancel()
            recordingTimerJob = null
            activeTimerStartedAtMs = null
            trackedReportTaskId = null
            journeyCollectionJob?.cancel()
            journeyCollectionJob = null
            journeyStageCollectionJob?.cancel()
            journeyStageCollectionJob = null
            stageDraftCollectionJob?.cancel()
            stageDraftCollectionJob = null
            resetStreamingPreviewState()
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
            val transcriptText = SimplifiedChineseText.normalize(
                transcripts.joinToString("\n") { it.content }
            )
            val hasReport = reportRepository.findByMeetingId(meetingId).getOrNull() != null
            val appConfig = configDataStore.appConfigFlow.first()
            val sttConfig = appConfig.sttConfig
            val recordingState = recordingController.state.value
            val isGlobalRecording = recordingState.meetingId == meetingId &&
                (recordingState.isRecording || recordingState.isStarting)
            val restoredDurationSeconds = if (recordingState.meetingId == meetingId) {
                maxOf(
                    meeting?.durationMs?.div(1_000L) ?: 0L,
                    recordingState.durationSecondsAt(SystemClock.elapsedRealtime())
                )
            } else {
                meeting?.durationMs?.div(1_000L) ?: 0L
            }
            if (meeting != null && isCurrentMeeting(meetingId)) {
                existingTranscriptText = transcriptText
                updateState {
                    it.copy(
                        meetingTitle = meeting.title,
                        sttEngineLabel = sttConfig.engineType.displayName,
                        sttLanguage = sttConfig.language,
                        isRecording = isGlobalRecording,
                        isPaused = recordingState.isPaused,
                        hasRecording = isGlobalRecording || transcriptText.isNotBlank(),
                        hasReport = hasReport,
                        recordingDuration = restoredDurationSeconds,
                        audioLevel = recordingState.audioLevel,
                        liveTranscript = when {
                            isGlobalRecording && it.liveTranscript.isNotBlank() -> it.liveTranscript
                            else -> transcriptText
                        },
                        transcriptPreviewMode = when {
                            isGlobalRecording -> "WebSocket 流式预览"
                            transcriptText.isNotBlank() -> "已保存转写"
                            else -> "流式预览"
                        },
                        presetTemplates = configDataStore.loadPresetTemplates(),
                        reportTemplate = appConfig.reportTemplateConfig
                    )
                }
                recordingState.streamUpdate?.let(::updateStreamingPreview)
                synchronizeRecordingTimer(recordingState)
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
                        journeyStageCount = if (journey == null) 0 else state.journeyStageCount
                    )
                }
                journeyStageCollectionJob?.cancel()
                journeyStageCollectionJob = null
                if (journey == null) return@journeyUpdate

                val journeyId = journey.id
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
                    "${currentConfig.localEndpoint.trimEnd('/')}/cloud-asr"
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
        if (isStudyJourneyAwaitingNextStage(state)) {
            _uiState.update { it.copy(error = "请先开始下一段旅程，再录音或导入本段素材") }
            return
        }
        if (state.isRecordingActionPending || state.isJourneyActionPending) return
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
                if (isCurrentMeeting(requestedMeetingId)) startRecordingNow(requestedMeetingId)
            }
            return
        }
        if (active.isRecording || active.isStarting) {
            _uiState.update { it.copy(error = "已经在录音中") }
            return
        }

        startRecordingNow(meetingId)
    }

    private fun startRecordingNow(meetingId: String) {
        if (!isCurrentMeeting(meetingId)) return
        if (isStudyJourneyAwaitingNextStage(_uiState.value)) {
            _uiState.update { it.copy(error = "请先开始下一段旅程，再录音或导入本段素材") }
            return
        }
        val currentTranscript = SimplifiedChineseText.normalize(_uiState.value.liveTranscript)
        existingTranscriptText = currentTranscript
        latestStreamingText = ""
        committedStreamingText = ""
        previewStreamingText = ""
        preservedStreamingText = ""
        currentStreamingSessionId = ""
        startForegroundService(meetingId, _uiState.value.currentJourneyStage?.id)
        _uiState.update {
            it.copy(
                isRecording = false,
                isPaused = false,
                isRecordingActionPending = true,
                hasRecording = currentTranscript.isNotBlank(),
                liveTranscript = currentTranscript,
                isTranscribing = false,
                recordingDuration = 0,
                transcriptPreviewMode = "正在启动后台录音",
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
        val action = if (state.isPaused) {
            RecordingService.ACTION_RESUME
        } else {
            RecordingService.ACTION_PAUSE
        }
        appContext.startService(Intent(appContext, RecordingService::class.java).apply {
            this.action = action
            putExtra(RecordingService.EXTRA_MEETING_ID, meetingId)
        })
    }

    fun addRecordingMarker() {
        val state = _uiState.value
        if (!state.isRecording) return
        val timestamp = state.recordingDuration.coerceAtLeast(0L)
        _uiState.update {
            it.copy(
                recordingMarkers = (it.recordingMarkers + timestamp).distinct(),
                transcriptPreviewMode = "已标记 ${formatRecordingMarker(timestamp)}"
            )
        }
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
            !state.isTranscribing &&
            !state.isGeneratingReport

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
                    isRecordingActionPending = false
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
                isRecordingActionPending = false,
                isTranscribing = true,
                transcriptionProgressPercent = null,
                transcriptionProgressStage = "后台转写正在排队",
                transcriptionProgressIndeterminate = true,
                transcriptPreviewMode = "正在提交后台转写",
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
        viewModelScope.launch {
            configDataStore.updateReportTemplate(config)
            updateState { it.copy(reportTemplate = config) }
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
                    enqueueReportGeneration(meetingId)
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
            else -> enqueueReportGeneration(meetingId)
        }
    }

    fun consumeReportNavigation() {
        _uiState.update { it.copy(reportReadyToOpen = false) }
    }

    fun importImages(uris: List<Uri>, captureLocation: Boolean = false) {
        val meetingId = currentMeetingId
        if (meetingId.isBlank() || uris.isEmpty()) return
        if (isStudyJourneyAwaitingNextStage(_uiState.value)) {
            _uiState.update { it.copy(error = "请先开始下一段旅程，再导入本段图片") }
            return
        }
        val journeyStageId = _uiState.value.currentJourneyStage?.id
        viewModelScope.launch {
            attachmentStore.importImages(
                meetingId = meetingId,
                sources = uris,
                captureLocation = captureLocation,
                journeyStageId = journeyStageId
            ).forEach { result ->
                result.onFailure { error ->
                    if (isCurrentMeeting(meetingId)) {
                        _uiState.update { it.copy(error = "图片导入失败: ${error.message}") }
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
        transcriptionCollectionJob?.cancel()
        reportCollectionJob?.cancel()
        audioRefreshJob?.cancel()
        audioImportJob?.cancel()
        audioImportToken = null
        pendingExternalTextMeetingId = null
        recordingTimerJob?.cancel()
        super.onCleared()
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
                            transcripts.joinToString("\n") { it.content }
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
                                error = "后台转写失败: ${task.error ?: "未知错误"}"
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

    private fun startForegroundService(meetingId: String, journeyStageId: String?) {
        val intent = Intent(appContext, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_MEETING_ID, meetingId)
            putExtra(RecordingService.EXTRA_MEETING_TITLE, _uiState.value.meetingTitle)
            putExtra(RecordingService.EXTRA_JOURNEY_STAGE_ID, journeyStageId)
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
        private const val STUDY_JOURNEY_TEMPLATE_NAME = "研学考察"
    }
}

internal fun shouldRollStreamingSession(currentSessionId: String, incomingSessionId: String): Boolean =
    currentSessionId.isNotBlank() && incomingSessionId.isNotBlank() && currentSessionId != incomingSessionId

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
