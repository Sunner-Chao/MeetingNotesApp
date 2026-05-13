package com.oa.automation.ui.screen.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.application.usecase.GenerateReportUseCase
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.infrastructure.llm.ChatMessage
import com.oa.automation.infrastructure.llm.LLMEngine
import com.oa.automation.infrastructure.llm.ReportPromptTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    // UI state
    val message: String? = null,
    val hasUnsavedChanges: Boolean = false
)

class ReportViewModel(
    private val generateReportUseCase: GenerateReportUseCase,
    private val reportRepository: ReportRepository,
    private val meetingRepository: MeetingRepository,
    private val llmEngine: LLMEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    fun loadReport(meetingId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // 加载转写文本
            val transcripts = meetingRepository.findTranscriptsByMeetingId(meetingId).getOrNull().orEmpty()
            val transcriptText = transcripts.joinToString("\n") { it.content }
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
                // 无保存报告，生成新报告
                generateNewReport(meetingId)
            }
        }
    }

    fun regenerateReport(meetingId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGenerating = true, error = null)
            generateNewReport(meetingId)
        }
    }

    private suspend fun generateNewReport(meetingId: String) {
        generateReportUseCase(meetingId)
            .onSuccess { report ->
                _uiState.value = _uiState.value.copy(
                    report = report,
                    isLoading = false,
                    isGenerating = false
                )
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    error = error.message,
                    isLoading = false,
                    isGenerating = false
                )
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

            llmEngine.chat(chatHistory)
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
