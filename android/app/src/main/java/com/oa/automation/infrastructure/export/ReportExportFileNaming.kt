package com.oa.automation.infrastructure.export

import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.ReportTitleResolver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ReportExportFileNaming {
    private const val MAX_FILE_NAME_BYTES = 240
    private const val MAX_TYPE_BYTES = 72
    private val invalidFileNameCharacters = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    private val repeatedWhitespace = Regex("\\s+")
    private val repeatedSeparators = Regex("-{2,}")

    fun build(
        report: Report,
        meetingTitle: String,
        extension: String,
        timestamp: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val safeType = sanitizeComponent(report.templateName, "会议").truncateUtf8(MAX_TYPE_BYTES)
        val safeExtension = extension.trim().removePrefix(".")
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .ifBlank { "dat" }
        val localTime = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply {
            this.timeZone = timeZone
        }.format(Date(timestamp))
        val suffix = "-$localTime.$safeExtension"
        val titleBudget = (
            MAX_FILE_NAME_BYTES - "$safeType-$suffix".toByteArray(Charsets.UTF_8).size
            ).coerceAtLeast(24)
        val safeTitle = sanitizeComponent(ReportTitleResolver.resolve(report, meetingTitle), "会议纪要")
            .truncateUtf8(titleBudget)

        return "$safeType-$safeTitle$suffix"
    }

    private fun sanitizeComponent(value: String, fallback: String): String = value
        .replace(invalidFileNameCharacters, "-")
        .replace(repeatedWhitespace, "-")
        .replace(repeatedSeparators, "-")
        .trim(' ', '-', '.')
        .ifBlank { fallback }

    private fun String.truncateUtf8(maxBytes: Int): String {
        if (toByteArray(Charsets.UTF_8).size <= maxBytes) return this

        val result = StringBuilder()
        var index = 0
        var byteCount = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            val next = String(Character.toChars(codePoint))
            val nextByteCount = next.toByteArray(Charsets.UTF_8).size
            if (byteCount + nextByteCount > maxBytes) break
            result.append(next)
            byteCount += nextByteCount
            index += Character.charCount(codePoint)
        }
        return result.toString().trimEnd(' ', '-', '.')
    }
}
