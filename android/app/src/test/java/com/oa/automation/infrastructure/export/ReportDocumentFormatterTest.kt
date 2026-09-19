package com.oa.automation.infrastructure.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDocumentFormatterTest {
    @Test
    fun convertsMarkdownListsToChineseParenthesizedNumbers() {
        val formatted = ReportDocumentFormatter.normalizeLists(
            """
                ## 结论
                - 第一项
                - 第二项

                1. 第三项
                2) 第四项
                | --- | --- |
            """.trimIndent()
        )

        assertTrue(formatted.contains("（1）第一项"))
        assertTrue(formatted.contains("（2）第二项"))
        assertTrue(formatted.contains("（1）第三项"))
        assertTrue(formatted.contains("（2）第四项"))
        assertTrue(formatted.contains("| --- | --- |"))
        assertFalse(formatted.lines().any { it.startsWith("- ") })
        assertEquals("（3）事项", ReportDocumentFormatter.numbered("事项", 2))
    }

    @Test
    fun normalizesLegacyProjectBacklogHeadingsForAllExportFormats() {
        val formatted = ReportDocumentFormatter.normalizeProjectManagementSections(
            """
                ## 8. 后续沉淀事项
                | 事项 | 状态 |
                | --- | --- |
                | 验证同步 | 待研究 |

                ## 9. 风险提醒
            """.trimIndent()
        )

        assertTrue(formatted.contains("## 8. 后续研究与储备事项"))
        assertTrue(formatted.contains("验证同步"))
        assertFalse(formatted.contains("后续沉淀事项"))
    }

    @Test
    fun removesTemplateGuidanceWithoutRemovingEvidenceNotes() {
        val formatted = ReportDocumentFormatter.stripTemplateGuidance(
            """
                # 会议主题

                > 适用场景：内部模板说明
                >
                > 写作定位：内部模板说明

                ## 事实记录

                > 记录说明：现场发生了设备报警。
                > 只记录原始音频或转写中可核对的现象，不写心理判断。
            """.trimIndent()
        )

        assertFalse(formatted.contains("适用场景："))
        assertFalse(formatted.contains("写作定位："))
        assertFalse(formatted.contains("只记录原始音频"))
        assertTrue(formatted.contains("记录说明：现场发生了设备报警。"))
    }
}
