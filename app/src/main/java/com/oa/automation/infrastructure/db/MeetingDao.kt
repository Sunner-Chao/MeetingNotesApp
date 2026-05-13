package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeeting(entity: MeetingEntity)

    @Query("SELECT * FROM meetings WHERE id = :id LIMIT 1")
    suspend fun findMeetingById(id: String): MeetingEntity?

    @Query("SELECT * FROM meetings ORDER BY createdAt DESC")
    fun observeAllMeetings(): Flow<List<MeetingEntity>>

    @Query("DELETE FROM meetings WHERE id = :id")
    suspend fun deleteMeeting(id: String)

    @Query("UPDATE meetings SET title = :title WHERE id = :id")
    suspend fun updateMeetingTitle(id: String, title: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranscript(entity: TranscriptEntity)

    @Query("SELECT * FROM transcripts WHERE meetingId = :meetingId ORDER BY createdAt ASC")
    suspend fun findTranscriptsByMeetingId(meetingId: String): List<TranscriptEntity>
}
