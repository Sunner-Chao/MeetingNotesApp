package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.JourneyEdition
import com.oa.automation.domain.model.JourneyEditionStatus
import com.oa.automation.domain.repository.JourneyEditionRepository
import com.oa.automation.infrastructure.db.JourneyEditionDao
import com.oa.automation.infrastructure.db.toDomain
import com.oa.automation.infrastructure.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class JourneyEditionRepositoryImpl(
    private val dao: JourneyEditionDao
) : JourneyEditionRepository {
    override suspend fun createEdition(
        journeyId: String,
        content: String,
        sourceStageDraftIds: List<String>,
        sourceStageCount: Int
    ): Result<JourneyEdition> = runCatching {
        require(journeyId.isNotBlank()) { "Journey id must not be blank" }
        require(content.isNotBlank()) { "Journey edition content must not be blank" }
        val sourceIds = sourceStageDraftIds.filter { it.isNotBlank() }.distinct()
        require(sourceIds.isNotEmpty()) { "A journey edition needs confirmed stage drafts" }
        require(sourceStageCount == sourceIds.size) { "Source stage count must match source drafts" }

        val previous = dao.findLatest(journeyId)
        val now = System.currentTimeMillis()
        val edition = JourneyEdition(
            id = UUID.randomUUID().toString(),
            journeyId = journeyId,
            versionNumber = (previous?.versionNumber ?: 0) + 1,
            content = content.trim(),
            status = JourneyEditionStatus.DRAFT,
            sourceStageDraftIds = sourceIds,
            sourceStageCount = sourceStageCount,
            createdAt = now,
            updatedAt = now
        )
        dao.insert(edition.toEntity())
        edition
    }

    override fun observeLatest(journeyId: String): Flow<JourneyEdition?> =
        dao.observeLatest(journeyId).map { it?.toDomain() }

    override suspend fun saveEdition(id: String, content: String): Result<JourneyEdition> = runCatching {
        require(content.isNotBlank()) { "Journey edition content must not be blank" }
        val existing = dao.findById(id) ?: error("Journey edition not found: $id")
        require(existing.status == JourneyEditionStatus.DRAFT.name) {
            "Confirmed journey editions are immutable"
        }
        check(dao.updateEditionContent(id, content.trim(), System.currentTimeMillis()) == 1) {
            "Journey edition is no longer editable"
        }
        dao.findById(id)?.toDomain() ?: error("Journey edition disappeared: $id")
    }

    override suspend fun confirmEdition(id: String): Result<JourneyEdition> = runCatching {
        val existing = dao.findById(id) ?: error("Journey edition not found: $id")
        require(existing.status == JourneyEditionStatus.DRAFT.name) {
            "Journey edition is already confirmed"
        }
        val now = System.currentTimeMillis()
        check(dao.markConfirmed(id, now) == 1) { "Journey edition is no longer confirmable" }
        dao.findById(id)?.toDomain() ?: error("Journey edition disappeared: $id")
    }
}
