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
import java.io.IOException
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
}

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
        503 -> "账户服务暂时不可用"
        else -> detail.ifBlank { "账户服务请求失败（HTTP $code）" }
    }
}
