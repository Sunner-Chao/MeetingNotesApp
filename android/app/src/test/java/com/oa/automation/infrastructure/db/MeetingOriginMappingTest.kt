package com.oa.automation.infrastructure.db

import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingOriginMappingTest {
    @Test
    fun `meeting origin survives entity round trip`() {
        val meeting = Meeting(
            id = "meeting-import",
            title = "已改名的考察资料",
            origin = MeetingOrigin.FILE_IMPORT,
            selectedTemplateName = "研学考察"
        )

        assertEquals(meeting, meeting.toEntity().toDomain())
    }

    @Test
    fun `unknown persisted origin falls back to quick meeting`() {
        val entity = MeetingEntity(
            id = "meeting-legacy",
            title = "旧会议",
            createdAt = 1L,
            durationMs = 0L,
            audioFilePath = null,
            origin = "UNKNOWN"
        )

        assertEquals(MeetingOrigin.QUICK, entity.toDomain().origin)
    }
}
