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
    fun availabilityReportsReadOnlyRolloutState() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"read_enabled":true,"write_enabled":false}""")
        )

        val availability = service.communityAvailability(
            server.url("/api").toString()
        ).getOrThrow()

        assertTrue(availability.readEnabled)
        assertEquals(false, availability.writeEnabled)
        assertEquals("/api/community/status", server.takeRequest().path)
    }

    @Test
    fun serviceUnavailablePreservesCommunityWriteDisabledDetail() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail":"社区写入暂时关闭，本地内容已保留"}""")
        )

        val error = service.toggleCommunityLike(
            server.url("/api").toString(),
            "session-token",
            "post-1"
        ).exceptionOrNull()

        assertEquals("社区写入暂时关闭，本地内容已保留", error?.message)
    }

    @Test
    fun publicGatewayCredentialErrorIsLocalized() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail":"Missing or invalid API credentials"}""")
        )

        val error = service.publicCommunityPosts(
            server.url("/api").toString()
        ).exceptionOrNull()

        assertEquals("服务鉴权状态异常，请稍后重试", error?.message)
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
            redactedCoordinateCount = 1,
            destination = "苏州",
            travelDate = "2026-08-06",
            travelDays = 2,
            stageTitles = listOf("园林研学"),
            tags = listOf("建筑"),
            pois = listOf("拙政园")
        )

        val result = service.createCommunityDraft(
            endpoint = server.url("/api").toString(),
            token = "session-token",
            post = post
        ).getOrThrow()
        val request = server.takeRequest()

        assertEquals("/api/account/community/drafts", request.path)
        assertEquals("Bearer session-token", request.getHeader("Authorization"))
        val requestBody = request.body.readUtf8()
        assertTrue(requestBody.contains("\"client_snapshot_id\":\"local-1\""))
        assertTrue(requestBody.contains("\"destination\":\"苏州\""))
        assertTrue(requestBody.contains("\"travel_days\":2"))
        assertTrue(requestBody.contains("\"stage_titles\":[\"园林研学\"]"))
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

    @Test
    fun publicAndMyPostListsUseSeparatedVisibilityRoutes() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":"public-1","title":"研学笔记","content":"已审核正文","ai_assisted":true,"redacted_coordinate_count":1,"published_at":10,"author_label":"研学同行者"}],"next_cursor":null}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":"mine-1","title":"我的笔记","content":"待审正文","status":"published","moderation_status":"pending","review":{"status":"pending","reason":""},"updated_at":11,"published_at":10}],"next_cursor":null}"""
            )
        )
        val endpoint = server.url("/api").toString()
        val publicPosts = service.publicCommunityPosts(endpoint).getOrThrow()
        val myPosts = service.myCommunityPosts(endpoint, "session-token").getOrThrow()

        assertEquals("研学笔记", publicPosts.items.single().title)
        assertEquals("/api/community/posts?limit=20", server.takeRequest().path)
        assertEquals("pending", myPosts.items.single().review.status)
        val myRequest = server.takeRequest()
        assertEquals("/api/account/community/posts?limit=20", myRequest.path)
        assertEquals("Bearer session-token", myRequest.getHeader("Authorization"))
    }

    @Test
    fun publicListEncodesStructuredSearchFilters() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[],"next_cursor":null,"facets":{"destinations":["苏州"],"tags":["园林"],"pois":["拙政园"]}}"""))

        val page = service.publicCommunityPosts(
            endpoint = server.url("/api").toString(),
            cursor = "cursor-1",
            query = "园林 研学",
            destination = "苏州",
            tag = "园林",
            poi = "拙政园",
            minDays = 1,
            maxDays = 3,
            hasMedia = true
        ).getOrThrow()

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.startsWith("/api/community/posts?"))
        assertTrue(path.contains("limit=20&cursor=cursor-1"))
        assertTrue(path.contains("q=%E5%9B%AD%E6%9E%97+%E7%A0%94%E5%AD%A6"))
        assertTrue(path.contains("destination=%E8%8B%8F%E5%B7%9E"))
        assertTrue(path.contains("tag=%E5%9B%AD%E6%9E%97"))
        assertTrue(path.contains("poi=%E6%8B%99%E6%94%BF%E5%9B%AD"))
        assertTrue(path.contains("min_days=1"))
        assertTrue(path.contains("max_days=3"))
        assertTrue(path.contains("has_media=true"))
        assertEquals(listOf("苏州"), page.facets?.destinations)
    }

    @Test
    fun interactionsAndCommentsUseAuthenticatedOwnerRoutes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"post_id":"post-1","liked":false,"bookmarked":false,"like_count":2,"comment_count":1}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"post_id":"post-1","liked":true,"bookmarked":false,"like_count":3,"comment_count":1}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"post_id":"post-1","liked":true,"bookmarked":true,"like_count":3,"comment_count":1}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[{"id":"comment-1","post_id":"post-1","content":"现场很有启发","author_label":"研学同行者","created_at":10,"can_delete":true}],"next_cursor":null}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"comment-2","post_id":"post-1","content":"建议预留讨论时间","author_label":"研学同行者","created_at":11,"can_delete":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"comment-2","status":"deleted"}"""))

        val endpoint = server.url("/api").toString()
        assertEquals(2, service.communityInteractions(endpoint, "session-token", "post-1").getOrThrow().likeCount)
        assertTrue(service.toggleCommunityLike(endpoint, "session-token", "post-1").getOrThrow().liked)
        assertTrue(service.toggleCommunityBookmark(endpoint, "session-token", "post-1").getOrThrow().bookmarked)
        assertTrue(service.accountCommunityComments(endpoint, "session-token", "post-1").getOrThrow().items.single().canDelete)
        assertEquals(
            "comment-2",
            service.createCommunityComment(endpoint, "session-token", "post-1", "建议预留讨论时间").getOrThrow().id
        )
        assertEquals("deleted", service.deleteCommunityComment(endpoint, "session-token", "comment-2").getOrThrow().status)

        assertEquals("/api/account/community/posts/post-1/interactions", server.takeRequest().path)
        assertEquals("/api/account/community/posts/post-1/like", server.takeRequest().path)
        assertEquals("/api/account/community/posts/post-1/bookmark", server.takeRequest().path)
        assertEquals("/api/account/community/posts/post-1/comments?limit=50", server.takeRequest().path)
        assertEquals("/api/account/community/posts/post-1/comments", server.takeRequest().path)
        assertEquals("/api/account/community/comments/comment-2", server.takeRequest().path)
    }

    @Test
    fun commentPagingReportsBookmarksAndAdminResolutionUseScopedRoutes() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":"comment-1","post_id":"post-1","content":"第一段","created_at":20,"can_delete":false}],"next_cursor":"comment-cursor"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":"comment-2","post_id":"post-1","content":"第二段","created_at":10,"can_delete":false}],"next_cursor":null}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"id":"comment-report-1","comment_id":"comment-2","category":"safety","reason":"需要核查","status":"open"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":"post-1","title":"收藏笔记","content":"公开正文","published_at":30}],"next_cursor":"bookmark-cursor"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":"comment-report-1","comment_id":"comment-2","post_id":"post-1","post_title":"收藏笔记","content":"第二段","comment_status":"visible","category":"safety","reason":"需要核查","status":"open"}],"next_cursor":null}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"comment-report-1","status":"resolved"}"""
            )
        )

        val endpoint = server.url("/api").toString()
        val firstPage = service.accountCommunityComments(endpoint, "session-token", "post-1", limit = 10).getOrThrow()
        assertEquals("comment-1", firstPage.items.single().id)
        assertEquals("comment-cursor", firstPage.nextCursor)
        val firstPageRequest = server.takeRequest()
        assertEquals("/api/account/community/posts/post-1/comments?limit=10", firstPageRequest.path)
        assertEquals("Bearer session-token", firstPageRequest.getHeader("Authorization"))

        val secondPage = service.accountCommunityComments(
            endpoint,
            "session-token",
            "post-1",
            cursor = firstPage.nextCursor,
            limit = 10
        ).getOrThrow()
        assertEquals("comment-2", secondPage.items.single().id)
        val secondPageRequest = server.takeRequest()
        assertEquals(
            "/api/account/community/posts/post-1/comments?limit=10&cursor=comment-cursor",
            secondPageRequest.path
        )
        assertEquals("Bearer session-token", secondPageRequest.getHeader("Authorization"))

        val report = service.reportCommunityComment(
            endpoint,
            "session-token",
            "comment-2",
            category = "safety",
            reason = "需要核查"
        ).getOrThrow()
        assertEquals("comment-report-1", report.id)
        val reportRequest = server.takeRequest()
        assertEquals("/api/account/community/comments/comment-2/report", reportRequest.path)
        assertEquals("Bearer session-token", reportRequest.getHeader("Authorization"))
        assertTrue(reportRequest.body.readUtf8().contains("\"category\":\"safety\""))

        val bookmarks = service.bookmarkedCommunityPosts(
            endpoint,
            "session-token",
            cursor = "bookmark-cursor",
            limit = 5
        ).getOrThrow()
        assertEquals("post-1", bookmarks.items.single().id)
        assertEquals("bookmark-cursor", bookmarks.nextCursor)
        val bookmarkRequest = server.takeRequest()
        assertEquals(
            "/api/account/community/bookmarks?limit=5&cursor=bookmark-cursor",
            bookmarkRequest.path
        )
        assertEquals("Bearer session-token", bookmarkRequest.getHeader("Authorization"))

        val reports = service.adminCommunityCommentReports(
            endpoint,
            "admin-token",
            status = "open",
            cursor = "report-cursor",
            limit = 7
        ).getOrThrow()
        assertEquals("comment-report-1", reports.items.single().id)
        val queueRequest = server.takeRequest()
        assertEquals(
            "/api/account/community/comment-reports?limit=7&cursor=report-cursor&status=open",
            queueRequest.path
        )
        assertEquals("Bearer admin-token", queueRequest.getHeader("Authorization"))

        val resolved = service.resolveCommunityCommentReport(
            endpoint,
            "admin-token",
            "comment-report-1",
            decision = "delete"
        ).getOrThrow()
        assertEquals("resolved", resolved.status)
        val resolveRequest = server.takeRequest()
        assertEquals("/api/account/community/comment-reports/comment-report-1", resolveRequest.path)
        assertEquals("Bearer admin-token", resolveRequest.getHeader("Authorization"))
        assertTrue(resolveRequest.body.readUtf8().contains("\"decision\":\"delete\""))
    }

    @Test
    fun createMediaAndUploadChunkUseAuthenticatedResumableProtocol() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"id":"media-1","original_received_bytes":0,"original_total_bytes":5,"thumbnail_received_bytes":0,"thumbnail_total_bytes":3,"status":"uploading"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"media-1","original_received_bytes":5,"original_total_bytes":5,"thumbnail_received_bytes":0,"thumbnail_total_bytes":3,"status":"uploading"}"""
            )
        )
        val endpoint = server.url("/api").toString()
        val created = service.createCommunityMedia(
            endpoint = endpoint,
            token = "session-token",
            postId = "post-1",
            clientMediaId = "local-media-1",
            displayName = "现场照片.jpg",
            mimeType = "image/jpeg",
            originalBytes = 5,
            originalSha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            thumbnailBytes = 3,
            thumbnailSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        ).getOrThrow()
        val manifestRequest = server.takeRequest()

        assertEquals("/api/account/community/posts/post-1/media", manifestRequest.path)
        assertEquals("Bearer session-token", manifestRequest.getHeader("Authorization"))
        assertTrue(manifestRequest.body.readUtf8().contains("\"client_media_id\":\"local-media-1\""))

        service.uploadCommunityMediaChunk(
            endpoint = endpoint,
            token = "session-token",
            postId = "post-1",
            mediaId = created.id,
            variant = "original",
            start = 0,
            total = 5,
            bytes = "hello".encodeToByteArray()
        ).getOrThrow()
        val uploadRequest = server.takeRequest()

        assertEquals("/api/account/community/posts/post-1/media/media-1/original", uploadRequest.path)
        assertEquals("PUT", uploadRequest.method)
        assertEquals("Bearer session-token", uploadRequest.getHeader("Authorization"))
        assertEquals("bytes 0-4/5", uploadRequest.getHeader("Content-Range"))
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            uploadRequest.getHeader("X-Chunk-SHA256")
        )
        assertEquals("hello", uploadRequest.body.readUtf8())
    }

    @Test
    fun reportUsesAuthenticatedPostAndModerationQueueUsesAdminBearer() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"id":"report-1","post_id":"post-1","category":"privacy","reason":"位置","status":"open"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":"post-1","title":"研学","content":"正文","published_at":10,"open_report_count":1,"reports":[{"category":"privacy","reason":"位置","created_at":9}]}],"next_cursor":null}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"post-1","client_snapshot_id":"local-1","status":"published","moderation_status":"pending"}"""
            )
        )
        val endpoint = server.url("/api").toString()
        val report = service.reportCommunityPost(
            endpoint,
            "session-token",
            "post-1",
            "privacy",
            "位置"
        ).getOrThrow()
        assertEquals("report-1", report.id)
        val reportRequest = server.takeRequest()
        assertEquals("/api/account/community/posts/post-1/report", reportRequest.path)
        assertEquals("Bearer session-token", reportRequest.getHeader("Authorization"))
        assertTrue(reportRequest.body.readUtf8().contains("\"category\":\"privacy\""))

        val queue = service.adminCommunityModerationQueue(
            endpoint,
            "admin-token",
            status = "reported",
            cursor = "moderation-cursor",
            limit = 9
        ).getOrThrow()
        assertEquals(1, queue.items.single().openReportCount)
        val queueRequest = server.takeRequest()
        assertEquals(
            "/api/account/community/moderation?limit=9&cursor=moderation-cursor&status=reported",
            queueRequest.path
        )
        assertEquals("Bearer admin-token", queueRequest.getHeader("Authorization"))

        service.moderateCommunityPost(endpoint, "admin-token", "post-1", "approved", "").getOrThrow()
        val moderationRequest = server.takeRequest()
        assertEquals("/api/account/community/moderation/post-1", moderationRequest.path)
        assertEquals("Bearer admin-token", moderationRequest.getHeader("Authorization"))
        assertTrue(moderationRequest.body.readUtf8().contains("\"decision\":\"approved\""))
    }

    @Test
    fun operationsSummaryAndRateLimitErrorsRemainCompact() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"window_hours":24,"generated_at":100,"allowed_action_count":18,"limited_action_count":2,"pending_post_count":3,"reported_post_count":1,"open_comment_report_count":4}"""
            )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "12")
                .setBody("{}")
        )
        val endpoint = server.url("/api").toString()

        val summary = service.adminCommunityOperationsSummary(
            endpoint,
            "admin-token",
            hours = 24
        ).getOrThrow()
        assertEquals(3, summary.pendingPostCount)
        assertEquals(4, summary.openCommentReportCount)
        assertEquals(2, summary.limitedActionCount)
        val summaryRequest = server.takeRequest()
        assertEquals("/api/account/community/operations-summary?hours=24", summaryRequest.path)
        assertEquals("Bearer admin-token", summaryRequest.getHeader("Authorization"))

        val limited = service.toggleCommunityLike(
            endpoint,
            "session-token",
            "post-1"
        ).exceptionOrNull()
        assertTrue(limited?.message.orEmpty().contains("12 秒后重试"))
        assertEquals("/api/account/community/posts/post-1/like", server.takeRequest().path)
    }
}
