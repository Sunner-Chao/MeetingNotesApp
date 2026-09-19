package com.oa.automation.infrastructure.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.oa.automation.domain.model.RoomCallState
import com.oa.automation.domain.model.RoomCallStatus
import com.oa.automation.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.android.ext.android.inject

/** A visible, user-started foreground call survives Activity navigation and backgrounding. */
class RoomCallService : Service() {
    private val controller: RoomCallController by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "会议通话", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        scope.launch {
            controller.state.map { Triple(it.status, it.muted, it.title) }.distinctUntilChanged().collect {
                val current = controller.state.value
                if (current.active) getSystemService(NotificationManager::class.java).notify(ID, notification(current))
                else if (started) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            JOIN -> {
                val roomId = intent.getStringExtra("room_id").orEmpty()
                val title = intent.getStringExtra("title").orEmpty()
                if (roomId.isBlank()) { stopSelf(); return START_NOT_STICKY }
                started = true
                try {
                    val preparing = notification(RoomCallState(roomId, title, RoomCallStatus.CONNECTING))
                    if (Build.VERSION.SDK_INT >= 30) startForeground(ID, preparing, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    else startForeground(ID, preparing)
                    controller.join(roomId, title)
                    if (!controller.state.value.active) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                } catch (_: Exception) {
                    controller.leave("无法启动通话，请允许麦克风权限后重试")
                    stopSelf()
                }
            }
            MUTE -> controller.toggleMicrophone()
            LEAVE -> { controller.leave(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(state: RoomCallState): Notification {
        val content = when (state.status) {
            RoomCallStatus.CONNECTING -> "正在加入通话"
            RoomCallStatus.RECONNECTING -> "网络波动，正在重新连接"
            else -> if (state.muted) "通话中 · 麦克风已关闭" else "通话中 · 麦克风已开启"
        }
        fun action(name: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, RoomCallService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(state.title.ifBlank { "聆听·策划会" }).setContentText(content)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ROOM_CALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, if (state.muted) "开启麦克风" else "静音", action(MUTE, 5))
            .addAction(0, "离开通话", action(LEAVE, 6)).build()
    }

    override fun onDestroy() {
        if (controller.state.value.active) controller.leave()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "MeetingRoomCall"
        private const val ID = 1011
        private const val JOIN = "com.oa.automation.room.JOIN"
        private const val MUTE = "com.oa.automation.room.MUTE"
        private const val LEAVE = "com.oa.automation.room.LEAVE"
        fun join(context: Context, roomId: String, title: String) {
            ContextCompat.startForegroundService(context, Intent(context, RoomCallService::class.java)
                .setAction(JOIN).putExtra("room_id", roomId).putExtra("title", title))
        }
    }
}
