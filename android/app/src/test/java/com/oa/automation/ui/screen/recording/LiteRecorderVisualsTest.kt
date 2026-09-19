package com.oa.automation.ui.screen.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiteRecorderVisualsTest {
    @Test
    fun liteRailKeepsSixReferenceBookmarksAndExpansionLabels() {
        assertEquals(
            listOf("宣贯", "推演", "共创", "洽谈", "复盘", "自定义"),
            LiteRecordingTabs.map { it.label }
        )
        assertEquals(
            listOf("落实会", "进度会", "头脑风暴", "博弈会", "分析会", "自定义"),
            LiteRecordingTabs.map { it.expandedLabel }
        )
        assertFalse(LiteRecordingTabs.any { it.label == "敏捷" || it.label == "论坛" })
    }
}
