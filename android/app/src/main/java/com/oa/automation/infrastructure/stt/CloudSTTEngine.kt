package com.oa.automation.infrastructure.stt

import com.google.gson.JsonParser
import com.oa.automation.BuildConfig
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.ProcessingProgress
import com.oa.automation.infrastructure.network.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cloud STT Engine
 * OpenAI audio-transcription compatible cloud ASR adapter.
 */
class CloudSTTEngine(
    private val config: STTConfig
) : SpeechToTextEngine {

    private val client = createClient()

    override suspend fun transcribe(
        audioFile: File,
        onProgress: (ProcessingProgress) -> Unit,
        meetingId: String?,
        archiveKey: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            require(audioFile.isFile && audioFile.length() > 0L) { "录音文件为空或不可用" }
            val endpoint = config.cloudEndpoint?.trim().orEmpty()
            require(endpoint.isNotBlank()) { "智悟增强云模型地址未配置" }
            val apiKey = config.cloudApiKey?.trim().orEmpty()
                .ifBlank { config.apiToken?.trim().orEmpty() }
            require(apiKey.isNotBlank()) { "智悟增强云模型访问令牌未配置" }
            val model = config.cloudModel.trim()
            require(model.isNotBlank()) { "智悟增强云模型未配置" }

            onProgress(ProcessingProgress(20, "上传录音并请求智悟增强云模型", isIndeterminate = true))
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody(audioFile.toCloudMediaType())
                )
                .addFormDataPart("model", model)
                .addFormDataPart("language", config.language.requestValue)
                .build()

            val requestBuilder = Request.Builder()
                .url(cloudTranscriptionUrl(endpoint))
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
            meetingId?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.addHeader("X-Meeting-Id", it)
            }
            archiveKey?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.addHeader("X-Archive-Key", it)
            }
            val request = requestBuilder.build()

            val transcript = client.newCall(request).awaitResponse().use { response ->
                onProgress(ProcessingProgress(80, "接收最终识别结果"))
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(cloudAsrHttpError(response.code, responseBody))
                }
                require(responseBody.isNotBlank()) { "智悟增强云模型返回了空响应" }

                onProgress(ProcessingProgress(84, "解析最终识别结果"))
                parseCloudTranscript(responseBody)
            }
            Result.success(transcript)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun transcribeStreamSession(
        sessionId: String,
        onProgress: (ProcessingProgress) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            require(config.engineType == STTEngineType.TENCENT_HYBRID) {
                "当前智悟增强云模型模式不支持流式会话最终化"
            }
            require(sessionId.matches(Regex("^[0-9a-f]{32}$"))) { "流式转写会话标识无效" }
            val apiToken = config.apiToken?.trim().orEmpty()
            require(apiToken.isNotBlank()) { "STT 访问令牌未配置" }

            onProgress(ProcessingProgress(45, "智悟增强云模型正在生成最终稿", isIndeterminate = true))
            val request = Request.Builder()
                .url(managedStreamTranscriptionUrl(config.localEndpoint, sessionId))
                .addHeader("Authorization", "Bearer $apiToken")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            val transcript = client.newCall(request).awaitResponse().use { response ->
                onProgress(ProcessingProgress(80, "接收最终识别结果"))
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(cloudAsrHttpError(response.code, responseBody))
                }
                require(responseBody.isNotBlank()) { "智悟增强云模型返回了空响应" }
                onProgress(ProcessingProgress(84, "解析最终识别结果"))
                parseCloudTranscript(responseBody)
            }
            Result.success(transcript)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override fun getEngineType(): STTEngineType = config.engineType

    override fun getDisplayName(): String = config.engineType.displayName

    override fun isAvailable(): Boolean {
        if (config.engineType == STTEngineType.TENCENT_HYBRID) {
            return config.localEndpoint.isNotBlank() && !config.apiToken.isNullOrBlank()
        }
        return !config.cloudEndpoint.isNullOrBlank() &&
            (!config.cloudApiKey.isNullOrBlank() || !config.apiToken.isNullOrBlank()) &&
            config.cloudModel.isNotBlank()
    }

    companion object {
        fun testConnection(config: STTConfig): Result<Boolean> {
            val endpoint = config.cloudEndpoint?.trim().orEmpty()
            val apiKey = config.cloudApiKey?.trim().orEmpty()
                .ifBlank { config.apiToken?.trim().orEmpty() }
            val model = config.cloudModel.trim()
            if (endpoint.isBlank()) return Result.failure(Exception("智悟增强云模型地址未配置"))
            if (apiKey.isBlank()) return Result.failure(Exception("智悟增强云模型访问令牌未配置"))
            if (model.isBlank()) return Result.failure(Exception("智悟增强云模型未配置"))

            return runCatching {
                val request = Request.Builder()
                    .url(cloudModelsUrl(endpoint))
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                createClient().newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException(cloudAsrHttpError(response.code, body))
                    }
                    true
                }
            }
        }

        fun testHybridConnection(config: STTConfig): Result<Boolean> {
            val endpoint = config.localEndpoint.trim()
            val apiToken = config.apiToken?.trim().orEmpty()
            if (endpoint.isBlank()) return Result.failure(Exception("STT 服务地址未配置"))
            if (apiToken.isBlank()) return Result.failure(Exception("STT 访问令牌未配置"))

            return runCatching {
                val request = Request.Builder()
                    .url(managedHealthUrl(endpoint))
                    .addHeader("Authorization", "Bearer $apiToken")
                    .get()
                    .build()
                createClient().newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException(cloudAsrHttpError(response.code, body))
                    }
                    val root = JsonParser.parseString(body).asJsonObject
                    val realtimeReady = root.getAsJsonObject("realtime_asr")
                        ?.get("configured")?.asBoolean == true
                    val finalReady = root.getAsJsonObject("cloud_asr")
                        ?.get("configured")?.asBoolean == true
                    check(realtimeReady) { "智悟增强云模型实时识别尚未启用或配置不完整" }
                    check(finalReady) { "智悟增强云模型最终稿尚未启用或配置不完整" }
                    true
                }
            }
        }

        private fun createClient() = OkHttpClient.Builder()
            .connectTimeout(BuildConfig.STT_CLOUD_CONNECT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .readTimeout(BuildConfig.STT_CLOUD_READ_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .writeTimeout(BuildConfig.STT_CLOUD_WRITE_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .build()
    }
}

internal fun cloudTranscriptionUrl(endpoint: String): HttpUrl =
    cloudApiUrl(endpoint, listOf("audio", "transcriptions"))

internal fun cloudModelsUrl(endpoint: String): HttpUrl =
    cloudApiUrl(endpoint, listOf("models"))

internal fun managedStreamTranscriptionUrl(endpoint: String, sessionId: String): HttpUrl {
    require(sessionId.matches(Regex("^[0-9a-f]{32}$"))) { "流式转写会话标识无效" }
    val url = endpoint.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("STT 服务地址格式无效")
    return url.newBuilder()
        .addPathSegment("transcribe")
        .addPathSegment("stream")
        .addPathSegment(sessionId)
        .fragment(null)
        .build()
}

private fun managedHealthUrl(endpoint: String): HttpUrl {
    val url = endpoint.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("STT 服务地址格式无效")
    return url.newBuilder().addPathSegment("health").fragment(null).build()
}

private fun cloudApiUrl(endpoint: String, resource: List<String>): HttpUrl {
    val url = endpoint.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("智悟增强云模型地址格式无效")
    val path = url.pathSegments.filter { it.isNotBlank() }.toMutableList()
    if (path.takeLast(2) == listOf("audio", "transcriptions")) {
        repeat(2) { path.removeAt(path.lastIndex) }
    } else if (path.lastOrNull() == "models") {
        path.removeAt(path.lastIndex)
    }
    if (path.lastOrNull() != "v1") path += "v1"
    path += resource

    val builder = url.newBuilder().encodedPath("/")
    path.forEach(builder::addPathSegment)
    return builder.fragment(null).build()
}

internal fun parseCloudTranscript(responseBody: String): String {
    val parsed = runCatching { JsonParser.parseString(responseBody) }.getOrNull()
    if (parsed?.isJsonObject == true) {
        val root = parsed.asJsonObject
        val candidates = listOfNotNull(
            root.get("text")?.takeIf { it.isJsonPrimitive }?.asString,
            root.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString,
            root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
        )
        candidates.firstOrNull { it.isNotBlank() }?.let { return it }
    }
    if (parsed == null && responseBody.isNotBlank()) return responseBody.trim()
    throw IOException("智悟增强云模型响应中没有转写文本")
}

private fun cloudAsrHttpError(code: Int, body: String): String = when (code) {
    401 -> "智悟增强云模型访问令牌无效或已过期 (HTTP 401)"
    403 -> "当前账号无权调用智悟增强云模型 (HTTP 403)"
    404 -> "智悟增强云模型接口不可用，请检查服务地址 (HTTP 404)"
    429 -> "智悟增强云模型繁忙或额度不足 (HTTP 429)"
    else -> "智悟增强云模型请求失败 (HTTP $code): ${body.take(200)}"
}

private fun File.toCloudMediaType() = when (extension.lowercase()) {
    "wav" -> "audio/wav".toMediaType()
    "m4a", "mp4", "mp3" -> "audio/mpeg".toMediaType()
    else -> "application/octet-stream".toMediaType()
}
