package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.ConfirmedJourneyStageDraft
import com.oa.automation.domain.model.StageDraftStatus
import com.oa.automation.domain.model.StageDraftVersion
import com.oa.automation.domain.repository.StageDraftRepository
import com.oa.automation.infrastructure.db.StageDraftDao
import com.oa.automation.infrastructure.db.toDomain
import com.oa.automation.infrastructure.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class StageDraftRepositoryImpl(
    private val dao: StageDraftDao
) : StageDraftRepository {
    override suspend fun createDraft(
        stageId: String,
        content: String,
        evidenceTranscriptCount: Int,
        evidenceAttachmentCount: Int
    ): Result<StageDraftVersion> = runCatching {
        require(stageId.isNotBlank()) { "Stage id must not be blank" }
        require(content.isNotBlank()) { "Stage draft content must not be blank" }
        val previous = dao.findLatest(stageId)
        val now = System.currentTimeMillis()
        val draft = StageDraftVersion(
            id = UUID.randomUUID().toString(),
            stageId = stageId,
            versionNumber = (previous?.versionNumber ?: 0) + 1,
            content = content.trim(),
            status = StageDraftStatus.DRAFT,
            evidenceTranscriptCount = evidenceTranscriptCount.coerceAtLeast(0),
            evidenceAttachmentCount = evidenceAttachmentCount.coerceAtLeast(0),
            createdAt = now,
            updatedAt = now
        )
        dao.insert(draft.toEntity())
        draft
    }

    override fun observeLatest(stageId: String): Flow<StageDraftVersion?> =
        dao.observeLatest(stageId).map { it?.toDomain() }

    override suspend fun findLatestConfirmedByJourneyId(
        journeyId: String
    ): Result<List<ConfirmedJourneyStageDraft>> = runCatching {
        require(journeyId.isNotBlank()) { "Journey id must not be blank" }
        dao.findLatestConfirmedByJourneyId(journeyId).map { row ->
            ConfirmedJourneyStageDraft(
                stageId = row.stageId,
                sequenceNumber = row.sequenceNumber,
                stageTitle = row.stageTitle,
                stageSavedAt = row.stageSavedAt,
                draft = StageDraftVersion(
                    id = row.draftId,
                    stageId = row.stageId,
                    versionNumber = row.versionNumber,
                    content = row.content,
                    status = StageDraftStatus.fromStorage(row.status),
                    evidenceTranscriptCount = row.evidenceTranscriptCount,
                    evidenceAttachmentCount = row.evidenceAttachmentCount,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    confirmedAt = row.confirmedAt
                )
            )
        }
    }

    override suspend fun saveDraft(id: String, content: String): Result<StageDraftVersion> = runCatching {
        require(content.isNotBlank()) { "Stage draft content must not be blank" }
        val existing = dao.findById(id) ?: error("Stage draft not found: $id")
        require(existing.status == StageDraftStatus.DRAFT.name) {
            "Confirmed stage drafts are immutable"
        }
        check(dao.updateDraftContent(id, content.trim(), System.currentTimeMillis()) == 1) {
            "Stage draft is no longer editable"
        }
        dao.findById(id)?.toDomain() ?: error("Stage draft disappeared: $id")
    }

    override suspend fun confirmDraft(id: String): Result<StageDraftVersion> = runCatching {
        val existing = dao.findById(id) ?: error("Stage draft not found: $id")
        require(existing.status == StageDraftStatus.DRAFT.name) {
            "Stage draft is already confirmed"
        }
        val now = System.currentTimeMillis()
        check(dao.markConfirmed(id, now) == 1) { "Stage draft is no longer confirmable" }
        dao.findById(id)?.toDomain() ?: error("Stage draft disappeared: $id")
    }
}
