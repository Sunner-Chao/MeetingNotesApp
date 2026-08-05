package com.oa.automation.infrastructure.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.oa.automation.application.usecase.GenerateReportUseCase
import com.oa.automation.domain.model.ProcessingProgress
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class GenerateReportWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {
    private val generateReportUseCase: GenerateReportUseCase by inject()

    override suspend fun doWork(): Result {
        val meetingId = inputData.getString(KEY_MEETING_ID).orEmpty()
        if (meetingId.isBlank()) return failure("会议标识缺失")

        publishProgress(ProcessingProgress(2, "会议纪要任务已启动"))

        try {
            setForeground(
                applicationContext.backgroundTaskForegroundInfo(
                    NOTIFICATION_ID,
                    "正在生成会议纪要",
                    "Agent 正在分析文字和图片",
                    id
                )
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not promote report work; continuing as scheduled work", error)
        }
        val result = generateReportUseCase(meetingId, ::publishProgress)
        if (result.isSuccess) return Result.success()

        val message = result.exceptionOrNull()?.message ?: "Agent 服务请求失败"
        return if (runAttemptCount < MAX_RETRIES && message.isRetryable()) {
            Result.retry()
        } else {
            failure(message)
        }
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

    private fun String.isRetryable(): Boolean {
        val value = lowercase()
        return value.contains("503") || value.contains("暂时") || value.contains("timeout") ||
            value.contains("timed out") || value.contains("请求失败") || value.contains("队列") ||
            value.contains("connection") || value.contains("network")
    }

    companion object {
        const val KEY_MEETING_ID = "meeting_id"
        private const val MAX_RETRIES = 2
        private const val NOTIFICATION_ID = 2102
        private const val TAG = "GenerateReportWorker"
    }
}
