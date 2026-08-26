package com.oa.automation.infrastructure.audio

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRecorderEnhancementTest {
    @Test
    fun `enhancement prefers voice recognition and retains microphone fallback`() {
        assertEquals(
            listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            ),
            audioSourceCandidates(enableAudioEnhancement = true)
        )
    }

    @Test
    fun `disabled enhancement uses the raw microphone only`() {
        assertEquals(
            listOf(MediaRecorder.AudioSource.MIC),
            audioSourceCandidates(enableAudioEnhancement = false)
        )
    }
}
