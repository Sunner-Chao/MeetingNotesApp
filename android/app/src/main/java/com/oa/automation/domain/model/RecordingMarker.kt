package com.oa.automation.domain.model

data class RecordingMarker(
    val id: String,
    val meetingId: String,
    val journeyStageId: String? = null,
    val timestampMs: Long,
    val transcriptAnchor: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
