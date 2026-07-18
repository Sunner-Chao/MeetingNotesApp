package com.oa.automation.application.usecase

import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.Task
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.infrastructure.llm.LLMEngine
import com.oa.automation.infrastructure.attachment.MeetingAttachmentStore
import com.oa.automation.locale.SimplifiedChineseText
import kotlinx.coroutines.flow.first
import java.util.UUID

class GenerateReportUseCase(
    private val meetingRepository: MeetingRepository,
    private val reportRepository: ReportRepository,
    private val llmEngine: LLMEngine,
    private val attachmentStore: MeetingAttachmentStore
) {
    suspend operator fun invoke(meetingId: String): Result<Report> {
        return try {
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
            val attachments = attachmentStore.toAgentAttachments(
                meetingRepository.observeAttachments(meetingId).first()
            )
            val reportData = llmEngine.generateReport(transcriptContent, attachments)

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

            reportRepository.save(report).getOrThrow()
            Result.success(report)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
