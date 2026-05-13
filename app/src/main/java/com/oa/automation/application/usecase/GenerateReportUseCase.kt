package com.oa.automation.application.usecase

import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.Task
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.infrastructure.llm.LLMEngine
import java.util.UUID

class GenerateReportUseCase(
    private val meetingRepository: MeetingRepository,
    private val reportRepository: ReportRepository,
    private val llmEngine: LLMEngine
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

            val transcriptContent = transcripts.joinToString("\n") { it.content }
            val reportData = llmEngine.generateReport(transcriptContent)

            val tasks = reportData.tasks.map { taskData ->
                Task(
                    content = taskData.content,
                    assignee = taskData.assignee,
                    due = taskData.due
                )
            }

            val report = Report(
                id = UUID.randomUUID().toString(),
                meetingId = meetingId,
                summary = reportData.summary,
                keyPoints = reportData.keyPoints,
                tasks = tasks,
                decisions = reportData.decisions,
                actionItems = reportData.actionItems,
                rawContent = reportData.rawContent,
                templateName = reportData.templateName
            )

            reportRepository.save(report)
            Result.success(report)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
