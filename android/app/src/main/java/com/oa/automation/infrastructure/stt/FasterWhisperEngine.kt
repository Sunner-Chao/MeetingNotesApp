package com.oa.automation.infrastructure.stt

import com.google.gson.Gson
import com.oa.automation.domain.model.DiscoveredSTTServer
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import com.oa.automation.locale.SimplifiedChineseText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
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

    override suspend fun transcribe(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!audioFile.isFile || audioFile.length() <= 44L) {
                return@withContext Result.failure(IOException("录音文件为空或不可用"))
            }
            val apiToken = config.apiToken?.trim().orEmpty()
            if (apiToken.isBlank()) {
                return@withContext Result.failure(IOException("STT 访问令牌未配置"))
            }

            // Build request to Python STT service
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody(audioFile.toMediaType())
                )
                .build()

            val requestBuilder = Request.Builder()
                .url("${config.localEndpoint.trim().trimEnd('/')}/transcribe")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $apiToken")
            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException(response.toSttError(responseBody))
                    )
                }

                if (responseBody.isBlank()) {
                    return@withContext Result.failure(IOException("STT 服务返回了空响应"))
                }

                val result = gson.fromJson(responseBody, STTResponse::class.java)
                val text = SimplifiedChineseText.normalize(result.text.orEmpty())
                if (text.isBlank()) {
                    Result.failure(IOException("未识别到有效语音，请确认麦克风收音后重试"))
                } else {
                    Result.success(text)
                }
            }

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
        return if (isLocal) "Faster-Whisper 本地" else "Faster-Whisper 云端"
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
                    return Result.failure(IOException(response.toSttError(response.body?.string().orEmpty())))
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
                else Result.failure(IOException(response.toSttError(response.body?.string().orEmpty())))
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
