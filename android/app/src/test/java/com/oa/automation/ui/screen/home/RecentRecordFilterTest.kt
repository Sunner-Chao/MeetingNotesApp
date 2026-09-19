package com.oa.automation.ui.screen.home

import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentRecordFilterTest {
    @Test
    fun `search combines with type and completion filters`() {
        val records = listOf(
            record("match", "教育论坛", MeetingOrigin.FILE_IMPORT, done = true),
            record("pending", "教育论坛", MeetingOrigin.FILE_IMPORT),
            record("live", "教育论坛", done = true),
            record("other", "研学考察", MeetingOrigin.FILE_IMPORT, done = true)
        )

        val filtered = applyRecentRecordFilter(
            records,
            RecentRecordFilter(type = RecentRecordType.IMPORT, status = RecentRecordStatus.DONE),
            query = "论坛"
        )

        assertEquals(listOf("match"), filtered.map { it.meeting.id })
    }

    @Test
    fun `search handles case whitespace and multiple terms`() {
        val records = listOf(
            record("match", "OpenAI 论坛纪要"),
            record("one-term", "OpenAI 产品讨论")
        )

        val filtered = applyRecentRecordFilter(records, RecentRecordFilter(), query = "  openai\t 论坛  ")

        assertEquals(listOf("match"), filtered.map { it.meeting.id })
        assertEquals(2, applyRecentRecordFilter(records, RecentRecordFilter(), query = " \t ").size)
    }

    @Test
    fun `legacy import records are found using their displayed names`() {
        val records = listOf(record("legacy", "资料导入 08-07 18:12", MeetingOrigin.FILE_IMPORT))

        val filtered = applyRecentRecordFilter(records, RecentRecordFilter(), query = "顷刻成稿 08-07")

        assertEquals(listOf("legacy"), filtered.map { it.meeting.id })
    }

    @Test
    fun `active recording stays first despite mismatched search and filters`() {
        val records = listOf(
            record("finished", "论坛", MeetingOrigin.FILE_IMPORT, done = true, createdAt = 20),
            record("active", "研学考察", createdAt = 1),
            record("hidden", "研学考察", createdAt = 30)
        )

        val filtered = applyRecentRecordFilter(
            records,
            RecentRecordFilter(type = RecentRecordType.IMPORT, status = RecentRecordStatus.DONE),
            activeRecordingMeetingId = "active",
            query = "论坛"
        )

        assertEquals(listOf("active", "finished"), filtered.map { it.meeting.id })
    }

    @Test
    fun `complete list retains records beyond the three item preview`() {
        val records = (1..30).map { record("meeting-$it", "会议 $it", createdAt = it.toLong()) }

        val filtered = applyRecentRecordFilter(records, RecentRecordFilter())

        assertEquals(30, filtered.size)
        assertEquals("meeting-30", filtered.first().meeting.id)
        assertEquals("meeting-1", filtered.last().meeting.id)
        assertTrue(applyRecentRecordFilter(records, RecentRecordFilter(), query = "不存在").isEmpty())
    }

    private fun record(
        id: String,
        title: String,
        origin: MeetingOrigin = MeetingOrigin.QUICK,
        done: Boolean = false,
        createdAt: Long = 0
    ) = MeetingWithReport(
        meeting = Meeting(id = id, title = title, origin = origin, createdAt = createdAt),
        hasReport = done
    )
}
