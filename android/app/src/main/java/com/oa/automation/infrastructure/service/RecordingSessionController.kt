package com.oa.automation.infrastructure.service

import android.os.SystemClock
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.infrastructure.audio.AudioRecorder
import com.oa.automation.infrastructure.stt.StreamingSttClient
import com.oa.automation.infrastructure.stt.StreamingSttProvider
import com.oa.automation.infrastructure.account.AccountSessionSynchronizer
import com.oa.automation.infrastructure.stt.StreamingTranscriptUpdate
import com.oa.automation.infrastructure.stt.StreamingTranscriptAccumulator
import com.oa.automation.infrastructure.stt.CloudSTTEngine
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlin.math.log10
import kotlin.math.sqrt

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
    val error: String? = null
)

data class RecordingStopResult(
    val meetingId: String,
    val audioFile: File,
    val streamSessionId: String?,
    val transcriptText: String,
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
                status = "正在结束录音",
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
            val accountSessionAvailable = configDataStore.authSessionFlow.first()?.expiresAt?.let {
                it > System.currentTimeMillis() / 1_000
            } == true
            // Refresh a valid account opportunistically. A failed refresh must not block
            // local recording; it only disables cloud fallback for this session.
            if (accountSessionAvailable) {
                accountSessionSynchronizer.refresh()
                sttConfig = configDataStore.appConfigFlow.first().sttConfig
            }
            accountAccessEnabled = accountSessionAvailable && !sttConfig.apiToken.isNullOrBlank()
            cloudFallbackAvailable = accountAccessEnabled && !sttConfig.cloudEndpoint.isNullOrBlank()
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
                    onPartialText = { update ->
                        val accumulatedText = transcriptAccumulator.update(update)
                        _state.update {
                            it.copy(
                                streamUpdate = update,
                                accumulatedTranscript = accumulatedText,
                                status = "实时预览（可修订）"
                            )
                        }
                    },
                    onStatus = { status -> _state.update { it.copy(status = status, error = null) } },
                    onError = { error -> _state.update { it.copy(error = error) } },
                    onProviderFailure = { provider, detail ->
                        if (provider == StreamingSttProvider.LOCAL) {
                            requestAutomaticCloudFallback(detail)
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
            val audioFile = audioRecorder.start()
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
                        stopWasRequested -> "正在结束录音"
                        useCloudInitially -> "智悟增强云模型识别中"
                        initialEndpoint.isBlank() -> "本地录音中"
                        else -> "本地实时预览处理中"
                    },
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
            _state.update { it.copy(isStopping = true, status = "正在结束录音") }
            val audioFile = audioRecorder.stop()
                ?: error("录音文件不可用")
            audioRecorder.setOnPcmDataListener(null)
            val streamSessionId = if (streamingPreviewActive) streamingSttClient.stop() else null
            streamingPreviewActive = false
            automaticCloudFallbackAttempted = false
            val transcriptText = transcriptAccumulator.snapshot()
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
                    error = null
                )
            }
            RecordingStopResult(
                meetingId,
                audioFile,
                streamSessionId,
                transcriptText,
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
                    error = "停止录音失败: ${error.message}"
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
                    it.copy(
                        status = "正在切换至${engineType.displayName}",
                        error = null
                    )
                }
                streamingSttClient.switchService(endpoint, provider).getOrThrow()
                _state.update {
                    it.copy(
                        status = "${engineType.displayName}已启用",
                        error = null
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(error = "识别引擎切换失败: ${error.message}")
                }
            }
        }

    private fun requestAutomaticCloudFallback(detail: String) {
        if (!cloudFallbackAvailable || automaticCloudFallbackAttempted) return
        if (!_state.value.isRecording || _state.value.isStopping) return
        automaticCloudFallbackAttempted = true
        _state.update {
            it.copy(
                status = "本地实时识别暂不可用，正在切换云端兜底",
                error = null
            )
        }
        fallbackScope.launch {
            val result = switchStreamingProvider(STTEngineType.TENCENT_HYBRID)
            _state.update {
                if (!it.isRecording || it.isStopping) return@update it
                if (result.isSuccess) {
                    it.copy(
                        status = "云端兜底实时识别中",
                        error = null
                    )
                } else {
                    it.copy(
                        status = "本地与云端实时识别均不可用",
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
                error = null
            )
        }
    }
}

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

internal fun RecordingSessionState.durationSecondsAt(elapsedRealtimeMs: Long): Long {
    val startedAt = startedAtElapsedRealtimeMs ?: return recordedDurationSeconds.coerceAtLeast(0)
    return recordedDurationSeconds + ((elapsedRealtimeMs - startedAt).coerceAtLeast(0) / 1_000L)
}

private fun TencentAsrTier.toStreamingProvider(): StreamingSttProvider = when (this) {
    TencentAsrTier.STANDARD_FREE -> StreamingSttProvider.TENCENT_REALTIME_STANDARD
    TencentAsrTier.PRECISION_PAID -> StreamingSttProvider.TENCENT_REALTIME_PRECISION
}
