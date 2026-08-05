package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.ScheduledMeeting
import com.oa.automation.domain.repository.ScheduledMeetingRepository
import com.oa.automation.infrastructure.db.ScheduledMeetingDao
import com.oa.automation.infrastructure.db.toDomain
import com.oa.automation.infrastructure.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ScheduledMeetingRepositoryImpl(
    private val dao: ScheduledMeetingDao
) : ScheduledMeetingRepository {
    override fun observeUpcoming(): Flow<List<ScheduledMeeting>> =
        dao.observeUpcoming(System.currentTimeMillis()).map { list -> list.map { it.toDomain() } }

    override suspend fun findUpcoming(now: Long): List<ScheduledMeeting> =
        dao.findUpcoming(now).map { it.toDomain() }

    override suspend fun save(meeting: ScheduledMeeting): Result<ScheduledMeeting> = runCatching {
        dao.upsert(meeting.toEntity())
        meeting
    }

    override suspend fun findById(id: String): Result<ScheduledMeeting?> = runCatching {
        dao.findById(id)?.toDomain()
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        dao.deleteById(id)
    }
}
