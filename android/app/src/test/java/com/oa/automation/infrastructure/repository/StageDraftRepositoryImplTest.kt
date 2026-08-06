package com.oa.automation.infrastructure.repository

import com.oa.automation.infrastructure.db.StageDraftDao
import com.oa.automation.infrastructure.db.StageDraftVersionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageDraftRepositoryImplTest {
    @Test
    fun `draft versions increment and confirmed content stays immutable`() = runBlocking {
        val dao = FakeStageDraftDao()
        val repository = StageDraftRepositoryImpl(dao)

        val first = repository.createDraft(
            stageId = "stage-1",
            content = "第一版",
            evidenceTranscriptCount = 2,
            evidenceAttachmentCount = 3
        ).getOrThrow()
        assertEquals(1, first.versionNumber)
        assertEquals(first, repository.observeLatest("stage-1").first())

        val edited = repository.saveDraft(first.id, "第一版修订").getOrThrow()
        assertEquals("第一版修订", edited.content)
        val confirmed = repository.confirmDraft(first.id).getOrThrow()
        assertEquals("CONFIRMED", confirmed.status.name)
        assertTrue(repository.saveDraft(first.id, "不得覆盖").isFailure)
        assertTrue(repository.confirmDraft(first.id).isFailure)

        val second = repository.createDraft(
            stageId = "stage-1",
            content = "第二版",
            evidenceTranscriptCount = 5,
            evidenceAttachmentCount = 8
        ).getOrThrow()
        assertEquals(2, second.versionNumber)
        assertEquals("第二版", repository.observeLatest("stage-1").first()?.content)
    }

    private class FakeStageDraftDao : StageDraftDao {
        private val values = linkedMapOf<String, StageDraftVersionEntity>()
        private val latest = MutableStateFlow<StageDraftVersionEntity?>(null)

        override suspend fun insert(entity: StageDraftVersionEntity) {
            check(values.putIfAbsent(entity.id, entity) == null)
            publish(entity.stageId)
        }

        override suspend fun findLatest(stageId: String): StageDraftVersionEntity? =
            values.values.filter { it.stageId == stageId }.maxByOrNull { it.versionNumber }

        override fun observeLatest(stageId: String): Flow<StageDraftVersionEntity?> =
            MutableStateFlow(values.values.filter { it.stageId == stageId }.maxByOrNull { it.versionNumber })

        override suspend fun findLatestConfirmedByJourneyId(
            journeyId: String
        ): List<com.oa.automation.infrastructure.db.ConfirmedJourneyStageDraftRow> = emptyList()

        override suspend fun findById(id: String): StageDraftVersionEntity? = values[id]

        override suspend fun updateDraftContent(id: String, content: String, updatedAt: Long): Int {
            val existing = values[id] ?: return 0
            if (existing.status != "DRAFT") return 0
            values[id] = existing.copy(content = content, updatedAt = updatedAt)
            publish(existing.stageId)
            return 1
        }

        override suspend fun markConfirmed(id: String, confirmedAt: Long): Int {
            val existing = values[id] ?: return 0
            if (existing.status != "DRAFT") return 0
            values[id] = existing.copy(
                status = "CONFIRMED",
                updatedAt = confirmedAt,
                confirmedAt = confirmedAt
            )
            publish(existing.stageId)
            return 1
        }

        private fun publish(stageId: String) {
            latest.value = values.values.filter { it.stageId == stageId }
                .maxByOrNull { it.versionNumber }
        }
    }
}
