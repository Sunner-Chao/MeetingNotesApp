package com.oa.automation.infrastructure.stt

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.oa.automation.infrastructure.audio.AudioRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayDeque
import android.util.Log
import com.oa.automation.locale.SimplifiedChineseText

class StreamingSttClient {

    private companion object {
        const val TAG = "StreamingSttClient"
        const val MAX_PENDING_AUDIO_BYTES =
            AudioRecorder.SAMPLE_RATE * AudioRecorder.CHANNEL_COUNT * 2 * 3
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isConnected = false

    // Reconnection state
    @Volatile
    private var endpoint: String = ""
    @Volatile
    private var apiToken: String? = null
    @Volatile
    private var isReconnecting = false
    private val reconnectLock = Any()
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 5
    private val baseDelayMs = 2000L
    private val sessionGeneration = AtomicLong(0)
    private val audioLock = Any()
    private val pendingAudio = ArrayDeque<ByteArray>()
    private var pendingAudioBytes = 0

    // Callback holders (set during start(), cleared during stop())
    private var onPartialTextCallback: ((StreamingTranscriptUpdate) -> Unit)? = null
    private var onStatusCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    fun start(
        endpoint: String,
        apiToken: String? = null,
        onPartialText: (StreamingTranscriptUpdate) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()  // tear down any existing connection before installing the new session

        // Store callbacks so reconnect logic can reuse them
        onPartialTextCallback = onPartialText
        onStatusCallback = onStatus
        onErrorCallback = onError
        this.endpoint = endpoint
        this.apiToken = apiToken
        val generation = sessionGeneration.incrementAndGet()
        reconnectAttempt = 0
        isReconnecting = false

        Log.d(TAG, "Starting streaming preview: ${endpoint.toStreamingWsUrl()}")

        connect(endpoint, generation, onPartialText, onStatus, onError)
    }

    private fun connect(
        endpoint: String,
        generation: Long,
        onPartialText: (StreamingTranscriptUpdate) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val requestBuilder = Request.Builder().url(endpoint.toStreamingWsUrl())
        apiToken?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
        }
        val request = requestBuilder.build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (generation != sessionGeneration.get()) {
                        webSocket.close(1000, "superseded")
                        return
                    }
                    Log.d(TAG, "WebSocket connected")
                    reconnectAttempt = 0
                    isReconnecting = false
                    webSocket.send(
                        gson.toJson(
                            StreamControlMessage(
                                event = "start",
                                sampleRate = AudioRecorder.SAMPLE_RATE,
                                channels = AudioRecorder.CHANNEL_COUNT
                            )
                        )
                    )
                    synchronized(audioLock) {
                        isConnected = true
                        while (pendingAudio.isNotEmpty()) {
                            val audio = pendingAudio.removeFirst()
                            pendingAudioBytes -= audio.size
                            webSocket.send(audio.toByteString())
                        }
                    }
                    onStatus("实时预览已连接")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (generation != sessionGeneration.get()) return
                    val message = runCatching {
                        gson.fromJson(text, StreamServerMessage::class.java)
                    }.getOrNull() ?: return

                    when (message.type) {
                        "partial" -> {
                            val committedText = SimplifiedChineseText.normalize(message.committedText.orEmpty())
                            val previewText = SimplifiedChineseText.normalize(message.previewText.orEmpty())
                            val textValue = SimplifiedChineseText.normalize(message.text.orEmpty())
                                .ifBlank { listOf(committedText, previewText).filter { it.isNotBlank() }.joinToString(" ") }
                            if (textValue.isNotBlank() || committedText.isNotBlank() || previewText.isNotBlank()) {
                                onPartialText(
                                    StreamingTranscriptUpdate(
                                        text = textValue,
                                        committedText = committedText,
                                        previewText = previewText
                                    )
                                )
                            }
                        }
                        "status" -> if (!message.message.isNullOrBlank()) onStatus(message.message)
                        "error" -> if (!message.message.isNullOrBlank()) onError(message.message)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (generation != sessionGeneration.get()) return
                    Log.d(TAG, "WebSocket closed: $code $reason")
                    isConnected = false
                    scheduleReconnectIfNeeded(generation)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (generation != sessionGeneration.get()) return
                    Log.w(TAG, "WebSocket failure: ${t.message}")
                    isConnected = false
                    onError("流式预览连接失败: ${t.message}")
                    scheduleReconnectIfNeeded(generation)
                }
            }
        )
    }

    private fun scheduleReconnectIfNeeded(generation: Long) {
        if (generation != sessionGeneration.get()) return
        if (reconnectAttempt >= maxReconnectAttempts) {
            isReconnecting = false
            return
        }
        synchronized(reconnectLock) {
            if (isReconnecting) return
            isReconnecting = true
        }

        reconnectAttempt++
        val delayMs = (baseDelayMs * (1 shl (reconnectAttempt - 1).coerceAtLeast(0))).coerceAtMost(30000L)

        Thread {
            Thread.sleep(delayMs)
            if (generation != sessionGeneration.get()) return@Thread
            val partialCb = onPartialTextCallback
            val statusCb = onStatusCallback
            val errorCb = onErrorCallback
            if (partialCb != null && statusCb != null && errorCb != null) {
                statusCb("正在重连流式预览 ($reconnectAttempt/$maxReconnectAttempts)...")
                connect(endpoint, generation, partialCb, statusCb, errorCb)
            } else {
                isReconnecting = false
            }
        }.start()
    }

    fun sendAudio(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        synchronized(audioLock) {
            val socket = webSocket
            if (isConnected && socket != null) {
                socket.send(pcmBytes.toByteString())
                return
            }

            val copy = pcmBytes.copyOf()
            pendingAudio.addLast(copy)
            pendingAudioBytes += copy.size
            while (pendingAudioBytes > MAX_PENDING_AUDIO_BYTES && pendingAudio.isNotEmpty()) {
                pendingAudioBytes -= pendingAudio.removeFirst().size
            }
        }
    }

    fun stop() {
        sessionGeneration.incrementAndGet()
        reconnectAttempt = maxReconnectAttempts  // prevent auto-reconnect
        isReconnecting = false
        if (isConnected) {
            webSocket?.send(gson.toJson(StreamControlMessage(event = "stop")))
        }
        webSocket?.close(1000, "client-stop")
        webSocket = null
        synchronized(audioLock) {
            isConnected = false
            pendingAudio.clear()
            pendingAudioBytes = 0
        }
        onPartialTextCallback = null
        onStatusCallback = null
        onErrorCallback = null
        apiToken = null
    }

    private fun String.toStreamingWsUrl(): String {
        val normalizedBase = removeSuffix("/")
        val wsBase = when {
            normalizedBase.startsWith("https://") -> normalizedBase.replaceFirst("https://", "wss://")
            normalizedBase.startsWith("http://") -> normalizedBase.replaceFirst("http://", "ws://")
            normalizedBase.startsWith("wss://") || normalizedBase.startsWith("ws://") -> normalizedBase
            else -> "ws://$normalizedBase"
        }
        return "$wsBase/ws/transcribe-stream"
    }

    private data class StreamControlMessage(
        val event: String,
        @SerializedName("sample_rate") val sampleRate: Int? = null,
        val channels: Int? = null
    )

    private data class StreamServerMessage(
        val type: String,
        val text: String? = null,
        @SerializedName("committed_text") val committedText: String? = null,
        @SerializedName("preview_text") val previewText: String? = null,
        val message: String? = null
    )
}

data class StreamingTranscriptUpdate(
    val text: String,
    val committedText: String,
    val previewText: String
)
