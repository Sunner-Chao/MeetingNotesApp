package com.oa.automation.ui.screen.report

import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.JourneyStageStatus
import com.oa.automation.domain.model.Transcript
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

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

    @Test
    fun `real journey stages remain visible even without photos`() {
        val stages = listOf(
            JourneyStage(
                id = "stage-2",
                journeyId = "journey-1",
                sequenceNumber = 2,
                title = "园区交流",
                status = JourneyStageStatus.ACTIVE,
                startedAt = 200L
            ),
            JourneyStage(
                id = "stage-1",
                journeyId = "journey-1",
                sequenceNumber = 1,
                title = "展馆参观",
                status = JourneyStageStatus.SAVED,
                startedAt = 100L,
                savedAt = 180L
            )
        )
        val attachments = listOf(
            attachment(id = "stage-1-photo", stageId = "stage-1", createdAt = 120L, located = true)
        )

        val summaries = referenceJourneyStageSummaries(attachments, stages)

        assertEquals(listOf(1, 2), summaries.map { it.sequenceNumber })
        assertEquals(listOf("展馆参观", "园区交流"), summaries.map { it.title })
        assertEquals(listOf(1, 0), summaries.map { it.attachmentCount })
        assertEquals(JourneyStageStatus.ACTIVE, summaries.last().status)
    }

    @Test
    fun `stage transcript map keeps stage order and ignores full meeting transcript`() {
        val transcripts = listOf(
            Transcript(meetingId = "meeting-1", content = "整场转写", startTimeMs = 0L),
            Transcript(meetingId = "meeting-1", journeyStageId = "stage-1", content = "第二句", startTimeMs = 2_000L),
            Transcript(meetingId = "meeting-1", journeyStageId = "stage-1", content = "第一句", startTimeMs = 1_000L),
            Transcript(meetingId = "meeting-1", journeyStageId = "stage-2", content = "下一段", startTimeMs = 3_000L)
        )

        assertEquals(
            mapOf("stage-1" to "第一句\n第二句", "stage-2" to "下一段"),
            journeyStageTranscriptMap(transcripts)
        )
    }

    @Test
    fun `stage anchors are ordered trimmed and deduplicated`() {
        val attachments = listOf(
            attachment(id = "photo-2", stageId = "stage-1", createdAt = 30L, located = false, anchor = " 第二处展板 ", markerTime = 2_000L),
            attachment(id = "photo-1", stageId = "stage-1", createdAt = 10L, located = false, anchor = "第一处展板", markerTime = 1_000L),
            attachment(id = "photo-1-copy", stageId = "stage-1", createdAt = 20L, located = false, anchor = "第一处展板", markerTime = 1_500L),
            attachment(id = "photo-empty", stageId = "stage-1", createdAt = 40L, located = false)
        )

        assertEquals(listOf("第一处展板", "第二处展板"), referenceJourneyStageAnchors(attachments))
    }

    @Test
    fun `meeting meta shows real multi day journey range and active duration`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val start = 1_786_420_800_000L
            val end = start + 202_800_000L

            assertEquals(
                "2026年8月11日 12:00 - 8月13日 20:20 · 560 分钟",
                formatMeetingMeta(start, 33_600_000L, end)
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun attachment(
        id: String,
        stageId: String?,
        createdAt: Long,
        located: Boolean,
        anchor: String? = null,
        markerTime: Long? = null
    ) = MeetingAttachment(
        id = id,
        meetingId = "meeting-1",
        journeyStageId = stageId,
        displayName = "$id.jpg",
        localPath = "/tmp/$id.jpg",
        mimeType = "image/jpeg",
        createdAt = createdAt,
        latitude = if (located) 31.23 else null,
        longitude = if (located) 121.47 else null,
        markerTimestampMs = markerTime,
        markerTranscriptAnchor = anchor
    )
}
