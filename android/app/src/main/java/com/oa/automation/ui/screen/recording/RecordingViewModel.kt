package com.oa.automation.ui.screen.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.model.Transcript
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.infrastructure.attachment.MeetingAttachmentStore
import com.oa.automation.infrastructure.background.BackgroundTaskScheduler
import com.oa.automation.infrastructure.background.BackgroundTaskState
import com.oa.automation.infrastructure.service.RecordingService
import com.oa.automation.infrastructure.service.RecordingSessionController
import com.oa.automation.infrastructure.stt.StreamingTranscriptUpdate
import com.oa.automation.locale.SimplifiedChineseText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class RecordingUiState(
    val meetingTitle: String = "",
    val sttEngineLabel: String = "",
    val isRecording: Boolean = false,
    val hasRecording: Boolean = false,
    val recordingDuration: Long = 0,
    val liveTranscript: String = "",
    val isTranscribing: Boolean = false,
    val isSavingTitle: Boolean = false,
    val transcriptPreviewMode: String = "流式预览",
    val presetTemplates: List<PresetReportTemplate> = emptyList(),
    val reportTemplate: ReportTemplateConfig = ReportTemplateConfig(),
    val error: String? = null,
    val hasPermission: Boolean = false,
    val inputMode: InputMode = InputMode.VOICE,
    val manualTextInput: String = "",
    // Transcript picker state
    val showTranscriptPicker: Boolean = false,
    val pendingStreamingText: String = "",
    val pendingBackendText: String = "",
    val attachments: List<MeetingAttachment> = emptyList(),
    val isGeneratingReport: Boolean = false,
    val reportReadyToOpen: Boolean = false
)

enum class InputMode { VOICE, TEXT }
enum class TranscriptSource { STREAMING, BACKEND }

class RecordingViewModel(
    private val configDataStore: ConfigDataStore,
    private val meetingRepository: MeetingRepository,
    private val attachmentStore: MeetingAttachmentStore,
    private val recordingController: RecordingSessionController,
    private val taskScheduler: BackgroundTaskScheduler,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    private val _uiState = MutableStateFlow(globalUiState)
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var currentMeetingId: String = globalMeetingId
    private var latestStreamingText: String = globalLatestStreamingText
    private var committedStreamingText: String = globalCommittedStreamingText
    private var previewStreamingText: String = globalPreviewStreamingText
    private var existingTranscriptText: String = globalExistingTranscriptText
    private var attachmentCollectionJob: Job? = null
    private var transcriptionCollectionJob: Job? = null
    private var reportCollectionJob: Job? = null
    private var trackedReportTaskId: java.util.UUID? = null

    init {
        viewModelScope.launch {
            _uiState.collect { state -> globalUiState = state }
        }
        viewModelScope.launch {
            recordingController.state.collect { session ->
                if (session.meetingId.isBlank() || session.meetingId != currentMeetingId) return@collect
                session.streamUpdate?.let(::updateStreamingPreview)
                _uiState.update {
                    it.copy(
                        isRecording = session.isRecording || session.isStarting,
                        transcriptPreviewMode = session.status,
                        error = session.error ?: it.error
                    )
                }
            }
        }
    }

    private fun updateState(transform: (RecordingUiState) -> RecordingUiState) {
        val nextState = transform(_uiState.value)
        globalUiState = nextState
        _uiState.value = nextState
    }

    fun loadMeeting(meetingId: String) {
        currentMeetingId = meetingId
        globalMeetingId = meetingId
        observeTranscriptionTask(meetingId)
        observeReportTask(meetingId)
        attachmentCollectionJob?.cancel()
        attachmentCollectionJob = viewModelScope.launch {
            attachmentStore.observe(meetingId).collect { attachments ->
                _uiState.update { it.copy(attachments = attachments) }
            }
        }

        viewModelScope.launch {
            val meeting = meetingRepository.findById(meetingId).getOrNull()
            val transcripts = meetingRepository.findTranscriptsByMeetingId(meetingId).getOrNull().orEmpty()
            val transcriptText = SimplifiedChineseText.normalize(
                transcripts.joinToString("\n") { it.content }
            )
            val appConfig = configDataStore.appConfigFlow.first()
            val sttConfig = appConfig.sttConfig
            val recordingState = recordingController.state.value
            val isGlobalRecording = recordingState.meetingId == meetingId &&
                (recordingState.isRecording || recordingState.isStarting)
            existingTranscriptText = transcriptText
            globalExistingTranscriptText = transcriptText

            if (meeting != null) {
                updateState {
                    it.copy(
                        meetingTitle = meeting.title,
                        sttEngineLabel = sttConfig.engineType.displayName,
                        isRecording = isGlobalRecording,
                        hasRecording = isGlobalRecording || transcriptText.isNotBlank(),
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
            }
        }
    }

    fun updatePermissionState(hasPermission: Boolean) {
        _uiState.update { it.copy(hasPermission = hasPermission) }
    }

    fun onMeetingTitleChange(title: String) {
        _uiState.update { it.copy(meetingTitle = title) }
    }

    fun saveMeetingTitle() {
        val newTitle = _uiState.value.meetingTitle.trim()
        if (currentMeetingId.isBlank()) return
        if (newTitle.isBlank()) {
            _uiState.update { it.copy(error = "会议名称不能为空") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingTitle = true, error = null) }
            meetingRepository.updateTitle(currentMeetingId, newTitle)
                .onSuccess { meeting ->
                    _uiState.update {
                        it.copy(
                            meetingTitle = meeting.title,
                            isSavingTitle = false
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            error = "保存会议名称失败: ${error.message}",
                            isSavingTitle = false
                        )
                    }
                }
        }
    }

    fun startRecording() {
        if (currentMeetingId.isBlank()) {
            _uiState.update { it.copy(error = "会议标识缺失") }
            return
        }
        val active = recordingController.state.value
        if (active.isRecording || active.isStarting) {
            _uiState.update { it.copy(error = "已经在录音中") }
            return
        }

        val currentTranscript = SimplifiedChineseText.normalize(_uiState.value.liveTranscript)
        existingTranscriptText = currentTranscript
        globalExistingTranscriptText = currentTranscript
        latestStreamingText = ""
        committedStreamingText = ""
        previewStreamingText = ""
        globalLatestStreamingText = ""
        globalCommittedStreamingText = ""
        globalPreviewStreamingText = ""
        startForegroundService()
        _uiState.update {
            it.copy(
                isRecording = true,
                hasRecording = currentTranscript.isNotBlank(),
                liveTranscript = currentTranscript,
                isTranscribing = false,
                transcriptPreviewMode = "正在启动后台录音",
                error = null
            )
        }
    }

    fun stopRecording() {
        val active = recordingController.state.value
        if (!active.isRecording && !active.isStarting) {
            _uiState.update { it.copy(error = "没有在录音", isTranscribing = false) }
            return
        }
        stopForegroundService()
        _uiState.update {
            it.copy(
                isRecording = false,
                isTranscribing = true,
                transcriptPreviewMode = "正在提交后台转写",
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
                    meetingId = currentMeetingId,
                    content = finalText
                )
            ).onSuccess {
                existingTranscriptText = finalText
                globalExistingTranscriptText = finalText
                updateState {
                    it.copy(
                        liveTranscript = finalText,
                        showTranscriptPicker = false,
                        pendingStreamingText = "",
                        pendingBackendText = ""
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(error = "保存转写失败: ${error.message}") }
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
        // Keep the foreground service running — do NOT stop it here.
        // The recording continues in the background even after the Activity is destroyed.
        _uiState.update { it.copy(error = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
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

    fun switchToTextMode() {
        _uiState.update { it.copy(inputMode = InputMode.TEXT) }
    }

    fun updateManualText(text: String) {
        _uiState.update { it.copy(manualTextInput = text) }
    }

    fun saveTextAndGenerateReport() {
        val text = _uiState.value.manualTextInput.trim()
        if (text.isBlank() || currentMeetingId.isBlank()) return

        viewModelScope.launch {
            val transcript = Transcript(
                id = java.util.UUID.randomUUID().toString(),
                meetingId = currentMeetingId,
                content = text
            )
            meetingRepository.saveTranscript(transcript)
                .onSuccess {
                    existingTranscriptText = text
                    globalExistingTranscriptText = text
                    _uiState.update { it.copy(hasRecording = true, liveTranscript = text) }
                    enqueueReportGeneration()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "保存文本失败: ${e.message}") }
                }
        }
    }

    fun generateReport() {
        val state = _uiState.value
        when {
            currentMeetingId.isBlank() -> _uiState.update { it.copy(error = "会议标识缺失") }
            state.isRecording -> _uiState.update { it.copy(error = "请先停止录音") }
            state.isTranscribing -> _uiState.update { it.copy(error = "请等待后台转写完成") }
            !state.hasRecording || state.liveTranscript.isBlank() -> {
                _uiState.update { it.copy(error = "请先录音或输入会议内容") }
            }
            state.isGeneratingReport -> Unit
            else -> enqueueReportGeneration()
        }
    }

    fun consumeReportNavigation() {
        _uiState.update { it.copy(reportReadyToOpen = false) }
    }

    fun importImages(uris: List<Uri>) {
        if (currentMeetingId.isBlank() || uris.isEmpty()) return
        viewModelScope.launch {
            uris.forEach { uri ->
                attachmentStore.importImage(currentMeetingId, uri)
                    .onFailure { error ->
                        _uiState.update { it.copy(error = "图片导入失败: ${error.message}") }
                    }
            }
        }
    }

    fun deleteAttachment(attachment: MeetingAttachment) {
        viewModelScope.launch {
            attachmentStore.delete(attachment).onFailure { error ->
                _uiState.update { it.copy(error = "图片删除失败: ${error.message}") }
            }
        }
    }

    override fun onCleared() {
        attachmentCollectionJob?.cancel()
        transcriptionCollectionJob?.cancel()
        reportCollectionJob?.cancel()
        super.onCleared()
    }

    private fun enqueueReportGeneration() {
        val taskId = taskScheduler.enqueueReport(currentMeetingId)
        trackedReportTaskId = taskId
        _uiState.update {
            it.copy(isGeneratingReport = true, reportReadyToOpen = false, error = null)
        }
    }

    private fun observeReportTask(meetingId: String) {
        reportCollectionJob?.cancel()
        reportCollectionJob = viewModelScope.launch {
            taskScheduler.observeReport(meetingId).collect { task ->
                when (task.state) {
                    BackgroundTaskState.QUEUED, BackgroundTaskState.RUNNING -> {
                        trackedReportTaskId = task.id
                        _uiState.update {
                            it.copy(isGeneratingReport = true, reportReadyToOpen = false, error = null)
                        }
                    }

                    BackgroundTaskState.SUCCEEDED -> {
                        if (trackedReportTaskId == task.id) {
                            trackedReportTaskId = null
                            _uiState.update {
                                it.copy(isGeneratingReport = false, reportReadyToOpen = true, error = null)
                            }
                        }
                    }

                    BackgroundTaskState.FAILED -> {
                        if (trackedReportTaskId == task.id) {
                            trackedReportTaskId = null
                            _uiState.update {
                                it.copy(
                                    isGeneratingReport = false,
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
                                    reportReadyToOpen = false,
                                    error = "会议纪要生成任务已取消"
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
                when (task.state) {
                    BackgroundTaskState.QUEUED, BackgroundTaskState.RUNNING -> {
                        _uiState.update {
                            it.copy(
                                isRecording = false,
                                isTranscribing = true,
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
                        val finalText = SimplifiedChineseText.normalize(
                            transcripts.joinToString("\n") { it.content }
                        )
                        existingTranscriptText = finalText
                        globalExistingTranscriptText = finalText
                        _uiState.update {
                            it.copy(
                                isRecording = false,
                                hasRecording = finalText.isNotBlank(),
                                isTranscribing = false,
                                liveTranscript = finalText,
                                transcriptPreviewMode = "最终稿",
                                error = null
                            )
                        }
                    }

                    BackgroundTaskState.FAILED -> _uiState.update {
                        it.copy(
                            isTranscribing = false,
                            transcriptPreviewMode = "后台转写失败",
                            error = "后台转写失败: ${task.error ?: "未知错误"}"
                        )
                    }

                    BackgroundTaskState.CANCELLED -> _uiState.update {
                        it.copy(isTranscribing = false, transcriptPreviewMode = "后台转写已取消")
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

        latestStreamingText = text
        committedStreamingText = committedText
        previewStreamingText = previewText
        globalLatestStreamingText = latestStreamingText
        globalCommittedStreamingText = committedStreamingText
        globalPreviewStreamingText = previewStreamingText

        val displayText = buildString {
            if (existingTranscriptText.isNotBlank()) append(existingTranscriptText)
            if (committedStreamingText.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(committedStreamingText)
            }
            if (previewStreamingText.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(previewStreamingText)
            }
            if (isEmpty()) append(text)
        }

        _uiState.update {
            it.copy(
                liveTranscript = displayText,
                transcriptPreviewMode = "实时预览（可修订）"
            )
        }
    }

    private fun startForegroundService() {
        val intent = Intent(appContext, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_MEETING_ID, currentMeetingId)
            putExtra(RecordingService.EXTRA_MEETING_TITLE, _uiState.value.meetingTitle)
        }
        appContext.startForegroundService(intent)
    }

    private fun stopForegroundService() {
        val intent = Intent(appContext, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        appContext.startService(intent)
    }

    companion object {
        private var globalUiState: RecordingUiState = RecordingUiState()
        private var globalMeetingId: String = ""
        private var globalLatestStreamingText: String = ""
        private var globalCommittedStreamingText: String = ""
        private var globalPreviewStreamingText: String = ""
        private var globalExistingTranscriptText: String = ""
    }
}

private fun String.normalizePreviewText(): String =
    replace(Regex("<[^>\\r\\n]{0,120}>"), " ")
        .replace(Regex("\\s+"), " ")
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

private fun mergeTranscriptText(existing: String, update: String): String {
    val base = existing.normalizePreviewText()
    val next = update.normalizePreviewText()
    if (base.isBlank()) return next
    if (next.isBlank() || base.contains(next)) return base
    if (next.contains(base)) return next

    val maxOverlap = minOf(base.length, next.length)
    for (overlap in maxOverlap downTo 4) {
        if (base.takeLast(overlap) == next.take(overlap)) {
            return (base + next.drop(overlap)).normalizePreviewText()
        }
    }

    return dedupeTranscriptLines("$base\n$next")
}

private fun dedupeTranscriptLines(text: String): String {
    // Split into sentences by Chinese sentence-end punctuation (。！？) + optional whitespace/newline
    // Do NOT split by individual punctuation — that creates fragments too short for similarity comparison
    val rawSentences = text.split(Regex("([。！？])\\s*"))
    val seen = LinkedHashSet<String>()
    return rawSentences
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filter { sentence ->
            val normalized = sentence.normalizeForDedupe()
            // Skip if a previously seen sentence already covers this one
            val dominated = seen.any { existing ->
                existing.normalizeForDedupe().contains(normalized) ||
                    normalized.contains(existing.normalizeForDedupe())
            }
            if (!dominated) seen.add(sentence)
            !dominated
        }
        .joinToString("。\n")
        .trim()
}

private fun String.normalizeForDedupe(): String =
    replace(Regex("<[^>\\r\\n]{0,120}>"), "")
        .replace(Regex("\\s+"), "")
        .trim()
