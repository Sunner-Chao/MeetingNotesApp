package com.oa.automation.infrastructure.stt

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.oa.automation.BuildConfig
import com.oa.automation.domain.model.STTLanguage
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

class StreamingSttClient internal constructor(
    private val client: OkHttpClient = createHttpClient(),
    private val reconnectDelay: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
    private val maxReconnectAttempts: Int = 5,
    private val baseDelayMs: Long = 2000L,
    private val debugLog: (String) -> Unit = { message -> Log.d(TAG, message) },
    private val warningLog: (String) -> Unit = { message -> Log.w(TAG, message) }
) {

    private companion object {
        const val TAG = "StreamingSttClient"
        const val MAX_PENDING_AUDIO_BYTES =
            AudioRecorder.SAMPLE_RATE * AudioRecorder.CHANNEL_COUNT * 2 * 3

        fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isConnected = false

    @Volatile
    private var serverSessionId: String? = null

    @Volatile
    private var streamCanFinalize = false

    // Reconnection state
    @Volatile
    private var endpoint: String = ""
    @Volatile
    private var apiToken: String? = null
    @Volatile
    private var meetingId: String = ""
    @Volatile
    private var streamProvider: StreamingSttProvider = StreamingSttProvider.LOCAL
    @Volatile
    private var language: STTLanguage = STTLanguage.CHINESE
    @Volatile
    private var isReconnecting = false
    @Volatile
    private var authorizationRejected = false
    private val reconnectLock = Any()
    private var reconnectAttempt = 0
    private val sessionGeneration = AtomicLong(0)
    private val connectionGeneration = AtomicLong(0)
    private val terminalConnectionGeneration = AtomicLong(-1)
    private val audioLock = Any()
    private val pendingAudio = ArrayDeque<ByteArray>()
    private var pendingAudioBytes = 0
    private val providerSwitchLock = Any()
    private var pendingProviderSwitch: PendingProviderSwitch? = null
    private val languageSwitchLock = Any()
    private var pendingLanguageSwitch: PendingLanguageSwitch? = null

    // Callback holders (set during start(), cleared during stop())
    private var onPartialTextCallback: ((StreamingTranscriptUpdate) -> Unit)? = null
    private var onStatusCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    fun start(
        endpoint: String,
        meetingId: String,
        apiToken: String? = null,
        streamProvider: StreamingSttProvider = StreamingSttProvider.LOCAL,
        language: STTLanguage = STTLanguage.CHINESE,
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
        this.meetingId = meetingId
        this.streamProvider = streamProvider
        this.language = language
        serverSessionId = null
        streamCanFinalize = true
        authorizationRejected = false
        val generation = sessionGeneration.incrementAndGet()
        reconnectAttempt = 0
        isReconnecting = false

        debugLog("Starting streaming preview: ${endpoint.toStreamingWsUrl()}")

        connect(endpoint, generation, onPartialText, onStatus, onError)
    }

    private fun connect(
        endpoint: String,
        generation: Long,
        onPartialText: (StreamingTranscriptUpdate) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val connectionId = connectionGeneration.incrementAndGet()
        val requestBuilder = Request.Builder().url(endpoint.toStreamingWsUrl())
        apiToken?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
        }
        val request = requestBuilder.build()

        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrentConnection(generation, connectionId)) {
                        webSocket.close(1000, "superseded")
                        return
                    }
                    debugLog("WebSocket connected")
                    if (reconnectAttempt > 0) {
                        streamCanFinalize = false
                    }
                    reconnectAttempt = 0
                    isReconnecting = false
                    if (!webSocket.send(buildStartControlMessage(meetingId, streamProvider, language))) {
                        webSocket.cancel()
                        handleConnectionTermination(
                            generation = generation,
                            connectionId = connectionId,
                            detail = "实时预览启动请求发送失败",
                            onStatus = onStatus,
                            onError = onError
                        )
                        return
                    }
                    var pendingFlushFailed = false
                    synchronized(audioLock) {
                        isConnected = true
                        while (pendingAudio.isNotEmpty()) {
                            val audio = pendingAudio.first()
                            if (!webSocket.send(audio.toByteString())) {
                                pendingFlushFailed = true
                                streamCanFinalize = false
                                break
                            }
                            pendingAudio.removeFirst()
                            pendingAudioBytes -= audio.size
                        }
                    }
                    if (pendingFlushFailed) {
                        webSocket.cancel()
                        handleConnectionTermination(
                            generation = generation,
                            connectionId = connectionId,
                            detail = "缓存音频发送失败",
                            onStatus = onStatus,
                            onError = onError
                        )
                        return
                    }
                    onStatus("实时预览已连接")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrentConnection(generation, connectionId)) return
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
                                        previewText = previewText,
                                        sessionId = serverSessionId.orEmpty()
                                    )
                                )
                            }
                        }
                        "status" -> {
                            message.sessionId?.takeIf { it.matches(Regex("^[0-9a-f]{32}$")) }?.let { id ->
                                val previousId = serverSessionId
                                if (previousId != null && previousId != id) {
                                    streamCanFinalize = false
                                }
                                serverSessionId = id
                            }
                            message.streamProvider?.let(::completeProviderSwitch)
                            message.language?.let(::completeLanguageSwitch)
                            if (!message.message.isNullOrBlank()) onStatus(message.message)
                        }
                        "error" -> if (!message.message.isNullOrBlank()) {
                            val errorMessage = message.message.orEmpty()
                            failProviderSwitch(errorMessage)
                            failLanguageSwitch(errorMessage)
                            if (isAuthorizationError(errorMessage)) {
                                authorizationRejected = true
                                isConnected = false
                                streamCanFinalize = false
                                onError("STT 访问令牌无效或无权限，请到服务设置中更新令牌")
                                webSocket.close(1008, "unauthorized")
                            } else {
                                onError(errorMessage)
                            }
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrentConnection(generation, connectionId)) return
                    debugLog("WebSocket closed: $code $reason")
                    handleConnectionTermination(
                        generation = generation,
                        connectionId = connectionId,
                        detail = reason.ifBlank { "流式连接已关闭" },
                        authorizationFailure = authorizationRejected || code == 1008,
                        onStatus = onStatus,
                        onError = onError
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrentConnection(generation, connectionId)) return
                    warningLog("WebSocket failure: ${t.message}")
                    handleConnectionTermination(
                        generation = generation,
                        connectionId = connectionId,
                        detail = t.message?.takeIf { it.isNotBlank() } ?: "网络连接已断开",
                        authorizationFailure = response?.code == 401 || response?.code == 403,
                        onStatus = onStatus,
                        onError = onError
                    )
                }
            }
        )
        if (isCurrentConnection(generation, connectionId)) {
            webSocket = socket
        } else {
            socket.cancel()
        }
    }

    private fun handleConnectionTermination(
        generation: Long,
        connectionId: Long,
        detail: String,
        authorizationFailure: Boolean = false,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isCurrentConnection(generation, connectionId)) return
        if (terminalConnectionGeneration.getAndSet(connectionId) == connectionId) return
        synchronized(audioLock) {
            isConnected = false
        }
        webSocket = null
        streamCanFinalize = false
        failProviderSwitch(detail)
        failLanguageSwitch(detail)
        isReconnecting = false
        if (authorizationFailure || authorizationRejected) {
            authorizationRejected = true
            onError("STT 访问令牌无效或无权限，请到服务设置中更新令牌")
            return
        }
        if (scheduleReconnectIfNeeded(generation)) {
            onStatus("网络波动，正在恢复实时预览")
        } else {
            onError("实时预览连接失败：$detail，请检查网络后重试")
        }
    }

    private fun isCurrentConnection(generation: Long, connectionId: Long): Boolean =
        generation == sessionGeneration.get() && connectionId == connectionGeneration.get()

    private fun scheduleReconnectIfNeeded(generation: Long): Boolean {
        val attempt: Int
        synchronized(reconnectLock) {
            if (generation != sessionGeneration.get()) return false
            if (isReconnecting) return true
            if (reconnectAttempt >= maxReconnectAttempts) return false
            isReconnecting = true
            reconnectAttempt++
            attempt = reconnectAttempt
        }

        val delayMs = (baseDelayMs * (1 shl (attempt - 1).coerceAtLeast(0))).coerceAtMost(30000L)

        Thread {
            reconnectDelay(delayMs)
            if (generation != sessionGeneration.get()) {
                isReconnecting = false
                return@Thread
            }
            val partialCb = onPartialTextCallback
            val statusCb = onStatusCallback
            val errorCb = onErrorCallback
            if (partialCb != null && statusCb != null && errorCb != null) {
                statusCb("正在重连流式预览 ($attempt/$maxReconnectAttempts)...")
                connect(endpoint, generation, partialCb, statusCb, errorCb)
            } else {
                isReconnecting = false
            }
        }.apply {
            name = "StreamingSttReconnect-$attempt"
            isDaemon = true
            start()
        }
        return true
    }

    fun sendAudio(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        var failedSocket: WebSocket? = null
        synchronized(audioLock) {
            val socket = webSocket
            if (isConnected && socket != null) {
                if (socket.send(pcmBytes.toByteString())) return
                streamCanFinalize = false
                isConnected = false
                failedSocket = socket
            }

            val copy = pcmBytes.copyOf()
            pendingAudio.addLast(copy)
            pendingAudioBytes += copy.size
            while (pendingAudioBytes > MAX_PENDING_AUDIO_BYTES && pendingAudio.isNotEmpty()) {
                pendingAudioBytes -= pendingAudio.removeFirst().size
                streamCanFinalize = false
            }
        }
        failedSocket?.cancel()
    }

    suspend fun switchProvider(provider: StreamingSttProvider): Result<Unit> = runCatching {
        if (provider == streamProvider) return@runCatching
        val socket = webSocket
        require(isConnected && socket != null) { "实时预览尚未连接" }
        val pending = PendingProviderSwitch(provider, CompletableDeferred())
        synchronized(providerSwitchLock) {
            check(pendingProviderSwitch == null) { "识别引擎正在切换" }
            pendingProviderSwitch = pending
        }
        try {
            check(socket.send(buildSwitchProviderControlMessage(provider))) {
                "识别引擎切换请求发送失败"
            }
            withTimeout(BuildConfig.STT_STREAM_SWITCH_TIMEOUT_SECONDS * 1_000L) {
                pending.completion.await()
            }
            streamProvider = provider
        } finally {
            synchronized(providerSwitchLock) {
                if (pendingProviderSwitch === pending) pendingProviderSwitch = null
            }
        }
    }

    suspend fun switchLanguage(nextLanguage: STTLanguage): Result<Unit> = runCatching {
        if (nextLanguage == language) return@runCatching
        val socket = webSocket
        require(isConnected && socket != null) { "实时预览尚未连接" }
        val pending = PendingLanguageSwitch(nextLanguage, CompletableDeferred())
        synchronized(languageSwitchLock) {
            check(pendingLanguageSwitch == null) { "识别语言正在切换" }
            pendingLanguageSwitch = pending
        }
        try {
            check(socket.send(buildSwitchLanguageControlMessage(nextLanguage))) {
                "识别语言切换请求发送失败"
            }
            withTimeout(BuildConfig.STT_STREAM_SWITCH_TIMEOUT_SECONDS * 1_000L) {
                pending.completion.await()
            }
            language = nextLanguage
        } finally {
            synchronized(languageSwitchLock) {
                if (pendingLanguageSwitch === pending) pendingLanguageSwitch = null
            }
        }
    }

    fun stop(): String? {
        var finalizationSession = serverSessionId?.takeIf { isConnected && streamCanFinalize }
        sessionGeneration.incrementAndGet()
        connectionGeneration.incrementAndGet()
        reconnectAttempt = maxReconnectAttempts  // prevent auto-reconnect
        isReconnecting = false
        failProviderSwitch("流式识别已结束")
        failLanguageSwitch("流式识别已结束")
        if (isConnected) {
            val stopSent = webSocket?.send(gson.toJson(StreamControlMessage(event = "stop"))) == true
            if (!stopSent) finalizationSession = null
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
        meetingId = ""
        streamProvider = StreamingSttProvider.LOCAL
        language = STTLanguage.CHINESE
        authorizationRejected = false
        serverSessionId = null
        streamCanFinalize = false
        return finalizationSession
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

    private fun isAuthorizationError(message: String): Boolean {
        val normalized = message.trim().lowercase()
        return normalized.contains("unauthorized") ||
            normalized.contains("invalid bearer") ||
            normalized.contains("invalid token") ||
            normalized.contains("访问令牌") ||
            normalized.contains("token") && normalized.contains("invalid")
    }

    internal fun buildStartControlMessage(
        meetingId: String,
        provider: StreamingSttProvider,
        language: STTLanguage = STTLanguage.CHINESE
    ): String = gson.toJson(
        StreamControlMessage(
            event = "start",
            sampleRate = AudioRecorder.SAMPLE_RATE,
            channels = AudioRecorder.CHANNEL_COUNT,
            meetingId = meetingId,
            streamProvider = provider.wireValue,
            language = language.requestValue
        )
    )

    internal fun buildSwitchProviderControlMessage(
        provider: StreamingSttProvider
    ): String = gson.toJson(
        StreamControlMessage(
            event = "switch_provider",
            streamProvider = provider.wireValue
        )
    )

    internal fun buildSwitchLanguageControlMessage(
        language: STTLanguage
    ): String = gson.toJson(
        StreamControlMessage(
            event = "switch_language",
            language = language.requestValue
        )
    )

    private fun completeProviderSwitch(wireValue: String) {
        val acknowledgedProvider = StreamingSttProvider.entries
            .firstOrNull { it.wireValue == wireValue }
            ?: return
        streamProvider = acknowledgedProvider
        val pending = synchronized(providerSwitchLock) { pendingProviderSwitch } ?: return
        if (pending.provider == acknowledgedProvider) {
            pending.completion.complete(Unit)
        }
    }

    private fun failProviderSwitch(message: String) {
        val pending = synchronized(providerSwitchLock) { pendingProviderSwitch } ?: return
        pending.completion.completeExceptionally(IllegalStateException(message))
    }

    private fun completeLanguageSwitch(requestValue: String) {
        val acknowledgedLanguage = STTLanguage.entries
            .firstOrNull { it.requestValue == requestValue }
            ?: return
        language = acknowledgedLanguage
        val pending = synchronized(languageSwitchLock) { pendingLanguageSwitch } ?: return
        if (pending.language == acknowledgedLanguage) {
            pending.completion.complete(Unit)
        }
    }

    private fun failLanguageSwitch(message: String) {
        val pending = synchronized(languageSwitchLock) { pendingLanguageSwitch } ?: return
        pending.completion.completeExceptionally(IllegalStateException(message))
    }

    private data class StreamControlMessage(
        val event: String,
        @SerializedName("sample_rate") val sampleRate: Int? = null,
        val channels: Int? = null,
        @SerializedName("meeting_id") val meetingId: String? = null,
        @SerializedName("stream_provider") val streamProvider: String? = null,
        val language: String? = null
    )

    private data class StreamServerMessage(
        val type: String,
        val text: String? = null,
        @SerializedName("committed_text") val committedText: String? = null,
        @SerializedName("preview_text") val previewText: String? = null,
        @SerializedName("session_id") val sessionId: String? = null,
        @SerializedName("stream_provider") val streamProvider: String? = null,
        val language: String? = null,
        val message: String? = null
    )

    private data class PendingProviderSwitch(
        val provider: StreamingSttProvider,
        val completion: CompletableDeferred<Unit>
    )

    private data class PendingLanguageSwitch(
        val language: STTLanguage,
        val completion: CompletableDeferred<Unit>
    )
}

enum class StreamingSttProvider(val wireValue: String) {
    LOCAL("local"),
    TENCENT_REALTIME_STANDARD("tencent-realtime-standard"),
    TENCENT_REALTIME_PRECISION("tencent-realtime-precision"),
    /** Old servers accept this alias as standard; new clients should use the explicit tier. */
    TENCENT_REALTIME("tencent-realtime")
}

data class StreamingTranscriptUpdate(
    val text: String,
    val committedText: String,
    val previewText: String,
    val sessionId: String = ""
)
