package com.oa.automation.ui.screen.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRecorderVisualsTest {
    @Test
    fun liteRailKeepsIndependentCustomAndListeningBookmarks() {
        assertEquals(
            listOf("宣贯", "推演", "共创", "洽谈", "复盘", "自定义", "聆听"),
            LiteRecordingTabs.map { it.label }
        )
        assertEquals(
            listOf("落实会", "进度会", "头脑风暴", "博弈会", "分析会", "自定义", "策划会"),
            LiteRecordingTabs.map { it.expandedLabel }
        )
        assertFalse(LiteRecordingTabs.any { it.label == "敏捷" || it.label == "论坛" })
        assertTrue(LiteRecordingTabs.any { it.templateName == "自定义会议" })
        assertTrue(LiteRecordingTabs.any { it.templateName == "聆听·策划会" })
    }
}
