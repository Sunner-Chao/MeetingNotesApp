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

class StreamingSttClient {

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
    private var isReconnecting = false
    private val reconnectLock = Any()
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 5
    private val baseDelayMs = 2000L

    // Callback holders (set during start(), cleared during stop())
    private var onPartialTextCallback: ((StreamingTranscriptUpdate) -> Unit)? = null
    private var onStatusCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    fun start(
        endpoint: String,
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
        reconnectAttempt = 0
        isReconnecting = false

        android.util.Log.e("AUDIO", ">>> streamingSttClient.start() called, endpoint=$endpoint")

        connect(endpoint, onPartialText, onStatus, onError)
    }

    private fun connect(
        endpoint: String,
        onPartialText: (StreamingTranscriptUpdate) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = Request.Builder()
            .url(endpoint.toStreamingWsUrl())
            .build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    android.util.Log.e("AUDIO", "WebSocket OPENED, sending start event")
                    android.util.Log.e("AUDIO", "WS URL: ${endpoint}/ws/transcribe-stream")
                    isConnected = true
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
                    onStatus("流式预览已连接")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching {
                        gson.fromJson(text, StreamServerMessage::class.java)
                    }.getOrNull() ?: return

                    when (message.type) {
                        "partial" -> if (!message.text.isNullOrBlank()) {
                            onPartialText(
                                StreamingTranscriptUpdate(
                                    text = message.text,
                                    committedText = message.committedText.orEmpty(),
                                    previewText = message.previewText.orEmpty()
                                )
                            )
                        }
                        "status" -> if (!message.message.isNullOrBlank()) onStatus(message.message)
                        "error" -> if (!message.message.isNullOrBlank()) onError(message.message)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    android.util.Log.e("AUDIO", "WebSocket CLOSED code=$code reason=$reason")
                    isConnected = false
                    scheduleReconnectIfNeeded()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    android.util.Log.e("AUDIO", "WebSocket FAILURE: ${t.message}")
                    isConnected = false
                    android.util.Log.e("AUDIO", "WS endpoint was: $endpoint")
                    onError("流式预览连接失败: ${t.message}")
                    scheduleReconnectIfNeeded()
                }
            }
        )
    }

    private fun scheduleReconnectIfNeeded() {
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
            val partialCb = onPartialTextCallback
            val statusCb = onStatusCallback
            val errorCb = onErrorCallback
            if (partialCb != null && statusCb != null && errorCb != null) {
                statusCb("正在重连流式预览 ($reconnectAttempt/$maxReconnectAttempts)...")
                connect(endpoint, partialCb, statusCb, errorCb)
            } else {
                isReconnecting = false
            }
        }.start()
    }

    fun sendAudio(pcmBytes: ByteArray) {
        if (!isConnected) return
        android.util.Log.e("AUDIO", "sendAudio called, bytes=${pcmBytes.size}")
        webSocket?.send(pcmBytes.toByteString())
    }

    fun stop() {
        reconnectAttempt = maxReconnectAttempts  // prevent auto-reconnect
        isReconnecting = false
        if (isConnected) {
            webSocket?.send(gson.toJson(StreamControlMessage(event = "stop")))
        }
        webSocket?.close(1000, "client-stop")
        webSocket = null
        isConnected = false
        onPartialTextCallback = null
        onStatusCallback = null
        onErrorCallback = null
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
