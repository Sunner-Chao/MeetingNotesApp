package com.oa.automation.infrastructure.service

import android.os.SystemClock
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.infrastructure.audio.AudioRecorder
import com.oa.automation.infrastructure.stt.StreamingSttClient
import com.oa.automation.infrastructure.stt.StreamingSttProvider
import com.oa.automation.infrastructure.account.AccountSessionSynchronizer
import com.oa.automation.infrastructure.stt.StreamingTranscriptUpdate
import com.oa.automation.infrastructure.stt.StreamingTranscriptAccumulator
import com.oa.automation.infrastructure.stt.StreamingTranscriptSegment
import com.oa.automation.infrastructure.stt.CloudSTTEngine
import com.oa.automation.infrastructure.stt.buildSttContextHint
import com.oa.automation.infrastructure.stt.CLOUD_STREAM_READY_STATUS
import com.oa.automation.infrastructure.stt.LOCAL_STREAM_READY_STATUS
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.STTLanguage
import com.oa.automation.domain.model.TencentAsrTier
import com.oa.automation.domain.model.serviceEndpointFor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import android.util.Log
import kotlin.math.log10
import kotlin.math.sqrt

enum class RealtimeSttRouteState {
    IDLE,
    LOCAL_CONNECTING,
    LOCAL_ACTIVE,
    LOCAL_RECOVERING,
    CLOUD_CONNECTING,
    SWITCHING_TO_CLOUD,
    CLOUD_ACTIVE,
    CLOUD_FALLBACK_ACTIVE,
    UNAVAILABLE
}

internal fun realtimeSttRouteAfterStatus(
    current: RealtimeSttRouteState,
    status: String
): RealtimeSttRouteState = when {
    status == LOCAL_STREAM_READY_STATUS -> RealtimeSttRouteState.LOCAL_ACTIVE
    status.startsWith("本地连接波动") || status.startsWith("本地快速恢复") ->
        RealtimeSttRouteState.LOCAL_RECOVERING
    status == CLOUD_STREAM_READY_STATUS && current == RealtimeSttRouteState.SWITCHING_TO_CLOUD ->
        RealtimeSttRouteState.CLOUD_FALLBACK_ACTIVE
    status == CLOUD_STREAM_READY_STATUS -> RealtimeSttRouteState.CLOUD_ACTIVE
    status.startsWith("云端连接波动") || status.startsWith("云端连接恢复") ->
        RealtimeSttRouteState.CLOUD_CONNECTING
    else -> current
}

data class RecordingSessionState(
    val meetingId: String = "",
    val meetingTitle: String = "",
    val isStarting: Boolean = false,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val isStopping: Boolean = false,
    val startedAtElapsedRealtimeMs: Long? = null,
    val recordedDurationSeconds: Long = 0,
    val audioLevel: Float = 0f,
    val streamUpdate: StreamingTranscriptUpdate? = null,
    val accumulatedTranscript: String = "",
    val status: String = "流式预览",
    val realtimeSttRoute: RealtimeSttRouteState = RealtimeSttRouteState.IDLE,
    val error: String? = null
)

data class RecordingStopResult(
    val meetingId: String,
    val audioFile: File,
    val streamSessionId: String?,
    val transcriptText: String,
    val speakerSegments: List<StreamingTranscriptSegment>,
    val durationMs: Long,
    val requiresLogin: Boolean
)

class RecordingSessionController(
    private val audioRecorder: AudioRecorder,
    private val streamingSttClient: StreamingSttClient,
    private val configDataStore: ConfigDataStore,
    private val accountSessionSynchronizer: AccountSessionSynchronizer
) {
    private val operationMutex = Mutex()
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamingPreviewActive = false
    private var accountAccessEnabled = false
    private var cloudFallbackAvailable = false
    @Volatile private var automaticCloudFallbackAttempted = false
    private val transcriptAccumulator = StreamingTranscriptAccumulator()
    @Volatile private var smoothedAudioLevel = 0f
    @Volatile private var pendingStopMeetingId: String? = null
    private val _state = MutableStateFlow(RecordingSessionState())
    val state: StateFlow<RecordingSessionState> = _state.asStateFlow()

    fun markStopRequested(expectedMeetingId: String? = null): Boolean {
        val current = _state.value
        val targetMeetingId = expectedMeetingId.orEmpty().ifBlank { current.meetingId }
        if (targetMeetingId.isBlank()) return false
        if (current.meetingId.isNotBlank() && current.meetingId != targetMeetingId) return false
        pendingStopMeetingId = targetMeetingId
        _state.update {
            if (it.meetingId.isBlank()) it else it.copy(
                isStopping = true,
                status = "正在整理录音",
                error = null
            )
        }
        return true
    }

    fun updatePostProcessingStatus(meetingId: String, status: String, error: String? = null) {
        _state.update {
            if (it.meetingId != meetingId) it else it.copy(status = status, error = error)
        }
    }

    suspend fun start(meetingId: String, meetingTitle: String): Result<Unit> = operationMutex.withLock {
        runCatching {
            require(meetingId.isNotBlank()) { "会议标识缺失" }
            if (pendingStopMeetingId == meetingId) {
                pendingStopMeetingId = null
                _state.value = RecordingSessionState(
                    meetingId = meetingId,
                    meetingTitle = meetingTitle,
                    status = "录音启动已取消"
                )
                throw CancellationException("录音启动已取消")
            }
            if (audioRecorder.isRecording() && _state.value.meetingId == meetingId) return@runCatching
            if (audioRecorder.isRecording()) error("已有其他会议正在录音")

            var sttConfig = configDataStore.appConfigFlow.first().sttConfig
            val usesTencentHybrid = sttConfig.engineType == STTEngineType.TENCENT_HYBRID
            automaticCloudFallbackAttempted = false
            val storedSession = configDataStore.authSessionFlow.first()
            // Do not make microphone capture wait on the account server. A stale
            // session can be refreshed opportunistically, but the local socket
            // should be allowed to connect immediately with the current token.
            val nowSeconds = System.currentTimeMillis() / 1_000L
            val shouldRefreshSession = storedSession != null && (
                storedSession.expiresAt <= nowSeconds + 120L ||
                    sttConfig.apiToken.isNullOrBlank()
                )
            if (shouldRefreshSession) {
                val refreshResult = withTimeoutOrNull(3_000L) {
                    accountSessionSynchronizer.refresh()
                }
                if (refreshResult == null) {
                    Log.w("RecordingSessionController", "account refresh timed out before STT start")
                } else if (refreshResult.isFailure) {
                    Log.w(
                        "RecordingSessionController",
                        "account refresh failed before STT start: ${refreshResult.exceptionOrNull()?.message}"
                    )
                } else {
                    sttConfig = configDataStore.appConfigFlow.first().sttConfig
                }
            }
            val currentSession = configDataStore.authSessionFlow.first()
            val accountSessionAvailable = currentSession?.expiresAt?.let {
                it > System.currentTimeMillis() / 1_000
            } == true
            val sttTokenAvailable = !sttConfig.apiToken.isNullOrBlank()
            accountAccessEnabled = accountSessionAvailable && sttTokenAvailable
            cloudFallbackAvailable = accountAccessEnabled && !sttConfig.cloudEndpoint.isNullOrBlank()
            Log.d(
                "RecordingSessionController",
                "STT start endpoint=${sttConfig.localEndpoint.trim().ifBlank { "<blank>" }} " +
                    "sessionPresent=${currentSession != null} sessionValid=$accountSessionAvailable " +
                    "tokenPresent=$sttTokenAvailable cloudFallback=$cloudFallbackAvailable"
            )
            val localEndpoint = sttConfig.localEndpoint.trim()
            val useCloudInitially = usesTencentHybrid ||
                (localEndpoint.isBlank() && cloudFallbackAvailable)
            val initialEndpoint = if (useCloudInitially) {
                sttConfig.serviceEndpointFor(STTEngineType.TENCENT_HYBRID)
            } else {
                localEndpoint
            }
            val initialProvider = if (useCloudInitially) {
                sttConfig.tencentAsrTier.toStreamingProvider()
            } else {
                StreamingSttProvider.LOCAL
            }
            transcriptAccumulator.reset()
            _state.value = RecordingSessionState(
                meetingId = meetingId,
                meetingTitle = meetingTitle,
                isStarting = true,
                realtimeSttRoute = when {
                    useCloudInitially -> RealtimeSttRouteState.CLOUD_CONNECTING
                    initialEndpoint.isNotBlank() -> RealtimeSttRouteState.LOCAL_CONNECTING
                    else -> RealtimeSttRouteState.IDLE
                },
                status = when {
                    useCloudInitially -> "正在连接智悟增强云模型"
                    initialEndpoint.isBlank() -> "正在启动本地录音"
                    else -> "正在连接本地实时预览"
                }
            )
            smoothedAudioLevel = 0f
            if (pendingStopMeetingId == meetingId) {
                pendingStopMeetingId = null
                throw CancellationException("录音启动已取消")
            }
            if (useCloudInitially) {
                require(cloudFallbackAvailable) { "智悟增强云模型需要有效的账户令牌和服务地址" }
                val connectionResult = withContext(Dispatchers.IO) {
                    CloudSTTEngine.testHybridConnection(sttConfig)
                }
                connectionResult.getOrElse { failure ->
                    error(failure.message ?: "STT 服务鉴权失败")
                }
            }
            audioRecorder.setOnChunkAvailableListener(null)
            streamingPreviewActive = initialEndpoint.isNotBlank()
            audioRecorder.setOnPcmDataListener { pcmBytes, length ->
                val measuredLevel = normalizedPcmLevel(pcmBytes, length)
                smoothedAudioLevel = smoothedAudioLevel * 0.72f + measuredLevel * 0.28f
                _state.update {
                    if (it.isRecording || it.isStarting) {
                        it.copy(audioLevel = smoothedAudioLevel.coerceIn(0f, 1f))
                    } else {
                        it
                    }
                }
                if (streamingPreviewActive) streamingSttClient.sendAudio(pcmBytes)
            }
            if (streamingPreviewActive) {
                streamingSttClient.start(
                    endpoint = initialEndpoint,
                    meetingId = meetingId,
                    apiToken = sttConfig.apiToken,
                    streamProvider = initialProvider,
                    language = sttConfig.language,
                    contextHint = buildSttContextHint(meetingTitle),
                    speakerDiarization = sttConfig.speakerDiarizationEnabled,
                    onPartialText = { update ->
                        val accumulatedText = transcriptAccumulator.update(update)
                        _state.update {
                            val route = when (it.realtimeSttRoute) {
                                RealtimeSttRouteState.SWITCHING_TO_CLOUD ->
                                    RealtimeSttRouteState.CLOUD_FALLBACK_ACTIVE
                                RealtimeSttRouteState.CLOUD_CONNECTING ->
                                    RealtimeSttRouteState.CLOUD_ACTIVE
                                RealtimeSttRouteState.CLOUD_ACTIVE,
                                RealtimeSttRouteState.CLOUD_FALLBACK_ACTIVE ->
                                    it.realtimeSttRoute
                                else -> RealtimeSttRouteState.LOCAL_ACTIVE
                            }
                            it.copy(
                                streamUpdate = update,
                                accumulatedTranscript = accumulatedText,
                                realtimeSttRoute = route,
                                status = "实时预览（可修订）"
                            )
                        }
                    },
                    onStatus = { status ->
                        _state.update {
                            it.copy(
                                status = status,
                                realtimeSttRoute = realtimeSttRouteAfterStatus(
                                    current = it.realtimeSttRoute,
                                    status = status
                                ),
                                error = null
                            )
                        }
                    },
                    onError = { error ->
                        _state.update {
                            val localWillFallback = cloudFallbackAvailable &&
                                !automaticCloudFallbackAttempted &&
                                it.realtimeSttRoute in LOCAL_ROUTE_STATES
                            if (localWillFallback) it else it.copy(error = error)
                        }
                    },
                    onProviderFailure = { provider, detail ->
                        if (provider == StreamingSttProvider.LOCAL) {
                            if (isSttAuthorizationFailure(detail)) {
                                _state.update {
                                    it.copy(
                                        realtimeSttRoute = RealtimeSttRouteState.UNAVAILABLE,
                                        error = "本地 STT 登录已失效，请重新登录后重试；录音仍会保存在本机"
                                    )
                                }
                            } else if (cloudFallbackAvailable) {
                                requestAutomaticCloudFallback(detail)
                            } else {
                                _state.update {
                                    it.copy(
                                        realtimeSttRoute = RealtimeSttRouteState.UNAVAILABLE,
                                        error = "本地实时识别不可用，录音仍会保存在本机"
                                    )
                                }
                            }
                        }
                    }
                )
            }
            if (pendingStopMeetingId == meetingId) {
                if (streamingPreviewActive) streamingSttClient.stop()
                streamingPreviewActive = false
                audioRecorder.setOnPcmDataListener(null)
                pendingStopMeetingId = null
                throw CancellationException("录音启动已取消")
            }
            val audioFile = audioRecorder.start(
                enableAudioEnhancement = sttConfig.audioEnhancementEnabled
            )
            if (audioFile == null) {
                if (streamingPreviewActive) streamingSttClient.stop()
                streamingPreviewActive = false
                audioRecorder.setOnPcmDataListener(null)
                error("无法启动录音，请检查麦克风权限")
            }
            _state.update {
                val stopWasRequested = pendingStopMeetingId == meetingId
                it.copy(
                    isStarting = false,
                    isRecording = true,
                    isPaused = false,
                    isStopping = stopWasRequested,
                    startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    recordedDurationSeconds = 0,
                    status = when {
                        stopWasRequested -> "正在整理录音"
                        useCloudInitially -> "智悟增强云模型识别中"
                        initialEndpoint.isBlank() -> "本地录音中"
                        else -> "本地实时预览处理中"
                    },
                    realtimeSttRoute = it.realtimeSttRoute,
                    error = null
                )
            }
        }.onFailure { error ->
            _state.update {
                if (error is CancellationException) {
                    it.copy(
                        isStarting = false,
                        isRecording = false,
                        isStopping = false,
                        startedAtElapsedRealtimeMs = null,
                        status = "录音启动已取消",
                        error = null
                    )
                } else {
                    it.copy(isStarting = false, isRecording = false, error = "录音启动失败: ${error.message}")
                }
            }
        }
    }

    suspend fun stop(expectedMeetingId: String? = null): Result<RecordingStopResult> = operationMutex.withLock {
        runCatching {
            val meetingId = _state.value.meetingId
            if (expectedMeetingId != null && expectedMeetingId != meetingId) {
                throw CancellationException("录音会话已切换")
            }
            require(meetingId.isNotBlank() && audioRecorder.isRecording()) { "没有在录音" }
            _state.update { it.copy(isStopping = true, status = "正在整理录音") }
            val audioFile = audioRecorder.stop()
                ?: error("录音文件不可用")
            audioRecorder.setOnPcmDataListener(null)
            val streamSessionId = if (streamingPreviewActive) streamingSttClient.stop() else null
            streamingPreviewActive = false
            automaticCloudFallbackAttempted = false
            val transcriptText = transcriptAccumulator.snapshot()
            val speakerSegments = transcriptAccumulator.snapshotSegments()
            smoothedAudioLevel = 0f
            pendingStopMeetingId = null
            require(audioFile.isFile && audioFile.length() > 0L) { "录音文件为空" }
            val durationMs = _state.value.durationSecondsAt(SystemClock.elapsedRealtime()) * 1_000L
            _state.update {
                it.copy(
                    isStarting = false,
                    isRecording = false,
                    isPaused = false,
                    isStopping = false,
                    startedAtElapsedRealtimeMs = null,
                    recordedDurationSeconds = it.durationSecondsAt(SystemClock.elapsedRealtime()),
                    audioLevel = 0f,
                    accumulatedTranscript = transcriptText,
                    status = if (transcriptText.isBlank()) "实时转写未产生文本" else "正在保存实时转写",
                    realtimeSttRoute = RealtimeSttRouteState.IDLE,
                    error = null
                )
            }
            RecordingStopResult(
                meetingId,
                audioFile,
                streamSessionId,
                transcriptText,
                speakerSegments,
                durationMs,
                requiresLogin = !accountAccessEnabled
            )
        }.onFailure { error ->
            if (_state.value.meetingId == expectedMeetingId) pendingStopMeetingId = null
            _state.update {
                it.copy(
                    isStarting = false,
                    isRecording = audioRecorder.isRecording(),
                    isStopping = false,
                    error = "保存录音失败: ${error.message}"
                )
            }
        }
    }

    suspend fun pause(expectedMeetingId: String? = null): Result<Unit> = operationMutex.withLock {
        runCatching {
            val current = _state.value
            require(expectedMeetingId.isNullOrBlank() || current.meetingId == expectedMeetingId) {
                "录音会话已切换"
            }
            require(current.isRecording && !current.isStopping && !current.isPaused) {
                "当前没有可暂停的录音"
            }
            check(audioRecorder.pause()) { "录音暂停失败" }
            val elapsed = SystemClock.elapsedRealtime()
            _state.update {
                it.copy(
                    isPaused = true,
                    audioLevel = 0f,
                    startedAtElapsedRealtimeMs = null,
                    recordedDurationSeconds = it.durationSecondsAt(elapsed),
                    status = "录音已暂停",
                    error = null
                )
            }
        }.onFailure { error ->
            _state.update { it.copy(error = "暂停录音失败: ${error.message}") }
        }
    }

    /**
     * Finalize a paused session just enough to release the microphone for a
     * different meeting. The service persists this snapshot as an unfinished
     * record; it is not treated as an explicit report-generation action.
     */
    suspend fun suspendPausedSession(expectedMeetingId: String? = null): Result<RecordingStopResult> =
        operationMutex.withLock {
            runCatching {
                val current = _state.value
                val meetingId = current.meetingId
                require(meetingId.isNotBlank()) { "没有可暂存的录音" }
                require(expectedMeetingId.isNullOrBlank() || meetingId == expectedMeetingId) {
                    "录音会话已切换"
                }
                require(current.isRecording && current.isPaused && !current.isStopping) {
                    "只有已暂停的录音可以切换"
                }
                val audioFile = audioRecorder.stop() ?: error("录音文件不可用")
                audioRecorder.setOnPcmDataListener(null)
                val streamSessionId = if (streamingPreviewActive) streamingSttClient.stop() else null
                streamingPreviewActive = false
                automaticCloudFallbackAttempted = false
                val transcriptText = transcriptAccumulator.snapshot()
                val speakerSegments = transcriptAccumulator.snapshotSegments()
                val durationMs = current.durationSecondsAt(SystemClock.elapsedRealtime()) * 1_000L
                smoothedAudioLevel = 0f
                pendingStopMeetingId = null
                _state.update {
                    it.copy(
                        isStarting = false,
                        isRecording = false,
                        isPaused = false,
                        isStopping = false,
                        startedAtElapsedRealtimeMs = null,
                        recordedDurationSeconds = durationMs / 1_000L,
                        audioLevel = 0f,
                        accumulatedTranscript = transcriptText,
                        status = "录音已暂存，可继续或生成纪要",
                        realtimeSttRoute = RealtimeSttRouteState.IDLE,
                        error = null
                    )
                }
                require(audioFile.isFile && audioFile.length() > 0L) { "录音文件为空" }
                RecordingStopResult(
                    meetingId = meetingId,
                    audioFile = audioFile,
                    streamSessionId = streamSessionId,
                    transcriptText = transcriptText,
                    speakerSegments = speakerSegments,
                    durationMs = durationMs,
                    requiresLogin = !accountAccessEnabled
                )
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isStarting = false,
                        isRecording = true,
                        isPaused = true,
                        isStopping = false,
                        error = "暂存录音失败: ${error.message}"
                    )
                }
            }
        }

    suspend fun resume(expectedMeetingId: String? = null): Result<Unit> = operationMutex.withLock {
        runCatching {
            val current = _state.value
            require(expectedMeetingId.isNullOrBlank() || current.meetingId == expectedMeetingId) {
                "录音会话已切换"
            }
            require(current.isRecording && current.isPaused && !current.isStopping) {
                "当前录音没有暂停"
            }
            check(audioRecorder.resume()) { "录音恢复失败" }
            _state.update {
                it.copy(
                    isPaused = false,
                    startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    status = "实时录音中",
                    error = null
                )
            }
        }.onFailure { error ->
            _state.update { it.copy(error = "恢复录音失败: ${error.message}") }
        }
    }

    suspend fun switchStreamingProvider(engineType: STTEngineType): Result<Unit> =
        operationMutex.withLock {
            runCatching {
                val current = _state.value
                require(current.isRecording && !current.isStopping) {
                    "当前没有可切换的实时录音"
                }
                val sttConfig = configDataStore.appConfigFlow.first().sttConfig
                val provider = if (engineType == STTEngineType.TENCENT_HYBRID) {
                    sttConfig.tencentAsrTier.toStreamingProvider()
                } else {
                    StreamingSttProvider.LOCAL
                }
                val endpoint = sttConfig.serviceEndpointFor(engineType)
                require(endpoint.isNotBlank()) {
                    if (engineType == STTEngineType.TENCENT_HYBRID) {
                        "智悟增强云模型地址未配置"
                    } else {
                        "智悟本地识别服务地址未配置"
                    }
                }
                _state.update {
                    val previousRoute = it.realtimeSttRoute
                    val nextRoute = when {
                        engineType != STTEngineType.TENCENT_HYBRID ->
                            RealtimeSttRouteState.LOCAL_CONNECTING
                        previousRoute in LOCAL_ROUTE_STATES || automaticCloudFallbackAttempted ->
                            RealtimeSttRouteState.SWITCHING_TO_CLOUD
                        else -> RealtimeSttRouteState.CLOUD_CONNECTING
                    }
                    it.copy(
                        status = if (nextRoute == RealtimeSttRouteState.SWITCHING_TO_CLOUD) {
                            "本地识别中断，正在接入云端识别"
                        } else {
                            "正在连接${engineType.displayName}"
                        },
                        realtimeSttRoute = nextRoute,
                        error = null
                    )
                }
                streamingSttClient.switchService(endpoint, provider).getOrThrow()
                _state.update {
                    val connectedRoute = when {
                        engineType != STTEngineType.TENCENT_HYBRID ->
                            RealtimeSttRouteState.LOCAL_ACTIVE
                        automaticCloudFallbackAttempted ->
                            RealtimeSttRouteState.CLOUD_FALLBACK_ACTIVE
                        else -> RealtimeSttRouteState.CLOUD_ACTIVE
                    }
                    it.copy(
                        status = if (connectedRoute == RealtimeSttRouteState.CLOUD_FALLBACK_ACTIVE) {
                            "云端识别已接管，录音未中断"
                        } else {
                            "${engineType.displayName}已启用"
                        },
                        realtimeSttRoute = connectedRoute,
                        error = null
                    )
                }
            }.onFailure { error ->
                streamingPreviewActive = false
                _state.update {
                    it.copy(
                        realtimeSttRoute = RealtimeSttRouteState.UNAVAILABLE,
                        error = "识别引擎切换失败: ${error.message}"
                    )
                }
            }
        }

    private fun requestAutomaticCloudFallback(detail: String) {
        if (!cloudFallbackAvailable || automaticCloudFallbackAttempted) return
        val current = _state.value
        if ((!current.isRecording && !current.isStarting) || current.isStopping) return
        automaticCloudFallbackAttempted = true
        _state.update {
            it.copy(
                status = "本地实时识别暂不可用，正在切换云端兜底",
                realtimeSttRoute = RealtimeSttRouteState.SWITCHING_TO_CLOUD,
                error = null
            )
        }
        fallbackScope.launch {
            val result = switchStreamingProvider(STTEngineType.TENCENT_HYBRID)
            _state.update {
                if (!it.isRecording || it.isStopping) return@update it
                if (result.isSuccess) {
                    it.copy(
                        status = "云端识别已接管，录音未中断",
                        realtimeSttRoute = RealtimeSttRouteState.CLOUD_FALLBACK_ACTIVE,
                        error = null
                    )
                } else {
                    it.copy(
                        status = "本地与云端实时识别均不可用",
                        realtimeSttRoute = RealtimeSttRouteState.UNAVAILABLE,
                        error = "本地实时识别失败：$detail；云端兜底失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    suspend fun switchStreamingLanguage(language: STTLanguage): Result<Unit> =
        operationMutex.withLock {
            runCatching {
                val current = _state.value
                require(current.isRecording && !current.isStopping) {
                    "当前没有可切换的实时录音"
                }
                _state.update {
                    it.copy(status = "正在切换至${language.displayName}", error = null)
                }
                streamingSttClient.switchLanguage(language).getOrThrow()
                _state.update {
                    it.copy(status = "${language.displayName}识别已启用", error = null)
                }
            }.onFailure { error ->
                _state.update { it.copy(error = "识别语言切换失败: ${error.message}") }
            }
        }

    suspend fun cancelSession(deleteFile: Boolean = false) = operationMutex.withLock {
        audioRecorder.setOnPcmDataListener(null)
        if (streamingPreviewActive) streamingSttClient.stop()
        streamingPreviewActive = false
        automaticCloudFallbackAttempted = false
        smoothedAudioLevel = 0f
        audioRecorder.cancel(deleteFile = deleteFile)
        pendingStopMeetingId = null
        _state.update {
            val completedDuration = it.durationSecondsAt(SystemClock.elapsedRealtime())
            it.copy(
                isStarting = false,
                isRecording = false,
                isPaused = false,
                isStopping = false,
                audioLevel = 0f,
                startedAtElapsedRealtimeMs = null,
                recordedDurationSeconds = if (deleteFile) 0 else completedDuration,
                status = if (deleteFile) "本次录音已放弃" else it.status,
                realtimeSttRoute = RealtimeSttRouteState.IDLE,
                error = null
            )
        }
    }
}

private val LOCAL_ROUTE_STATES = setOf(
    RealtimeSttRouteState.LOCAL_CONNECTING,
    RealtimeSttRouteState.LOCAL_ACTIVE,
    RealtimeSttRouteState.LOCAL_RECOVERING
)

/** Convert 16-bit mono PCM into a UI-friendly 0..1 sound level. */
private fun normalizedPcmLevel(bytes: ByteArray, length: Int): Float {
    val safeLength = length.coerceIn(0, bytes.size)
    val sampleCount = (safeLength / 2).coerceAtLeast(1)
    var sumSquares = 0.0
    var offset = 0
    repeat(sampleCount) {
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt()
        val sample = ((high shl 8) or low).toShort().toInt() / 32768.0
        sumSquares += sample * sample
        offset += 2
    }

    val rms = sqrt(sumSquares / sampleCount).coerceAtLeast(0.00001)
    val decibels = 20.0 * log10(rms)
    return ((decibels + 52.0) / 52.0).coerceIn(0.04, 1.0).toFloat()
}

private fun isSttAuthorizationFailure(detail: String): Boolean {
    val normalized = detail.lowercase()
    return normalized.contains("unauthorized") ||
        normalized.contains("invalid bearer") ||
        normalized.contains("invalid token") ||
        normalized.contains("http 401") ||
        normalized.contains("http 403") ||
        normalized.contains("访问令牌") ||
        normalized.contains("令牌无效")
}

internal fun RecordingSessionState.durationSecondsAt(elapsedRealtimeMs: Long): Long {
    val startedAt = startedAtElapsedRealtimeMs ?: return recordedDurationSeconds.coerceAtLeast(0)
    return recordedDurationSeconds + ((elapsedRealtimeMs - startedAt).coerceAtLeast(0) / 1_000L)
}

private fun TencentAsrTier.toStreamingProvider(): StreamingSttProvider = when (this) {
    TencentAsrTier.STANDARD_FREE -> StreamingSttProvider.TENCENT_REALTIME_STANDARD
    TencentAsrTier.PRECISION_PAID -> StreamingSttProvider.TENCENT_REALTIME_PRECISION
}
