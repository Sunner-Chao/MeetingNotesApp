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

/**
 * Stage snapshots support study-tour drafting, while unscoped transcripts are
 * the canonical full-meeting record. Prefer the canonical record when both are
 * present so the same speech is not shown or summarized twice.
 */
fun List<Transcript>.canonicalMeetingTranscripts(): List<Transcript> {
    val fullMeetingTranscripts = filter { it.journeyStageId == null }
    return fullMeetingTranscripts.ifEmpty { this }
}
