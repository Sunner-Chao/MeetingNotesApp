package com.oa.automation.infrastructure.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSttReadinessTest {
    @Test fun `only ready V100 responses pass the recording preflight`() {
        assertTrue(parseLocalSttReadiness("""{"ready":true,"engine":"funasr-paraformer","device":"cuda:0"}""").isSuccess)
        assertFalse(parseLocalSttReadiness("""{"ready":false,"engine":"funasr-paraformer"}""").isSuccess)
        assertFalse(parseLocalSttReadiness("""{"ready":true,"engine":"faster-whisper"}""").isSuccess)
        assertFalse(parseLocalSttReadiness("<html>login required</html>").isSuccess)
        assertFalse(parseLocalSttReadiness("{}").isSuccess)
    }
}
