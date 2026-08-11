package com.oa.automation.ui.screen.community

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class CommunityErrorMessageTest {
    @Test
    fun `gateway credentials never leak as raw English`() {
        assertEquals(
            "社区服务正在更新，请稍后重试",
            communityErrorMessage(IOException("Missing or invalid API credentials"))
        )
    }

    @Test
    fun `network failures become actionable Chinese messages`() {
        assertEquals(
            "连接超时，请检查网络后重试",
            communityErrorMessage(IOException("Read timed out"))
        )
        assertEquals(
            "暂时无法连接研学社区",
            communityErrorMessage(IOException("Unable to resolve host"))
        )
    }
}
