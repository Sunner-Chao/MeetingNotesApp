package com.oa.automation.infrastructure.audio

import android.content.Context
import com.oa.automation.domain.model.RoomCallParticipant
import com.oa.automation.domain.model.RoomCallStatus
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** SDK owns Opus/WebRTC, echo cancellation and audio-device selection. No second recorder. */
class LiveKitRoomCallTransport(context: Context) : RoomCallTransport {
    private val room = LiveKit.create(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var eventsJob: Job? = null
    private var callback: ((RoomCallTransportUpdate) -> Unit)? = null

    override suspend fun connect(url: String, token: String, onUpdate: (RoomCallTransportUpdate) -> Unit) {
        callback = onUpdate
        eventsJob = scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Disconnected, is RoomEvent.FailedToConnect ->
                        callback?.invoke(RoomCallTransportUpdate(RoomCallStatus.IDLE))
                    else -> emitSnapshot()
                }
            }
        }
        // Join listening first; only an explicit microphone tap publishes an audio track.
        room.connect(url, token, ConnectOptions(audio = false, video = false, autoSubscribe = true))
        emitSnapshot()
    }

    private fun emitSnapshot() {
        val participants = listOf(room.localParticipant) + room.remoteParticipants.values
        val status = when (room.state) {
            Room.State.CONNECTED -> RoomCallStatus.CONNECTED
            Room.State.CONNECTING -> RoomCallStatus.CONNECTING
            Room.State.RECONNECTING -> RoomCallStatus.RECONNECTING
            Room.State.DISCONNECTED -> RoomCallStatus.IDLE
        }
        callback?.invoke(RoomCallTransportUpdate(status, participants.map {
            RoomCallParticipant(it.identity?.value.orEmpty(), it.name.orEmpty().ifBlank { "成员" },
                !it.isMicrophoneEnabled, it.isSpeaking, it == room.localParticipant)
        }, room.audioSwitchHandler?.selectedAudioDevice?.name))
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        val changed = room.localParticipant.setMicrophoneEnabled(enabled)
        emitSnapshot()
        return changed
    }

    override fun close() {
        callback = null
        eventsJob?.cancel()
        room.release()
        scope.cancel()
    }
}
