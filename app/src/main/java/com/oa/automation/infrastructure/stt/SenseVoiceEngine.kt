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

    override suspend fun transcribe(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody(audioFile.toSenseVoiceMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("${config.localEndpoint}/transcribe")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("SenseVoice STT request failed: ${response.code}")
                )
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(
                Exception("Empty response from SenseVoice service")
            )

            val result = gson.fromJson(responseBody, SenseVoiceResponse::class.java)
            Result.success(result.text ?: "")

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getEngineType(): STTEngineType = STTEngineType.SENSE_VOICE

    override fun getDisplayName(): String = "SenseVoice (中文优化)"

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
