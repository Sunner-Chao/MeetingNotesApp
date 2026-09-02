package com.oa.automation.infrastructure.stt

import com.google.gson.JsonParser
import com.oa.automation.domain.model.STTLanguage
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    fun `sparse provider speaker ids use stable compact labels across partial updates`() {
        val server = MockWebServer()
        val updates = Collections.synchronizedList(mutableListOf<StreamingTranscriptUpdate>())
        val updatesReceived = CountDownLatch(2)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val payload = JsonParser.parseString(text).asJsonObject
                        if (payload.get("event")?.asString == "start") {
                            webSocket.send(
                                """{
                                    "type":"partial",
                                    "segments":[{"start":0.0,"end":1.0,"text":"甲先介绍。","speaker_id":7,"committed":true}],
                                    "diarization":{"enabled":true,"active":true}
                                }""".trimIndent()
                            )
                            webSocket.send(
                                """{
                                    "type":"partial",
                                    "segments":[
                                        {"start":0.0,"end":1.0,"text":"甲先介绍。","speaker_id":7,"committed":true},
                                        {"start":1.0,"end":2.0,"text":"乙再补充。","speaker_id":9,"committed":true}
                                    ],
                                    "diarization":{"enabled":true,"active":true}
                                }""".trimIndent()
                            )
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
            debugLog = {},
            warningLog = {}
        )
        try {
            client.start(
                endpoint = server.url("/speaker-normalization").toString(),
                meetingId = "meeting-speakers",
                speakerDiarization = true,
                onPartialText = { update ->
                    updates += update
                    updatesReceived.countDown()
                },
                onStatus = {},
                onError = {}
            )

            assertTrue("speaker partial updates were not received", updatesReceived.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(0), updates[0].segments.map { it.speaker })
            assertEquals("说话人 1：甲先介绍。", updates[0].text)
            assertEquals(listOf(0, 1), updates[1].segments.map { it.speaker })
            assertEquals("说话人 1：甲先介绍。\n说话人 2：乙再补充。", updates[1].text)
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    @Test
    fun `speaker labels reset for a different meeting`() {
        val server = MockWebServer()
        val firstUpdate = AtomicReference<StreamingTranscriptUpdate>()
        val secondUpdate = AtomicReference<StreamingTranscriptUpdate>()
        val firstReceived = CountDownLatch(1)
        val secondReceived = CountDownLatch(1)

        fun response(speakerId: Int) = MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val payload = JsonParser.parseString(text).asJsonObject
                    if (payload.get("event")?.asString == "start") {
                        webSocket.send(
                            """{
                                "type":"partial",
                                "segments":[{"start":0.0,"end":1.0,"text":"一段发言。","speaker_id":$speakerId,"committed":true}],
                                "diarization":{"enabled":true,"active":true}
                            }""".trimIndent()
                        )
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }
        )

        server.enqueue(response(7))
        server.enqueue(response(9))
        server.start()
        val client = StreamingSttClient(
            client = OkHttpClient.Builder().build(),
            reconnectDelay = {},
            debugLog = {},
            warningLog = {}
        )
        try {
            client.start(
                endpoint = server.url("/speaker-reset").toString(),
                meetingId = "meeting-a",
                speakerDiarization = true,
                onPartialText = { update ->
                    firstUpdate.set(update)
                    firstReceived.countDown()
                },
                onStatus = {},
                onError = {}
            )
            assertTrue("first speaker update was not received", firstReceived.await(5, TimeUnit.SECONDS))

            client.start(
                endpoint = server.url("/speaker-reset").toString(),
                meetingId = "meeting-b",
                speakerDiarization = true,
                onPartialText = { update ->
                    secondUpdate.set(update)
                    secondReceived.countDown()
                },
                onStatus = {},
                onError = {}
            )
            assertTrue("second speaker update was not received", secondReceived.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(0), firstUpdate.get().segments.map { it.speaker })
            assertEquals(listOf(0), secondUpdate.get().segments.map { it.speaker })
            assertEquals("说话人 1：一段发言。", firstUpdate.get().text)
            assertEquals("说话人 1：一段发言。", secondUpdate.get().text)
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    @Test
    fun `local stream uses three one-second recovery attempts`() {
        assertEquals(3, LOCAL_STREAM_RECONNECT_ATTEMPTS)
        assertEquals(3L, STREAM_PING_INTERVAL_SECONDS)
        assertEquals(
            listOf(1_000L, 1_000L, 1_000L),
            (1..LOCAL_STREAM_RECONNECT_ATTEMPTS).map { attempt ->
                streamingReconnectDelayMs(
                    attempt = attempt,
                    provider = StreamingSttProvider.LOCAL,
                    baseDelayMs = LOCAL_STREAM_RECONNECT_DELAY_MS
                )
            }
        )
    }

    @Test
    fun `cloud reconnect retains bounded exponential backoff`() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L),
            (1..5).map { attempt ->
                streamingReconnectDelayMs(
                    attempt = attempt,
                    provider = StreamingSttProvider.TENCENT_REALTIME_STANDARD,
                    baseDelayMs = 1_000L
                )
            }
        )
    }

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
    fun `meeting context is included in start control message`() {
        val payload = JsonParser.parseString(
            StreamingSttClient().buildStartControlMessage(
                meetingId = "meeting-context",
                provider = StreamingSttProvider.LOCAL,
                contextHint = "大佛寺研学考察"
            )
        ).asJsonObject

        assertEquals("大佛寺研学考察", payload.get("context_hint").asString)
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
    fun `server error message closes broken socket and reconnects`() {
        val server = MockWebServer()
        val firstStartReceived = CountDownLatch(1)
        val secondStartReceived = CountDownLatch(1)
        val statuses = Collections.synchronizedList(mutableListOf<String>())
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val payload = JsonParser.parseString(text).asJsonObject
                        if (payload.get("event")?.asString == "start") {
                            firstStartReceived.countDown()
                            webSocket.send("""{"type":"error","message":"provider reset"}""")
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                }
            )
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val payload = JsonParser.parseString(text).asJsonObject
                        if (payload.get("event")?.asString == "start") {
                            secondStartReceived.countDown()
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
            maxReconnectAttempts = 1,
            baseDelayMs = 1,
            debugLog = {},
            warningLog = {}
        )
        try {
            client.start(
                endpoint = server.url("/local").toString(),
                meetingId = "meeting-server-error",
                streamProvider = StreamingSttProvider.LOCAL,
                onPartialText = {},
                onStatus = statuses::add,
                onError = {}
            )

            assertTrue(firstStartReceived.await(5, TimeUnit.SECONDS))
            assertTrue("server error did not trigger reconnect", secondStartReceived.await(5, TimeUnit.SECONDS))
            assertEquals(2, server.requestCount)
            assertTrue(statuses.any { it.startsWith("本地连接波动") })
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
    fun `local recovery deadline reports one provider failure after three seconds`() {
        val server = MockWebServer()
        val deadlineStarted = CountDownLatch(1)
        val releaseDeadline = CountDownLatch(1)
        val providerFailed = CountDownLatch(1)
        val callbackCount = AtomicInteger(0)
        val requestedDeadlineMs = AtomicLong(-1L)
        val failureDetail = AtomicReference<String>()
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        val client = StreamingSttClient(
            client = OkHttpClient.Builder().build(),
            reconnectDelay = { Thread.sleep(500) },
            maxReconnectAttempts = LOCAL_STREAM_RECONNECT_ATTEMPTS,
            localRecoveryDelay = { delayMs ->
                requestedDeadlineMs.set(delayMs)
                deadlineStarted.countDown()
                releaseDeadline.await(5, TimeUnit.SECONDS)
            },
            debugLog = {},
            warningLog = {}
        )
        try {
            client.start(
                endpoint = server.url("/local").toString(),
                meetingId = "meeting-local-deadline",
                streamProvider = StreamingSttProvider.LOCAL,
                onPartialText = {},
                onStatus = {},
                onError = {},
                onProviderFailure = { provider, detail ->
                    assertEquals(StreamingSttProvider.LOCAL, provider)
                    callbackCount.incrementAndGet()
                    failureDetail.set(detail)
                    providerFailed.countDown()
                }
            )

            assertTrue("local recovery deadline was not scheduled", deadlineStarted.await(5, TimeUnit.SECONDS))
            assertEquals(LOCAL_STREAM_FAILOVER_WINDOW_MS, requestedDeadlineMs.get())
            releaseDeadline.countDown()
            assertTrue("provider failure was not reported", providerFailed.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(1, callbackCount.get())
            assertTrue(failureDetail.get().contains("3 秒快速恢复窗口"))
        } finally {
            releaseDeadline.countDown()
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
