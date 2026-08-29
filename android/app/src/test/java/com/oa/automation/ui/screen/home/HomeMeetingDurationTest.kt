package com.oa.automation.ui.screen.home

import com.oa.automation.domain.model.Meeting
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeMeetingDurationTest {
    @Test
    fun `recent meeting duration is shown only in rounded minutes`() {
        assertEquals("0分钟", meetingDurationLabel(Meeting(title = "空记录")))
        assertEquals("0分钟", meetingDurationLabel(Meeting(title = "短记录", durationMs = 1L)))
        assertEquals("1分钟", meetingDurationLabel(Meeting(title = "完整记录", durationMs = 60_001L)))
    }
}
