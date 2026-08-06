package com.oa.automation.infrastructure.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.infrastructure.db.PublishedPostMediaDao
import com.oa.automation.infrastructure.db.PublishedPostMediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

class PublishedPostMediaStore(
    private val context: Context,
    private val dao: PublishedPostMediaDao
) {
    suspend fun prepare(postId: String, attachments: List<MeetingAttachment>): List<PublishedPostMediaEntity> =
        withContext(Dispatchers.IO) {
            val destination = File(context.filesDir, "community-media/$postId").apply { mkdirs() }
            val prepared = attachments.distinctBy { it.id }.mapNotNull { attachment ->
                prepareAttachment(postId, attachment, destination).getOrNull()
            }
            if (prepared.isNotEmpty()) dao.upsertAll(prepared)
            prepared
        }

    private fun prepareAttachment(
        postId: String,
        attachment: MeetingAttachment,
        destination: File
    ): Result<PublishedPostMediaEntity> = runCatching {
        val source = File(attachment.localPath)
        require(source.isFile) { "图片源文件不存在" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法读取" }
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_ORIGINAL_EDGE)
            }
        ) ?: error("图片无法读取")
        val mediaId = UUID.randomUUID().toString()
        val originalFile = File(destination, "$mediaId-original.jpg")
        val thumbnailFile = File(destination, "$mediaId-thumbnail.jpg")
        try {
            val original = scaleDown(decoded, MAX_ORIGINAL_EDGE)
            originalFile.outputStream().use { output ->
                check(original.compress(Bitmap.CompressFormat.JPEG, ORIGINAL_QUALITY, output))
            }
            if (original !== decoded) original.recycle()
            val thumbnail = scaleDown(decoded, MAX_THUMBNAIL_EDGE)
            thumbnailFile.outputStream().use { output ->
                check(thumbnail.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output))
            }
            if (thumbnail !== decoded) thumbnail.recycle()
        } finally {
            decoded.recycle()
        }
        PublishedPostMediaEntity(
            id = mediaId,
            postId = postId,
            sourceAttachmentId = attachment.id,
            displayName = attachment.displayName.take(200).ifBlank { "研学图片" },
            originalPath = originalFile.absolutePath,
            thumbnailPath = thumbnailFile.absolutePath,
            mimeType = "image/jpeg",
            originalBytes = originalFile.length(),
            originalSha256 = sha256(originalFile),
            thumbnailBytes = thumbnailFile.length(),
            thumbnailSha256 = sha256(thumbnailFile),
            remoteMediaId = null,
            status = "PENDING",
            lastError = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var size = 1
        while (max(width / size, height / size) > target * 2) size *= 2
        return size
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val current = max(source.width, source.height)
        if (current <= maxEdge) return source
        val scale = maxEdge.toFloat() / current
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_ORIGINAL_EDGE = 2_560
        private const val MAX_THUMBNAIL_EDGE = 640
        private const val ORIGINAL_QUALITY = 88
        private const val THUMBNAIL_QUALITY = 78
    }
}
