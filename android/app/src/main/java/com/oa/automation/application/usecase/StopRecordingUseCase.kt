package com.oa.automation.application.usecase

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.Transcript
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.infrastructure.audio.AudioRecorder
import com.oa.automation.infrastructure.stt.SpeechToTextEngine
import com.oa.automation.locale.SimplifiedChineseText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
        transcriptId: String = UUID.randomUUID().toString()
    ): Result<Transcript> = withContext(Dispatchers.IO) {
        try {
            // Use provided audio file or stop recorder to get it
            val file = audioFile ?: audioRecorder.stop()
                ?: return@withContext Result.failure(Exception("No audio file recorded"))
            _isRecording.value = false

            // Get STT config and create appropriate engine
            val sttConfig = configDataStore.appConfigFlow.first().sttConfig
            val sttEngine = SpeechToTextEngine.fromConfig(sttConfig)

            // Transcribe audio
            val transcriptText = sttEngine.transcribe(file)
                .getOrElse { error ->
                    return@withContext Result.failure(Exception("STT failed: ${error.message}"))
                }
                .let(SimplifiedChineseText::normalize)

            val transcript = Transcript(
                id = transcriptId,
                meetingId = meetingId,
                content = transcriptText
            )
            meetingRepository.saveTranscript(transcript).getOrThrow()
            Result.success(transcript)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
