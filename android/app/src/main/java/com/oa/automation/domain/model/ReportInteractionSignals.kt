package com.oa.automation.domain.model

/**
 * An interaction signal explicitly recorded in a report.
 *
 * The model intentionally stores the observed wording instead of a guessed
 * emotion or intent. This keeps the light edition auditable and allows the
 * original report text to remain the source of truth.
 */
data class InteractionSignal(
    val content: String,
    val detail: String? = null,
    val source: String? = null
)

/**
 * Extract only a dedicated interaction-signal section from Markdown.
 * Free-form mentions such as "会议气氛紧张" are ignored deliberately.
 */
fun extractInteractionSignals(rawContent: String): List<InteractionSignal> {
    if (rawContent.isBlank()) return emptyList()
    val heading = Regex(
        "^\\s*#{1,6}\\s*(?:\\d+[.、]\\s*)?(?:可观察互动信号|互动信号|互动观察)\\s*$"
    )
    val items = mutableListOf<InteractionSignal>()
    var inSection = false
    rawContent.lineSequence().forEach { line ->
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
        if (cells.isNotEmpty() && cells.any {
                it.contains("信号") || it.contains("观察") || it.contains("证据") || it.contains("来源")
            }) {
            return@forEach
        }
        val normalized = trimmed
            .replace(Regex("^[-*+•]\\s*"), "")
            .replace(Regex("^\\d+[.、]\\s*"), "")
            .trim()
        if (normalized.isBlank()) return@forEach
        if (trimmed.startsWith("|") && cells.size >= 2) {
            items += InteractionSignal(
                content = cells.first(),
                detail = cells.getOrNull(1),
                source = cells.getOrNull(2)
            )
        } else {
            items += InteractionSignal(content = normalized)
        }
    }
    return items
        .filter { it.content.isNotBlank() }
        .distinctBy { listOf(it.content, it.detail, it.source) }
}
