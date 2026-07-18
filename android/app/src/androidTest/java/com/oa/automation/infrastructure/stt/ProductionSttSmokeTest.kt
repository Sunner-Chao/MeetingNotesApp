package com.oa.automation.infrastructure.stt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oa.automation.data.local.ConfigDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionSttSmokeTest {
    @Test
    fun latestRecordedAudioTranscribesWithProvisionedConfig() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = ConfigDataStore(context).appConfigFlow.first().sttConfig
        val recording = context.cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("oa_recording_") && file.extension == "wav"
        }?.maxByOrNull { it.lastModified() }

        assertTrue("A recorded WAV is required for the production smoke test", recording != null)
        val result = SpeechToTextEngine.fromConfig(config).transcribe(recording!!)

        assertTrue("Production STT failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertTrue("Production STT returned blank text", result.getOrNull()?.isNotBlank() == true)
    }
}
