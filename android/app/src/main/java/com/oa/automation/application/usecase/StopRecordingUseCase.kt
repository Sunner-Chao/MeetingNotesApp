package com.oa.automation.application.usecase

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.Transcript
import com.oa.automation.domain.model.ProcessingProgress
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.infrastructure.audio.AudioRecorder
import com.oa.automation.infrastructure.stt.SpeechToTextEngine
import com.oa.automation.infrastructure.stt.buildSttContextHint
import com.oa.automation.locale.SimplifiedChineseText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class CloudAuthenticationRequiredException : IllegalStateException(AUTH_REQUIRED_MESSAGE)

const val AUTH_REQUIRED_MESSAGE = "登录后即可上传转写，本地录音不会丢失"

class StopRecordingUseCase(
    private val meetingRepository: MeetingRepository,
    private val audioRecorder: AudioRecorder,
    private val configDataStore: ConfigDataStore
) {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    suspend operator fun invoke(
        meetingId: String,
        audioFile: File? = null,
        transcriptId: String = UUID.randomUUID().toString(),
        streamSessionId: String? = null,
        journeyStageId: String? = null,
        onProgress: (ProcessingProgress) -> Unit = {}
    ): Result<Transcript> = withContext(Dispatchers.IO) {
        try {
            onProgress(ProcessingProgress(5, "准备最终转录"))
            // Use provided audio file or stop recorder to get it
            val file = audioFile ?: audioRecorder.stop()
                ?: return@withContext Result.failure(Exception("No audio file recorded"))
            _isRecording.value = false
            if (configDataStore.authSessionFlow.first() == null) {
                return@withContext Result.failure(CloudAuthenticationRequiredException())
            }

            // Get STT config and create appropriate engine
            onProgress(ProcessingProgress(12, "读取转录配置"))
            val sttConfig = configDataStore.appConfigFlow.first().sttConfig
            val sttEngine = SpeechToTextEngine.fromConfig(sttConfig)
            val meeting = meetingRepository.findById(meetingId).getOrNull()
            val contextHint = buildSttContextHint(
                meetingTitle = meeting?.title,
                templateName = meeting?.selectedTemplateName
            )

            // Transcribe audio
            val sttProgress: (ProcessingProgress) -> Unit = onProgress
            val streamResult = streamSessionId
                ?.takeIf { it.isNotBlank() }
                ?.let { sttEngine.transcribeStreamSession(it, sttProgress) }
            val transcriptionResult = streamResult
                ?.takeIf { it.isSuccess }
                ?: sttEngine.transcribe(
                    audioFile = file,
                    onProgress = sttProgress,
                    meetingId = meetingId,
                    archiveKey = streamSessionId?.takeIf { it.isNotBlank() } ?: transcriptId,
                    contextHint = contextHint
                )
            onProgress(ProcessingProgress(88, "整理识别文本"))
            val transcriptText = transcriptionResult
                .getOrElse { error ->
                    return@withContext Result.failure(error)
                }
                .let(SimplifiedChineseText::normalize)

            val transcript = Transcript(
                id = transcriptId,
                meetingId = meetingId,
                journeyStageId = journeyStageId,
                content = transcriptText
            )
            onProgress(ProcessingProgress(96, "保存最终转录"))
            meetingRepository.saveTranscript(transcript).getOrThrow()
            onProgress(ProcessingProgress(100, "最终转录已完成"))
            Result.success(transcript)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
