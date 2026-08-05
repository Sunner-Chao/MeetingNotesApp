package com.oa.automation.infrastructure.export

import android.graphics.Bitmap
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.infrastructure.image.OrientedImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File

internal data class PreparedMeetingImage(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val caption: String
)

internal object MeetingImagePreparer {
    fun prepare(attachment: MeetingAttachment): PreparedMeetingImage? = runCatching {
        val source = File(attachment.localPath).takeIf { it.isFile } ?: return@runCatching null
        val oriented = OrientedImageDecoder.decode(source, maximumDimension = 2400)
            ?: return@runCatching null

        val scale = minOf(1f, 1800f / maxOf(oriented.width, oriented.height))
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * scale).toInt(),
                (oriented.height * scale).toInt(),
                true
            ).also { oriented.recycle() }
        } else {
            oriented
        }

        val bytes = ByteArrayOutputStream().use { output ->
            check(resized.compress(Bitmap.CompressFormat.JPEG, 88, output))
            output.toByteArray()
        }
        PreparedMeetingImage(
            bytes = bytes,
            widthPx = resized.width,
            heightPx = resized.height,
            caption = attachment.displayName
        ).also { resized.recycle() }
    }.getOrNull()
}
