package com.oa.automation.locale

import org.junit.Assert.assertEquals
import org.junit.Test

class SimplifiedChineseTextTest {
    @Test
    fun fallbackConvertsCommonMeetingVocabulary() {
        assertEquals(
            "会议记录与价格，负责人确认后进行",
            SimplifiedChineseText.fallback("會議記錄與價格，負責人確認後進行")
        )
    }
}
