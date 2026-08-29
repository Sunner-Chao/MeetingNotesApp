package com.oa.automation.infrastructure.account

import com.alipay.sdk.app.EnvUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class AlipaySdkClientTest {

    @Test
    fun `sandbox environment targets the sandbox gateway`() {
        assertEquals(
            EnvUtils.EnvEnum.SANDBOX,
            AlipaySdkClient.resolveEnvironment("sandbox")
        )
    }

    @Test
    fun `environment matching ignores case and surrounding spaces`() {
        assertEquals(
            EnvUtils.EnvEnum.SANDBOX,
            AlipaySdkClient.resolveEnvironment("  SandBox ")
        )
    }

    @Test
    fun `production environment targets the online gateway`() {
        assertEquals(
            EnvUtils.EnvEnum.ONLINE,
            AlipaySdkClient.resolveEnvironment("production")
        )
    }

    @Test
    fun `pre sandbox environment targets the pre sandbox gateway`() {
        assertEquals(
            EnvUtils.EnvEnum.PRE_SANDBOX,
            AlipaySdkClient.resolveEnvironment("pre_sandbox")
        )
    }

    @Test
    fun `unknown or blank environment never falls back to sandbox`() {
        listOf("", "   ", "staging", "unknown").forEach { value ->
            assertEquals(
                "environment=$value must stay on the production gateway",
                EnvUtils.EnvEnum.ONLINE,
                AlipaySdkClient.resolveEnvironment(value)
            )
        }
    }
}
