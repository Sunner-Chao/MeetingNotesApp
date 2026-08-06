package com.oa.automation.ui.screen.recording

import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.StageDraftStatus
import com.oa.automation.domain.model.StageDraftVersion
import com.oa.automation.domain.model.JourneyEdition
import com.oa.automation.domain.model.JourneyEditionStatus
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.PublishedPostStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.oa.automation.infrastructure.service.RecordingSessionState

class RecordingUiStateTest {
    @Test
    fun `changing meeting clears transient recording and processing state`() {
        val previous = RecordingUiState(
            meetingTitle = "Previous meeting",
            isRecordingActionPending = true,
            isRecording = true,
            isPaused = true,
            hasRecording = true,
            recordingDuration = 184,
            liveTranscript = "previous transcript",
            isTranscribing = true,
            transcriptionProgressPercent = 62,
            transcriptionProgressStage = "final transcription",
            error = "stale error",
            inputMode = InputMode.IMPORT,
            manualTextInput = "imported text",
            importedAudioDisplayName = "old.m4a",
            isGeneratingReport = true,
            reportProgressPercent = 40,
            reportReadyToOpen = true,
            audioExportBusyId = "audio-1",
            audioExportMessage = "stale export",
            journey = Journey(
                id = "journey-1",
                meetingId = "meeting-1",
                title = "研学考察",
                currentStageId = "stage-1"
            ),
            currentJourneyStage = JourneyStage(
                id = "stage-1",
                journeyId = "journey-1",
                sequenceNumber = 1,
                title = "第 1 段"
            ),
            latestSavedJourneyStage = JourneyStage(
                id = "stage-0",
                journeyId = "journey-1",
                sequenceNumber = 0,
                title = "第 0 段"
            ),
            latestStageDraft = StageDraftVersion(
                id = "draft-1",
                stageId = "stage-0",
                versionNumber = 1,
                content = "阶段笔记",
                status = StageDraftStatus.DRAFT
            ),
            isGeneratingStageDraft = true,
            isSavingStageDraft = true,
            stageDraftEditorVisible = true,
            latestJourneyEdition = JourneyEdition(
                id = "edition-1",
                journeyId = "journey-1",
                versionNumber = 1,
                content = "总游记",
                status = JourneyEditionStatus.DRAFT,
                sourceStageDraftIds = listOf("draft-1"),
                sourceStageCount = 1
            ),
            isGeneratingJourneyEdition = true,
            isSavingJourneyEdition = true,
            journeyEditionEditorVisible = true,
            latestPublishedPost = PublishedPost(
                id = "post-1",
                journeyId = "journey-1",
                journeyEditionId = "edition-1",
                versionNumber = 1,
                sourceEditionVersion = 1,
                title = "研学考察",
                content = "发布预览",
                status = PublishedPostStatus.REVIEW
            ),
            isCreatingPublishedPost = true,
            isSavingPublishedPost = true,
            publishedPostReviewVisible = true,
            journeyStageCount = 1,
            isJourneyActionPending = true,
            journeyStatusMessage = "第 1 段已暂存",
            recordingMarkers = listOf(12L, 98L)
        )

        val next = previous.resetForMeetingChange()

        assertFalse(next.isRecordingActionPending)
        assertFalse(next.isRecording)
        assertFalse(next.isPaused)
        assertFalse(next.hasRecording)
        assertEquals(0L, next.recordingDuration)
        assertEquals("", next.liveTranscript)
        assertFalse(next.isTranscribing)
        assertNull(next.transcriptionProgressPercent)
        assertEquals("", next.transcriptionProgressStage)
        assertNull(next.error)
        assertEquals(InputMode.VOICE, next.inputMode)
        assertEquals("", next.manualTextInput)
        assertEquals("", next.importedAudioDisplayName)
        assertFalse(next.isGeneratingReport)
        assertNull(next.reportProgressPercent)
        assertFalse(next.reportReadyToOpen)
        assertNull(next.audioExportBusyId)
        assertEquals("", next.audioExportMessage)
        assertNull(next.journey)
        assertNull(next.currentJourneyStage)
        assertNull(next.latestSavedJourneyStage)
        assertNull(next.latestStageDraft)
        assertFalse(next.isGeneratingStageDraft)
        assertFalse(next.isSavingStageDraft)
        assertFalse(next.stageDraftEditorVisible)
        assertNull(next.latestJourneyEdition)
        assertFalse(next.isGeneratingJourneyEdition)
        assertFalse(next.isSavingJourneyEdition)
        assertFalse(next.journeyEditionEditorVisible)
        assertNull(next.latestPublishedPost)
        assertFalse(next.isCreatingPublishedPost)
        assertFalse(next.isSavingPublishedPost)
        assertFalse(next.publishedPostReviewVisible)
        assertEquals(0, next.journeyStageCount)
        assertFalse(next.isJourneyActionPending)
        assertEquals("", next.journeyStatusMessage)
        assertEquals(emptyList<Long>(), next.recordingMarkers)
    }

    @Test
    fun `final transcription does not block the next recording`() {
        assertTrue(isRecordingActionEnabled(RecordingUiState(isTranscribing = true)))
        assertFalse(isRecordingActionEnabled(RecordingUiState(isRecordingActionPending = true)))
        assertFalse(isRecordingActionEnabled(RecordingUiState(isJourneyActionPending = true)))
        assertFalse(isRecordingActionEnabled(RecordingUiState(isGeneratingReport = true)))
    }

    @Test
    fun `completed stop clears the transient action lock`() {
        assertFalse(
            recordingActionPending(
                RecordingSessionState(
                    meetingId = "meeting-1",
                    status = "后台转写已排队"
                )
            )
        )
    }
}
