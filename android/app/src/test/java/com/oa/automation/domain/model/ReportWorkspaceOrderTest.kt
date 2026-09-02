package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportWorkspaceOrderTest {
    @Test
    fun normalizesPersistedOrderWithoutDroppingNewBlocks() {
        val available = listOf("audio", "images", "report")
        assertEquals(
            listOf("report", "audio", "images"),
            normalizeReportWorkspaceOrder(listOf("report", "missing", "report"), available)
        )
    }

    @Test
    fun hiddenBlocksAreDistinctAndNeverHideReportBody() {
        val available = listOf("audio", "images", "report")
        assertEquals(
            listOf("images", "audio"),
            normalizeHiddenReportWorkspaceBlocks(
                listOf("images", "report", "images", "missing", "audio"),
                available
            )
        )
    }

    @Test
    fun hiddenBlocksRespectAvailableBlocks() {
        assertEquals(
            listOf("audio"),
            normalizeHiddenReportWorkspaceBlocks(
                listOf("audio", "participants"),
                available = listOf("audio", "images")
            )
        )
    }
}
