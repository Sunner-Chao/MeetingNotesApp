package com.oa.automation.infrastructure.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.oa.automation.application.usecase.StopRecordingUseCase
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
        if (meetingId.isBlank() || audioPath.isBlank() || transcriptId.isBlank()) {
            return failure("后台转写参数不完整")
        }
        val audioFile = File(audioPath)
        if (!audioFile.isFile || audioFile.length() == 0L) {
            return failure("录音文件不存在或为空")
        }

        try {
            setForeground(
                applicationContext.backgroundTaskForegroundInfo(
                    NOTIFICATION_ID,
                    "正在生成最终转写",
                    "可返回应用查看进度"
                )
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not promote transcription work; continuing as scheduled work", error)
        }
        val result = stopRecordingUseCase(meetingId, audioFile, transcriptId)
        if (result.isSuccess) return Result.success()

        val message = result.exceptionOrNull()?.message ?: "STT 服务请求失败"
        return if (runAttemptCount < MAX_RETRIES) Result.retry() else failure(message)
    }

    private fun failure(message: String) =
        Result.failure(workDataOf(BackgroundTaskScheduler.KEY_ERROR to message.take(500)))

    companion object {
        const val KEY_MEETING_ID = "meeting_id"
        const val KEY_AUDIO_PATH = "audio_path"
        const val KEY_TRANSCRIPT_ID = "transcript_id"
        private const val MAX_RETRIES = 2
        private const val NOTIFICATION_ID = 2101
        private const val TAG = "TranscriptionWorker"
    }
}
