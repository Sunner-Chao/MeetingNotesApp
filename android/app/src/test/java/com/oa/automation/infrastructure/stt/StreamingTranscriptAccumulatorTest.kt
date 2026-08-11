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

    private fun update(text: String, sessionId: String) = StreamingTranscriptUpdate(
        text = text,
        committedText = "",
        previewText = "",
        sessionId = sessionId
    )
}
