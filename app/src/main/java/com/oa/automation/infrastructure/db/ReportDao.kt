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

    @Query("SELECT * FROM reports WHERE meetingId = :meetingId LIMIT 1")
    suspend fun findByMeetingId(meetingId: String): ReportEntity?

    @Query("DELETE FROM reports WHERE meetingId = :meetingId")
    suspend fun deleteByMeetingId(meetingId: String)

    @Query("SELECT * FROM reports ORDER BY generatedAt DESC")
    fun observeAllReports(): Flow<List<ReportEntity>>
}
