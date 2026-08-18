package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.CommunitySyncOperation
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.infrastructure.community.CommunitySyncEnqueuer
import com.oa.automation.infrastructure.db.CommunitySyncOutboxDao
import com.oa.automation.infrastructure.db.CommunitySyncOutboxEntity
import com.oa.automation.infrastructure.db.PublishedPostDao
import com.oa.automation.infrastructure.db.PublishedPostEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunitySyncRepositoryImplTest {
    @Test
    fun `ready post is enqueued once and publish changes desired operation`() = runBlocking {
        val posts = FakePublishedPostDao(
            PublishedPostEntity(
                id = "post-1",
                journeyId = "journey-1",
                journeyEditionId = "edition-1",
                versionNumber = 1,
                sourceEditionVersion = 1,
                title = "研学",
                content = "正文",
                status = PublishedPostStatus.READY.name,
                visibility = "PRIVATE_PREVIEW",
                aiAssisted = true,
                privacyReviewed = true,
                rightsConfirmed = true,
                redactedCoordinateCount = 0,
                createdAt = 1L,
                updatedAt = 2L,
                readyAt = 2L,
                withdrawnAt = null
            )
        )
        val outbox = FakeOutboxDao()
        val enqueued = mutableListOf<String>()
        val repository = CommunitySyncRepositoryImpl(
            outbox,
            posts,
            CommunitySyncEnqueuer { enqueued += it }
        )

        val first = repository.enqueueUpload("post-1").getOrThrow()
        val second = repository.enqueueUpload("post-1").getOrThrow()
        val published = repository.requestPublish("post-1").getOrThrow()

        assertEquals(CommunitySyncOperation.UPLOAD, first.operation)
        assertEquals(first.postId, second.postId)
        assertEquals(CommunitySyncOperation.PUBLISH, published.operation)
        assertEquals(listOf("post-1", "post-1", "post-1"), enqueued)
        assertTrue(outbox.value!!.attemptCount == 0)
    }

    private class FakeOutboxDao : CommunitySyncOutboxDao {
        var value: CommunitySyncOutboxEntity? = null
        private val state = MutableStateFlow<CommunitySyncOutboxEntity?>(null)

        override fun observeAll(): Flow<List<CommunitySyncOutboxEntity>> =
            MutableStateFlow(listOfNotNull(value))

        override suspend fun upsert(entity: CommunitySyncOutboxEntity) {
            value = entity
            state.value = entity
        }

        override suspend fun findByPostId(postId: String): CommunitySyncOutboxEntity? = value

        override fun observe(postId: String): Flow<CommunitySyncOutboxEntity?> = state

        override suspend fun updateIntent(postId: String, operation: String, status: String, updatedAt: Long): Int {
            val current = value ?: return 0
            value = current.copy(operation = operation, status = status, lastError = null, updatedAt = updatedAt)
            state.value = value
            return 1
        }

        override suspend fun markRunning(postId: String, status: String, attemptCount: Int, updatedAt: Long): Int = 0

        override suspend fun markRemoteState(postId: String, status: String, remotePostId: String?, updatedAt: Long): Int = 0

        override suspend fun markFailed(postId: String, attemptCount: Int, lastError: String, updatedAt: Long): Int = 0
    }

    private class FakePublishedPostDao(
        private val value: PublishedPostEntity
    ) : PublishedPostDao {
        override fun observeAll(): Flow<List<PublishedPostEntity>> = MutableStateFlow(listOf(value))
        override suspend fun insert(entity: PublishedPostEntity) = Unit
        override suspend fun findLatest(journeyId: String): PublishedPostEntity? = value
        override fun observeLatest(journeyId: String): Flow<PublishedPostEntity?> = MutableStateFlow(value)
        override suspend fun findById(id: String): PublishedPostEntity? = value.takeIf { it.id == id }
        override suspend fun updateReview(id: String, privacyReviewed: Boolean, rightsConfirmed: Boolean, updatedAt: Long): Int = 0
        override suspend fun updateMetadata(
            id: String,
            destination: String,
            travelDate: String,
            travelDays: Int,
            tags: List<String>,
            pois: List<String>,
            updatedAt: Long
        ): Int = 0
        override suspend fun markReady(id: String, readyAt: Long): Int = 0
        override suspend fun markWithdrawn(id: String, withdrawnAt: Long): Int = 0
    }
}
