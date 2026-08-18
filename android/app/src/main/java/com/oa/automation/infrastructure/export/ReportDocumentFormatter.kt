package com.oa.automation.infrastructure.export

internal object ReportDocumentFormatter {
    private val listItem = Regex("^(\\s*)(?:[-*+]|\\d+[.)、])\\s+(.+)$")
    private val normalizedListItem = Regex("^\\s*（\\d+）.+$")
    private val projectBacklogHeading = Regex(
        "^(\\s*#{1,6}\\s*)(?:(?:8[.、]\\s*)?)(后续研究与储备事项|后续研究及储备事项|待研究与储备事项|后续沉淀事项|沉淀事项|backlog)(\\s*)$",
        RegexOption.IGNORE_CASE
    )

    fun normalizeLists(content: String): String {
        val counters = mutableMapOf<Int, Int>()
        var insideList = false
        return content.lines().joinToString("\n") { line ->
            val match = listItem.matchEntire(line)
            if (match == null) {
                if (line.isBlank() || line.firstOrNull()?.isWhitespace() != true) {
                    counters.clear()
                    insideList = false
                }
                line
            } else {
                val indentation = match.groupValues[1]
                val level = indentation.replace("\t", "    ").length
                if (!insideList) counters.clear()
                counters.keys.filter { it > level }.forEach(counters::remove)
                val number = (counters[level] ?: 0) + 1
                counters[level] = number
                insideList = true
                "$indentation（$number）${match.groupValues[2].trim()}"
            }
        }
    }

    fun normalizeProjectManagementSections(content: String): String =
        content.lines().joinToString("\n") { line ->
            projectBacklogHeading.matchEntire(line)?.let { match ->
                "${match.groupValues[1]}8. 后续研究与储备事项${match.groupValues[3]}"
            } ?: line
        }

    fun isNumberedListItem(line: String): Boolean = normalizedListItem.matches(line)

    fun numbered(text: String, index: Int): String = "（${index + 1}）${text.trim()}"
}
