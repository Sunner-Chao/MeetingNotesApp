package com.oa.automation.domain.model

object ReportTitleResolver {
    private val placeholderMeetingTitle = Regex(
        pattern = "^(快速录音|快速会议|即刻洞见|即刻倾听|新建会议|会议记录|资料导入|文件导入|顷刻解析|顷刻成稿)(\\s|[-_：:]|\\d|$).*$"
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
            .ifBlank { report.rawContent.topicTableValue() }
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

        val usableMarkdownTitle = markdownTitle.takeUnless {
            it == report.templateName.cleanTitle() ||
                it == MeetingMode.fromTemplateName(report.templateName).displayName ||
                it.isGenericHeading()
        }.orEmpty()

        return usableMarkdownTitle
            .ifBlank { topic }
            .ifBlank { summaryTitle }
            .ifBlank { fallback }
            .ifBlank { templateFallback }
            .ifBlank { "会议纪要" }
    }

    private fun String.topicSectionTitle(): String {
        val lines = lineSequence().map(String::trim).toList()
        val headingIndex = lines.indexOfFirst { line ->
            line.trimStart('#').trim().replaceFirst(Regex("^\\d+[.、]\\s*"), "").cleanTitle() in
                setOf("会议主题", "主题", "论坛主题", "洽谈主题")
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

    private fun String.topicTableValue(): String {
        val labels = setOf("会议主题", "论坛主题", "洽谈主题", "事件名称", "项目名称", "主题")
        return lineSequence()
            .map { it.trim().trim('|') }
            .filter { it.contains('|') }
            .map { it.split('|').map(String::trim) }
            .firstOrNull { cells -> cells.size >= 2 && cells[0].cleanTitle() in labels &&
                cells[1].cleanTitle() !in setOf("", "未提及", "待确认") }
            ?.getOrNull(1)
            ?.cleanTitle()
            ?.take(72)
            .orEmpty()
    }

    private fun String.cleanTitle(): String = trim()
        .trimStart('-', '*', '>', '#')
        .trim()
        .trim('*', '_', '`', '：', ':', '。')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.isGenericHeading(): Boolean =
        trim() in setOf("会议纪要", "会议主题", "主题", "会议报告", "纪要报告") ||
            MeetingMode.entries.any {
                trim() in setOf(it.templateName + "纪要", it.displayName + "纪要",
                    it.templateName + "会议纪要", it.displayName + "会议纪要")
            }
}
