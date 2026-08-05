package com.oa.automation.infrastructure.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.oa.automation.domain.model.StageDraftStatus
import com.oa.automation.domain.model.StageDraftVersion

@Entity(
    tableName = "stage_draft_versions",
    foreignKeys = [
        ForeignKey(
            entity = JourneyStageEntity::class,
            parentColumns = ["id"],
            childColumns = ["stageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["stageId"]),
        Index(value = ["stageId", "versionNumber"], unique = true),
        Index(value = ["stageId", "status"])
    ]
)
data class StageDraftVersionEntity(
    @PrimaryKey val id: String,
    val stageId: String,
    val versionNumber: Int,
    val content: String,
    val status: String,
    val evidenceTranscriptCount: Int,
    val evidenceAttachmentCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val confirmedAt: Long?
)

fun StageDraftVersionEntity.toDomain() = StageDraftVersion(
    id = id,
    stageId = stageId,
    versionNumber = versionNumber,
    content = content,
    status = StageDraftStatus.fromStorage(status),
    evidenceTranscriptCount = evidenceTranscriptCount,
    evidenceAttachmentCount = evidenceAttachmentCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    confirmedAt = confirmedAt
)

fun StageDraftVersion.toEntity() = StageDraftVersionEntity(
    id = id,
    stageId = stageId,
    versionNumber = versionNumber,
    content = content,
    status = status.name,
    evidenceTranscriptCount = evidenceTranscriptCount,
    evidenceAttachmentCount = evidenceAttachmentCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    confirmedAt = confirmedAt
)
