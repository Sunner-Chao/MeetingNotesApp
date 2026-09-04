package com.oa.automation.application.usecase

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.Transcript
import com.oa.automation.domain.model.ProcessingProgress
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.infrastructure.account.AccountSessionSynchronizer
import com.oa.automation.infrastructure.account.isAuthenticationFailure
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

class CloudAuthenticationRequiredException : IllegalStateException(AUTH_REQUIRED_MESSAGE)

const val AUTH_REQUIRED_MESSAGE = "登录后即可上传转写，本地录音不会丢失"

class StopRecordingUseCase(
    private val meetingRepository: MeetingRepository,
    private val audioRecorder: AudioRecorder,
    private val configDataStore: ConfigDataStore,
    private val accountSessionSynchronizer: AccountSessionSynchronizer
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

            // Renew derived STT credentials opportunistically. The user only
            // maintains the normal account login session.
            onProgress(ProcessingProgress(12, "读取转录配置"))
            val hasAccountSession = configDataStore.authSessionFlow.first() != null
            if (hasAccountSession) {
                withTimeoutOrNull(3_000L) { accountSessionSynchronizer.refreshIfNeeded() }
            }
            var sttConfig = configDataStore.appConfigFlow.first().sttConfig
            var sttEngine = SpeechToTextEngine.fromConfig(sttConfig)
            val meeting = meetingRepository.findById(meetingId).getOrNull()
            val contextHint = buildSttContextHint(
                meetingTitle = meeting?.title,
                templateName = meeting?.selectedTemplateName
            )

            // Transcribe audio. A single 401 triggers one transparent session
            // refresh and retry, which avoids exposing token maintenance to users.
            val sttProgress: (ProcessingProgress) -> Unit = onProgress
            suspend fun transcribeWithCurrentEngine(): Result<String> {
                val streamResult = streamSessionId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sttEngine.transcribeStreamSession(it, sttProgress) }
                return streamResult
                    ?.takeIf { it.isSuccess }
                    ?: sttEngine.transcribe(
                        audioFile = file,
                        onProgress = sttProgress,
                        meetingId = meetingId,
                        archiveKey = streamSessionId?.takeIf { it.isNotBlank() } ?: transcriptId,
                        contextHint = contextHint
                    )
            }
            var transcriptionResult = transcribeWithCurrentEngine()
            if (hasAccountSession && transcriptionResult.exceptionOrNull()?.isAuthenticationFailure() == true) {
                onProgress(ProcessingProgress(14, "服务会话正在更新，请稍候", isIndeterminate = true))
                val refreshed = withTimeoutOrNull(5_000L) { accountSessionSynchronizer.refresh() }
                if (refreshed?.isSuccess == true) {
                    sttConfig = configDataStore.appConfigFlow.first().sttConfig
                    sttEngine = SpeechToTextEngine.fromConfig(sttConfig)
                    transcriptionResult = transcribeWithCurrentEngine()
                }
            }
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
