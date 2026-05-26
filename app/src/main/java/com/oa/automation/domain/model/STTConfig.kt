package com.oa.automation.domain.model

/**
 * STT (Speech-to-Text) Engine Types
 *
 * Priority:
 * - P0: Faster-Whisper (main local engine)
 * - P1: SenseVoice (Chinese-optimized, alternative local)
 * - P2: Cloud ASR (cloud backup)
 */
enum class STTEngineType(val displayName: String, val defaultModel: String) {
    FASTER_WHISPER("Faster-Whisper (本地)", "small"),
    SENSE_VOICE("SenseVoice (中文优化)", "SenseVoiceSmall"),
    CLOUD_ASR("云端 ASR", "")
}

/**
 * Discovered STT server info
 */
data class DiscoveredSTTServer(
    val endpoint: String,
    val engine: String,
    val model: String,
    val port: Int,
    val isAvailable: Boolean
)

/**
 * STT Engine Configuration
 */
data class STTConfig(
    val engineType: STTEngineType = STTEngineType.FASTER_WHISPER,
    val localEndpoint: String = DEFAULT_LOCAL_ENDPOINT,
    val localModel: String = "small",
    val cloudEndpoint: String? = null,
    val cloudApiKey: String? = null
) {
    companion object {
        const val DEFAULT_LOCAL_ENDPOINT = "http://ecobim.cn:38270"
        const val LEGACY_LOCAL_ENDPOINT = "http://localhost:8888"
        val DEFAULT = STTConfig()

        // Common ports for STT services
        val COMMON_PORTS = listOf(8888, 8000, 8001, 8002, 8889, 8890)
    }
}
