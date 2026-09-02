package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportInsightsTest {
    @Test
    fun extractsOnlyExplicitRiskSectionRows() {
        val items = extractRiskItems(
            """
            ## 会议概述
            进度存在不确定性。
            ## 风险与阻塞
            | 编号 | 风险内容 | 说明 | 状态 |
            | --- | --- | --- | --- |
            | R1 | 接口联调延期 | 等待依赖方 | 待确认 |
            ## 后续行动
            不应被识别为风险。
            """.trimIndent()
        )

        assertEquals(1, items.size)
        assertEquals("接口联调延期", items.single().content)
        assertTrue(items.single().detail.orEmpty().contains("等待依赖方"))
    }

    @Test
    fun ignoresRiskWordsOutsideExplicitSection() {
        assertTrue(extractRiskItems("普通正文提到风险，但没有风险章节").isEmpty())
    }
}
