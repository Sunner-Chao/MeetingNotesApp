package com.oa.automation.ui

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class BeijingTimeTest {
    @Test
    fun `formats publication timestamps in Beijing time across UTC day boundary`() {
        val timestamp = Instant.parse("2026-08-17T16:30:00Z").toEpochMilli()

        assertEquals("2026/08/18 00:30", formatBeijingTime(timestamp, "yyyy/MM/dd HH:mm"))
    }
}
