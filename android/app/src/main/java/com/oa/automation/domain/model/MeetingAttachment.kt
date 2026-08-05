package com.oa.automation.domain.model

data class MeetingAttachment(
    val id: String,
    val meetingId: String,
    val displayName: String,
    val localPath: String,
    val mimeType: String,
    val createdAt: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val locationCapturedAt: Long? = null,
    val locationSource: String? = null
)
