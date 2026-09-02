package com.oa.automation.infrastructure.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTranscriptAccumulatorTest {
    @Test
    fun `same stream revisions replace preview instead of duplicating it`() {
        val accumulator = StreamingTranscriptAccumulator()

        accumulator.update(update(text = "欢迎来到大佛寺", sessionId = "session-a"))
        val result = accumulator.update(
            update(text = "欢迎来到大佛寺，今天重点考察建筑布局。", sessionId = "session-a")
        )

        assertEquals("欢迎来到大佛寺，今天重点考察建筑布局。", result)
    }

    @Test
    fun `new server session preserves earlier transcript`() {
        val accumulator = StreamingTranscriptAccumulator()

        accumulator.update(update(text = "第一段介绍大佛寺的历史沿革。", sessionId = "session-a"))
        val result = accumulator.update(
            update(text = "第二段继续介绍主要殿宇。", sessionId = "session-b")
        )

        assertTrue(result.contains("第一段介绍大佛寺的历史沿革。"))
        assertTrue(result.contains("第二段继续介绍主要殿宇。"))
    }

    @Test
    fun `material preview regression does not discard spoken content`() {
        val accumulator = StreamingTranscriptAccumulator()
        val complete = "讲解员介绍了寺院中轴线、主要殿宇以及文物保护现状。"

        accumulator.update(update(text = complete, sessionId = "session-a"))
        val result = accumulator.update(update(text = "文物保护", sessionId = "session-a"))

        assertEquals(complete, result)
    }

    @Test
    fun `overlapping stream sessions merge their shared boundary`() {
        assertEquals(
            "参观大佛寺建筑布局与文物保护",
            mergeStreamingTranscriptText("参观大佛寺建筑布局", "建筑布局与文物保护")
        )
    }

    @Test
    fun `speaker segments replace overlapping revisions and render labeled rows`() {
        val accumulator = StreamingTranscriptAccumulator()

        accumulator.update(
            update(
                text = "",
                sessionId = "session-a",
                segments = listOf(
                    segment(0f, 2f, "先介绍行程", speaker = 0),
                    segment(2f, 4f, "补充注意事项", speaker = 1)
                )
            )
        )
        accumulator.update(
            update(
                text = "",
                sessionId = "session-a",
                segments = listOf(
                    segment(0f, 2.2f, "先介绍行程和安排", speaker = 0),
                    segment(2.2f, 4f, "补充注意事项", speaker = 1)
                )
            )
        )

        val rows = accumulator.snapshotSegments()
        assertEquals(2, rows.size)
        assertEquals("先介绍行程和安排", rows[0].text)
        assertEquals(0, rows[0].speaker)
        assertEquals("补充注意事项", rows[1].text)
        assertEquals(1, rows[1].speaker)
    }

    @Test
    fun `adjacent segments by the same speaker are grouped without duplication`() {
        val accumulator = StreamingTranscriptAccumulator()
        accumulator.update(
            update(
                text = "",
                sessionId = "session-a",
                segments = listOf(
                    segment(0f, 1f, "第一句", speaker = 0),
                    segment(1.2f, 2.4f, "第二句", speaker = 0)
                )
            )
        )

        val rows = accumulator.snapshotSegments()
        assertEquals(1, rows.size)
        assertEquals("第一句\n第二句", rows.single().text)
    }

    @Test
    fun `speaker segments survive a websocket session reconnect`() {
        val accumulator = StreamingTranscriptAccumulator()
        accumulator.update(
            update(
                text = "",
                sessionId = "session-a",
                segments = listOf(segment(0f, 2f, "旧会话内容", speaker = 0))
            )
        )
        accumulator.update(
            update(
                text = "",
                sessionId = "session-b",
                segments = listOf(segment(0f, 2f, "新会话内容", speaker = 1))
            )
        )

        val segments = accumulator.snapshotSegments()
        assertEquals(listOf("旧会话内容", "新会话内容"), segments.map { it.text })
        assertEquals(listOf(0f, 2f), segments.map { it.startSeconds })
        assertEquals(listOf(2f, 4f), segments.map { it.endSeconds })
    }

    @Test
    fun `successive websocket reconnects keep extending the segment timeline`() {
        val accumulator = StreamingTranscriptAccumulator()
        accumulator.update(
            update(
                text = "",
                sessionId = "session-a",
                segments = listOf(segment(0f, 1.5f, "第一段", speaker = 0))
            )
        )
        accumulator.update(
            update(
                text = "",
                sessionId = "session-b",
                segments = listOf(segment(0f, 2f, "第二段", speaker = 1))
            )
        )
        accumulator.update(
            update(
                text = "",
                sessionId = "session-c",
                segments = listOf(segment(0f, 1f, "第三段", speaker = 2))
            )
        )

        val segments = accumulator.snapshotSegments()
        assertEquals(listOf(0f, 1.5f, 3.5f), segments.map { it.startSeconds })
        assertEquals(listOf(1.5f, 3.5f, 4.5f), segments.map { it.endSeconds })
    }

    @Test
    fun `client supplied reconnect offset is applied exactly once`() {
        val accumulator = StreamingTranscriptAccumulator()
        accumulator.update(
            update(
                text = "",
                sessionId = "session-a",
                segments = listOf(segment(0f, 2f, "旧会话内容", speaker = 0))
            )
        )
        accumulator.update(
            update(
                text = "",
                sessionId = "session-b",
                timelineOffsetSeconds = 12f,
                segments = listOf(segment(0f, 2f, "重连后的内容", speaker = 1))
            )
        )

        val segments = accumulator.snapshotSegments()
        assertEquals(listOf(0f, 12f), segments.map { it.startSeconds })
        assertEquals(listOf(2f, 14f), segments.map { it.endSeconds })
    }

    private fun update(
        text: String,
        sessionId: String,
        segments: List<StreamingTranscriptSegment> = emptyList(),
        timelineOffsetSeconds: Float = 0f
    ) = StreamingTranscriptUpdate(
        text = text,
        committedText = "",
        previewText = "",
        sessionId = sessionId,
        timelineOffsetSeconds = timelineOffsetSeconds,
        segments = segments
    )

    private fun segment(
        start: Float,
        end: Float,
        text: String,
        speaker: Int
    ) = StreamingTranscriptSegment(start, end, text, speaker, committed = true)
}
