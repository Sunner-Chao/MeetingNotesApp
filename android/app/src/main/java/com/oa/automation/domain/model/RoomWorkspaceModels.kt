package com.oa.automation.domain.model

import com.google.gson.annotations.SerializedName

data class RoomTranscriptionState(
    val state: String = "idle",
    @SerializedName("origin_ms") val originMs: Long = 0,
    val revision: Long = 0,
    val available: Boolean = false,
    val message: String = ""
) {
    val active: Boolean get() = state in setOf("starting", "running", "stopping")
}

data class RoomTranscriptSegment(
    val id: Long = 0,
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("start_ms") val startMs: Long = 0,
    @SerializedName("end_ms") val endMs: Long = 0,
    val text: String = "",
    val finalized: Boolean = false,
    val revision: Long = 0
)

data class RoomSharedReport(
    @SerializedName("request_id") val requestId: String = "",
    val state: String = "",
    @SerializedName("source_revision") val sourceRevision: Long = 0,
    val text: String = "",
    val message: String = ""
) {
    val generating: Boolean get() = state == "queued" || state == "generating"
}

data class RoomWorkspacePage(
    @SerializedName("room_id") val roomId: String = "",
    @SerializedName("snapshot_key") val snapshotKey: String = "",
    val transcription: RoomTranscriptionState = RoomTranscriptionState(),
    val segments: List<RoomTranscriptSegment> = emptyList(),
    @SerializedName("next_cursor") val nextCursor: Long = 0,
    @SerializedName("has_more") val hasMore: Boolean = false,
    val report: RoomSharedReport? = null
)

data class RoomWorkspaceState(
    val roomId: String? = null,
    val snapshotKey: String = "",
    val cursor: Long = 0,
    val transcription: RoomTranscriptionState = RoomTranscriptionState(),
    val segments: List<RoomTranscriptSegment> = emptyList(),
    val report: RoomSharedReport? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null
) {
    fun accept(page: RoomWorkspacePage): RoomWorkspaceState {
        require(page.roomId == roomId) { "房间内容不匹配" }
        require(page.nextCursor >= cursor) { "转录同步游标异常" }
        val merged = (segments + page.segments).groupBy { it.id }.values.map { versions -> versions.maxBy { it.revision } }
            .sortedWith(compareBy<RoomTranscriptSegment> { it.startMs }.thenBy { it.id })
        return copy(snapshotKey = page.snapshotKey, cursor = page.nextCursor, transcription = page.transcription,
            segments = merged, report = page.report, loading = page.hasMore, error = null)
    }
}
