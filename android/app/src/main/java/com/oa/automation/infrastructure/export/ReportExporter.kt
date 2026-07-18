package com.oa.automation.infrastructure.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.oa.automation.domain.model.Report
import java.io.File
import java.io.FileOutputStream

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
    fun exportToPdf(context: Context, report: Report): File {
        val document = PdfDocument()
        val pageWidth = 595  // A4 width in points (72 dpi)
        val pageHeight = 842 // A4 height in points
        val margin = 40f
        val contentWidth = pageWidth - 2 * margin

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

        val linePaint = Paint().apply {
            color = Color.parseColor("#1a73e8")
            strokeWidth = 2f
        }

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = document.startPage(pageInfo)
        var canvas: Canvas = page.canvas
        var yPosition = margin

        fun startNewPage() {
            document.finishPage(page)
            pageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            page = document.startPage(pageInfo)
            canvas = page.canvas
            yPosition = margin
        }

        fun drawText(text: String, paint: TextPaint, x: Float = margin): Float {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f, 1.2f)
                .build()

            if (yPosition + layout.height > pageHeight - margin) {
                startNewPage()
            }

            canvas.save()
            canvas.translate(x, yPosition)
            layout.draw(canvas)
            canvas.restore()
            yPosition += layout.height + 8f
            return yPosition
        }

        // Title
        val title = if (report.rawContent.isNotBlank()) "会议纪要" else "会议纪要"
        yPosition = drawText(title, titlePaint)
        canvas.drawLine(margin, yPosition, pageWidth - margin, yPosition, linePaint)
        yPosition += 16f

        if (report.rawContent.isNotBlank()) {
            // Parse raw markdown content
            report.rawContent.lines().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("# ") -> {
                        yPosition += 8f
                        yPosition = drawText(trimmed.removePrefix("# ").trim(), titlePaint)
                    }
                    trimmed.startsWith("## ") -> {
                        yPosition += 6f
                        yPosition = drawText(trimmed.removePrefix("## ").trim(), headingPaint)
                    }
                    trimmed.startsWith("### ") -> {
                        yPosition += 4f
                        val subHeadingPaint = TextPaint(headingPaint).apply { textSize = 14f }
                        yPosition = drawText(trimmed.removePrefix("### ").trim(), subHeadingPaint)
                    }
                    trimmed.startsWith("|") -> {
                        yPosition = drawText(trimmed, bodyPaint)
                    }
                    trimmed.isBlank() -> {
                        yPosition += 8f
                    }
                    trimmed == "---" -> {
                        canvas.drawLine(margin, yPosition + 4f, pageWidth - margin, yPosition + 4f, linePaint)
                        yPosition += 16f
                    }
                    else -> {
                        yPosition = drawText(line, bodyPaint)
                    }
                }
            }
        } else {
            // Structured content
            if (report.summary.isNotBlank()) {
                yPosition = drawText("会议概述", headingPaint)
                yPosition = drawText(report.summary, bodyPaint)
                yPosition += 8f
            }

            if (report.keyPoints.isNotEmpty()) {
                yPosition = drawText("关键要点", headingPaint)
                report.keyPoints.forEachIndexed { index, point ->
                    yPosition = drawText("${index + 1}. $point", bodyPaint)
                }
                yPosition += 8f
            }

            if (report.decisions.isNotEmpty()) {
                yPosition = drawText("决策事项", headingPaint)
                report.decisions.forEach { decision ->
                    yPosition = drawText("- $decision", bodyPaint)
                }
                yPosition += 8f
            }

            if (report.tasks.isNotEmpty()) {
                yPosition = drawText("待办任务", headingPaint)
                report.tasks.forEach { task ->
                    val status = if (task.completed) "[完成]" else "[待办]"
                    yPosition = drawText("- $status ${task.content}", bodyPaint)
                }
                yPosition += 8f
            }

            if (report.actionItems.isNotEmpty()) {
                yPosition = drawText("行动项", headingPaint)
                report.actionItems.forEach { item ->
                    yPosition = drawText("- $item", bodyPaint)
                }
            }
        }

        // Footer
        val footerText = "由 智悟本 自动生成 | ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(report.generatedAt))}"
        if (yPosition + 30 > pageHeight - margin) {
            startNewPage()
        }
        canvas.drawLine(margin, yPosition + 4f, pageWidth - margin, yPosition + 4f, linePaint)
        yPosition += 12f
        drawText(footerText, footerPaint)

        document.finishPage(page)

        val fileName = "meeting_report_${System.currentTimeMillis()}.pdf"
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val exportFile = File(exportDir, fileName)
        FileOutputStream(exportFile).use { out ->
            document.writeTo(out)
        }
        document.close()

        return exportFile
    }

}
