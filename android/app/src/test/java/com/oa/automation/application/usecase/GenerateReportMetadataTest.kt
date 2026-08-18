package com.oa.automation.application.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerateReportMetadataTest {
    @Test
    fun `summary uses first narrative paragraph instead of title or route metadata`() {
        val content = """
            # 城市更新研学

            从旧厂房走到滨水空间，这段路线让同行者重新理解了更新与运营的关系。

            **路线**：展馆 → 老街 → 河岸
        """.trimIndent()

        assertEquals(
            "从旧厂房走到滨水空间，这段路线让同行者重新理解了更新与运营的关系。",
            extractReportSummary(content)
        )
    }

    @Test
    fun `summary accepts markdown quote opening`() {
        val content = """
            # 研学游记

            > 进入展厅后，最先吸引注意的不是大屏，而是被保留下来的旧结构。
        """.trimIndent()

        assertEquals(
            "进入展厅后，最先吸引注意的不是大屏，而是被保留下来的旧结构。",
            extractReportSummary(content)
        )
    }
}
