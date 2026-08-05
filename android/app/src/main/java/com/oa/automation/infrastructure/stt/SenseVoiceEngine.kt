package com.oa.automation.infrastructure.stt

import com.google.gson.Gson
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SenseVoice STT Engine
 * Alibaba's speech recognition model optimized for Chinese conversations
 *
 * Similar interface to FasterWhisper but using SenseVoice model
 * Expected Python service endpoints:
 * - POST /transcribe - Transcribe audio file
 */
class SenseVoiceEngine(
    private val config: STTConfig
) : SpeechToTextEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    private val gson = Gson()

    override suspend fun transcribe(
        audioFile: File,
        onProgress: (ProcessingProgress) -> Unit,
        meetingId: String?,
        archiveKey: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onProgress(ProcessingProgress(20, "上传录音并请求智悟灵听模型", isIndeterminate = true))
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("language", config.language.requestValue)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody(audioFile.toSenseVoiceMediaType())
                )
                .build()

            val requestBuilder = Request.Builder()
                .url("${config.localEndpoint}/transcribe")
                .post(requestBody)
            config.apiToken?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.addHeader("Authorization", "Bearer $it")
            }
        meetingId?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.addHeader("X-Meeting-Id", it)
        }
        archiveKey?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.addHeader("X-Archive-Key", it)
        }
            val request = requestBuilder.build()

            client.newCall(request).awaitResponse().use { response ->
                onProgress(ProcessingProgress(80, "接收最终识别结果"))
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("智悟灵听模型请求失败: ${response.code}")
                    )
                }

                val responseBody = response.body?.string() ?: return@withContext Result.failure(
                    Exception("智悟灵听模型返回空响应")
                )

                onProgress(ProcessingProgress(84, "解析最终识别结果"))
                val result = gson.fromJson(responseBody, SenseVoiceResponse::class.java)
                Result.success(result.text ?: "")
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getEngineType(): STTEngineType = STTEngineType.SENSE_VOICE

    override fun getDisplayName(): String = "智悟灵听模型"

    override fun isAvailable(): Boolean {
        return config.localEndpoint.isNotBlank()
    }

    private data class SenseVoiceResponse(
        val text: String?,
        val language: String?
    )
}

private fun File.toSenseVoiceMediaType() = when (extension.lowercase()) {
    "wav" -> "audio/wav".toMediaType()
    "m4a", "mp4", "mp3" -> "audio/mpeg".toMediaType()
    else -> "application/octet-stream".toMediaType()
}
