package com.oa.automation.infrastructure.stt

import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.ProcessingProgress
import java.io.File

/**
 * Interface for Speech-to-Text engines
 */
interface SpeechToTextEngine {

    /**
     * Transcribe audio file to text
     */
    suspend fun transcribe(
        audioFile: File,
        onProgress: (ProcessingProgress) -> Unit = {},
        meetingId: String? = null,
        archiveKey: String? = null
    ): Result<String>

    /** Finalize audio already uploaded by the streaming preview connection. */
    suspend fun transcribeStreamSession(
        sessionId: String,
        onProgress: (ProcessingProgress) -> Unit = {}
    ): Result<String> =
        Result.failure(UnsupportedOperationException("当前 STT 引擎不支持流式会话最终化"))

    /**
     * Get the engine type
     */
    fun getEngineType(): STTEngineType

    /**
     * Get display name
     */
    fun getDisplayName(): String

    /**
     * Check if engine is available/configured
     */
    fun isAvailable(): Boolean

    /**
     * Create engine from configuration
     */
    companion object {
        fun fromConfig(config: STTConfig): SpeechToTextEngine {
            val primary = when (config.engineType) {
                STTEngineType.FASTER_WHISPER -> FasterWhisperEngine(config)
                STTEngineType.TENCENT_HYBRID -> CloudSTTEngine(config)
            }
            if (config.engineType == STTEngineType.TENCENT_HYBRID ||
                config.cloudEndpoint.isNullOrBlank() ||
                config.apiToken.isNullOrBlank()
            ) {
                return primary
            }

            val cloudFallback = CloudSTTEngine(
                config.copy(
                    engineType = STTEngineType.TENCENT_HYBRID,
                    cloudApiKey = null,
                    cloudModel = config.tencentAsrTier.cloudModel
                )
            )
            return FallbackSpeechToTextEngine(primary, cloudFallback)
        }
    }
}
