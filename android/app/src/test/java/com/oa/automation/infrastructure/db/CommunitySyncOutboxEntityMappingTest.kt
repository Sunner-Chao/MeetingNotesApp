package com.oa.automation.infrastructure.db

import com.oa.automation.domain.model.CommunitySyncOperation
import com.oa.automation.domain.model.CommunitySyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CommunitySyncOutboxEntityMappingTest {
    @Test
    fun `outbox mapping preserves idempotency key and remote state`() {
        val entity = CommunitySyncOutboxEntity(
            postId = "local-post-1",
            operation = CommunitySyncOperation.PUBLISH.name,
            status = CommunitySyncStatus.PUBLISHED.name,
            remotePostId = "remote-post-1",
            attemptCount = 2,
            lastError = null,
            createdAt = 100L,
            updatedAt = 200L
        )

        val state = entity.toDomain()

        assertEquals("local-post-1", state.postId)
        assertEquals(CommunitySyncOperation.PUBLISH, state.operation)
        assertEquals(CommunitySyncStatus.PUBLISHED, state.status)
        assertEquals("remote-post-1", state.remotePostId)
        assertEquals(2, state.attemptCount)
    }
}
