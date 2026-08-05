package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportDocumentKindTest {
    @Test
    fun recognizesCurrentAndLegacyConstructionDesignLogs() {
        listOf(
            "工程/建筑 施工/设计日志",
            "工程行业施工日志",
            "建筑专业设计日志"
        ).forEach { templateName ->
            assertEquals(
                ReportDocumentKind.CONSTRUCTION_DESIGN_LOG,
                reportDocumentKind(templateName)
            )
        }
    }

    @Test
    fun keepsSupervisionLogAsMeetingMinutes() {
        assertEquals(
            ReportDocumentKind.MEETING_MINUTES,
            reportDocumentKind("监理会例会日志")
        )
    }
}
