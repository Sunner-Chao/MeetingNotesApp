package com.oa.automation.domain.model

/** A risk or blocker derived from a generated report's explicit risk section. */
data class RiskItem(
    val content: String,
    val detail: String? = null,
    val status: String? = null
)

/**
 * Parse only explicit Markdown risk sections. This is deliberately conservative:
 * free-form prose outside a heading is not promoted to a risk fact.
 */
fun extractRiskItems(rawContent: String): List<RiskItem> {
    if (rawContent.isBlank()) return emptyList()
    val lines = rawContent.lines()
    val heading = Regex("^\\s*#{1,6}\\s*(?:\\d+[.、]\\s*)?(风险与阻塞|风险提醒|风险清单|阻塞项)\\s*$")
    val items = mutableListOf<RiskItem>()
    var inSection = false
    lines.forEach { line ->
        val trimmed = line.trim()
        if (heading.matches(trimmed)) {
            inSection = true
            return@forEach
        }
        if (!inSection) return@forEach
        if (trimmed.startsWith("#")) {
            inSection = false
            return@forEach
        }
        if (trimmed.isBlank() || trimmed.matches(Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$"))) {
            return@forEach
        }
        val cells = trimmed.trim('|').split('|').map { it.trim() }.filter(String::isNotBlank)
        if (cells.isNotEmpty() && cells.any { it.contains("风险内容") || it == "风险" || it == "说明" }) {
            return@forEach
        }
        val normalized = trimmed
            .replace(Regex("^[-*+•]\\s*"), "")
            .replace(Regex("^\\d+[.、]\\s*"), "")
            .trim()
        if (normalized.isBlank()) return@forEach
        if (trimmed.startsWith("|") && cells.size >= 2) {
            items += RiskItem(
                content = cells.getOrNull(1) ?: cells.first(),
                detail = cells.getOrNull(2),
                status = cells.getOrNull(3)
            )
        } else {
            items += RiskItem(content = normalized)
        }
    }
    return items.distinctBy { listOf(it.content, it.detail, it.status) }
}
