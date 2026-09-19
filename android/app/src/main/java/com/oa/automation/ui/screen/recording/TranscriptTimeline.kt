package com.oa.automation.ui.screen.recording

import com.oa.automation.domain.model.Transcript
import com.oa.automation.domain.model.renderedContent
import com.oa.automation.infrastructure.stt.StreamingTranscriptSegment
import java.util.Locale

data class TranscriptTimelineRow(
    val key: String,
    val startTimeMs: Long?,
    val text: String,
    val speaker: String? = null,
    val isPreview: Boolean = false
)

internal fun List<Transcript>.toTimelineRows(): List<TranscriptTimelineRow> =
    filter { it.content.isNotBlank() }.mapIndexed { index, row ->
        TranscriptTimelineRow(
            key = "saved:${row.id}:$index",
            startTimeMs = row.startTimeMs.takeIf { row.endTimeMs > row.startTimeMs },
            text = row.renderedContent().let { rendered ->
                row.speakerName?.takeIf { it.isNotBlank() }?.let { speaker ->
                    rendered.replaceFirst(Regex("^" + Regex.escape(speaker) + "\\s*[：:]\\s*"), "")
                } ?: rendered
            },
            speaker = row.speakerName
        )
    }

internal fun streamingTimelineRows(
    segments: List<StreamingTranscriptSegment>,
    offsetMs: Long
): List<TranscriptTimelineRow> = segments.mapIndexed { index, segment ->
    TranscriptTimelineRow(
        key = "live:$offsetMs:${segment.startSeconds}:$index",
        startTimeMs = offsetMs + (segment.startSeconds * 1_000).toLong(),
        text = segment.text,
        speaker = segment.speaker?.let { "说话人 ${it + 1}" },
        isPreview = !segment.committed
    )
}

/** Never hide a transcript tail if an older server supplied only some timed rows. */
internal fun timelineCoversTranscript(rows: List<TranscriptTimelineRow>, transcript: String): Boolean {
    fun normalize(value: String) = value.replace(Regex("说话人\\s*\\d+\\s*[：:]"), "")
        .replace(Regex("\\s+"), "")
    return rows.isNotEmpty() && normalize(rows.joinToString("") { it.text }) == normalize(transcript)
}

internal fun transcriptTimestamp(timeMs: Long): String {
    val seconds = timeMs.coerceAtLeast(0) / 1_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
}
