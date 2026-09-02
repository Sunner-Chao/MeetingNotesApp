package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptRenderingTest {
    @Test
    fun `renders speaker name before structured transcript content`() {
        val transcript = Transcript(
            meetingId = "meeting-1",
            speakerName = "说话人 1",
            content = "介绍今天的安排。"
        )

        assertEquals("说话人 1：介绍今天的安排。", transcript.renderedContent())
    }

    @Test
    fun `does not duplicate a legacy speaker prefix`() {
        val transcript = Transcript(
            meetingId = "meeting-1",
            speakerName = "说话人 1",
            content = "说话人 1：介绍今天的安排。"
        )

        assertEquals("说话人 1：介绍今天的安排。", transcript.renderedContent())
    }

    @Test
    fun `keeps ordinary transcript content unchanged`() {
        val transcript = Transcript(meetingId = "meeting-1", content = "普通会议内容")

        assertEquals("普通会议内容", transcript.renderedContent())
    }

    @Test
    fun `renders a speaker label on its own row before multiline content`() {
        val transcript = Transcript(
            meetingId = "meeting-1",
            speakerName = "说话人 2",
            content = "先说明路线。\n再补充集合时间。"
        )

        assertEquals(
            "说话人 2：先说明路线。\n再补充集合时间。",
            transcript.renderedContent()
        )
    }

    @Test
    fun `does not duplicate bracketed legacy speaker prefix`() {
        val transcript = Transcript(
            meetingId = "meeting-1",
            speakerName = "说话人 2",
            content = "[说话人 2] 先说明路线。"
        )

        assertEquals("[说话人 2] 先说明路线。", transcript.renderedContent())
    }

    @Test
    fun `does not duplicate a speaker prefix without punctuation`() {
        val transcript = Transcript(
            meetingId = "meeting-1",
            speakerName = "说话人 2",
            content = "说话人 2 先说明路线。"
        )

        assertEquals("说话人 2 先说明路线。", transcript.renderedContent())
    }
}
