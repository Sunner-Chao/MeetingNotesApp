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
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.model.RecordingMarker
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.infrastructure.background.BackgroundTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.oa.automation.infrastructure.service.RecordingSessionState
import com.oa.automation.infrastructure.service.RealtimeSttRouteState

class RecordingUiStateTest {
    @Test
    fun `unfinished meeting restores its saved template while new meeting stays unselected`() {
        val presets = listOf(
            PresetReportTemplate("通用会议", "通用内容"),
            PresetReportTemplate("项目管理", "项目内容")
        )
        val appConfig = ReportTemplateConfig(
            selectedName = "项目管理",
            content = "项目内容"
        )

        assertEquals(
            "项目管理",
            resolveRestoredRecordingTemplateName(
                meeting = Meeting(selectedTemplateName = "项目管理", title = "待完善会议"),
                appConfig = appConfig,
                isGlobalRecording = false
            )
        )
        assertNull(
            resolveRestoredRecordingTemplateName(
                meeting = Meeting(title = "新会议"),
                appConfig = appConfig,
                isGlobalRecording = false
            )
        )
        assertEquals(
            "项目管理",
            resolveRestoredRecordingTemplateName(
                meeting = Meeting(title = "旧待完善会议", durationMs = 12_000L),
                appConfig = appConfig,
                isGlobalRecording = false,
                hasPriorWork = true
            )
        )
        assertEquals(
            "项目内容",
            resolveRestoredRecordingTemplateConfig(
                selectedName = "项目管理",
                presetTemplates = presets,
                appConfig = appConfig,
                isGlobalRecording = false
            ).content
        )
    }

    @Test
    fun `active recording can recover the global template for a process restart`() {
        val config = ReportTemplateConfig(selectedName = "研学考察", content = "旅程")

        assertEquals(
            "研学考察",
            resolveRestoredRecordingTemplateName(
                meeting = Meeting(title = "正在录音"),
                appConfig = config,
                isGlobalRecording = true
            )
        )
    }

    @Test
    fun `resumed meeting restores its saved speech engine without changing active session`() {
        assertEquals(
            STTEngineType.TENCENT_HYBRID,
            resolveRestoredSttEngineType(
                meeting = Meeting(
                    title = "待完善会议",
                    selectedSttEngineName = STTEngineType.TENCENT_HYBRID.name
                ),
                appEngineType = STTEngineType.FASTER_WHISPER,
                isGlobalRecording = false
            )
        )
        assertEquals(
            STTEngineType.FASTER_WHISPER,
            resolveRestoredSttEngineType(
                meeting = Meeting(
                    title = "正在录音",
                    selectedSttEngineName = STTEngineType.TENCENT_HYBRID.name
                ),
                appEngineType = STTEngineType.FASTER_WHISPER,
                isGlobalRecording = true
            )
        )
    }

    @Test
    fun `legacy unfinished meetings receive a one-time compatible template`() {
        assertEquals(
            "通用会议",
            inferLegacyRecordingTemplateName(
                meeting = Meeting(title = "即刻倾听 08-19 17:02"),
                hasPriorWork = true
            )
        )
        assertEquals(
            "研学考察",
            inferLegacyRecordingTemplateName(
                meeting = Meeting(title = "暑期研学考察"),
                hasPriorWork = true
            )
        )
        assertNull(
            inferLegacyRecordingTemplateName(
                meeting = Meeting(title = "新会议"),
                hasPriorWork = false
            )
        )
    }

    @Test
    fun `resumed recording duration keeps the persisted meeting baseline`() {
        val active = RecordingSessionState(
            meetingId = "meeting-1",
            isRecording = true,
            recordedDurationSeconds = 12
        )
        val idle = active.copy(
            isRecording = false,
            recordedDurationSeconds = 12
        )

        assertEquals(72L, restoredRecordingDurationSeconds(60L, active))
        assertEquals(60L, restoredRecordingDurationSeconds(60L, idle))
    }

    @Test
    fun `fallback status clearly tells the user that cloud has taken over`() {
        val switching = realtimeSttStatusPresentation(
            RealtimeSttRouteState.SWITCHING_TO_CLOUD
        )
        val active = realtimeSttStatusPresentation(
            RealtimeSttRouteState.CLOUD_FALLBACK_ACTIVE
        )

        assertEquals("正在转接云端识别", switching?.title)
        assertTrue(switching?.detail.orEmpty().contains("录音持续保存"))
        assertEquals("云端识别已接管", active?.title)
        assertTrue(active?.detail.orEmpty().contains("录音未中断"))
    }

    @Test
    fun `live route drives the displayed stt engine`() {
        assertEquals(
            STTEngineType.TENCENT_HYBRID,
            effectiveSttEngineType(
                preferred = STTEngineType.FASTER_WHISPER,
                route = RealtimeSttRouteState.CLOUD_FALLBACK_ACTIVE,
                isRecording = true
            )
        )
        assertEquals(
            STTEngineType.FASTER_WHISPER,
            effectiveSttEngineType(
                preferred = STTEngineType.TENCENT_HYBRID,
                route = RealtimeSttRouteState.LOCAL_ACTIVE,
                isRecording = true
            )
        )
    }

    @Test
    fun `paused recording with live text can generate a report`() {
        assertTrue(
            canGenerateReportFromRecording(
                RecordingUiState(
                    isRecording = true,
                    isPaused = true,
                    hasRecording = true,
                    liveTranscript = "已暂停的会议内容"
                )
            )
        )
        assertFalse(
            canGenerateReportFromRecording(
                RecordingUiState(
                    isRecording = true,
                    hasRecording = true,
                    liveTranscript = "仍在录音"
                )
            )
        )
    }

    @Test
    fun `failed recording is recovered once when its local file still exists`() {
        val meeting = Meeting(title = "研学考察")

        assertTrue(
            shouldRecoverInterruptedTranscription(
                meeting = meeting,
                hasTranscript = false,
                taskState = BackgroundTaskState.FAILED,
                audioFileAvailable = true,
                recoveryAlreadyAttempted = false
            )
        )
        assertFalse(
            shouldRecoverInterruptedTranscription(
                meeting = meeting,
                hasTranscript = false,
                taskState = BackgroundTaskState.FAILED,
                audioFileAvailable = true,
                recoveryAlreadyAttempted = true
            )
        )
    }

    @Test
    fun `active and completed recordings are not auto restarted`() {
        val meeting = Meeting(title = "文件导入", origin = MeetingOrigin.FILE_IMPORT)

        listOf(
            BackgroundTaskState.QUEUED,
            BackgroundTaskState.RUNNING,
            BackgroundTaskState.SUCCEEDED
        ).forEach { taskState ->
            assertFalse(
                shouldRecoverInterruptedTranscription(
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
    fun `cancelled recording with saved audio is eligible for one recovery attempt`() {
        assertTrue(
            shouldRecoverInterruptedTranscription(
                meeting = Meeting(title = "即刻倾听"),
                hasTranscript = false,
                taskState = BackgroundTaskState.CANCELLED,
                audioFileAvailable = true,
                recoveryAlreadyAttempted = false
            )
        )
    }

    @Test
    fun `recording main action pauses or continues an active session`() {
        assertEquals(
            RecordingMainAction.TOGGLE_PAUSE,
            recordingMainAction(RecordingUiState(isRecording = true, isPaused = true))
        )
        assertEquals(
            RecordingMainAction.TOGGLE_PAUSE,
            recordingMainAction(RecordingUiState(isRecording = true))
        )
        assertEquals(
            RecordingMainAction.START,
            recordingMainAction(RecordingUiState())
        )
    }

    @Test
    fun `recording requires an explicit template selection before start`() {
        assertFalse(isRecordingMainActionEnabled(RecordingUiState()))
        assertTrue(
            isRecordingMainActionEnabled(
                RecordingUiState(selectedRecordingTemplateName = "通用会议")
            )
        )
        assertTrue(isRecordingMainActionEnabled(RecordingUiState(isRecording = true)))
    }

    @Test
    fun `changing meeting clears transient recording and processing state`() {
        val previous = RecordingUiState(
            meetingTitle = "Previous meeting",
            selectedRecordingTemplateName = "项目讨论",
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
            isImportingImages = true,
            imageImportCompleted = 4,
            imageImportTotal = 10,
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
        assertNull(next.selectedRecordingTemplateName)
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
        assertFalse(next.isImportingImages)
        assertEquals(0, next.imageImportCompleted)
        assertEquals(0, next.imageImportTotal)
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
    fun `marker transcript segments preserve text and expose insertion boundaries`() {
        val segments = recordingMarkerTranscriptSegments(
            transcript = "先介绍展馆。重点观察数字看板。继续参观。",
            anchors = listOf("重点观察数字看板。")
        )

        assertEquals("先介绍展馆。重点观察数字看板。继续参观。", segments.joinToString("") { it.text })
        assertEquals(listOf(false, true, false), segments.map { it.isMarker })
        assertEquals("重点观察数字看板。", segments.single { it.isMarker }.text)
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
    fun `study stage finalization keeps the frozen pause boundary`() {
        val snapshot = StudyStageFinalizationSnapshot(
            stageId = "stage-1",
            transcript = "第一段内容。暂停前最后一句。",
            durationSeconds = 125L,
            markerCount = 0,
            attachmentCount = 1
        )

        val evidence = resolveStudyStageEvidence(
            baseline = "第一段内容。",
            startedDurationSeconds = 60L,
            snapshot = snapshot
        )

        assertEquals("暂停前最后一句。", evidence.transcriptDelta)
        assertFalse(evidence.transcriptDelta.contains("恢复后的第二段内容"))
        assertEquals(60_000L, evidence.startTimeMs)
        assertEquals(125_000L, evidence.endTimeMs)
        assertTrue(evidence.isMeaningful)
    }

    @Test
    fun `active photo marker closes only after a linked image succeeds`() {
        assertFalse(shouldCloseActivePhotoMarker("marker-1", "marker-1", importedCount = 0))
        assertFalse(shouldCloseActivePhotoMarker("marker-1", "marker-2", importedCount = 1))
        assertTrue(shouldCloseActivePhotoMarker("marker-1", "marker-1", importedCount = 1))
    }

    @Test
    fun `image import exposes compact progress and rejects concurrent work`() {
        val importing = RecordingUiState(
            isImportingImages = true,
            imageImportCompleted = 7,
            imageImportTotal = 24
        )

        assertFalse(canStartImageImport(importing))
        assertEquals("正在导入图片 7/24", imageImportProgressLabel(importing))
        assertTrue(canStartImageImport(RecordingUiState()))
        assertNull(imageImportProgressLabel(RecordingUiState()))
    }

    @Test
    fun `image import summary keeps all successes and reports partial failures once`() {
        val summary = summarizeImageImport(
            listOf(
                Result.success(Unit),
                Result.failure(IllegalStateException("文件已损坏")),
                Result.success(Unit),
                Result.failure(IllegalArgumentException("格式不支持"))
            )
        )

        assertEquals(4, summary.total)
        assertEquals(2, summary.succeeded)
        assertEquals(2, summary.failed)
        assertEquals("文件已损坏", summary.firstFailureMessage)
        assertEquals("已导入 2 张，2 张失败：文件已损坏", summary.failureMessage())
    }

    @Test
    fun `image import target remains frozen when the active journey stage changes`() {
        val marker = RecordingMarker(
            id = "marker-1",
            meetingId = "meeting-1",
            journeyStageId = "stage-1",
            timestampMs = 12_000L,
            transcriptAnchor = "第一段现场讲解"
        )
        val initialState = RecordingUiState(
            currentJourneyStage = JourneyStage(
                id = "stage-1",
                journeyId = "journey-1",
                sequenceNumber = 1,
                title = "第一段"
            )
        )

        val frozenTarget = resolveImageImportTarget(initialState, listOf(marker), marker.id)
        val resumedState = initialState.copy(
            currentJourneyStage = JourneyStage(
                id = "stage-2",
                journeyId = "journey-1",
                sequenceNumber = 2,
                title = "第二段"
            )
        )

        assertEquals("stage-1", frozenTarget?.journeyStageId)
        assertEquals(marker, frozenTarget?.recordingMarker)
        assertEquals("stage-2", resumedState.currentJourneyStage?.id)
    }
}
