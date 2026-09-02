package com.oa.automation.infrastructure.export

import android.content.Context
import com.oa.automation.domain.model.ForumParticipant
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.MeetingMode
import com.oa.automation.domain.model.isForumMeetingTemplate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToLong

private fun String.usesProjectManagementDocxTemplate(): Boolean =
    MeetingMode.fromTemplateName(this) == MeetingMode.PROGRESS ||
        contains("孔爵") && contains("表格")

private fun String.usesStudyReportStyle(): Boolean =
    contains("研学") || contains("参观考察") || contains("游记") || contains("文旅")

private val photoAnchorPattern = Regex(
    "^\\s*\\[照片\\s*[:：]\\s*图\\s*(\\d+)(?:\\s*[|｜]\\s*([^]]+?))?]\\s*$"
)

object DocxReportExporter {
    private const val KONGJUE_TEMPLATE_ASSET = "kongjue-team-table-v1.docx"

    fun export(
        context: Context,
        report: Report,
        attachments: List<MeetingAttachment>,
        meetingTitle: String = "",
        forumParticipants: List<ForumParticipant> = emptyList()
    ): File {
        val images = attachments.map { attachment ->
            val image = MeetingImagePreparer.prepare(attachment)
                ?: error("无法写入会议图片：${attachment.displayName}")
            DocxImage(
                bytes = image.bytes,
                widthPx = image.widthPx,
                heightPx = image.heightPx,
                caption = image.caption
            )
        }
        val templatePackage = report.templateName
            .takeIf { it.usesProjectManagementDocxTemplate() }
            ?.let {
                runCatching {
                    context.assets.open(KONGJUE_TEMPLATE_ASSET).use { input -> input.readBytes() }
                }.getOrNull()
            }
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val output = File(
            exportDir,
            ReportExportFileNaming.build(report, meetingTitle, "docx")
        )
        DocxPackageWriter(
            report,
            images,
            templatePackage,
            forumParticipants.ifEmpty { report.participants }
        ).write(output)
        return output
    }
}

internal data class DocxImage(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val caption: String
)

private sealed interface DocxBlock
private data class ParagraphBlock(val text: String, val style: String = "Normal") : DocxBlock
private data class TableBlock(val rows: List<List<String>>) : DocxBlock
private data class ImageBlock(
    val index: Int,
    val image: DocxImage,
    val caption: String = image.caption
) : DocxBlock
private object ForumParticipantWallBlock : DocxBlock
private object PageBreakBlock : DocxBlock
private data class ForumAvatarImage(
    val participant: ForumParticipant,
    val image: DocxImage
)
private data class ParsedMarkdownSection(
    val heading: String,
    val level: Int,
    val paragraphs: MutableList<String> = mutableListOf(),
    val tables: MutableList<List<List<String>>> = mutableListOf()
)
private data class ParsedMarkdownDocument(
    val title: String?,
    val sections: List<ParsedMarkdownSection>
)

internal class DocxPackageWriter(
    private val report: Report,
    private val images: List<DocxImage>,
    private val templatePackage: ByteArray? = null,
    private val forumParticipants: List<ForumParticipant> = emptyList()
) {
    private val forumAvatarImages: List<ForumAvatarImage> by lazy {
        forumParticipants.mapNotNull { participant ->
            if (!participant.photoAuthorized) return@mapNotNull null
            val encoded = participant.avatarDataUrl
                ?.substringAfter("base64,", "")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            runCatching {
                val bytes = Base64.getMimeDecoder().decode(encoded)
                if (!bytes.isJpegImage()) return@runCatching null
                ForumAvatarImage(
                    participant = participant,
                    image = DocxImage(
                        bytes = bytes,
                        widthPx = 256,
                        heightPx = 256,
                        caption = "${participant.name}的授权头像"
                    )
                )
            }.getOrNull()
        }
    }
    private val forumAvatarIndexes: Map<ForumParticipant, Int> by lazy {
        forumAvatarImages.mapIndexed { offset, avatar ->
            avatar.participant to images.size + offset
        }.toMap()
    }
    private val allImages: List<DocxImage> by lazy {
        images + forumAvatarImages.map { it.image }
    }
    private val generatedAt = Instant.ofEpochMilli(report.generatedAt).toString()
    private val templateEntries: Map<String, ByteArray> by lazy {
        templatePackage?.let(::readZipEntries).orEmpty()
    }

    fun write(output: File) {
        output.parentFile?.mkdirs()
        if (templateEntries.isNotEmpty()) {
            writeFromTemplate(output)
        } else {
            writeStandalone(output)
        }
    }

    private fun writeStandalone(output: File) {
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.addText("[Content_Types].xml", contentTypes())
            zip.addText("_rels/.rels", rootRelationships())
            zip.addText("docProps/core.xml", coreProperties())
            zip.addText("docProps/app.xml", appProperties())
            zip.addText("word/document.xml", documentXml())
            zip.addText("word/styles.xml", stylesXml())
            zip.addText("word/settings.xml", settingsXml())
            zip.addText("word/footer1.xml", footerXml())
            zip.addText("word/_rels/document.xml.rels", documentRelationships())
            allImages.forEachIndexed { index, image ->
                zip.addBytes("word/media/image${index + 1}.jpg", image.bytes)
            }
        }
    }

    private fun writeFromTemplate(output: File) {
        val replacedEntries = setOf(
            "[Content_Types].xml",
            "docProps/core.xml",
            "word/document.xml",
            "word/_rels/document.xml.rels"
        )
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            templateEntries.forEach { (path, bytes) ->
                if (path !in replacedEntries && !path.endsWith("/")) {
                    zip.addBytes(path, bytes)
                }
            }
            zip.addText("[Content_Types].xml", templateContentTypes())
            zip.addText("docProps/core.xml", coreProperties())
            zip.addText("word/document.xml", documentXml())
            zip.addText("word/_rels/document.xml.rels", templateDocumentRelationships())
            allImages.forEachIndexed { index, image ->
                zip.addBytes("word/media/image${index + 1}.jpg", image.bytes)
            }
        }
    }

    private fun documentXml(): String {
        val reportBlocks = parseReport()
        val anchoredImageIndexes = reportBlocks
            .filterIsInstance<ImageBlock>()
            .mapTo(mutableSetOf()) { it.index }
        val remainingImageIndexes = images.indices.filterNot(anchoredImageIndexes::contains)
        val imageSectionTitle = when {
            report.templateName.usesStudyReportStyle() -> "照片集锦"
            report.templateName.usesProjectManagementDocxTemplate() -> "会议影像资料与签到表"
            else -> "会议影像资料"
        }
        val reportAndParticipantBlocks = if (
            report.templateName.isForumMeetingTemplate() && forumParticipants.isNotEmpty()
        ) {
            val titleIndex = reportBlocks.indexOfFirst { block ->
                block is ParagraphBlock && block.style in setOf("Title", "StudyTitle")
            }
            buildList {
                if (titleIndex >= 0) {
                    addAll(reportBlocks.take(titleIndex + 1))
                    add(ForumParticipantWallBlock)
                    addAll(reportBlocks.drop(titleIndex + 1))
                } else {
                    add(ForumParticipantWallBlock)
                    addAll(reportBlocks)
                }
            }
        } else {
            reportBlocks
        }
        val blocks = buildList {
            addAll(reportAndParticipantBlocks)
            if (remainingImageIndexes.isNotEmpty()) add(PageBreakBlock)
            remainingImageIndexes.forEachIndexed { position, index ->
                val image = images[index]
                add(if (position == 0) ParagraphBlock(imageSectionTitle, "Heading1") else ParagraphBlock("", "Spacer"))
                add(ImageBlock(index, image))
                add(ParagraphBlock(imageCaption(index, image.caption), captionStyle()))
            }
        }

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"")
            append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"")
            append(" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"")
            append(" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"")
            append(" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
            append("<w:body>")
            blocks.forEach { block ->
                when (block) {
                    is ParagraphBlock -> append(paragraphXml(block))
                    is TableBlock -> append(tableXml(block.rows))
                    is ImageBlock -> append(imageXml(block.index, block.image, block.caption))
                    ForumParticipantWallBlock -> append(forumParticipantWallXml())
                    PageBreakBlock -> append(pageBreakXml())
                }
            }
            append(sectionPropertiesXml())
            append("</w:body></w:document>")
        }
    }

    private fun forumParticipantWallXml(): String {
        val visible = forumParticipants.filter { it.name.isNotBlank() }.take(24)
        if (visible.isEmpty()) return ""
        return buildString {
            append(paragraphXml(ParagraphBlock("论坛参会名录", "Heading1")))
            append(paragraphXml(ParagraphBlock("照片墙名单 · ${visible.size} 人 · 未采集头像以姓名首字显示", "Caption")))
            append("<w:tbl><w:tblPr><w:tblW w:w=\"9734\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/><w:tblBorders>")
            listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { edge ->
                append("<w:").append(edge).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D6E1F2\"/>")
            }
            append("</w:tblBorders></w:tblPr><w:tblGrid>")
            repeat(4) { append("<w:gridCol w:w=\"2433\"/>") }
            append("</w:tblGrid>")
            visible.chunked(4).forEach { row ->
                append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
                row.forEach { participant ->
                    append("<w:tc><w:tcPr><w:tcW w:w=\"2433\" w:type=\"dxa\"/><w:shd w:val=\"clear\" w:fill=\"F7F9FC\"/></w:tcPr>")
                    val avatarIndex = forumAvatarIndexes[participant]
                    avatarIndex?.let { index ->
                        allImages.getOrNull(index)?.let { image ->
                            append(imageXml(index, image, "${participant.name}的授权头像", 700_000L, 700_000L))
                        }
                    } ?: append(forumInitialBadgeXml(participant.name))
                    val meta = listOf(participant.name, participant.role, participant.organization)
                        .filter(String::isNotBlank)
                        .joinToString(" · ")
                    append(paragraphXml(ParagraphBlock(meta, "Caption")))
                    append("</w:tc>")
                }
                repeat(4 - row.size) {
                    append("<w:tc><w:tcPr><w:tcW w:w=\"2433\" w:type=\"dxa\"/></w:tcPr><w:p/></w:tc>")
                }
                append("</w:tr>")
            }
            append("</w:tbl><w:p/>")
        }
    }

    private fun forumInitialBadgeXml(name: String): String = buildString {
        append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:before=\"40\" w:after=\"70\"/></w:pPr>")
        append("<w:r><w:rPr><w:b/><w:color w:val=\"FFFFFF\"/><w:shd w:val=\"clear\" w:fill=\"5B8DEF\"/>")
        append("<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/></w:rPr><w:t>")
        append(name.take(1).escapeXml()).append("</w:t></w:r></w:p>")
    }

    private fun parseReport(): List<DocxBlock> {
        return if (templateEntries.isNotEmpty() || report.templateName.usesProjectManagementDocxTemplate()) {
            teamTableBlocks()
        } else {
            markdownBlocks()
        }
    }

    private fun markdownBlocks(): List<DocxBlock> {
        if (report.rawContent.isBlank()) return structuredBlocks()

        val isStudyReport = report.templateName.usesStudyReportStyle()
        val lines = ReportDocumentFormatter.normalizeLists(report.rawContent).lines()
        val blocks = mutableListOf<DocxBlock>()
        var index = 0
        var hasTitle = false
        while (index < lines.size) {
            val line = lines[index].trim()
            if (
                report.templateName.isForumMeetingTemplate() && forumParticipants.isNotEmpty() &&
                line.isForumRosterHeading()
            ) {
                index++
                while (index < lines.size && !lines[index].trim().isMarkdownHeading()) index++
                continue
            }
            when {
                line.isBlank() || line == "---" -> index++
                photoAnchorPattern.matches(line) -> {
                    val imageIndex = photoAnchorPattern.matchEntire(line)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?.minus(1)
                    val anchorCaption = photoAnchorPattern.matchEntire(line)
                        ?.groupValues
                        ?.getOrNull(2)
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                    val image = imageIndex?.let { images.getOrNull(it) }
                    if (imageIndex != null && image != null && blocks.none { it is ImageBlock && it.index == imageIndex }) {
                        val caption = anchorCaption ?: image.caption
                        blocks += ImageBlock(imageIndex, image, caption)
                        blocks += ParagraphBlock(imageCaption(imageIndex, caption), captionStyle())
                    }
                    index++
                }
                line.startsWith("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1]) -> {
                    val rows = mutableListOf<List<String>>()
                    rows += parseTableRow(line)
                    index += 2
                    while (index < lines.size && lines[index].trim().startsWith("|")) {
                        rows += parseTableRow(lines[index])
                        index++
                    }
                    if (rows.isNotEmpty()) blocks += TableBlock(rows)
                }
                line.startsWith("### ") -> {
                    blocks += ParagraphBlock(
                        cleanMarkdown(line.removePrefix("### ")),
                        if (isStudyReport) "StudyHeading2" else "Heading2"
                    )
                    index++
                }
                line.matches(Regex("^#{4,6}\\s+.+$")) -> {
                    blocks += ParagraphBlock(
                        cleanMarkdown(line),
                        if (isStudyReport) "StudyHeading2" else "Heading2"
                    )
                    index++
                }
                line.startsWith("## ") -> {
                    blocks += ParagraphBlock(
                        cleanMarkdown(line.removePrefix("## ")),
                        if (isStudyReport) "StudyHeading1" else "Heading1"
                    )
                    index++
                }
                line.startsWith("# ") -> {
                    blocks += ParagraphBlock(
                        cleanMarkdown(line.removePrefix("# ")),
                        when {
                            !hasTitle && isStudyReport -> "StudyTitle"
                            !hasTitle -> "Title"
                            isStudyReport -> "StudyHeading1"
                            else -> "Heading1"
                        }
                    )
                    hasTitle = true
                    index++
                }
                isStudyReport && line.startsWith(">") -> {
                    blocks += ParagraphBlock(cleanMarkdown(line), "StudyLead")
                    index++
                }
                isStudyReport && (
                    line.startsWith("**路线**") ||
                        line.startsWith("**同行与讲解**")
                    ) -> {
                    blocks += ParagraphBlock(cleanMarkdown(line), "StudyMetadata")
                    index++
                }
                ReportDocumentFormatter.isNumberedListItem(line) -> {
                    blocks += ParagraphBlock(cleanMarkdown(line), "ListNumber")
                    index++
                }
                else -> {
                    val paragraph = mutableListOf(line)
                    index++
                    while (index < lines.size) {
                        val next = lines[index].trim()
                        if (
                            next.isBlank() || next.startsWith("#") || next.startsWith("|") ||
                            photoAnchorPattern.matches(next) ||
                            ReportDocumentFormatter.isNumberedListItem(next)
                        ) break
                        paragraph += next
                        index++
                    }
                    blocks += ParagraphBlock(cleanMarkdown(paragraph.joinToString(" ")))
                }
            }
        }

        if (!hasTitle) {
            blocks.add(
                0,
                ParagraphBlock(
                    report.templateName.ifBlank { "会议纪要" },
                    if (isStudyReport) "StudyTitle" else "Title"
                )
            )
        }
        blocks.add(
            1,
            ParagraphBlock(
                "生成时间：${generatedAt.substring(0, 10)}",
                if (isStudyReport) "StudyMetadata" else "Metadata"
            )
        )
        return blocks
    }

    private fun captionStyle(): String =
        if (report.templateName.usesStudyReportStyle()) "StudyCaption" else "Caption"

    private fun imageCaption(index: Int, caption: String): String =
        if (report.templateName.usesStudyReportStyle()) {
            "图 ${index + 1}｜${caption.ifBlank { "现场照片" }}"
        } else {
            "图 ${index + 1}  ${caption.ifBlank { "会议图片" }}"
        }

    private fun structuredBlocks(): List<DocxBlock> = buildList {
        add(ParagraphBlock(report.templateName.ifBlank { "会议纪要" }, "Title"))
        add(ParagraphBlock("生成时间：${generatedAt.substring(0, 10)}", "Metadata"))
        add(ParagraphBlock("1. 会议概述", "Heading1"))
        add(ParagraphBlock(report.summary.ifBlank { "暂无概述" }))
        if (report.keyPoints.isNotEmpty()) {
            add(ParagraphBlock("2. 关键要点", "Heading1"))
            report.keyPoints.forEachIndexed { index, value ->
                add(ParagraphBlock(ReportDocumentFormatter.numbered(value, index), "ListNumber"))
            }
        }
        if (report.decisions.isNotEmpty()) {
            add(ParagraphBlock("3. 决策事项", "Heading1"))
            report.decisions.forEachIndexed { index, value ->
                add(ParagraphBlock(ReportDocumentFormatter.numbered(value, index), "ListNumber"))
            }
        }
        if (report.tasks.isNotEmpty()) {
            add(ParagraphBlock("4. 行动项跟踪表", "Heading1"))
            add(TableBlock(buildList {
                add(listOf("事项", "负责人", "截止时间", "状态"))
                report.tasks.forEach { task ->
                    add(listOf(task.content, task.assignee.orEmpty(), task.due.orEmpty(), if (task.completed) "已完成" else "待执行"))
                }
            }))
        }
        if (report.actionItems.isNotEmpty()) {
            add(ParagraphBlock("5. 后续行动", "Heading1"))
            report.actionItems.forEachIndexed { index, value ->
                add(ParagraphBlock(ReportDocumentFormatter.numbered(value, index), "ListNumber"))
            }
        }
    }

    private fun teamTableBlocks(): List<DocxBlock> {
        val markdown = parseMarkdownDocument(report.rawContent)
        val title = markdown.title
            ?.takeIf { it.isNotBlank() }
            ?: report.templateName.ifBlank { "会议纪要" }
        val meetingTopic = sectionParagraphs(markdown, "会议主题")
            .ifEmpty { listOf(title) }
        val coreSummary = buildList {
            report.summary.takeIf { it.isNotBlank() }?.let(::add)
            addAll(sectionParagraphs(markdown, "核心结论"))
            if (isEmpty()) addAll(report.keyPoints)
            if (isEmpty()) addAll(sectionParagraphs(markdown, "正文"))
        }.ifEmpty { listOf("未提及") }
        val discussionSections = markdown.sections.filter { section ->
            section.heading.contains("重点讨论") ||
                section.heading.matches(Regex("^3(?:[.、]|\\s).*"))
        }

        val consensusHeaders = listOf("编号", "共识事项", "说明")
        val unresolvedHeaders = listOf("编号", "未解决问题", "当前状态", "备注")
        val actionHeaders = listOf(
            "ActionID", "事项", "负责人", "当前状态", "截止时间", "是否待确认", "来源背景", "备注"
        )
        val backlogHeaders = listOf(
            "事项编号", "事项名称", "类别", "具体说明", "来源", "保留原因",
            "建议优先级", "当前状态", "是否立项", "备注"
        )
        val riskHeaders = listOf("风险编号", "风险内容", "说明")

        val consensusFallback = report.decisions.mapIndexed { index, value ->
            listOf(teamIdentifier("C", index), value, "来自会议决策")
        }
        val actionFallback = buildList {
            report.tasks.forEachIndexed { index, task ->
                add(
                    listOf(
                        teamIdentifier("ACT", index),
                        task.content,
                        task.assignee.orEmpty(),
                        if (task.completed) "已完成" else "待执行",
                        task.due.orEmpty(),
                        if (task.assignee.isNullOrBlank() || task.due.isNullOrBlank()) "是" else "否",
                        "会议记录",
                        ""
                    )
                )
            }
            if (isEmpty()) {
                report.actionItems.forEachIndexed { index, value ->
                    add(
                        listOf(
                            teamIdentifier("ACT", index), value, "待确认", "待执行",
                            "待确认", "是", "会议记录", ""
                        )
                    )
                }
            }
        }

        return buildList {
            add(ParagraphBlock(title, "Title"))
            add(ParagraphBlock("生成时间：${generatedAt.substring(0, 10)}", "Metadata"))

            add(ParagraphBlock("1. 会议主题", "Heading1"))
            meetingTopic.forEach { add(ParagraphBlock(it)) }

            add(ParagraphBlock("2. 核心结论摘要", "Heading1"))
            coreSummary.forEachIndexed { index, value ->
                add(ParagraphBlock(ReportDocumentFormatter.numbered(value, index), "ListNumber"))
            }

            add(ParagraphBlock("3. 重点讨论议题", "Heading1"))
            if (discussionSections.isEmpty()) {
                report.keyPoints.ifEmpty { listOf("未提及") }
                    .forEachIndexed { index, value ->
                        add(ParagraphBlock(ReportDocumentFormatter.numbered(value, index), "ListNumber"))
                    }
            } else {
                discussionSections.forEach { section ->
                    if (!section.heading.contains("重点讨论")) {
                        add(ParagraphBlock(section.heading, "Heading2"))
                    }
                    section.paragraphs.forEach { add(ParagraphBlock(it)) }
                }
            }

            add(ParagraphBlock("4. 已达成共识", "Heading1"))
            add(
                TableBlock(
                    teamTableRows(markdown, listOf("已达成共识"), consensusHeaders, consensusFallback, "C", 1)
                )
            )

            add(ParagraphBlock("5. 未解决问题", "Heading1"))
            add(
                TableBlock(
                    teamTableRows(markdown, listOf("未解决问题"), unresolvedHeaders, emptyList(), "U", 1)
                )
            )

            add(ParagraphBlock("6. 行动项跟踪表", "Heading1"))
            add(
                TableBlock(
                    teamTableRows(markdown, listOf("行动项跟踪", "行动项"), actionHeaders, actionFallback, "ACT", 1)
                )
            )

            add(ParagraphBlock("7. 待确认项", "Heading1"))
            sectionParagraphs(markdown, "待确认项").ifEmpty { listOf("未提及") }
                .forEachIndexed { index, value ->
                    add(ParagraphBlock(ReportDocumentFormatter.numbered(value, index), "ListNumber"))
                }

            add(ParagraphBlock("8. 后续研究与储备事项", "Heading1"))
            add(
                TableBlock(
                    teamTableRows(
                        markdown,
                        listOf(
                            "后续研究与储备事项",
                            "后续研究及储备事项",
                            "待研究与储备事项",
                            "后续沉淀事项",
                            "沉淀事项",
                            "backlog",
                            "Backlog"
                        ),
                        backlogHeaders,
                        emptyList(),
                        "BLG",
                        1
                    )
                )
            )

            add(ParagraphBlock("9. 风险提醒", "Heading1"))
            add(
                TableBlock(
                    teamTableRows(markdown, listOf("风险提醒", "风险"), riskHeaders, emptyList(), "R", 1)
                )
            )

            add(ParagraphBlock("10. 会议结论", "Heading1"))
            sectionParagraphs(markdown, "会议结论")
                .ifEmpty { listOf(report.summary.ifBlank { "未提及" }) }
                .forEach { add(ParagraphBlock(it)) }
        }
    }

    private fun parseMarkdownDocument(raw: String): ParsedMarkdownDocument {
        var title: String? = null
        val sections = mutableListOf<ParsedMarkdownSection>()
        var current = ParsedMarkdownSection("正文", 0).also(sections::add)
        val lines = ReportDocumentFormatter.normalizeLists(raw).lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
            when {
                line.isBlank() || line == "---" -> index++
                heading != null -> {
                    val level = heading.groupValues[1].length
                    val text = cleanMarkdown(heading.groupValues[2])
                    if (level == 1 && title.isNullOrBlank()) {
                        title = text
                    } else {
                        current = ParsedMarkdownSection(text, level).also(sections::add)
                    }
                    index++
                }
                line.startsWith("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1]) -> {
                    val rows = mutableListOf<List<String>>()
                    rows += parseTableRow(line)
                    index += 2
                    while (index < lines.size && lines[index].trim().startsWith("|")) {
                        rows += parseTableRow(lines[index])
                        index++
                    }
                    current.tables.add(rows)
                }
                else -> {
                    val text = cleanMarkdown(
                        line.replaceFirst(Regex("^（\\d+）\\s*"), "")
                    )
                    if (text.isNotBlank()) current.paragraphs += text
                    index++
                }
            }
        }
        return ParsedMarkdownDocument(title, sections)
    }

    private fun sectionParagraphs(
        markdown: ParsedMarkdownDocument,
        vararg keywords: String
    ): List<String> = markdown.sections
        .filter { section -> keywords.any { section.heading.contains(it, ignoreCase = true) } }
        .flatMap { it.paragraphs }
        .filterNot(::isTemplatePlaceholder)

    private fun teamTableRows(
        markdown: ParsedMarkdownDocument,
        keywords: List<String>,
        headers: List<String>,
        fallbackRows: List<List<String>>,
        idPrefix: String,
        contentColumn: Int
    ): List<List<String>> {
        val section = markdown.sections.firstOrNull { candidate ->
            keywords.any { candidate.heading.contains(it, ignoreCase = true) }
        }
        val tableRows = section?.tables?.firstOrNull()
            ?.let { table -> mapTableRows(table, headers, idPrefix) }
            .orEmpty()
        val paragraphRows = section?.paragraphs.orEmpty()
            .filterNot(::isTemplatePlaceholder)
            .mapIndexed { index, paragraph ->
                MutableList(headers.size) { column -> defaultCellValue(headers[column]) }.apply {
                    this[0] = teamIdentifier(idPrefix, index)
                    this[contentColumn] = paragraph
                }
            }
        val rows = when {
            tableRows.isNotEmpty() -> tableRows
            fallbackRows.isNotEmpty() -> fallbackRows
            paragraphRows.isNotEmpty() -> paragraphRows
            else -> listOf(
                MutableList(headers.size) { column -> defaultCellValue(headers[column]) }.apply {
                    this[0] = teamIdentifier(idPrefix, 0)
                    this[contentColumn] = "未提及"
                }
            )
        }
        return listOf(headers) + rows.map { row ->
            headers.indices.map { column ->
                row.getOrNull(column)?.takeIf { it.isNotBlank() }
                    ?: defaultCellValue(headers[column])
            }
        }
    }

    private fun defaultCellValue(header: String): String = when (header) {
        "负责人", "截止时间" -> "待确认"
        "当前状态" -> "待确认"
        "是否待确认" -> "是"
        "是否可直接视为立项" -> "否"
        else -> "未提及"
    }

    private fun mapTableRows(
        table: List<List<String>>,
        targetHeaders: List<String>,
        idPrefix: String
    ): List<List<String>> {
        val sourceHeaders = table.firstOrNull().orEmpty()
        if (sourceHeaders.isEmpty()) return emptyList()
        return table.drop(1).mapIndexed { rowIndex, sourceRow ->
            targetHeaders.mapIndexed { columnIndex, targetHeader ->
                val sourceIndex = sourceHeaders.indexOfFirst { sourceHeader ->
                    headersMatch(sourceHeader, targetHeader)
                }
                val sourceValue = sourceRow.getOrNull(sourceIndex).orEmpty()
                when {
                    sourceValue.isNotBlank() -> sourceValue
                    columnIndex == 0 -> teamIdentifier(idPrefix, rowIndex)
                    else -> defaultCellValue(targetHeader)
                }
            }
        }
    }

    private fun headersMatch(source: String, target: String): Boolean {
        val sourceValue = normalizeHeader(source)
        val targetValue = normalizeHeader(target)
        if (sourceValue == targetValue) return true
        val aliases = when (targetValue) {
            "actionid" -> setOf("行动项编号", "任务编号", "编号", "序号")
            "事项编号" -> setOf("backlogid", "候选编号", "编号", "序号")
            "编号" -> setOf("共识编号", "问题编号", "序号")
            "风险编号" -> setOf("编号", "序号")
            "事项" -> setOf("任务内容", "行动项", "待办事项", "任务")
            "负责人" -> setOf("责任人", "执行人", "owner")
            "当前状态" -> setOf("状态", "进展")
            "截止时间" -> setOf("完成期限", "截止日期", "计划时间")
            "说明" -> setOf("描述", "详情")
            "备注" -> setOf("补充说明")
            else -> emptySet()
        }
        return sourceValue in aliases.map(::normalizeHeader)
    }

    private fun normalizeHeader(value: String): String = cleanMarkdown(value)
        .lowercase()
        .replace(Regex("[\\s_\\-]+"), "")

    private fun teamIdentifier(prefix: String, index: Int): String {
        val width = if (prefix.length == 1) 2 else 3
        return "$prefix-${(index + 1).toString().padStart(width, '0')}"
    }

    private fun isTemplatePlaceholder(value: String): Boolean {
        val text = value.trim()
        return text.isBlank() || text == "..." ||
            (text.startsWith("（") && text.endsWith("）")) ||
            text.startsWith("输出约束")
    }

    private fun paragraphXml(block: ParagraphBlock): String {
        if (block.style == "Spacer") return "<w:p><w:pPr><w:spacing w:after=\"80\"/></w:pPr></w:p>"
        if (templateEntries.isNotEmpty()) return templateParagraphXml(block)
        return buildString {
            append("<w:p><w:pPr><w:pStyle w:val=\"").append(block.style).append("\"/>")
            append("<w:widowControl/><w:keepLines/>")
            if (block.style == "ListBullet" || block.style == "ListNumber") {
                append("<w:ind w:left=\"420\" w:hanging=\"210\"/>")
            }
            append("</w:pPr><w:r><w:t xml:space=\"preserve\">")
            append(block.text.escapeXml()).append("</w:t></w:r></w:p>")
        }
    }

    private fun templateParagraphXml(block: ParagraphBlock): String {
        val paragraphProperties = when (block.style) {
            "Title" -> "<w:jc w:val=\"center\"/><w:spacing w:after=\"180\"/><w:keepNext/><w:keepLines/><w:widowControl/>"
            "Metadata" -> "<w:jc w:val=\"right\"/><w:spacing w:after=\"180\"/><w:keepLines/><w:widowControl/>"
            "Heading1" -> "<w:spacing w:before=\"220\" w:after=\"100\"/><w:keepNext/><w:keepLines/><w:widowControl/>"
            "Heading2" -> "<w:spacing w:before=\"160\" w:after=\"80\"/><w:keepNext/><w:keepLines/><w:widowControl/>"
            "ListBullet", "ListNumber" -> "<w:ind w:left=\"420\" w:hanging=\"210\"/><w:spacing w:after=\"60\"/><w:keepLines/><w:widowControl/>"
            "Caption" -> "<w:jc w:val=\"center\"/><w:spacing w:before=\"60\" w:after=\"140\"/><w:keepLines/><w:widowControl/>"
            else -> "<w:spacing w:after=\"120\" w:line=\"312\" w:lineRule=\"auto\"/><w:widowControl/>"
        }
        val runProperties = when (block.style) {
            "Title" -> "<w:b/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/>"
            "Metadata" -> "<w:color w:val=\"687887\"/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/>"
            "Heading1" -> "<w:b/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/>"
            "Heading2" -> "<w:b/><w:sz w:val=\"23\"/><w:szCs w:val=\"23\"/>"
            "Caption" -> "<w:color w:val=\"687887\"/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/>"
            else -> ""
        }
        return buildString {
            append("<w:p><w:pPr>").append(paragraphProperties).append("</w:pPr>")
            append("<w:r><w:rPr>").append(runProperties).append("</w:rPr>")
            append("<w:t xml:space=\"preserve\">").append(block.text.escapeXml())
            append("</w:t></w:r></w:p>")
        }
    }

    private fun tableXml(rows: List<List<String>>): String {
        val columns = rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: return ""
        val columnWidths = tableColumnWidths(columns)
        val fontSize = when {
            columns >= 8 -> 16
            columns >= 5 -> 18
            else -> 20
        }
        return buildString {
            append("<w:tbl><w:tblPr><w:tblW w:w=\"9734\" w:type=\"dxa\"/>")
            append("<w:tblLayout w:type=\"fixed\"/><w:tblCellMar>")
            append("<w:top w:w=\"80\" w:type=\"dxa\"/><w:left w:w=\"80\" w:type=\"dxa\"/>")
            append("<w:bottom w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/>")
            append("</w:tblCellMar><w:tblBorders>")
            val borderColor = if (report.templateName.usesStudyReportStyle()) "D6E1F2" else "AAB7C4"
            listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { edge ->
                append("<w:").append(edge).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"")
                    .append(borderColor).append("\"/>")
            }
            append("</w:tblBorders></w:tblPr><w:tblGrid>")
            columnWidths.forEach { width -> append("<w:gridCol w:w=\"").append(width).append("\"/>") }
            append("</w:tblGrid>")
            rows.forEachIndexed { rowIndex, row ->
                append("<w:tr><w:trPr><w:cantSplit/>")
                if (rowIndex == 0) append("<w:tblHeader/>")
                append("</w:trPr>")
                repeat(columns) { columnIndex ->
                    val value = row.getOrNull(columnIndex).orEmpty()
                    append("<w:tc><w:tcPr><w:tcW w:w=\"").append(columnWidths[columnIndex]).append("\" w:type=\"dxa\"/>")
                    if (rowIndex == 0) {
                        append("<w:shd w:val=\"clear\" w:fill=\"")
                            .append(if (report.templateName.usesStudyReportStyle()) "EAF2FF" else "D9EAF7")
                            .append("\"/>")
                    }
                    append("<w:vAlign w:val=\"center\"/></w:tcPr><w:p><w:pPr><w:spacing w:after=\"0\"/><w:keepLines/><w:widowControl/></w:pPr>")
                    append("<w:r><w:rPr><w:sz w:val=\"").append(fontSize).append("\"/><w:szCs w:val=\"").append(fontSize).append("\"/>")
                    if (rowIndex == 0) append("<w:b/>")
                    append("</w:rPr><w:t xml:space=\"preserve\">").append(cleanMarkdown(value).escapeXml())
                    append("</w:t></w:r></w:p></w:tc>")
                }
                append("</w:tr>")
            }
            append("</w:tbl><w:p><w:pPr><w:spacing w:after=\"80\"/></w:pPr></w:p>")
        }
    }

    private fun tableColumnWidths(columns: Int): List<Int> = when (columns) {
        3 -> listOf(1100, 3200, 5434)
        4 -> listOf(900, 3200, 1600, 4034)
        8 -> listOf(900, 2100, 900, 1000, 1100, 1000, 1400, 1334)
        10 -> listOf(800, 1300, 900, 1500, 700, 1100, 800, 850, 1050, 734)
        else -> List(columns) { 9734 / columns }
    }

    private fun imageXml(
        index: Int,
        image: DocxImage,
        caption: String,
        maxWidth: Long = 5_669_280L,
        maxHeight: Long = 5_943_600L
    ): String {
        val sourceWidth = image.widthPx.coerceAtLeast(1).toLong()
        val sourceHeight = image.heightPx.coerceAtLeast(1).toLong()
        val scale = minOf(maxWidth.toDouble() / sourceWidth, maxHeight.toDouble() / sourceHeight)
        val width = (sourceWidth * scale).roundToLong()
        val height = (sourceHeight * scale).roundToLong()
        val pictureId = index + 1
        return """
            <w:p><w:pPr><w:jc w:val="center"/><w:keepNext/></w:pPr><w:r><w:drawing>
              <wp:inline distT="0" distB="0" distL="0" distR="0">
                <wp:extent cx="$width" cy="$height"/><wp:effectExtent l="0" t="0" r="0" b="0"/>
                <wp:docPr id="$pictureId" name="Meeting image $pictureId" descr="${caption.escapeXml()}"/>
                <wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr>
                <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                  <pic:pic><pic:nvPicPr><pic:cNvPr id="$pictureId" name="image$pictureId.jpg"/><pic:cNvPicPr/></pic:nvPicPr>
                  <pic:blipFill><a:blip r:embed="rIdImage$pictureId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
                  <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$width" cy="$height"/></a:xfrm>
                  <a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>
                </a:graphicData></a:graphic>
              </wp:inline>
            </w:drawing></w:r></w:p>
        """.trimIndent()
    }

    private fun pageBreakXml(): String =
        "<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr><w:r><w:br w:type=\"page\"/></w:r></w:p>"

    private fun contentTypes(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        if (allImages.isNotEmpty()) append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
        append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
        append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>")
        append("<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>")
        append("<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>")
        append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        append("</Types>")
    }

    private fun templateContentTypes(): String {
        val original = templateEntries["[Content_Types].xml"]
            ?.toString(Charsets.UTF_8)
            ?: return contentTypes()
        if (allImages.isEmpty() || Regex("Extension=\"jpe?g\"", RegexOption.IGNORE_CASE).containsMatchIn(original)) {
            return original
        }
        return original.replace(
            "</Types>",
            "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/></Types>"
        )
    }

    private fun rootRelationships(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
        </Relationships>
    """.trimIndent()

    private fun documentRelationships(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        append("<Relationship Id=\"rIdSettings\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/>")
        append("<Relationship Id=\"rIdFooter\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>")
        allImages.indices.forEach { index ->
            append("<Relationship Id=\"rIdImage").append(index + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image")
                .append(index + 1).append(".jpg\"/>")
        }
        append("</Relationships>")
    }

    private fun templateDocumentRelationships(): String {
        val original = templateEntries["word/_rels/document.xml.rels"]
            ?.toString(Charsets.UTF_8)
            ?: return documentRelationships()
        val imageRelationships = buildString {
            allImages.indices.forEach { index ->
                append("<Relationship Id=\"rIdImage").append(index + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image")
                    .append(index + 1).append(".jpg\"/>")
            }
        }
        return original.replace("</Relationships>", "$imageRelationships</Relationships>")
    }

    private fun sectionPropertiesXml(): String {
        val templateDocument = templateEntries["word/document.xml"]?.toString(Charsets.UTF_8)
        if (templateDocument != null) {
            val start = templateDocument.lastIndexOf("<w:sectPr")
            val closingTag = "</w:sectPr>"
            val end = templateDocument.indexOf(closingTag, start).takeIf { it >= 0 }
            if (start >= 0 && end != null) {
                return templateDocument.substring(start, end + closingTag.length)
            }
        }
        return """
            <w:sectPr>
              <w:footerReference w:type="default" r:id="rIdFooter"/>
              <w:pgSz w:w="12240" w:h="15840"/>
              <w:pgMar w:top="1253" w:right="1253" w:bottom="1253" w:left="1253" w:header="720" w:footer="720" w:gutter="0"/>
              <w:cols w:space="720"/><w:docGrid w:linePitch="312"/>
            </w:sectPr>
        """.trimIndent()
    }

    private fun coreProperties(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
          xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"
          xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <dc:title>${(report.templateName.ifBlank { "会议纪要" }).escapeXml()}</dc:title>
          <dc:creator>智悟本</dc:creator><cp:lastModifiedBy>智悟本</cp:lastModifiedBy>
          <dcterms:created xsi:type="dcterms:W3CDTF">$generatedAt</dcterms:created>
          <dcterms:modified xsi:type="dcterms:W3CDTF">$generatedAt</dcterms:modified>
        </cp:coreProperties>
    """.trimIndent()

    private fun appProperties(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
          xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
          <Application>智悟本</Application><AppVersion>1.0</AppVersion>
        </Properties>
    """.trimIndent()

    private fun settingsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:zoom w:percent="100"/><w:defaultTabStop w:val="420"/>
          <w:themeFontLang w:val="zh-CN" w:eastAsia="zh-CN"/>
          <w:compat/><w:updateFields w:val="true"/>
        </w:settings>
    """.trimIndent()

    private fun stylesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:docDefaults>
            <w:rPrDefault><w:rPr><w:rFonts w:ascii="Microsoft YaHei" w:hAnsi="Microsoft YaHei" w:eastAsia="Microsoft YaHei"/><w:sz w:val="21"/><w:szCs w:val="21"/><w:lang w:val="zh-CN" w:eastAsia="zh-CN"/></w:rPr></w:rPrDefault>
            <w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="312" w:lineRule="auto"/></w:pPr></w:pPrDefault>
          </w:docDefaults>
          <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="正文"/></w:style>
          <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="标题"/><w:basedOn w:val="Normal"/><w:next w:val="Metadata"/>
            <w:pPr><w:jc w:val="center"/><w:spacing w:before="0" w:after="180"/><w:keepNext/></w:pPr>
            <w:rPr><w:b/><w:color w:val="17365D"/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="Metadata"><w:name w:val="元数据"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:jc w:val="right"/><w:spacing w:after="180"/></w:pPr><w:rPr><w:color w:val="687887"/><w:sz w:val="18"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="一级标题"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:spacing w:before="220" w:after="100"/><w:keepNext/><w:outlineLvl w:val="0"/><w:pBdr><w:bottom w:val="single" w:sz="8" w:space="4" w:color="4F81BD"/></w:pBdr></w:pPr>
            <w:rPr><w:b/><w:color w:val="17365D"/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="二级标题"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:spacing w:before="160" w:after="80"/><w:keepNext/><w:outlineLvl w:val="1"/></w:pPr>
            <w:rPr><w:b/><w:color w:val="365F91"/><w:sz w:val="23"/><w:szCs w:val="23"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="ListBullet"><w:name w:val="项目符号"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="60"/></w:pPr></w:style>
          <w:style w:type="paragraph" w:styleId="ListNumber"><w:name w:val="编号列表"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="60"/></w:pPr></w:style>
          <w:style w:type="paragraph" w:styleId="Caption"><w:name w:val="图注"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:jc w:val="center"/><w:spacing w:before="60" w:after="140"/><w:keepLines/><w:widowControl/></w:pPr><w:rPr><w:color w:val="687887"/><w:sz w:val="18"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="StudyTitle"><w:name w:val="研学标题"/><w:basedOn w:val="Normal"/><w:next w:val="StudyMetadata"/>
            <w:pPr><w:jc w:val="left"/><w:spacing w:before="0" w:after="160"/><w:keepNext/><w:keepLines/></w:pPr>
            <w:rPr><w:b/><w:color w:val="1E3A5F"/><w:sz w:val="38"/><w:szCs w:val="38"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="StudyMetadata"><w:name w:val="研学元数据"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:spacing w:after="140"/><w:keepLines/></w:pPr><w:rPr><w:b/><w:color w:val="355EA8"/><w:sz w:val="19"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="StudyLead"><w:name w:val="研学导语"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:ind w:left="260" w:right="260"/><w:spacing w:before="80" w:after="200" w:line="340" w:lineRule="auto"/><w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="8BB4F0"/></w:pBdr><w:keepLines/><w:widowControl/></w:pPr>
            <w:rPr><w:i/><w:color w:val="334155"/><w:sz w:val="22"/><w:szCs w:val="22"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="StudyHeading1"><w:name w:val="研学一级标题"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:spacing w:before="260" w:after="120"/><w:keepNext/><w:keepLines/><w:outlineLvl w:val="0"/><w:pBdr><w:left w:val="single" w:sz="20" w:space="8" w:color="2563EB"/><w:bottom w:val="single" w:sz="4" w:space="5" w:color="D6E1F2"/></w:pBdr></w:pPr>
            <w:rPr><w:b/><w:color w:val="1E3A5F"/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="StudyHeading2"><w:name w:val="研学二级标题"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:spacing w:before="180" w:after="80"/><w:keepNext/><w:keepLines/><w:outlineLvl w:val="1"/></w:pPr>
            <w:rPr><w:b/><w:color w:val="355EA8"/><w:sz w:val="23"/><w:szCs w:val="23"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="StudyCaption"><w:name w:val="研学图注"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:jc w:val="center"/><w:spacing w:before="60" w:after="180"/><w:keepLines/><w:widowControl/></w:pPr>
            <w:rPr><w:color w:val="64748B"/><w:sz w:val="18"/><w:szCs w:val="18"/></w:rPr></w:style>
        </w:styles>
    """.trimIndent()

    private fun footerXml(): String {
        val footerTitle = if (report.templateName.usesStudyReportStyle()) "研学考察记录" else "结构化会议纪要"
        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:p><w:pPr><w:jc w:val="center"/><w:pBdr><w:top w:val="single" w:sz="4" w:space="5" w:color="D9E2F3"/></w:pBdr></w:pPr>
            <w:r><w:rPr><w:color w:val="7F8C8D"/><w:sz w:val="17"/></w:rPr><w:t>智悟本 · $footerTitle  |  第 </w:t></w:r>
            <w:fldSimple w:instr="PAGE"><w:r><w:rPr><w:color w:val="7F8C8D"/><w:sz w:val="17"/></w:rPr><w:t>1</w:t></w:r></w:fldSimple>
            <w:r><w:rPr><w:color w:val="7F8C8D"/><w:sz w:val="17"/></w:rPr><w:t> 页</w:t></w:r>
          </w:p>
        </w:ftr>
        """.trimIndent()
    }

    private fun isTableSeparator(line: String): Boolean =
        line.trim().trim('|').split('|').all { it.trim().matches(Regex(":?-{3,}:?")) }

    private fun parseTableRow(line: String): List<String> =
        line.trim().trim('|').split('|').map { cleanMarkdown(it.trim()) }

    private fun cleanMarkdown(text: String): String = text
        .replace(Regex("^\\s*#{1,6}\\s+"), "")
        .replace(Regex("^\\s*>+\\s?"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace("`", "")
        .trim()

    private fun String.escapeXml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun ZipOutputStream.addText(path: String, content: String) {
        addBytes(path, content.toByteArray(Charsets.UTF_8))
    }

    private fun ZipOutputStream.addBytes(path: String, content: ByteArray) {
        putNextEntry(ZipEntry(path).apply { time = report.generatedAt })
        write(content)
        closeEntry()
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream()
                    input.copyTo(output)
                    entries[entry.name] = output.toByteArray()
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
        return entries
    }
}

private fun ByteArray.isJpegImage(): Boolean =
    size >= 4 &&
        (this[0].toInt() and 0xFF) == 0xFF &&
        (this[1].toInt() and 0xFF) == 0xD8 &&
        (this[size - 2].toInt() and 0xFF) == 0xFF &&
        (this[size - 1].toInt() and 0xFF) == 0xD9

private fun String.isMarkdownHeading(): Boolean = matches(Regex("^#{1,6}\\s+.+$"))

private fun String.isForumRosterHeading(): Boolean {
    if (!isMarkdownHeading()) return false
    val heading = replaceFirst(Regex("^#{1,6}\\s+"), "")
        .replaceFirst(Regex("^\\d+[.、]\\s*"), "")
        .trim()
    return heading.contains("参会") && (heading.contains("名录") || heading.contains("通讯录"))
}
