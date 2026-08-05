package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.oa.automation.domain.model.ScheduledMeeting
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMeetingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduledMeetingEntity)

    @Query("SELECT * FROM scheduled_meetings WHERE scheduledAt >= :now ORDER BY scheduledAt ASC")
    fun observeUpcoming(now: Long): Flow<List<ScheduledMeetingEntity>>

    @Query("SELECT * FROM scheduled_meetings WHERE scheduledAt >= :now ORDER BY scheduledAt ASC")
    suspend fun findUpcoming(now: Long): List<ScheduledMeetingEntity>

    @Query("SELECT * FROM scheduled_meetings WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ScheduledMeetingEntity?

    @Query("DELETE FROM scheduled_meetings WHERE id = :id")
    suspend fun deleteById(id: String)
}

fun ScheduledMeetingEntity.toDomain() = ScheduledMeeting(
    id = id,
    title = title,
    scheduledAt = scheduledAt,
    reminderMinutes = reminderMinutes,
    templateName = templateName,
    createdAt = createdAt
)

fun ScheduledMeeting.toEntity() = ScheduledMeetingEntity(
    id = id,
    title = title,
    scheduledAt = scheduledAt,
    reminderMinutes = reminderMinutes,
    templateName = templateName,
    createdAt = createdAt
)
