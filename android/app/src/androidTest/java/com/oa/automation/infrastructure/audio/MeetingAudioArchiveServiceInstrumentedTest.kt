package com.oa.automation.infrastructure.audio

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oa.automation.data.local.ConfigDataStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeetingAudioArchiveServiceInstrumentedTest {
    @Test
    fun savePreparedCopiesAudioToSelectedUri() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "exports/audio-test").apply { mkdirs() }
        val source = File(directory, "source.wav")
        val destination = File(directory, "saved.wav")
        val expected = "RIFF-test-audio".encodeToByteArray()
        source.writeBytes(expected)
        destination.delete()

        try {
            val prepared = PreparedMeetingAudioShare(
                uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    source
                ),
                displayName = "meeting.wav",
                mimeType = "audio/wav"
            )
            val service = MeetingAudioArchiveService(context, ConfigDataStore(context))

            val result = service.savePrepared(prepared, Uri.fromFile(destination))

            assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
            assertArrayEquals(expected, destination.readBytes())
        } finally {
            source.delete()
            destination.delete()
        }
    }
}
