package com.oa.automation.infrastructure.export

import com.oa.automation.domain.model.Report

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

            // Summary Section
            appendLine("## 📋 会议概述")
            appendLine()
            appendLine(report.summary.ifEmpty { "暂无概述" })
            appendLine()

            // Key Points Section
            if (report.keyPoints.isNotEmpty()) {
                appendLine("## 📌 关键要点")
                appendLine()
                report.keyPoints.forEachIndexed { index, point ->
                    appendLine("${index + 1}. $point")
                }
                appendLine()
            }

            // Decisions Section
            if (report.decisions.isNotEmpty()) {
                appendLine("## ✅ 决策事项")
                appendLine()
                report.decisions.forEach { decision ->
                    appendLine("- $decision")
                }
                appendLine()
            }

            // Tasks Section
            if (report.tasks.isNotEmpty()) {
                appendLine("## 📝 待办任务")
                appendLine()
                report.tasks.forEachIndexed { index, task ->
                    val checkbox = if (task.completed) "[x]" else "[ ]"
                    val meta = listOfNotNull(
                        task.assignee?.let { "👤 $it" },
                        task.due?.let { "📅 $it" }
                    ).joinToString(" | ")
                    appendLine("- $checkbox ${task.content}")
                    if (meta.isNotEmpty()) {
                        appendLine("  $meta")
                    }
                }
                appendLine()
            }

            // Action Items Section
            if (report.actionItems.isNotEmpty()) {
                appendLine("## 🚀 行动项")
                appendLine()
                report.actionItems.forEach { item ->
                    appendLine("- $item")
                }
                appendLine()
            }

            // Footer
            appendLine("---")
            appendLine()
            appendLine("*由 OA助手 自动生成 | ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(report.generatedAt))}*")
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
}
