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
import com.oa.automation.R
import com.oa.automation.infrastructure.audio.AudioRecorder
import com.oa.automation.infrastructure.stt.StreamingSttClient

/**
 * Foreground service that keeps the audio recording + WebSocket STT session alive
 * when the app moves to the background.
 *
 * Lifecycle:
 *   start()  -> starts foreground notification + keeps AudioRecorder/STT running
 *   stop()   -> stops AudioRecorder + STT, stops foreground service
 *
 * The service is started/stopped from RecordingViewModel so that the Activity
 * can be destroyed without killing the recording session.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "RecordingServiceChannel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.oa.automation.action.START_RECORDING"
        const val ACTION_STOP  = "com.oa.automation.action.STOP_RECORDING"

        private var audioRecorder: AudioRecorder? = null
        private var streamingSttClient: StreamingSttClient? = null

        // Called by RecordingViewModel to hand over the live instances
        fun bindRecordingSession(recorder: AudioRecorder, sttClient: StreamingSttClient) {
            audioRecorder = recorder
            streamingSttClient = sttClient
        }

        fun isRunning(): Boolean = instance != null

        @Volatile
        private var instance: RecordingService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val meetingTitle = intent.getStringExtra("meeting_title") ?: "会议录音"
                startForegroundWithNotification(meetingTitle)
            }
            ACTION_STOP -> {
                stopRecordingSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRecordingSession()
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "录音服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持录音和转写连接"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(title: String) {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在录音：$title")
            .setContentText("点击停止录音")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "停止",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopRecordingSession() {
        try {
            streamingSttClient?.stop()
            audioRecorder?.cancel(deleteFile = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording session", e)
        } finally {
            audioRecorder = null
            streamingSttClient = null
        }
    }
}
