package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class STTEngineTypeTest {

    @Test
    fun `tencent hybrid is the only cloud transcription option`() {
        assertEquals(
            setOf("FASTER_WHISPER", "TENCENT_HYBRID"),
            STTEngineType.entries.map { it.name }.toSet()
        )
        assertEquals("tencent-standard", STTEngineType.TENCENT_HYBRID.defaultModel)
        assertEquals("large-v3-turbo", STTEngineType.FASTER_WHISPER.defaultModel)
        assertEquals("本地智悟通用模型", STTEngineType.FASTER_WHISPER.displayName)
        assertEquals("云端智悟增强模型", STTEngineType.TENCENT_HYBRID.displayName)
        assertEquals("tencent-standard", TencentAsrTier.STANDARD_FREE.cloudModel)
        assertEquals("tencent-precision", TencentAsrTier.PRECISION_PAID.cloudModel)
        assertFalse(STTEngineType.entries.any { it.name == "CLOUD_ASR" })
        assertFalse(STTEngineType.entries.any { it.displayName.contains("仅最终稿") })
    }

    @Test
    fun `release migration recognizes development-only STT endpoints`() {
        assertEquals(true, "http://localhost:8888".isDevelopmentOnlySttEndpoint())
        assertEquals(true, "http://10.0.2.2:8888/".isDevelopmentOnlySttEndpoint())
        assertEquals(true, "http://127.0.0.1:8888".isDevelopmentOnlySttEndpoint())
        assertFalse("https://stt.example.com".isDevelopmentOnlySttEndpoint())
    }
}
