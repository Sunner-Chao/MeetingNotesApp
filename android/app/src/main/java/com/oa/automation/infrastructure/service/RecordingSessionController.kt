package com.oa.automation.infrastructure.service

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.infrastructure.audio.AudioRecorder
import com.oa.automation.infrastructure.stt.StreamingSttClient
import com.oa.automation.infrastructure.stt.StreamingTranscriptUpdate
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RecordingSessionState(
    val meetingId: String = "",
    val meetingTitle: String = "",
    val isStarting: Boolean = false,
    val isRecording: Boolean = false,
    val streamUpdate: StreamingTranscriptUpdate? = null,
    val status: String = "流式预览",
    val error: String? = null
)

data class RecordingStopResult(val meetingId: String, val audioFile: File)

class RecordingSessionController(
    private val audioRecorder: AudioRecorder,
    private val streamingSttClient: StreamingSttClient,
    private val configDataStore: ConfigDataStore
) {
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(RecordingSessionState())
    val state: StateFlow<RecordingSessionState> = _state.asStateFlow()

    suspend fun start(meetingId: String, meetingTitle: String): Result<Unit> = operationMutex.withLock {
        runCatching {
            require(meetingId.isNotBlank()) { "会议标识缺失" }
            if (audioRecorder.isRecording() && _state.value.meetingId == meetingId) return@runCatching
            if (audioRecorder.isRecording()) error("已有其他会议正在录音")

            _state.value = RecordingSessionState(
                meetingId = meetingId,
                meetingTitle = meetingTitle,
                isStarting = true,
                status = "正在连接实时预览"
            )
            val sttConfig = configDataStore.appConfigFlow.first().sttConfig
            audioRecorder.setOnChunkAvailableListener(null)
            audioRecorder.setOnPcmDataListener { pcmBytes, _ -> streamingSttClient.sendAudio(pcmBytes) }
            streamingSttClient.start(
                endpoint = sttConfig.localEndpoint,
                apiToken = sttConfig.apiToken,
                onPartialText = { update ->
                    _state.update { it.copy(streamUpdate = update, status = "实时预览（可修订）") }
                },
                onStatus = { status -> _state.update { it.copy(status = status, error = null) } },
                onError = { error -> _state.update { it.copy(error = error) } }
            )
            val audioFile = audioRecorder.start()
            if (audioFile == null) {
                streamingSttClient.stop()
                audioRecorder.setOnPcmDataListener(null)
                error("无法启动录音，请检查麦克风权限")
            }
            _state.update {
                it.copy(isStarting = false, isRecording = true, status = "实时预览处理中", error = null)
            }
        }.onFailure { error ->
            _state.update {
                it.copy(isStarting = false, isRecording = false, error = "录音启动失败: ${error.message}")
            }
        }
    }

    suspend fun stop(): Result<RecordingStopResult> = operationMutex.withLock {
        runCatching {
            val meetingId = _state.value.meetingId
            require(meetingId.isNotBlank() && audioRecorder.isRecording()) { "没有在录音" }
            _state.update { it.copy(status = "正在结束录音") }
            audioRecorder.setOnPcmDataListener(null)
            streamingSttClient.stop()
            val audioFile = audioRecorder.stop()
                ?: error("录音文件不可用")
            require(audioFile.isFile && audioFile.length() > 0L) { "录音文件为空" }
            _state.update {
                it.copy(isStarting = false, isRecording = false, status = "后台转写已排队", error = null)
            }
            RecordingStopResult(meetingId, audioFile)
        }.onFailure { error ->
            _state.update { it.copy(isStarting = false, error = "停止录音失败: ${error.message}") }
        }
    }

    fun cancelSession() {
        audioRecorder.setOnPcmDataListener(null)
        streamingSttClient.stop()
        audioRecorder.cancel(deleteFile = false)
        _state.update { it.copy(isStarting = false, isRecording = false) }
    }
}
