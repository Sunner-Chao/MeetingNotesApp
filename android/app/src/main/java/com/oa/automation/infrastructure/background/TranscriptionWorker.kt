package com.oa.automation.infrastructure.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.oa.automation.application.usecase.StopRecordingUseCase
import com.oa.automation.domain.model.ProcessingProgress
import java.io.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class TranscriptionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {
    private val stopRecordingUseCase: StopRecordingUseCase by inject()

    override suspend fun doWork(): Result {
        val meetingId = inputData.getString(KEY_MEETING_ID).orEmpty()
        val audioPath = inputData.getString(KEY_AUDIO_PATH).orEmpty()
        val transcriptId = inputData.getString(KEY_TRANSCRIPT_ID).orEmpty()
        val streamSessionId = inputData.getString(KEY_STREAM_SESSION_ID)
        val journeyStageId = inputData.getString(KEY_JOURNEY_STAGE_ID)?.takeIf { it.isNotBlank() }
        if (meetingId.isBlank() || audioPath.isBlank() || transcriptId.isBlank()) {
            return failure("后台转写参数不完整")
        }
        val audioFile = File(audioPath)
        if (!audioFile.isFile || audioFile.length() == 0L) {
            return failure("录音文件不存在或为空")
        }

        publishProgress(ProcessingProgress(2, "最终转录任务已启动"))

        try {
            setForeground(
                applicationContext.backgroundTaskForegroundInfo(
                    NOTIFICATION_ID,
                    "正在生成最终转写",
                    "可返回应用查看进度",
                    id
                )
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not promote transcription work; continuing as scheduled work", error)
        }
        val result = stopRecordingUseCase(
            meetingId = meetingId,
            audioFile = audioFile,
            transcriptId = transcriptId,
            streamSessionId = streamSessionId,
            journeyStageId = journeyStageId,
            onProgress = ::publishProgress
        )
        if (result.isSuccess) return Result.success()

        val message = result.exceptionOrNull()?.message ?: "STT 服务请求失败"
        return if (runAttemptCount < MAX_RETRIES) Result.retry() else failure(message)
    }

    private fun failure(message: String) =
        Result.failure(workDataOf(BackgroundTaskScheduler.KEY_ERROR to message.take(500)))

    private fun publishProgress(progress: ProcessingProgress) {
        setProgressAsync(
            workDataOf(
                BackgroundTaskScheduler.KEY_PROGRESS_PERCENT to progress.percent,
                BackgroundTaskScheduler.KEY_PROGRESS_STAGE to progress.stage,
                BackgroundTaskScheduler.KEY_PROGRESS_INDETERMINATE to progress.isIndeterminate
            )
        )
    }

    companion object {
        const val KEY_MEETING_ID = "meeting_id"
        const val KEY_AUDIO_PATH = "audio_path"
        const val KEY_TRANSCRIPT_ID = "transcript_id"
        const val KEY_STREAM_SESSION_ID = "stream_session_id"
        const val KEY_JOURNEY_STAGE_ID = "journey_stage_id"
        private const val MAX_RETRIES = 2
        private const val NOTIFICATION_ID = 2101
        private const val TAG = "TranscriptionWorker"
    }
}
