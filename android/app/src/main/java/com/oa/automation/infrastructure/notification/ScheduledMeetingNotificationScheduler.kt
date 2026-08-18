package com.oa.automation.infrastructure.notification

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.oa.automation.domain.model.ScheduledMeeting
import com.oa.automation.ui.MainActivity

private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 701

fun requestNotificationPermissionIfNeeded(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val activity = context as? android.app.Activity ?: return
    if (ActivityCompat.checkSelfPermission(
            activity,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE
        )
    }
}

class ScheduledMeetingNotificationScheduler(private val context: Context) {
    private val appContext = context.applicationContext

    init {
        createChannel()
    }

    fun schedule(meeting: ScheduledMeeting) {
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = (meeting.scheduledAt - meeting.reminderMinutes * 60_000L)
            .coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val intent = reminderIntent(meeting)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            requestCode(meeting.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    fun cancel(meetingId: String) {
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            requestCode(meetingId),
            Intent(appContext, ScheduledMeetingReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    fun postReminder(meetingId: String, title: String, scheduledAt: Long) {
        if (!canPostNotifications()) return
        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_SCHEDULED_MEETING
            putExtra(EXTRA_MEETING_ID, meetingId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openPendingIntent = PendingIntent.getActivity(
            appContext,
            requestCode(meetingId),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val formattedTime = android.text.format.DateFormat.format("HH:mm", scheduledAt)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("预定会议即将开始")
            .setContentText("$title · $formattedTime")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title 将于 $formattedTime 开始，点击打开智悟本。"))
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(appContext).notify(requestCode(meetingId), notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked after the check above.
        }
    }

    private fun reminderIntent(meeting: ScheduledMeeting) =
        Intent(appContext, ScheduledMeetingReminderReceiver::class.java).apply {
            putExtra(EXTRA_MEETING_ID, meeting.id)
            putExtra(EXTRA_MEETING_TITLE, meeting.title)
            putExtra(EXTRA_SCHEDULED_AT, meeting.scheduledAt)
        }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "预定会议提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "预定会议开始前的提醒"
                setShowBadge(true)
            }
        )
    }

    companion object {
        const val ACTION_OPEN_SCHEDULED_MEETING = "com.oa.automation.OPEN_SCHEDULED_MEETING"
        const val EXTRA_MEETING_ID = "scheduled_meeting_id"
        const val EXTRA_MEETING_TITLE = "scheduled_meeting_title"
        const val EXTRA_SCHEDULED_AT = "scheduled_meeting_at"
        const val CHANNEL_ID = "scheduled_meeting_reminders"

        fun requestCode(id: String): Int = id.hashCode() and 0x7fffffff
    }
}

class ScheduledMeetingReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ScheduledMeetingNotificationScheduler.EXTRA_MEETING_ID).orEmpty()
        val title = intent.getStringExtra(ScheduledMeetingNotificationScheduler.EXTRA_MEETING_TITLE)
            .orEmpty()
        val scheduledAt = intent.getLongExtra(
            ScheduledMeetingNotificationScheduler.EXTRA_SCHEDULED_AT,
            System.currentTimeMillis()
        )
        if (id.isNotBlank() && title.isNotBlank()) {
            ScheduledMeetingNotificationScheduler(context).postReminder(id, title, scheduledAt)
        }
    }
}
