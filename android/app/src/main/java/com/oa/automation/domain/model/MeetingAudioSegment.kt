package com.oa.automation.domain.model

/** One persisted WAV segment captured during a meeting's recording lifecycle. */
data class MeetingAudioSegment(
    val id: String,
    val meetingId: String,
    val sequenceNumber: Int,
    val localPath: String,
    val durationMs: Long,
    val bytes: Long,
    val createdAt: Long = System.currentTimeMillis()
)
