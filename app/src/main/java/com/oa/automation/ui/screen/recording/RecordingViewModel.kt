package com.oa.automation.ui.screen.recording

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.application.usecase.StopRecordingUseCase
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.model.Transcript
import com.oa.automation.infrastructure.audio.AudioRecorder
import com.oa.automation.infrastructure.service.RecordingService
import com.oa.automation.infrastructure.stt.StreamingSttClient
import com.oa.automation.infrastructure.stt.StreamingTranscriptUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    val pendingBackendText: String = ""
)

enum class InputMode { VOICE, TEXT }
enum class TranscriptSource { STREAMING, BACKEND }

class RecordingViewModel(
    private val stopRecordingUseCase: StopRecordingUseCase,
    private val audioRecorder: AudioRecorder,
    private val configDataStore: ConfigDataStore,
    private val meetingRepository: MeetingRepository,
    private val streamingSttClient: StreamingSttClient,
    private val appScope: CoroutineScope,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(globalUiState)
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var currentMeetingId: String = globalMeetingId
    private var latestStreamingText: String = globalLatestStreamingText
    private var committedStreamingText: String = globalCommittedStreamingText
    private var previewStreamingText: String = globalPreviewStreamingText
    private var existingTranscriptText: String = globalExistingTranscriptText

    init {
        viewModelScope.launch {
            _uiState.collect { state -> globalUiState = state }
        }
    }

    private fun updateState(transform: (RecordingUiState) -> RecordingUiState) {
        globalUiState = transform(globalUiState)
        _uiState.value = transform(_uiState.value)
    }

    fun loadMeeting(meetingId: String) {
        currentMeetingId = meetingId
        globalMeetingId = meetingId

        viewModelScope.launch {
            val meeting = meetingRepository.findById(meetingId).getOrNull()
            val transcripts = meetingRepository.findTranscriptsByMeetingId(meetingId).getOrNull().orEmpty()
            val transcriptText = transcripts.joinToString("\n") { it.content }.trim()
            val appConfig = configDataStore.appConfigFlow.first()
            val sttConfig = appConfig.sttConfig
            val isGlobalRecording = audioRecorder.isRecording()
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

            if (isGlobalRecording) {
                resumeStreamingSession()
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
        viewModelScope.launch {
            try {
                // Check the actual AudioRecord instance, not the stale prefs flag.
                // After app crash/kill, prefs may still say "recording" but AudioRecord
                // instance is gone — we must re-initialize in that case.
                if (audioRecorder.isRecording() && audioRecorder.isAudioRecordReady()) {
                    _uiState.update { it.copy(error = "已经在录音中") }
                    return@launch
                }

                val currentTranscript = _uiState.value.liveTranscript.trim()
                existingTranscriptText = currentTranscript
                globalExistingTranscriptText = currentTranscript
                latestStreamingText = ""
                committedStreamingText = ""
                previewStreamingText = ""
                globalLatestStreamingText = ""
                globalCommittedStreamingText = ""
                globalPreviewStreamingText = ""

                audioRecorder.setOnChunkAvailableListener(null)
                audioRecorder.setOnPcmDataListener { pcmBytes, readBytes ->
                    android.util.Log.e("AUDIO", "pcmListener called bytes=$readBytes")
                    streamingSttClient.sendAudio(pcmBytes)
                }

                val endpoint = configDataStore.appConfigFlow.first().sttConfig.localEndpoint
                streamingSttClient.start(
                    endpoint = endpoint,
                    onPartialText = { updateStreamingPreview(it) },
                    onStatus = { message ->
                        _uiState.update { it.copy(transcriptPreviewMode = message) }
                    },
                    onError = { message ->
                        _uiState.update { it.copy(error = message) }
                    }
                )

                val result = audioRecorder.start()
                if (result != null) {
                    // Bind the live session to RecordingService so it survives Activity destruction
                    RecordingService.bindRecordingSession(audioRecorder, streamingSttClient)
                    startForegroundService()

                    _uiState.update {
                        it.copy(
                            isRecording = true,
                            hasRecording = currentTranscript.isNotBlank(),
                            liveTranscript = currentTranscript,
                            isTranscribing = false,
                            transcriptPreviewMode = "WebSocket 流式预览",
                            error = null
                        )
                    }
                } else {
                    audioRecorder.setOnPcmDataListener(null)
                    streamingSttClient.stop()
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            error = "无法启动录音，请检查麦克风权限"
                        )
                    }
                }
            } catch (e: Exception) {
                audioRecorder.setOnPcmDataListener(null)
                streamingSttClient.stop()
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        error = "录音启动失败: ${e.message}"
                    )
                }
            }
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTranscribing = true) }
            try {
                if (!audioRecorder.isRecording()) {
                    _uiState.update {
                        it.copy(
                            error = "没有在录音",
                            isTranscribing = false
                        )
                    }
                    return@launch
                }

                audioRecorder.setOnPcmDataListener(null)
                streamingSttClient.stop()
                val audioFile = audioRecorder.stop()
                stopForegroundService()
                val streamingPreviewAtStop = _uiState.value.liveTranscript.normalizePreviewText()

                if (audioFile == null || audioFile.length() == 0L) {
                    _uiState.update {
                        it.copy(
                            error = "录音文件不可用",
                            isTranscribing = false
                        )
                    }
                    return@launch
                }

                // 保存流式预览文本（用于用户选择），不与后台转写合并
                val savedStreamingText = streamingPreviewAtStop

                _uiState.update {
                    it.copy(
                        isRecording = false,
                        isTranscribing = false,
                        transcriptPreviewMode = "后台转写中",
                        error = "已停止录音，正在后台生成最终稿"
                    )
                }

                appScope.launch {
                    stopRecordingUseCase(currentMeetingId, audioFile)
                        .onSuccess { transcript ->
                            // 直接用后台转写结果，不再与流式预览合并
                            val finalText = existingTranscriptText
                                .normalizePreviewText()
                                .takeIf { it.isNotBlank() }
                                ?.let { mergeTranscriptText(it, transcript.content) }
                                ?: transcript.content

                            meetingRepository.saveTranscript(transcript.copy(content = finalText))
                            existingTranscriptText = finalText
                            globalExistingTranscriptText = finalText

                            // 显示选择器，让用户决定用哪个
                            _uiState.update {
                                it.copy(
                                    showTranscriptPicker = true,
                                    pendingStreamingText = savedStreamingText,
                                    pendingBackendText = finalText,
                                    isRecording = false,
                                    hasRecording = true,
                                    isTranscribing = false,
                                    liveTranscript = finalText,
                                    transcriptPreviewMode = "最终稿",
                                    error = null
                                )
                            }
                        }
                        .onFailure { error ->
                            _uiState.update {
                                it.copy(
                                    error = "后台转写失败: ${error.message}",
                                    isTranscribing = false
                                )
                            }
                        }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = "停止录音失败: ${e.message}",
                        isTranscribing = false
                    )
                }
            }
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
            )
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

    fun saveTextAsTranscript(onComplete: () -> Unit) {
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
                    _uiState.update { it.copy(hasRecording = true) }
                    onComplete()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "保存文本失败: ${e.message}") }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    private fun updateStreamingPreview(update: StreamingTranscriptUpdate) {
        val text = update.text.normalizePreviewText()
        if (text.isBlank() || text == latestStreamingText) return

        latestStreamingText = text
        committedStreamingText = update.committedText.normalizePreviewText()
        previewStreamingText = update.previewText.normalizePreviewText()
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
                transcriptPreviewMode = "WebSocket 流式预览"
            )
        }
    }

    private suspend fun resumeStreamingSession() {
        try {
            audioRecorder.setOnPcmDataListener(null)
            streamingSttClient.stop()
            val endpoint = configDataStore.appConfigFlow.first().sttConfig.localEndpoint
            audioRecorder.setOnPcmDataListener { pcmBytes, _ ->
                streamingSttClient.sendAudio(pcmBytes)
            }
            streamingSttClient.start(
                endpoint = endpoint,
                onPartialText = { updateStreamingPreview(it) },
                onStatus = { message ->
                    _uiState.update { it.copy(transcriptPreviewMode = message) }
                },
                onError = { message ->
                    _uiState.update { it.copy(error = message) }
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun startForegroundService() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra("meeting_title", _uiState.value.meetingTitle)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
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
