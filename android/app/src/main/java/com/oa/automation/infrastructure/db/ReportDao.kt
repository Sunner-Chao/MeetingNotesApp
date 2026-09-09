package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReport(entity: ReportEntity)

    @Query("SELECT * FROM reports WHERE ownerId = :ownerId AND meetingId = :meetingId LIMIT 1")
    suspend fun findByMeetingId(meetingId: String, ownerId: String? = null): ReportEntity?

    @Query("DELETE FROM reports WHERE ownerId = :ownerId AND meetingId = :meetingId")
    suspend fun deleteByMeetingId(meetingId: String, ownerId: String?)

    @Query("SELECT * FROM reports WHERE ownerId = :ownerId ORDER BY generatedAt DESC")
    fun observeAllReports(ownerId: String?): Flow<List<ReportEntity>>
}
