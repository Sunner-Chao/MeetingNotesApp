package com.oa.automation.domain.model

/**
 * A forum attendee entry that can be rendered in the report photo wall.
 *
 * Names may come from diarization, a confirmed report table, or the meeting
 * organizer. A portrait is only rendered when it is explicitly supplied and
 * authorized; otherwise the UI uses a deterministic initial.
 */
data class ForumParticipant(
    val name: String,
    val role: String = "",
    val organization: String = "",
    val avatarDataUrl: String? = null,
    val photoAttachmentId: String? = null,
    val photoAuthorized: Boolean = false
)

fun String.isForumMeetingTemplate(): Boolean =
    contains("论坛会议") || contains("讲座论坛")

/**
 * Extracts conservative roster entries from forum report tables and speaker
 * metadata. This intentionally does not infer a person's identity from a
 * photo or a role-only sentence.
 */
internal fun extractForumParticipants(
    rawContent: String,
    speakerNames: List<String> = emptyList()
): List<ForumParticipant> {
    val entries = linkedMapOf<String, ForumParticipant>()

    fun add(rawName: String, role: String = "", organization: String = "") {
        val name = rawName
            .replace(Regex("^[-*（(【\\[]+|[）)】\\]]+$"), "")
            .replace(Regex("^第[^：:]+[：:]"), "")
            .replace(Regex("[*_`~]"), "")
            .trim()
            .take(80)
        val genericSpeaker = name.matches(Regex("(?i)(speaker|spk|speaker_|spk_)[-_ ]?\\d+")) ||
            name.matches(Regex("(说话人|发言人|未知人员)[-_ ]?\\d*"))
        if (
            name.isBlank() || genericSpeaker ||
            name in setOf("未提及", "待确认", "现场听众", "线上线下行业听众")
        ) return
        val key = name.lowercase()
        val existing = entries[key]
        entries[key] = existing?.copy(
            role = existing.role.ifBlank { role },
            organization = existing.organization.ifBlank { organization }
        ) ?: ForumParticipant(name = name, role = role, organization = organization)
    }

    fun addValue(value: String, role: String) {
        value.split(Regex("[；;，,、\\n]"))
            .map { it.trim() }
            .filter(String::isNotBlank)
            .forEach { segment ->
                add(segment.substringAfterLast(":", segment).substringAfterLast("：", segment), role)
            }
    }

    fun tableCells(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    fun isSeparator(line: String): Boolean =
        tableCells(line).all { it.matches(Regex(":?-{3,}:?")) }

    val lines = rawContent.lines()
    var lineIndex = 0
    while (lineIndex + 1 < lines.size) {
        val headerLine = lines[lineIndex].trim()
        if (!headerLine.startsWith("|") || !isSeparator(lines[lineIndex + 1])) {
            lineIndex++
            continue
        }
        val header = tableCells(headerLine)
        val rows = mutableListOf<List<String>>()
        lineIndex += 2
        while (lineIndex < lines.size && lines[lineIndex].trim().startsWith("|")) {
            rows += tableCells(lines[lineIndex])
            lineIndex++
        }
        val nameColumn = header.indexOfFirst {
            it.contains("姓名") || it.contains("称谓") || it == "人员"
        }
        if (nameColumn >= 0) {
            val roleColumn = header.indexOfFirst { it.contains("角色") || it.contains("身份") }
            val organizationColumn = header.indexOfFirst { it.contains("单位") || it.contains("机构") }
            rows.forEach { row ->
                add(
                    rawName = row.getOrNull(nameColumn).orEmpty(),
                    role = row.getOrNull(roleColumn).orEmpty(),
                    organization = row.getOrNull(organizationColumn).orEmpty()
                )
            }
        } else {
            rows.forEach { cells ->
                val label = cells.firstOrNull().orEmpty()
                val role = when {
                    label.contains("主持") -> "主持人"
                    label.contains("嘉宾") || label.contains("主讲") -> "嘉宾"
                    label.contains("发言") -> "发言人"
                    else -> return@forEach
                }
                addValue(cells.drop(1).joinToString(" "), role)
            }
        }
    }

    lines.forEach { line ->
        val match = Regex("(?:主持人|嘉宾|主讲人|发言人)[：:](.+)").find(line.trim())
        if (match != null) {
            val role = when {
                match.value.startsWith("主持") -> "主持人"
                match.value.startsWith("嘉宾") || match.value.startsWith("主讲") -> "嘉宾"
                else -> "发言人"
            }
            addValue(match.groupValues[1], role)
        }
    }

    speakerNames
        .map { it.trim() }
        .filter(String::isNotBlank)
        .forEach { add(it, "发言人") }

    return entries.values.toList()
}
