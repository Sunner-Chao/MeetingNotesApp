package com.oa.automation.infrastructure.llm

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.ReportTemplateConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Calls a server-owned Agent gateway. The mobile client never executes Codex or
 * Claude locally and never contains the credentials used by those CLIs.
 */
class AgentGatewayEngine(
    private val config: LLMConfig
) : LLMReportEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()
    private val gson = Gson()

    override suspend fun generateReport(
        transcript: String,
        template: ReportTemplateConfig,
        attachments: List<AgentAttachment>
    ): Result<ReportData> = withContext(Dispatchers.IO) {
        val payload = AgentTaskRequest(
            provider = config.agentProvider.requestValue,
            operation = "generate_report",
            transcript = transcript,
            templateName = template.selectedName,
            templateContent = template.content
        )
        execute(payload, attachments).map { response ->
            response.report?.copy(templateName = response.report.templateName.ifBlank { template.selectedName })
                ?: ReportData(rawContent = response.text, templateName = template.selectedName)
        }
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        attachments: List<AgentAttachment>
    ): Result<String> = withContext(Dispatchers.IO) {
        val payload = AgentTaskRequest(
            provider = config.agentProvider.requestValue,
            operation = "chat",
            messages = messages
        )
        execute(payload, attachments).map { it.text }
    }

    override fun getEngineType(): LLMEngineType = LLMEngineType.AGENT_GATEWAY

    override fun getDisplayName(): String = "云端 Agent (${config.agentProvider.displayName})"

    override fun isAvailable(): Boolean =
        config.agentEndpoint.isNotBlank() && !config.agentAccessToken.isNullOrBlank()

    private fun execute(
        payload: AgentTaskRequest,
        attachments: List<AgentAttachment>
    ): Result<AgentTaskResponse> = runCatching {
        val endpoint = config.agentEndpoint.trim().trimEnd('/')
        require(endpoint.isNotBlank()) { "Agent 服务地址未配置" }
        val token = config.agentAccessToken?.trim().orEmpty()
        require(token.isNotBlank()) { "Agent 访问令牌未配置" }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "request",
                null,
                gson.toJson(payload).toRequestBody("application/json".toMediaType())
            )
        attachments.filter { it.file.isFile }.forEach { attachment ->
            body.addFormDataPart(
                "attachments",
                attachment.displayName,
                attachment.file.asRequestBody(attachment.mimeType.toMediaType())
            )
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $token")
            .post(body.build())
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(response.toAgentError(responseBody))
            }
            parseResponse(responseBody)
        }
    }

    private fun parseResponse(body: String): AgentTaskResponse {
        val parsed = runCatching { gson.fromJson(body, AgentTaskResponse::class.java) }.getOrNull()
        if (parsed?.text?.isNotBlank() == true || parsed?.report != null) return parsed
        if (body.isBlank()) throw IOException("Agent gateway returned an empty response")
        return AgentTaskResponse(text = body)
    }

    data class AgentTaskRequest(
        val provider: String,
        val operation: String,
        val transcript: String? = null,
        val templateName: String? = null,
        val templateContent: String? = null,
        val messages: List<ChatMessage>? = null
    )

    data class AgentTaskResponse(
        val text: String = "",
        val report: ReportData? = null
    )

    companion object {
        fun testConnection(config: LLMConfig): Result<Boolean> = runCatching {
            require(config.agentEndpoint.isNotBlank()) { "Agent 服务地址未配置" }
            require(!config.agentAccessToken.isNullOrBlank()) { "Agent 访问令牌未配置" }
            val request = Request.Builder()
                .url("${config.agentEndpoint.trim().trimEnd('/')}/health")
                .addHeader("Authorization", "Bearer ${config.agentAccessToken}")
                .get()
                .build()
            val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Agent gateway returned HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val provider = JsonParser.parseString(body)
                    .asJsonObject
                    .getAsJsonObject("providers")
                    ?.getAsJsonObject(config.agentProvider.requestValue)
                if (provider?.get("authenticated")?.asBoolean != true) {
                    throw IOException("${config.agentProvider.displayName} 尚未在服务账号中登录")
                }
            }
            true
        }
    }
}

private fun Response.toAgentError(responseBody: String): String {
    val detail = runCatching {
        JsonParser.parseString(responseBody).asJsonObject.get("detail")?.asString.orEmpty()
    }.getOrDefault("").take(240)
    val suffix = detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
    return when (code) {
        400 -> "Agent 请求内容不受支持$suffix"
        401 -> "Agent 访问令牌无效或已过期"
        403 -> "当前令牌没有所选 Agent 的使用权限"
        429 -> "Agent 请求额度已用尽或服务队列已满$suffix"
        503 -> "云端 Agent 暂时不可用，请稍后重试$suffix"
        else -> "Agent 服务请求失败（HTTP $code）$suffix"
    }
}
