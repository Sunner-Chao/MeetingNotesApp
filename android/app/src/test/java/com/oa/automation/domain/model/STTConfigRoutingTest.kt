package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class STTConfigRoutingTest {
    @Test
    fun `audio enhancement is enabled by default`() {
        assertTrue(STTConfig.DEFAULT.audioEnhancementEnabled)
    }

    @Test
    fun `local and Tencent engines resolve independent service roots`() {
        val config = STTConfig(
            localEndpoint = "http://10.0.2.2:8888",
            cloudEndpoint = "https://118.25.43.185/stt-cloud"
        )

        assertEquals(
            "http://10.0.2.2:8888",
            config.serviceEndpointFor(STTEngineType.FASTER_WHISPER)
        )
        assertEquals(
            "https://118.25.43.185/stt-cloud",
            config.serviceEndpointFor(STTEngineType.TENCENT_HYBRID)
        )
    }
}
