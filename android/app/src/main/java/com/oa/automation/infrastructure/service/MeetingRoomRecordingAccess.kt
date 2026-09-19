package com.oa.automation.infrastructure.service

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AuthSession
import com.oa.automation.domain.model.MeetingRoom
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.infrastructure.account.AccountApiService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/** Identity of an explicitly room-bound recording; never contains a credential. */
data class RoomRecordingBinding(val meetingId: String, val roomId: String, val ownerId: String, val endpoint: String)

internal fun requireRoomRecordingConsent(room: MeetingRoom, userId: String) {
    require(userId.isNotBlank()) { "请登录后再使用会议房间" }
    require(room.state == "open") { "房间已结束，请选择其他房间后再开始" }
    val activeMembers = room.members.filter { it.leftAt == null }
    require(activeMembers.any { it.userId == userId }) { "你已离开房间，请重新加入后再开始" }
    require(activeMembers.all { it.recordingConsent } && room.allRecordingConsented) {
        "还有成员未同意录音，请在会议房间中确认"
    }
}

/** Shared by foreground start/resume and background consent monitoring. */
class MeetingRoomRecordingAccess internal constructor(
    private val sessions: Flow<AuthSession?>,
    private val endpoints: Flow<String>,
    private val meetings: MeetingRepository,
    private val api: AccountApiService
) {
    constructor(config: ConfigDataStore, meetings: MeetingRepository, api: AccountApiService) :
        this(config.authSessionFlow, config.accountEndpointFlow, meetings, api)

    suspend fun bind(meetingId: String, roomId: String?) {
        val account = sessions.first() ?: error("登录后才能关联会议房间")
        val endpoint = endpoints.first()
        if (roomId != null) {
            val room = withTimeoutOrNull(5_000) {
                api.meetingRoom(endpoint, account.accessToken, roomId).getOrThrow()
            } ?: error("房间暂时无法同步，请恢复网络后重试")
            require(room.id == roomId && room.state == "open") { "房间已结束或不可用" }
            require(room.members.any { it.userId == account.user.id && it.leftAt == null }) { "请先加入这个房间" }
        }
        require(sessions.first()?.user?.id == account.user.id &&
            endpoints.first() == endpoint) { "账号或服务已切换，请重新打开记录" }
        meetings.bindRoom(meetingId, roomId, account.user.id).getOrThrow()
    }

    suspend fun forStart(meetingId: String): RoomRecordingBinding? {
        val meeting = meetings.findById(meetingId).getOrThrow() ?: error("记录不存在或账号已切换")
        val roomId = meeting.meetingRoomId ?: return null
        val account = sessions.first() ?: error("请登录后再使用会议房间")
        require(meeting.ownerId == account.user.id) { "账号已切换，请重新打开记录" }
        return RoomRecordingBinding(meetingId, roomId, account.user.id, endpoints.first())
            .also { validate(it) }
    }

    suspend fun validate(binding: RoomRecordingBinding) {
        val account = sessions.first() ?: error("账号已退出，房间录音已暂停")
        require(account.user.id == binding.ownerId && endpoints.first() == binding.endpoint) {
            "账号或服务已切换，请重新打开记录"
        }
        val room = withTimeoutOrNull(5_000) {
            api.meetingRoom(binding.endpoint, account.accessToken, binding.roomId).getOrThrow()
        } ?: error("房间暂时无法同步，请恢复网络后再继续")
        require(room.id == binding.roomId) { "房间信息不一致，请重新打开房间" }
        requireRoomRecordingConsent(room, binding.ownerId)
        val meeting = meetings.findById(binding.meetingId).getOrThrow()
        require(sessions.first()?.user?.id == binding.ownerId &&
            endpoints.first() == binding.endpoint &&
            meeting?.ownerId == binding.ownerId && meeting.meetingRoomId == binding.roomId) {
            "账号或房间关联已变化，请重新打开记录"
        }
    }
}
