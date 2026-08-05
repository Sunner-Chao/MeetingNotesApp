package com.oa.automation.infrastructure.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import com.oa.automation.domain.repository.ScheduledMeetingRepository

/** AlarmManager entries are rebuilt after a device reboot or time-zone change. */
class ScheduledMeetingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = GlobalContext.get().get<ScheduledMeetingRepository>()
                val scheduler = ScheduledMeetingNotificationScheduler(context)
                repository.findUpcoming().forEach(scheduler::schedule)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
