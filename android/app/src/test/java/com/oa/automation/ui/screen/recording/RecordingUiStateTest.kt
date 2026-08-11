package com.oa.automation.ui.screen.recording

import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.StageDraftStatus
import com.oa.automation.domain.model.StageDraftVersion
import com.oa.automation.domain.model.JourneyEdition
import com.oa.automation.domain.model.JourneyEditionStatus
import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingOrigin
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.domain.model.RecordingMarker
import com.oa.automation.infrastructure.background.BackgroundTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.oa.automation.infrastructure.service.RecordingSessionState

class RecordingUiStateTest {
    @Test
    fun `failed imported audio is recovered once when its local file still exists`() {
        val meeting = Meeting(title = "文件导入", origin = MeetingOrigin.FILE_IMPORT)

        assertTrue(
            shouldRecoverImportedTranscription(
                meeting = meeting,
                hasTranscript = false,
                taskState = BackgroundTaskState.FAILED,
                audioFileAvailable = true,
                recoveryAlreadyAttempted = false
            )
        )
        assertFalse(
            shouldRecoverImportedTranscription(
                meeting = meeting,
                hasTranscript = false,
                taskState = BackgroundTaskState.FAILED,
                audioFileAvailable = true,
                recoveryAlreadyAttempted = true
            )
        )
    }

    @Test
    fun `active or deliberately cancelled import is not auto restarted`() {
        val meeting = Meeting(title = "文件导入", origin = MeetingOrigin.FILE_IMPORT)

        listOf(
            BackgroundTaskState.QUEUED,
            BackgroundTaskState.RUNNING,
            BackgroundTaskState.CANCELLED,
            BackgroundTaskState.SUCCEEDED
        ).forEach { taskState ->
            assertFalse(
                shouldRecoverImportedTranscription(
                    meeting = meeting,
                    hasTranscript = false,
                    taskState = taskState,
                    audioFileAvailable = true,
                    recoveryAlreadyAttempted = false
                )
            )
        }
    }

    @Test
    fun `paused recording keeps terminate available while continue remains separate`() {
        assertEquals(
            RecordingMainAction.STOP,
            recordingMainAction(RecordingUiState(isRecording = true, isPaused = true))
        )
        assertEquals(
            RecordingMainAction.STOP,
            recordingMainAction(RecordingUiState(isRecording = true))
        )
        assertEquals(
            RecordingMainAction.START,
            recordingMainAction(RecordingUiState())
        )
    }

    @Test
    fun `changing meeting clears transient recording and processing state`() {
        val previous = RecordingUiState(
            meetingTitle = "Previous meeting",
            isRecordingActionPending = true,
            isFinalizingRecording = true,
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
            recordingMarkers = listOf(12L, 98L),
            recordingMarkerAnchors = listOf("现场讲解"),
            activePhotoMarker = RecordingMarker(
                id = "marker-1",
                meetingId = "meeting-1",
                timestampMs = 98_000L,
                transcriptAnchor = "现场讲解"
            )
        )

        val next = previous.resetForMeetingChange()

        assertFalse(next.isRecordingActionPending)
        assertFalse(next.isFinalizingRecording)
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
        assertEquals(emptyList<String>(), next.recordingMarkerAnchors)
        assertNull(next.activePhotoMarker)
    }

    @Test
    fun `final transcription does not block the next recording`() {
        assertTrue(isRecordingActionEnabled(RecordingUiState(isTranscribing = true)))
        assertFalse(isRecordingActionEnabled(RecordingUiState(isRecordingActionPending = true)))
        assertFalse(isRecordingActionEnabled(RecordingUiState(isFinalizingRecording = true)))
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

    @Test
    fun `live recording finalization covers stop and transcript persistence`() {
        assertTrue(
            isLiveRecordingFinalizing(
                RecordingSessionState(meetingId = "meeting-1", isStopping = true)
            )
        )
        assertTrue(
            isLiveRecordingFinalizing(
                RecordingSessionState(meetingId = "meeting-1", status = "正在保存实时转写")
            )
        )
        assertFalse(
            isLiveRecordingFinalizing(
                RecordingSessionState(meetingId = "meeting-1", status = "会议纪要正在排队")
            )
        )
    }

    @Test
    fun `recording marker anchors the latest spoken sentence`() {
        val transcript = "讲解员介绍了展馆历史。随后我们来到设备区，重点观察了数字化看板。"

        assertEquals(
            "随后我们来到设备区，重点观察了数字化看板。",
            extractRecordingMarkerAnchor(transcript)
        )
    }

    @Test
    fun `marker highlight targets the latest repeated occurrence`() {
        val transcript = "查看数字看板。继续交流。查看数字看板。"

        assertEquals(
            listOf(12..17),
            findRecordingMarkerAnchorRanges(transcript, listOf("查看数字看板"))
        )
    }

    @Test
    fun `study stage only closes when it contains evidence`() {
        assertFalse(hasMeaningfulStudyStageEvidence("", markerCount = 0, attachmentCount = 0))
        assertTrue(hasMeaningfulStudyStageEvidence("新增讲解内容", markerCount = 0, attachmentCount = 0))
        assertTrue(hasMeaningfulStudyStageEvidence("", markerCount = 1, attachmentCount = 0))
        assertTrue(hasMeaningfulStudyStageEvidence("", markerCount = 0, attachmentCount = 1))
    }

    @Test
    fun `study stage transcript keeps only text after its baseline`() {
        assertEquals(
            "第二段新增内容。",
            studyStageTranscriptDelta(
                baseline = "第一段内容。",
                currentTranscript = "第一段内容。第二段新增内容。"
            )
        )
    }

    @Test
    fun `active photo marker closes only after a linked image succeeds`() {
        assertFalse(shouldCloseActivePhotoMarker("marker-1", "marker-1", importedCount = 0))
        assertFalse(shouldCloseActivePhotoMarker("marker-1", "marker-2", importedCount = 1))
        assertTrue(shouldCloseActivePhotoMarker("marker-1", "marker-1", importedCount = 1))
    }
}
