package com.oa.automation.domain.model

import com.oa.automation.BuildConfig

/**
 * STT (Speech-to-Text) Engine Types
 *
 * Priority:
 * - P0: Zhiwu local model
 * - P1: Zhiwu listening model
 * - P2: Zhiwu enhanced cloud model
 */
enum class STTEngineType(val displayName: String, val defaultModel: String) {
    FASTER_WHISPER("智悟本地 Faster-Whisper", "large-v3-turbo"),
    TENCENT_HYBRID("智悟增强云模型", "tencent-standard")
}

enum class STTLanguage(
    val displayName: String,
    val compactLabel: String,
    val requestValue: String
) {
    CHINESE("中文", "中", "zh"),
    ENGLISH("English", "EN", "en")
}

enum class TencentAsrTier(
    val displayName: String,
    val cloudModel: String,
    val streamProvider: String,
    val isPaid: Boolean
) {
    STANDARD_FREE(
        displayName = "智悟增强云模型 · 标准",
        cloudModel = "tencent-standard",
        streamProvider = "tencent-realtime-standard",
        isPaid = false
    ),
    PRECISION_PAID(
        displayName = "智悟增强云模型 · 臻享",
        cloudModel = "tencent-precision",
        streamProvider = "tencent-realtime-precision",
        isPaid = true
    )
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
    val language: STTLanguage = STTLanguage.CHINESE,
    val localEndpoint: String = DEFAULT_LOCAL_ENDPOINT,
    val localModel: String = BuildConfig.DEFAULT_STT_MODEL,
    val apiToken: String? = null,
    val cloudEndpoint: String? = DEFAULT_CLOUD_ENDPOINT,
    val cloudApiKey: String? = null,
    val cloudModel: String = DEFAULT_CLOUD_MODEL,
    val tencentAsrTier: TencentAsrTier = TencentAsrTier.STANDARD_FREE
) {
    companion object {
        const val DEFAULT_LOCAL_ENDPOINT = BuildConfig.DEFAULT_STT_ENDPOINT
        val DEFAULT_CLOUD_ENDPOINT = BuildConfig.DEFAULT_STT_CLOUD_ENDPOINT.takeIf { it.isNotBlank() }
        val DEFAULT_CLOUD_MODEL = BuildConfig.DEFAULT_STT_CLOUD_MODEL.ifBlank {
            TencentAsrTier.STANDARD_FREE.cloudModel
        }
        const val PREVIOUS_PUBLIC_ENDPOINT = "http://ecobim.cn:57414"
        const val LEGACY_LOCAL_ENDPOINT = "http://localhost:8888"
        const val AVD_HOST_ENDPOINT = "http://10.0.2.2:8888"
        val DEFAULT = STTConfig()

        // Common ports for STT services
        val COMMON_PORTS = listOf(8888, 8000, 8001, 8002, 8889, 8890)
    }
}

fun STTConfig.serviceEndpointFor(engineType: STTEngineType = this.engineType): String =
    when (engineType) {
        STTEngineType.TENCENT_HYBRID -> cloudEndpoint?.trim().orEmpty()
        else -> localEndpoint.trim()
    }

fun String.isDevelopmentOnlySttEndpoint(): Boolean {
    val normalized = trim().trimEnd('/').lowercase()
    return normalized == STTConfig.LEGACY_LOCAL_ENDPOINT ||
        normalized == STTConfig.AVD_HOST_ENDPOINT ||
        normalized.startsWith("http://127.0.0.1:")
}
