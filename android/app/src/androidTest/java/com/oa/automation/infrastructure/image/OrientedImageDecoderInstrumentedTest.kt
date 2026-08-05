package com.oa.automation.infrastructure.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oa.automation.infrastructure.location.ExifLocationReader
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrientedImageDecoderInstrumentedTest {
    @Test
    fun appliesExifRotationForPreviewAndExportDecoding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "oriented-preview-test.jpg")
        val sourceBitmap = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
        try {
            sourceBitmap.eraseColor(Color.rgb(20, 190, 80))
            FileOutputStream(source).use { output ->
                check(sourceBitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
            }
        } finally {
            sourceBitmap.recycle()
        }
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val decoded = OrientedImageDecoder.decode(source, maximumDimension = 512)

        assertNotNull(decoded)
        try {
            assertEquals(80, decoded!!.width)
            assertEquals(40, decoded.height)
        } finally {
            decoded?.recycle()
            source.delete()
        }
    }

    @Test
    fun readsGpsCoordinatesFromImportedImageExif() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "exif-location-test.jpg")
        val sourceBitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        try {
            sourceBitmap.eraseColor(Color.rgb(20, 190, 80))
            FileOutputStream(source).use { output ->
                check(sourceBitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
            }
        } finally {
            sourceBitmap.recycle()
        }
        ExifInterface(source).apply {
            setLatLong(30.7521, 120.7582)
            setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2026:08:04")
            setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "10/1,20/1,30/1")
            saveAttributes()
        }

        val location = ExifLocationReader.read(source, fallbackCapturedAt = 123L)

        assertNotNull(location)
        assertEquals(30.7521, location!!.latitude, 0.000001)
        assertEquals(120.7582, location.longitude, 0.000001)
        assertEquals("exif", location.source)
        assertEquals(1785838830000L, location.capturedAt)
        source.delete()
    }
}
