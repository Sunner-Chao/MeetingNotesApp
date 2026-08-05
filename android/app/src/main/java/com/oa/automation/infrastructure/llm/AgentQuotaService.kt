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
    @SerializedName("allowed_providers") val allowedProviders: List<String>,
    @SerializedName("expires_at") val expiresAt: Long?
) {
    val usedFraction: Float
        get() = if (requestLimit <= 0) 0f else (requestsUsed.toFloat() / requestLimit).coerceIn(0f, 1f)
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
                    ?: throw IOException("额度服务返回空数据")
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
        403 -> "当前令牌没有额度查询权限"
        429 -> "AI 处理额度已用尽"
        503 -> "额度服务暂时不可用"
        else -> "额度查询失败（HTTP $code）$suffix"
    }
}
