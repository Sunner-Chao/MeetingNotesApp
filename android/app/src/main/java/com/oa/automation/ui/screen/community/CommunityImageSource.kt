package com.oa.automation.ui.screen.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.URL

private const val COMMUNITY_ASSET_PREFIX = "asset:///"

internal fun resolveCommunityMediaUrl(baseUrl: String, path: String): String = when {
    path.contains("://") -> path
    else -> "$baseUrl$path"
}

internal fun decodeCommunityBitmap(context: Context, source: String): Bitmap? = when {
    source.startsWith(COMMUNITY_ASSET_PREFIX) -> {
        val assetPath = source.removePrefix(COMMUNITY_ASSET_PREFIX)
        context.assets.open(assetPath).use(BitmapFactory::decodeStream)
    }
    source.startsWith("https://") || source.startsWith("http://") -> {
        URL(source).openStream().use(BitmapFactory::decodeStream)
    }
    else -> null
}
