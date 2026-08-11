package com.oa.automation.ui.screen.report

import com.oa.automation.domain.model.MeetingAttachment
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportReferenceJourneyTest {
    @Test
    fun `stage summaries keep stage order and count location evidence`() {
        val attachments = listOf(
            attachment(id = "stage-2-photo", stageId = "stage-2", createdAt = 30L, located = true),
            attachment(id = "stage-1-photo", stageId = "stage-1", createdAt = 10L, located = false),
            attachment(id = "stage-1-photo-2", stageId = "stage-1", createdAt = 20L, located = true)
        )

        assertEquals(
            listOf(
                ReferenceJourneyStageSummary(sequenceNumber = 1, attachmentCount = 2, locationCount = 1),
                ReferenceJourneyStageSummary(sequenceNumber = 2, attachmentCount = 1, locationCount = 1)
            ),
            referenceJourneyStageSummaries(attachments)
        )
    }

    @Test
    fun `attachments without stage ids remain one current stage`() {
        val attachments = listOf(
            attachment(id = "photo-1", stageId = null, createdAt = 1L, located = false),
            attachment(id = "photo-2", stageId = null, createdAt = 2L, located = true)
        )

        assertEquals(
            listOf(ReferenceJourneyStageSummary(sequenceNumber = 1, attachmentCount = 2, locationCount = 1)),
            referenceJourneyStageSummaries(attachments)
        )
    }

    private fun attachment(
        id: String,
        stageId: String?,
        createdAt: Long,
        located: Boolean
    ) = MeetingAttachment(
        id = id,
        meetingId = "meeting-1",
        journeyStageId = stageId,
        displayName = "$id.jpg",
        localPath = "/tmp/$id.jpg",
        mimeType = "image/jpeg",
        createdAt = createdAt,
        latitude = if (located) 31.23 else null,
        longitude = if (located) 121.47 else null
    )
}
