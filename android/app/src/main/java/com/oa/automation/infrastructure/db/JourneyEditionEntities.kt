package com.oa.automation.infrastructure.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.oa.automation.domain.model.JourneyEdition
import com.oa.automation.domain.model.JourneyEditionStatus

@Entity(
    tableName = "journey_editions",
    foreignKeys = [
        ForeignKey(
            entity = JourneyEntity::class,
            parentColumns = ["id"],
            childColumns = ["journeyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["journeyId"]),
        Index(value = ["journeyId", "versionNumber"], unique = true),
        Index(value = ["journeyId", "status"])
    ]
)
data class JourneyEditionEntity(
    @PrimaryKey val id: String,
    val journeyId: String,
    val versionNumber: Int,
    val content: String,
    val status: String,
    val sourceStageDraftIds: List<String>,
    val sourceStageCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val confirmedAt: Long?
)

fun JourneyEditionEntity.toDomain() = JourneyEdition(
    id = id,
    journeyId = journeyId,
    versionNumber = versionNumber,
    content = content,
    status = JourneyEditionStatus.fromStorage(status),
    sourceStageDraftIds = sourceStageDraftIds,
    sourceStageCount = sourceStageCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    confirmedAt = confirmedAt
)

fun JourneyEdition.toEntity() = JourneyEditionEntity(
    id = id,
    journeyId = journeyId,
    versionNumber = versionNumber,
    content = content,
    status = status.name,
    sourceStageDraftIds = sourceStageDraftIds,
    sourceStageCount = sourceStageCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    confirmedAt = confirmedAt
)
