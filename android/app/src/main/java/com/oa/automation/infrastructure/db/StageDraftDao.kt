package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StageDraftDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: StageDraftVersionEntity)

    @Query(
        "SELECT * FROM stage_draft_versions " +
            "WHERE stageId = :stageId ORDER BY versionNumber DESC LIMIT 1"
    )
    suspend fun findLatest(stageId: String): StageDraftVersionEntity?

    @Query(
        "SELECT * FROM stage_draft_versions " +
            "WHERE stageId = :stageId ORDER BY versionNumber DESC LIMIT 1"
    )
    fun observeLatest(stageId: String): Flow<StageDraftVersionEntity?>

    @Query("SELECT * FROM stage_draft_versions WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): StageDraftVersionEntity?

    @Query(
        "UPDATE stage_draft_versions SET content = :content, updatedAt = :updatedAt " +
            "WHERE id = :id AND status = 'DRAFT'"
    )
    suspend fun updateDraftContent(id: String, content: String, updatedAt: Long): Int

    @Query(
        "UPDATE stage_draft_versions SET status = 'CONFIRMED', confirmedAt = :confirmedAt, " +
            "updatedAt = :confirmedAt WHERE id = :id AND status = 'DRAFT'"
    )
    suspend fun markConfirmed(id: String, confirmedAt: Long): Int
}
