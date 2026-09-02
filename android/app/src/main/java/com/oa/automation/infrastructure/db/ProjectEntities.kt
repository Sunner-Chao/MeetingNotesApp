package com.oa.automation.infrastructure.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.oa.automation.domain.model.Project
import com.oa.automation.domain.model.ProjectDecisionRef
import com.oa.automation.domain.model.ProjectMeetingLink
import com.oa.automation.domain.model.ProjectRiskRef
import com.oa.automation.domain.model.ProjectStatus
import com.oa.automation.domain.model.ProjectTaskRef
import com.oa.automation.domain.model.ProjectAggregateSnapshot

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?,
    val deletedAt: Long?
)

@Entity(
    tableName = "project_meeting_links",
    primaryKeys = ["projectId", "meetingId"],
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MeetingEntity::class,
            parentColumns = ["id"],
            childColumns = ["meetingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("meetingId")]
)
data class ProjectMeetingLinkEntity(
    val projectId: String,
    val meetingId: String,
    val linkedAt: Long,
    val removedAt: Long?
)

@Entity(
    tableName = "project_task_refs",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("projectId"),
        Index(value = ["projectId", "sourceReportId", "sourceKey"], unique = true)
    ]
)
data class ProjectTaskRefEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val sourceMeetingId: String,
    val sourceReportId: String,
    val sourceKey: String,
    val content: String,
    val assignee: String?,
    val due: String?,
    val priority: String?,
    val completed: Boolean,
    val manuallyEdited: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "project_risk_refs",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("projectId"),
        Index(value = ["projectId", "sourceReportId", "sourceKey"], unique = true)
    ]
)
data class ProjectRiskRefEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val sourceMeetingId: String,
    val sourceReportId: String,
    val sourceKey: String,
    val content: String,
    val detail: String?,
    val status: String?,
    val manuallyEdited: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "project_decision_refs",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("projectId"),
        Index(value = ["projectId", "sourceReportId", "sourceKey"], unique = true)
    ]
)
data class ProjectDecisionRefEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val sourceMeetingId: String,
    val sourceReportId: String,
    val sourceKey: String,
    val content: String,
    val confirmed: Boolean,
    val manuallyEdited: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "project_aggregate_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index(value = ["projectId", "generatedAt"])]
)
data class ProjectAggregateSnapshotEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val sourceMeetingCount: Int,
    val openTaskCount: Int,
    val openRiskCount: Int,
    val pendingDecisionCount: Int,
    val generatedAt: Long
)

fun ProjectEntity.toDomain() = Project(
    id = id,
    name = name,
    status = ProjectStatus.fromStorage(status),
    createdAt = createdAt,
    updatedAt = updatedAt,
    archivedAt = archivedAt,
    deletedAt = deletedAt
)

fun Project.toEntity() = ProjectEntity(
    id = id,
    name = name,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    archivedAt = archivedAt,
    deletedAt = deletedAt
)

fun ProjectMeetingLinkEntity.toDomain() = ProjectMeetingLink(projectId, meetingId, linkedAt, removedAt)
fun ProjectMeetingLink.toEntity() = ProjectMeetingLinkEntity(projectId, meetingId, linkedAt, removedAt)

fun ProjectTaskRefEntity.toDomain() = ProjectTaskRef(
    id, projectId, sourceMeetingId, sourceReportId, sourceKey, content,
    assignee, due, priority, completed, manuallyEdited, createdAt, updatedAt
)

fun ProjectTaskRef.toEntity() = ProjectTaskRefEntity(
    id, projectId, sourceMeetingId, sourceReportId, sourceKey, content,
    assignee, due, priority, completed, manuallyEdited, createdAt, updatedAt
)

fun ProjectRiskRefEntity.toDomain() = ProjectRiskRef(
    id, projectId, sourceMeetingId, sourceReportId, sourceKey, content,
    detail, status, manuallyEdited, createdAt, updatedAt
)

fun ProjectRiskRef.toEntity() = ProjectRiskRefEntity(
    id, projectId, sourceMeetingId, sourceReportId, sourceKey, content,
    detail, status, manuallyEdited, createdAt, updatedAt
)

fun ProjectDecisionRefEntity.toDomain() = ProjectDecisionRef(
    id, projectId, sourceMeetingId, sourceReportId, sourceKey, content,
    confirmed, manuallyEdited, createdAt, updatedAt
)

fun ProjectDecisionRef.toEntity() = ProjectDecisionRefEntity(
    id, projectId, sourceMeetingId, sourceReportId, sourceKey, content,
    confirmed, manuallyEdited, createdAt, updatedAt
)

fun ProjectAggregateSnapshotEntity.toDomain() = ProjectAggregateSnapshot(
    id, projectId, sourceMeetingCount, openTaskCount, openRiskCount,
    pendingDecisionCount, generatedAt
)

fun ProjectAggregateSnapshot.toEntity() = ProjectAggregateSnapshotEntity(
    id, projectId, sourceMeetingCount, openTaskCount, openRiskCount,
    pendingDecisionCount, generatedAt
)
