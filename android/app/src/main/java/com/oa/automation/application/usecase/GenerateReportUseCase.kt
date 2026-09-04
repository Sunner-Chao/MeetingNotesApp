package com.oa.automation.application.usecase

import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.ProcessingProgress
import com.oa.automation.domain.model.Task
import com.oa.automation.domain.model.canonicalMeetingTranscripts
import com.oa.automation.domain.model.extractForumParticipants
import com.oa.automation.domain.model.isForumMeetingTemplate
import com.oa.automation.domain.model.renderedContent
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.infrastructure.llm.LLMEngine
import com.oa.automation.infrastructure.llm.AgentAttachment
import com.oa.automation.infrastructure.llm.ReportData
import com.oa.automation.infrastructure.attachment.MeetingAttachmentStore
import com.oa.automation.infrastructure.account.AccountSessionSynchronizer
import com.oa.automation.infrastructure.account.isAuthenticationFailure
import com.oa.automation.locale.SimplifiedChineseText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class GenerateReportUseCase(
    private val meetingRepository: MeetingRepository,
    private val reportRepository: ReportRepository,
    private val llmEngine: LLMEngine,
    private val attachmentStore: MeetingAttachmentStore,
    private val accountSessionSynchronizer: AccountSessionSynchronizer
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
                transcripts.canonicalMeetingTranscripts().joinToString("\n") { it.renderedContent() }
            )
            onProgress(ProcessingProgress(20, "准备模板和会议图片"))
            val attachments = attachmentStore.toAgentAttachments(
                meetingRepository.observeAttachments(meetingId).first()
            )
            onProgress(ProcessingProgress(35, "Agent 正在分析会议内容", isIndeterminate = true))
            val reportTranscript = buildMarkerAwareTranscript(transcriptContent, attachments)
            val usageKey = "report:$meetingId:${UUID.randomUUID()}"
            var reportResult = runReportRequest(reportTranscript, attachments, meetingId, usageKey)
            if (reportResult.exceptionOrNull()?.isAuthenticationFailure() == true) {
                onProgress(ProcessingProgress(38, "服务会话正在更新，请稍候", isIndeterminate = true))
                val refreshed = withTimeoutOrNull(5_000L) { accountSessionSynchronizer.refresh() }
                if (refreshed?.isSuccess == true) {
                    reportResult = runReportRequest(reportTranscript, attachments, meetingId, usageKey)
                }
            }
            val reportData = reportResult.getOrElse { return Result.failure(it) }

            onProgress(ProcessingProgress(85, "整理纪要结构"))
            val tasks = reportData.tasks.map { taskData ->
                Task(
                    content = taskData.content,
                    assignee = taskData.assignee,
                    due = taskData.due,
                    priority = taskData.priority
                )
            }

            val existingReportId = reportRepository.findByMeetingId(meetingId)
                .getOrNull()
                ?.id
            val report = Report(
                id = existingReportId ?: UUID.randomUUID().toString(),
                meetingId = meetingId,
                summary = reportData.summary.ifBlank {
                    extractReportSummary(reportData.rawContent)
                },
                keyPoints = reportData.keyPoints,
                tasks = tasks,
                decisions = reportData.decisions,
                actionItems = reportData.actionItems,
                participants = if (reportData.templateName.isForumMeetingTemplate()) {
                    reportData.participants.ifEmpty {
                        extractForumParticipants(
                            rawContent = reportData.rawContent,
                            speakerNames = transcripts.mapNotNull { it.speakerName }
                        )
                    }
                } else {
                    emptyList()
                },
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

    private suspend fun runReportRequest(
        transcript: String,
        attachments: List<AgentAttachment>,
        meetingId: String,
        usageKey: String
    ): Result<ReportData> = try {
        Result.success(llmEngine.generateReport(transcript, attachments, meetingId, usageKey))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}

internal fun extractReportSummary(rawContent: String): String {
    return rawContent
        .split(Regex("\\n\\s*\\n"))
        .asSequence()
        .map { block ->
            block.lineSequence()
                .map { line -> line.trim().removePrefix(">").trim() }
                .joinToString(" ")
                .replace("**", "")
                .replace("__", "")
                .trim()
        }
        .firstOrNull { block ->
            block.length >= 20 &&
                !block.startsWith("#") &&
                !block.startsWith("|") &&
                !block.startsWith("-") &&
                !block.startsWith("[") &&
                !block.startsWith("路线：") &&
                !block.startsWith("同行与讲解：")
        }
        ?.take(600)
        .orEmpty()
}

internal fun buildMarkerAwareTranscript(
    transcript: String,
    attachments: List<AgentAttachment>
): String {
    val markerLines = attachments.mapIndexedNotNull { index, attachment ->
        val timestampMs = attachment.markerTimestampMs ?: return@mapIndexedNotNull null
        val markerTime = formatMarkerTimestamp(timestampMs)
        val anchor = attachment.markerTranscriptAnchor
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        buildString {
            append("- 图 ${index + 1}：录音标记 $markerTime")
            if (anchor.isNotBlank()) append("；转写锚点：“$anchor”")
        }
    }
    if (markerLines.isEmpty()) return transcript
    return buildString {
        appendLine(transcript.trim())
        appendLine()
        appendLine("照片定位标记（仅用于确定照片在正文中的插入位置，不是新的事实材料）：")
        markerLines.forEach(::appendLine)
    }.trim()
}

private fun formatMarkerTimestamp(timestampMs: Long): String {
    val totalSeconds = (timestampMs.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
