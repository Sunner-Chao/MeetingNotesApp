package com.oa.automation.domain.model

import com.oa.automation.infrastructure.db.toDomain
import com.oa.automation.infrastructure.db.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingSessionTest {
    @Test
    fun sourceAndDeviceSurviveMeetingStorageRoundTrip() {
        val original = Meeting(
            id = "session", title = "策划讨论", ownerId = "owner",
            audioSource = MeetingAudioSource.BLUETOOTH_MICROPHONE,
            captureDeviceName = "会议耳机", externalSessionId = "external-meeting",
            preferredCaptureInput = CaptureInput.BLUETOOTH, detectedSpeakerCount = 2
        )
        assertEquals(original, original.toEntity().toDomain())
        val legacy = original.toEntity().copy(audioSource = "OLD_SOURCE", preferredCaptureInput = "OLD_INPUT")
        assertEquals(MeetingAudioSource.UNKNOWN, legacy.toDomain().audioSource)
        assertEquals(CaptureInput.PHONE, legacy.toDomain().preferredCaptureInput)
    }

    @Test
    fun roomBindingSurvivesMeetingStorageRoundTripAndCanBeCleared() {
        val meeting = Meeting(id = "room-bound", title = "策划讨论", ownerId = "owner", meetingRoomId = "room-1")
        assertEquals(meeting, meeting.toEntity().toDomain())
        assertEquals(null, meeting.copy(meetingRoomId = null).toEntity().toDomain().meetingRoomId)
    }

    @Test
    fun importedAudioDoesNotClaimDirectCallAccessOrVerifiedIdentity() {
        val meeting = Meeting(title = "导入讨论", audioSource = MeetingAudioSource.TENCENT_MEETING_EXPORT)
        val transcript = Transcript(id = "one", meetingId = "session", content = "下一步先核对预算", speakerName = "说话人 1", startTimeMs = 1200, endTimeMs = 4400)
        val context = buildMeetingSessionContext(meeting, listOf(transcript))
        assertTrue(context.contains("腾讯会议导出音频"))
        assertTrue(context.contains("并非直接接入第三方通话"))
        assertTrue(context.contains("不代表真实姓名"))
        assertTrue(context.contains("没有音频分析证据时不得推断"))
        assertTrue(transcript.renderedTimelineContent().startsWith("[00:00:01–00:00:04]"))
        assertTrue(transcript.renderedTimelineContent().contains("说话人 1"))
        assertFalse(transcript.copy(startTimeMs = 0, endTimeMs = 0).renderedTimelineContent().startsWith("["))
    }
}
