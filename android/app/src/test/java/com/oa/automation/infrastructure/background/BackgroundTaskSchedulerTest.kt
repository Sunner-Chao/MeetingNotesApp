package com.oa.automation.infrastructure.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackgroundTaskSchedulerTest {
    @Test
    fun workNamesAreStableAndSeparatedByOperation() {
        assertEquals(
            "meeting-transcription-meeting-1",
            BackgroundTaskScheduler.transcriptionWorkName("meeting-1")
        )
        assertEquals(
            "meeting-report-meeting-1",
            BackgroundTaskScheduler.reportWorkName("meeting-1")
        )
        assertNotEquals(
            BackgroundTaskScheduler.transcriptionWorkName("meeting-1"),
            BackgroundTaskScheduler.reportWorkName("meeting-1")
        )
    }
}
