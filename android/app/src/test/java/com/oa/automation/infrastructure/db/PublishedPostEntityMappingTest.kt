package com.oa.automation.infrastructure.db

import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.PublishedPostStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PublishedPostEntityMappingTest {
    @Test
    fun `published post round trip preserves privacy and lifecycle fields`() {
        val post = PublishedPost(
            id = "post-1",
            journeyId = "journey-1",
            journeyEditionId = "edition-2",
            versionNumber = 2,
            sourceEditionVersion = 3,
            title = "城市更新研学",
            content = "# 城市更新研学",
            status = PublishedPostStatus.READY,
            privacyReviewed = true,
            rightsConfirmed = true,
            redactedCoordinateCount = 2,
            createdAt = 100L,
            updatedAt = 200L,
            readyAt = 200L
        )

        assertEquals(post, post.toEntity().toDomain())
    }
}
