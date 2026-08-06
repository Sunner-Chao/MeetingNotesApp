package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class ConfirmedJourneyStageDraftRow(
    val stageId: String,
    val sequenceNumber: Int,
    val stageTitle: String,
    val stageSavedAt: Long?,
    val draftId: String,
    val versionNumber: Int,
    val content: String,
    val status: String,
    val evidenceTranscriptCount: Int,
    val evidenceAttachmentCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val confirmedAt: Long?
)

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

    @Query(
        "SELECT s.id AS stageId, s.sequenceNumber AS sequenceNumber, s.title AS stageTitle, " +
            "s.savedAt AS stageSavedAt, d.id AS draftId, d.versionNumber AS versionNumber, " +
            "d.content AS content, d.status AS status, " +
            "d.evidenceTranscriptCount AS evidenceTranscriptCount, " +
            "d.evidenceAttachmentCount AS evidenceAttachmentCount, d.createdAt AS createdAt, " +
            "d.updatedAt AS updatedAt, d.confirmedAt AS confirmedAt " +
            "FROM stage_draft_versions d INNER JOIN journey_stages s ON d.stageId = s.id " +
            "WHERE s.journeyId = :journeyId AND d.status = 'CONFIRMED' " +
            "AND d.versionNumber = (SELECT MAX(v.versionNumber) FROM stage_draft_versions v " +
            "WHERE v.stageId = d.stageId AND v.status = 'CONFIRMED') " +
            "ORDER BY s.sequenceNumber ASC"
    )
    suspend fun findLatestConfirmedByJourneyId(
        journeyId: String
    ): List<ConfirmedJourneyStageDraftRow>

    @Query("SELECT * FROM stage_draft_versions WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): StageDraftVersionEntity?

    @Query("SELECT * FROM stage_draft_versions WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<StageDraftVersionEntity>

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
