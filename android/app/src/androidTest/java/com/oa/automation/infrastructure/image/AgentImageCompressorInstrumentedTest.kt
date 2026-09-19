package com.oa.automation.infrastructure.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oa.automation.infrastructure.llm.AgentAttachment
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AgentImageCompressorInstrumentedTest {
    @Test
    fun largeImageIsCompressedWithoutReplacingTheOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "agent-compression-source.jpg")
        val bitmap = Bitmap.createBitmap(2_400, 1_800, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height step 4) {
                for (x in 0 until bitmap.width step 4) {
                    bitmap.setPixel(x, y, Color.rgb((x * 17) % 256, (y * 29) % 256, (x + y) % 256))
                }
            }
            FileOutputStream(source).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        val originalBytes = source.length()
        try {
            val prepared = AgentImageCompressor.prepare(
                AgentAttachment(source, "image/jpeg", "现场.jpg")
            )
            assertTrue(prepared.temporaryFile != null)
            assertTrue(prepared.attachment.file.length() <= AgentImageCompressor.TARGET_BYTES)
            assertEquals(originalBytes, source.length())
        } finally {
            source.delete()
        }
    }
}
