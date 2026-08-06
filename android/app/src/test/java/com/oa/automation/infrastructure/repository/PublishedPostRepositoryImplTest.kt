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

    private class FakePublishedPostDao : PublishedPostDao {
        private val values = linkedMapOf<String, PublishedPostEntity>()

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
