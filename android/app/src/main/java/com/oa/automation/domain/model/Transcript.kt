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

/** Renders a transcript without duplicating speaker labels already stored in legacy content. */
fun Transcript.renderedContent(): String {
    val body = content.trim()
    val speaker = speakerName?.trim().orEmpty()
    if (body.isBlank() || speaker.isBlank()) return body

    val escapedSpeaker = Regex.escape(speaker)
    val alreadyPrefixed = listOf(
        // Current format: 说话人 1：内容 / 说话人 1 内容.
        Regex("^$escapedSpeaker(?:\\s*[：:]|\\s+|$)"),
        // Legacy imports: [说话人 1] 内容, 【说话人 1】内容, or (说话人 1) 内容.
        Regex("^\\s*[\\[【(]\\s*$escapedSpeaker\\s*[\\]】)](?:\\s*[：:]?\\s*|$)")
    ).any { it.containsMatchIn(body) }
    return if (alreadyPrefixed) body else "$speaker：$body"
}

/**
 * Stage snapshots support study-tour drafting, while unscoped transcripts are
 * the canonical full-meeting record. Prefer the canonical record when both are
 * present so the same speech is not shown or summarized twice.
 */
fun List<Transcript>.canonicalMeetingTranscripts(): List<Transcript> {
    val fullMeetingTranscripts = filter { it.journeyStageId == null }
    return fullMeetingTranscripts.ifEmpty { this }
}
