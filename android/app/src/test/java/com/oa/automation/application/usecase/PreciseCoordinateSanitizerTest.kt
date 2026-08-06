package com.oa.automation.application.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreciseCoordinateSanitizerTest {
    @Test
    fun `precise decimal coordinate pairs are removed from publish snapshots`() {
        val result = PreciseCoordinateSanitizer.sanitize(
            "展馆定位 31.2304, 121.4737；本次共参访 12 个点位。备用位置 30.12345，120.98765。"
        )

        assertEquals(2, result.redactedCount)
        assertFalse(result.content.contains("31.2304"))
        assertFalse(result.content.contains("120.98765"))
        assertTrue(result.content.contains("12 个点位"))
        assertEquals(2, result.content.windowed("[精确位置已移除]".length).count { it == "[精确位置已移除]" })
    }

    @Test
    fun `ordinary decimal measurements are not treated as coordinate pairs`() {
        val result = PreciseCoordinateSanitizer.sanitize("能耗下降 12.500%，面积 3.250 平方米。")

        assertEquals(0, result.redactedCount)
        assertTrue(result.content.contains("12.500%"))
    }
}
