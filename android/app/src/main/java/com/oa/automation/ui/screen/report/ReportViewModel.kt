package com.oa.automation.ui.screen.report

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.infrastructure.llm.ChatMessage
import com.oa.automation.infrastructure.llm.LLMEngine
import com.oa.automation.infrastructure.llm.ReportPromptTemplates
import com.oa.automation.infrastructure.attachment.MeetingAttachmentStore
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

data class ChatMessageUi(
    val id: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ReportUiState(
    val report: Report? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isGenerating: Boolean = false,
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
    val attachments: List<MeetingAttachment> = emptyList()
)

class ReportViewModel(
    private val taskScheduler: BackgroundTaskScheduler,
    private val reportRepository: ReportRepository,
    private val meetingRepository: MeetingRepository,
    private val llmEngine: LLMEngine,
    private val configDataStore: ConfigDataStore,
    private val attachmentStore: MeetingAttachmentStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()
    private var attachmentCollectionJob: Job? = null
    private var reportCollectionJob: Job? = null
    private var reportTaskCollectionJob: Job? = null

    fun loadReport(meetingId: String) {
        observeReportTask(meetingId)
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
                    _uiState.update {
                        it.copy(report = report, isLoading = false, isGenerating = false, error = null)
                    }
                }
            }
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // 加载模板配置
            val templates = configDataStore.loadPresetTemplates()
            val appConfig = configDataStore.appConfigFlow.first()
            _uiState.value = _uiState.value.copy(
                presetTemplates = templates,
                reportTemplate = appConfig.reportTemplateConfig
            )

            // 加载转写文本
            val transcripts = meetingRepository.findTranscriptsByMeetingId(meetingId).getOrNull().orEmpty()
            val transcriptText = SimplifiedChineseText.normalize(
                transcripts.joinToString("\n") { it.content }
            )
            _uiState.value = _uiState.value.copy(transcriptText = transcriptText)

            // 先尝试从数据库加载已保存的报告
            val existingReport = reportRepository.findByMeetingId(meetingId).getOrNull()

            if (existingReport != null) {
                // 已有保存的报告，直接显示
                _uiState.value = _uiState.value.copy(
                    report = existingReport,
                    isLoading = false
                )
            } else {
                taskScheduler.enqueueReport(meetingId)
                _uiState.update { it.copy(isLoading = true, isGenerating = true) }
            }
        }
    }

    fun regenerateReport(meetingId: String) {
        taskScheduler.enqueueReport(meetingId)
        _uiState.update { it.copy(isGenerating = true, error = null, message = "已加入后台生成队列") }
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
        _uiState.update { it.copy(isGenerating = true, error = null, message = "已使用所选模板后台生成") }
    }

    private fun observeReportTask(meetingId: String) {
        reportTaskCollectionJob?.cancel()
        reportTaskCollectionJob = viewModelScope.launch {
            taskScheduler.observeReport(meetingId).collect { task ->
                when (task.state) {
                    BackgroundTaskState.QUEUED, BackgroundTaskState.RUNNING -> _uiState.update {
                        it.copy(isGenerating = true, isLoading = it.report == null, error = null)
                    }

                    BackgroundTaskState.SUCCEEDED -> {
                        val report = reportRepository.findByMeetingId(meetingId).getOrNull()
                        _uiState.update {
                            it.copy(
                                report = report ?: it.report,
                                isLoading = false,
                                isGenerating = false,
                                error = if (report == null) "纪要任务已完成，但未找到报告数据" else null,
                                message = if (report != null) "会议纪要已生成" else it.message
                            )
                        }
                    }

                    BackgroundTaskState.FAILED -> _uiState.update {
                        it.copy(
                            isLoading = false,
                            isGenerating = false,
                            error = "生成报告失败：${task.error ?: "Agent 服务请求失败"}"
                        )
                    }

                    BackgroundTaskState.CANCELLED -> _uiState.update {
                        it.copy(isLoading = false, isGenerating = false, error = "纪要生成任务已取消")
                    }

                    BackgroundTaskState.NONE -> Unit
                }
            }
        }
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

    fun importImages(meetingId: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            uris.forEach { uri ->
                attachmentStore.importImage(meetingId, uri)
                    .onFailure { error ->
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
