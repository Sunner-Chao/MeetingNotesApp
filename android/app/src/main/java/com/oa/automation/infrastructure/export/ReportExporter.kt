package com.oa.automation.infrastructure.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.oa.automation.domain.model.ForumParticipant
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.MeetingMode
import com.oa.automation.domain.model.isForumMeetingTemplate
import java.io.File
import java.io.FileOutputStream

private fun String.usesStudyReportStyle(): Boolean =
    contains("研学") || contains("参观考察") || contains("游记") || contains("文旅")

private fun String.usesProjectManagementReportStyle(): Boolean =
    MeetingMode.fromTemplateName(this) == MeetingMode.PROGRESS ||
        contains("孔爵") && contains("表格")

/** Image ordering contract shared by PDF and UI preview/export tests. */
internal fun shouldInlineReportImage(templateName: String): Boolean =
    !templateName.usesProjectManagementReportStyle()

internal fun reportImageAppendixTitle(templateName: String): String = when {
    templateName.usesStudyReportStyle() -> "照片集锦"
    templateName.usesProjectManagementReportStyle() -> "会议影像资料与签到表"
    else -> "会议图片"
}

private val photoAnchorPattern = Regex(
    "^\\s*\\[照片\\s*[:：]\\s*图\\s*(\\d+)(?:\\s*[|｜]\\s*([^]]+?))?]\\s*$"
)

/**
 * Report Exporter - Export meeting reports to various formats
 */
object ReportExporter {

    /**
     * Export report to Markdown format
     */
    fun exportToMarkdown(report: Report): String {
        if (report.rawContent.isNotBlank()) {
            return report.rawContent
        }

        return buildString {
            appendLine("# 会议纪要")
            appendLine()
            appendLine("---")
            appendLine()

            appendLine("## 会议概述")
            appendLine()
            appendLine(report.summary.ifEmpty { "暂无概述" })
            appendLine()

            if (report.keyPoints.isNotEmpty()) {
                appendLine("## 关键要点")
                appendLine()
                report.keyPoints.forEachIndexed { index, point ->
                    appendLine("${index + 1}. $point")
                }
                appendLine()
            }

            if (report.decisions.isNotEmpty()) {
                appendLine("## 决策事项")
                appendLine()
                report.decisions.forEach { decision ->
                    appendLine("- $decision")
                }
                appendLine()
            }

            if (report.tasks.isNotEmpty()) {
                appendLine("## 待办任务")
                appendLine()
                report.tasks.forEachIndexed { _, task ->
                    val checkbox = if (task.completed) "[x]" else "[ ]"
                    val meta = listOfNotNull(
                        task.assignee?.let { "负责人: $it" },
                        task.due?.let { "截止: $it" }
                    ).joinToString(" | ")
                    appendLine("- $checkbox ${task.content}")
                    if (meta.isNotEmpty()) {
                        appendLine("  $meta")
                    }
                }
                appendLine()
            }

            if (report.actionItems.isNotEmpty()) {
                appendLine("## 行动项")
                appendLine()
                report.actionItems.forEach { item ->
                    appendLine("- $item")
                }
                appendLine()
            }

            appendLine("---")
            appendLine()
            appendLine("*由 智悟本 自动生成 | ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(report.generatedAt))}*")
        }
    }

    /**
     * Export report to plain text format
     */
    fun exportToText(report: Report): String {
        if (report.rawContent.isNotBlank()) {
            return report.rawContent
        }

        return buildString {
            appendLine("会议纪要")
            appendLine("=".repeat(20))
            appendLine()

            appendLine("会议概述:")
            appendLine(report.summary.ifEmpty { "暂无概述" })
            appendLine()

            if (report.keyPoints.isNotEmpty()) {
                appendLine("关键要点:")
                report.keyPoints.forEachIndexed { index, point ->
                    appendLine("  ${index + 1}. $point")
                }
                appendLine()
            }

            if (report.decisions.isNotEmpty()) {
                appendLine("决策事项:")
                report.decisions.forEach { decision ->
                    appendLine("  - $decision")
                }
                appendLine()
            }

            if (report.tasks.isNotEmpty()) {
                appendLine("待办任务:")
                report.tasks.forEach { task ->
                    val status = if (task.completed) "[完成]" else "[待办]"
                    val meta = listOfNotNull(
                        task.assignee?.let { "负责人: $it" },
                        task.due?.let { "截止: $it" }
                    ).joinToString(", ")
                    appendLine("  - $status ${task.content}")
                    if (meta.isNotEmpty()) {
                        appendLine("    $meta")
                    }
                }
                appendLine()
            }

            if (report.actionItems.isNotEmpty()) {
                appendLine("行动项:")
                report.actionItems.forEach { item ->
                    appendLine("  - $item")
                }
            }
        }
    }

    /**
     * Export report to PDF format using Android PdfDocument API
     */
    fun exportToPdf(
        context: Context,
        report: Report,
        attachments: List<MeetingAttachment>,
        meetingTitle: String = "",
        forumParticipants: List<ForumParticipant> = emptyList()
    ): File {
        val isStudyReport = report.templateName.usesStudyReportStyle()
        val participantsForLayout = forumParticipants.ifEmpty { report.participants }
        val images = attachments.map { attachment ->
            MeetingImagePreparer.prepare(attachment)
                ?: error("无法写入会议图片：${attachment.displayName}")
        }
        val document = PdfDocument()
        val pageWidth = 595  // A4 width in points (72 dpi)
        val pageHeight = 842 // A4 height in points
        val margin = 40f
        val contentWidth = pageWidth - 2 * margin
        val contentBottom = pageHeight - margin - 18f
        val accentColor = Color.parseColor(if (isStudyReport) "#2563EB" else "#1A73E8")
        val headingColor = Color.parseColor(if (isStudyReport) "#1F2937" else "#333333")
        val bodyColor = Color.parseColor(if (isStudyReport) "#374151" else "#555555")
        val mutedColor = Color.parseColor(if (isStudyReport) "#64748B" else "#999999")
        val softAccentColor = Color.parseColor("#EAF2FF")
        val softSurfaceColor = Color.parseColor("#F7F9FC")

        val titlePaint = TextPaint().apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            color = accentColor
            isAntiAlias = true
        }

        val headingPaint = TextPaint().apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            color = headingColor
            isAntiAlias = true
        }

        val bodyPaint = TextPaint().apply {
            textSize = 12f
            typeface = Typeface.DEFAULT
            color = bodyColor
            isAntiAlias = true
        }

        val footerPaint = TextPaint().apply {
            textSize = 10f
            typeface = Typeface.DEFAULT
            color = mutedColor
            isAntiAlias = true
        }

        val pageNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f
            color = Color.parseColor("#7B8791")
            textAlign = Paint.Align.RIGHT
        }

        val linePaint = Paint().apply {
            color = if (isStudyReport) Color.parseColor("#CBD9EE") else accentColor
            strokeWidth = if (isStudyReport) 1f else 2f
        }

        val studyLeadPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            color = Color.parseColor("#334155")
        }

        val studyMetaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.parseColor("#355EA8")
        }

        val studySurfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = softSurfaceColor
        }

        val studyAccentSurfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = softAccentColor
        }

        val studyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = Color.parseColor("#D6E1F2")
        }

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = document.startPage(pageInfo)
        var canvas: Canvas = page.canvas
        var yPosition = margin

        fun finishCurrentPage() {
            canvas.drawText("第 $pageNumber 页", pageWidth - margin, pageHeight - 18f, pageNumberPaint)
            document.finishPage(page)
        }

        fun startNewPage() {
            finishCurrentPage()
            pageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            page = document.startPage(pageInfo)
            canvas = page.canvas
            yPosition = margin
        }

        fun ensureSpace(requiredHeight: Float) {
            if (yPosition > margin && yPosition + requiredHeight > contentBottom) {
                startNewPage()
            }
        }

        fun addVerticalSpace(height: Float) {
            ensureSpace(height)
            yPosition += height
        }

        fun createTextLayout(
            text: String,
            paint: TextPaint,
            alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
            width: Int = contentWidth.toInt(),
            maxLines: Int = Int.MAX_VALUE
        ): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(alignment)
            .setLineSpacing(4f, 1.2f)
            .setMaxLines(maxLines)
            .build()

        fun drawText(
            text: String,
            paint: TextPaint,
            x: Float = margin,
            minimumFollowingSpace: Float = 0f
        ): Float {
            var remaining = text.trim()
            var firstPart = true
            while (remaining.isNotEmpty()) {
                var layout = createTextLayout(remaining, paint)
                val minimumBlock = minOf(layout.height.toFloat(), paint.textSize * 2.2f) +
                    if (firstPart) minimumFollowingSpace else 0f
                ensureSpace(minimumBlock)
                val available = contentBottom - yPosition
                if (layout.height <= available) {
                    canvas.save()
                    canvas.translate(x, yPosition)
                    layout.draw(canvas)
                    canvas.restore()
                    yPosition += layout.height + 8f
                    break
                }

                val lastLine = (0 until layout.lineCount)
                    .lastOrNull { layout.getLineBottom(it) <= available }
                if (lastLine == null) {
                    startNewPage()
                    continue
                }
                val end = layout.getLineEnd(lastLine).coerceAtLeast(1)
                val pageText = remaining.substring(0, end).trimEnd()
                layout = createTextLayout(pageText, paint)
                canvas.save()
                canvas.translate(x, yPosition)
                layout.draw(canvas)
                canvas.restore()
                remaining = remaining.substring(end).trimStart()
                yPosition += layout.height
                if (remaining.isNotEmpty()) startNewPage() else yPosition += 8f
                firstPart = false
            }
            return yPosition
        }

        fun drawStudyLead(text: String) {
            val horizontalPadding = 14f
            val verticalPadding = 12f
            val layout = createTextLayout(
                text = cleanPdfMarkdown(text),
                paint = studyLeadPaint,
                width = (contentWidth - horizontalPadding * 2).toInt()
            )
            val blockHeight = layout.height + verticalPadding * 2
            ensureSpace(blockHeight + 8f)
            val bounds = RectF(margin, yPosition, pageWidth - margin, yPosition + blockHeight)
            canvas.drawRoundRect(bounds, 8f, 8f, studyAccentSurfacePaint)
            canvas.save()
            canvas.translate(margin + horizontalPadding, yPosition + verticalPadding)
            layout.draw(canvas)
            canvas.restore()
            yPosition += blockHeight + 12f
        }

        fun drawStudyHeading(text: String, primary: Boolean) {
            val paint = TextPaint(headingPaint).apply {
                textSize = if (primary) 15f else 13f
                color = if (primary) Color.parseColor("#1E3A5F") else headingColor
            }
            val horizontalPadding = if (primary) 14f else 4f
            val verticalPadding = if (primary) 9f else 4f
            val layout = createTextLayout(
                text = cleanPdfMarkdown(text),
                paint = paint,
                width = (contentWidth - horizontalPadding * 2).toInt()
            )
            val blockHeight = layout.height + verticalPadding * 2
            ensureSpace(blockHeight + if (primary) 36f else 26f)
            if (primary) {
                val bounds = RectF(margin, yPosition, pageWidth - margin, yPosition + blockHeight)
                canvas.drawRoundRect(bounds, 7f, 7f, studyAccentSurfacePaint)
                canvas.drawRect(margin, yPosition, margin + 4f, yPosition + blockHeight, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                })
            }
            canvas.save()
            canvas.translate(margin + horizontalPadding, yPosition + verticalPadding)
            layout.draw(canvas)
            canvas.restore()
            yPosition += blockHeight + if (primary) 10f else 6f
        }

        fun drawTable(sourceRows: List<List<String>>) {
            if (sourceRows.isEmpty()) return
            val columnCount = sourceRows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: return
            val rows = sourceRows.map { row ->
                List(columnCount) { column -> cleanPdfMarkdown(row.getOrNull(column).orEmpty()) }
            }
            val cellWidth = contentWidth / columnCount
            val cellPadding = 4f
            val textSize = when {
                columnCount >= 8 -> 6.5f
                columnCount >= 5 -> 8f
                else -> 10f
            }
            val headerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSize
                typeface = Typeface.DEFAULT_BOLD
                color = if (isStudyReport) Color.parseColor("#1E3A5F") else Color.WHITE
            }
            val cellTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSize
                typeface = Typeface.DEFAULT
                color = Color.parseColor("#24323D")
            }
            val headerBackgroundPaint = Paint().apply {
                style = Paint.Style.FILL
                color = if (isStudyReport) Color.parseColor("#DCE9FB") else accentColor
            }
            val alternateBackgroundPaint = Paint().apply {
                style = Paint.Style.FILL
                color = if (isStudyReport) Color.parseColor("#F8FAFD") else Color.parseColor("#F4F7FA")
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.7f
                color = Color.parseColor("#9AA8B4")
            }

            fun layoutsFor(row: List<String>, isHeader: Boolean): Pair<List<StaticLayout>, Float> {
                val paint = if (isHeader) headerTextPaint else cellTextPaint
                val layouts = row.map { value ->
                    createTextLayout(
                        text = value,
                        paint = paint,
                        width = (cellWidth - cellPadding * 2).toInt()
                    )
                }
                val rowHeight = (layouts.maxOfOrNull { it.height.toFloat() } ?: 0f) + cellPadding * 2
                return layouts to rowHeight.coerceAtLeast(textSize + cellPadding * 2)
            }

            fun renderRow(layouts: List<StaticLayout>, rowHeight: Float, rowIndex: Int, isHeader: Boolean) {
                repeat(columnCount) { column ->
                    val left = margin + column * cellWidth
                    val bounds = RectF(left, yPosition, left + cellWidth, yPosition + rowHeight)
                    when {
                        isHeader -> canvas.drawRect(bounds, headerBackgroundPaint)
                        rowIndex % 2 == 0 -> canvas.drawRect(bounds, alternateBackgroundPaint)
                    }
                    canvas.drawRect(bounds, borderPaint)
                    canvas.save()
                    canvas.clipRect(bounds)
                    canvas.translate(left + cellPadding, yPosition + cellPadding)
                    layouts[column].draw(canvas)
                    canvas.restore()
                }
                yPosition += rowHeight
            }

            val (headerLayouts, headerHeight) = layoutsFor(rows.first(), true)
            val firstBodyHeight = rows.getOrNull(1)
                ?.let { layoutsFor(it, false).second }
                ?: 0f
            ensureSpace(headerHeight + firstBodyHeight + 12f)
            rows.forEachIndexed { rowIndex, row ->
                val isHeader = rowIndex == 0
                val (layouts, rowHeight) = if (isHeader) {
                    headerLayouts to headerHeight
                } else {
                    layoutsFor(row, false)
                }
                if (yPosition + rowHeight > contentBottom) {
                    startNewPage()
                    if (!isHeader) {
                        renderRow(headerLayouts, headerHeight, 0, true)
                    }
                }
                renderRow(layouts, rowHeight, rowIndex, isHeader)
            }
            addVerticalSpace(12f)
        }

        val placedImageIndexes = mutableSetOf<Int>()

        fun drawReportImage(index: Int, anchorCaption: String? = null) {
            val image = images.getOrNull(index) ?: return
            val bitmap = checkNotNull(
                BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
            ) { "无法解码图片：${image.caption}" }
            try {
                val fallbackCaption = if (isStudyReport) "现场照片" else "会议图片"
                val captionText = anchorCaption?.trim()?.takeIf(String::isNotBlank)
                    ?: image.caption.ifBlank { fallbackCaption }
                val caption = if (isStudyReport) {
                    "图 ${index + 1}｜$captionText"
                } else {
                    "图 ${index + 1}：$captionText"
                }
                val imageHorizontalInset = if (isStudyReport) 10f else 0f
                val captionLayout = createTextLayout(
                    caption,
                    footerPaint,
                    Layout.Alignment.ALIGN_CENTER,
                    width = (contentWidth - imageHorizontalInset * 2).toInt()
                )
                val blockPadding = if (isStudyReport) 10f else 0f
                val fullPageImageHeight = contentBottom - margin - captionLayout.height - 38f
                val currentPageImageHeight = contentBottom - yPosition - captionLayout.height -
                    24f - blockPadding * 2
                val maxImageHeight = if (isStudyReport && currentPageImageHeight >= 160f) {
                    minOf(fullPageImageHeight, currentPageImageHeight)
                } else {
                    fullPageImageHeight
                }
                val availableImageWidth = contentWidth - imageHorizontalInset * 2
                val scale = minOf(
                    1f,
                    availableImageWidth / bitmap.width,
                    maxImageHeight / bitmap.height
                )
                val imageWidth = bitmap.width * scale
                val imageHeight = bitmap.height * scale
                val blockHeight = imageHeight + captionLayout.height + 24f + blockPadding * 2

                if (yPosition + blockHeight > contentBottom) {
                    startNewPage()
                }

                val blockTop = yPosition
                if (isStudyReport) {
                    val bounds = RectF(margin, blockTop, pageWidth - margin, blockTop + blockHeight)
                    canvas.drawRoundRect(bounds, 8f, 8f, studySurfacePaint)
                    canvas.drawRoundRect(bounds, 8f, 8f, studyBorderPaint)
                    yPosition += blockPadding
                }
                val imageLeft = margin + (contentWidth - imageWidth) / 2f
                canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(imageLeft, yPosition, imageLeft + imageWidth, yPosition + imageHeight),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
                yPosition += imageHeight + 8f
                canvas.save()
                canvas.translate(margin + imageHorizontalInset, yPosition)
                captionLayout.draw(canvas)
                canvas.restore()
                yPosition += captionLayout.height + 16f + if (isStudyReport) blockPadding else 0f
                placedImageIndexes += index
            } finally {
                bitmap.recycle()
            }
        }

        fun participantAvatar(participant: ForumParticipant) =
            if (!participant.photoAuthorized) null else runCatching {
                val encoded = participant.avatarDataUrl
                    ?.substringAfter("base64,", "")
                    ?.takeIf(String::isNotBlank)
                    ?: return@runCatching null
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()

        fun drawForumParticipantWall() {
            val visible = participantsForLayout.filter { it.name.isNotBlank() }.take(24)
            if (!report.templateName.isForumMeetingTemplate() || visible.isEmpty()) return
            yPosition = drawText("论坛参会名录", headingPaint, minimumFollowingSpace = 18f)
            yPosition = drawText(
                "照片墙名单 · ${visible.size} 人 · 未采集头像以姓名首字显示",
                footerPaint
            )
            val columns = 4
            val cellWidth = contentWidth / columns
            val cellHeight = 88f
            visible.chunked(columns).forEach { row ->
                if (yPosition + cellHeight > contentBottom) startNewPage()
                row.forEachIndexed { column, participant ->
                    val left = margin + column * cellWidth
                    val centerX = left + cellWidth / 2f
                    val centerY = yPosition + 23f
                    val avatar = participantAvatar(participant)
                    if (avatar != null) {
                        val radius = 18f
                        val path = Path().apply { addCircle(centerX, centerY, radius, Path.Direction.CW) }
                        canvas.save()
                        canvas.clipPath(path)
                        canvas.drawBitmap(
                            avatar,
                            null,
                            RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius),
                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        )
                        canvas.restore()
                        avatar.recycle()
                    } else {
                        canvas.drawCircle(
                            centerX,
                            centerY,
                            18f,
                            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8BB4F0") }
                        )
                        val initial = createTextLayout(
                            participant.name.take(1),
                            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                                textSize = 14f
                                typeface = Typeface.DEFAULT_BOLD
                                color = Color.WHITE
                            },
                            Layout.Alignment.ALIGN_CENTER,
                            width = 36
                        )
                        canvas.save()
                        canvas.translate(centerX - 18f, centerY - initial.height / 2f)
                        initial.draw(canvas)
                        canvas.restore()
                    }
                    val nameLayout = createTextLayout(
                        participant.name,
                        footerPaint,
                        Layout.Alignment.ALIGN_CENTER,
                        width = (cellWidth - 8f).toInt()
                    )
                    canvas.save()
                    canvas.translate(left + 4f, yPosition + 45f)
                    nameLayout.draw(canvas)
                    canvas.restore()
                    val meta = listOf(participant.role, participant.organization)
                        .filter(String::isNotBlank)
                        .joinToString(" · ")
                    if (meta.isNotBlank()) {
                        val metaLayout = createTextLayout(
                            meta,
                            TextPaint(footerPaint).apply { textSize = 7.5f },
                            Layout.Alignment.ALIGN_CENTER,
                            width = (cellWidth - 8f).toInt(),
                            maxLines = 1
                        )
                        canvas.save()
                        canvas.translate(left + 4f, yPosition + 61f)
                        metaLayout.draw(canvas)
                        canvas.restore()
                    }
                }
                yPosition += cellHeight
            }
            addVerticalSpace(6f)
        }

        // Title
        val title = report.rawContent.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: if (isStudyReport) "研学考察游记" else "会议纪要"
        yPosition = drawText(title, titlePaint)
        canvas.drawLine(margin, yPosition, pageWidth - margin, yPosition, linePaint)
        yPosition += 16f
        drawForumParticipantWall()

        if (report.rawContent.isNotBlank()) {
            // Parse raw markdown content
            val sourceContent = if (report.templateName.usesProjectManagementReportStyle()) {
                ReportDocumentFormatter.normalizeProjectManagementSections(report.rawContent)
            } else {
                report.rawContent
            }
            val lines = ReportDocumentFormatter.normalizeLists(sourceContent).lines()
            var lineIndex = 0
            while (lineIndex < lines.size) {
                val line = lines[lineIndex]
                val trimmed = line.trim()
                if (
                    report.templateName.isForumMeetingTemplate() && participantsForLayout.isNotEmpty() &&
                    trimmed.isForumRosterHeading()
                ) {
                    lineIndex++
                    while (lineIndex < lines.size && !lines[lineIndex].trim().isMarkdownHeading()) {
                        lineIndex++
                    }
                    continue
                }
                val photoAnchor = photoAnchorPattern.matchEntire(trimmed)
                when {
                    // Project-management reports keep all meeting/sign-in images in
                    // the appendix. Their source may still contain legacy photo
                    // anchors, but those anchors must not interrupt the structured
                    // minutes body.
                    photoAnchor != null && shouldInlineReportImage(report.templateName) -> {
                        val imageIndex = photoAnchor.groupValues[1].toIntOrNull()?.minus(1)
                        val anchorCaption = photoAnchor.groupValues.getOrNull(2)
                        if (imageIndex != null && imageIndex !in placedImageIndexes) {
                            drawReportImage(imageIndex, anchorCaption)
                        }
                        lineIndex++
                    }
                    photoAnchor != null -> {
                        // Legacy project-management anchors are represented by the
                        // appendix image section, so omit the marker line itself.
                        lineIndex++
                    }
                    trimmed.startsWith("|") &&
                        lineIndex + 1 < lines.size &&
                        isPdfTableSeparator(lines[lineIndex + 1]) -> {
                        val rows = mutableListOf<List<String>>()
                        rows += parsePdfTableRow(trimmed)
                        lineIndex += 2
                        while (lineIndex < lines.size && lines[lineIndex].trim().startsWith("|")) {
                            rows += parsePdfTableRow(lines[lineIndex])
                            lineIndex++
                        }
                        drawTable(rows)
                    }
                    trimmed.startsWith("# ") -> {
                        val heading = trimmed.removePrefix("# ").trim()
                        if (heading != title) {
                            addVerticalSpace(8f)
                            yPosition = drawText(
                                heading,
                                titlePaint,
                                minimumFollowingSpace = 30f
                            )
                        }
                        lineIndex++
                    }
                    trimmed.startsWith("## ") -> {
                        addVerticalSpace(6f)
                        val followedByTable = lines.drop(lineIndex + 1)
                            .firstOrNull { it.isNotBlank() }
                            ?.trim()
                            ?.startsWith("|") == true
                        val heading = trimmed.removePrefix("## ").trim()
                        if (isStudyReport) {
                            drawStudyHeading(heading, primary = true)
                        } else {
                            yPosition = drawText(
                                heading,
                                headingPaint,
                                minimumFollowingSpace = if (followedByTable) 80f else 28f
                            )
                        }
                        lineIndex++
                    }
                    trimmed.startsWith("### ") -> {
                        addVerticalSpace(4f)
                        val subHeadingPaint = TextPaint(headingPaint).apply { textSize = 14f }
                        val heading = trimmed.removePrefix("### ").trim()
                        if (isStudyReport) {
                            drawStudyHeading(heading, primary = false)
                        } else {
                            yPosition = drawText(
                                heading,
                                subHeadingPaint,
                                minimumFollowingSpace = 26f
                            )
                        }
                        lineIndex++
                    }
                    trimmed.matches(Regex("^#{4,6}\\s+.+$")) -> {
                        addVerticalSpace(3f)
                        val subHeadingPaint = TextPaint(headingPaint).apply { textSize = 12.5f }
                        yPosition = drawText(
                            cleanPdfMarkdown(trimmed),
                            subHeadingPaint,
                            minimumFollowingSpace = 20f
                        )
                        lineIndex++
                    }
                    trimmed.isBlank() -> {
                        addVerticalSpace(8f)
                        lineIndex++
                    }
                    trimmed == "---" -> {
                        ensureSpace(20f)
                        canvas.drawLine(margin, yPosition + 4f, pageWidth - margin, yPosition + 4f, linePaint)
                        yPosition += 16f
                        lineIndex++
                    }
                    isStudyReport && trimmed.startsWith(">") -> {
                        drawStudyLead(trimmed)
                        lineIndex++
                    }
                    isStudyReport && (
                        trimmed.startsWith("**路线**") ||
                            trimmed.startsWith("**同行与讲解**")
                        ) -> {
                        yPosition = drawText(cleanPdfMarkdown(line), studyMetaPaint)
                        lineIndex++
                    }
                    else -> {
                        yPosition = drawText(cleanPdfMarkdown(line), bodyPaint)
                        lineIndex++
                    }
                }
            }
        } else {
            // Structured content
            if (report.summary.isNotBlank()) {
                yPosition = drawText("会议概述", headingPaint, minimumFollowingSpace = 28f)
                yPosition = drawText(report.summary, bodyPaint)
                addVerticalSpace(8f)
            }

            if (report.keyPoints.isNotEmpty()) {
                yPosition = drawText("关键要点", headingPaint, minimumFollowingSpace = 28f)
                report.keyPoints.forEachIndexed { index, point ->
                    yPosition = drawText(ReportDocumentFormatter.numbered(point, index), bodyPaint)
                }
                addVerticalSpace(8f)
            }

            if (report.decisions.isNotEmpty()) {
                yPosition = drawText("决策事项", headingPaint, minimumFollowingSpace = 28f)
                report.decisions.forEachIndexed { index, decision ->
                    yPosition = drawText(ReportDocumentFormatter.numbered(decision, index), bodyPaint)
                }
                addVerticalSpace(8f)
            }

            if (report.tasks.isNotEmpty()) {
                yPosition = drawText("待办任务", headingPaint, minimumFollowingSpace = 40f)
                drawTable(buildList {
                    add(listOf("事项", "负责人", "截止时间", "状态"))
                    report.tasks.forEach { task ->
                        add(
                            listOf(
                                task.content,
                                task.assignee.orEmpty(),
                                task.due.orEmpty(),
                                if (task.completed) "已完成" else "待办"
                            )
                        )
                    }
                })
            }

            if (report.actionItems.isNotEmpty()) {
                yPosition = drawText("行动项", headingPaint, minimumFollowingSpace = 28f)
                report.actionItems.forEachIndexed { index, item ->
                    yPosition = drawText(ReportDocumentFormatter.numbered(item, index), bodyPaint)
                }
            }
        }

        val remainingImageIndexes = images.indices.filterNot(placedImageIndexes::contains)
        if (remainingImageIndexes.isNotEmpty()) {
            if (yPosition > margin + 8f) {
                startNewPage()
            }
            yPosition = drawText(
                reportImageAppendixTitle(report.templateName),
                headingPaint,
                minimumFollowingSpace = 48f
            )
            addVerticalSpace(4f)

            remainingImageIndexes.forEach { imageIndex -> drawReportImage(imageIndex) }
        }

        // Footer
        val footerText = "由 智悟本 自动生成 | ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(report.generatedAt))}"
        ensureSpace(30f)
        canvas.drawLine(margin, yPosition + 4f, pageWidth - margin, yPosition + 4f, linePaint)
        yPosition += 12f
        drawText(footerText, footerPaint)

        finishCurrentPage()

        val fileName = ReportExportFileNaming.build(report, meetingTitle, "pdf")
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val exportFile = File(exportDir, fileName)
        FileOutputStream(exportFile).use { out ->
            document.writeTo(out)
        }
        document.close()

        return exportFile
    }

    private fun isPdfTableSeparator(line: String): Boolean =
        line.trim().trim('|').split('|').all { it.trim().matches(Regex(":?-{3,}:?")) }

    private fun parsePdfTableRow(line: String): List<String> =
        line.trim().trim('|').split('|').map { cleanPdfMarkdown(it.trim()) }

    private fun cleanPdfMarkdown(text: String): String = text
        .replace(Regex("^\\s*#{1,6}\\s+"), "")
        .replace(Regex("^\\s*>+\\s?"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace("`", "")
        .trim()

    private fun String.isMarkdownHeading(): Boolean = matches(Regex("^#{1,6}\\s+.+$"))

    private fun String.isForumRosterHeading(): Boolean {
        if (!isMarkdownHeading()) return false
        val heading = replaceFirst(Regex("^#{1,6}\\s+"), "")
            .replaceFirst(Regex("^\\d+[.、]\\s*"), "")
            .trim()
        return heading.contains("参会") && (heading.contains("名录") || heading.contains("通讯录"))
    }

}
