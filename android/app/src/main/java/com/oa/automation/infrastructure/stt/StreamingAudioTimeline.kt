package com.oa.automation.infrastructure.stt

/** Paragraphs anchored to decoded audio windows, not invented word-level timestamps. */
internal class StreamingAudioTimeline {
    private val completed = mutableListOf<StreamingTranscriptSegment>()
    private var prefix = ""
    private var startSeconds = 0f
    private var endSeconds = 0f
    private var currentText = ""

    fun update(text: String, audioEndSeconds: Float): List<StreamingTranscriptSegment> {
        if (!audioEndSeconds.isFinite() || audioEndSeconds <= 0f || text.isBlank()) return snapshot()
        // A recognizer may revise its earlier hypothesis. Rebuild that window
        // instead of retaining contradictory or duplicated paragraphs.
        if (!text.startsWith(prefix)) {
            completed.clear()
            prefix = ""
            startSeconds = 0f
        }
        val previousText = prefix + currentText
        if (currentText.isNotBlank() && text.startsWith(previousText) && text.length > previousText.length &&
            (endSeconds - startSeconds >= 10f || currentText.length >= 120 ||
                currentText.last() in "。！？!?\n" || audioEndSeconds - endSeconds >= 2f)
        ) {
            completed += StreamingTranscriptSegment(startSeconds, endSeconds, currentText, committed = true)
            prefix = previousText
            startSeconds = endSeconds
        }
        currentText = text.removePrefix(prefix)
        endSeconds = maxOf(startSeconds, audioEndSeconds)
        return snapshot()
    }

    private fun snapshot(): List<StreamingTranscriptSegment> = completed +
        if (currentText.isBlank()) emptyList() else listOf(
            StreamingTranscriptSegment(startSeconds, endSeconds, currentText)
        )
}
