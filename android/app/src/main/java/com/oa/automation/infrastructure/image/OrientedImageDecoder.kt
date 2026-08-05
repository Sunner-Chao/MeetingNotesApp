package com.oa.automation.infrastructure.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Decodes local meeting images in the same visual orientation used for exports. */
internal object OrientedImageDecoder {
    fun decode(file: File, maximumDimension: Int): Bitmap? = runCatching {
        require(maximumDimension > 0) { "maximumDimension must be positive" }
        require(file.isFile) { "Image file is unavailable" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maximumDimension ||
            bounds.outHeight / sampleSize > maximumDimension
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return@runCatching null

        decoded.applyExifOrientation(readOrientation(file))
    }.getOrNull()

    private fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun Bitmap.applyExifOrientation(orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            }
        }
        if (matrix.isIdentity) return this
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
            .also { recycle() }
    }
}
