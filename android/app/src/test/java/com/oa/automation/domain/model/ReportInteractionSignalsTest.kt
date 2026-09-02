package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportInteractionSignalsTest {
    @Test
    fun extractsExplicitSignalRowsAndIgnoresOtherSections() {
        val items = extractInteractionSignals(
            """
            ## 会议概述
            现场气氛比较紧张。
            ## 可观察互动信号
            | 观察 | 证据 | 来源 |
            | --- | --- | --- |
            | 长停顿 | 约 2 秒 | 00:12 |
            - 说话人 2 多次打断，说话人 1 重复强调交付时间
            ## 风险与阻塞
            不应被识别为互动信号。
            """.trimIndent()
        )

        assertEquals(2, items.size)
        assertEquals("长停顿", items.first().content)
        assertEquals("约 2 秒", items.first().detail)
        assertEquals("00:12", items.first().source)
        assertTrue(items.last().content.contains("多次打断"))
    }

    @Test
    fun ignoresSignalWordsOutsideExplicitSection() {
        assertTrue(extractInteractionSignals("普通正文提到打断和停顿，但没有专门章节").isEmpty())
    }
}
