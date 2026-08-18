package com.oa.automation.infrastructure.community

import com.oa.automation.domain.model.AccountProfile
import com.oa.automation.domain.model.AuthSession
import com.oa.automation.domain.model.CommunitySyncOperation
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.infrastructure.db.CommunitySyncOutboxDao
import com.oa.automation.infrastructure.db.CommunitySyncOutboxEntity
import com.oa.automation.infrastructure.db.PublishedPostDao
import com.oa.automation.infrastructure.db.PublishedPostEntity
import com.oa.automation.infrastructure.db.PublishedPostMediaDao
import com.oa.automation.infrastructure.db.PublishedPostMediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CommunitySyncProcessorAcceptanceTest {
    @Test
    fun `transient failures remain durable across processor recreation and end at retry budget`() = runBlocking {
        val server = MockWebServer()
        repeat(6) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setBody("{\"detail\":\"network temporarily unavailable\"}")
            )
        }
        server.start()
        try {
            val store = ProcessorStore(post = readyPost())
            store.outbox = pendingOutbox(CommunitySyncOperation.UPLOAD)
            val endpoint = server.url("/api").toString()

            repeat(6) { attempt ->
                val result = newProcessor(store, endpoint).run("post-1", attempt)
                if (attempt < 5) {
                    assertTrue("attempt $attempt should retry", result is ProcessingResult.Retry)
                } else {
                    assertTrue("attempt $attempt should be terminal", result is ProcessingResult.Failure)
                }
                assertEquals(attempt + 1, store.outbox!!.attemptCount)
                assertEquals("FAILED", store.outbox!!.status)
            }
            assertEquals(6, server.requestCount)
            assertTrue(store.outbox!!.lastError.orEmpty().isNotBlank())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `resumes a large original from server offset and completes thumbnail`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            "{\"id\":\"remote-1\",\"client_snapshot_id\":\"post-1\",\"status\":\"private_draft\",\"moderation_status\":\"pending\"}"
        ))
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            "{\"id\":\"media-1\",\"original_received_bytes\":524288,\"original_total_bytes\":524291,\"thumbnail_received_bytes\":0,\"thumbnail_total_bytes\":3,\"status\":\"uploading\"}"
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            "{\"id\":\"media-1\",\"original_received_bytes\":524291,\"original_total_bytes\":524291,\"thumbnail_received_bytes\":0,\"thumbnail_total_bytes\":3,\"status\":\"uploading\"}"
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            "{\"id\":\"media-1\",\"original_received_bytes\":524291,\"original_total_bytes\":524291,\"thumbnail_received_bytes\":3,\"thumbnail_total_bytes\":3,\"status\":\"ready\"}"
        ))
        server.start()

        val original = ByteArray(524_291) { (it % 251).toByte() }
        val directory = Files.createTempDirectory("meeting-notes-sync")
        val originalPath = directory.resolve("original.bin")
        val thumbnailPath = directory.resolve("thumbnail.bin")
        Files.write(originalPath, original)
        Files.write(thumbnailPath, "abc".encodeToByteArray())
        try {
            val store = ProcessorStore(post = readyPost())
            store.outbox = pendingOutbox(CommunitySyncOperation.UPLOAD)
            store.media += PublishedPostMediaEntity(
                id = "media-local-1",
                postId = "post-1",
                sourceAttachmentId = "attachment-1",
                displayName = "现场照片.jpg",
                originalPath = originalPath.toString(),
                thumbnailPath = thumbnailPath.toString(),
                mimeType = "image/jpeg",
                originalBytes = original.size.toLong(),
                originalSha256 = "original-hash",
                thumbnailBytes = 3,
                thumbnailSha256 = "thumbnail-hash",
                remoteMediaId = null,
                status = "PENDING",
                lastError = null,
                createdAt = 1L,
                updatedAt = 1L
            )

            val endpoint = server.url("/api").toString()
            val result = newProcessor(store, endpoint).run("post-1", 0)
            assertTrue(result is ProcessingResult.Success)
            assertEquals("PRIVATE_DRAFT", store.outbox!!.status)
            assertEquals("remote-1", store.outbox!!.remotePostId)
            assertEquals("READY", store.media.single().status)

            // The first request is the draft and the second is the media manifest.
            assertEquals("/api/account/community/drafts", server.takeRequest().path)
            assertEquals("/api/account/community/posts/remote-1/media", server.takeRequest().path)
            val originalChunk = server.takeRequest()
            assertEquals("/api/account/community/posts/remote-1/media/media-1/original", originalChunk.path)
            assertEquals("bytes 524288-524290/524291", originalChunk.getHeader("Content-Range"))
            assertEquals(original.copyOfRange(524_288, 524_291).toList(), originalChunk.body.readByteArray().toList())
            val thumbnailChunk = server.takeRequest()
            assertEquals("bytes 0-2/3", thumbnailChunk.getHeader("Content-Range"))
            assertEquals("abc", thumbnailChunk.body.readUtf8())
        } finally {
            Files.deleteIfExists(originalPath)
            Files.deleteIfExists(thumbnailPath)
            Files.deleteIfExists(directory)
            server.shutdown()
        }
    }

    private fun newProcessor(store: ProcessorStore, endpoint: String) = CommunitySyncProcessor(
        outboxDao = FakeOutboxDao(store),
        publishedPostDao = FakePublishedPostDao(store),
        publishedPostMediaDao = FakeMediaDao(store),
        accountApiService = AccountApiService(),
        authSessionProvider = { session() },
        endpointProvider = { endpoint }
    )

    private fun pendingOutbox(operation: CommunitySyncOperation) = CommunitySyncOutboxEntity(
        postId = "post-1",
        operation = operation.name,
        status = "PENDING",
        remotePostId = null,
        attemptCount = 0,
        lastError = null,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun readyPost() = PublishedPostEntity(
        id = "post-1",
        journeyId = "journey-1",
        journeyEditionId = "edition-1",
        versionNumber = 1,
        sourceEditionVersion = 1,
        title = "城市更新研学",
        content = "公开预览正文",
        status = PublishedPostStatus.READY.name,
        visibility = "PRIVATE_PREVIEW",
        aiAssisted = true,
        privacyReviewed = true,
        rightsConfirmed = true,
        redactedCoordinateCount = 1,
        createdAt = 1L,
        updatedAt = 1L,
        readyAt = 1L,
        withdrawnAt = null
    )

    private fun session() = AuthSession(
        accessToken = "session-token",
        agentAccessToken = "agent-token",
        tokenType = "bearer",
        expiresAt = Long.MAX_VALUE,
        user = AccountProfile(
            id = "user-1",
            username = "tester",
            role = "user",
            isAdmin = false,
            enabled = true,
            vipEnabled = false,
            constructionLogsUnlocked = false,
            createdAt = 1L
        )
    )

    private class ProcessorStore(val post: PublishedPostEntity) {
        var outbox: CommunitySyncOutboxEntity? = null
        val media = mutableListOf<PublishedPostMediaEntity>()
    }

    private class FakeOutboxDao(private val store: ProcessorStore) : CommunitySyncOutboxDao {
        private val state = MutableStateFlow(store.outbox)

        override fun observeAll(): Flow<List<CommunitySyncOutboxEntity>> =
            MutableStateFlow(listOfNotNull(store.outbox))

        override suspend fun upsert(entity: CommunitySyncOutboxEntity) { store.outbox = entity; state.value = entity }
        override suspend fun findByPostId(postId: String) = store.outbox?.takeIf { it.postId == postId }
        override fun observe(postId: String): Flow<CommunitySyncOutboxEntity?> = state

        override suspend fun updateIntent(postId: String, operation: String, status: String, updatedAt: Long): Int = 0

        override suspend fun markRunning(postId: String, status: String, attemptCount: Int, updatedAt: Long): Int {
            val current = store.outbox ?: return 0
            store.outbox = current.copy(status = status, attemptCount = attemptCount, lastError = null, updatedAt = updatedAt)
            state.value = store.outbox
            return 1
        }

        override suspend fun markRemoteState(postId: String, status: String, remotePostId: String?, updatedAt: Long): Int {
            val current = store.outbox ?: return 0
            store.outbox = current.copy(status = status, remotePostId = remotePostId, lastError = null, updatedAt = updatedAt)
            state.value = store.outbox
            return 1
        }

        override suspend fun markFailed(postId: String, attemptCount: Int, lastError: String, updatedAt: Long): Int {
            val current = store.outbox ?: return 0
            store.outbox = current.copy(status = "FAILED", attemptCount = attemptCount, lastError = lastError, updatedAt = updatedAt)
            state.value = store.outbox
            return 1
        }
    }

    private class FakePublishedPostDao(private val store: ProcessorStore) : PublishedPostDao {
        override fun observeAll(): Flow<List<PublishedPostEntity>> = MutableStateFlow(listOf(store.post))
        override suspend fun insert(entity: PublishedPostEntity) = Unit
        override suspend fun findLatest(journeyId: String) = store.post
        override fun observeLatest(journeyId: String): Flow<PublishedPostEntity?> = MutableStateFlow(store.post)
        override suspend fun findById(id: String) = store.post.takeIf { it.id == id }
        override suspend fun updateReview(id: String, privacyReviewed: Boolean, rightsConfirmed: Boolean, updatedAt: Long) = 0
        override suspend fun updateMetadata(id: String, destination: String, travelDate: String, travelDays: Int, tags: List<String>, pois: List<String>, updatedAt: Long) = 0
        override suspend fun markReady(id: String, readyAt: Long) = 0
        override suspend fun markWithdrawn(id: String, withdrawnAt: Long) = 0
    }

    private class FakeMediaDao(private val store: ProcessorStore) : PublishedPostMediaDao {
        override suspend fun upsertAll(items: List<PublishedPostMediaEntity>) { store.media += items }
        override suspend fun findByPostId(postId: String) = store.media.filter { it.postId == postId }
        override suspend fun findById(id: String) = store.media.firstOrNull { it.id == id }
        override suspend fun markRemote(id: String, remoteMediaId: String, status: String, updatedAt: Long): Int {
            val index = store.media.indexOfFirst { it.id == id }
            if (index < 0) return 0
            store.media[index] = store.media[index].copy(remoteMediaId = remoteMediaId, status = status, lastError = null, updatedAt = updatedAt)
            return 1
        }
        override suspend fun markFailed(id: String, status: String, lastError: String, updatedAt: Long): Int {
            val index = store.media.indexOfFirst { it.id == id }
            if (index < 0) return 0
            store.media[index] = store.media[index].copy(status = status, lastError = lastError, updatedAt = updatedAt)
            return 1
        }
        override suspend fun updateSelection(postId: String, id: String, status: String, updatedAt: Long) = 0
    }
}
