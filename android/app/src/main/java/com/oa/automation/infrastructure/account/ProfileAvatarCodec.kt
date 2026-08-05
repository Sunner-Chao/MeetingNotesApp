package com.oa.automation.infrastructure.account

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import com.oa.automation.BuildConfig
import com.oa.automation.infrastructure.image.OrientedImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfileAvatarCodec(context: Context) {
    private val appContext = context.applicationContext

    suspend fun encode(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheDirectory = File(appContext.cacheDir, "profile").apply { mkdirs() }
            val source = File.createTempFile("avatar-", ".image", cacheDirectory)
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    source.outputStream().use(input::copyTo)
                } ?: error("无法读取所选头像")
                val bitmap = OrientedImageDecoder.decode(
                    source,
                    BuildConfig.PROFILE_AVATAR_MAX_DIMENSION
                ) ?: error("无法解析所选图片")
                val bytes = ByteArrayOutputStream().use { output ->
                    try {
                        check(
                            bitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                BuildConfig.PROFILE_AVATAR_JPEG_QUALITY,
                                output
                            )
                        ) { "头像压缩失败" }
                        output.toByteArray()
                    } finally {
                        bitmap.recycle()
                    }
                }
                require(bytes.size <= BuildConfig.PROFILE_AVATAR_MAX_BYTES) {
                    "头像压缩后仍然过大，请选择更简洁的图片"
                }
                "data:image/jpeg;base64," +
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                source.delete()
            }
        }
    }
}
