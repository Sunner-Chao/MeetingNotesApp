package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class STTEngineTypeTest {

    @Test
    fun `tencent hybrid is the only cloud transcription option`() {
        assertEquals(
            setOf("FASTER_WHISPER", "SENSE_VOICE", "TENCENT_HYBRID"),
            STTEngineType.entries.map { it.name }.toSet()
        )
        assertEquals("tencent-standard", STTEngineType.TENCENT_HYBRID.defaultModel)
        assertEquals("tencent-standard", TencentAsrTier.STANDARD_FREE.cloudModel)
        assertEquals("tencent-precision", TencentAsrTier.PRECISION_PAID.cloudModel)
        assertFalse(STTEngineType.entries.any { it.name == "CLOUD_ASR" })
        assertFalse(STTEngineType.entries.any { it.displayName.contains("仅最终稿") })
    }
}
