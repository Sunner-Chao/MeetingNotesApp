package com.oa.automation.domain.repository

import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.Transcript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MeetingRepository {
    suspend fun save(meeting: Meeting): Result<Meeting>
    suspend fun findById(id: String): Result<Meeting?>
    fun getAllMeetingsFlow(): StateFlow<List<Meeting>>
    suspend fun delete(id: String): Result<Unit>
    suspend fun updateTitle(id: String, title: String): Result<Meeting>
    suspend fun saveTranscript(transcript: Transcript): Result<Transcript>
    suspend fun findTranscriptsByMeetingId(meetingId: String): Result<List<Transcript>>
    suspend fun findTranscriptsByJourneyStageId(journeyStageId: String): Result<List<Transcript>>
    suspend fun saveAttachment(attachment: MeetingAttachment): Result<MeetingAttachment>
    fun observeAttachments(meetingId: String): Flow<List<MeetingAttachment>>
    fun observeAttachmentsByJourneyStageId(journeyStageId: String): Flow<List<MeetingAttachment>>
    suspend fun deleteAttachment(id: String): Result<Unit>
}
