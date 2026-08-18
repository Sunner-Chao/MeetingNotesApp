package com.oa.automation.ui.screen.community

import com.oa.automation.domain.model.CommunityReview
import com.oa.automation.domain.model.CommunitySyncOperation
import com.oa.automation.domain.model.CommunitySyncState
import com.oa.automation.domain.model.CommunitySyncStatus
import com.oa.automation.domain.model.MyCommunityPost
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.PublishedPostStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CommunityPublishingWorkbenchTest {
    @Test
    fun `status text follows local and sync lifecycle`() {
        val review = CommunityPublishingItem(post(PublishedPostStatus.REVIEW), null)
        val failed = CommunityPublishingItem(
            post(PublishedPostStatus.READY),
            sync(CommunitySyncStatus.FAILED)
        )
        val published = CommunityPublishingItem(
            post(PublishedPostStatus.READY),
            sync(CommunitySyncStatus.PUBLISHED)
        )
        val approvedRemote = remotePost("remote-1")

        assertEquals("待检查", publishingStatusText(review))
        assertEquals("同步失败", publishingStatusText(failed))
        assertEquals("审核中", publishingStatusText(published))
        assertEquals("已发布", publishingStatusText(published, approvedRemote))
    }

    @Test
    fun `remote post represented by local sync state is not duplicated`() {
        val local = CommunityPublishingItem(
            post(PublishedPostStatus.READY),
            sync(CommunitySyncStatus.PUBLISHED, remotePostId = "remote-1")
        )
        val remote = listOf(remotePost("remote-1"), remotePost("remote-2"))

        assertEquals(listOf("remote-2"), visibleRemoteCommunityPosts(remote, listOf(local)).map { it.id })
    }

    private fun post(status: PublishedPostStatus) = PublishedPost(
        id = "local-1",
        journeyId = "journey-1",
        journeyEditionId = "edition-1",
        versionNumber = 1,
        sourceEditionVersion = 1,
        title = "城市研学",
        content = "正文",
        status = status
    )

    private fun sync(status: CommunitySyncStatus, remotePostId: String? = null) = CommunitySyncState(
        postId = "local-1",
        operation = CommunitySyncOperation.PUBLISH,
        status = status,
        remotePostId = remotePostId,
        createdAt = 1L,
        updatedAt = 2L
    )

    private fun remotePost(id: String) = MyCommunityPost(
        id = id,
        title = "城市研学",
        content = "正文",
        status = "published",
        moderationStatus = "approved",
        review = CommunityReview(status = "approved"),
        updatedAt = 2L
    )
}
