package com.oa.automation.infrastructure.llm

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.infrastructure.network.awaitResponse
import kotlinx.coroutines.CancellationException
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
    private val config: LLMConfig,
    private val accountAccessToken: String? = null
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
        attachments: List<AgentAttachment>,
        usageContext: AgentUsageContext?
    ): Result<ReportData> = withContext(Dispatchers.IO) {
        val payload = AgentTaskRequest(
            provider = config.agentProvider.requestValue,
            operation = "generate_report",
            codexReasoningEffort = config.codexReasoningEffort.requestValue,
            claudeEffort = config.claudeReasoningEffort.requestValue,
            transcript = transcript,
            templateName = template.selectedName,
            templateContent = template.content,
            meetingId = usageContext?.meetingId,
            usageKey = usageContext?.usageKey,
            attachmentManifest = buildAttachmentManifest(attachments)
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
            codexReasoningEffort = config.codexReasoningEffort.requestValue,
            claudeEffort = config.claudeReasoningEffort.requestValue,
            messages = messages,
            attachmentManifest = buildAttachmentManifest(attachments)
        )
        execute(payload, attachments).map { it.text }
    }

    override fun getEngineType(): LLMEngineType = LLMEngineType.AGENT_GATEWAY

    override fun getDisplayName(): String = "智悟云端模型 (${config.agentProvider.displayName})"

    override fun isAvailable(): Boolean =
        config.agentEndpoint.isNotBlank() && !credential().isNullOrBlank()

    private fun credential(): String? =
        accountAccessToken?.trim()?.takeIf { it.isNotBlank() }
            ?: config.agentAccessToken?.trim()?.takeIf { it.isNotBlank() }

    private suspend fun execute(
        payload: AgentTaskRequest,
        attachments: List<AgentAttachment>
    ): Result<AgentTaskResponse> {
        return try {
            val endpoint = config.agentEndpoint.trim().trimEnd('/')
            require(endpoint.isNotBlank()) { "Agent 服务地址未配置" }
            val token = credential().orEmpty()
            require(token.isNotBlank()) { "请先登录账户" }

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

            val parsed = client.newCall(request).awaitResponse().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(response.toAgentError(responseBody))
                }
                parseResponse(responseBody)
            }
            Result.success(parsed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun buildAttachmentManifest(
        attachments: List<AgentAttachment>
    ): List<AgentAttachmentManifestEntry> = attachments
        .filter { it.file.isFile }
        .mapIndexed { index, attachment ->
            AgentAttachmentManifestEntry(
                index = index + 1,
                displayName = attachment.displayName,
                capturedAt = attachment.capturedAt,
                latitude = attachment.latitude,
                longitude = attachment.longitude,
                accuracyMeters = attachment.accuracyMeters,
                locationCapturedAt = attachment.locationCapturedAt,
                locationSource = attachment.locationSource,
                recordingMarkerId = attachment.recordingMarkerId,
                markerTimestampMs = attachment.markerTimestampMs,
                markerTranscriptAnchor = attachment.markerTranscriptAnchor
            )
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
        @SerializedName("model_reasoning_effort")
        val codexReasoningEffort: String,
        @SerializedName("effort")
        val claudeEffort: String,
        val transcript: String? = null,
        val templateName: String? = null,
        val templateContent: String? = null,
        @SerializedName("meeting_id") val meetingId: String? = null,
        @SerializedName("usage_key") val usageKey: String? = null,
        val messages: List<ChatMessage>? = null,
        val attachmentManifest: List<AgentAttachmentManifestEntry> = emptyList()
    )

    data class AgentAttachmentManifestEntry(
        val index: Int,
        val displayName: String,
        val capturedAt: Long? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val accuracyMeters: Float? = null,
        val locationCapturedAt: Long? = null,
        val locationSource: String? = null,
        val recordingMarkerId: String? = null,
        val markerTimestampMs: Long? = null,
        val markerTranscriptAnchor: String? = null
    )

    data class AgentTaskResponse(
        val text: String = "",
        val report: ReportData? = null
    )

    companion object {
        fun testConnection(config: LLMConfig, accountAccessToken: String? = null): Result<Boolean> = runCatching {
            require(config.agentEndpoint.isNotBlank()) { "Agent 服务地址未配置" }
            val token = accountAccessToken?.trim()?.takeIf { it.isNotBlank() }
                ?: config.agentAccessToken?.trim()?.takeIf { it.isNotBlank() }
            require(!token.isNullOrBlank()) { "请先登录账户" }
            val request = Request.Builder()
                .url("${config.agentEndpoint.trim().trimEnd('/')}/health")
                .addHeader("Authorization", "Bearer $token")
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
        503 -> "智悟云端模型暂时不可用，请稍后重试$suffix"
        else -> "Agent 服务请求失败（HTTP $code）$suffix"
    }
}
