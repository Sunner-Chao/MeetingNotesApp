package com.oa.automation.infrastructure.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oa.automation.infrastructure.background.BackgroundTaskScheduler
import com.oa.automation.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

/** Owns microphone capture and streaming STT independently of any Activity/ViewModel. */
class RecordingService : Service() {
    private val recordingController: RecordingSessionController by inject()
    private val taskScheduler: BackgroundTaskScheduler by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    private var stopJob: Job? = null
    private var cancelJob: Job? = null
    private var controlJob: Job? = null
    @Volatile private var activeMeetingId: String? = null
    @Volatile private var activeJourneyStageId: String? = null
    private var pendingStart: PendingStart? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val meetingId = intent.getStringExtra(EXTRA_MEETING_ID).orEmpty()
                val meetingTitle = intent.getStringExtra(EXTRA_MEETING_TITLE) ?: "会议录音"
                val journeyStageId = intent.getStringExtra(EXTRA_JOURNEY_STAGE_ID)
                    ?.takeIf { it.isNotBlank() }
                if (meetingId.isBlank()) {
                    Log.w(TAG, "Ignoring start without meeting id")
                    return START_NOT_STICKY
                }
                val currentMeetingId = activeMeetingId
                if (currentMeetingId != null) {
                    if (
                        currentMeetingId == meetingId &&
                        (stopJob?.isActive == true || recordingController.state.value.isStopping)
                    ) {
                        // ACTION_STOP releases the microphone before this service
                        // finishes its cleanup. Queue a fast second tap instead of
                        // dropping it during that transition window.
                        pendingStart = PendingStart(meetingId, meetingTitle, journeyStageId)
                    } else if (currentMeetingId != meetingId) {
                        Log.w(TAG, "Ignoring start for $meetingId while $currentMeetingId is active")
                    }
                    return START_NOT_STICKY
                }
                activeMeetingId = meetingId
                activeJourneyStageId = journeyStageId
                // The floating ball is reserved for an idle app in the background.
                FloatingStatusService.hide(this)
                startForegroundWithNotification(meetingTitle)
                launchRecording(meetingId, meetingTitle, startId)
            }

            ACTION_STOP -> {
                val meetingId = intent.getStringExtra(EXTRA_MEETING_ID)
                    ?: activeMeetingId
                    ?: recordingController.state.value.meetingId
                if (meetingId.isBlank() || activeMeetingId != meetingId || stopJob?.isActive == true) {
                    return START_NOT_STICKY
                }
                recordingController.markStopRequested(meetingId)
                updateNotification("正在结束录音", "最终转写将在后台继续")
                stopJob = serviceScope.launch {
                    startJob?.join()
                    recordingController.stop(meetingId)
                        .onSuccess { stopped ->
                            taskScheduler.enqueueTranscription(
                                meetingId = stopped.meetingId,
                                audioFile = stopped.audioFile,
                                streamSessionId = stopped.streamSessionId,
                                journeyStageId = activeJourneyStageId
                            )
                        }
                        .onFailure { error -> Log.i(TAG, "Recording session did not produce audio", error) }
                    finishSessionIfCurrent(meetingId, startId)
                }
            }

            ACTION_CANCEL -> {
                val meetingId = intent.getStringExtra(EXTRA_MEETING_ID)
                    ?: activeMeetingId
                    ?: recordingController.state.value.meetingId
                if (meetingId.isBlank() || activeMeetingId != meetingId || cancelJob?.isActive == true) {
                    return START_NOT_STICKY
                }
                recordingController.markStopRequested(meetingId)
                cancelJob = serviceScope.launch {
                    startJob?.join()
                    recordingController.cancelSession(deleteFile = true)
                    finishSessionIfCurrent(meetingId, startId)
                }
            }

            ACTION_PAUSE -> {
                val meetingId = intent.getStringExtra(EXTRA_MEETING_ID)
                    ?: activeMeetingId
                    ?: recordingController.state.value.meetingId
                if (meetingId.isBlank() || activeMeetingId != meetingId || controlJob?.isActive == true) {
                    return START_NOT_STICKY
                }
                controlJob = serviceScope.launch {
                    recordingController.pause(meetingId).fold(
                        onSuccess = { updateNotification("录音已暂停", "点击继续可恢复录音") },
                        onFailure = { updateNotification("正在录音", "暂停失败，请返回应用重试") }
                    )
                    controlJob = null
                }
            }

            ACTION_RESUME -> {
                val meetingId = intent.getStringExtra(EXTRA_MEETING_ID)
                    ?: activeMeetingId
                    ?: recordingController.state.value.meetingId
                if (meetingId.isBlank() || activeMeetingId != meetingId || controlJob?.isActive == true) {
                    return START_NOT_STICKY
                }
                controlJob = serviceScope.launch {
                    recordingController.resume(meetingId).fold(
                        onSuccess = { updateNotification("正在录音", "录音与实时转写正在后台运行") },
                        onFailure = { updateNotification("录音已暂停", "继续失败，请返回应用重试") }
                    )
                    controlJob = null
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (
            recordingController.state.value.isRecording ||
            recordingController.state.value.isStarting ||
            recordingController.state.value.isStopping
        ) {
            runBlocking(Dispatchers.IO) { recordingController.cancelSession() }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun finishSessionIfCurrent(meetingId: String, startId: Int) {
        if (activeMeetingId != meetingId) return
        activeMeetingId = null
        activeJourneyStageId = null
        startJob = null
        stopJob = null
        cancelJob = null
        controlJob = null
        val next = pendingStart
        pendingStart = null
        if (next != null) {
            activeMeetingId = next.meetingId
            activeJourneyStageId = next.journeyStageId
            FloatingStatusService.hide(this)
            startForegroundWithNotification(next.title)
            launchRecording(next.meetingId, next.title, startId)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun launchRecording(meetingId: String, meetingTitle: String, startId: Int) {
        startJob = serviceScope.launch {
            recordingController.start(meetingId, meetingTitle).onFailure { error ->
                Log.e(TAG, "Recording session failed to start", error)
                finishSessionIfCurrent(meetingId, startId)
            }
        }
    }

    private data class PendingStart(
        val meetingId: String,
        val title: String,
        val journeyStageId: String?
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "录音与实时转写",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "App 进入后台后继续录音和实时转写"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(title: String) {
        val notification = buildNotification("正在录音：$title", "录音与实时转写正在后台运行")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, content: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    private fun buildNotification(title: String, content: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val controlIntent = Intent(this, RecordingService::class.java).apply {
            action = if (recordingController.state.value.isPaused) ACTION_RESUME else ACTION_PAUSE
            putExtra(EXTRA_MEETING_ID, recordingController.state.value.meetingId)
        }
        val controlPendingIntent = PendingIntent.getService(
            this,
            1,
            controlIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_MEETING_ID, recordingController.state.value.meetingId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            2,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(contentIntent)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                if (recordingController.state.value.isPaused) {
                    android.R.drawable.ic_media_play
                } else {
                    android.R.drawable.ic_media_pause
                },
                if (recordingController.state.value.isPaused) "继续录音" else "暂停录音",
                controlPendingIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "放弃录音", cancelPendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "RecordingServiceChannel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.oa.automation.action.START_RECORDING"
        const val ACTION_STOP = "com.oa.automation.action.STOP_RECORDING"
        const val ACTION_CANCEL = "com.oa.automation.action.CANCEL_RECORDING"
        const val ACTION_PAUSE = "com.oa.automation.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.oa.automation.action.RESUME_RECORDING"
        const val EXTRA_MEETING_ID = "meeting_id"
        const val EXTRA_MEETING_TITLE = "meeting_title"
        const val EXTRA_JOURNEY_STAGE_ID = "journey_stage_id"
    }
}
