package com.oa.automation.infrastructure.stt

import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.STTLanguage
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudSTTEngineTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `transcription sends configured model and bearer token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"text\":\"方言转写结果\"}"))
        val audio = File.createTempFile("cloud-asr-", ".wav").apply {
            writeBytes(ByteArray(64) { it.toByte() })
        }
        try {
            val engine = CloudSTTEngine(
                STTConfig(
                    engineType = STTEngineType.TENCENT_HYBRID,
                    language = STTLanguage.ENGLISH,
                    cloudEndpoint = server.url("/gateway").toString(),
                    cloudApiKey = "cloud-secret",
                    cloudModel = "dialect-asr-model"
                )
            )

            val result = engine.transcribe(audio)

            assertTrue(result.isSuccess)
            assertEquals("方言转写结果", result.getOrThrow())
            val request = server.takeRequest()
            assertEquals("/gateway/v1/audio/transcriptions", request.path)
            assertEquals("Bearer cloud-secret", request.getHeader("Authorization"))
            val multipart = request.body.readUtf8()
            assertTrue(multipart.contains("name=\"model\""))
            assertTrue(multipart.contains("dialect-asr-model"))
            assertTrue(multipart.contains("name=\"language\""))
            assertTrue(multipart.contains("en"))
            assertTrue(multipart.contains("name=\"file\""))
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `full transcription endpoint is not duplicated`() {
        val endpoint = server.url("/v1/audio/transcriptions").toString()

        assertEquals(endpoint, cloudTranscriptionUrl(endpoint).toString())
    }

    @Test
    fun `connection test uses models endpoint`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"data\":[]}"))
        val config = STTConfig(
            engineType = STTEngineType.TENCENT_HYBRID,
            cloudEndpoint = server.url("/v1").toString(),
            cloudApiKey = "cloud-secret",
            cloudModel = "dialect-asr-model"
        )

        val result = CloudSTTEngine.testConnection(config)

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer cloud-secret", request.getHeader("Authorization"))
    }

    @Test
    fun `nested provider response text is supported`() {
        assertEquals("识别结果", parseCloudTranscript("{\"result\":{\"text\":\"识别结果\"}}"))
    }

    @Test
    fun `managed cloud asr uses account stt token and meeting id`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"text\":\"托管识别结果\"}"))
        val audio = File.createTempFile("managed-cloud-asr-", ".wav").apply {
            writeBytes(ByteArray(64) { it.toByte() })
        }
        try {
            val engine = CloudSTTEngine(
                STTConfig(
                    engineType = STTEngineType.TENCENT_HYBRID,
                    apiToken = "account-stt-token",
                    cloudEndpoint = server.url("/cloud-asr").toString(),
                    cloudApiKey = null,
                    cloudModel = "tencent-flash"
                )
            )

            val result = engine.transcribe(
                audio,
                meetingId = "meeting-1",
                archiveKey = "transcript-1"
            )

            assertEquals("托管识别结果", result.getOrThrow())
            val request = server.takeRequest()
            assertEquals("Bearer account-stt-token", request.getHeader("Authorization"))
            assertEquals("meeting-1", request.getHeader("X-Meeting-Id"))
            assertEquals("transcript-1", request.getHeader("X-Archive-Key"))
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `tencent hybrid finalizes the server stream without uploading audio again`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"text\":\"极速版最终稿\"}"))
        val sessionId = "a".repeat(32)
        val engine = CloudSTTEngine(
            STTConfig(
                engineType = STTEngineType.TENCENT_HYBRID,
                localEndpoint = server.url("/managed").toString(),
                apiToken = "account-stt-token",
                cloudEndpoint = server.url("/managed/cloud-asr").toString(),
                cloudApiKey = null,
                cloudModel = "tencent-flash"
            )
        )

        val result = engine.transcribeStreamSession(sessionId)

        assertEquals("极速版最终稿", result.getOrThrow())
        assertEquals(STTEngineType.TENCENT_HYBRID, engine.getEngineType())
        val request = server.takeRequest()
        assertEquals("/managed/transcribe/stream/$sessionId", request.path)
        assertEquals("Bearer account-stt-token", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `tencent hybrid connection test requires realtime and flash readiness`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"realtime_asr":{"configured":true},"cloud_asr":{"configured":true}}"""
            )
        )
        val config = STTConfig(
            engineType = STTEngineType.TENCENT_HYBRID,
            localEndpoint = server.url("/managed").toString(),
            apiToken = "account-stt-token"
        )

        val result = CloudSTTEngine.testHybridConnection(config)

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("/managed/health", request.path)
        assertEquals("Bearer account-stt-token", request.getHeader("Authorization"))
    }
}
