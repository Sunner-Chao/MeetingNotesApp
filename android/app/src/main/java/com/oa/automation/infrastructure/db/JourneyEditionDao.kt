package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface JourneyEditionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: JourneyEditionEntity)

    @Query(
        "SELECT * FROM journey_editions " +
            "WHERE journeyId = :journeyId ORDER BY versionNumber DESC LIMIT 1"
    )
    suspend fun findLatest(journeyId: String): JourneyEditionEntity?

    @Query(
        "SELECT * FROM journey_editions " +
            "WHERE journeyId = :journeyId ORDER BY versionNumber DESC LIMIT 1"
    )
    fun observeLatest(journeyId: String): Flow<JourneyEditionEntity?>

    @Query("SELECT * FROM journey_editions WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): JourneyEditionEntity?

    @Query(
        "UPDATE journey_editions SET content = :content, updatedAt = :updatedAt " +
            "WHERE id = :id AND status = 'DRAFT'"
    )
    suspend fun updateEditionContent(id: String, content: String, updatedAt: Long): Int

    @Query(
        "UPDATE journey_editions SET status = 'CONFIRMED', confirmedAt = :confirmedAt, " +
            "updatedAt = :confirmedAt WHERE id = :id AND status = 'DRAFT'"
    )
    suspend fun markConfirmed(id: String, confirmedAt: Long): Int
}
