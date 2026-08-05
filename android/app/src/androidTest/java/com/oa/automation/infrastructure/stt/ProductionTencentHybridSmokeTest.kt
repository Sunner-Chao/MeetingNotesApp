package com.oa.automation.infrastructure.stt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.STTEngineType
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionTencentHybridSmokeTest {
    @Test
    fun realtimePreviewAndFlashFinalUseTheSameServerRecording() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configStore = ConfigDataStore(context)
        val initialConfig = configStore.appConfigFlow.first().sttConfig
        if (initialConfig.engineType != STTEngineType.TENCENT_HYBRID) {
            configStore.updateSTTConfig(
                initialConfig.copy(engineType = STTEngineType.TENCENT_HYBRID)
            )
        }
        val config = configStore.appConfigFlow.first().sttConfig
        assertEquals(STTEngineType.TENCENT_HYBRID, config.engineType)
        assertTrue("A current account STT token is required", !config.apiToken.isNullOrBlank())

        val recording = context.cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("oa_recording_") && file.extension == "wav"
        }?.maxByOrNull { it.lastModified() }
        assertNotNull("A recorded WAV is required for the production hybrid smoke test", recording)

        val connected = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val client = StreamingSttClient()
        client.start(
            endpoint = config.localEndpoint,
            meetingId = "production-hybrid-smoke",
            apiToken = config.apiToken,
            streamProvider = StreamingSttProvider.TENCENT_REALTIME,
            onPartialText = {},
            onStatus = { status ->
                if (status.contains("腾讯云实时识别已连接")) connected.countDown()
            },
            onError = { message -> error.set(message) }
        )

        try {
            assertTrue(
                "Tencent realtime handshake failed: ${error.get()}",
                connected.await(30, TimeUnit.SECONDS)
            )
            RandomAccessFile(recording!!, "r").use { source ->
                source.seek(44)
                val frame = ByteArray(6400)
                repeat(15) {
                    val read = source.read(frame)
                    if (read <= 0) return@repeat
                    client.sendAudio(if (read == frame.size) frame else frame.copyOf(read))
                    Thread.sleep(200)
                }
            }
            assertTrue("Tencent realtime stream failed: ${error.get()}", error.get().isNullOrBlank())

            val sessionId = client.stop()
            assertNotNull("Server stream session was not retained for finalization", sessionId)
            val finalResult = CloudSTTEngine(config).transcribeStreamSession(sessionId!!)

            assertTrue(
                "Hybrid final transcription failed: ${finalResult.exceptionOrNull()?.message}",
                finalResult.isSuccess
            )
            assertTrue("Hybrid final transcript is blank", finalResult.getOrNull()?.isNotBlank() == true)
        } finally {
            client.stop()
        }
    }
}
