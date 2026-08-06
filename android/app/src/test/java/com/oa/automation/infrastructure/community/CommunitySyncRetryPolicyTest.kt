package com.oa.automation.infrastructure.community

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunitySyncRetryPolicyTest {
    @Test
    fun rolloutWriteClosureIsVisibleFailureInsteadOfRetry() {
        assertFalse(isCommunitySyncRetryable("社区写入暂时关闭，本地内容已保留"))
        assertFalse(isCommunitySyncRetryable("HTTP 503：社区写入暂时关闭"))
    }

    @Test
    fun transientNetworkAndServiceFailuresRemainRetryable() {
        assertTrue(isCommunitySyncRetryable("network connection timeout"))
        assertTrue(isCommunitySyncRetryable("HTTP 503"))
        assertTrue(isCommunitySyncRetryable("账户服务暂时不可用"))
    }
}
