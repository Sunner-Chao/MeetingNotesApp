package com.oa.automation.domain.model

data class Transcript(
    val id: String = "",
    val meetingId: String,
    val journeyStageId: String? = null,
    val speakerName: String? = null,
    val content: String,
    val startTimeMs: Long = 0,
    val endTimeMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)
