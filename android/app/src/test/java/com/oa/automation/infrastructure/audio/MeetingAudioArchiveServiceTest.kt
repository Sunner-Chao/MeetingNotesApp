package com.oa.automation.infrastructure.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingAudioArchiveServiceTest {
    @Test
    fun `archive list removes duplicate ids and duplicate audio hashes`() {
        val items = listOf(
            archivedAudio(id = "newest", meetingId = "meeting-1", sha256 = "hash-a"),
            archivedAudio(id = "same-content", meetingId = "meeting-1", sha256 = "hash-a"),
            archivedAudio(id = "newest", meetingId = "meeting-1", sha256 = "hash-b"),
            archivedAudio(id = "other-meeting", meetingId = "meeting-2", sha256 = "hash-a"),
            archivedAudio(id = "legacy", meetingId = "meeting-1", sha256 = ""),
            archivedAudio(id = "legacy", meetingId = "meeting-1", sha256 = "")
        )

        assertEquals(
            listOf("newest", "other-meeting", "legacy"),
            deduplicateArchivedAudio(items).map { it.id }
        )
    }

    private fun archivedAudio(id: String, meetingId: String, sha256: String) = ArchivedMeetingAudio(
        id = id,
        meetingId = meetingId,
        createdAt = "2026-07-27T00:00:00Z",
        bytes = 1024,
        durationSec = 1.0,
        filename = "$id.wav",
        source = "stream",
        downloadPath = "/audio-archive/$id",
        sha256 = sha256
    )
}
