package com.oa.automation.infrastructure.service

import com.oa.automation.domain.model.*
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.infrastructure.account.AccountApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class MeetingRoomRecordingIntegrationTest {
    private val server = MockWebServer()
    private val sessions = MutableStateFlow<AuthSession?>(session("owner"))
    private val endpoints = MutableStateFlow("")
    private val store = RoomBindingStore()
    private lateinit var access: MeetingRoomRecordingAccess

    @Before fun setUp() {
        server.start()
        endpoints.value = server.url("/api").toString()
        access = MeetingRoomRecordingAccess(sessions, endpoints, store, AccountApiService())
    }

    @After fun tearDown() { server.shutdown() }

    private fun roomResponse(consent: Boolean = true) = MockResponse().setBody(
        """{"id":"room-1","state":"open","members":[{"user_id":"owner","recording_consent":$consent}],"all_recording_consented":$consent}"""
    )

    private fun duringResponse(action: () -> Unit) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                action()
                return roomResponse()
            }
        }
    }

    @Test fun unboundRecordingDoesNotRequireLoginOrRoomNetwork() = runBlocking {
        sessions.value = null
        assertNull(access.forStart("meeting-1"))
        assertEquals(0, server.requestCount)
    }

    @Test fun bindingIsExplicitAndConsentIsCheckedAgainAtStart() = runBlocking {
        server.enqueue(roomResponse(false))
        access.bind("meeting-1", "room-1")
        assertEquals("room-1", store.meeting.meetingRoomId)
        server.enqueue(roomResponse(false))
        assertTrue(runCatching { access.forStart("meeting-1") }.isFailure)
        server.enqueue(roomResponse())
        val binding = access.forStart("meeting-1")!!
        assertEquals("owner", binding.ownerId)
        assertEquals("room-1", binding.roomId)
        assertEquals("Bearer test-session", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun userCanUnbindEndedOrUnavailableRoomWithoutNetwork() = runBlocking {
        store.meeting = store.meeting.copy(meetingRoomId = "room-1")
        access.bind("meeting-1", null)
        assertNull(store.meeting.meetingRoomId)
        assertEquals(0, server.requestCount)
    }

    @Test fun accountChangeDuringBindCannotWriteOldAssociation() = runBlocking {
        duringResponse { sessions.value = session("other") }
        assertTrue(runCatching { access.bind("meeting-1", "room-1") }.isFailure)
        assertEquals(0, store.bindingWrites)
        assertNull(store.meeting.meetingRoomId)
    }

    @Test fun endpointChangeDuringBindCannotWriteOldAssociation() = runBlocking {
        duringResponse { endpoints.value = "https://other.example/api" }
        assertTrue(runCatching { access.bind("meeting-1", "room-1") }.isFailure)
        assertEquals(0, store.bindingWrites)
    }

    @Test fun startRejectsOtherOwnersBeforeSendingRoomRequest() = runBlocking {
        store.meeting = store.meeting.copy(meetingRoomId = "room-1", ownerId = "other")
        assertTrue(runCatching { access.forStart("meeting-1") }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun validationRechecksAccountAndAssociationAfterResponse() = runBlocking {
        store.meeting = store.meeting.copy(meetingRoomId = "room-1")
        val binding = RoomRecordingBinding("meeting-1", "room-1", "owner", endpoints.value)
        duringResponse { sessions.value = session("other") }
        assertTrue(runCatching { access.validate(binding) }.isFailure)
        sessions.value = session("owner")
        duringResponse { store.meeting = store.meeting.copy(meetingRoomId = null) }
        assertTrue(runCatching { access.validate(binding) }.isFailure)
    }

    @Test fun failedOrMismatchedRoomResponseNeverAuthorizesCapture() = runBlocking {
        store.meeting = store.meeting.copy(meetingRoomId = "room-1")
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"已离开房间"}"""))
        assertTrue(runCatching { access.forStart("meeting-1") }.isFailure)
        server.enqueue(MockResponse().setBody("""{"id":"other","state":"open","all_recording_consented":true,"members":[{"user_id":"owner","recording_consent":true}]}"""))
        assertTrue(runCatching { access.forStart("meeting-1") }.isFailure)
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(runCatching { access.forStart("meeting-1") }.isFailure)
    }

    @Test fun roomRequestHasBoundedDeadline() = runBlocking {
        store.meeting = store.meeting.copy(meetingRoomId = "room-1")
        server.enqueue(roomResponse().setBodyDelay(8, TimeUnit.SECONDS))
        val started = System.nanoTime()
        assertTrue(runCatching { access.forStart("meeting-1") }.isFailure)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("Room request exceeded its 5 second deadline: $elapsedMs ms", elapsedMs < 7_000)
    }

    private class RoomBindingStore : MeetingRepository {
        var meeting = Meeting(id = "meeting-1", title = "策划讨论", ownerId = "owner")
        var bindingWrites = 0
        override suspend fun findById(id: String) = Result.success(meeting.takeIf { it.id == id })
        override suspend fun bindRoom(id: String, roomId: String?, expectedOwnerId: String) = runCatching {
            require(id == meeting.id && expectedOwnerId == meeting.ownerId)
            meeting = meeting.copy(meetingRoomId = roomId)
            bindingWrites++
            Unit
        }
        override fun getAllMeetingsFlow() = MutableStateFlow(listOf(meeting))
        override suspend fun save(meeting: Meeting): Result<Meeting> = error("Not used")
        override suspend fun delete(id: String): Result<Unit> = error("Not used")
        override suspend fun saveAudioSegment(segment: MeetingAudioSegment): Result<MeetingAudioSegment> = error("Not used")
        override suspend fun findAudioSegmentsByMeetingId(meetingId: String): Result<List<MeetingAudioSegment>> = error("Not used")
        override suspend fun updateTitle(id: String, title: String): Result<Meeting> = error("Not used")
        override suspend fun saveTranscript(transcript: Transcript): Result<Transcript> = error("Not used")
        override suspend fun findTranscriptsByMeetingId(meetingId: String): Result<List<Transcript>> = error("Not used")
        override suspend fun findTranscriptsByJourneyStageId(journeyStageId: String): Result<List<Transcript>> = error("Not used")
        override suspend fun saveAttachment(attachment: MeetingAttachment): Result<MeetingAttachment> = error("Not used")
        override fun observeAttachments(meetingId: String) = MutableStateFlow(emptyList<MeetingAttachment>())
        override fun observeAttachmentsByJourneyStageId(journeyStageId: String) = MutableStateFlow(emptyList<MeetingAttachment>())
        override suspend fun findAttachmentsByJourneyStageIds(stageIds: List<String>): Result<List<MeetingAttachment>> = error("Not used")
        override suspend fun deleteAttachment(id: String): Result<Unit> = error("Not used")
        override suspend fun saveRecordingMarker(marker: RecordingMarker): Result<RecordingMarker> = error("Not used")
        override suspend fun findRecordingMarkersByMeetingId(meetingId: String): Result<List<RecordingMarker>> = error("Not used")
    }

    companion object {
        private fun session(id: String) = AuthSession(accessToken = "test-session", tokenType = "bearer",
            expiresAt = Long.MAX_VALUE, user = AccountProfile(id = id, username = id, role = "user",
                isAdmin = false, enabled = true, vipEnabled = false, constructionLogsUnlocked = false, createdAt = 1))
    }
}
