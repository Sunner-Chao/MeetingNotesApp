package com.oa.automation.infrastructure.stt

import com.oa.automation.domain.model.STTConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class STTServiceClientTest {
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
    fun `connection test validates health and bearer token`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"events\":[]}"))

        val result = STTServiceClient.testConnection(endpoint(), "valid-token")

        assertTrue(result.isSuccess)
        assertEquals("/health", server.takeRequest().path)
        val authRequest = server.takeRequest()
        assertEquals("/debug/stream-events?limit=1", authRequest.path)
        assertEquals("Bearer valid-token", authRequest.getHeader("Authorization"))
    }

    @Test
    fun `connection test reports an invalid bearer token`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"detail\":\"Unauthorized\"}"))

        val result = STTServiceClient.testConnection(endpoint(), "wrong-token")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("令牌无效"))
    }

    @Test
    fun `connection test rejects a missing token before network access`() {
        val result = STTServiceClient.testConnection(endpoint(), null)

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `transcription maps unauthorized response to actionable error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"detail\":\"Unauthorized\"}"))
        val audio = File.createTempFile("meetingnotes-test-", ".wav").apply {
            writeBytes(ByteArray(64))
        }
        try {
            val engine = FasterWhisperEngine(
                STTConfig(localEndpoint = endpoint(), apiToken = "wrong-token")
            )

            val result = engine.transcribe(audio)

            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("令牌无效"))
        } finally {
            audio.delete()
        }
    }

    private fun endpoint(): String = server.url("/").toString().trimEnd('/')
}
