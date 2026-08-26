package com.oa.automation.domain.repository

import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.MeetingAudioSegment
import com.oa.automation.domain.model.RecordingMarker
import com.oa.automation.domain.model.Transcript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MeetingRepository {
    suspend fun save(meeting: Meeting): Result<Meeting>
    suspend fun findById(id: String): Result<Meeting?>
    fun getAllMeetingsFlow(): StateFlow<List<Meeting>>

    /**
     * Reads the current meeting rows directly from the local store.
     *
     * The observable list remains the primary path for live updates, while
     * this snapshot is used when a screen returns from another destination so
     * it cannot keep rendering a stale ViewModel copy.
     */
    suspend fun getAllMeetings(): List<Meeting> = getAllMeetingsFlow().value
    suspend fun delete(id: String): Result<Unit>
    suspend fun saveAudioSegment(segment: MeetingAudioSegment): Result<MeetingAudioSegment>
    suspend fun findAudioSegmentsByMeetingId(meetingId: String): Result<List<MeetingAudioSegment>>
    suspend fun updateTitle(id: String, title: String): Result<Meeting>
    suspend fun saveTranscript(transcript: Transcript): Result<Transcript>
    suspend fun findTranscriptsByMeetingId(meetingId: String): Result<List<Transcript>>
    suspend fun findTranscriptsByJourneyStageId(journeyStageId: String): Result<List<Transcript>>
    suspend fun saveAttachment(attachment: MeetingAttachment): Result<MeetingAttachment>
    /** Snapshot used to migrate legacy cache-backed image attachments on startup. */
    suspend fun getAllAttachments(): List<MeetingAttachment> = emptyList()
    fun observeAttachments(meetingId: String): Flow<List<MeetingAttachment>>
    fun observeAttachmentsByJourneyStageId(journeyStageId: String): Flow<List<MeetingAttachment>>
    suspend fun findAttachmentsByJourneyStageIds(stageIds: List<String>): Result<List<MeetingAttachment>>
    suspend fun deleteAttachment(id: String): Result<Unit>
    suspend fun saveRecordingMarker(marker: RecordingMarker): Result<RecordingMarker>
    suspend fun findRecordingMarkersByMeetingId(meetingId: String): Result<List<RecordingMarker>>
}
