package com.oa.automation.domain.model

import com.google.gson.annotations.SerializedName

/** Control-plane state for a first-party 聆听·策划会 room. */
data class MeetingRoom(
    val id: String = "",
    val code: String = "",
    val title: String = "聆听·策划会",
    @SerializedName("host_id") val hostId: String = "",
    val state: String = "unknown",
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("ended_at") val endedAt: Long? = null,
    val members: List<MeetingRoomMember> = emptyList(),
    @SerializedName("all_recording_consented") val allRecordingConsented: Boolean = false,
    /** False until a WebRTC/RTC media plane is deliberately enabled. */
    @SerializedName("media_ready") val mediaReady: Boolean = false
)

data class MeetingRoomMember(
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("joined_at") val joinedAt: Long = 0,
    @SerializedName("left_at") val leftAt: Long? = null,
    @SerializedName("recording_consent") val recordingConsent: Boolean = false
)

data class MeetingRoomList(
    val rooms: List<MeetingRoom> = emptyList(),
    @SerializedName("media_ready") val mediaReady: Boolean = false
)
