package com.oa.automation.infrastructure.stt

import com.oa.automation.locale.SimplifiedChineseText

/** Keeps the usable live transcript while the server revises or reconnects a stream. */
internal class StreamingTranscriptAccumulator {
    private var preservedText = ""
    private var currentSessionId = ""
    private var latestText = ""
    private var committedText = ""
    private var previewText = ""
    private var currentSessionOffsetSeconds = 0f
    private var currentSessionDurationSeconds = 0f
    private val preservedSegments = mutableListOf<StreamingTranscriptSegment>()
    private val currentSegments = mutableListOf<StreamingTranscriptSegment>()

    @Synchronized
    fun reset() {
        preservedText = ""
        currentSessionId = ""
        latestText = ""
        committedText = ""
        previewText = ""
        currentSessionOffsetSeconds = 0f
        currentSessionDurationSeconds = 0f
        preservedSegments.clear()
        currentSegments.clear()
    }

    @Synchronized
    fun update(update: StreamingTranscriptUpdate): String {
        val incomingText = normalizeStreamingTranscript(update.text)
        val incomingCommitted = normalizeStreamingTranscript(update.committedText)
        val incomingPreview = normalizeStreamingTranscript(update.previewText)
        val incomingSessionId = update.sessionId.trim()
        val incomingTimelineOffsetSeconds = update.timelineOffsetSeconds.coerceAtLeast(0f)
        val incomingSessionText = sessionText(incomingText, incomingCommitted, incomingPreview)
        val incomingSegments = update.segments
            .asSequence()
            .mapNotNull(::normalizeSegment)
            .toList()
        if (incomingSessionText.isBlank() && incomingSegments.isEmpty()) return snapshotLocked()

        val previousSessionText = sessionText(latestText, committedText, previewText)
        val sessionChanged = currentSessionId.isNotBlank() &&
            incomingSessionId.isNotBlank() &&
            currentSessionId != incomingSessionId
        if (sessionChanged) {
            preservedText = mergeStreamingTranscriptText(preservedText, previousSessionText)
            preservedSegments += currentSegments
            currentSegments.clear()
            currentSessionOffsetSeconds = maxOf(
                currentSessionOffsetSeconds + currentSessionDurationSeconds,
                preservedSegments.maxOfOrNull { it.endSeconds } ?: 0f,
                incomingTimelineOffsetSeconds
            )
            currentSessionDurationSeconds = 0f
            latestText = ""
            committedText = ""
            previewText = ""
        } else if (isMaterialRegression(previousSessionText, incomingSessionText)) {
            preservedText = mergeStreamingTranscriptText(preservedText, previousSessionText)
        }

        // The streaming client derives this from the amount of PCM already
        // produced before the reconnect queue. Keep it when the first update
        // of a session arrives, including a session that started after audio
        // was buffered before the socket opened.
        if (!sessionChanged && currentSegments.isEmpty() && incomingTimelineOffsetSeconds > 0f) {
            currentSessionOffsetSeconds = maxOf(currentSessionOffsetSeconds, incomingTimelineOffsetSeconds)
        }
        if (incomingSessionId.isNotBlank()) currentSessionId = incomingSessionId
        latestText = incomingText
        committedText = incomingCommitted
        previewText = incomingPreview
        if (incomingSegments.isNotEmpty()) {
            currentSessionDurationSeconds = maxOf(
                currentSessionDurationSeconds,
                incomingSegments.maxOf { it.endSeconds }
            )
            mergeSegments(
                currentSegments,
                incomingSegments.map { segment ->
                    segment.copy(
                        startSeconds = segment.startSeconds + currentSessionOffsetSeconds,
                        endSeconds = segment.endSeconds + currentSessionOffsetSeconds
                    )
                }
            )
        }
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): String = snapshotLocked()

    /** Speaker rows are persisted independently of the revisable display text. */
    @Synchronized
    fun snapshotSegments(): List<StreamingTranscriptSegment> =
        mergeAdjacentSegments(preservedSegments + currentSegments)

    private fun snapshotLocked(): String = mergeStreamingTranscriptText(
        preservedText,
        sessionText(latestText, committedText, previewText)
    )
}

private fun normalizeSegment(segment: StreamingTranscriptSegment): StreamingTranscriptSegment? {
    val text = normalizeStreamingTranscript(segment.text)
    if (text.isBlank() || segment.speaker == null) return null
    val start = segment.startSeconds.coerceAtLeast(0f)
    val end = segment.endSeconds.coerceAtLeast(start)
    return segment.copy(startSeconds = start, endSeconds = end, text = text)
}

private fun mergeSegments(
    target: MutableList<StreamingTranscriptSegment>,
    incoming: List<StreamingTranscriptSegment>
) {
    incoming.forEach { next ->
        target.removeAll { existing -> shouldReplaceSegment(existing, next) }
        target += next
    }
    target.sortBy { it.startSeconds }
    while (target.size > 160) target.removeAt(0)
}

private fun shouldReplaceSegment(
    existing: StreamingTranscriptSegment,
    next: StreamingTranscriptSegment
): Boolean {
    val overlap = minOf(existing.endSeconds, next.endSeconds) -
        maxOf(existing.startSeconds, next.startSeconds)
    if (overlap <= 0f) return false
    val shorterDuration = minOf(
        (existing.endSeconds - existing.startSeconds).coerceAtLeast(0.1f),
        (next.endSeconds - next.startSeconds).coerceAtLeast(0.1f)
    )
    return overlap / shorterDuration >= 0.6f ||
        kotlin.math.abs(existing.startSeconds - next.startSeconds) < 0.35f
}

private fun mergeAdjacentSegments(
    segments: List<StreamingTranscriptSegment>
): List<StreamingTranscriptSegment> = segments
    .sortedBy { it.startSeconds }
    .fold(mutableListOf()) { grouped, next ->
        val previous = grouped.lastOrNull()
        if (
            previous != null &&
            previous.speaker == next.speaker &&
            next.startSeconds - previous.endSeconds <= 1.5f
        ) {
            grouped[grouped.lastIndex] = previous.copy(
                endSeconds = maxOf(previous.endSeconds, next.endSeconds),
                text = mergeStreamingTranscriptText(previous.text, next.text),
                committed = previous.committed && next.committed
            )
        } else {
            grouped += next
        }
        grouped
    }

private fun sessionText(text: String, committedText: String, previewText: String): String =
    listOf(committedText, previewText)
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .ifBlank { text }

private fun isMaterialRegression(previous: String, incoming: String): Boolean {
    if (previous.length < 16 || incoming.isBlank() || incoming.length >= previous.length) return false
    return previous.length - incoming.length >= maxOf(12, previous.length / 4)
}

internal fun mergeStreamingTranscriptText(existing: String, update: String): String {
    val base = normalizeStreamingTranscript(existing)
    val next = normalizeStreamingTranscript(update)
    if (base.isBlank()) return next
    if (next.isBlank() || base.contains(next)) return base
    if (next.contains(base)) return next

    val overlap = longestStreamingTranscriptOverlap(base, next)
    return if (overlap >= 4) {
        normalizeStreamingTranscript(base + next.substring(overlap))
    } else {
        "$base\n$next"
    }
}

internal fun longestStreamingTranscriptOverlap(base: String, next: String): Int {
    val maxLength = minOf(base.length, next.length)
    for (length in maxLength downTo 1) {
        if (base.regionMatches(base.length - length, next, 0, length)) return length
    }
    return 0
}

private fun normalizeStreamingTranscript(value: String): String =
    SimplifiedChineseText.normalize(value)
        .replace(Regex("<[^>\\r\\n]{0,120}>"), " ")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex(" *\\n+ *"), "\n")
        .trim()
