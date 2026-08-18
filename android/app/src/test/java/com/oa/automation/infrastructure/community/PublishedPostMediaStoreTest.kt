package com.oa.automation.infrastructure.community

import android.content.ContextWrapper
import com.oa.automation.infrastructure.db.PublishedPostDao
import com.oa.automation.infrastructure.db.PublishedPostEntity
import com.oa.automation.infrastructure.db.PublishedPostMediaDao
import com.oa.automation.infrastructure.db.PublishedPostMediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedPostMediaStoreTest {
    @Test
    fun selectionCanChangeDuringReviewButLocksAfterReady() = runBlocking {
        val postDao = FakePublishedPostDao(reviewPost())
        val mediaDao = FakePublishedPostMediaDao(media())
        val store = PublishedPostMediaStore(ContextWrapper(null), mediaDao, postDao)

        val excluded = store.setIncluded("post-1", "media-1", included = false).getOrThrow()
        assertEquals(PublishedPostMediaStore.EXCLUDED, excluded.single().status)

        val restored = store.setIncluded("post-1", "media-1", included = true).getOrThrow()
        assertEquals(PublishedPostMediaStore.PENDING, restored.single().status)

        postDao.post = reviewPost(status = "READY")
        assertTrue(store.setIncluded("post-1", "media-1", included = false).isFailure)
        assertEquals(PublishedPostMediaStore.PENDING, mediaDao.items.getValue("media-1").status)
    }

    private fun reviewPost(status: String = "REVIEW") = PublishedPostEntity(
        id = "post-1",
        journeyId = "journey-1",
        journeyEditionId = "edition-1",
        versionNumber = 1,
        sourceEditionVersion = 1,
        title = "研学记录",
        content = "正文",
        status = status,
        visibility = "PRIVATE_PREVIEW",
        aiAssisted = true,
        privacyReviewed = false,
        rightsConfirmed = false,
        redactedCoordinateCount = 0,
        createdAt = 1L,
        updatedAt = 1L,
        readyAt = null,
        withdrawnAt = null
    )

    private fun media() = PublishedPostMediaEntity(
        id = "media-1",
        postId = "post-1",
        sourceAttachmentId = "attachment-1",
        displayName = "现场照片.jpg",
        originalPath = "/unused/original.jpg",
        thumbnailPath = "/unused/thumbnail.jpg",
        mimeType = "image/jpeg",
        originalBytes = 100L,
        originalSha256 = "original",
        thumbnailBytes = 10L,
        thumbnailSha256 = "thumbnail",
        remoteMediaId = null,
        status = PublishedPostMediaStore.PENDING,
        lastError = null,
        createdAt = 1L,
        updatedAt = 1L
    )

    private class FakePublishedPostMediaDao(initial: PublishedPostMediaEntity) : PublishedPostMediaDao {
        val items = linkedMapOf(initial.id to initial)

        override suspend fun upsertAll(items: List<PublishedPostMediaEntity>) {
            items.forEach { this.items[it.id] = it }
        }

        override suspend fun findByPostId(postId: String): List<PublishedPostMediaEntity> =
            items.values.filter { it.postId == postId }

        override suspend fun findById(id: String): PublishedPostMediaEntity? = items[id]

        override suspend fun markRemote(
            id: String,
            remoteMediaId: String,
            status: String,
            updatedAt: Long
        ): Int = 0

        override suspend fun markFailed(
            id: String,
            status: String,
            lastError: String,
            updatedAt: Long
        ): Int = 0

        override suspend fun updateSelection(
            postId: String,
            id: String,
            status: String,
            updatedAt: Long
        ): Int {
            val existing = items[id] ?: return 0
            if (existing.postId != postId) return 0
            items[id] = existing.copy(status = status, lastError = null, updatedAt = updatedAt)
            return 1
        }
    }

    private class FakePublishedPostDao(
        var post: PublishedPostEntity
    ) : PublishedPostDao {
        override fun observeAll(): Flow<List<PublishedPostEntity>> = MutableStateFlow(listOf(post))
        override suspend fun insert(entity: PublishedPostEntity) = Unit

        override suspend fun findLatest(journeyId: String): PublishedPostEntity? = post

        override fun observeLatest(journeyId: String): Flow<PublishedPostEntity?> =
            MutableStateFlow(post)

        override suspend fun findById(id: String): PublishedPostEntity? = post.takeIf { it.id == id }

        override suspend fun updateReview(
            id: String,
            privacyReviewed: Boolean,
            rightsConfirmed: Boolean,
            updatedAt: Long
        ): Int = 0

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
