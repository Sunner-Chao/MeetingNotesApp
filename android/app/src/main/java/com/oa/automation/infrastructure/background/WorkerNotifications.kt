package com.oa.automation.infrastructure.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.oa.automation.ui.MainActivity
import java.util.UUID

private const val CHANNEL_ID = "MeetingBackgroundTasks"

internal fun Context.backgroundTaskForegroundInfo(
    notificationId: Int,
    title: String,
    content: String,
    workId: UUID
): ForegroundInfo {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台处理", NotificationManager.IMPORTANCE_LOW).apply {
                description = "会议转写与纪要生成任务"
                setShowBadge(false)
            }
        )
    }
    val contentIntent = PendingIntent.getActivity(
        this,
        notificationId,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(title)
        .setContentText(content)
        .setContentIntent(contentIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "终止",
            WorkManager.getInstance(this).createCancelPendingIntent(workId)
        )
        .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(notificationId, notification)
    }
}
