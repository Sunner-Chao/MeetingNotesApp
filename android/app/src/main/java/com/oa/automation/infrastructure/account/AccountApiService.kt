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
                "rights_confirmed" to post.rightsConfirmed
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
        limit: Int = 20
    ): Result<CommunityPostPage<PublicCommunityPost>> = request(
        endpoint = endpoint,
        path = communityListPath("community/posts", cursor, limit),
        method = "GET"
    ) { body ->
        gson.fromJson(
            body,
            object : TypeToken<CommunityPostPage<PublicCommunityPost>>() {}.type
        )
    }

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
    return when (code) {
        400 -> detail.ifBlank { "请求内容不正确" }
        401 -> detail.ifBlank { "用户名、密码或登录会话无效" }
        403 -> detail.ifBlank { "当前账号没有操作权限" }
        404 -> detail.ifBlank { "请求的账户资源不存在" }
        409 -> detail.ifBlank { "账号或订单状态冲突" }
        422 -> detail.ifBlank { "社区快照字段不符合要求" }
        503 -> "账户服务暂时不可用"
        else -> detail.ifBlank { "账户服务请求失败（HTTP $code）" }
    }
}
