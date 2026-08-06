package com.oa.automation.infrastructure.community

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.oa.automation.infrastructure.background.CommunitySyncWorker
import java.util.concurrent.TimeUnit

fun interface CommunitySyncEnqueuer {
    fun enqueue(postId: String)
}

class CommunitySyncScheduler(context: Context) : CommunitySyncEnqueuer {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override fun enqueue(postId: String) {
        val request = OneTimeWorkRequestBuilder<CommunitySyncWorker>()
            .setInputData(workDataOf(CommunitySyncWorker.KEY_POST_ID to postId))
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(tag(postId))
            .build()
        workManager.enqueueUniqueWork(
            workName(postId),
            // Each new intent follows any in-flight upload. This closes the race where
            // publish/withdraw is requested while the private-draft worker is running.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    companion object {
        fun workName(postId: String) = "community-sync-$postId"
        fun tag(postId: String) = "community-sync:$postId"
    }
}
