package com.oa.automation.infrastructure.audio

import com.oa.automation.domain.model.RoomCallParticipant
import com.oa.automation.domain.model.RoomCallStatus

data class RoomCallTransportUpdate(
    val status: RoomCallStatus,
    val participants: List<RoomCallParticipant> = emptyList(),
    val deviceName: String? = null
)

interface RoomCallTransport {
    suspend fun connect(url: String, token: String, onUpdate: (RoomCallTransportUpdate) -> Unit)
    suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean
    fun close()
}
