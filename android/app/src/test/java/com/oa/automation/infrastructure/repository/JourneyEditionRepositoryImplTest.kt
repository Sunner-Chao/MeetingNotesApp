package com.oa.automation.infrastructure.repository

import com.oa.automation.infrastructure.db.JourneyEditionDao
import com.oa.automation.infrastructure.db.JourneyEditionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyEditionRepositoryImplTest {
    @Test
    fun `journey editions are versioned and confirmed copies are immutable`() = runBlocking {
        val repository = JourneyEditionRepositoryImpl(FakeJourneyEditionDao())
        val first = repository.createEdition(
            journeyId = "journey-1",
            content = "第一版总游记",
            sourceStageDraftIds = listOf("draft-1", "draft-2"),
            sourceStageCount = 2
        ).getOrThrow()
        assertEquals(1, first.versionNumber)

        val edited = repository.saveEdition(first.id, "第一版总游记修订").getOrThrow()
        assertEquals("第一版总游记修订", edited.content)
        repository.confirmEdition(first.id).getOrThrow()
        assertTrue(repository.saveEdition(first.id, "不得覆盖").isFailure)
        assertTrue(repository.confirmEdition(first.id).isFailure)

        val second = repository.createEdition(
            journeyId = "journey-1",
            content = "第二版总游记",
            sourceStageDraftIds = listOf("draft-1", "draft-3"),
            sourceStageCount = 2
        ).getOrThrow()
        assertEquals(2, second.versionNumber)
        assertEquals("第二版总游记", repository.observeLatest("journey-1").first()?.content)
    }

    private class FakeJourneyEditionDao : JourneyEditionDao {
        private val values = linkedMapOf<String, JourneyEditionEntity>()

        override suspend fun insert(entity: JourneyEditionEntity) {
            check(values.putIfAbsent(entity.id, entity) == null)
        }

        override suspend fun findLatest(journeyId: String): JourneyEditionEntity? =
            values.values.filter { it.journeyId == journeyId }.maxByOrNull { it.versionNumber }

        override fun observeLatest(journeyId: String): Flow<JourneyEditionEntity?> =
            MutableStateFlow(values.values.filter { it.journeyId == journeyId }.maxByOrNull { it.versionNumber })

        override suspend fun findById(id: String): JourneyEditionEntity? = values[id]

        override suspend fun updateEditionContent(id: String, content: String, updatedAt: Long): Int {
            val existing = values[id] ?: return 0
            if (existing.status != "DRAFT") return 0
            values[id] = existing.copy(content = content, updatedAt = updatedAt)
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
            return 1
        }
    }
}
