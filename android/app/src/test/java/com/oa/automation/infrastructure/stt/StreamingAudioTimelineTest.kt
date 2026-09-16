package com.oa.automation.infrastructure.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAudioTimelineTest {
    @Test
    fun `revisable preview becomes timed paragraphs without losing text`() {
        val timeline = StreamingAudioTimeline()
        timeline.update("第一段说明。", 3f)
        val rows = timeline.update("第一段说明。第二段补充。", 12f)
        assertEquals(listOf("第一段说明。", "第二段补充。"), rows.map { it.text })
        assertEquals(listOf(0f, 3f), rows.map { it.startSeconds })
        assertTrue(rows[0].committed)
    }

    @Test
    fun `a recognizer revision replaces the active paragraph`() {
        val timeline = StreamingAudioTimeline()
        timeline.update("今天讨论", 3f)
        val rows = timeline.update("今天讨论项目进度", 4f)
        assertEquals(1, rows.size)
        assertEquals("今天讨论项目进度", rows.single().text)
    }
}
