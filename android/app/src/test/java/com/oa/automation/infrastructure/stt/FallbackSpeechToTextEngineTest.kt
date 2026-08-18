package com.oa.automation.infrastructure.stt

import com.oa.automation.domain.model.ProcessingProgress
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackSpeechToTextEngineTest {
    @Test
    fun `local configuration creates a cloud-capable fallback engine`() {
        val engine = SpeechToTextEngine.fromConfig(
            STTConfig(
                engineType = STTEngineType.FASTER_WHISPER,
                localEndpoint = "http://10.0.2.2:8888",
                apiToken = "account-token",
                cloudEndpoint = "https://118.25.43.185/stt-cloud"
            )
        )

        assertTrue(engine is FallbackSpeechToTextEngine)
        assertEquals(STTEngineType.FASTER_WHISPER, engine.getEngineType())
    }

    @Test
    fun `cloud fallback is not used when local transcription succeeds`() = runBlocking {
        val local = FakeEngine(Result.success("local"), STTEngineType.FASTER_WHISPER)
        val cloud = FakeEngine(Result.success("cloud"), STTEngineType.TENCENT_HYBRID)
        val engine = FallbackSpeechToTextEngine(local, cloud)

        val result = engine.transcribe(File("audio.wav"))

        assertEquals("local", result.getOrThrow())
        assertEquals(1, local.transcribeCalls)
        assertEquals(0, cloud.transcribeCalls)
    }

    @Test
    fun `cloud fallback is used only after local transcription fails`() = runBlocking {
        val local = FakeEngine(Result.failure(IllegalStateException("local unavailable")), STTEngineType.FASTER_WHISPER)
        val cloud = FakeEngine(Result.success("cloud"), STTEngineType.TENCENT_HYBRID)
        val progress = mutableListOf<ProcessingProgress>()
        val engine = FallbackSpeechToTextEngine(local, cloud)

        val result = engine.transcribe(File("audio.wav"), progress::add)

        assertEquals("cloud", result.getOrThrow())
        assertEquals(1, local.transcribeCalls)
        assertEquals(1, cloud.transcribeCalls)
        assertTrue(progress.any { it.stage.contains("云端兜底") })
        assertEquals(progress.map { it.percent }.sorted(), progress.map { it.percent })
    }

    @Test
    fun `stream session remains bound to the service that created it`() = runBlocking {
        val local = FakeEngine(Result.success("local"), STTEngineType.FASTER_WHISPER)
        val cloud = FakeEngine(Result.success("cloud"), STTEngineType.TENCENT_HYBRID)
        val engine = FallbackSpeechToTextEngine(local, cloud)

        val result = engine.transcribeStreamSession("a".repeat(32))

        assertFalse(result.isSuccess)
        assertEquals(1, local.streamCalls)
        assertEquals(0, cloud.streamCalls)
    }

    private class FakeEngine(
        private val result: Result<String>,
        private val type: STTEngineType
    ) : SpeechToTextEngine {
        var transcribeCalls = 0
        var streamCalls = 0

        override suspend fun transcribe(
            audioFile: File,
            onProgress: (ProcessingProgress) -> Unit,
            meetingId: String?,
            archiveKey: String?
        ): Result<String> {
            transcribeCalls += 1
            onProgress(ProcessingProgress(if (type == STTEngineType.TENCENT_HYBRID) 20 else 40, "working"))
            return result
        }

        override suspend fun transcribeStreamSession(
            sessionId: String,
            onProgress: (ProcessingProgress) -> Unit
        ): Result<String> {
            streamCalls += 1
            return Result.failure(UnsupportedOperationException("no stream finalization"))
        }

        override fun getEngineType(): STTEngineType = type

        override fun getDisplayName(): String = type.displayName

        override fun isAvailable(): Boolean = true
    }
}
