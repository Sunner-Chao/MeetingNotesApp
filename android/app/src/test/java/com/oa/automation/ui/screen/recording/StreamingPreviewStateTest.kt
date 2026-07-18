package com.oa.automation.ui.screen.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
