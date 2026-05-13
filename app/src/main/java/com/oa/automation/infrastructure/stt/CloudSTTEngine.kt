package com.oa.automation.infrastructure.stt

import com.google.gson.Gson
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
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
 * Cloud STT Engine
 * Supports various cloud ASR services (SiliconFlow, Alibaba Cloud, etc.)
 *
 * Expected to use OpenAI-compatible Whisper API or custom ASR API
 */
class CloudSTTEngine(
    private val config: STTConfig
) : SpeechToTextEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun transcribe(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val endpoint = config.cloudEndpoint ?: return@withContext Result.failure(
                Exception("Cloud endpoint not configured")
            )

            val apiKey = config.cloudApiKey ?: return@withContext Result.failure(
                Exception("Cloud API key not configured")
            )

            // Build request based on SiliconFlow Whisper API format
            // Different cloud providers may have different formats
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody(audioFile.toCloudMediaType())
                )
                .addFormDataPart("model", "paraformer-zh") // Default to Chinese model
                .build()

            val request = Request.Builder()
                .url("$endpoint/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Cloud STT request failed: ${response.code}")
                )
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(
                Exception("Empty response from cloud STT service")
            )

            // Try to parse as OpenAI-compatible format first
            val openAIResponse = runCatching {
                gson.fromJson(responseBody, OpenAIWhisperResponse::class.java)
            }.getOrNull()

            if (openAIResponse != null) {
                return@withContext Result.success(openAIResponse.text)
            }

            // Try SiliconFlow format
            val siliconFlowResponse = runCatching {
                gson.fromJson(responseBody, SiliconFlowResponse::class.java)
            }.getOrNull()

            if (siliconFlowResponse != null) {
                return@withContext Result.success(siliconFlowResponse.text)
            }

            // If no known format, return raw text
            Result.success(responseBody)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getEngineType(): STTEngineType = STTEngineType.CLOUD_ASR

    override fun getDisplayName(): String = "云端 ASR"

    override fun isAvailable(): Boolean {
        return !config.cloudEndpoint.isNullOrBlank() && !config.cloudApiKey.isNullOrBlank()
    }

    // OpenAI Whisper API compatible response
    private data class OpenAIWhisperResponse(
        val text: String
    )

    // SiliconFlow response format
    private data class SiliconFlowResponse(
        val text: String
    )
}

private fun File.toCloudMediaType() = when (extension.lowercase()) {
    "wav" -> "audio/wav".toMediaType()
    "m4a", "mp4", "mp3" -> "audio/mpeg".toMediaType()
    else -> "application/octet-stream".toMediaType()
}
