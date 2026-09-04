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
import okhttp3.Dns

internal const val LOCAL_STREAM_RECONNECT_ATTEMPTS = 3
internal const val LOCAL_STREAM_RECONNECT_DELAY_MS = 1_000L
internal const val LOCAL_STREAM_FAILOVER_WINDOW_MS = 3_000L
internal const val STREAM_PING_INTERVAL_SECONDS = 3L
internal const val LOCAL_STREAM_READY_STATUS = "本地实时识别已就绪"
internal const val CLOUD_STREAM_READY_STATUS = "云端实时识别已接管"

internal fun streamingReconnectDelayMs(
    attempt: Int,
    provider: StreamingSttProvider,
    baseDelayMs: Long
): Long {
    val safeBase = baseDelayMs.coerceAtLeast(0L)
    if (provider == StreamingSttProvider.LOCAL) return safeBase
    return (safeBase * (1L shl (attempt - 1).coerceIn(0, 30))).coerceAtMost(30_000L)
}

class StreamingSttClient internal constructor(
    private val client: OkHttpClient = createHttpClient(STT_LOCAL_DNS),
    private val reconnectDelay: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
    private val maxReconnectAttempts: Int = LOCAL_STREAM_RECONNECT_ATTEMPTS,
    private val baseDelayMs: Long = LOCAL_STREAM_RECONNECT_DELAY_MS,
    private val maxCloudReconnectAttempts: Int = 5,
    private val localRecoveryDelay: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
    private val debugLog: (String) -> Unit = { message -> Log.d(TAG, message) },
    private val warningLog: (String) -> Unit = { message -> Log.w(TAG, message) }
) {

    private companion object {
        const val TAG = "StreamingSttClient"
        const val MAX_PENDING_AUDIO_BYTES =
            AudioRecorder.SAMPLE_RATE * AudioRecorder.CHANNEL_COUNT * 2 * 3

        fun createHttpClient(dns: Dns = Dns.SYSTEM): OkHttpClient = OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // Mobile proxy paths can become half-open while PCM frames appear
            // to send normally. A short ping makes local-to-cloud failover
            // observable within seconds instead of about a minute.
            .pingInterval(STREAM_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    // Local STT may be exposed through the Windows host's IPv6 address. Keep
    // the injected client for local traffic and use the IPv4 relay only for
    // Tencent/cloud traffic, whose endpoint is intentionally IPv4-first.
    private val cloudClient: OkHttpClient = createHttpClient(STT_IPV4_RELAY_DNS)

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
    private var contextHint: String = ""
    private var speakerDiarization: Boolean = false
    @Volatile
    private var isReconnecting = false
    @Volatile
    private var authorizationRejected = false
    private val reconnectLock = Any()
    private var reconnectAttempt = 0
    private val sessionGeneration = AtomicLong(0)
    private val connectionGeneration = AtomicLong(0)
    private val terminalConnectionGeneration = AtomicLong(-1)
    private val providerFailureGeneration = AtomicLong(-1)
    private val localRecoveryEpoch = AtomicLong(0)
    private val audioLock = Any()
    private val pendingAudio = ArrayDeque<ByteArray>()
    private var pendingAudioBytes = 0
    private var producedAudioBytes = 0L
    @Volatile
    private var connectionTimelineOffsetSeconds = 0f
    private val speakerLabelLock = Any()
    private val speakerLabels = linkedMapOf<Int, Int>()
    private var speakerLabelMeetingId: String? = null
    private val providerSwitchLock = Any()
    private var pendingProviderSwitch: PendingProviderSwitch? = null
    private val languageSwitchLock = Any()
    private var pendingLanguageSwitch: PendingLanguageSwitch? = null
    @Volatile
    private var connectionReady: CompletableDeferred<Unit>? = null
    private var serverReady = false
    @Volatile
    private var localRecoveryDeadlineScheduled = false

    // Callback holders (set during start(), cleared during stop())
    private var onPartialTextCallback: ((StreamingTranscriptUpdate) -> Unit)? = null
    private var onStatusCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onProviderFailureCallback: ((StreamingSttProvider, String) -> Unit)? = null

    fun start(
        endpoint: String,
        meetingId: String,
        apiToken: String? = null,
        streamProvider: StreamingSttProvider = StreamingSttProvider.LOCAL,
        language: STTLanguage = STTLanguage.CHINESE,
        contextHint: String? = null,
        speakerDiarization: Boolean = false,
        allowFinalization: Boolean = true,
        onPartialText: (StreamingTranscriptUpdate) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit,
        onProviderFailure: (StreamingSttProvider, String) -> Unit = { _, _ -> },
        connectionReady: CompletableDeferred<Unit>? = null,
        preserveAudioBuffer: Boolean = false
    ) {
        // A provider switch reuses the same meeting and should keep its
        // speaker labels. A completed meeting (or a different meeting) starts
        // a fresh label map.
        val reuseSpeakerLabels = this.meetingId == meetingId && meetingId.isNotBlank()
        stopInternal(
            clearSpeakerLabels = !reuseSpeakerLabels,
            preserveAudioBuffer = preserveAudioBuffer
        )

        // Store callbacks so reconnect logic can reuse them
        onPartialTextCallback = onPartialText
        onStatusCallback = onStatus
        onErrorCallback = onError
        onProviderFailureCallback = onProviderFailure
        this.endpoint = endpoint
        this.apiToken = apiToken
        this.meetingId = meetingId
        this.streamProvider = streamProvider
        this.language = language
        this.contextHint = contextHint.orEmpty()
        this.speakerDiarization = speakerDiarization
        this.connectionReady = connectionReady
        synchronized(speakerLabelLock) {
            if (speakerLabelMeetingId != meetingId) {
                speakerLabels.clear()
                speakerLabelMeetingId = meetingId
            }
        }
        serverSessionId = null
        serverReady = false
        localRecoveryDeadlineScheduled = false
        localRecoveryEpoch.incrementAndGet()
        providerFailureGeneration.set(-1)
        streamCanFinalize = allowFinalization
        authorizationRejected = false
        val generation = sessionGeneration.incrementAndGet()
        reconnectAttempt = 0
        isReconnecting = false

        debugLog(
            "Starting streaming preview endpoint=${endpoint.toStreamingWsUrl()} " +
                "provider=${streamProvider.wireValue} tokenPresent=${!apiToken.isNullOrBlank()}"
        )

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

        val socketClient = if (streamProvider == StreamingSttProvider.LOCAL) {
            client
        } else {
            cloudClient
        }
        val socket = socketClient.newWebSocket(
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
                    if (!webSocket.send(
                            buildStartControlMessage(
                                meetingId = meetingId,
                                provider = streamProvider,
                                language = language,
                                contextHint = contextHint,
                                speakerDiarization = speakerDiarization
                            )
                        )
                    ) {
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
                        // A reconnect starts a fresh server timeline. The server
                        // receives only the retained PCM queue, so keep the
                        // absolute start of that queue for persisted segments.
                        connectionTimelineOffsetSeconds = audioBytesToSeconds(
                            (producedAudioBytes - pendingAudioBytes).coerceAtLeast(0L)
                        )
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
                            val segments = message.segments.orEmpty().mapNotNull { segment ->
                                val segmentText = SimplifiedChineseText.normalize(segment.text.orEmpty()).trim()
                                if (segmentText.isBlank()) {
                                    null
                                } else {
                                    StreamingTranscriptSegment(
                                        startSeconds = segment.start?.coerceAtLeast(0f) ?: 0f,
                                        endSeconds = (segment.end ?: segment.start ?: 0f).coerceAtLeast(0f),
                                        text = segmentText,
                                        speaker = normalizeSpeakerId(segment.speaker ?: segment.speakerId),
                                        committed = segment.committed ?: false
                                    )
                                }
                            }
                            val structuredText = formatStreamingSpeakerPreview(segments)
                            val textValue = structuredText.ifBlank {
                                SimplifiedChineseText.normalize(message.text.orEmpty())
                                .ifBlank { listOf(committedText, previewText).filter { it.isNotBlank() }.joinToString(" ") }
                            }
                            val displayCommitted = if (structuredText.isNotBlank()) structuredText else committedText
                            val displayPreview = if (structuredText.isNotBlank()) "" else previewText
                            if (textValue.isNotBlank() || displayCommitted.isNotBlank() || displayPreview.isNotBlank()) {
                                onPartialText(
                                    StreamingTranscriptUpdate(
                                        text = textValue,
                                        committedText = displayCommitted,
                                        previewText = displayPreview,
                                        sessionId = serverSessionId.orEmpty(),
                                        timelineOffsetSeconds = connectionTimelineOffsetSeconds,
                                        segments = segments,
                                        diarizationEnabled = message.diarization?.enabled ?: false,
                                        diarizationActive = message.diarization?.active ?: segments.any { it.speaker != null }
                                    )
                                )
                            }
                        }
                        "status" -> {
                            val acceptedSessionId = message.sessionId
                                ?.takeIf { it.matches(Regex("^[0-9a-f]{32}$")) }
                            acceptedSessionId?.let { id ->
                                val previousId = serverSessionId
                                if (previousId != null && previousId != id) {
                                    streamCanFinalize = false
                                }
                                serverSessionId = id
                            }
                            message.streamProvider?.let(::completeProviderSwitch)
                            message.language?.let(::completeLanguageSwitch)
                            if (acceptedSessionId != null) {
                                // A TCP/WebSocket handshake alone is not
                                // enough: the server must accept the start
                                // event and issue a session id.
                                connectionReady?.complete(Unit)
                                connectionReady = null
                                serverReady = true
                                synchronized(reconnectLock) {
                                    localRecoveryDeadlineScheduled = false
                                    localRecoveryEpoch.incrementAndGet()
                                }
                                onStatus(
                                    if (streamProvider == StreamingSttProvider.LOCAL) {
                                        LOCAL_STREAM_READY_STATUS
                                    } else {
                                        CLOUD_STREAM_READY_STATUS
                                    }
                                )
                            }
                            if (!message.message.isNullOrBlank()) onStatus(message.message)
                        }
                        "error" -> if (!message.message.isNullOrBlank()) {
                            val errorMessage = message.message.orEmpty()
                            failProviderSwitch(errorMessage)
                            failLanguageSwitch(errorMessage)
                            val authorizationFailure = isAuthorizationError(errorMessage)
                            if (authorizationFailure) {
                                authorizationRejected = true
                                webSocket.close(1008, "unauthorized")
                            } else {
                                webSocket.cancel()
                            }
                            handleConnectionTermination(
                                generation = generation,
                                connectionId = connectionId,
                                detail = errorMessage,
                                authorizationFailure = authorizationFailure,
                                onStatus = onStatus,
                                onError = onError
                            )
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
                    warningLog(
                        "WebSocket failure endpoint=${endpoint.toStreamingWsUrl()} " +
                            "provider=${streamProvider.wireValue} responseCode=${response?.code ?: "none"} " +
                            "tokenPresent=${!apiToken.isNullOrBlank()} detail=${t.message ?: "unknown"}"
                    )
                    handleConnectionTermination(
                        generation = generation,
                        connectionId = connectionId,
                        detail = buildString {
                            response?.code?.let { append("HTTP ").append(it).append(": ") }
                            append(t.message?.takeIf { it.isNotBlank() } ?: "网络连接已断开")
                        },
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
        serverReady = false
        webSocket = null
        streamCanFinalize = false
        failProviderSwitch(detail)
        failLanguageSwitch(detail)
        isReconnecting = false
        if (authorizationFailure || authorizationRejected) {
            authorizationRejected = true
            connectionReady?.completeExceptionally(IllegalStateException(detail))
            connectionReady = null
            onError("STT 访问令牌无效或无权限，请到服务设置中更新令牌")
            notifyProviderFailureOnce(generation, detail)
            return
        }
        scheduleLocalFailoverDeadline(generation, detail)
        if (scheduleReconnectIfNeeded(generation)) {
            onStatus(
                if (streamProvider == StreamingSttProvider.LOCAL) {
                    "本地连接波动，正在快速恢复"
                } else {
                    "云端连接波动，正在恢复实时识别"
                }
            )
        } else {
            connectionReady?.completeExceptionally(IllegalStateException(detail))
            connectionReady = null
            onError("实时预览连接失败：$detail，请检查网络后重试")
            notifyProviderFailureOnce(generation, detail)
        }
    }

    private fun scheduleLocalFailoverDeadline(generation: Long, detail: String) {
        if (streamProvider != StreamingSttProvider.LOCAL) return
        val epoch: Long
        synchronized(reconnectLock) {
            if (generation != sessionGeneration.get() || localRecoveryDeadlineScheduled) return
            localRecoveryDeadlineScheduled = true
            epoch = localRecoveryEpoch.incrementAndGet()
        }
        Thread {
            localRecoveryDelay(LOCAL_STREAM_FAILOVER_WINDOW_MS)
            val shouldFailover = synchronized(reconnectLock) {
                val valid = generation == sessionGeneration.get() &&
                    epoch == localRecoveryEpoch.get() &&
                    !serverReady &&
                    streamProvider == StreamingSttProvider.LOCAL
                if (valid) {
                    reconnectAttempt = maxReconnectAttempts
                    isReconnecting = false
                }
                if (epoch == localRecoveryEpoch.get()) localRecoveryDeadlineScheduled = false
                valid
            }
            if (shouldFailover) {
                webSocket?.cancel()
                notifyProviderFailureOnce(
                    generation,
                    "本地实时识别在 3 秒快速恢复窗口内未就绪：$detail"
                )
            }
        }.apply {
            name = "StreamingSttLocalFailover"
            isDaemon = true
            start()
        }
    }

    private fun notifyProviderFailureOnce(generation: Long, detail: String) {
        if (generation != sessionGeneration.get()) return
        if (providerFailureGeneration.getAndSet(generation) == generation) return
        onProviderFailureCallback?.invoke(streamProvider, detail)
    }

    private fun isCurrentConnection(generation: Long, connectionId: Long): Boolean =
        generation == sessionGeneration.get() && connectionId == connectionGeneration.get()

    private fun scheduleReconnectIfNeeded(generation: Long): Boolean {
        val attempt: Int
        val retryLimit = if (streamProvider == StreamingSttProvider.LOCAL) {
            maxReconnectAttempts
        } else {
            maxCloudReconnectAttempts
        }
        synchronized(reconnectLock) {
            if (generation != sessionGeneration.get()) return false
            if (isReconnecting) return true
            if (reconnectAttempt >= retryLimit) return false
            isReconnecting = true
            reconnectAttempt++
            attempt = reconnectAttempt
        }

        val delayMs = streamingReconnectDelayMs(
            attempt = attempt,
            provider = streamProvider,
            baseDelayMs = baseDelayMs
        )

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
                statusCb(
                    if (streamProvider == StreamingSttProvider.LOCAL) {
                        "本地快速恢复 $attempt/$retryLimit"
                    } else {
                        "云端连接恢复 $attempt/$retryLimit"
                    }
                )
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
            producedAudioBytes += pcmBytes.size.toLong()
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

    suspend fun switchService(
        nextEndpoint: String,
        provider: StreamingSttProvider,
        forceReconnect: Boolean = false,
        apiTokenOverride: String? = null
    ): Result<Unit> {
        val normalizedEndpoint = nextEndpoint.trim().trimEnd('/')
        require(normalizedEndpoint.isNotBlank()) { "STT 服务地址未配置" }
        if (!forceReconnect && normalizedEndpoint == endpoint.trim().trimEnd('/')) {
            return switchProvider(provider)
        }

        return runCatching {
            val partialCallback = requireNotNull(onPartialTextCallback) { "实时预览尚未启动" }
            val statusCallback = requireNotNull(onStatusCallback) { "实时预览尚未启动" }
            val errorCallback = requireNotNull(onErrorCallback) { "实时预览尚未启动" }
            val activeMeetingId = meetingId
            val activeApiToken = apiTokenOverride?.takeIf { it.isNotBlank() } ?: apiToken
            val activeLanguage = language
            val providerFailureCallback = onProviderFailureCallback
            require(activeMeetingId.isNotBlank()) { "实时预览会话无效" }

            val readiness = CompletableDeferred<Unit>()
            start(
                endpoint = normalizedEndpoint,
                meetingId = activeMeetingId,
                apiToken = activeApiToken,
                streamProvider = provider,
                language = activeLanguage,
                contextHint = contextHint,
                speakerDiarization = speakerDiarization,
                allowFinalization = false,
                onPartialText = partialCallback,
                onStatus = statusCallback,
                onError = errorCallback,
                onProviderFailure = providerFailureCallback ?: { _, _ -> },
                connectionReady = readiness,
                // A provider switch closes the old socket, but PCM queued while
                // it is closing still belongs to this meeting and must be sent
                // to the new socket after its handshake.
                preserveAudioBuffer = true
            )
            try {
                withTimeout(BuildConfig.STT_STREAM_SWITCH_TIMEOUT_SECONDS * 1_000L) {
                    readiness.await()
                }
            } catch (error: Throwable) {
                // Do not leave a failed cloud socket reconnecting in the
                // background after the fallback operation has been reported.
                if (connectionReady === readiness) stop()
                throw error
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

    fun stop(): String? = stopInternal(clearSpeakerLabels = true)

    private fun stopInternal(
        clearSpeakerLabels: Boolean,
        preserveAudioBuffer: Boolean = false
    ): String? {
        var finalizationSession = serverSessionId?.takeIf { isConnected && streamCanFinalize }
        sessionGeneration.incrementAndGet()
        connectionGeneration.incrementAndGet()
        reconnectAttempt = maxOf(maxReconnectAttempts, maxCloudReconnectAttempts)
        isReconnecting = false
        failProviderSwitch("流式识别已结束")
        failLanguageSwitch("流式识别已结束")
        connectionReady?.cancel()
        connectionReady = null
        serverReady = false
        localRecoveryDeadlineScheduled = false
        localRecoveryEpoch.incrementAndGet()
        if (isConnected) {
            val stopSent = webSocket?.send(gson.toJson(StreamControlMessage(event = "stop"))) == true
            if (!stopSent) finalizationSession = null
        }
        webSocket?.close(1000, "client-stop")
        webSocket = null
        synchronized(audioLock) {
            isConnected = false
            if (!preserveAudioBuffer) {
                producedAudioBytes = 0L
                connectionTimelineOffsetSeconds = 0f
                pendingAudio.clear()
                pendingAudioBytes = 0
            }
        }
        onPartialTextCallback = null
        onStatusCallback = null
        onErrorCallback = null
        onProviderFailureCallback = null
        apiToken = null
        meetingId = ""
        streamProvider = StreamingSttProvider.LOCAL
        language = STTLanguage.CHINESE
        contextHint = ""
        authorizationRejected = false
        serverSessionId = null
        streamCanFinalize = false
        if (clearSpeakerLabels) {
            synchronized(speakerLabelLock) {
                speakerLabels.clear()
                speakerLabelMeetingId = null
            }
        }
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

    private fun audioBytesToSeconds(bytes: Long): Float =
        bytes.toFloat() / (AudioRecorder.SAMPLE_RATE * AudioRecorder.CHANNEL_COUNT * 2f)

    private fun normalizeSpeakerId(rawSpeaker: Int?): Int? = rawSpeaker?.let { raw ->
        synchronized(speakerLabelLock) {
            speakerLabels.getOrPut(raw) { speakerLabels.size }
        }
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
        language: STTLanguage = STTLanguage.CHINESE,
        contextHint: String? = null,
        speakerDiarization: Boolean = false
    ): String = gson.toJson(
        StreamControlMessage(
            event = "start",
            sampleRate = AudioRecorder.SAMPLE_RATE,
            channels = AudioRecorder.CHANNEL_COUNT,
            meetingId = meetingId,
            streamProvider = provider.wireValue,
            language = language.requestValue,
            contextHint = contextHint?.takeIf { it.isNotBlank() },
            speakerDiarization = speakerDiarization
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
        val language: String? = null,
        @SerializedName("context_hint") val contextHint: String? = null,
        @SerializedName("speaker_diarization") val speakerDiarization: Boolean? = null
    )

    private data class StreamServerMessage(
        val type: String,
        val text: String? = null,
        @SerializedName("committed_text") val committedText: String? = null,
        @SerializedName("preview_text") val previewText: String? = null,
        @SerializedName("session_id") val sessionId: String? = null,
        @SerializedName("stream_provider") val streamProvider: String? = null,
        val language: String? = null,
        val message: String? = null,
        val segments: List<StreamSegment>? = null,
        val diarization: StreamDiarization? = null
    )

    private data class StreamSegment(
        val start: Float? = null,
        val end: Float? = null,
        val text: String? = null,
        val speaker: Int? = null,
        @SerializedName("speaker_id") val speakerId: Int? = null,
        val committed: Boolean? = null
    )

    private data class StreamDiarization(
        val enabled: Boolean? = null,
        val active: Boolean? = null
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
    val sessionId: String = "",
    /** Absolute audio position represented by the first frame of this socket session. */
    val timelineOffsetSeconds: Float = 0f,
    val segments: List<StreamingTranscriptSegment> = emptyList(),
    val diarizationEnabled: Boolean = false,
    val diarizationActive: Boolean = false
)

data class StreamingTranscriptSegment(
    val startSeconds: Float,
    val endSeconds: Float,
    val text: String,
    val speaker: Int? = null,
    val committed: Boolean = false
)

private fun formatStreamingSpeakerPreview(segments: List<StreamingTranscriptSegment>): String {
    if (segments.none { it.speaker != null }) return ""
    return segments
        .sortedBy { it.startSeconds }
        .groupByConsecutive { it.speaker }
        .mapNotNull { group ->
            val speaker = group.first().speaker ?: return@mapNotNull null
            val content = group.joinToString(" ") { it.text }.trim()
            content.takeIf { it.isNotBlank() }?.let { "说话人 ${speaker + 1}：$it" }
        }
        .joinToString("\n")
}

private fun <T, K> List<T>.groupByConsecutive(keySelector: (T) -> K): List<List<T>> {
    if (isEmpty()) return emptyList()
    val groups = mutableListOf<MutableList<T>>()
    forEach { item ->
        val current = groups.lastOrNull()
        if (current == null || keySelector(current.last()) != keySelector(item)) {
            groups += mutableListOf(item)
        } else {
            current += item
        }
    }
    return groups
}
