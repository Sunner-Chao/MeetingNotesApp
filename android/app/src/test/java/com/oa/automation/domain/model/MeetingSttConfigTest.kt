package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingSttConfigTest {
    @Test
    fun `queued import retains local route after another meeting selects cloud`() {
        val global = STTConfig(engineType = STTEngineType.TENCENT_HYBRID, apiToken = "test-session")
        val actual = global.forMeetingEngine(STTEngineType.FASTER_WHISPER.name, true)
        assertEquals(STTEngineType.FASTER_WHISPER, actual.engineType)
        assertEquals("test-session", actual.apiToken)
    }

    @Test
    fun `saved cloud choice and cloud only edition use configured cloud tier`() {
        val config = STTConfig(tencentAsrTier = TencentAsrTier.PRECISION_PAID)
        assertEquals(STTEngineType.TENCENT_HYBRID,
            config.forMeetingEngine(STTEngineType.TENCENT_HYBRID.name, true).engineType)
        val disabled = config.forMeetingEngine(STTEngineType.FASTER_WHISPER.name, false)
        assertEquals(STTEngineType.TENCENT_HYBRID, disabled.engineType)
        assertEquals(TencentAsrTier.PRECISION_PAID.cloudModel, disabled.cloudModel)
    }
}
