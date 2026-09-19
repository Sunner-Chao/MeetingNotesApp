package com.oa.automation.infrastructure.service

import com.oa.automation.domain.model.*
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.infrastructure.account.AccountHttpException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Server owns content; screen lifecycle only controls lightweight incremental reads. */
class RoomWorkspaceController(
    private val sessions: Flow<AuthSession?>,
    private val endpoints: Flow<String>,
    private val api: AccountApiService,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 1_000
) {
    private val mutableState = MutableStateFlow(RoomWorkspaceState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var polling: Job? = null
    private var action: Job? = null
    private var owner: Pair<String, String>? = null

    init {
        scope.launch {
            combine(sessions, endpoints) { session, endpoint -> session?.user?.id.orEmpty() to endpoint }
                .distinctUntilChanged().collect { identity ->
                    if (owner != null && owner != identity) close()
                }
        }
    }

    fun open(roomId: String) {
        close()
        mutableState.value = RoomWorkspaceState(roomId = roomId, loading = true)
        val revision = generation
        polling = scope.launch {
            while (isActive && revision == generation) {
                if (!state.value.busy) refresh(revision)
                delay(pollIntervalMs)
            }
        }
    }

    fun close() {
        generation++
        polling?.cancel()
        action?.cancel()
        owner = null
        mutableState.value = RoomWorkspaceState()
    }

    private suspend fun refresh(revision: Long) {
        try {
            val session = sessions.first() ?: error("登录后即可查看房间内容")
            val endpoint = endpoints.first()
            owner = session.user.id to endpoint
            var more: Boolean
            do {
                val before = state.value
                val roomId = before.roomId ?: return
                val page = api.roomWorkspace(endpoint, session.accessToken, roomId, before.cursor).getOrThrow()
                if (!current(revision, session.user.id, endpoint)) return
                if (before.snapshotKey.isNotBlank() && before.snapshotKey != page.snapshotKey) {
                    // Membership/account deletion may remove old rows: rebuild rather than retain a ghost copy.
                    mutableState.value = RoomWorkspaceState(roomId = roomId, loading = true, busy = before.busy)
                    more = true
                } else {
                    check(!page.hasMore || page.nextCursor > before.cursor) { "转录同步暂不可用" }
                    mutableState.value = state.value.accept(page)
                    more = page.hasMore
                }
            } while (more && revision == generation)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (revision == generation && !clearUnavailable(error)) {
                mutableState.update { it.copy(loading = false, error = "内容暂未同步，正在重试") }
            }
        }
    }

    private fun clearUnavailable(error: Exception): Boolean {
        if (error !is AccountHttpException || error.statusCode !in setOf(401, 403, 404)) return false
        // Membership removal must clear the on-screen copy; a network outage may retain it.
        close()
        mutableState.value = RoomWorkspaceState(error = "暂时无法查看该房间，请返回房间列表")
        return true
    }

    private suspend fun current(revision: Long, userId: String, endpoint: String) =
        revision == generation && sessions.first()?.user?.id == userId && endpoints.first() == endpoint

    fun transcription(start: Boolean) = operate { endpoint, session, roomId ->
        val result = api.roomTranscription(endpoint, session.accessToken, roomId, start).getOrThrow()
        val update: (RoomWorkspaceState) -> RoomWorkspaceState = { it.copy(transcription = result) }
        update
    }

    fun generateReport() = operate { endpoint, session, roomId ->
        val result = api.requestRoomReport(endpoint, session.accessToken, roomId).getOrThrow()
        val update: (RoomWorkspaceState) -> RoomWorkspaceState = { it.copy(report = result) }
        update
    }

    private fun operate(block: suspend (String, AuthSession, String) -> (RoomWorkspaceState) -> RoomWorkspaceState) {
        if (state.value.busy) return
        val roomId = state.value.roomId ?: return
        val revision = ++generation
        polling?.cancel()
        mutableState.update { it.copy(busy = true, error = null) }
        action = scope.launch {
            try {
                val account = sessions.first() ?: error("请先登录")
                val endpoint = endpoints.first()
                owner = account.user.id to endpoint
                val update = block(endpoint, account, roomId)
                if (current(revision, account.user.id, endpoint)) mutableState.update(update)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (revision == generation) mutableState.update { it.copy(error = error.message ?: "操作未完成，请重试") }
            } finally {
                if (revision == generation) {
                    mutableState.update { it.copy(busy = false) }
                    polling = scope.launch {
                        while (isActive && revision == generation) {
                            refresh(revision)
                            delay(pollIntervalMs)
                        }
                    }
                }
            }
        }
    }
}
