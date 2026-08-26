package com.oa.automation.infrastructure.llm

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.oa.automation.domain.model.LLMConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class AgentQuota(
    val label: String,
    @SerializedName("request_limit") val requestLimit: Int,
    @SerializedName("requests_used") val requestsUsed: Int,
    @SerializedName("requests_remaining") val requestsRemaining: Int,
    @SerializedName("ai_credits_granted") private val billedAiCreditsGranted: Int? = null,
    @SerializedName("ai_credits_used") private val billedAiCreditsUsed: Int? = null,
    @SerializedName("ai_credits_remaining") private val billedAiCreditsRemaining: Int? = null,
    @SerializedName("points_granted") private val billedPointsGranted: Int? = null,
    @SerializedName("points_used") private val billedPointsUsed: Int? = null,
    @SerializedName("points_remaining") private val billedPointsRemaining: Int? = null,
    @SerializedName("allowed_providers") val allowedProviders: List<String>,
    @SerializedName("expires_at") val expiresAt: Long?
) {
    val aiCreditsGranted: Int get() = billedAiCreditsGranted ?: requestLimit
    val aiCreditsUsed: Int get() = billedAiCreditsUsed ?: requestsUsed
    val aiCreditsRemaining: Int get() = billedAiCreditsRemaining ?: requestsRemaining
    val pointsGranted: Int get() = billedPointsGranted ?: aiCreditsGranted
    val pointsUsed: Int get() = billedPointsUsed ?: aiCreditsUsed
    val pointsRemaining: Int get() = billedPointsRemaining ?: aiCreditsRemaining
    val usedFraction: Float
        get() = if (pointsGranted <= 0) 0f else {
            (pointsUsed.toFloat() / pointsGranted).coerceIn(0f, 1f)
        }
}

class AgentQuotaService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {
    suspend fun fetch(config: LLMConfig): Result<AgentQuota> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = config.agentEndpoint.trim().trimEnd('/')
            require(endpoint.isNotBlank()) { "Agent 服务地址未配置" }
            val token = config.agentAccessToken?.trim().orEmpty()
            require(token.isNotBlank()) { "Agent 访问令牌未配置" }

            val request = Request.Builder()
                .url("$endpoint/quota")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(response.toQuotaError(body))
                gson.fromJson(body, AgentQuota::class.java)
                    ?: throw IOException("积分服务返回空数据")
            }
        }
    }
}

private fun Response.toQuotaError(body: String): String {
    val detail = runCatching {
        JsonParser.parseString(body).asJsonObject.get("detail")?.asString.orEmpty()
    }.getOrDefault("").take(160)
    val suffix = detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
    return when (code) {
        401 -> "访问令牌无效或已过期"
        403 -> "当前令牌没有积分查询权限"
        429 -> "积分不足"
        503 -> "积分服务暂时不可用"
        else -> "积分查询失败（HTTP $code）$suffix"
    }
}
