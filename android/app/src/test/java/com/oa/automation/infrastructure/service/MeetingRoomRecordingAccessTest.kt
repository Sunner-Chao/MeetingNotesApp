package com.oa.automation.infrastructure.service

import com.oa.automation.domain.model.MeetingRoom
import com.oa.automation.domain.model.MeetingRoomMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingRoomRecordingAccessTest {
    @Test
    fun roomAutoPauseHasANotificationButOrdinaryCaptureDoesNot() {
        val paused = RecordingSessionState(isRecording = true, isPaused = true,
            status = "房间录音已暂停", error = "房间录音已暂停：成员撤回同意")
        assertEquals(paused.error, roomRecordingPauseNotice(paused))
        assertEquals(null, roomRecordingPauseNotice(paused.copy(isPaused = false)))
        assertEquals(null, roomRecordingPauseNotice(paused.copy(status = "录音已暂停", error = null)))
    }

    private fun room(
        state: String = "open",
        allConsented: Boolean = true,
        members: List<MeetingRoomMember> = listOf(
            MeetingRoomMember(userId = "host", recordingConsent = true),
            MeetingRoomMember(userId = "guest", recordingConsent = true)
        )
    ) = MeetingRoom(id = "room-1", state = state, allRecordingConsented = allConsented, members = members)

    @Test
    fun allActiveMembersMustConsentBeforeRecording() {
        requireRoomRecordingConsent(room(), "host")
    }

    @Test
    fun departedMemberDoesNotBlockRemainingMembers() {
        val departed = MeetingRoomMember(userId = "guest", leftAt = 100L, recordingConsent = false)
        requireRoomRecordingConsent(room(members = listOf(
            MeetingRoomMember(userId = "host", recordingConsent = true), departed
        )), "host")
    }

    @Test
    fun missingConsentRevokedConsentAndEndedRoomAreRejected() {
        val missing = assertThrows(IllegalArgumentException::class.java) {
            requireRoomRecordingConsent(room(
                allConsented = false,
                members = listOf(
                    MeetingRoomMember(userId = "host", recordingConsent = true),
                    MeetingRoomMember(userId = "guest", recordingConsent = false)
                )
            ), "host")
        }
        assertEquals("还有成员未同意录音，请在会议房间中确认", missing.message)

        val ended = assertThrows(IllegalArgumentException::class.java) {
            requireRoomRecordingConsent(room(state = "ended"), "host")
        }
        assertTrue(ended.message.orEmpty().contains("房间已结束"))
    }

    @Test
    fun nonMemberCannotRecordEvenWhenEveryoneElseConsented() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireRoomRecordingConsent(room(), "outsider")
        }
        assertTrue(error.message.orEmpty().contains("已离开房间"))
    }

    @Test
    fun staleAggregateOrMissingStateCannotAuthorizeCapture() {
        assertThrows(IllegalArgumentException::class.java) {
            requireRoomRecordingConsent(room(allConsented = false), "host")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireRoomRecordingConsent(room(members = listOf(MeetingRoomMember(userId = "host"))), "host")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireRoomRecordingConsent(MeetingRoom(allRecordingConsented = true, members = room().members), "host")
        }
    }

    @Test
    fun departedSelfOrEmptyIdentityCannotRecord() {
        assertThrows(IllegalArgumentException::class.java) {
            requireRoomRecordingConsent(room(members = listOf(
                MeetingRoomMember(userId = "host", leftAt = 100L, recordingConsent = true),
                MeetingRoomMember(userId = "guest", recordingConsent = true)
            )), "host")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireRoomRecordingConsent(room(), "")
        }
    }
}
