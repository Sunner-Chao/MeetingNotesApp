package com.oa.automation.infrastructure.export

import com.oa.automation.domain.model.Report
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.SimpleTimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExportFileNamingTest {
    private val utcPlusEight = SimpleTimeZone(8 * 60 * 60 * 1000, "UTC+08:00")

    @Test
    fun usesTemplateMarkdownTitleAndLocalTimeWithoutZoneSuffix() {
        val timestamp = GregorianCalendar(utcPlusEight).apply {
            set(2026, Calendar.JULY, 20, 15, 4, 5)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val report = Report(
            meetingId = "meeting-1",
            templateName = "项目管理",
            rawContent = "# 季度推进会\n\n## 结论"
        )

        val fileName = ReportExportFileNaming.build(
            report = report,
            meetingTitle = "备用标题",
            extension = ".pdf",
            timestamp = timestamp,
            timeZone = utcPlusEight
        )

        assertEquals("项目管理-季度推进会-20260720-150405.pdf", fileName)
        assertFalse(fileName.contains("+0800"))
    }

    @Test
    fun fallsBackToMeetingTitleAndRemovesInvalidCharacters() {
        val report = Report(meetingId = "meeting-2", templateName = "行政/会议")

        val fileName = ReportExportFileNaming.build(
            report = report,
            meetingTitle = "预算:审批?会",
            extension = "docx",
            timestamp = 0L,
            timeZone = SimpleTimeZone(0, "UTC")
        )

        assertEquals("行政-会议-预算-审批-会-19700101-000000.docx", fileName)
    }

    @Test
    fun keepsUtf8FileNameWithinPortableByteLimit() {
        val report = Report(
            meetingId = "meeting-3",
            templateName = "头脑风暴",
            rawContent = "# ${"超长会议标题".repeat(60)}"
        )

        val fileName = ReportExportFileNaming.build(
            report = report,
            meetingTitle = "",
            extension = "txt",
            timestamp = 0L,
            timeZone = SimpleTimeZone(0, "UTC")
        )

        assertTrue(fileName.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue(fileName.endsWith("-19700101-000000.txt"))
    }
}
