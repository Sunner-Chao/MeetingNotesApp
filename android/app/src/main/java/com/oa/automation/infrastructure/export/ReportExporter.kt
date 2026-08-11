package com.oa.automation.infrastructure.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.Report
import java.io.File
import java.io.FileOutputStream

private fun String.usesStudyReportStyle(): Boolean =
    contains("研学") || contains("参观考察") || contains("游记") || contains("文旅")

private val photoAnchorPattern = Regex("^\\s*\\[照片\\s*[:：]\\s*图\\s*(\\d+)]\\s*$")

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
        meetingTitle: String = ""
    ): File {
        val isStudyReport = report.templateName.usesStudyReportStyle()
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

        val titlePaint = TextPaint().apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.parseColor("#1a73e8")
            isAntiAlias = true
        }

        val headingPaint = TextPaint().apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.parseColor("#333333")
            isAntiAlias = true
        }

        val bodyPaint = TextPaint().apply {
            textSize = 12f
            typeface = Typeface.DEFAULT
            color = Color.parseColor("#555555")
            isAntiAlias = true
        }

        val footerPaint = TextPaint().apply {
            textSize = 10f
            typeface = Typeface.DEFAULT
            color = Color.parseColor("#999999")
            isAntiAlias = true
        }

        val pageNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f
            color = Color.parseColor("#7B8791")
            textAlign = Paint.Align.RIGHT
        }

        val linePaint = Paint().apply {
            color = Color.parseColor("#1a73e8")
            strokeWidth = 2f
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
            width: Int = contentWidth.toInt()
        ): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(alignment)
            .setLineSpacing(4f, 1.2f)
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
                color = Color.WHITE
            }
            val cellTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSize
                typeface = Typeface.DEFAULT
                color = Color.parseColor("#24323D")
            }
            val headerBackgroundPaint = Paint().apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#1A73E8")
            }
            val alternateBackgroundPaint = Paint().apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#F4F7FA")
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

        fun drawReportImage(index: Int) {
            val image = images.getOrNull(index) ?: return
            val bitmap = checkNotNull(
                BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
            ) { "无法解码图片：${image.caption}" }
            try {
                val fallbackCaption = if (isStudyReport) "照片" else "会议图片"
                val caption = "图 ${index + 1}：${image.caption.ifBlank { fallbackCaption }}"
                val captionLayout = createTextLayout(
                    caption,
                    footerPaint,
                    Layout.Alignment.ALIGN_CENTER
                )
                val maxImageHeight = contentBottom - margin - captionLayout.height - 38f
                val scale = minOf(
                    1f,
                    contentWidth / bitmap.width,
                    maxImageHeight / bitmap.height
                )
                val imageWidth = bitmap.width * scale
                val imageHeight = bitmap.height * scale
                val blockHeight = imageHeight + captionLayout.height + 24f

                if (yPosition + blockHeight > contentBottom) {
                    startNewPage()
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
                canvas.translate(margin, yPosition)
                captionLayout.draw(canvas)
                canvas.restore()
                yPosition += captionLayout.height + 16f
                placedImageIndexes += index
            } finally {
                bitmap.recycle()
            }
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

        if (report.rawContent.isNotBlank()) {
            // Parse raw markdown content
            val lines = ReportDocumentFormatter.normalizeLists(report.rawContent).lines()
            var lineIndex = 0
            while (lineIndex < lines.size) {
                val line = lines[lineIndex]
                val trimmed = line.trim()
                val photoAnchor = photoAnchorPattern.matchEntire(trimmed)
                when {
                    photoAnchor != null -> {
                        val imageIndex = photoAnchor.groupValues[1].toIntOrNull()?.minus(1)
                        if (imageIndex != null && imageIndex !in placedImageIndexes) {
                            drawReportImage(imageIndex)
                        }
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
                        yPosition = drawText(
                            trimmed.removePrefix("## ").trim(),
                            headingPaint,
                            minimumFollowingSpace = if (followedByTable) 80f else 28f
                        )
                        lineIndex++
                    }
                    trimmed.startsWith("### ") -> {
                        addVerticalSpace(4f)
                        val subHeadingPaint = TextPaint(headingPaint).apply { textSize = 14f }
                        yPosition = drawText(
                            trimmed.removePrefix("### ").trim(),
                            subHeadingPaint,
                            minimumFollowingSpace = 26f
                        )
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
                if (isStudyReport) "照片集锦" else "会议图片",
                headingPaint,
                minimumFollowingSpace = 48f
            )
            addVerticalSpace(4f)

            remainingImageIndexes.forEach(::drawReportImage)
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

}
