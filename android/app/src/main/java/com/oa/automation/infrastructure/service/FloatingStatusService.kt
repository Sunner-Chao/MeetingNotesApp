package com.oa.automation.infrastructure.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.oa.automation.R
import com.oa.automation.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Desktop status ball shown while the app is in the background when enabled by the user.
 * Tapping returns to the active recording. It never changes recording state.
 */
class FloatingStatusService : Service() {
    private val recordingController: RecordingSessionController by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var ball: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var activeMeetingId: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW, ACTION_UPDATE -> {
                activeMeetingId = intent.getStringExtra(EXTRA_MEETING_ID).orEmpty()
                if (!Settings.canDrawOverlays(this) || !isRecordingActive()) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                showBallIfNeeded()
            }
            ACTION_HIDE -> {
                removeBall()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            recordingController.state.collectLatest { state ->
                activeMeetingId = state.meetingId.takeIf {
                    state.isRecording || state.isStarting || state.isStopping
                }.orEmpty()
                if (!isRecordingActive()) {
                    removeBall()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                ball?.contentDescription = if (state.isPaused) "录音已暂停" else "正在录音"
                ball?.alpha = if (state.isPaused) 0.72f else 1f
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeBall()
        scope.cancel()
        super.onDestroy()
    }

    private fun showBallIfNeeded() {
        if (!isRecordingActive()) {
            removeBall()
            return
        }
        if (ball != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (72 * resources.displayMetrics.density).toInt()
        val root = FrameLayout(this).apply {
            isClickable = true
            isFocusable = false
            background = null
            setOnClickListener {
                startActivity(
                    Intent(this@FloatingStatusService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(MainActivity.EXTRA_OPEN_RECORDING_MEETING_ID, activeMeetingId)
                    }
                )
            }
            setOnTouchListener(DragListener())
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.floating_drop_art)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "智悟本后台悬浮水滴"
        }
        root.addView(icon, FrameLayout.LayoutParams(-1, -1))
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - size - 20).coerceAtLeast(0)
            y = resources.displayMetrics.heightPixels / 2
        }
        runCatching {
            manager.addView(root, params)
            windowManager = manager
            ball = root
            layoutParams = params
        }.onFailure { stopSelf() }
    }

    private fun removeBall() {
        val current = ball ?: return
        runCatching { windowManager?.removeView(current) }
        ball = null
        layoutParams = null
        windowManager = null
    }

    private fun isRecordingActive(): Boolean {
        val state = recordingController.state.value
        return state.isRecording || state.isStarting || state.isStopping
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this,
            702,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_RECORDING_MEETING_ID, activeMeetingId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("智悟本悬浮球已开启")
            .setContentText("点击返回正在进行的录音")
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "悬浮球状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "智悟本切换到后台时显示悬浮球"
                setShowBadge(false)
            }
        )
    }

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { windowManager?.updateViewLayout(view, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    return true
                }
            }
            return true
        }
    }

    companion object {
        const val ACTION_SHOW = "com.oa.automation.FLOATING_SHOW"
        const val ACTION_UPDATE = "com.oa.automation.FLOATING_UPDATE"
        const val ACTION_HIDE = "com.oa.automation.FLOATING_HIDE"
        const val EXTRA_MEETING_ID = "floating_meeting_id"
        private const val CHANNEL_ID = "floating_status"
        private const val NOTIFICATION_ID = 2103

        fun show(context: Context, meetingId: String) {
            val intent = Intent(context, FloatingStatusService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_MEETING_ID, meetingId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun hide(context: Context) {
            context.startService(Intent(context, FloatingStatusService::class.java).apply {
                action = ACTION_HIDE
            })
        }
    }
}
