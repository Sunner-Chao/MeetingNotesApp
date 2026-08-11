package com.oa.automation.infrastructure.db

import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.RecordingMarker
import com.oa.automation.domain.model.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeetingEvidenceStageMappingTest {
    @Test
    fun `transcript round trip preserves journey stage attribution`() {
        val transcript = Transcript(
            id = "transcript-1",
            meetingId = "meeting-1",
            journeyStageId = "stage-2",
            speakerName = "讲解员",
            content = "展馆采用被动式节能设计。",
            startTimeMs = 2_000L,
            endTimeMs = 8_000L,
            createdAt = 100L
        )

        assertEquals(transcript, transcript.toEntity().toDomain())
    }

    @Test
    fun `attachment round trip preserves journey stage attribution`() {
        val attachment = MeetingAttachment(
            id = "attachment-1",
            meetingId = "meeting-1",
            journeyStageId = "stage-2",
            displayName = "展馆外立面.jpg",
            localPath = "C:/meeting-attachments/attachment-1.jpg",
            mimeType = "image/jpeg",
            createdAt = 100L,
            latitude = 31.2304,
            longitude = 121.4737,
            locationSource = "exif",
            recordingMarkerId = "marker-1",
            markerTimestampMs = 42_000L,
            markerTranscriptAnchor = "讲解员开始介绍展馆外立面。"
        )

        assertEquals(attachment, attachment.toEntity().toDomain())
    }

    @Test
    fun `recording marker round trip preserves transcript anchor`() {
        val marker = RecordingMarker(
            id = "marker-1",
            meetingId = "meeting-1",
            journeyStageId = "stage-2",
            timestampMs = 42_000L,
            transcriptAnchor = "讲解员开始介绍展馆外立面。",
            createdAt = 200L
        )

        assertEquals(marker, marker.toEntity().toDomain())
    }

    @Test
    fun `general meeting evidence remains unassigned by default`() {
        assertNull(
            Transcript(
                meetingId = "meeting-general",
                content = "普通会议记录"
            ).toEntity().journeyStageId
        )
        assertNull(
            MeetingAttachment(
                id = "attachment-general",
                meetingId = "meeting-general",
                displayName = "whiteboard.jpg",
                localPath = "C:/meeting-attachments/whiteboard.jpg",
                mimeType = "image/jpeg",
                createdAt = 100L
            ).toEntity().journeyStageId
        )
        assertNull(
            MeetingAttachment(
                id = "attachment-general",
                meetingId = "meeting-general",
                displayName = "whiteboard.jpg",
                localPath = "C:/meeting-attachments/whiteboard.jpg",
                mimeType = "image/jpeg",
                createdAt = 100L
            ).toEntity().recordingMarkerId
        )
    }
}
