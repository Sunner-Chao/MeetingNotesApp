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
}
