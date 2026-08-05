package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportTitleResolverTest {
    @Test
    fun markdownTitleReplacesQuickMeetingPlaceholder() {
        val report = Report(
            meetingId = "meeting-1",
            rawContent = "# 智慧工地季度推进会\n\n## 会议结论"
        )

        assertEquals(
            "智慧工地季度推进会",
            ReportTitleResolver.resolve(report, "快速会议 07-23 15:00")
        )
    }

    @Test
    fun topicSectionIsUsedWhenTopHeadingIsGeneric() {
        val report = Report(
            meetingId = "meeting-2",
            rawContent = "# 会议纪要\n\n## 会议主题\n工程进度与安全整改协调会\n\n## 会议结论"
        )

        assertEquals(
            "工程进度与安全整改协调会",
            ReportTitleResolver.resolve(report, "快速录音")
        )
    }

    @Test
    fun placeholderMeetingNameIsNotUsedAsFallbackTitle() {
        val report = Report(
            meetingId = "meeting-3",
            templateName = "行政会议"
        )

        assertEquals(
            "行政会议纪要",
            ReportTitleResolver.resolve(report, "快速会议 07-23 15:00")
        )
    }

    @Test
    fun userNamedMeetingRemainsTheLastMeaningfulFallback() {
        val report = Report(meetingId = "meeting-4")

        assertEquals(
            "年度预算评审会",
            ReportTitleResolver.resolve(report, "年度预算评审会")
        )
    }
}
