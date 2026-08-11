package com.oa.automation.infrastructure.stt

import com.oa.automation.locale.SimplifiedChineseText

/** Keeps the usable live transcript while the server revises or reconnects a stream. */
internal class StreamingTranscriptAccumulator {
    private var preservedText = ""
    private var currentSessionId = ""
    private var latestText = ""
    private var committedText = ""
    private var previewText = ""

    @Synchronized
    fun reset() {
        preservedText = ""
        currentSessionId = ""
        latestText = ""
        committedText = ""
        previewText = ""
    }

    @Synchronized
    fun update(update: StreamingTranscriptUpdate): String {
        val incomingText = normalizeStreamingTranscript(update.text)
        val incomingCommitted = normalizeStreamingTranscript(update.committedText)
        val incomingPreview = normalizeStreamingTranscript(update.previewText)
        val incomingSessionId = update.sessionId.trim()
        val incomingSessionText = sessionText(incomingText, incomingCommitted, incomingPreview)
        if (incomingSessionText.isBlank()) return snapshotLocked()

        val previousSessionText = sessionText(latestText, committedText, previewText)
        val sessionChanged = currentSessionId.isNotBlank() &&
            incomingSessionId.isNotBlank() &&
            currentSessionId != incomingSessionId
        if (sessionChanged) {
            preservedText = mergeStreamingTranscriptText(preservedText, previousSessionText)
            latestText = ""
            committedText = ""
            previewText = ""
        } else if (isMaterialRegression(previousSessionText, incomingSessionText)) {
            preservedText = mergeStreamingTranscriptText(preservedText, previousSessionText)
        }

        if (incomingSessionId.isNotBlank()) currentSessionId = incomingSessionId
        latestText = incomingText
        committedText = incomingCommitted
        previewText = incomingPreview
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): String = snapshotLocked()

    private fun snapshotLocked(): String = mergeStreamingTranscriptText(
        preservedText,
        sessionText(latestText, committedText, previewText)
    )
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
