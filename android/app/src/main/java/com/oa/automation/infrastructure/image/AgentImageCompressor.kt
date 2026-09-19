package com.oa.automation.infrastructure.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.oa.automation.infrastructure.llm.AgentAttachment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Prepares a private, short-lived copy of an image for Agent upload.
 *
 * Meeting attachments themselves are never rewritten: the original remains
 * available for local preview, export and later editing. Only images larger
 * than [TARGET_BYTES] are decoded, oriented and re-encoded as JPEG.
 */
internal object AgentImageCompressor {
    const val TARGET_BYTES = 1_000_000
    private const val INITIAL_MAX_EDGE = 2_048
    private val MAX_EDGES = intArrayOf(2_048, 1_600, 1_280, 1_024, 768, 640, 512, 384)
    private val QUALITIES = intArrayOf(90, 82, 74, 66, 58, 50, 42, 34, 28)

    data class Prepared(
        val attachment: AgentAttachment,
        val temporaryFile: File?
    )

    suspend fun prepare(attachment: AgentAttachment, targetBytes: Int = TARGET_BYTES): Prepared {
        require(targetBytes in 1..TARGET_BYTES)
        currentCoroutineContext().ensureActive()
        val source = attachment.file
        if (!source.isFile || source.length() == 0L) {
            throw IOException("图片不存在或为空：${attachment.displayName}")
        }
        if (source.length() <= targetBytes) {
            return Prepared(attachment, null)
        }
        val decoded = OrientedImageDecoder.decode(source, INITIAL_MAX_EDGE)
            ?: throw IOException("图片无法读取或压缩：${attachment.displayName}")
        var output: File? = null
        return try {
            // The source is already in app-private storage; keeping the copy
            // beside it avoids relying on the device-wide java.io.tmpdir.
            output = File(
                source.parentFile ?: source.absoluteFile.parentFile,
                ".agent-upload-${UUID.randomUUID()}.jpg"
            )
            if (!encodeWithinLimit(decoded, output, targetBytes)) {
                throw IOException("图片无法压缩到上传大小，请重新选择：${attachment.displayName}")
            }
            currentCoroutineContext().ensureActive()
            Prepared(
                attachment = attachment.copy(
                    file = output,
                    mimeType = "image/jpeg"
                ),
                temporaryFile = output
            )
        } catch (error: Throwable) {
            output?.delete()
            throw error
        } finally {
            decoded.recycle()
        }
    }

    private suspend fun encodeWithinLimit(source: Bitmap, output: File, targetBytes: Int): Boolean {
        for (edge in MAX_EDGES) {
            currentCoroutineContext().ensureActive()
            val scaled = scaleDown(source, edge)
            try {
                for (quality in QUALITIES) {
                    currentCoroutineContext().ensureActive()
                    output.outputStream().use { stream ->
                        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) return false
                    }
                    if (output.length() in 1..targetBytes.toLong()) return true
                }
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        }
        return false
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val current = maxOf(source.width, source.height)
        val ratio = (maxEdge.toFloat() / current).coerceAtMost(1f)
        val scaled = if (current <= maxEdge) source else Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        // JPEG has no alpha. A white backing avoids turning transparent PNGs black.
        if (scaled.hasAlpha()) {
            val flattened = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            Canvas(flattened).apply {
                drawColor(Color.WHITE)
                drawBitmap(scaled, 0f, 0f, null)
            }
            if (scaled !== source) scaled.recycle()
            return flattened
        }
        return scaled
    }
}
