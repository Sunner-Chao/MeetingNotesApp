package com.oa.automation.domain.repository

import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.Transcript
import kotlinx.coroutines.flow.StateFlow

interface MeetingRepository {
    suspend fun save(meeting: Meeting): Result<Meeting>
    suspend fun findById(id: String): Result<Meeting?>
    fun getAllMeetingsFlow(): StateFlow<List<Meeting>>
    suspend fun delete(id: String): Result<Unit>
    suspend fun updateTitle(id: String, title: String): Result<Meeting>
    suspend fun saveTranscript(transcript: Transcript): Result<Transcript>
    suspend fun findTranscriptsByMeetingId(meetingId: String): Result<List<Transcript>>
}
