package com.oa.automation.infrastructure.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSessionStateTest {
    @Test
    fun `active session counts elapsed whole seconds`() {
        val state = RecordingSessionState(
            isRecording = true,
            startedAtElapsedRealtimeMs = 2_000L
        )

        assertEquals(7L, state.durationSecondsAt(9_999L))
    }

    @Test
    fun `completed session keeps captured duration`() {
        val state = RecordingSessionState(recordedDurationSeconds = 725L)

        assertEquals(725L, state.durationSecondsAt(99_999L))
    }

    @Test
    fun `paused session freezes its accumulated duration`() {
        val state = RecordingSessionState(
            isRecording = true,
            isPaused = true,
            recordedDurationSeconds = 47L
        )

        assertEquals(47L, state.durationSecondsAt(999_999L))
    }

    @Test
    fun `resumed session adds new elapsed time to captured duration`() {
        val state = RecordingSessionState(
            isRecording = true,
            recordedDurationSeconds = 47L,
            startedAtElapsedRealtimeMs = 10_000L
        )

        assertEquals(52L, state.durationSecondsAt(15_800L))
    }
}
