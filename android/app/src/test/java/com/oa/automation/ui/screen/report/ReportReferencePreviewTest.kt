package com.oa.automation.ui.screen.report

import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.Task
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportReferencePreviewTest {
    @Test
    fun fullMarkdownPreviewKeepsSectionsTablesAndOmitsPhotoMarkers() {
        val preview = reportPreviewDocument(
            Report(
                meetingId = "meeting-1",
                templateName = "研学考察",
                rawContent = """
                    # 现场纪要
                    ## 观察记录
                    第一段完整记录。
                    [照片：图 1｜现场照片]
                    | 事项 | 状态 |
                    | --- | --- |
                    | 完成走访 | 已完成 |
                """.trimIndent()
            )
        )

        assertTrue(preview.contains("现场纪要"))
        assertTrue(preview.contains("观察记录"))
        assertTrue(preview.contains("第一段完整记录"))
        assertTrue(preview.contains("完成走访"))
        assertTrue(preview.contains("已完成"))
        assertFalse(preview.contains("[照片"))
    }

    @Test
    fun structuredPreviewIncludesAllContentGroups() {
        val preview = reportPreviewDocument(
            Report(
                meetingId = "meeting-2",
                summary = "完整概述",
                keyPoints = listOf("关键点"),
                decisions = listOf("已决定"),
                tasks = listOf(Task("跟进任务", "小孙", "周五")),
                actionItems = listOf("发送纪要")
            )
        )

        assertTrue(preview.contains("完整概述"))
        assertTrue(preview.contains("关键点"))
        assertTrue(preview.contains("已决定"))
        assertTrue(preview.contains("跟进任务"))
        assertTrue(preview.contains("小孙"))
        assertTrue(preview.contains("发送纪要"))
    }

    @Test
    fun structuredPreviewIncludesTaskPriority() {
        val preview = reportPreviewDocument(
            Report(
                meetingId = "priority",
                tasks = listOf(Task("完成复核", "小孙", "周五", priority = "高"))
            )
        )

        assertTrue(preview.contains("高"))
    }
}
