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
import org.koin.android.ext.android.inject

/** Owns microphone capture and streaming STT independently of any Activity/ViewModel. */
class RecordingService : Service() {
    private val recordingController: RecordingSessionController by inject()
    private val taskScheduler: BackgroundTaskScheduler by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val meetingId = intent.getStringExtra(EXTRA_MEETING_ID).orEmpty()
                val meetingTitle = intent.getStringExtra(EXTRA_MEETING_TITLE) ?: "会议录音"
                startForegroundWithNotification(meetingTitle)
                serviceScope.launch {
                    recordingController.start(meetingId, meetingTitle).onFailure { error ->
                        Log.e(TAG, "Recording session failed to start", error)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(startId)
                    }
                }
            }

            ACTION_STOP -> {
                updateNotification("正在结束录音", "最终转写将在后台继续")
                serviceScope.launch {
                    recordingController.stop()
                        .onSuccess { stopped ->
                            taskScheduler.enqueueTranscription(stopped.meetingId, stopped.audioFile)
                        }
                        .onFailure { error -> Log.e(TAG, "Recording session failed to stop", error) }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (recordingController.state.value.isRecording || recordingController.state.value.isStarting) {
            recordingController.cancelSession()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

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
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
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
            .addAction(android.R.drawable.ic_media_pause, "停止录音", stopPendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "RecordingServiceChannel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.oa.automation.action.START_RECORDING"
        const val ACTION_STOP = "com.oa.automation.action.STOP_RECORDING"
        const val EXTRA_MEETING_ID = "meeting_id"
        const val EXTRA_MEETING_TITLE = "meeting_title"
    }
}
