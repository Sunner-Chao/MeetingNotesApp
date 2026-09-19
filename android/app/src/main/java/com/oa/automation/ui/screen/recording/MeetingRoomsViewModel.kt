package com.oa.automation.ui.screen.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AuthSession
import com.oa.automation.domain.model.MeetingRoom
import com.oa.automation.infrastructure.account.AccountApiService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MeetingRoomsUiState(
    val userId: String = "",
    val rooms: List<MeetingRoom> = emptyList(),
    val selected: MeetingRoom? = null,
    val busy: Boolean = false,
    val error: String? = null
)

/** Room membership uses the existing login session, with no independent room credential. */
class MeetingRoomsViewModel(
    config: ConfigDataStore,
    private val api: AccountApiService
) : ViewModel() {
    private val mutableState = MutableStateFlow(MeetingRoomsUiState())
    val state = mutableState.asStateFlow()
    private var session: AuthSession? = null
    private var endpoint = ""
    private var request: Job? = null
    private var polling: Job? = null
    private var revision = 0L
    private var visible = false
    private var requestedRoomId: String? = null

    init {
        viewModelScope.launch {
            combine(config.authSessionFlow, config.accountEndpointFlow) { account, address -> account to address }
                .collect { (account, address) ->
                    val changed = account?.user?.id != session?.user?.id || address != endpoint
                    session = account
                    endpoint = address
                    if (changed) {
                        revision++
                        request?.cancel()
                        requestedRoomId = null
                        mutableState.value = MeetingRoomsUiState(userId = account?.user?.id.orEmpty())
                        if (visible) refresh()
                    }
                }
        }
    }

    fun open(roomId: String? = null) {
        visible = true
        requestedRoomId = roomId
        mutableState.update { it.copy(selected = it.rooms.firstOrNull { room -> room.id == roomId }) }
        refresh()
        polling?.cancel()
        polling = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                if (mutableState.value.busy) continue
                val account = session ?: continue
                val generation = revision
                val result = api.meetingRooms(endpoint, account.accessToken)
                if (generation != revision || !visible) continue
                result.onSuccess { response ->
                    mutableState.update { current ->
                        current.copy(
                            rooms = response.rooms,
                            selected = response.rooms.firstOrNull { it.id == current.selected?.id },
                            error = null
                        )
                    }
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    mutableState.update { it.copy(error = "房间信息暂未同步，可点击刷新") }
                }
            }
        }
    }

    fun close() {
        visible = false
        revision++
        polling?.cancel()
        polling = null
        request?.cancel()
        request = null
        mutableState.update { it.copy(busy = false) }
    }
    fun showList() { requestedRoomId = null; mutableState.update { it.copy(selected = null, error = null) } }

    fun refresh() = execute { account, address, state ->
        val selectedId = requestedRoomId ?: state.selected?.id
        val rooms = api.meetingRooms(address, account.accessToken).getOrThrow().rooms
        state.copy(rooms = rooms, selected = rooms.firstOrNull { room -> room.id == selectedId })
    }

    fun select(room: MeetingRoom) = execute { account, address, state ->
        state.accept(api.meetingRoom(address, account.accessToken, room.id).getOrThrow())
    }

    fun create(title: String, consent: Boolean) = execute { account, address, state ->
        state.accept(api.createMeetingRoom(address, account.accessToken, title, consent).getOrThrow())
    }

    fun join(code: String, consent: Boolean) = execute { account, address, state ->
        require(code.matches(Regex("[0-9]{9}"))) { "请输入九位会议号" }
        state.accept(api.joinMeetingRoom(address, account.accessToken, code, consent).getOrThrow())
    }

    fun consent(value: Boolean) = execute { account, address, state ->
        val room = state.selected ?: return@execute state
        state.accept(api.consentMeetingRoomRecording(address, account.accessToken, room.id, value).getOrThrow())
    }

    fun end() = execute { account, address, state ->
        val room = state.selected ?: return@execute state
        state.accept(api.endMeetingRoom(address, account.accessToken, room.id).getOrThrow())
    }

    fun leave() = execute { account, address, state ->
        val room = state.selected ?: return@execute state
        api.leaveMeetingRoom(address, account.accessToken, room.id).getOrThrow()
        state.copy(selected = null, rooms = state.rooms.filterNot { it.id == room.id })
    }

    private fun MeetingRoomsUiState.accept(room: MeetingRoom) =
        copy(selected = room, rooms = (rooms.filterNot { it.id == room.id } + room).sortedByDescending { it.createdAt })

    private fun execute(block: suspend (AuthSession, String, MeetingRoomsUiState) -> MeetingRoomsUiState) {
        if (mutableState.value.busy) return
        val account = session ?: run {
            mutableState.update { it.copy(error = "登录后即可创建或加入会议房间") }
            return
        }
        val address = endpoint
        val generation = ++revision
        mutableState.update { it.copy(busy = true, error = null) }
        request = viewModelScope.launch {
            try {
                val next = block(account, address, mutableState.value)
                if (generation == revision && visible) {
                    requestedRoomId = next.selected?.id
                    mutableState.value = next
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (generation == revision) mutableState.update { it.copy(error = error.message ?: "房间暂时无法连接，请重试") }
            } finally {
                if (generation == revision) mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
