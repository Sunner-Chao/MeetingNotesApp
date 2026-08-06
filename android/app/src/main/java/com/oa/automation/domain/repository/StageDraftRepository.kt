package com.oa.automation.domain.repository

import com.oa.automation.domain.model.ConfirmedJourneyStageDraft
import com.oa.automation.domain.model.StageDraftVersion
import kotlinx.coroutines.flow.Flow

interface StageDraftRepository {
    suspend fun createDraft(
        stageId: String,
        content: String,
        evidenceTranscriptCount: Int,
        evidenceAttachmentCount: Int
    ): Result<StageDraftVersion>

    fun observeLatest(stageId: String): Flow<StageDraftVersion?>

    suspend fun findLatestConfirmedByJourneyId(
        journeyId: String
    ): Result<List<ConfirmedJourneyStageDraft>>

    suspend fun findByIds(ids: List<String>): Result<List<StageDraftVersion>>

    suspend fun saveDraft(id: String, content: String): Result<StageDraftVersion>

    suspend fun confirmDraft(id: String): Result<StageDraftVersion>
}
