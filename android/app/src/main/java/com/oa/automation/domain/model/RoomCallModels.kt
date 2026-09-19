package com.oa.automation.domain.model

import com.google.gson.annotations.SerializedName

data class RoomMediaSession(
    val url: String = "",
    val token: String = "",
    @SerializedName("room_name") val roomName: String = "",
    val identity: String = "",
    @SerializedName("expires_at") val expiresAt: Long = 0
) {
    override fun toString() = "RoomMediaSession(roomName=$roomName, identity=$identity, token=<redacted>)"
}

enum class RoomCallStatus { IDLE, CONNECTING, CONNECTED, RECONNECTING }

data class RoomCallParticipant(
    val id: String, val name: String, val muted: Boolean, val speaking: Boolean, val isSelf: Boolean
)

data class RoomCallState(
    val roomId: String? = null,
    val title: String = "",
    val status: RoomCallStatus = RoomCallStatus.IDLE,
    val muted: Boolean = true,
    val changingMicrophone: Boolean = false,
    val participants: List<RoomCallParticipant> = emptyList(),
    val deviceName: String? = null,
    val error: String? = null
) {
    val active: Boolean get() = status != RoomCallStatus.IDLE
}
