package com.oa.automation.application.usecase

import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.ProcessingProgress
import com.oa.automation.domain.model.Task
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.infrastructure.llm.LLMEngine
import com.oa.automation.infrastructure.attachment.MeetingAttachmentStore
import com.oa.automation.locale.SimplifiedChineseText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import java.util.UUID

class GenerateReportUseCase(
    private val meetingRepository: MeetingRepository,
    private val reportRepository: ReportRepository,
    private val llmEngine: LLMEngine,
    private val attachmentStore: MeetingAttachmentStore
) {
    suspend operator fun invoke(
        meetingId: String,
        onProgress: (ProcessingProgress) -> Unit = {}
    ): Result<Report> {
        return try {
            onProgress(ProcessingProgress(8, "读取最终转录"))
            val transcriptsResult = meetingRepository.findTranscriptsByMeetingId(meetingId)
            val transcripts = transcriptsResult.getOrElse {
                return Result.failure(Exception("Transcript not found"))
            }

            if (transcripts.isEmpty()) {
                return Result.failure(Exception("Transcript not found"))
            }

            val transcriptContent = SimplifiedChineseText.normalize(
                transcripts.joinToString("\n") { it.content }
            )
            onProgress(ProcessingProgress(20, "准备模板和会议图片"))
            val attachments = attachmentStore.toAgentAttachments(
                meetingRepository.observeAttachments(meetingId).first()
            )
            onProgress(ProcessingProgress(35, "Agent 正在分析会议内容", isIndeterminate = true))
            val reportData = llmEngine.generateReport(transcriptContent, attachments)

            onProgress(ProcessingProgress(85, "整理纪要结构"))
            val tasks = reportData.tasks.map { taskData ->
                Task(
                    content = taskData.content,
                    assignee = taskData.assignee,
                    due = taskData.due
                )
            }

            val existingReportId = reportRepository.findByMeetingId(meetingId)
                .getOrNull()
                ?.id
            val report = Report(
                id = existingReportId ?: UUID.randomUUID().toString(),
                meetingId = meetingId,
                summary = reportData.summary,
                keyPoints = reportData.keyPoints,
                tasks = tasks,
                decisions = reportData.decisions,
                actionItems = reportData.actionItems,
                rawContent = reportData.rawContent,
                templateName = reportData.templateName
            )

            onProgress(ProcessingProgress(96, "保存会议纪要"))
            reportRepository.save(report).getOrThrow()
            onProgress(ProcessingProgress(100, "会议纪要已完成"))
            Result.success(report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
