package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface JourneyDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJourney(entity: JourneyEntity)

    @Upsert
    suspend fun upsertJourney(entity: JourneyEntity)

    @Query("SELECT * FROM journeys WHERE id = :id LIMIT 1")
    suspend fun findJourneyById(id: String): JourneyEntity?

    @Query("SELECT * FROM journeys WHERE meetingId = :meetingId LIMIT 1")
    suspend fun findJourneyByMeetingId(meetingId: String): JourneyEntity?

    @Query("SELECT * FROM journeys WHERE meetingId = :meetingId LIMIT 1")
    fun observeJourneyByMeetingId(meetingId: String): Flow<JourneyEntity?>

    @Query("DELETE FROM journeys WHERE id = :id")
    suspend fun deleteJourneyById(id: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStage(entity: JourneyStageEntity)

    @Upsert
    suspend fun upsertStage(entity: JourneyStageEntity)

    @Query("SELECT * FROM journey_stages WHERE id = :id LIMIT 1")
    suspend fun findStageById(id: String): JourneyStageEntity?

    @Query("SELECT * FROM journey_stages WHERE journeyId = :journeyId ORDER BY sequenceNumber DESC LIMIT 1")
    suspend fun findLatestStage(journeyId: String): JourneyStageEntity?

    @Query("SELECT * FROM journey_stages WHERE journeyId = :journeyId ORDER BY sequenceNumber ASC")
    fun observeStages(journeyId: String): Flow<List<JourneyStageEntity>>

    @Transaction
    suspend fun insertJourneyWithInitialStage(
        journey: JourneyEntity,
        initialStage: JourneyStageEntity
    ) {
        insertJourney(journey)
        insertStage(initialStage)
    }

    @Transaction
    suspend fun saveCurrentStage(
        journey: JourneyEntity,
        savedStage: JourneyStageEntity
    ) {
        upsertStage(savedStage)
        upsertJourney(journey)
    }

    @Transaction
    suspend fun startNextStage(
        journey: JourneyEntity,
        nextStage: JourneyStageEntity
    ) {
        insertStage(nextStage)
        upsertJourney(journey)
    }
}
