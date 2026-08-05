package com.oa.automation.domain.model

object ReportTitleResolver {
    private val placeholderMeetingTitle = Regex(
        pattern = "^(快速录音|快速会议|新建会议|会议记录)(\\s|[-_]|\\d|$).*$"
    )

    fun resolve(report: Report, fallbackMeetingTitle: String = ""): String {
        val markdownTitle = report.rawContent.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            .orEmpty()
            .cleanTitle()
            .takeUnless { it.isGenericHeading() }
            .orEmpty()
        val topic = report.rawContent.topicSectionTitle()
        val summaryTitle = report.summary.lineSequence()
            .firstOrNull(String::isNotBlank)
            .orEmpty()
            .cleanTitle()
            .substringBefore('。')
            .substringBefore('！')
            .substringBefore('？')
            .take(48)
            .trim()
            .takeUnless { it.isGenericHeading() }
            .orEmpty()
        val fallback = fallbackMeetingTitle.cleanTitle()
            .takeUnless { it.isBlank() || placeholderMeetingTitle.matches(it) }
            .orEmpty()
        val templateFallback = report.templateName.cleanTitle()
            .takeUnless { it.isBlank() || it.isGenericHeading() }
            ?.let {
                when {
                    it.endsWith("会议纪要") || it.endsWith("纪要") -> it
                    it.endsWith("会议") -> "${it}纪要"
                    else -> "${it}会议纪要"
                }
            }
            .orEmpty()

        return markdownTitle
            .ifBlank { topic }
            .ifBlank { summaryTitle }
            .ifBlank { fallback }
            .ifBlank { templateFallback }
            .ifBlank { "会议纪要" }
    }

    private fun String.topicSectionTitle(): String {
        val lines = lineSequence().map(String::trim).toList()
        val headingIndex = lines.indexOfFirst { line ->
            line.trimStart('#').trim().cleanTitle() in setOf("会议主题", "主题")
        }
        if (headingIndex < 0) return ""
        return lines.asSequence()
            .drop(headingIndex + 1)
            .takeWhile { !it.startsWith("#") }
            .map { it.cleanTitle() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("|") && !it.isGenericHeading() }
            .orEmpty()
            .take(48)
            .trim()
    }

    private fun String.cleanTitle(): String = trim()
        .trimStart('-', '*', '>', '#')
        .trim()
        .trim('*', '_', '`', '：', ':', '。')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.isGenericHeading(): Boolean = when (trim()) {
        "会议纪要", "会议主题", "主题", "会议报告", "纪要报告" -> true
        else -> false
    }
}
