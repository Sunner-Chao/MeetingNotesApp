package com.oa.automation.domain.model

/**
 * Complete meeting record containing all related data
 */
data class MeetingRecord(
    val meeting: Meeting,
    val transcript: Transcript? = null,
    val report: Report? = null,
    val participants: List<String> = emptyList()
)

/**
 * Export format options for meeting reports
 */
enum class ExportFormat(val extension: String, val mimeType: String) {
    MARKDOWN("md", "text/markdown"),
    TXT("txt", "text/plain"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF("pdf", "application/pdf")
}
