package com.oa.automation.domain.model

import com.oa.automation.BuildConfig

/** Build-time product surface; the light edition keeps social data and routes compatible. */
enum class ProductEdition {
    LIGHT_ENJOY,
    SOCIAL;

    /** Lite ships with the managed Tencent route only. */
    val supportsLocalStt: Boolean
        get() = this != LIGHT_ENJOY || BuildConfig.LITE_LOCAL_STT_ENABLED

    val defaultSttEngine: STTEngineType
        get() = if (supportsLocalStt) {
            STTEngineType.FASTER_WHISPER
        } else {
            STTEngineType.TENCENT_HYBRID
        }

    val includesSocialSurface: Boolean
        get() = this == SOCIAL

    val displayName: String
        get() = when (this) {
            SOCIAL -> "智悟本(Pro)"
            LIGHT_ENJOY -> "智悟本(Lite)"
        }

    companion object {
        val current: ProductEdition = when (BuildConfig.PRODUCT_EDITION.lowercase()) {
            "social" -> SOCIAL
            else -> LIGHT_ENJOY
        }
    }
}
