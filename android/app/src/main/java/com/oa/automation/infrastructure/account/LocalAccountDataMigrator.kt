package com.oa.automation.infrastructure.account

import com.oa.automation.domain.model.AuthSession
import com.oa.automation.domain.model.canonicalMeetingTranscripts
import com.oa.automation.domain.model.displayTitle
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LocalAccountDataMigrator(
    private val meetingRepository: MeetingRepository,
    private val reportRepository: ReportRepository,
    private val accountApiService: AccountApiService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun migrateAsync(endpoint: String, session: AuthSession) {
        scope.launch {
            meetingRepository.getAllMeetingsFlow().first().forEach { meeting ->
                val transcripts = meetingRepository.findTranscriptsByMeetingId(meeting.id)
                    .getOrNull()
                    .orEmpty()
                val report = reportRepository.findByMeetingId(meeting.id).getOrNull()
                val updatedAt = maxOf(
                    meeting.createdAt,
                    transcripts.maxOfOrNull { it.createdAt } ?: 0L,
                    report?.generatedAt ?: 0L
                )
                accountApiService.upsertAccountMeeting(
                    endpoint = endpoint,
                    token = session.accessToken,
                    meetingId = meeting.id,
                    title = meeting.displayTitle(),
                    createdAt = meeting.createdAt,
                    updatedAt = updatedAt,
                    durationSeconds = meeting.durationMs / 1_000L,
                    transcript = transcripts.canonicalMeetingTranscripts()
                        .joinToString("\n") { it.content },
                    report = report?.rawContent?.ifBlank { report.summary }.orEmpty()
                )
            }
        }
    }
}
