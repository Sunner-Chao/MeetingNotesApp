package com.oa.automation.infrastructure.stt

import com.google.gson.JsonParser
import com.oa.automation.domain.model.STTLanguage
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingSttClientTest {
    @Test
    fun `tencent realtime provider is included in start control message`() {
        val payload = JsonParser.parseString(
            StreamingSttClient().buildStartControlMessage(
                meetingId = "meeting-1",
                provider = StreamingSttProvider.TENCENT_REALTIME
            )
        ).asJsonObject

        assertEquals("start", payload.get("event").asString)
        assertEquals("meeting-1", payload.get("meeting_id").asString)
        assertEquals("tencent-realtime", payload.get("stream_provider").asString)
        assertEquals("zh", payload.get("language").asString)
        assertEquals(16000, payload.get("sample_rate").asInt)
        assertEquals(1, payload.get("channels").asInt)
    }

    @Test
    fun `explicit standard Tencent provider is included in start control message`() {
        val payload = JsonParser.parseString(
            StreamingSttClient().buildStartControlMessage(
                meetingId = "meeting-1",
                provider = StreamingSttProvider.TENCENT_REALTIME_STANDARD
            )
        ).asJsonObject

        assertEquals("tencent-realtime-standard", payload.get("stream_provider").asString)
    }

    @Test
    fun `provider switch control message keeps the current websocket session`() {
        val payload = JsonParser.parseString(
            StreamingSttClient().buildSwitchProviderControlMessage(
                StreamingSttProvider.LOCAL
            )
        ).asJsonObject

        assertEquals("switch_provider", payload.get("event").asString)
        assertEquals("local", payload.get("stream_provider").asString)
    }

    @Test
    fun `english language is included in start and switch control messages`() {
        val client = StreamingSttClient()
        val start = JsonParser.parseString(
            client.buildStartControlMessage(
                meetingId = "meeting-english",
                provider = StreamingSttProvider.LOCAL,
                language = STTLanguage.ENGLISH
            )
        ).asJsonObject
        val switch = JsonParser.parseString(
            client.buildSwitchLanguageControlMessage(STTLanguage.ENGLISH)
        ).asJsonObject

        assertEquals("en", start.get("language").asString)
        assertEquals("switch_language", switch.get("event").asString)
        assertEquals("en", switch.get("language").asString)
    }

    @Test
    fun `transient websocket failures retry repeatedly and retain cloud provider`() {
        val server = MockWebServer()
        val startReceived = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val payload = JsonParser.parseString(text).asJsonObject
                        if (payload.get("event")?.asString == "start") {
                            assertEquals(
                                "tencent-realtime-standard",
                                payload.get("stream_provider").asString
                            )
                            startReceived.countDown()
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                }
            )
        )
        server.start()
        val client = StreamingSttClient(
            client = OkHttpClient.Builder().build(),
            reconnectDelay = {},
            maxReconnectAttempts = 3,
            baseDelayMs = 1,
            debugLog = {},
            warningLog = {}
        )
        try {
            client.start(
                endpoint = server.url("/stt").toString(),
                meetingId = "meeting-reconnect",
                streamProvider = StreamingSttProvider.TENCENT_REALTIME_STANDARD,
                onPartialText = {},
                onStatus = {},
                onError = errors::add
            )

            assertTrue("third WebSocket attempt did not connect", startReceived.await(5, TimeUnit.SECONDS))
            assertEquals(3, server.requestCount)
            assertTrue(errors.isEmpty())
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    @Test
    fun `terminal local websocket failure reports provider for automatic fallback`() {
        val server = MockWebServer()
        val failureLatch = CountDownLatch(1)
        val failedProvider = AtomicReference<StreamingSttProvider>()
        val failureDetail = AtomicReference<String>()
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        val client = StreamingSttClient(
            client = OkHttpClient.Builder().build(),
            reconnectDelay = {},
            maxReconnectAttempts = 0,
            debugLog = {},
            warningLog = {}
        )
        try {
            client.start(
                endpoint = server.url("/local").toString(),
                meetingId = "meeting-local-failure",
                streamProvider = StreamingSttProvider.LOCAL,
                onPartialText = {},
                onStatus = {},
                onError = {},
                onProviderFailure = { provider, detail ->
                    failedProvider.set(provider)
                    failureDetail.set(detail)
                    failureLatch.countDown()
                }
            )

            assertTrue(failureLatch.await(5, TimeUnit.SECONDS))
            assertEquals(StreamingSttProvider.LOCAL, failedProvider.get())
            assertTrue(failureDetail.get().isNotBlank())
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    @Test
    fun `cross service switch reconnects and disables partial session finalization`() = runBlocking {
        val localServer = MockWebServer()
        val cloudServer = MockWebServer()
        val localStarted = CountDownLatch(1)
        val cloudStarted = CountDownLatch(1)

        fun response(latch: CountDownLatch, sessionId: String) = MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val payload = JsonParser.parseString(text).asJsonObject
                    if (payload.get("event")?.asString == "start") {
                        webSocket.send(
                            """{"type":"status","session_id":"$sessionId","message":"ready"}"""
                        )
                        latch.countDown()
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }
        )

        localServer.enqueue(response(localStarted, "a".repeat(32)))
        cloudServer.enqueue(response(cloudStarted, "b".repeat(32)))
        localServer.start()
        cloudServer.start()
        val client = StreamingSttClient(
            client = OkHttpClient.Builder().build(),
            reconnectDelay = {},
            debugLog = {},
            warningLog = {}
        )
        try {
            client.start(
                endpoint = localServer.url("/local").toString(),
                meetingId = "meeting-switch",
                streamProvider = StreamingSttProvider.LOCAL,
                onPartialText = {},
                onStatus = {},
                onError = {}
            )
            assertTrue(localStarted.await(5, TimeUnit.SECONDS))

            assertTrue(
                client.switchService(
                    cloudServer.url("/cloud").toString(),
                    StreamingSttProvider.TENCENT_REALTIME_STANDARD
                ).isSuccess
            )
            assertTrue(cloudStarted.await(5, TimeUnit.SECONDS))
            assertEquals("/cloud/ws/transcribe-stream", cloudServer.takeRequest().path)
            assertNull(client.stop())
        } finally {
            client.stop()
            localServer.shutdown()
            cloudServer.shutdown()
        }
    }
}
