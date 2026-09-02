package com.oa.automation.domain.model

/** Local-only project lifecycle. DELETED is a soft-delete marker for auditability. */
enum class ProjectStatus {
    ACTIVE,
    ARCHIVED,
    DELETED;

    companion object {
        fun fromStorage(value: String): ProjectStatus =
            entries.firstOrNull { it.name == value } ?: ACTIVE
    }
}

data class Project(
    val id: String,
    val name: String,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val archivedAt: Long? = null,
    val deletedAt: Long? = null
)

/** A relationship only; it never copies the meeting's audio, images, transcript, or report. */
data class ProjectMeetingLink(
    val projectId: String,
    val meetingId: String,
    val linkedAt: Long = System.currentTimeMillis(),
    val removedAt: Long? = null
)

/** Readable projection of a report task while retaining its source for traceability. */
data class ProjectTaskRef(
    val id: String,
    val projectId: String,
    val sourceMeetingId: String,
    val sourceReportId: String,
    val sourceKey: String,
    val content: String,
    val assignee: String? = null,
    val due: String? = null,
    val priority: String? = null,
    val completed: Boolean = false,
    val manuallyEdited: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

/** Readable projection of a report risk; model output remains distinguishable from edits. */
data class ProjectRiskRef(
    val id: String,
    val projectId: String,
    val sourceMeetingId: String,
    val sourceReportId: String,
    val sourceKey: String,
    val content: String,
    val detail: String? = null,
    val status: String? = null,
    val manuallyEdited: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

/** A decision record can be confirmed or left pending without changing the source report. */
data class ProjectDecisionRef(
    val id: String,
    val projectId: String,
    val sourceMeetingId: String,
    val sourceReportId: String,
    val sourceKey: String,
    val content: String,
    val confirmed: Boolean = false,
    val manuallyEdited: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

/** Rebuildable read model for a project overview; never a replacement for source refs. */
data class ProjectAggregateSnapshot(
    val id: String,
    val projectId: String,
    val sourceMeetingCount: Int,
    val openTaskCount: Int,
    val openRiskCount: Int,
    val pendingDecisionCount: Int,
    val generatedAt: Long = System.currentTimeMillis()
)
