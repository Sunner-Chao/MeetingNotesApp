package com.oa.automation.domain.repository

import com.oa.automation.domain.model.ScheduledMeeting
import kotlinx.coroutines.flow.Flow

interface ScheduledMeetingRepository {
    fun observeUpcoming(): Flow<List<ScheduledMeeting>>
    suspend fun findUpcoming(now: Long = System.currentTimeMillis()): List<ScheduledMeeting>
    suspend fun save(meeting: ScheduledMeeting): Result<ScheduledMeeting>
    suspend fun findById(id: String): Result<ScheduledMeeting?>
    suspend fun delete(id: String): Result<Unit>
}
