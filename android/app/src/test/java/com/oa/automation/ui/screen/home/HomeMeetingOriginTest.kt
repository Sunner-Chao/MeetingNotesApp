package com.oa.automation.ui.screen.home

import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingOrigin
import com.oa.automation.domain.model.displayTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeMeetingOriginTest {
    @Test
    fun `file import launch creates and resumes the import workflow`() {
        assertEquals(MeetingOrigin.FILE_IMPORT, HomeLaunchAction.OPEN_IMPORT.toMeetingOrigin())
        assertEquals(
            HomeLaunchAction.OPEN_IMPORT,
            Meeting(title = "任意标题", origin = MeetingOrigin.FILE_IMPORT).resumeLaunchAction()
        )
    }

    @Test
    fun `quick and scheduled meetings resume the live workflow`() {
        assertEquals(
            HomeLaunchAction.STANDARD,
            Meeting(title = "快速会议", origin = MeetingOrigin.QUICK).resumeLaunchAction()
        )
        assertEquals(
            HomeLaunchAction.STANDARD,
            Meeting(title = "预定会议", origin = MeetingOrigin.SCHEDULED).resumeLaunchAction()
        )
    }

    @Test
    fun `recent records expose a compact source label for every origin`() {
        assertEquals("快速", meetingOriginLabel(MeetingOrigin.QUICK))
        assertEquals("预定", meetingOriginLabel(MeetingOrigin.SCHEDULED))
        assertEquals("导入", meetingOriginLabel(MeetingOrigin.FILE_IMPORT))
    }

    @Test
    fun `legacy default import title is displayed with current entry name`() {
        val meeting = Meeting(
            title = "资料导入 08-07 18:12",
            origin = MeetingOrigin.FILE_IMPORT
        )

        assertEquals("顷刻成稿 08-07 18:12", meeting.displayTitle())
    }

    @Test
    fun `custom import title is not rewritten`() {
        val meeting = Meeting(
            title = "资料导入后的客户访谈",
            origin = MeetingOrigin.FILE_IMPORT
        )

        assertEquals("资料导入后的客户访谈", meeting.displayTitle())
    }
}
