package com.oa.automation.domain.model

import com.oa.automation.BuildConfig

/** Build-time product surface; the light edition keeps social data and routes compatible. */
enum class ProductEdition {
    LIGHT_ENJOY,
    SOCIAL;

    val includesSocialSurface: Boolean
        get() = this == SOCIAL

    companion object {
        val current: ProductEdition = when (BuildConfig.PRODUCT_EDITION.lowercase()) {
            "social" -> SOCIAL
            else -> LIGHT_ENJOY
        }
    }
}
