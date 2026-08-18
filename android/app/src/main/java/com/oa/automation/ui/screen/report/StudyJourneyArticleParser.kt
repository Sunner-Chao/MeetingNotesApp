package com.oa.automation.ui.screen.report

import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.MeetingAttachment

internal enum class StudyJourneyBlockType {
    PARAGRAPH,
    QUOTE,
    SUBHEADING,
    PHOTO
}

internal data class StudyJourneyContentBlock(
    val type: StudyJourneyBlockType,
    val text: String = "",
    val photoNumber: Int? = null,
    val caption: String = ""
)

internal data class StudyJourneySection(
    val sequenceNumber: Int,
    val title: String,
    val subtitle: String = "",
    val blocks: List<StudyJourneyContentBlock> = emptyList()
)

internal data class StudyJourneyArticle(
    val title: String,
    val lead: List<StudyJourneyContentBlock> = emptyList(),
    val routeStops: List<String> = emptyList(),
    val companionLine: String = "",
    val sections: List<StudyJourneySection> = emptyList(),
    val reflection: List<StudyJourneyContentBlock> = emptyList(),
    val tips: List<String> = emptyList(),
    val coverTitles: List<String> = emptyList(),
    val tags: List<String> = emptyList()
) {
    fun searchableText(): String = buildString {
        appendLine(title)
        appendLine(routeStops.joinToString(" "))
        lead.forEach { appendLine(it.text) }
        sections.forEach { section ->
            appendLine(section.title)
            appendLine(section.subtitle)
            section.blocks.forEach { appendLine(it.text) }
        }
        reflection.forEach { appendLine(it.text) }
        tips.forEach(::appendLine)
        tags.forEach(::appendLine)
    }
}

internal data class StudyJourneySectionMedia(
    val section: StudyJourneySection,
    val attachments: List<MeetingAttachment>
)

private data class HeadingGroup(
    val heading: String,
    val lines: List<String>
)

private data class ParsedSectionTitle(
    val title: String,
    val subtitle: String
)

private val studyPhotoAnchor = Regex(
    "^\\[\\s*照片\\s*[:：]\\s*图\\s*(\\d+)(?:\\s*[｜|]\\s*(.*?))?\\s*]$"
)
private val studyHeading = Regex("^##\\s+(.+)$")
private val studySubheading = Regex("^###\\s+(.+)$")
private val studyListItem = Regex("^\\s*(?:[-*+]\\s+|\\d+[.)、]\\s*|（\\d+）\\s*)(.+)$")
private val studyField = Regex("^\\*\\*(路线|同行与讲解)\\*\\*\\s*[:：]\\s*(.+)$")
private val studyTag = Regex("#[^#\\s]+")
private val studyStationPrefix = Regex(
    "^(?:第[一二三四五六七八九十百\\d]+站|DAY\\s*\\d+|Day\\s*\\d+)\\s*[｜|:：.．-]*\\s*"
)

internal fun parseStudyJourneyArticle(
    rawContent: String,
    fallbackTitle: String
): StudyJourneyArticle {
    val visibleLines = rawContent.lineSequence()
        .takeWhile { line -> !isHiddenStudyAppendixHeading(line) }
        .toList()
    val titleLineIndex = visibleLines.indexOfFirst { it.trim().startsWith("# ") }
    val title = visibleLines.getOrNull(titleLineIndex)
        ?.trim()
        ?.removePrefix("# ")
        ?.cleanStudyMarkdown()
        ?.takeIf(String::isNotBlank)
        ?: fallbackTitle.ifBlank { "研学考察游记" }

    val firstHeadingIndex = visibleLines.indexOfFirst { studyHeading.matches(it.trim()) }
        .let { if (it < 0) visibleLines.size else it }
    val preambleStart = if (titleLineIndex >= 0) titleLineIndex + 1 else 0
    val preamble = visibleLines.subList(
        preambleStart.coerceAtMost(firstHeadingIndex),
        firstHeadingIndex
    )
    val routeStops = parseRouteStops(preamble)
    val companionLine = parseField(preamble, "同行与讲解")
    val lead = parseStudyBlocks(
        preamble.filterNot { line -> studyField.matches(line.trim()) }
    )

    val groups = splitStudyHeadingGroups(visibleLines.drop(firstHeadingIndex))
    val sections = mutableListOf<StudyJourneySection>()
    var reflection = emptyList<StudyJourneyContentBlock>()
    val tips = mutableListOf<String>()
    val coverTitles = mutableListOf<String>()
    val tags = mutableListOf<String>()

    groups.forEach { group ->
        when {
            group.heading.isStudyReflectionHeading() -> {
                reflection = parseStudyBlocks(group.lines)
            }
            group.heading.isStudyTipsHeading() -> {
                tips += parseStudyBlocks(group.lines)
                    .filter { it.type != StudyJourneyBlockType.PHOTO }
                    .map(StudyJourneyContentBlock::text)
                    .filter(String::isNotBlank)
            }
            group.heading.contains("封面标题") -> {
                coverTitles += parseStudyBlocks(group.lines)
                    .map(StudyJourneyContentBlock::text)
                    .filter(String::isNotBlank)
            }
            group.heading.contains("话题标签") || group.heading == "标签" -> {
                tags += group.lines.flatMap { line ->
                    studyTag.findAll(line).map { it.value }.toList()
                }
            }
            group.heading.contains("照片集锦") || group.heading.contains("图片集锦") -> Unit
            group.heading.isNotBlank() -> {
                val parsedTitle = parseStudySectionTitle(group.heading)
                sections += StudyJourneySection(
                    sequenceNumber = sections.size + 1,
                    title = parsedTitle.title,
                    subtitle = parsedTitle.subtitle,
                    blocks = parseStudyBlocks(group.lines)
                )
            }
        }
    }

    val fallbackSections = if (sections.isEmpty() && lead.isNotEmpty()) {
        listOf(
            StudyJourneySection(
                sequenceNumber = 1,
                title = title,
                blocks = lead
            )
        )
    } else {
        sections
    }
    val normalizedLead = if (sections.isEmpty()) emptyList() else lead

    return StudyJourneyArticle(
        title = title,
        lead = normalizedLead,
        routeStops = routeStops,
        companionLine = companionLine,
        sections = fallbackSections,
        reflection = reflection,
        tips = tips.distinct(),
        coverTitles = coverTitles.distinct(),
        tags = tags.distinct()
    )
}

internal fun resolveStudyJourneySectionMedia(
    article: StudyJourneyArticle,
    attachments: List<MeetingAttachment>,
    journeyStages: List<JourneyStage> = emptyList()
): List<StudyJourneySectionMedia> {
    if (article.sections.isEmpty()) return emptyList()
    val sortedAttachments = attachments.sortedWith(
        compareBy<MeetingAttachment> { it.markerTimestampMs ?: Long.MAX_VALUE }
            .thenBy { it.createdAt }
            .thenBy { it.id }
    )
    val assigned = article.sections.map { mutableListOf<MeetingAttachment>() }
    val usedIds = mutableSetOf<String>()

    article.sections.forEachIndexed { sectionIndex, section ->
        section.blocks.mapNotNull(StudyJourneyContentBlock::photoNumber)
            .distinct()
            .forEach { photoNumber ->
                sortedAttachments.getOrNull(photoNumber - 1)?.let { attachment ->
                    if (usedIds.add(attachment.id)) assigned[sectionIndex] += attachment
                }
            }
    }

    val orderedStages = journeyStages.sortedBy(JourneyStage::sequenceNumber)
    article.sections.forEachIndexed { sectionIndex, _ ->
        val stage = orderedStages.getOrNull(sectionIndex) ?: return@forEachIndexed
        sortedAttachments.filter { it.journeyStageId == stage.id }.forEach { attachment ->
            if (usedIds.add(attachment.id)) assigned[sectionIndex] += attachment
        }
    }

    sortedAttachments.filterNot { it.id in usedIds }.forEach { attachment ->
        val targetIndex = assigned.indices.minByOrNull { assigned[it].size } ?: 0
        assigned[targetIndex] += attachment
        usedIds += attachment.id
    }

    return article.sections.mapIndexed { index, section ->
        StudyJourneySectionMedia(
            section = section,
            attachments = assigned[index].sortedBy(MeetingAttachment::createdAt)
        )
    }
}

private fun splitStudyHeadingGroups(lines: List<String>): List<HeadingGroup> {
    if (lines.isEmpty()) return emptyList()
    val groups = mutableListOf<HeadingGroup>()
    var currentHeading: String? = null
    var currentLines = mutableListOf<String>()
    fun flush() {
        currentHeading?.let { groups += HeadingGroup(it, currentLines.toList()) }
        currentLines = mutableListOf()
    }
    lines.forEach { line ->
        val heading = studyHeading.matchEntire(line.trim())?.groupValues?.get(1)?.cleanStudyMarkdown()
        if (heading != null) {
            flush()
            currentHeading = heading
        } else if (currentHeading != null) {
            currentLines += line
        }
    }
    flush()
    return groups
}

private fun parseStudyBlocks(lines: List<String>): List<StudyJourneyContentBlock> {
    val blocks = mutableListOf<StudyJourneyContentBlock>()
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        val text = paragraph.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .cleanStudyMarkdown()
        if (text.isNotBlank()) {
            blocks += StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, text)
        }
        paragraph.clear()
    }

    lines.forEach { sourceLine ->
        val line = sourceLine.trim()
        val photo = studyPhotoAnchor.matchEntire(line)
        val subheading = studySubheading.matchEntire(line)
        when {
            line.isBlank() -> flushParagraph()
            photo != null -> {
                flushParagraph()
                blocks += StudyJourneyContentBlock(
                    type = StudyJourneyBlockType.PHOTO,
                    photoNumber = photo.groupValues[1].toIntOrNull(),
                    caption = photo.groupValues.getOrNull(2).orEmpty().cleanStudyMarkdown()
                )
            }
            subheading != null -> {
                flushParagraph()
                blocks += StudyJourneyContentBlock(
                    StudyJourneyBlockType.SUBHEADING,
                    subheading.groupValues[1].cleanStudyMarkdown()
                )
            }
            line.startsWith(">") -> {
                flushParagraph()
                blocks += StudyJourneyContentBlock(
                    StudyJourneyBlockType.QUOTE,
                    line.removePrefix(">").cleanStudyMarkdown()
                )
            }
            line.startsWith("|") -> Unit
            studyField.matches(line) -> Unit
            studyListItem.matches(line) -> {
                flushParagraph()
                val item = studyListItem.matchEntire(line)?.groupValues?.get(1).orEmpty().cleanStudyMarkdown()
                if (item.isNotBlank()) {
                    blocks += StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, item)
                }
            }
            else -> paragraph += line
        }
    }
    flushParagraph()
    return blocks
}

private fun parseRouteStops(lines: List<String>): List<String> {
    val route = parseField(lines, "路线")
    if (route.isBlank()) return emptyList()
    return route.split(Regex("\\s*(?:→|->|➡|—>|＞)\\s*"))
        .map { it.cleanStudyMarkdown() }
        .filter(String::isNotBlank)
        .distinct()
}

private fun parseField(lines: List<String>, fieldName: String): String = lines.firstNotNullOfOrNull { line ->
    val match = studyField.matchEntire(line.trim()) ?: return@firstNotNullOfOrNull null
    match.groupValues[2].cleanStudyMarkdown().takeIf { match.groupValues[1] == fieldName }
}.orEmpty()

private fun parseStudySectionTitle(heading: String): ParsedSectionTitle {
    val normalized = heading.replace(studyStationPrefix, "").trim()
    val parts = normalized.split(Regex("\\s*[·｜|]\\s*"), limit = 2)
    return ParsedSectionTitle(
        title = parts.firstOrNull().orEmpty().ifBlank { heading },
        subtitle = parts.getOrNull(1).orEmpty()
    )
}

private fun isHiddenStudyAppendixHeading(line: String): Boolean {
    val heading = studyHeading.matchEntire(line.trim())?.groupValues?.get(1)?.cleanStudyMarkdown().orEmpty()
    return heading.contains("事实与待确认") ||
        heading.contains("事实核验") ||
        heading.contains("证据附录") ||
        heading == "已确认信息" ||
        heading == "仍待确认"
}

private fun String.isStudyReflectionHeading(): Boolean =
    contains("旅程回望") || contains("旅途回望") || contains("最后想说") || contains("结尾感悟")

private fun String.isStudyTipsHeading(): Boolean =
    contains("实用小贴士") || contains("实用 Tips", ignoreCase = true) || contains("出行提示") || contains("参观提示")

private fun String.cleanStudyMarkdown(): String = trim()
    .removePrefix("#")
    .trim()
    .replace("**", "")
    .replace("__", "")
    .replace(Regex("`([^`]*)`"), "$1")
    .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
    .trim()
