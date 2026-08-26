package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.MeetingAudioSegment
import com.oa.automation.domain.model.RecordingMarker
import com.oa.automation.domain.model.Transcript
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.infrastructure.db.MeetingDao
import com.oa.automation.infrastructure.db.toDomain
import com.oa.automation.infrastructure.db.toEntity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted

class MeetingRepositoryImpl(
    private val meetingDao: MeetingDao
) : MeetingRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val meetingsFlow: StateFlow<List<Meeting>> = meetingDao.observeAllMeetings()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    override suspend fun save(meeting: Meeting): Result<Meeting> {
        return runCatching {
            meetingDao.upsertMeeting(meeting.toEntity())
            meeting
        }
    }

    override suspend fun findById(id: String): Result<Meeting?> {
        return runCatching {
            meetingDao.findMeetingById(id)?.toDomain()
        }
    }

    override fun getAllMeetingsFlow(): StateFlow<List<Meeting>> {
        return meetingsFlow
    }

    override suspend fun getAllMeetings(): List<Meeting> = meetingDao.findAllMeetings()
        .map { it.toDomain() }

    override suspend fun delete(id: String): Result<Unit> {
        return runCatching {
            meetingDao.deleteMeeting(id)
        }
    }

    override suspend fun saveAudioSegment(segment: MeetingAudioSegment): Result<MeetingAudioSegment> =
        runCatching {
            meetingDao.upsertAudioSegment(segment.toEntity())
            segment
        }

    override suspend fun findAudioSegmentsByMeetingId(
        meetingId: String
    ): Result<List<MeetingAudioSegment>> = runCatching {
        meetingDao.findAudioSegmentsByMeetingId(meetingId).map { it.toDomain() }
    }

    override suspend fun updateTitle(id: String, title: String): Result<Meeting> {
        val existing = meetingDao.findMeetingById(id)
            ?: return Result.failure(IllegalArgumentException("Meeting not found: $id"))
        return runCatching {
            val updated = existing.copy(title = title).toDomain()
            meetingDao.upsertMeeting(updated.toEntity())
            updated
        }
    }

    override suspend fun saveTranscript(transcript: Transcript): Result<Transcript> {
        return runCatching {
            meetingDao.upsertTranscript(transcript.toEntity())
            transcript
        }
    }

    override suspend fun findTranscriptsByMeetingId(meetingId: String): Result<List<Transcript>> {
        return runCatching {
            meetingDao.findTranscriptsByMeetingId(meetingId).map { it.toDomain() }
        }
    }

    override suspend fun findTranscriptsByJourneyStageId(
        journeyStageId: String
    ): Result<List<Transcript>> = runCatching {
        meetingDao.findTranscriptsByJourneyStageId(journeyStageId).map { it.toDomain() }
    }

    override suspend fun saveAttachment(attachment: MeetingAttachment): Result<MeetingAttachment> = runCatching {
        meetingDao.upsertAttachment(attachment.toEntity())
        attachment
    }

    override suspend fun getAllAttachments(): List<MeetingAttachment> =
        meetingDao.findAllAttachments().map { it.toDomain() }

    override fun observeAttachments(meetingId: String) =
        meetingDao.observeAttachmentsByMeetingId(meetingId).map { list -> list.map { it.toDomain() } }

    override fun observeAttachmentsByJourneyStageId(journeyStageId: String) =
        meetingDao.observeAttachmentsByJourneyStageId(journeyStageId)
            .map { list -> list.map { it.toDomain() } }

    override suspend fun findAttachmentsByJourneyStageIds(
        stageIds: List<String>
    ): Result<List<MeetingAttachment>> = runCatching {
        if (stageIds.isEmpty()) emptyList()
        else meetingDao.findAttachmentsByJourneyStageIds(stageIds.distinct()).map { it.toDomain() }
    }

    override suspend fun deleteAttachment(id: String): Result<Unit> = runCatching {
        meetingDao.deleteAttachment(id)
    }

    override suspend fun saveRecordingMarker(marker: RecordingMarker): Result<RecordingMarker> = runCatching {
        meetingDao.upsertRecordingMarker(marker.toEntity())
        marker
    }

    override suspend fun findRecordingMarkersByMeetingId(
        meetingId: String
    ): Result<List<RecordingMarker>> = runCatching {
        meetingDao.findRecordingMarkersByMeetingId(meetingId).map { it.toDomain() }
    }
}
