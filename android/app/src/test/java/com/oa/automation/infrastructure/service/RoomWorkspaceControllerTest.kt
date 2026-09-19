package com.oa.automation.infrastructure.service

import com.oa.automation.domain.model.*
import com.oa.automation.infrastructure.account.AccountApiService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RoomWorkspaceControllerTest {
    private val server = MockWebServer()
    private val sessions = MutableStateFlow<AuthSession?>(session("owner"))
    private val endpoints = MutableStateFlow("")
    private lateinit var scope: CoroutineScope
    private lateinit var controller: RoomWorkspaceController
    @Volatile private var snapshot = "a"
    @Volatile private var revision = 1L
    @Volatile private var status = 200
    @Volatile private var responseDelay = 0L
    private val reports = AtomicInteger()
    private val cursors = java.util.Collections.synchronizedList(mutableListOf<Long>())

    @Before fun setup() {
        server.start()
        endpoints.value = server.url("/api").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.endsWith("/report")) {
                    reports.incrementAndGet()
                    return MockResponse().setBody("""{"request_id":"new","state":"queued"}""")
                        .setBodyDelay(150, TimeUnit.MILLISECONDS)
                }
                val cursor = request.requestUrl!!.queryParameter("after")!!.toLong()
                cursors.add(cursor)
                if (status != 200) return MockResponse().setResponseCode(status).setBody("""{"detail":"不可用"}""")
                val roomId = request.requestUrl!!.pathSegments[3]
                val rows = if (cursor < revision) """[{"id":1,"revision":$revision,"text":"内容$revision","start_ms":0,"end_ms":1000,"finalized":true}]""" else "[]"
                val report = if (reports.get() > 0) """{"request_id":"new","state":"queued"}""" else """{"request_id":"old","state":"ready","text":"原稿"}"""
                return MockResponse().setBody("""{"room_id":"$roomId","snapshot_key":"$snapshot","segments":$rows,"next_cursor":${maxOf(cursor, revision)},"transcription":{"state":"paused","available":true},"report":$report}""")
                    .setBodyDelay(responseDelay, TimeUnit.MILLISECONDS)
            }
        }
    }

    @After fun teardown() {
        if (::controller.isInitialized) controller.close()
        if (::scope.isInitialized) scope.cancel()
        server.shutdown()
    }

    private fun CoroutineScope.create() {
        scope = CoroutineScope(coroutineContext + SupervisorJob())
        controller = RoomWorkspaceController(sessions, endpoints, AccountApiService(), scope, 30)
    }

    private suspend fun awaitState(predicate: (RoomWorkspaceState) -> Boolean) {
        withTimeout(4_000) { while (!predicate(controller.state.value)) delay(5) }
    }

    @Test fun updatesSameSegmentWithoutDuplication() = runBlocking {
        create()
        controller.open("room")
        awaitState { it.cursor == 1L }
        revision = 2L
        awaitState { it.cursor == 2L }
        assertEquals(listOf("内容2"), controller.state.value.segments.map { it.text })
        assertEquals("Bearer test-session", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun membershipSnapshotChangeRebuildsFromZero() = runBlocking {
        create()
        controller.open("room")
        awaitState { it.cursor == 1L }
        val initialReads = cursors.count { it == 0L }
        snapshot = "after-account-deletion"
        awaitState { it.snapshotKey == snapshot && it.cursor == 1L }
        assertTrue(cursors.count { it == 0L } > initialReads)
    }

    @Test fun accountSwitchClearsContentAndLateResponse() = runBlocking {
        create()
        controller.open("room")
        awaitState { it.segments.isNotEmpty() }
        sessions.value = session("other")
        awaitState { it.roomId == null }
        delay(100)
        assertTrue(controller.state.value.segments.isEmpty())
    }

    @Test fun newRoomDoesNotReceiveOldRoomResponse() = runBlocking {
        create()
        responseDelay = 150
        controller.open("first")
        withTimeout(2_000) { while (server.requestCount == 0) delay(5) }
        controller.open("second")
        awaitState { it.roomId == "second" && !it.loading }
        assertEquals("second", controller.state.value.roomId)
        assertEquals(1, controller.state.value.segments.size)
    }

    @Test fun temporaryFailureRetainsContentButRemovedMembershipClearsIt() = runBlocking {
        create()
        controller.open("room")
        awaitState { it.segments.isNotEmpty() }
        status = 503
        awaitState { it.error != null }
        assertEquals(1, controller.state.value.segments.size)
        status = 403
        awaitState { it.roomId == null }
        assertTrue(controller.state.value.segments.isEmpty())
        val requests = server.requestCount
        delay(100)
        assertEquals(requests, server.requestCount)
    }

    @Test fun duplicateReportTapQueuesOnceAndRemovesPreviousBody() = runBlocking {
        create()
        controller.open("room")
        awaitState { it.report?.state == "ready" }
        controller.generateReport()
        controller.generateReport()
        awaitState { it.report?.state == "queued" }
        assertEquals(1, reports.get())
        assertEquals("", controller.state.value.report?.text)
    }

    @Test fun mergingKeepsLatestRevisionAndChronologicalOrder() {
        val state = RoomWorkspaceState(roomId = "room", cursor = 1, segments = listOf(
            RoomTranscriptSegment(id = 1, revision = 1, startMs = 2000, text = "旧")))
        val merged = state.accept(RoomWorkspacePage(roomId = "room", nextCursor = 3, segments = listOf(
            RoomTranscriptSegment(id = 1, revision = 3, startMs = 2000, text = "新"),
            RoomTranscriptSegment(id = 2, revision = 2, startMs = 1000, text = "先"))))
        assertEquals(listOf("先", "新"), merged.segments.map { it.text })
    }

    companion object {
        private fun session(id: String) = AuthSession(accessToken = "test-session", tokenType = "bearer",
            expiresAt = Long.MAX_VALUE, user = AccountProfile(id = id, username = id, role = "user",
                isAdmin = false, enabled = true, vipEnabled = false, constructionLogsUnlocked = false, createdAt = 1))
    }
}
