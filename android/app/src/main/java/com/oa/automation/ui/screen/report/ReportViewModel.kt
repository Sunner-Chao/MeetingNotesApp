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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
)

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

    fun loadReport(meetingId: String) {
        observeReportTask(meetingId)
        refreshArchivedAudio(meetingId)
        attachmentCollectionJob?.cancel()
        attachmentCollectionJob = viewModelScope.launch {
            attachmentStore.observe(meetingId).collect { attachments ->
                _uiState.update { it.copy(attachments = attachments) }
            }
        }
        reportCollectionJob?.cancel()
        reportCollectionJob = viewModelScope.launch {
            reportRepository.getAllReportsFlow().collect { reports ->
                reports.firstOrNull { it.meetingId == meetingId }?.let { report ->
                    val title = resolveAndPersistReportTitle(meetingId, report)
                    _uiState.update {
                        it.copy(
                            report = report,
                            meetingTitle = title,
                            forumParticipants = resolveForumParticipants(report),
                            isLoading = false,
                            isGenerating = false,
                            error = null
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
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
            _uiState.value = _uiState.value.copy(
                presetTemplates = templates,
                reportTemplate = appConfig.reportTemplateConfig
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

            // 先尝试从数据库加载已保存的报告
            val existingReport = reportRepository.findByMeetingId(meetingId).getOrNull()

            if (existingReport != null) {
                val title = resolveAndPersistReportTitle(meetingId, existingReport)
                // 已有保存的报告，直接显示
                _uiState.value = _uiState.value.copy(
                    report = existingReport,
                    meetingTitle = title,
                    forumParticipants = resolveForumParticipants(existingReport),
                    isLoading = false
                )
            } else {
                taskScheduler.enqueueReport(meetingId)
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        isGenerating = true,
                        generationProgressStage = "会议纪要正在排队",
                        generationProgressIndeterminate = true,
                        generationCancelled = false
                    )
                }
            }
        }
    }

    fun regenerateReport(meetingId: String) {
        taskScheduler.enqueueReport(meetingId)
        _uiState.update {
            it.copy(
                isGenerating = true,
                generationProgressPercent = null,
                generationProgressStage = "会议纪要正在排队",
                generationProgressIndeterminate = true,
                generationCancelled = false,
                error = null,
                message = "已加入后台生成队列"
            )
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
            _uiState.update { it.copy(reportTemplate = config) }
        }
    }

    fun regenerateWithTemplate(meetingId: String) {
        taskScheduler.enqueueReport(meetingId)
        _uiState.update {
            it.copy(
                isGenerating = true,
                generationProgressPercent = null,
                generationProgressStage = "会议纪要正在排队",
                generationProgressIndeterminate = true,
                generationCancelled = false,
                error = null,
                message = "已使用所选模板后台生成"
            )
        }
    }

    fun cancelGeneration(meetingId: String) {
        taskScheduler.cancelReport(meetingId)
        _uiState.update {
            it.copy(
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

    private fun observeReportTask(meetingId: String) {
        reportTaskCollectionJob?.cancel()
        reportTaskCollectionJob = viewModelScope.launch {
            taskScheduler.observeReport(meetingId).collect { task ->
                when (task.state) {
                    BackgroundTaskState.QUEUED, BackgroundTaskState.RUNNING -> _uiState.update {
                        it.copy(
                            isGenerating = true,
                            isLoading = it.report == null,
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
                        val title = report?.let { resolveAndPersistReportTitle(meetingId, it) }
                        _uiState.update {
                            it.copy(
                                report = report ?: it.report,
                                meetingTitle = title ?: it.meetingTitle,
                                forumParticipants = report?.let(::resolveForumParticipants)
                                    ?: it.forumParticipants,
                                isLoading = false,
                                isGenerating = false,
                                generationProgressPercent = null,
                                generationProgressStage = "",
                                generationProgressIndeterminate = false,
                                generationCancelled = false,
                                error = if (report == null) "纪要任务已完成，但未找到报告数据" else null,
                                message = if (report != null) "会议纪要已生成" else it.message
                            )
                        }
                    }

                    BackgroundTaskState.FAILED -> _uiState.update {
                        it.copy(
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

                    BackgroundTaskState.NONE -> Unit
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

    // 删除报告
    fun deleteReport(meetingId: String) {
        viewModelScope.launch {
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
                sb.appendLine("- ${task.content} | $assignee | $due")
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
