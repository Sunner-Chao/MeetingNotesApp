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
        assertEquals("59分钟", meetingDurationLabel(Meeting(title = "临界", durationMs = 59 * 60_000L)))
    }

    @Test
    fun `an hour or longer reads in hours instead of a large minute count`() {
        assertEquals("1小时", meetingDurationLabel(Meeting(title = "整点", durationMs = 60 * 60_000L)))
        assertEquals(
            "1小时35分钟",
            meetingDurationLabel(Meeting(title = "带余数", durationMs = 95 * 60_000L))
        )
        assertEquals("6小时", meetingDurationLabel(Meeting(title = "长会", durationMs = 360 * 60_000L)))
    }
}
