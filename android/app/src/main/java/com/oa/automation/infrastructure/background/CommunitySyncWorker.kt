package com.oa.automation.infrastructure.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.oa.automation.infrastructure.community.CommunitySyncProcessor
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class CommunitySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {
    private val processor: CommunitySyncProcessor by inject()

    override suspend fun doWork(): Result {
        val postId = inputData.getString(KEY_POST_ID).orEmpty()
        if (postId.isBlank()) return Result.failure()
        return when (val result = processor.run(postId, runAttemptCount)) {
            is com.oa.automation.infrastructure.community.ProcessingResult.Success -> Result.success()
            is com.oa.automation.infrastructure.community.ProcessingResult.Retry -> Result.retry()
            is com.oa.automation.infrastructure.community.ProcessingResult.Failure ->
                Result.failure(androidx.work.workDataOf(BackgroundTaskScheduler.KEY_ERROR to result.message))
        }
    }

    companion object {
        const val KEY_POST_ID = "post_id"
    }
}
