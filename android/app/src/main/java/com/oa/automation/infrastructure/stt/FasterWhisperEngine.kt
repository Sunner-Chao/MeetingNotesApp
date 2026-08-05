package com.oa.automation.infrastructure.stt

import com.google.gson.Gson
import com.oa.automation.domain.model.DiscoveredSTTServer
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.TencentAsrQuotaWarningLevel
import com.oa.automation.domain.model.TencentAsrBudgetPolicy
import com.oa.automation.domain.model.TencentAsrBudgetService
import com.oa.automation.domain.model.TencentAsrTierPolicy
import com.oa.automation.domain.model.TencentAsrUsage
import com.oa.automation.domain.model.TencentAsrUsageService
import com.oa.automation.domain.model.ProcessingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import com.oa.automation.locale.SimplifiedChineseText
import com.oa.automation.infrastructure.network.awaitResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.buffer
import java.net.InetAddress
import java.net.URI
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Faster-Whisper STT Engine
 * Communicates with a local Python FastAPI service running faster-whisper
 *
 * Expected Python service endpoints:
 * - POST /transcribe - Transcribe audio file
 *   Request: multipart/form-data with audio file
 *   Response: { "text": "...", "language": "zh" }
 */
class FasterWhisperEngine(
    private val config: STTConfig
) : SpeechToTextEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES) // Long recordings can take a while to transcribe.
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    private val gson = Gson()

    override suspend fun transcribe(
        audioFile: File,
        onProgress: (ProcessingProgress) -> Unit,
        meetingId: String?,
        archiveKey: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!audioFile.isFile || audioFile.length() <= 44L) {
            return@withContext Result.failure(IOException("录音文件为空或不可用"))
        }
        val apiToken = config.apiToken?.trim().orEmpty()
        if (apiToken.isBlank()) {
            return@withContext Result.failure(IOException("STT 访问令牌未配置"))
        }

        var lastProgress = -1
        val audioBody = ProgressRequestBody(audioFile.asRequestBody(audioFile.toMediaType())) { written, total ->
            val percent = if (total > 0L) 18 + ((written * 27L) / total).toInt() else 18
            if (percent >= lastProgress + 2 || written == total) {
                lastProgress = percent
                onProgress(
                    ProcessingProgress(
                        percent = percent.coerceIn(18, 45),
                        stage = if (written == total) "服务器正在生成最终稿" else "上传录音文件",
                        isIndeterminate = written == total
                    )
                )
            }
        }
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("language", config.language.requestValue)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioBody
            )
            .build()

        val requestBuilder = Request.Builder()
                .url("${config.localEndpoint.trim().trimEnd('/')}/transcribe")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $apiToken")
        meetingId?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.addHeader("X-Meeting-Id", it)
        }
        archiveKey?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.addHeader("X-Archive-Key", it)
        }
        executeTranscription(
            requestBuilder.build(),
            onProgress
        )
    }

    override suspend fun transcribeStreamSession(
        sessionId: String,
        onProgress: (ProcessingProgress) -> Unit
    ): Result<String> =
        withContext(Dispatchers.IO) {
            if (!sessionId.matches(Regex("^[0-9a-f]{32}$"))) {
                return@withContext Result.failure(IOException("流式转写会话标识无效"))
            }
            val apiToken = config.apiToken?.trim().orEmpty()
            if (apiToken.isBlank()) {
                return@withContext Result.failure(IOException("STT 访问令牌未配置"))
            }
            onProgress(ProcessingProgress(45, "服务器正在生成最终稿", isIndeterminate = true))
            executeTranscription(
                Request.Builder()
                    .url("${config.localEndpoint.trim().trimEnd('/')}/transcribe/stream/$sessionId")
                    .post(ByteArray(0).toRequestBody(null))
                    .addHeader("Authorization", "Bearer $apiToken")
                    .build(),
                onProgress
            )
        }

    private suspend fun executeTranscription(
        request: Request,
        onProgress: (ProcessingProgress) -> Unit
    ): Result<String> {
        return try {
            client.newCall(request).awaitResponse().use { response ->
                onProgress(ProcessingProgress(80, "接收最终识别结果"))
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Result.failure(response.toSttFailure(responseBody))
                }

                if (responseBody.isBlank()) {
                    return Result.failure(IOException("STT 服务返回了空响应"))
                }

                onProgress(ProcessingProgress(84, "解析最终识别结果"))
                val result = gson.fromJson(responseBody, STTResponse::class.java)
                val text = SimplifiedChineseText.normalize(result.text.orEmpty())
                if (text.isBlank()) {
                    Result.failure(IOException("未识别到有效语音，请确认麦克风收音后重试"))
                } else {
                    Result.success(text)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getEngineType(): STTEngineType = STTEngineType.FASTER_WHISPER

    override fun getDisplayName(): String {
        val host = runCatching { URI(config.localEndpoint).host.orEmpty() }.getOrDefault("")
        val isLocal = host.equals("localhost", ignoreCase = true) ||
            host.startsWith("127.") ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.matches(Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\..+"))
        return if (isLocal) "智悟本地模型" else "智悟远程模型"
    }

    override fun isAvailable(): Boolean {
        // In production, could do a health check ping
        return config.localEndpoint.isNotBlank()
    }

    /**
     * Response model for STT service
     */
    private data class STTResponse(
        val text: String?,
        val language: String?,
        val segments: List<Segment>? = null
    )

    private data class Segment(
        val start: Float,
        val end: Float,
        val text: String
    )
}

private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (written: Long, total: Long) -> Unit
) : RequestBody() {
    override fun contentType() = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var written = 0L
        val forwardingSink = object : okio.ForwardingSink(sink) {
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                onProgress(written, total)
            }
        }
        val bufferedSink = forwardingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }
}

private fun File.toMediaType() = when (extension.lowercase()) {
    "wav" -> "audio/wav".toMediaType()
    "m4a", "mp4" -> "audio/mp4".toMediaType()
    else -> "application/octet-stream".toMediaType()
}

/**
 * Simple HTTP client for STT service
 */
object STTServiceClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Test connection to STT service
     */
    fun testConnection(endpoint: String, apiToken: String?): Result<Unit> {
        return try {
            val normalizedEndpoint = endpoint.trim().trimEnd('/')
            if (normalizedEndpoint.isBlank()) {
                return Result.failure(IOException("STT 服务地址未配置"))
            }
            val token = apiToken?.trim().orEmpty()
            if (token.isBlank()) {
                return Result.failure(IOException("STT 访问令牌未配置"))
            }

            val healthRequest = Request.Builder()
                .url("$normalizedEndpoint/health")
                .get()
                .build()
            client.newCall(healthRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(response.toSttFailure(response.body?.string().orEmpty()))
                }
            }

            // /health is intentionally anonymous. This protected endpoint confirms
            // that the token used by recording/transcription is accepted as well.
            val authRequest = Request.Builder()
                .url("$normalizedEndpoint/debug/stream-events?limit=1")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(authRequest).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(response.toSttFailure(response.body?.string().orEmpty()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun fetchTencentAsrUsage(
        endpoint: String,
        apiToken: String?,
        forceRefresh: Boolean = false
    ): Result<TencentAsrUsage> {
        return try {
            val normalizedEndpoint = endpoint.trim().trimEnd('/')
            if (normalizedEndpoint.isBlank()) {
                return Result.failure(IOException("STT 服务地址未配置"))
            }
            val token = apiToken?.trim().orEmpty()
            if (token.isBlank()) {
                return Result.failure(IOException("STT 访问令牌未配置"))
            }
            val usageUrl = "$normalizedEndpoint/cloud-asr/usage"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("force", forceRefresh.toString())
                .build()
            val request = Request.Builder()
                .url(usageUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(response.toSttFailure(responseBody))
                } else {
                    Result.success(parseTencentAsrUsage(responseBody))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun fetchTencentAsrPolicy(
        endpoint: String,
        apiToken: String?
    ): Result<TencentAsrBudgetPolicy> {
        return try {
            val normalizedEndpoint = endpoint.trim().trimEnd('/')
            if (normalizedEndpoint.isBlank()) {
                return Result.failure(IOException("STT 服务地址未配置"))
            }
            val token = apiToken?.trim().orEmpty()
            if (token.isBlank()) {
                return Result.failure(IOException("STT 访问令牌未配置"))
            }
            val request = Request.Builder()
                .url("$normalizedEndpoint/cloud-asr/policy")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(response.toSttFailure(responseBody))
                } else {
                    Result.success(parseTencentAsrBudgetPolicy(responseBody))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Scan for STT servers by discovering local network subnet
     * Uses device's own IP to determine the subnet, then scans all IPs in parallel
     */
    suspend fun scanForServers(): List<DiscoveredSTTServer> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<DiscoveredSTTServer>()

        // Get device's own IP to determine subnet
        val deviceIp = getDeviceIpAddress()
        val subnet = deviceIp?.substringBeforeLast(".") ?: return@withContext servers

        // Fast parallel scan client
        val scanClient = OkHttpClient.Builder()
            .connectTimeout(200, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()

        // Scan ports 8888 and 8000 (most common STT ports)
        val ports = listOf(8888, 8000)

        // Launch parallel scans for all IPs in subnet (1-254)
        val jobs = (1..254).map { i ->
            async {
                val ip = "$subnet.$i"
                ports.mapNotNull { port ->
                    val endpoint = "http://$ip:$port"
                    try {
                        val request = Request.Builder()
                            .url("$endpoint/health")
                            .get()
                            .build()
                        scanClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string() ?: "{}"
                                parseServerInfo(body, endpoint)
                            } else null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }

        // Collect all results
        val allServers: List<DiscoveredSTTServer> = jobs.awaitAll().flatten().filterNotNull().distinctBy { s: DiscoveredSTTServer -> s.endpoint }
        allServers.forEach { server: DiscoveredSTTServer ->
            servers.add(server)
        }

        servers
    }

    /**
     * Get device's IP address on local network
     */
    private fun getDeviceIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || networkInterface.isVirtual) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress ?: continue
                        // Private IP ranges: 192.168.x.x, 10.x.x.x, 172.16-31.x.x
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                            ip.matches(Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\.\\d+\\.\\d+$"))) {
                            return ip
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Scan a specific endpoint
     */
    suspend fun scanEndpoint(endpoint: String): DiscoveredSTTServer? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$endpoint/health")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    return@withContext parseServerInfo(body, endpoint)
                }
            }
        } catch (_: Exception) {
            // Not available
        }
        null
    }

    private fun parseServerInfo(body: String, endpoint: String): DiscoveredSTTServer {
        // Try to parse JSON info response
        return try {
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            val engine = json.get("engine")?.asString ?: "Unknown"
            val model = json.get("model")?.asString ?: ""
            val port = extractPort(endpoint)
            DiscoveredSTTServer(
                endpoint = endpoint,
                engine = engine,
                model = model,
                port = port,
                isAvailable = true
            )
        } catch (_: Exception) {
            // JSON parse failed, assume it's a working STT server
            val port = extractPort(endpoint)
            DiscoveredSTTServer(
                endpoint = endpoint,
                engine = "Unknown",
                model = "",
                port = port,
                isAvailable = true
            )
        }
    }

    private fun extractPort(endpoint: String): Int {
        return try {
            endpoint.substringAfterLast(":").toInt()
        } catch (_: Exception) {
            8888
        }
    }
}

internal fun parseTencentAsrUsage(responseBody: String): TencentAsrUsage {
    val json = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
    val serviceArray = json.getAsJsonArray("services")
        ?: throw IOException("智悟增强云模型用量响应缺少服务明细")
    val services = buildList {
        serviceArray.forEach { element ->
            val item = element.asJsonObject
            val id = item.get("id")?.asString.orEmpty().trim()
            if (id.isNotBlank()) {
                add(
                    TencentAsrUsageService(
                        id = id,
                        displayName = item.get("display_name")?.asString ?: id,
                        usedSeconds = (item.get("used_seconds")?.asLong ?: 0L).coerceAtLeast(0L),
                        freeSeconds = (item.get("free_seconds")?.asLong ?: 0L).coerceAtLeast(0L),
                        remainingSeconds = (item.get("remaining_seconds")?.asLong ?: 0L).coerceAtLeast(0L),
                        usageRatio = (item.get("usage_ratio")?.asFloat ?: 0f).coerceIn(0f, 1f),
                        requestCount = (item.get("request_count")?.asLong ?: 0L).coerceAtLeast(0L),
                        pendingLocalSeconds = (item.get("pending_local_seconds")?.asLong ?: 0L)
                            .coerceAtLeast(0L),
                        pendingLocalRequestCount = (
                            item.get("pending_local_request_count")?.asLong ?: 0L
                        ).coerceAtLeast(0L)
                    )
                )
            }
        }
    }
    if (services.isEmpty()) throw IOException("智悟增强云模型用量响应不包含有效服务")
    return TencentAsrUsage(
        month = json.get("month")?.asString.orEmpty(),
        timezone = json.get("timezone")?.asString.orEmpty(),
        nextResetAt = json.get("next_reset_at")?.asString.orEmpty(),
        hybridRemainingSeconds = (json.get("hybrid_remaining_seconds")?.asLong ?: 0L)
            .coerceAtLeast(0L),
        warningLevel = TencentAsrQuotaWarningLevel.fromWireValue(
            json.get("warning_level")?.asString.orEmpty()
        ),
        services = services,
        source = json.get("source")?.asString.orEmpty(),
        updatedAt = json.get("updated_at")?.asString.orEmpty(),
        isEstimated = json.get("is_estimated")?.asBoolean ?: false
    )
}

internal fun parseTencentAsrBudgetPolicy(responseBody: String): TencentAsrBudgetPolicy {
    val json = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
    val tiers = buildList {
        json.getAsJsonArray("tiers")?.forEach tierLoop@{ tierElement ->
            val tier = tierElement.asJsonObject
            val id = tier.get("id")?.asString.orEmpty()
            if (id.isBlank()) return@tierLoop
            val services = buildList {
                tier.getAsJsonArray("services")?.forEach serviceLoop@{ serviceElement ->
                    val service = serviceElement.asJsonObject
                    val serviceId = service.get("business_name")?.asString.orEmpty()
                    if (serviceId.isBlank()) return@serviceLoop
                    add(
                        TencentAsrBudgetService(
                            id = serviceId,
                            displayName = service.get("display_name")?.asString ?: serviceId,
                            usedSeconds = (service.get("used_seconds")?.asLong ?: 0L).coerceAtLeast(0L),
                            reservedSeconds = (service.get("reserved_seconds")?.asLong ?: 0L).coerceAtLeast(0L),
                            limitSeconds = (service.get("limit_seconds")?.asLong ?: 0L).coerceAtLeast(0L),
                            remainingSeconds = (service.get("remaining_seconds")?.asLong ?: 0L).coerceAtLeast(0L)
                        )
                    )
                }
            }
            add(
                TencentAsrTierPolicy(
                    id = id,
                    displayName = tier.get("display_name")?.asString ?: id,
                    isPaid = tier.get("paid")?.asBoolean ?: false,
                    flashEnabled = tier.get("flash_enabled")?.asBoolean ?: false,
                    realtimeEnabled = tier.get("realtime_enabled")?.asBoolean ?: false,
                    monthlyLimitSeconds = (tier.get("monthly_limit_sec")?.asLong ?: 0L).coerceAtLeast(0L),
                    budgetEnforced = tier.get("budget_enforced")?.asBoolean ?: false,
                    services = services
                )
            )
        }
    }
    if (tiers.isEmpty()) throw IOException("智悟增强云模型状态响应不包含档位")
    return TencentAsrBudgetPolicy(
        month = json.get("month")?.asString.orEmpty(),
        source = json.get("source")?.asString.orEmpty(),
        tiers = tiers
    )
}

internal class SttAuthorizationException : IOException("STT 访问令牌无效或无权限")

private fun okhttp3.Response.toSttFailure(responseBody: String): IOException =
    if (code == 401 || code == 403) SttAuthorizationException() else IOException(toSttError(responseBody))

private fun okhttp3.Response.toSttError(responseBody: String): String {
    return when (code) {
        401, 403 -> "STT 访问令牌无效或无权限"
        429 -> "STT 服务繁忙，排队已满，请稍后重试"
        503 -> "STT 服务正在维护或模型尚未就绪"
        else -> {
            val detail = runCatching {
                com.google.gson.JsonParser.parseString(responseBody)
                    .asJsonObject
                    .get("detail")
                    ?.asString
            }.getOrNull()?.takeIf { it.isNotBlank() }
            detail ?: "STT 请求失败: HTTP $code $message"
        }
    }
}
