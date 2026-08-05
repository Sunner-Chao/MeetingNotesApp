package com.oa.automation.domain.model

/** A meeting that has been planned but has not started recording yet. */
data class ScheduledMeeting(
    val id: String = "",
    val title: String,
    val scheduledAt: Long,
    val reminderMinutes: Int = 15,
    val templateName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
