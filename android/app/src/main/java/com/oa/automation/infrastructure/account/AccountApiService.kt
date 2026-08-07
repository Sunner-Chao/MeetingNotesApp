package com.oa.automation.infrastructure.account

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.oa.automation.domain.model.AccountPlan
import com.oa.automation.domain.model.AccountProfile
import com.oa.automation.domain.model.AccountSessionCredentials
import com.oa.automation.domain.model.AuthSession
import com.oa.automation.domain.model.RechargeOrder
import com.oa.automation.domain.model.SocialAuthProvider
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.CommunityPostPage
import com.oa.automation.domain.model.CommunityAvailability
import com.oa.automation.domain.model.CommunityModerationItem
import com.oa.automation.domain.model.CommunityReport
import com.oa.automation.domain.model.CommunityComment
import com.oa.automation.domain.model.CommunityDeleteResult
import com.oa.automation.domain.model.CommunityInteractionState
import com.oa.automation.domain.model.CommunityCommentReport
import com.oa.automation.domain.model.CommunityCommentReportQueueItem
import com.oa.automation.domain.model.CommunityOperationsSummary
import com.oa.automation.domain.model.CommunityCollection
import com.oa.automation.domain.model.CommunityCollectionAdminDetail
import com.oa.automation.domain.model.CommunityCollectionBatchResult
import com.oa.automation.domain.model.CommunityCollectionDetail
import com.oa.automation.domain.model.CommunityCollectionOperationsSummary
import com.oa.automation.domain.model.CommunityCollectionPage
import com.oa.automation.domain.model.CommunityCollectionPost
import com.oa.automation.domain.model.CommunityCollectionRemoval
import com.oa.automation.domain.model.MyCommunityPost
import com.oa.automation.domain.model.PublicCommunityPost
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class AccountApiService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {
    data class CommunityPostResponse(
        val id: String,
        @com.google.gson.annotations.SerializedName("client_snapshot_id") val clientSnapshotId: String,
        val status: String,
        @com.google.gson.annotations.SerializedName("moderation_status") val moderationStatus: String
    )

    data class CommunityMediaResponse(
        val id: String,
        @com.google.gson.annotations.SerializedName("original_received_bytes") val originalReceivedBytes: Long,
        @com.google.gson.annotations.SerializedName("original_total_bytes") val originalTotalBytes: Long,
        @com.google.gson.annotations.SerializedName("thumbnail_received_bytes") val thumbnailReceivedBytes: Long,
        @com.google.gson.annotations.SerializedName("thumbnail_total_bytes") val thumbnailTotalBytes: Long,
        val status: String
    )

    suspend fun communityAvailability(
        endpoint: String
    ): Result<CommunityAvailability> = request(
        endpoint = endpoint,
        path = "community/status",
        method = "GET"
    ) { body -> gson.fromJson(body, CommunityAvailability::class.java) }

    suspend fun createCommunityDraft(
        endpoint: String,
        token: String,
        post: PublishedPost
    ): Result<CommunityPostResponse> = request(
        endpoint = endpoint,
        path = "account/community/drafts",
        token = token,
        method = "POST",
        jsonBody = gson.toJson(
            mapOf(
                "client_snapshot_id" to post.id,
                "journey_id" to post.journeyId,
                "journey_edition_id" to post.journeyEditionId,
                "source_edition_version" to post.sourceEditionVersion,
                "title" to post.title,
                "content" to post.content,
                "ai_assisted" to post.aiAssisted,
                "redacted_coordinate_count" to post.redactedCoordinateCount,
                "privacy_reviewed" to post.privacyReviewed,
                "rights_confirmed" to post.rightsConfirmed,
                "destination" to post.destination,
                "travel_date" to post.travelDate,
                "travel_days" to post.travelDays,
                "stage_titles" to post.stageTitles,
                "tags" to post.tags,
                "pois" to post.pois
            )
        )
    ) { body -> gson.fromJson(body, CommunityPostResponse::class.java) }

    suspend fun publishCommunityPost(
        endpoint: String,
        token: String,
        postId: String
    ): Result<CommunityPostResponse> = request(
        endpoint = endpoint,
        path = "account/community/posts/$postId/publish",
        token = token,
        method = "POST",
        jsonBody = "{}"
    ) { body -> gson.fromJson(body, CommunityPostResponse::class.java) }

    suspend fun withdrawCommunityPost(
        endpoint: String,
        token: String,
        postId: String
    ): Result<CommunityPostResponse> = request(
        endpoint = endpoint,
        path = "account/community/posts/$postId/withdraw",
        token = token,
        method = "POST",
        jsonBody = "{}"
    ) { body -> gson.fromJson(body, CommunityPostResponse::class.java) }

    suspend fun publicCommunityPosts(
        endpoint: String,
        cursor: String? = null,
        limit: Int = 20,
        query: String = "",
        destination: String = "",
        tag: String = "",
        poi: String = "",
        minDays: Int = 0,
        maxDays: Int = 0,
        hasMedia: Boolean = false
    ): Result<CommunityPostPage<PublicCommunityPost>> = request(
        endpoint = endpoint,
        path = communityPublicListPath(
            cursor = cursor,
            limit = limit,
            query = query,
            destination = destination,
            tag = tag,
            poi = poi,
            minDays = minDays,
            maxDays = maxDays,
            hasMedia = hasMedia
        ),
        method = "GET"
    ) { body ->
        gson.fromJson(
            body,
            object : TypeToken<CommunityPostPage<PublicCommunityPost>>() {}.type
        )
    }

    suspend fun publicCommunityCollections(
        endpoint: String,
        cursor: String? = null,
        limit: Int = 20,
        destination: String = "",
        theme: String = ""
    ): Result<CommunityCollectionPage> = request(
        endpoint = endpoint,
        path = communityListPath("community/collections", cursor, limit) + buildString {
            destination.trim().takeIf { it.isNotEmpty() }?.let {
                append("&destination=${encodeQueryValue(it)}")
            }
            theme.trim().takeIf { it.isNotEmpty() }?.let {
                append("&theme=${encodeQueryValue(it)}")
            }
        },
        method = "GET"
    ) { body ->
        gson.fromJson(body, CommunityCollectionPage::class.java)
    }

    suspend fun publicCommunityCollection(
        endpoint: String,
        collectionId: String,
        cursor: String? = null,
        limit: Int = 20
    ): Result<CommunityCollectionDetail> = request(
        endpoint = endpoint,
        path = communityListPath("community/collections/$collectionId", cursor, limit),
        method = "GET"
    ) { body -> gson.fromJson(body, CommunityCollectionDetail::class.java) }

    suspend fun publicCommunityPost(
        endpoint: String,
        postId: String
    ): Result<PublicCommunityPost> = request(
        endpoint = endpoint,
        path = "community/posts/$postId",
        method = "GET"
    ) { body -> gson.fromJson(body, PublicCommunityPost::class.java) }

    suspend fun myCommunityPosts(
        endpoint: String,
        token: String,
        cursor: String? = null,
        limit: Int = 20
    ): Result<CommunityPostPage<MyCommunityPost>> = request(
        endpoint = endpoint,
        path = communityListPath("account/community/posts", cursor, limit),
        token = token,
        method = "GET"
    ) { body ->
        gson.fromJson(
            body,
            object : TypeToken<CommunityPostPage<MyCommunityPost>>() {}.type
        )
    }

    suspend fun reportCommunityPost(
        endpoint: String,
        token: String,
        postId: String,
        category: String,
        reason: String = ""
    ): Result<CommunityReport> = request(
        endpoint = endpoint,
        path = "account/community/posts/$postId/report",
        token = token,
        method = "POST",
        jsonBody = gson.toJson(mapOf("category" to category, "reason" to reason))
    ) { body -> gson.fromJson(body, CommunityReport::class.java) }

    suspend fun communityInteractions(
        endpoint: String,
        token: String,
        postId: String
    ): Result<CommunityInteractionState> = request(
        endpoint = endpoint,
        path = "account/community/posts/$postId/interactions",
        token = token,
        method = "GET"
    ) { body -> gson.fromJson(body, CommunityInteractionState::class.java) }

    suspend fun toggleCommunityLike(
        endpoint: String,
        token: String,
        postId: String
    ): Result<CommunityInteractionState> = request(
        endpoint = endpoint,
        path = "account/community/posts/$postId/like",
        token = token,
        method = "POST",
        jsonBody = "{}"
    ) { body -> gson.fromJson(body, CommunityInteractionState::class.java) }

    suspend fun toggleCommunityBookmark(
        endpoint: String,
        token: String,
        postId: String
    ): Result<CommunityInteractionState> = request(
        endpoint = endpoint,
        path = "account/community/posts/$postId/bookmark",
        token = token,
        method = "POST",
        jsonBody = "{}"
    ) { body -> gson.fromJson(body, CommunityInteractionState::class.java) }

    suspend fun publicCommunityComments(
        endpoint: String,
        postId: String,
        cursor: String? = null,
        limit: Int = 50
    ): Result<CommunityPostPage<CommunityComment>> = request(
        endpoint = endpoint,
        path = communityListPath("community/posts/$postId/comments", cursor, limit),
        method = "GET"
    ) { body ->
        gson.fromJson(body, object : TypeToken<CommunityPostPage<CommunityComment>>() {}.type)
    }

    suspend fun accountCommunityComments(
        endpoint: String,
        token: String,
        postId: String,
        cursor: String? = null,
        limit: Int = 50
    ): Result<CommunityPostPage<CommunityComment>> = request(
        endpoint = endpoint,
        path = communityListPath("account/community/posts/$postId/comments", cursor, limit),
        token = token,
        method = "GET"
    ) { body ->
        gson.fromJson(body, object : TypeToken<CommunityPostPage<CommunityComment>>() {}.type)
    }

    suspend fun createCommunityComment(
        endpoint: String,
        token: String,
        postId: String,
        content: String
    ): Result<CommunityComment> = request(
        endpoint = endpoint,
        path = "account/community/posts/$postId/comments",
        token = token,
        method = "POST",
        jsonBody = gson.toJson(mapOf("content" to content))
    ) { body -> gson.fromJson(body, CommunityComment::class.java) }

    suspend fun deleteCommunityComment(
        endpoint: String,
        token: String,
        commentId: String
    ): Result<CommunityDeleteResult> = request(
        endpoint = endpoint,
        path = "account/community/comments/$commentId",
        token = token,
        method = "DELETE"
    ) { body -> gson.fromJson(body, CommunityDeleteResult::class.java) }

    suspend fun reportCommunityComment(
        endpoint: String,
        token: String,
        commentId: String,
        category: String,
        reason: String = ""
    ): Result<CommunityCommentReport> = request(
        endpoint = endpoint,
        path = "account/community/comments/$commentId/report",
        token = token,
        method = "POST",
        jsonBody = gson.toJson(mapOf("category" to category, "reason" to reason))
    ) { body -> gson.fromJson(body, CommunityCommentReport::class.java) }

    suspend fun bookmarkedCommunityPosts(
        endpoint: String,
        token: String,
        cursor: String? = null,
        limit: Int = 20
    ): Result<CommunityPostPage<PublicCommunityPost>> = request(
        endpoint = endpoint,
        path = communityListPath("account/community/bookmarks", cursor, limit),
        token = token,
        method = "GET"
    ) { body ->
        gson.fromJson(body, object : TypeToken<CommunityPostPage<PublicCommunityPost>>() {}.type)
    }

    suspend fun adminCommunityCommentReports(
        endpoint: String,
        token: String,
        status: String = "open",
        cursor: String? = null,
        limit: Int = 20
    ): Result<CommunityPostPage<CommunityCommentReportQueueItem>> = request(
        endpoint = endpoint,
        path = communityListPath("account/community/comment-reports", cursor, limit) +
            "&status=" + URLEncoder.encode(status, Charsets.UTF_8.name()),
        token = token,
        method = "GET"
    ) { body ->
        gson.fromJson(body, object : TypeToken<CommunityPostPage<CommunityCommentReportQueueItem>>() {}.type)
    }

    suspend fun resolveCommunityCommentReport(
        endpoint: String,
        token: String,
        reportId: String,
        decision: String
    ): Result<CommunityDeleteResult> = request(
        endpoint = endpoint,
        path = "account/community/comment-reports/$reportId",
        token = token,
        method = "POST",
        jsonBody = gson.toJson(mapOf("decision" to decision))
    ) { body -> gson.fromJson(body, CommunityDeleteResult::class.java) }

    suspend fun adminCommunityOperationsSummary(
        endpoint: String,
        token: String,
        hours: Int = 24
    ): Result<CommunityOperationsSummary> = request(
        endpoint = endpoint,
        path = "account/community/operations-summary?hours=${hours.coerceIn(1, 168)}",
        token = token,
        method = "GET"
    ) { body -> gson.fromJson(body, CommunityOperationsSummary::class.java) }

    suspend fun adminCommunityModerationQueue(
        endpoint: String,
        token: String,
        status: String = "pending",
        cursor: String? = null,
        limit: Int = 20
    ): Result<CommunityPostPage<CommunityModerationItem>> = request(
        endpoint = endpoint,
        path = "${communityListPath("account/community/moderation", cursor, limit)}&status=${URLEncoder.encode(status, Charsets.UTF_8.name())}",
        token = token,
        method = "GET"
    ) { body ->
        gson.fromJson(
            body,
            object : TypeToken<CommunityPostPage<CommunityModerationItem>>() {}.type
        )
    }

    suspend fun adminCommunityCollections(
        endpoint: String,
        token: String,
        status: String = "all",
        cursor: String? = null,
        limit: Int = 20
    ): Result<CommunityPostPage<CommunityCollection>> = request(
        endpoint = endpoint,
        path = communityListPath("account/community/collections", cursor, limit) +
            "&status=" + URLEncoder.encode(status, Charsets.UTF_8.name()),
        token = token,
        method = "GET"
    ) { body ->
        gson.fromJson(body, object : TypeToken<CommunityPostPage<CommunityCollection>>() {}.type)
    }

    suspend fun adminCommunityCollection(
        endpoint: String,
        token: String,
        collectionId: String
    ): Result<CommunityCollectionAdminDetail> = request(
        endpoint = endpoint,
        path = "account/community/collections/$collectionId",
        token = token,
        method = "GET"
    ) { body -> gson.fromJson(body, CommunityCollectionAdminDetail::class.java) }

    suspend fun adminCommunityCollectionOperationsSummary(
        endpoint: String,
        token: String
    ): Result<CommunityCollectionOperationsSummary> = request(
        endpoint = endpoint,
        path = "account/community/collection-operations-summary",
        token = token,
        method = "GET"
    ) { body -> gson.fromJson(body, CommunityCollectionOperationsSummary::class.java) }

    suspend fun createCommunityCollection(
        endpoint: String,
        token: String,
        title: String,
        description: String = "",
        destination: String = "",
        theme: String = "",
        displayOrder: Int = 0
    ): Result<CommunityCollection> = request(
        endpoint = endpoint,
        path = "account/community/collections",
        token = token,
        method = "POST",
        jsonBody = gson.toJson(
            mapOf(
                "title" to title,
                "description" to description,
                "destination" to destination,
                "theme" to theme,
                "display_order" to displayOrder
            )
        )
    ) { body -> gson.fromJson(body, CommunityCollection::class.java) }

    suspend fun updateCommunityCollection(
        endpoint: String,
        token: String,
        collectionId: String,
        title: String,
        description: String = "",
        destination: String = "",
        theme: String = "",
        displayOrder: Int = 0
    ): Result<CommunityCollection> = request(
        endpoint = endpoint,
        path = "account/community/collections/$collectionId",
        token = token,
        method = "PUT",
        jsonBody = gson.toJson(
            mapOf(
                "title" to title,
                "description" to description,
                "destination" to destination,
                "theme" to theme,
                "display_order" to displayOrder
            )
        )
    ) { body -> gson.fromJson(body, CommunityCollection::class.java) }

    suspend fun setCommunityCollectionStatus(
        endpoint: String,
        token: String,
        collectionId: String,
        status: String
    ): Result<CommunityCollection> = request(
        endpoint = endpoint,
        path = "account/community/collections/$collectionId/status",
        token = token,
        method = "POST",
        jsonBody = gson.toJson(mapOf("status" to status))
    ) { body -> gson.fromJson(body, CommunityCollection::class.java) }

    suspend fun addCommunityCollectionPost(
        endpoint: String,
        token: String,
        collectionId: String,
        postId: String,
        position: Int = 0,
        curationNote: String = ""
    ): Result<CommunityCollectionPost> = request(
        endpoint = endpoint,
        path = "account/community/collections/$collectionId/posts/$postId",
        token = token,
        method = "PUT",
        jsonBody = gson.toJson(
            mapOf("position" to position, "curation_note" to curationNote)
        )
    ) { body -> gson.fromJson(body, CommunityCollectionPost::class.java) }

    suspend fun batchAddCommunityCollectionPosts(
        endpoint: String,
        token: String,
        collectionId: String,
        assignments: List<Triple<String, Int, String>>
    ): Result<CommunityCollectionBatchResult> = request(
        endpoint = endpoint,
        path = "account/community/collections/$collectionId/posts/batch",
        token = token,
        method = "PUT",
        jsonBody = gson.toJson(
            mapOf(
                "items" to assignments.map { (postId, position, note) ->
                    mapOf(
                        "post_id" to postId,
                        "position" to position,
                        "curation_note" to note
                    )
                }
            )
        )
    ) { body -> gson.fromJson(body, CommunityCollectionBatchResult::class.java) }

    suspend fun setCommunityCollectionCover(
        endpoint: String,
        token: String,
        collectionId: String,
        postId: String?
    ): Result<CommunityCollection> = request(
        endpoint = endpoint,
        path = "account/community/collections/$collectionId/cover",
        token = token,
        method = "PUT",
        jsonBody = gson.toJson(mapOf("post_id" to postId))
    ) { body -> gson.fromJson(body, CommunityCollection::class.java) }

    suspend fun removeCommunityCollectionPost(
        endpoint: String,
        token: String,
        collectionId: String,
        postId: String
    ): Result<CommunityCollectionRemoval> = request(
        endpoint = endpoint,
        path = "account/community/collections/$collectionId/posts/$postId",
        token = token,
        method = "DELETE"
    ) { body -> gson.fromJson(body, CommunityCollectionRemoval::class.java) }

    suspend fun moderateCommunityPost(
        endpoint: String,
        token: String,
        postId: String,
        decision: String,
        reason: String = ""
    ): Result<CommunityPostResponse> = request(
        endpoint = endpoint,
        path = "account/community/moderation/$postId",
        token = token,
        method = "POST",
        jsonBody = gson.toJson(mapOf("decision" to decision, "reason" to reason))
    ) { body -> gson.fromJson(body, CommunityPostResponse::class.java) }

    suspend fun createCommunityMedia(
        endpoint: String,
        token: String,
        postId: String,
        clientMediaId: String,
        displayName: String,
        mimeType: String,
        originalBytes: Long,
        originalSha256: String,
        thumbnailBytes: Long,
        thumbnailSha256: String
    ): Result<CommunityMediaResponse> = request(
        endpoint = endpoint,
        path = "account/community/posts/$postId/media",
        token = token,
        method = "POST",
        jsonBody = gson.toJson(
            mapOf(
                "client_media_id" to clientMediaId,
                "display_name" to displayName,
                "mime_type" to mimeType,
                "original_bytes" to originalBytes,
                "original_sha256" to originalSha256,
                "thumbnail_bytes" to thumbnailBytes,
                "thumbnail_sha256" to thumbnailSha256
            )
        )
    ) { body -> gson.fromJson(body, CommunityMediaResponse::class.java) }

    suspend fun uploadCommunityMediaChunk(
        endpoint: String,
        token: String,
        postId: String,
        mediaId: String,
        variant: String,
        start: Long,
        total: Long,
        bytes: ByteArray
    ): Result<CommunityMediaResponse> = requestBytes(
        endpoint = endpoint,
        path = "account/community/posts/$postId/media/$mediaId/$variant",
        token = token,
        bytes = bytes,
        contentType = "application/octet-stream",
        headers = mapOf(
            "Content-Range" to "bytes $start-${start + bytes.size - 1}/$total",
            "X-Chunk-SHA256" to bytes.sha256()
        )
    ) { body -> gson.fromJson(body, CommunityMediaResponse::class.java) }

    suspend fun login(endpoint: String, username: String, password: String): Result<AuthSession> =
        postCredentials(endpoint, "auth/login", username, password)

    suspend fun register(endpoint: String, username: String, password: String): Result<AuthSession> =
        postCredentials(endpoint, "auth/register", username, password)

    suspend fun authProviders(endpoint: String): Result<List<SocialAuthProvider>> = request(
        endpoint = endpoint,
        path = "auth/providers",
        method = "GET"
    ) { body ->
        gson.fromJson(body, object : TypeToken<List<SocialAuthProvider>>() {}.type)
    }

    suspend fun profile(endpoint: String, token: String): Result<AccountProfile> = request(
        endpoint = endpoint,
        path = "account/me",
        token = token,
        method = "GET"
    ) { body -> gson.fromJson(body, AccountProfile::class.java) }

    suspend fun updateProfile(
        endpoint: String,
        token: String,
        displayName: String,
        avatarDataUrl: String?
    ): Result<AccountProfile> = request(
        endpoint = endpoint,
        path = "account/me",
        token = token,
        method = "PATCH",
        jsonBody = gson.toJson(
            mapOf(
                "display_name" to displayName,
                "avatar_data_url" to avatarDataUrl
            )
        )
    ) { body -> gson.fromJson(body, AccountProfile::class.java) }

    suspend fun refreshSession(
        endpoint: String,
        token: String
    ): Result<AccountSessionCredentials> = request(
        endpoint = endpoint,
        path = "account/session",
        token = token,
        method = "GET"
    ) { body -> gson.fromJson(body, AccountSessionCredentials::class.java) }

    suspend fun plans(endpoint: String, token: String): Result<List<AccountPlan>> = request(
        endpoint = endpoint,
        path = "account/plans",
        token = token,
        method = "GET"
    ) { body ->
        gson.fromJson(body, object : TypeToken<List<AccountPlan>>() {}.type)
    }

    suspend fun orders(endpoint: String, token: String): Result<List<RechargeOrder>> = request(
        endpoint = endpoint,
        path = "account/orders",
        token = token,
        method = "GET"
    ) { body ->
        gson.fromJson(body, object : TypeToken<List<RechargeOrder>>() {}.type)
    }

    suspend fun createOrder(
        endpoint: String,
        token: String,
        planCode: String
    ): Result<RechargeOrder> = request(
        endpoint = endpoint,
        path = "account/orders",
        token = token,
        method = "POST",
        jsonBody = gson.toJson(mapOf("plan_code" to planCode))
    ) { body -> gson.fromJson(body, RechargeOrder::class.java) }

    suspend fun adminOrders(
        endpoint: String,
        token: String,
        status: String = "pending"
    ): Result<List<RechargeOrder>> = request(
        endpoint = endpoint,
        path = "admin/accounts/orders?status=$status",
        token = token,
        method = "GET"
    ) { body ->
        gson.fromJson(body, object : TypeToken<List<RechargeOrder>>() {}.type)
    }

    suspend fun adminUsers(
        endpoint: String,
        token: String
    ): Result<List<AccountProfile>> = request(
        endpoint = endpoint,
        path = "admin/accounts/users",
        token = token,
        method = "GET"
    ) { body ->
        gson.fromJson(body, object : TypeToken<List<AccountProfile>>() {}.type)
    }

    suspend fun setUserEnabled(
        endpoint: String,
        token: String,
        userId: String,
        enabled: Boolean
    ): Result<AccountProfile> = request(
        endpoint = endpoint,
        path = "admin/accounts/users/$userId",
        token = token,
        method = "PATCH",
        jsonBody = gson.toJson(mapOf("enabled" to enabled))
    ) { body -> gson.fromJson(body, AccountProfile::class.java) }

    suspend fun deleteUser(
        endpoint: String,
        token: String,
        userId: String
    ): Result<Unit> = request(
        endpoint = endpoint,
        path = "admin/accounts/users/$userId",
        token = token,
        method = "DELETE"
    ) { Unit }

    suspend fun approveOrder(
        endpoint: String,
        token: String,
        orderId: String
    ): Result<RechargeOrder> = request(
        endpoint = endpoint,
        path = "admin/accounts/orders/$orderId/approve",
        token = token,
        method = "POST",
        jsonBody = "{}"
    ) { body -> gson.fromJson(body, RechargeOrder::class.java) }

    suspend fun rejectOrder(
        endpoint: String,
        token: String,
        orderId: String
    ): Result<RechargeOrder> = request(
        endpoint = endpoint,
        path = "admin/accounts/orders/$orderId/reject",
        token = token,
        method = "POST",
        jsonBody = "{}"
    ) { body -> gson.fromJson(body, RechargeOrder::class.java) }

    suspend fun logout(endpoint: String, token: String): Result<Unit> = request(
        endpoint = endpoint,
        path = "account/logout",
        token = token,
        method = "POST",
        jsonBody = "{}"
    ) { Unit }

    private suspend fun postCredentials(
        endpoint: String,
        path: String,
        username: String,
        password: String
    ): Result<AuthSession> = request(
        endpoint = endpoint,
        path = path,
        method = "POST",
        jsonBody = gson.toJson(mapOf("username" to username, "password" to password))
    ) { body -> gson.fromJson(body, AuthSession::class.java) }

    private fun communityListPath(basePath: String, cursor: String?, limit: Int): String {
        val boundedLimit = limit.coerceIn(1, 50)
        val cursorQuery = cursor?.takeIf { it.isNotBlank() }?.let {
            "&cursor=${URLEncoder.encode(it, Charsets.UTF_8.name())}"
        }.orEmpty()
        return "$basePath?limit=$boundedLimit$cursorQuery"
    }

    private fun communityPublicListPath(
        cursor: String?,
        limit: Int,
        query: String,
        destination: String,
        tag: String,
        poi: String,
        minDays: Int,
        maxDays: Int,
        hasMedia: Boolean
    ): String {
        val suffix = buildList {
            query.trim().takeIf { it.isNotEmpty() }?.let { add("q=${encodeQueryValue(it)}") }
            destination.trim().takeIf { it.isNotEmpty() }?.let { add("destination=${encodeQueryValue(it)}") }
            tag.trim().takeIf { it.isNotEmpty() }?.let { add("tag=${encodeQueryValue(it)}") }
            poi.trim().takeIf { it.isNotEmpty() }?.let { add("poi=${encodeQueryValue(it)}") }
            minDays.coerceIn(0, 31).takeIf { it > 0 }?.let { add("min_days=$it") }
            maxDays.coerceIn(0, 31).takeIf { it > 0 }?.let { add("max_days=$it") }
            if (hasMedia) add("has_media=true")
        }.joinToString("&")
        return communityListPath("community/posts", cursor, limit).let {
            if (suffix.isEmpty()) it else "$it&$suffix"
        }
    }

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private suspend fun <T> request(
        endpoint: String,
        path: String,
        token: String? = null,
        method: String,
        jsonBody: String? = null,
        parser: (String) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = endpoint.trim().trimEnd('/')
            require(baseUrl.isNotBlank()) { "账户服务地址未配置" }
            val builder = Request.Builder().url("$baseUrl/$path")
            if (!token.isNullOrBlank()) builder.addHeader("Authorization", "Bearer $token")
            when (method) {
                "GET" -> builder.get()
                "POST" -> builder.post(
                    (jsonBody ?: "{}").toRequestBody("application/json".toMediaType())
                )
                "PUT" -> builder.put(
                    (jsonBody ?: "{}").toRequestBody("application/json".toMediaType())
                )
                "PATCH" -> builder.patch(
                    (jsonBody ?: "{}").toRequestBody("application/json".toMediaType())
                )
                "DELETE" -> builder.delete()
                else -> error("Unsupported HTTP method: $method")
            }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(response.toAccountError(body))
                parser(body)
            }
        }
    }

    private suspend fun <T> requestBytes(
        endpoint: String,
        path: String,
        token: String,
        bytes: ByteArray,
        contentType: String,
        headers: Map<String, String>,
        parser: (String) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = endpoint.trim().trimEnd('/')
            require(baseUrl.isNotBlank()) { "账户服务地址未配置" }
            val requestBuilder = Request.Builder()
                .url("$baseUrl/$path")
                .addHeader("Authorization", "Bearer $token")
            headers.forEach { (name, value) -> requestBuilder.addHeader(name, value) }
            requestBuilder.put(bytes.toRequestBody(contentType.toMediaType()))
            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(response.toAccountError(body))
                parser(body)
            }
        }
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

private fun Response.toAccountError(body: String): String {
    val detail = runCatching {
        JsonParser.parseString(body).asJsonObject.get("detail")?.asString.orEmpty()
    }.getOrDefault("").take(200)
    val retryAfter = header("Retry-After")?.toIntOrNull()?.coerceAtLeast(1)
    return when (code) {
        400 -> detail.ifBlank { "请求内容不正确" }
        401 -> detail.ifBlank { "用户名、密码或登录会话无效" }
        403 -> detail.ifBlank { "当前账号没有操作权限" }
        404 -> detail.ifBlank { "请求的账户资源不存在" }
        409 -> detail.ifBlank { "账号或订单状态冲突" }
        429 -> detail.ifBlank {
            retryAfter?.let { "操作太频繁，请在 $it 秒后重试" } ?: "操作太频繁，请稍后重试"
        }
        422 -> detail.ifBlank { "社区快照字段不符合要求" }
        503 -> detail.ifBlank { "账户服务暂时不可用" }
        else -> detail.ifBlank { "账户服务请求失败（HTTP $code）" }
    }
}
