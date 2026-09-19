package com.oa.automation.infrastructure.service

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.*
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.infrastructure.audio.AudioSessionGate
import com.oa.automation.infrastructure.audio.RoomCallTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Application-scoped call state; UI dismissal never ends a call. */
class RoomCallController internal constructor(
    private val sessions: Flow<AuthSession?>,
    private val endpoints: Flow<String>,
    private val api: AccountApiService,
    private val audioGate: AudioSessionGate,
    private val transportFactory: () -> RoomCallTransport,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 5_000
) {
    constructor(config: ConfigDataStore, api: AccountApiService, audioGate: AudioSessionGate,
        factory: () -> RoomCallTransport) : this(config.authSessionFlow, config.accountEndpointFlow,
        api, audioGate, factory, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    private val mutableState = MutableStateFlow(RoomCallState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var connection: Job? = null
    private var monitor: Job? = null
    private var microphone: Job? = null
    private var transport: RoomCallTransport? = null
    private var callOwner: String? = null
    private var callEndpoint: String? = null
    private var lease: String? = null

    init {
        scope.launch {
            combine(sessions, endpoints) { account, endpoint -> account?.user?.id to endpoint }
                .distinctUntilChanged().collect { (owner, endpoint) ->
                    if (callOwner != null && (owner != callOwner || endpoint != callEndpoint)) {
                        leave("账号或服务已切换，已离开通话")
                    }
                }
        }
    }

    fun join(roomId: String, title: String) {
        if (state.value.active) return
        val revision = ++generation
        val candidate = "call:$revision"
        if (!audioGate.acquire(candidate)) {
            mutableState.value = RoomCallState(error = "请先完成当前录音，再加入通话")
            return
        }
        lease = candidate
        mutableState.value = RoomCallState(roomId = roomId, title = title, status = RoomCallStatus.CONNECTING)
        connection = scope.launch {
            try {
                val account = sessions.first() ?: error("登录后才能加入通话")
                val endpoint = endpoints.first()
                callOwner = account.user.id
                callEndpoint = endpoint
                val ticket = api.meetingRoomMediaSession(endpoint, account.accessToken, roomId).getOrThrow()
                require(ticket.identity == account.user.id && ticket.token.isNotBlank() &&
                    ticket.roomName == "meeting-$roomId" && validRoomCallUrl(ticket.url)) { "通话连接信息无效" }
                ensureCurrent(revision, account.user.id, endpoint)
                val activeTransport = transportFactory()
                transport = activeTransport
                withTimeout(15_000) {
                    activeTransport.connect(ticket.url, ticket.token) { update ->
                        if (revision != generation) return@connect
                        if (update.status == RoomCallStatus.IDLE) {
                            scope.launch { if (revision == generation) leave("通话已断开，可重新加入") }
                        } else mutableState.update { current -> current.copy(
                            status = update.status, participants = update.participants,
                            muted = update.participants.firstOrNull { it.isSelf }?.muted ?: current.muted,
                            deviceName = update.deviceName
                        ) }
                    }
                }
                ensureCurrent(revision, account.user.id, endpoint)
                monitor = scope.launch { monitorMembership(revision, account.user.id, endpoint, roomId) }
            } catch (error: Exception) {
                if (revision != generation) return@launch
                leave(if (error is TimeoutCancellationException) "通话连接超时，请重试"
                    else if (error is CancellationException) null else "暂时无法加入通话，请检查网络或服务配置")
            }
        }
    }

    private suspend fun ensureCurrent(revision: Long, owner: String, endpoint: String) {
        check(revision == generation && sessions.first()?.user?.id == owner && endpoints.first() == endpoint)
    }

    private suspend fun monitorMembership(revision: Long, owner: String, endpoint: String, roomId: String) {
        var failures = 0
        while (currentCoroutineContext().isActive && revision == generation) {
            delay(pollIntervalMs)
            val account = sessions.first()
            if (account?.user?.id != owner || endpoints.first() != endpoint) {
                leave("账号已切换，已离开通话")
                return
            }
            val result = api.meetingRoom(endpoint, account.accessToken, roomId)
            if (revision != generation) return
            val room = result.getOrNull()
            if (room != null) {
                failures = 0
                if (room.state != "open" || room.members.none { it.userId == owner && it.leftAt == null }) {
                    leave("房间已结束或你已离开")
                    return
                }
            } else if (++failures >= 3) {
                leave("房间持续无法同步，已离开通话")
                return
            }
        }
    }

    fun toggleMicrophone() {
        if (state.value.status != RoomCallStatus.CONNECTED || state.value.changingMicrophone) return
        val revision = generation
        val enabled = state.value.muted
        val current = transport ?: return
        mutableState.update { it.copy(changingMicrophone = true, error = null) }
        microphone = scope.launch {
            try {
                check(withTimeout(10_000) { current.setMicrophoneEnabled(enabled) })
                if (revision == generation) mutableState.update { it.copy(muted = !enabled) }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (revision == generation) mutableState.update { it.copy(error = "麦克风切换失败，请重试") }
            } finally {
                if (revision == generation) mutableState.update { it.copy(changingMicrophone = false) }
            }
        }
    }

    fun leave(reason: String? = null) {
        generation++
        connection?.cancel()
        monitor?.cancel()
        microphone?.cancel()
        runCatching { transport?.close() }
        transport = null
        lease?.let(audioGate::release)
        lease = null
        callOwner = null
        callEndpoint = null
        mutableState.value = RoomCallState(error = reason)
    }
}

internal fun validRoomCallUrl(url: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    uri.host != null && uri.userInfo == null && uri.query == null &&
        (uri.scheme == "wss" || (uri.scheme == "ws" && uri.host in setOf("127.0.0.1", "localhost", "[::1]")))
}.getOrDefault(false)
