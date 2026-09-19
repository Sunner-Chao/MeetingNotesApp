package com.oa.automation.ui.screen.report

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ForumParticipant
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.ReportWorkspaceBlocks
import com.oa.automation.domain.model.normalizeReportWorkspaceOrder
import com.oa.automation.domain.model.normalizeHiddenReportWorkspaceBlocks
import com.oa.automation.domain.model.ReportTitleResolver
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.model.Transcript
import com.oa.automation.domain.model.extractForumParticipants
import com.oa.automation.domain.model.isForumMeetingTemplate
import com.oa.automation.domain.model.canonicalMeetingTranscripts
import com.oa.automation.domain.model.renderedContent
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.JourneyRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.infrastructure.llm.ChatMessage
import com.oa.automation.infrastructure.llm.LLMEngine
import com.oa.automation.infrastructure.llm.ReportPromptTemplates
import com.oa.automation.infrastructure.attachment.MeetingAttachmentStore
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudio
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudioPlaybackSource
import com.oa.automation.infrastructure.audio.MeetingAudioArchiveService
import com.oa.automation.infrastructure.audio.PreparedMeetingAudioShare
import com.oa.automation.infrastructure.background.BackgroundTaskScheduler
import com.oa.automation.infrastructure.background.BackgroundTaskState
import com.oa.automation.locale.SimplifiedChineseText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.time.Instant

data class ChatMessageUi(
    val id: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ReportUiState(
    val report: Report? = null,
    val meetingTitle: String = "",
    val meetingCreatedAt: Long = 0L,
    val meetingDurationMs: Long = 0L,
    val journeyStartedAt: Long = 0L,
    val journeyEndedAt: Long? = null,
    val initiatorName: String = "",
    val initiatorAvatarDataUrl: String? = null,
    val forumParticipants: List<ForumParticipant> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isGenerating: Boolean = false,
    val generationProgressPercent: Int? = null,
    val generationProgressStage: String = "",
    val generationProgressIndeterminate: Boolean = false,
    val generationCancelled: Boolean = false,
    // Chat state
    val chatMessages: List<ChatMessageUi> = emptyList(),
    val chatInput: String = "",
    val isChatLoading: Boolean = false,
    val chatError: String? = null,
    // Transcript state
    val transcriptText: String = "",
    val showTranscript: Boolean = false,
    // Template state
    val presetTemplates: List<PresetReportTemplate> = emptyList(),
    val reportTemplate: ReportTemplateConfig = ReportTemplateConfig(),
    // UI state
    val message: String? = null,
    val hasUnsavedChanges: Boolean = false,
    val attachments: List<MeetingAttachment> = emptyList(),
    val journeyStages: List<JourneyStage> = emptyList(),
    val journeyStageTranscripts: Map<String, String> = emptyMap(),
    val archivedAudio: List<ArchivedMeetingAudio> = emptyList(),
    val isLoadingAudio: Boolean = false,
    val preparingAudioShareId: String? = null,
    val deletingAudioId: String? = null,
    val pendingAudioShare: PreparedMeetingAudioShare? = null
) {
    val canUseReport: Boolean get() = report != null && !isLoading && !isGenerating &&
        error == null && !generationCancelled &&
        (reportTemplate.selectedName.isBlank() || report.templateName == reportTemplate.selectedName)
}

internal fun journeyStageTranscriptMap(transcripts: List<Transcript>): Map<String, String> =
    transcripts
        .filter { !it.journeyStageId.isNullOrBlank() }
        .groupBy { it.journeyStageId.orEmpty() }
        .mapValues { (_, stageTranscripts) ->
            SimplifiedChineseText.normalize(
                stageTranscripts
                    .sortedWith(compareBy<Transcript> { it.startTimeMs }.thenBy { it.createdAt })
                    .joinToString("\n") { it.renderedContent() }
            )
        }

class ReportViewModel(
    private val taskScheduler: BackgroundTaskScheduler,
    private val reportRepository: ReportRepository,
    private val meetingRepository: MeetingRepository,
    private val llmEngine: LLMEngine,
    private val configDataStore: ConfigDataStore,
    private val attachmentStore: MeetingAttachmentStore,
    private val audioArchiveService: MeetingAudioArchiveService,
    private val journeyRepository: JourneyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()
    private var attachmentCollectionJob: Job? = null
    private var reportCollectionJob: Job? = null
    private var reportTaskCollectionJob: Job? = null
    private var forumSpeakerNames: List<String> = emptyList()
    private var loadJob: Job? = null
    private var generationLaunchJob: Job? = null
    private var currentMeetingId: String? = null

    fun loadReport(meetingId: String) {
        currentMeetingId = meetingId
        loadJob?.cancel()
        reportCollectionJob?.cancel()
        reportTaskCollectionJob?.cancel()
        _uiState.update { it.copy(report = null, isLoading = true, error = null) }
        refreshArchivedAudio(meetingId)
        attachmentCollectionJob?.cancel()
        attachmentCollectionJob = viewModelScope.launch {
            attachmentStore.observe(meetingId).collect { attachments ->
                _uiState.update { it.copy(attachments = attachments) }
            }
        }
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                generationCancelled = false
            )

            val meeting = meetingRepository.findById(meetingId).getOrNull()
            val journey = journeyRepository.findByMeetingId(meetingId).getOrNull()
            val journeyStages = journey?.let { journeyRepository.observeStages(it.id).first() }.orEmpty()
            val journeyEndedAt = journey?.completedAt
                ?: journeyStages.maxOfOrNull { it.savedAt ?: it.updatedAt }
            val session = configDataStore.authSessionFlow.first()
            _uiState.update {
                it.copy(
                    meetingTitle = meeting?.title.orEmpty(),
                    meetingCreatedAt = meeting?.createdAt ?: 0L,
                    meetingDurationMs = meeting?.durationMs ?: 0L,
                    journeyStartedAt = journey?.createdAt ?: 0L,
                    journeyEndedAt = journeyEndedAt?.takeIf { it > (journey?.createdAt ?: 0L) },
                    journeyStages = journeyStages,
                    initiatorName = session?.user?.displayName.orEmpty()
                        .ifBlank { session?.user?.username.orEmpty() },
                    initiatorAvatarDataUrl = session?.user?.avatarDataUrl
                )
            }

            // 加载模板配置
            val templates = configDataStore.loadPresetTemplates()
            val appConfig = configDataStore.appConfigFlow.first()
            val existingReport = reportRepository.findByMeetingId(meetingId).getOrThrow()
            val selectedName = meeting?.selectedTemplateName?.takeIf(String::isNotBlank)
                ?: existingReport?.templateName?.takeIf(String::isNotBlank)
                ?: appConfig.reportTemplateConfig.selectedName
            val meetingTemplate = templates.firstOrNull { it.name == selectedName }
            _uiState.value = _uiState.value.copy(
                presetTemplates = templates,
                reportTemplate = if (meetingTemplate != null &&
                    meetingTemplate.name != appConfig.reportTemplateConfig.selectedName
                ) {
                    ReportTemplateConfig(selectedName = meetingTemplate.name, content = meetingTemplate.content)
                } else appConfig.reportTemplateConfig.copy(selectedName = selectedName)
            )

            // 加载转写文本
            val transcripts = meetingRepository.findTranscriptsByMeetingId(meetingId).getOrNull().orEmpty()
            forumSpeakerNames = transcripts.mapNotNull { it.speakerName }
            val transcriptText = SimplifiedChineseText.normalize(
                transcripts.canonicalMeetingTranscripts().joinToString("\n") { it.renderedContent() }
            )
            _uiState.value = _uiState.value.copy(
                transcriptText = transcriptText,
                journeyStageTranscripts = journeyStageTranscriptMap(transcripts),
                forumParticipants = resolveForumParticipants(null)
            )

            val generation = reportRepository.findGeneration(meetingId)
            if (generation != null) {
                if (generation.cancelled) {
                    _uiState.update { it.copy(report = null, isLoading = false, isGenerating = false, generationCancelled = true) }
                } else if (generation.completed && existingReport != null) {
                    // WorkManager may prune completed jobs; the committed result remains valid.
                    showCompletedReport(meetingId, existingReport)
                } else {
                    observeReportTask(meetingId, java.util.UUID.fromString(generation.requestId))
                }
            } else if (existingReport != null &&
                (selectedName.isBlank() || existingReport.templateName == selectedName)) {
                showCompletedReport(meetingId, existingReport)
            } else {
                regenerateWithTemplate(meetingId)
            }
            observeSavedReport(meetingId)
        }
    }

    fun regenerateReport(meetingId: String) = regenerateWithTemplate(meetingId)

    fun selectReportTemplate(meetingId: String, template: PresetReportTemplate) {
        if (generationLaunchJob?.isActive == true) return
        if (template.name == _uiState.value.reportTemplate.selectedName) return
        val config = ReportTemplateConfig(selectedName = template.name, content = template.content, isCustom = false)
        _uiState.update { it.copy(reportTemplate = config) }
        regenerateWithTemplate(meetingId)
    }

    fun regenerateWithTemplate(meetingId: String) {
        if (generationLaunchJob?.isActive == true) return
        val selectedName = _uiState.value.reportTemplate.selectedName
        reportTaskCollectionJob?.cancel()
        _uiState.update {
            it.copy(
                report = null,
                forumParticipants = emptyList(),
                isLoading = true,
                isGenerating = true,
                chatMessages = emptyList(),
                chatInput = "",
                generationProgressPercent = null,
                generationProgressStage = "会议纪要正在排队",
                generationProgressIndeterminate = true,
                generationCancelled = false,
                error = null,
                message = null
            )
        }
        generationLaunchJob = viewModelScope.launch {
            runCatching {
                taskScheduler.enqueueReport(meetingId, replaceRunning = true, templateName = selectedName)
            }.onSuccess { requestId ->
                observeReportTask(meetingId, requestId)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update { it.copy(report = null, isLoading = false, isGenerating = false, error = error.message ?: "纪要生成未能启动") }
            }
        }
    }

    fun cancelGeneration(meetingId: String) {
        reportTaskCollectionJob?.cancel()
        generationLaunchJob?.cancel()
        viewModelScope.launch {
            reportRepository.cancelGeneration(meetingId)
            taskScheduler.cancelReport(meetingId)
        }
        _uiState.update {
            it.copy(
                report = null,
                isLoading = false,
                isGenerating = false,
                generationProgressPercent = null,
                generationProgressStage = "",
                generationProgressIndeterminate = false,
                generationCancelled = true,
                error = null,
                message = "纪要生成已终止"
            )
        }
    }

    private fun observeReportTask(meetingId: String, requestId: java.util.UUID? = null) {
        reportTaskCollectionJob?.cancel()
        reportTaskCollectionJob = viewModelScope.launch {
            val tasks = if (requestId != null) taskScheduler.observeReportRequest(requestId)
                else taskScheduler.observeReport(meetingId)
            tasks.collect { task ->
                if (currentMeetingId != meetingId) return@collect
                when (task.state) {
                    BackgroundTaskState.QUEUED, BackgroundTaskState.RUNNING -> _uiState.update {
                        it.copy(
                            report = null,
                            forumParticipants = emptyList(),
                            isGenerating = true,
                            isLoading = true,
                            generationProgressPercent = task.progressPercent,
                            generationProgressStage = task.progressStage.ifBlank {
                                if (task.state == BackgroundTaskState.QUEUED) {
                                    "会议纪要正在排队"
                                } else {
                                    "会议纪要处理中"
                                }
                            },
                            generationProgressIndeterminate = task.progressIndeterminate ||
                                task.progressPercent == null,
                            generationCancelled = false,
                            error = null
                        )
                    }

                    BackgroundTaskState.SUCCEEDED -> {
                        val report = reportRepository.findByMeetingId(meetingId).getOrNull()
                        val generation = reportRepository.findGeneration(meetingId)
                        val valid = report != null && report.templateName == _uiState.value.reportTemplate.selectedName &&
                            (generation == null || (generation.completed && !generation.cancelled && generation.requestId == task.id.toString()))
                        if (valid) {
                            showCompletedReport(meetingId, checkNotNull(report))
                        } else {
                            _uiState.update { it.copy(report = null, isLoading = false, isGenerating = false,
                                error = "当前模板尚无可用纪要，请重新生成") }
                        }
                    }

                    BackgroundTaskState.FAILED -> _uiState.update {
                        it.copy(
                            report = null,
                            isLoading = false,
                            isGenerating = false,
                            generationProgressPercent = null,
                            generationProgressStage = "",
                            generationProgressIndeterminate = false,
                            generationCancelled = false,
                            error = "生成报告失败：${task.error ?: "Agent 服务请求失败"}"
                        )
                    }

                    BackgroundTaskState.CANCELLED -> _uiState.update {
                        it.copy(
                            report = null,
                            isLoading = false,
                            isGenerating = false,
                            generationProgressPercent = null,
                            generationProgressStage = "",
                            generationProgressIndeterminate = false,
                            generationCancelled = true,
                            error = null,
                            message = "纪要生成已终止"
                        )
                    }

                    BackgroundTaskState.NONE -> if (requestId != null) {
                        _uiState.update { it.copy(report = null, isLoading = false, isGenerating = false,
                            error = "纪要任务未完成，请重新生成") }
                    }
                }
            }
        }
    }

    private fun resolveForumParticipants(report: Report?): List<ForumParticipant> {
        val state = _uiState.value
        val templateName = report?.templateName
            .orEmpty()
            .ifBlank { state.reportTemplate.selectedName }
        if (!templateName.isForumMeetingTemplate()) return emptyList()

        val extracted = report?.participants.orEmpty().ifEmpty {
            extractForumParticipants(
                rawContent = report?.rawContent.orEmpty(),
                speakerNames = forumSpeakerNames
            )
        }
        val enriched = extracted.map { participant ->
            if (participant.name.equals(state.initiatorName, ignoreCase = true)) {
                participant.copy(
                    avatarDataUrl = state.initiatorAvatarDataUrl,
                    photoAuthorized = !state.initiatorAvatarDataUrl.isNullOrBlank()
                )
            } else {
                participant
            }
        }.toMutableList()
        if (state.initiatorName.isNotBlank() && enriched.none {
                it.name.equals(state.initiatorName, ignoreCase = true)
            }) {
            enriched += ForumParticipant(
                name = state.initiatorName,
                role = "记录者",
                avatarDataUrl = state.initiatorAvatarDataUrl,
                photoAuthorized = !state.initiatorAvatarDataUrl.isNullOrBlank()
            )
        }
        return enriched
    }

    // Chat functions
    fun updateChatInput(input: String) {
        _uiState.value = _uiState.value.copy(chatInput = input)
    }

    fun sendMessage() {
        val input = _uiState.value.chatInput.trim()
        if (input.isEmpty() || _uiState.value.isChatLoading) return

        val report = _uiState.value.report ?: return

        viewModelScope.launch {
            // Add user message
            val userMessage = ChatMessageUi(
                id = java.util.UUID.randomUUID().toString(),
                role = "user",
                content = input
            )

            val currentMessages = _uiState.value.chatMessages + userMessage
            _uiState.value = _uiState.value.copy(
                chatMessages = currentMessages,
                chatInput = "",
                isChatLoading = true,
                chatError = null
            )

            // Build chat context with report content
            val reportContext = buildReportContext(report)
            val chatHistory = buildList {
                add(ChatMessage("system", ReportPromptTemplates.REFINEMENT_SYSTEM_PROMPT))
                add(ChatMessage("user", "当前会议纪要内容：\n\n$reportContext"))
                // Add previous chat messages
                currentMessages.forEach { msg ->
                    if (msg.id != userMessage.id) {
                        add(ChatMessage(msg.role, msg.content))
                    }
                }
                add(ChatMessage("user", input))
            }

            llmEngine.chat(chatHistory, attachmentStore.toAgentAttachments(_uiState.value.attachments))
                .onSuccess { response ->
                    val assistantMessage = ChatMessageUi(
                        id = java.util.UUID.randomUUID().toString(),
                        role = "assistant",
                        content = response
                    )
                    _uiState.value = _uiState.value.copy(
                        chatMessages = currentMessages + assistantMessage,
                        isChatLoading = false
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isChatLoading = false,
                        chatError = "发送失败: ${error.message}"
                    )
                }
        }
    }

    fun clearChat() {
        _uiState.value = _uiState.value.copy(
            chatMessages = emptyList(),
            chatError = null
        )
    }

    fun importImages(
        meetingId: String,
        uris: List<Uri>,
        captureLocation: Boolean = false
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            attachmentStore.importImages(meetingId, uris, captureLocation).forEach { result ->
                result.onFailure { error ->
                    _uiState.update { it.copy(message = "图片导入失败: ${error.message}") }
                }
            }
        }
    }

    fun deleteAttachment(attachment: MeetingAttachment) {
        viewModelScope.launch {
            attachmentStore.delete(attachment).onFailure { error ->
                _uiState.update { it.copy(message = "图片删除失败: ${error.message}") }
            }
        }
    }

    suspend fun attachmentsForExport(meetingId: String): List<MeetingAttachment> {
        return attachmentStore.observe(meetingId).first()
    }

    fun refreshArchivedAudio(meetingId: String) {
        if (meetingId.isBlank()) return
        viewModelScope.launch {
            val localAudio = localMeetingAudio(meetingId)
            _uiState.update {
                it.copy(
                    archivedAudio = localAudio.ifEmpty { it.archivedAudio },
                    isLoadingAudio = true
                )
            }
            audioArchiveService.list(meetingId)
                .onSuccess { items ->
                    val visibleItems = items.ifEmpty { localAudio }
                    val archivedDurationMs = visibleItems.firstOrNull()?.durationSec
                        ?.takeIf { it.isFinite() && it > 0.0 }
                        ?.times(1_000.0)
                        ?.toLong()
                    _uiState.update {
                        it.copy(
                            archivedAudio = visibleItems,
                            meetingDurationMs = archivedDurationMs ?: it.meetingDurationMs,
                            isLoadingAudio = false
                        )
                    }
                    if (archivedDurationMs != null) {
                        meetingRepository.findById(meetingId).getOrNull()?.let { meeting ->
                            if (meeting.durationMs != archivedDurationMs) {
                                meetingRepository.save(meeting.copy(durationMs = archivedDurationMs))
                            }
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            archivedAudio = localAudio,
                            isLoadingAudio = false,
                            message = if (localAudio.isNotEmpty()) {
                                "服务器归档暂不可用，已切换为本机录音"
                            } else {
                                "会议音频加载失败: ${error.message}"
                            }
                        )
                    }
                }
        }
    }

    private suspend fun localMeetingAudio(meetingId: String): List<ArchivedMeetingAudio> {
        val meeting = meetingRepository.findById(meetingId).getOrNull() ?: return emptyList()
        val file = meeting.audioFilePath?.let(::File)
            ?.takeIf { it.isFile && it.length() > 44L }
            ?: return emptyList()
        return listOf(
            ArchivedMeetingAudio(
                id = "local-$meetingId-${file.lastModified()}",
                meetingId = meetingId,
                createdAt = Instant.ofEpochMilli(file.lastModified()).toString(),
                bytes = file.length(),
                durationSec = meeting.durationMs.takeIf { it > 0L }?.div(1_000.0),
                filename = file.name,
                source = "本机录音",
                downloadPath = "",
                localFilePath = file.absolutePath
            )
        )
    }

    suspend fun prepareArchivedAudioPlayback(
        audio: ArchivedMeetingAudio
    ): Result<ArchivedMeetingAudioPlaybackSource> = audioArchiveService.preparePlayback(audio)

    fun shareArchivedAudio(audio: ArchivedMeetingAudio) {
        val state = _uiState.value
        if (state.preparingAudioShareId != null || state.deletingAudioId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(preparingAudioShareId = audio.id) }
            audioArchiveService.prepareShare(audio, _uiState.value.meetingTitle)
                .onSuccess { prepared ->
                    _uiState.update {
                        it.copy(
                            preparingAudioShareId = null,
                            pendingAudioShare = prepared
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(preparingAudioShareId = null, message = "会议音频分享失败: ${error.message}")
                    }
                }
        }
    }

    fun deleteArchivedAudio(audio: ArchivedMeetingAudio) {
        val state = _uiState.value
        if (state.preparingAudioShareId != null || state.deletingAudioId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(deletingAudioId = audio.id) }
            audioArchiveService.delete(audio)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            archivedAudio = it.archivedAudio.filterNot { item -> item.id == audio.id },
                            deletingAudioId = null,
                            message = "会议音频已删除"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            deletingAudioId = null,
                            message = "会议音频删除失败: ${error.message}"
                        )
                    }
                }
        }
    }

    fun consumeAudioShare() {
        _uiState.update { it.copy(pendingAudioShare = null) }
    }

    // 保存报告到数据库
    fun saveReport() {
        val report = _uiState.value.report ?: return
        viewModelScope.launch {
            reportRepository.save(report)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        message = "报告已保存",
                        hasUnsavedChanges = false
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        message = "保存失败: ${error.message}"
                    )
                }
        }
    }

    fun updateWorkspaceBlockOrder(order: List<String>) {
        val report = _uiState.value.report ?: return
        val normalized = normalizeReportWorkspaceOrder(order)
        if (normalized.isEmpty()) return
        val updated = report.copy(workspaceBlockOrder = normalized)
        _uiState.update { it.copy(report = updated, hasUnsavedChanges = true) }
        viewModelScope.launch {
            reportRepository.save(updated).onFailure { error ->
                _uiState.update { it.copy(message = "纪要布局保存失败: ${error.message}") }
            }
        }
    }

    fun hideWorkspaceBlock(blockId: String) {
        val report = _uiState.value.report ?: return
        if (blockId == ReportWorkspaceBlocks.REPORT) return
        val hidden = normalizeHiddenReportWorkspaceBlocks(
            report.hiddenWorkspaceBlocks + blockId
        )
        if (hidden == report.hiddenWorkspaceBlocks) return
        persistWorkspaceLayout(report.copy(hiddenWorkspaceBlocks = hidden))
    }

    fun restoreWorkspaceLayout() {
        val report = _uiState.value.report ?: return
        if (report.workspaceBlockOrder.isEmpty() && report.hiddenWorkspaceBlocks.isEmpty()) return
        persistWorkspaceLayout(
            report.copy(
                workspaceBlockOrder = emptyList(),
                hiddenWorkspaceBlocks = emptyList()
            )
        )
    }

    private fun persistWorkspaceLayout(updated: Report) {
        _uiState.update { it.copy(report = updated, hasUnsavedChanges = true) }
        viewModelScope.launch {
            reportRepository.save(updated).onFailure { error ->
                _uiState.update { it.copy(message = "纪要布局保存失败: ${error.message}") }
            }
        }
    }

    private suspend fun showCompletedReport(meetingId: String, report: Report) {
        val title = resolveAndPersistReportTitle(meetingId, report)
        val saved = reportRepository.findByMeetingId(meetingId).getOrNull()
        currentCoroutineContext().ensureActive()
        if (currentMeetingId != meetingId || saved != report ||
            report.templateName != _uiState.value.reportTemplate.selectedName) return
        _uiState.update { it.copy(report = report, meetingTitle = title,
            forumParticipants = resolveForumParticipants(report), isLoading = false, isGenerating = false,
            generationProgressPercent = null, generationProgressStage = "", generationProgressIndeterminate = false,
            generationCancelled = false, error = null) }
    }

    private fun observeSavedReport(meetingId: String) {
        reportCollectionJob?.cancel()
        reportCollectionJob = viewModelScope.launch {
            reportRepository.getAllReportsFlow().collect {
                val current = _uiState.value
                if (!current.canUseReport) return@collect
                val saved = reportRepository.findByMeetingId(meetingId).getOrNull()
                if (currentMeetingId != meetingId || _uiState.value.report != current.report) return@collect
                if (saved == null) {
                    _uiState.update { it.copy(report = null) }
                } else if (saved.id == current.report?.id && saved.generatedAt == current.report.generatedAt &&
                    saved.templateName == current.reportTemplate.selectedName) {
                    // Layout edits may refresh a completed report, never a running task.
                    _uiState.update { it.copy(report = saved) }
                }
            }
        }
    }

    suspend fun isCurrentReport(report: Report): Boolean {
        val state = _uiState.value
        if (!state.canUseReport || state.report != report) return false
        val saved = reportRepository.findByMeetingId(report.meetingId).getOrNull()
        return currentMeetingId == report.meetingId && saved == report &&
            _uiState.value.canUseReport && _uiState.value.report == report
    }

    // 删除报告
    fun deleteReport(meetingId: String) {
        reportTaskCollectionJob?.cancel()
        generationLaunchJob?.cancel()
        _uiState.update { it.copy(report = null, isLoading = false, isGenerating = false) }
        viewModelScope.launch {
            taskScheduler.cancelReport(meetingId)
            reportRepository.deleteByMeetingId(meetingId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        report = null,
                        message = "报告已删除",
                        hasUnsavedChanges = false
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        message = "删除失败: ${error.message}"
                    )
                }
        }
    }

    // 清除消息
    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    // 标记有未保存的更改
    fun markUnsavedChanges() {
        _uiState.value = _uiState.value.copy(hasUnsavedChanges = true)
    }

    // 切换转写文本显示
    fun toggleTranscript() {
        _uiState.value = _uiState.value.copy(showTranscript = !_uiState.value.showTranscript)
    }

    override fun onCleared() {
        loadJob?.cancel()
        generationLaunchJob?.cancel()
        attachmentCollectionJob?.cancel()
        reportCollectionJob?.cancel()
        reportTaskCollectionJob?.cancel()
        super.onCleared()
    }

    private suspend fun resolveAndPersistReportTitle(meetingId: String, report: Report): String {
        val meeting = meetingRepository.findById(meetingId).getOrNull()
        val resolved = ReportTitleResolver.resolve(report, meeting?.title.orEmpty())
        if (meeting != null && resolved != meeting.title) {
            meetingRepository.updateTitle(meetingId, resolved)
        }
        return resolved
    }

    private fun buildReportContext(report: Report): String {
        val sb = StringBuilder()
        sb.appendLine("## 会议概述")
        sb.appendLine(report.summary)
        sb.appendLine()

        if (report.keyPoints.isNotEmpty()) {
            sb.appendLine("## 关键要点")
            report.keyPoints.forEachIndexed { index, point ->
                sb.appendLine("${index + 1}. $point")
            }
            sb.appendLine()
        }

        if (report.decisions.isNotEmpty()) {
            sb.appendLine("## 决策事项")
            report.decisions.forEach { decision ->
                sb.appendLine("- $decision")
            }
            sb.appendLine()
        }

        if (report.tasks.isNotEmpty()) {
            sb.appendLine("## 待办任务")
            report.tasks.forEach { task ->
                val assignee = task.assignee ?: "无"
                val due = task.due ?: "无"
                val priority = task.priority ?: "未提及"
                sb.appendLine("- ${task.content} | $assignee | $due | $priority")
            }
            sb.appendLine()
        }

        if (report.actionItems.isNotEmpty()) {
            sb.appendLine("## 行动项")
            report.actionItems.forEach { item ->
                sb.appendLine("- $item")
            }
        }

        return sb.toString()
    }
}
