package com.oa.automation.infrastructure.stt

import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import java.io.File

/**
 * Interface for Speech-to-Text engines
 */
interface SpeechToTextEngine {

    /**
     * Transcribe audio file to text
     */
    suspend fun transcribe(audioFile: File): Result<String>

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
            return when (config.engineType) {
                STTEngineType.FASTER_WHISPER -> FasterWhisperEngine(config)
                STTEngineType.SENSE_VOICE -> SenseVoiceEngine(config)
                STTEngineType.CLOUD_ASR -> CloudSTTEngine(config)
            }
        }
    }
}