package com.oa.automation.infrastructure.repository

import com.oa.automation.infrastructure.db.PublishedPostDao
import com.oa.automation.infrastructure.db.PublishedPostEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedPostRepositoryImplTest {
    @Test
    fun `private snapshot requires both reviews before ready and can be withdrawn`() = runBlocking {
        val repository = PublishedPostRepositoryImpl(FakePublishedPostDao())
        val post = repository.createReviewSnapshot(
            journeyId = "journey-1",
            journeyEditionId = "edition-1",
            sourceEditionVersion = 1,
            title = "城市更新研学",
            content = "发布预览",
            redactedCoordinateCount = 1
        ).getOrThrow()

        assertEquals(1, post.versionNumber)
        assertTrue(post.aiAssisted)
        assertTrue(repository.markReady(post.id).isFailure)

        val reviewed = repository.saveReview(
            id = post.id,
            privacyReviewed = true,
            rightsConfirmed = true
        ).getOrThrow()
        assertTrue(reviewed.privacyReviewed)
        assertTrue(reviewed.rightsConfirmed)

        val ready = repository.markReady(post.id).getOrThrow()
        assertEquals("READY", ready.status.name)
        assertTrue(repository.saveReview(post.id, false, false).isFailure)

        val withdrawn = repository.withdraw(post.id).getOrThrow()
        assertEquals("WITHDRAWN", withdrawn.status.name)
        assertTrue(repository.withdraw(post.id).isFailure)
        assertEquals(withdrawn, repository.observeLatest("journey-1").first())
    }

    @Test
    fun `metadata can be corrected during review and locks after ready`() = runBlocking {
        val repository = PublishedPostRepositoryImpl(FakePublishedPostDao())
        val post = repository.createReviewSnapshot(
            journeyId = "journey-2",
            journeyEditionId = "edition-2",
            sourceEditionVersion = 1,
            title = "研学考察",
            content = "发布预览",
            redactedCoordinateCount = 0
        ).getOrThrow()

        val corrected = repository.updateMetadata(
            id = post.id,
            destination = "苏州",
            travelDate = "2026-08-06",
            travelDays = 2,
            tags = listOf("园林", "园林", "建筑"),
            pois = listOf("拙政园")
        ).getOrThrow()
        assertEquals("苏州", corrected.destination)
        assertEquals(2, corrected.travelDays)
        assertEquals(listOf("园林", "建筑"), corrected.tags)

        repository.saveReview(post.id, true, true).getOrThrow()
        repository.markReady(post.id).getOrThrow()
        assertTrue(
            repository.updateMetadata(post.id, "杭州", "", 0, emptyList(), emptyList()).isFailure
        )
    }

    private class FakePublishedPostDao : PublishedPostDao {
        private val values = linkedMapOf<String, PublishedPostEntity>()

        override fun observeAll(): Flow<List<PublishedPostEntity>> =
            MutableStateFlow(values.values.sortedByDescending { it.updatedAt })

        override suspend fun insert(entity: PublishedPostEntity) {
            check(values.putIfAbsent(entity.id, entity) == null)
        }

        override suspend fun findLatest(journeyId: String): PublishedPostEntity? =
            values.values.filter { it.journeyId == journeyId }.maxByOrNull { it.versionNumber }

        override fun observeLatest(journeyId: String): Flow<PublishedPostEntity?> =
            MutableStateFlow(values.values.filter { it.journeyId == journeyId }.maxByOrNull { it.versionNumber })

        override suspend fun findById(id: String): PublishedPostEntity? = values[id]

        override suspend fun updateReview(
            id: String,
            privacyReviewed: Boolean,
            rightsConfirmed: Boolean,
            updatedAt: Long
        ): Int {
            val existing = values[id] ?: return 0
            if (existing.status != "REVIEW") return 0
            values[id] = existing.copy(
                privacyReviewed = privacyReviewed,
                rightsConfirmed = rightsConfirmed,
                updatedAt = updatedAt
            )
            return 1
        }

        override suspend fun updateMetadata(
            id: String,
            destination: String,
            travelDate: String,
            travelDays: Int,
            tags: List<String>,
            pois: List<String>,
            updatedAt: Long
        ): Int {
            val existing = values[id] ?: return 0
            if (existing.status != "REVIEW") return 0
            values[id] = existing.copy(
                destination = destination,
                travelDate = travelDate,
                travelDays = travelDays,
                tags = tags,
                pois = pois,
                updatedAt = updatedAt
            )
            return 1
        }

        override suspend fun markReady(id: String, readyAt: Long): Int {
            val existing = values[id] ?: return 0
            if (existing.status != "REVIEW" || !existing.privacyReviewed || !existing.rightsConfirmed) return 0
            values[id] = existing.copy(status = "READY", readyAt = readyAt, updatedAt = readyAt)
            return 1
        }

        override suspend fun markWithdrawn(id: String, withdrawnAt: Long): Int {
            val existing = values[id] ?: return 0
            if (existing.status != "READY") return 0
            values[id] = existing.copy(
                status = "WITHDRAWN",
                withdrawnAt = withdrawnAt,
                updatedAt = withdrawnAt
            )
            return 1
        }
    }
}
