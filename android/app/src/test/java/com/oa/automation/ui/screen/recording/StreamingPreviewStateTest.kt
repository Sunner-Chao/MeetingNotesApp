package com.oa.automation.ui.screen.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingPreviewStateTest {
    @Test
    fun previewChangeRefreshesUiWhenCombinedTextIsUnchanged() {
        assertTrue(
            hasStreamingPreviewChanged(
                text = "会议内容",
                committedText = "",
                previewText = "新的临时预览",
                latestText = "会议内容",
                latestCommittedText = "",
                latestPreviewText = "旧的临时预览"
            )
        )
    }

    @Test
    fun identicalOrEmptyPayloadDoesNotRefreshUi() {
        assertFalse(
            hasStreamingPreviewChanged(
                text = "会议内容",
                committedText = "已确认",
                previewText = "临时预览",
                latestText = "会议内容",
                latestCommittedText = "已确认",
                latestPreviewText = "临时预览"
            )
        )
        assertFalse(hasStreamingPreviewChanged("", "", "", "", "", ""))
    }

    @Test
    fun reconnectPreservesPreviousSessionBeforeNewPreviewStarts() {
        assertTrue(shouldRollStreamingSession("session-a", "session-b"))
        assertFalse(shouldRollStreamingSession("session-a", "session-a"))
        assertEquals(
            "第一段已经识别",
            preserveStreamingSessionText(
                preservedText = "",
                latestText = "第一段已经识别",
                committedText = "",
                previewText = "第一段"
            )
        )
        assertEquals(
            "第一段已经识别\n第二段继续",
            preserveStreamingSessionText(
                preservedText = "第一段已经识别",
                latestText = "第二段继续",
                committedText = "第二段继续",
                previewText = ""
            )
        )
    }

    @Test
    fun sameSessionStablePromotionDoesNotResetAccumulatedPreview() {
        val previous = "第一部分已经讨论完成，第二部分继续补充意见，第三部分正在形成结论。"
        val preserved = preserveStreamingPreviewRegression(
            preservedText = "",
            previousSessionText = previous,
            incomingSessionText = "形成结论。"
        )

        assertEquals(previous, preserved)
        assertTrue(isSevereStreamingPreviewRegression(previous, "形成结论。"))
    }

    @Test
    fun smallPreviewRevisionRemainsRevisable() {
        val previous = "会议决定下周三复查项目进度。"

        assertEquals(
            "",
            preserveStreamingPreviewRegression(
                preservedText = "",
                previousSessionText = previous,
                incomingSessionText = "会议决定下周复查项目进度。"
            )
        )
        assertFalse(isSevereStreamingPreviewRegression(previous, "会议决定下周复查项目进度。"))
    }

    @Test
    fun longCumulativeTranscriptMergesWithoutScanningEveryPossibleSubstring() {
        val shared = "项目风险已经确认，进入下一项议题。"
        val base = "甲".repeat(8_000) + shared
        val next = shared + "乙".repeat(8_000)

        assertEquals(shared.length, longestTranscriptOverlap(base, next))
        assertEquals("甲".repeat(8_000) + shared + "乙".repeat(8_000), mergeTranscriptText(base, next))
    }

    @Test
    fun previewWindowKeepsFullTextSmallAndOnlyTailsLongText() {
        assertEquals("完整内容", transcriptPreviewWindow("完整内容", 20))

        val transcript = "开场内容。" + "后续讨论".repeat(1_000)
        val preview = transcriptPreviewWindow(transcript, 800)

        assertTrue(preview.startsWith("…\n"))
        assertTrue(preview.length <= 802)
        assertTrue(transcript.endsWith(preview.removePrefix("…\n")))
    }
}
