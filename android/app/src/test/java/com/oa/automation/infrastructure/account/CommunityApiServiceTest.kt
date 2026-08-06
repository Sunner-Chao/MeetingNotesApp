package com.oa.automation.infrastructure.account

import com.oa.automation.domain.model.PublishedPost
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommunityApiServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: AccountApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = AccountApiService()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun createDraftUsesLocalPostIdAsIdempotencyKey() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"remote-1","client_snapshot_id":"local-1","status":"private_draft","moderation_status":"not_submitted"}"""
                )
        )
        val post = PublishedPost(
            id = "local-1",
            journeyId = "journey-1",
            journeyEditionId = "edition-1",
            versionNumber = 1,
            sourceEditionVersion = 2,
            title = "研学记录",
            content = "脱敏后的正文",
            privacyReviewed = true,
            rightsConfirmed = true,
            redactedCoordinateCount = 1
        )

        val result = service.createCommunityDraft(
            endpoint = server.url("/api").toString(),
            token = "session-token",
            post = post
        ).getOrThrow()
        val request = server.takeRequest()

        assertEquals("/api/account/community/drafts", request.path)
        assertEquals("Bearer session-token", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"client_snapshot_id\":\"local-1\""))
        assertEquals("remote-1", result.id)
        assertEquals("private_draft", result.status)
    }

    @Test
    fun publishAndWithdrawUseRemotePostId() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"remote-1","client_snapshot_id":"local-1","status":"published","moderation_status":"pending"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"remote-1","client_snapshot_id":"local-1","status":"withdrawn","moderation_status":"pending"}"""
            )
        )
        val endpoint = server.url("/api").toString()
        service.publishCommunityPost(endpoint, "session-token", "remote-1").getOrThrow()
        service.withdrawCommunityPost(endpoint, "session-token", "remote-1").getOrThrow()

        assertEquals("/api/account/community/posts/remote-1/publish", server.takeRequest().path)
        assertEquals("/api/account/community/posts/remote-1/withdraw", server.takeRequest().path)
    }
}
