package com.oa.automation.infrastructure.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttContextHintTest {
    @Test
    fun `meeting title and template form a compact recognition hint`() {
        assertEquals(
            "大佛寺研学 考察；研学考察",
            buildSttContextHint("  大佛寺研学\n考察  ", "研学考察")
        )
    }

    @Test
    fun `context hint removes control characters and stays bounded`() {
        val hint = buildSttContextHint("会议\u0000主题" + "词".repeat(300))

        assertFalse(hint.contains('\u0000'))
        assertTrue(hint.length <= 240)
    }
}
