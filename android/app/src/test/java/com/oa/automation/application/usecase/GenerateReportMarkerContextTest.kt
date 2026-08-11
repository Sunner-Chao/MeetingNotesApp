package com.oa.automation.application.usecase

import com.oa.automation.domain.model.Transcript
import com.oa.automation.domain.model.canonicalMeetingTranscripts
import com.oa.automation.infrastructure.llm.AgentAttachment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateReportMarkerContextTest {
    @Test
    fun `marker context keeps image index time and transcript anchor`() {
        val result = buildMarkerAwareTranscript(
            transcript = "先进入山门。随后讲解员介绍大殿。",
            attachments = listOf(
                AgentAttachment(
                    file = File("first.jpg"),
                    mimeType = "image/jpeg",
                    displayName = "first.jpg"
                ),
                AgentAttachment(
                    file = File("second.jpg"),
                    mimeType = "image/jpeg",
                    displayName = "second.jpg",
                    recordingMarkerId = "marker-1",
                    markerTimestampMs = 72_000L,
                    markerTranscriptAnchor = "随后讲解员介绍\n大殿。"
                )
            )
        )

        assertTrue(result.contains("图 2：录音标记 01:12"))
        assertTrue(result.contains("转写锚点：“随后讲解员介绍 大殿。”"))
        assertFalse(result.contains("图 1：录音标记"))
    }

    @Test
    fun `unmarked attachments leave transcript unchanged`() {
        val transcript = "普通会议记录"

        assertEquals(
            transcript,
            buildMarkerAwareTranscript(
                transcript,
                listOf(AgentAttachment(File("photo.jpg"), "image/jpeg", "photo.jpg"))
            )
        )
    }

    @Test
    fun `full meeting transcript excludes duplicate study stage snapshots`() {
        val transcripts = listOf(
            Transcript(
                id = "stage-1",
                meetingId = "meeting-1",
                journeyStageId = "journey-stage-1",
                content = "第一段阶段快照"
            ),
            Transcript(
                id = "full-1",
                meetingId = "meeting-1",
                content = "整场会议完整转写"
            )
        )

        assertEquals(
            listOf("整场会议完整转写"),
            transcripts.canonicalMeetingTranscripts().map(Transcript::content)
        )
    }
}
