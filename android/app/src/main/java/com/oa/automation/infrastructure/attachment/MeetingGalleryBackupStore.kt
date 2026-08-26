package com.oa.automation.infrastructure.attachment

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.oa.automation.domain.model.MeetingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates a durable, user-visible copy of meeting images in the device gallery.
 *
 * The app-owned file remains the source of truth. Gallery writes are best effort:
 * a missing legacy storage permission or a media-provider failure must not make
 * importing a meeting image fail.
 */
class MeetingGalleryBackupStore(private val context: Context) {
    suspend fun backup(attachment: MeetingAttachment): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            attachment.galleryUri
                ?.let(Uri::parse)
                ?.takeIf(::isReadable)
                ?.let { return@runCatching it }

            val source = File(attachment.localPath).takeIf { it.isFile && it.length() > 0L }
                ?: error("应用内图片不存在")
            requireGalleryPermission()

            val displayName = buildDisplayName(attachment)
            val relativePath = buildRelativePath(attachment.meetingId)
            findExisting(displayName, relativePath)?.let { return@runCatching it }

            val collection = imageCollection()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, normalizeMimeType(attachment.mimeType))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val destination = context.contentResolver.insert(collection, values)
                ?: error("无法创建手机相册文件")

            try {
                context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                } ?: error("无法写入手机相册文件")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val ready = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    check(context.contentResolver.update(destination, ready, null, null) == 1) {
                        "无法完成手机相册文件保存"
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("未授予保存图片到手机相册的权限")
        }
    }

    private fun imageCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun findExisting(displayName: String, relativePath: String): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Images.Media.IS_PENDING} = 0"
            selectionArgs = arrayOf(displayName, relativePath)
        } else {
            selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            selectionArgs = arrayOf(displayName)
        }
        return context.contentResolver.query(
            imageCollection(),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            Uri.withAppendedPath(imageCollection(), id.toString())
        }
    }

    private fun isReadable(uri: Uri): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length != 0L
        } ?: false
    }.getOrDefault(false)

    private fun buildDisplayName(attachment: MeetingAttachment): String {
        val original = attachment.displayName.substringBeforeLast('.', attachment.displayName)
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            .trim('_')
            .ifBlank { "会议图片" }
        val extension = attachment.displayName.substringAfterLast('.', "jpg")
            .replace(Regex("[^A-Za-z0-9]+"), "")
            .lowercase()
            .ifBlank { "jpg" }
        return "${original.take(60)}_${attachment.id.take(8)}.$extension"
    }

    private fun buildRelativePath(meetingId: String): String {
        val safeMeetingId = meetingId.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(80)
        return "Pictures/智悟本/会议图片/$safeMeetingId/"
    }

    private fun normalizeMimeType(value: String): String =
        value.takeIf { it.startsWith("image/") } ?: "image/jpeg"
}
