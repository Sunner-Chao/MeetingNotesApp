package com.oa.automation.infrastructure.account

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GrowthQrGalleryStore(private val context: Context) {
    suspend fun save(
        bytes: ByteArray,
        displayNamePrefix: String = "智悟本福利7群二维码"
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            require(bytes.isNotEmpty()) { "二维码图片尚未加载完成" }
            requireGalleryPermission()
            val format = detectFormat(bytes)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .take(5)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            val displayName = "${displayNamePrefix}_$digest.${format.extension}"
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/智悟本/福利群/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val destination = context.contentResolver.insert(collection, values)
                ?: error("无法创建相册图片")
            try {
                context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    output.write(bytes)
                    output.flush()
                } ?: error("无法写入相册图片")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val ready = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    check(context.contentResolver.update(destination, ready, null, null) == 1) {
                        "无法完成相册保存"
                    }
                }
                destination
            } catch (error: Throwable) {
                context.contentResolver.delete(destination, null, null)
                throw error
            }
        }
    }

    private fun requireGalleryPermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("未授予保存图片到手机相册的权限")
        }
    }

    private fun detectFormat(bytes: ByteArray): ImageFormat = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        ) -> ImageFormat("png", "image/png")
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> ImageFormat("webp", "image/webp")
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> ImageFormat("jpg", "image/jpeg")
        else -> error("二维码图片格式无效")
    }

    private data class ImageFormat(val extension: String, val mimeType: String)
}
