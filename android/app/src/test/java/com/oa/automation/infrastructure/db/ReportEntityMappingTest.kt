package com.oa.automation.infrastructure.db

import com.oa.automation.domain.model.Report
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportEntityMappingTest {
    @Test
    fun workspaceLayoutRoundTripsIncludingHiddenBlocks() {
        val report = Report(
            id = "report-1",
            meetingId = "meeting-1",
            summary = "summary",
            workspaceBlockOrder = listOf("report", "images", "audio"),
            hiddenWorkspaceBlocks = listOf("audio")
        )

        assertEquals(report, report.toEntity().toDomain())
    }
}
