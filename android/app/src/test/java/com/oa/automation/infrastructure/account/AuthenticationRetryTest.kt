package com.oa.automation.infrastructure.account

import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationRetryTest {
    @Test
    fun recognizesNestedServiceTokenFailures() {
        val root = IllegalStateException("STT 请求失败").apply {
            addSuppressed(IOException("Agent 访问令牌无效或已过期"))
        }
        assertTrue(root.isAuthenticationFailure())
    }

    @Test
    fun ignoresQuotaAndNetworkFailures() {
        assertFalse(IllegalStateException("STT 服务繁忙，请稍后重试").isAuthenticationFailure())
        assertFalse(IllegalStateException("网络连接已断开").isAuthenticationFailure())
    }

    @Test
    fun readsExpiryFromAccountSttTokenWithoutVerifyingItsSignature() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"exp\":1893456000,\"sub\":\"user-1\"}".toByteArray())
        val token = "mn_stt_user_v1.$payload.signature"
        assertEquals(1893456000L, sttTokenExpiresAt(token))
    }

    @Test
    fun malformedOrLegacySttTokensRequireRefresh() {
        assertNull(sttTokenExpiresAt(null))
        assertNull(sttTokenExpiresAt("legacy-token"))
        assertNull(sttTokenExpiresAt("mn_stt_user_v1.not-base64.signature"))
    }
}
