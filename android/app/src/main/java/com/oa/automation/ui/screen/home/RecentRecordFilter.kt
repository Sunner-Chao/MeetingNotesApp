package com.oa.automation.ui.screen.home

import com.oa.automation.domain.model.MeetingOrigin

/** Record kind shown in the 最近记录 type filter. */
internal enum class RecentRecordType(val label: String) {
    ALL("全部"),
    LISTEN("即刻倾听"),
    IMPORT("顷刻成稿")
}

/** Completion state shown in the 最近记录 status filter. */
internal enum class RecentRecordStatus(val label: String) {
    ALL("全部"),
    DONE("已完成"),
    PENDING("待完善")
}

/** Ordering offered by the 最近记录 time control. */
internal enum class RecentRecordSort(val label: String) {
    NEWEST("最近更新"),
    OLDEST("最早优先"),
    LONGEST("时长最长")
}

internal data class RecentRecordFilter(
    val type: RecentRecordType = RecentRecordType.ALL,
    val status: RecentRecordStatus = RecentRecordStatus.ALL,
    val sort: RecentRecordSort = RecentRecordSort.NEWEST
) {
    val isDefault: Boolean
        get() = type == RecentRecordType.ALL &&
            status == RecentRecordStatus.ALL &&
            sort == RecentRecordSort.NEWEST
}

/** Counts shown next to each filter chip, always over the unfiltered list. */
internal data class RecentRecordCounts(
    val total: Int = 0,
    val listen: Int = 0,
    val importParsed: Int = 0,
    val done: Int = 0,
    val pending: Int = 0
)

internal fun MeetingOrigin.toRecentRecordType(): RecentRecordType = when (this) {
    MeetingOrigin.FILE_IMPORT -> RecentRecordType.IMPORT
    MeetingOrigin.QUICK,
    MeetingOrigin.SCHEDULED -> RecentRecordType.LISTEN
}

internal fun recentRecordCounts(items: List<MeetingWithReport>): RecentRecordCounts =
    RecentRecordCounts(
        total = items.size,
        listen = items.count { it.meeting.origin.toRecentRecordType() == RecentRecordType.LISTEN },
        importParsed = items.count { it.meeting.origin.toRecentRecordType() == RecentRecordType.IMPORT },
        done = items.count { it.hasReport },
        pending = items.count { !it.hasReport }
    )

/**
 * Applies the 最近记录 filter and ordering.
 *
 * A meeting that is currently recording is always kept and always pinned to the
 * top, whatever the filter says, so the in-progress session can never be
 * filtered out of reach.
 */
internal fun applyRecentRecordFilter(
    items: List<MeetingWithReport>,
    filter: RecentRecordFilter,
    activeRecordingMeetingId: String? = null
): List<MeetingWithReport> {
    val matching = items.filter { item ->
        val isActive = activeRecordingMeetingId != null &&
            item.meeting.id == activeRecordingMeetingId
        isActive || (matchesType(item, filter.type) && matchesStatus(item, filter.status))
    }
    val ordered = when (filter.sort) {
        RecentRecordSort.NEWEST -> matching.sortedByDescending { it.meeting.createdAt }
        RecentRecordSort.OLDEST -> matching.sortedBy { it.meeting.createdAt }
        RecentRecordSort.LONGEST -> matching.sortedWith(
            compareByDescending<MeetingWithReport> { it.meeting.durationMs }
                .thenByDescending { it.meeting.createdAt }
        )
    }
    if (activeRecordingMeetingId == null) return ordered
    return ordered.sortedBy { it.meeting.id != activeRecordingMeetingId }
}

private fun matchesType(item: MeetingWithReport, type: RecentRecordType): Boolean =
    type == RecentRecordType.ALL || item.meeting.origin.toRecentRecordType() == type

private fun matchesStatus(item: MeetingWithReport, status: RecentRecordStatus): Boolean =
    when (status) {
        RecentRecordStatus.ALL -> true
        RecentRecordStatus.DONE -> item.hasReport
        RecentRecordStatus.PENDING -> !item.hasReport
    }
