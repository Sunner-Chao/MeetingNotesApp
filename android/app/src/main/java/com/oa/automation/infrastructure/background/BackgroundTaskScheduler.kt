package com.oa.automation.infrastructure.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class BackgroundTaskState { NONE, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class BackgroundTaskStatus(
    val id: UUID? = null,
    val state: BackgroundTaskState = BackgroundTaskState.NONE,
    val error: String? = null,
    val runAttemptCount: Int = 0,
    val progressPercent: Int? = null,
    val progressStage: String = "",
    val progressIndeterminate: Boolean = false
) {
    val isActive: Boolean
        get() = state == BackgroundTaskState.QUEUED || state == BackgroundTaskState.RUNNING
}

class BackgroundTaskScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueTranscription(
        meetingId: String,
        audioFile: File,
        streamSessionId: String? = null,
        journeyStageId: String? = null
    ): UUID {
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(
                workDataOf(
                    TranscriptionWorker.KEY_MEETING_ID to meetingId,
                    TranscriptionWorker.KEY_AUDIO_PATH to audioFile.absolutePath,
                    TranscriptionWorker.KEY_TRANSCRIPT_ID to UUID.randomUUID().toString(),
                    TranscriptionWorker.KEY_STREAM_SESSION_ID to streamSessionId.orEmpty(),
                    TranscriptionWorker.KEY_JOURNEY_STAGE_ID to journeyStageId.orEmpty()
                )
            )
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(transcriptionTag(meetingId))
            .build()
        workManager.enqueueUniqueWork(
            transcriptionWorkName(meetingId),
            // Keep every recording segment. A later stop must wait for the earlier
            // segment instead of replacing its final transcription task.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        return request.id
    }

    fun enqueueReport(meetingId: String, replaceRunning: Boolean = false): UUID {
        val request = OneTimeWorkRequestBuilder<GenerateReportWorker>()
            .setInputData(workDataOf(GenerateReportWorker.KEY_MEETING_ID to meetingId))
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(reportTag(meetingId))
            .build()
        workManager.enqueueUniqueWork(
            reportWorkName(meetingId),
            if (replaceRunning) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
        return request.id
    }

    fun observeTranscription(meetingId: String): Flow<BackgroundTaskStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(transcriptionWorkName(meetingId))
            .map(::latestStatus)

    fun observeReport(meetingId: String): Flow<BackgroundTaskStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(reportWorkName(meetingId))
            .map(::latestStatus)

    fun cancelTranscription(meetingId: String) {
        workManager.cancelUniqueWork(transcriptionWorkName(meetingId))
    }

    fun cancelReport(meetingId: String) {
        workManager.cancelUniqueWork(reportWorkName(meetingId))
    }

    private fun latestStatus(workInfos: List<WorkInfo>): BackgroundTaskStatus {
        val workInfo = workInfos.lastOrNull() ?: return BackgroundTaskStatus()
        return BackgroundTaskStatus(
            id = workInfo.id,
            state = when (workInfo.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> BackgroundTaskState.QUEUED
                WorkInfo.State.RUNNING -> BackgroundTaskState.RUNNING
                WorkInfo.State.SUCCEEDED -> BackgroundTaskState.SUCCEEDED
                WorkInfo.State.FAILED -> BackgroundTaskState.FAILED
                WorkInfo.State.CANCELLED -> BackgroundTaskState.CANCELLED
            },
            error = workInfo.outputData.getString(KEY_ERROR),
            runAttemptCount = workInfo.runAttemptCount,
            progressPercent = workInfo.progress.getInt(KEY_PROGRESS_PERCENT, -1).takeIf { it >= 0 },
            progressStage = workInfo.progress.getString(KEY_PROGRESS_STAGE).orEmpty(),
            progressIndeterminate = workInfo.progress.getBoolean(KEY_PROGRESS_INDETERMINATE, false)
        )
    }

    companion object {
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_PROGRESS_STAGE = "progress_stage"
        const val KEY_PROGRESS_INDETERMINATE = "progress_indeterminate"
        fun transcriptionWorkName(meetingId: String) = "meeting-transcription-$meetingId"
        fun reportWorkName(meetingId: String) = "meeting-report-$meetingId"
        private fun transcriptionTag(meetingId: String) = "transcription:$meetingId"
        private fun reportTag(meetingId: String) = "report:$meetingId"
    }
}
