package com.oa.automation.infrastructure.llm

import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ReportTemplateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MeetingReportTemplateTest {
    @Test
    fun `queued meeting keeps its template after another meeting changes the global choice`() {
        val current = ReportTemplateConfig(selectedName = "通用会议", content = "general")
        val actual = resolveMeetingReportTemplate(
            "宣贯·落实会", current,
            listOf(PresetReportTemplate(name = "宣贯·落实会", content = "directive"))
        )
        assertEquals("宣贯·落实会", actual.selectedName)
        assertEquals("directive", actual.content)
        assertEquals("通用会议", current.selectedName)
    }

    @Test
    fun `current custom content and legacy meetings keep their configured template`() {
        val custom = ReportTemplateConfig(selectedName = "自定义会议", content = "edited modules")
        assertSame(custom, resolveMeetingReportTemplate("自定义会议", custom, emptyList()))
        assertSame(custom, resolveMeetingReportTemplate(null, custom, emptyList()))
    }

    @Test(expected = IllegalStateException::class)
    fun `missing saved template does not silently generate a different meeting type`() {
        resolveMeetingReportTemplate("missing", ReportTemplateConfig(), emptyList())
    }
}
