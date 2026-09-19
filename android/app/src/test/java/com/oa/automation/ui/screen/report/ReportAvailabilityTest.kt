package com.oa.automation.ui.screen.report

import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.ReportTemplateConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportAvailabilityTest {
    private val completed = ReportUiState(
        report = Report(meetingId = "meeting", templateName = "宣贯·落实会"),
        reportTemplate = ReportTemplateConfig(selectedName = "宣贯·落实会")
    )

    @Test
    fun onlyMatchingCompletedReportCanBeDisplayedOrExported() {
        assertTrue(completed.canUseReport)
        assertFalse(completed.copy(report = null).canUseReport)
        assertFalse(completed.copy(reportTemplate = ReportTemplateConfig(selectedName = "博弈·洽谈会")).canUseReport)
    }

    @Test
    fun cachedBodyIsNeverAvailableWhileLoadingOrGenerating() {
        assertFalse(completed.copy(isLoading = true).canUseReport)
        assertFalse(completed.copy(isGenerating = true).canUseReport)
    }

    @Test
    fun failureAndCancellationDoNotFallBackToCachedBody() {
        assertFalse(completed.copy(error = "请求失败").canUseReport)
        assertFalse(completed.copy(generationCancelled = true).canUseReport)
    }
}
