package com.oa.automation.infrastructure.service

import com.oa.automation.domain.model.*
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.infrastructure.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RoomCallControllerTest {
    private val server = MockWebServer()
    private val sessions = MutableStateFlow<AuthSession?>(session("owner"))
    private val endpoints = MutableStateFlow("")
    private val gate = AudioSessionGate()
    private val fake = FakeTransport()
    private lateinit var controller: RoomCallController
    private lateinit var scope: CoroutineScope
    @Volatile private var roomState = "open"
    @Volatile private var failRoom = false
    private var ticketIdentity = "owner"
    private var onTicket: () -> Unit = {}

    @Before fun setup() {
        server.start()
        endpoints.value = server.url("/api").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.endsWith("/media-session")) {
                    onTicket()
                    return MockResponse().setBody("""{"url":"wss://rtc.example","token":"test-ticket","identity":"$ticketIdentity","room_name":"meeting-room","expires_at":9999999999}""")
                }
                return if (failRoom) MockResponse().setResponseCode(503)
                else MockResponse().setBody("""{"id":"room","state":"$roomState","members":[{"user_id":"owner"}]}""")
            }
        }
    }

    @After fun teardown() {
        if (::controller.isInitialized) controller.leave()
        if (::scope.isInitialized) scope.cancel()
        server.shutdown()
    }

    private fun CoroutineScope.createController() {
        scope = CoroutineScope(coroutineContext + SupervisorJob())
        controller = RoomCallController(sessions, endpoints, AccountApiService(), gate, { fake }, scope, 20)
    }

    private suspend fun awaitState(predicate: (RoomCallState) -> Boolean) {
        withTimeout(4_000) { while (!predicate(controller.state.value)) delay(5) }
    }

    @Test fun startsMutedAndExplicitMicrophoneActionsAreApplied() = runBlocking {
        createController()
        controller.join("room", "策划会")
        awaitState { it.status == RoomCallStatus.CONNECTED }
        assertTrue(controller.state.value.muted)
        assertTrue(fake.microphoneRequests.isEmpty())
        controller.toggleMicrophone()
        awaitState { !it.muted }
        controller.toggleMicrophone()
        awaitState { it.muted && !it.changingMicrophone }
        assertEquals(listOf(true, false), fake.microphoneRequests)
        assertEquals("Bearer test-session", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun callAndRecordingCannotOwnMicrophoneAtSameTime() = runBlocking {
        createController()
        assertTrue(gate.acquire("recording"))
        controller.join("room", "策划会")
        assertFalse(controller.state.value.active)
        assertEquals(0, server.requestCount)
        gate.release("not-the-owner")
        assertFalse(gate.acquire("another"))
        gate.release("recording")
        controller.join("room", "策划会")
        awaitState { it.status == RoomCallStatus.CONNECTED }
        assertFalse(gate.acquire("recording"))
        controller.leave()
        assertTrue(fake.closed)
        assertTrue(gate.acquire("recording"))
    }

    @Test fun duplicateJoinDoesNotCreateSecondTransportAndLateCallbackIsIgnored() = runBlocking {
        createController()
        controller.join("room", "策划会")
        controller.join("other", "其他会")
        awaitState { it.status == RoomCallStatus.CONNECTED }
        assertEquals(1, fake.connections)
        controller.leave()
        fake.emit(RoomCallStatus.CONNECTED)
        assertFalse(controller.state.value.active)
    }

    @Test fun accountChangeClosesConnectedCallAndReleasesAudio() = runBlocking {
        createController()
        controller.join("room", "策划会")
        awaitState { it.status == RoomCallStatus.CONNECTED }
        sessions.value = session("other")
        awaitState { !it.active }
        assertTrue(fake.closed)
        assertTrue(gate.acquire("recording"))
    }

    @Test fun accountChangeWhileObtainingTicketNeverConnectsOldAccount() = runBlocking {
        createController()
        onTicket = { sessions.value = session("other") }
        controller.join("room", "策划会")
        awaitState { !it.active }
        assertEquals(0, fake.connections)
        assertTrue(gate.acquire("recording"))
    }

    @Test fun endpointChangeWhileObtainingTicketNeverConnectsOldService() = runBlocking {
        createController()
        onTicket = { endpoints.value = "https://other.example/api" }
        controller.join("room", "策划会")
        awaitState { !it.active }
        assertEquals(0, fake.connections)
    }

    @Test fun mismatchedIdentityIsRejectedBeforeTransportCreation() = runBlocking {
        createController()
        ticketIdentity = "someone-else"
        controller.join("room", "策划会")
        awaitState { !it.active }
        assertEquals(0, fake.connections)
    }

    @Test fun hostEndingRoomDisconnectsLocalCall() = runBlocking {
        createController()
        controller.join("room", "策划会")
        awaitState { it.status == RoomCallStatus.CONNECTED }
        roomState = "ended"
        awaitState { !it.active }
        assertTrue(fake.closed)
    }

    @Test fun persistentMembershipFailureDisconnectsAndTransientFailureDoesNot() = runBlocking {
        createController()
        controller.join("room", "策划会")
        awaitState { it.status == RoomCallStatus.CONNECTED }
        failRoom = true
        // Wait for the first membership check, then recover before the third.
        withTimeout(2_000) { while (server.requestCount < 2) delay(2) }
        failRoom = false
        delay(80)
        assertTrue(controller.state.value.active)
        failRoom = true
        awaitState { !it.active }
        assertTrue(fake.closed)
    }

    @Test fun failedMicrophoneChangeKeepsMutedAndAllowsRetry() = runBlocking {
        createController()
        controller.join("room", "策划会")
        awaitState { it.status == RoomCallStatus.CONNECTED }
        fake.microphoneSucceeds = false
        controller.toggleMicrophone()
        awaitState { !it.changingMicrophone }
        assertTrue(controller.state.value.muted)
        assertNotNull(controller.state.value.error)
        fake.microphoneSucceeds = true
        controller.toggleMicrophone()
        awaitState { !it.muted }
    }

    @Test fun disconnectEventClosesTransportAndFreesMicrophone() = runBlocking {
        createController()
        controller.join("room", "策划会")
        awaitState { it.status == RoomCallStatus.CONNECTED }
        fake.emit(RoomCallStatus.IDLE)
        awaitState { !it.active }
        assertTrue(gate.acquire("recording"))
    }

    @Test fun connectionValidationAndLoggingDoNotLeakToken() {
        assertTrue(validRoomCallUrl("wss://rtc.example"))
        assertTrue(validRoomCallUrl("ws://127.0.0.1:7880"))
        assertFalse(validRoomCallUrl("ws://rtc.example"))
        assertFalse(validRoomCallUrl("wss://name:password@rtc.example"))
        assertFalse(validRoomCallUrl("wss://rtc.example?token=secret"))
        assertFalse(RoomMediaSession(token = "secret-token").toString().contains("secret-token"))
    }

    private class FakeTransport : RoomCallTransport {
        var connections = 0
        var closed = false
        var microphoneSucceeds = true
        val microphoneRequests = mutableListOf<Boolean>()
        var callback: ((RoomCallTransportUpdate) -> Unit)? = null
        override suspend fun connect(url: String, token: String, onUpdate: (RoomCallTransportUpdate) -> Unit) {
            connections++
            callback = onUpdate
            emit(RoomCallStatus.CONNECTED)
        }
        fun emit(status: RoomCallStatus) {
            callback?.invoke(RoomCallTransportUpdate(status, listOf(RoomCallParticipant("owner", "成员", true, false, true)), "手机"))
        }
        override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean {
            microphoneRequests += enabled
            return microphoneSucceeds
        }
        override fun close() { closed = true }
    }

    companion object {
        private fun session(id: String) = AuthSession(accessToken = "test-session", tokenType = "bearer",
            expiresAt = Long.MAX_VALUE, user = AccountProfile(id = id, username = id, role = "user",
                isAdmin = false, enabled = true, vipEnabled = false, constructionLogsUnlocked = false, createdAt = 1))
    }
}
