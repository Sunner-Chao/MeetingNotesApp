package com.oa.automation.infrastructure.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.JourneyStageStatus
import com.oa.automation.domain.model.JourneyStatus

@Entity(
    tableName = "journeys",
    foreignKeys = [
        ForeignKey(
            entity = MeetingEntity::class,
            parentColumns = ["id"],
            childColumns = ["meetingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["meetingId"], unique = true),
        Index(value = ["status"])
    ]
)
data class JourneyEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val title: String,
    val status: String,
    val currentStageId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val pausedAt: Long?,
    val completedAt: Long?
)

@Entity(
    tableName = "journey_stages",
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
        Index(value = ["journeyId", "sequenceNumber"], unique = true),
        Index(value = ["status"])
    ]
)
data class JourneyStageEntity(
    @PrimaryKey val id: String,
    val journeyId: String,
    val sequenceNumber: Int,
    val title: String,
    val status: String,
    val startedAt: Long,
    val updatedAt: Long,
    val savedAt: Long?
)

fun JourneyEntity.toDomain() = Journey(
    id = id,
    meetingId = meetingId,
    title = title,
    status = JourneyStatus.fromStorage(status),
    currentStageId = currentStageId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pausedAt = pausedAt,
    completedAt = completedAt
)

fun Journey.toEntity() = JourneyEntity(
    id = id,
    meetingId = meetingId,
    title = title,
    status = status.name,
    currentStageId = currentStageId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pausedAt = pausedAt,
    completedAt = completedAt
)

fun JourneyStageEntity.toDomain() = JourneyStage(
    id = id,
    journeyId = journeyId,
    sequenceNumber = sequenceNumber,
    title = title,
    status = JourneyStageStatus.fromStorage(status),
    startedAt = startedAt,
    updatedAt = updatedAt,
    savedAt = savedAt
)

fun JourneyStage.toEntity() = JourneyStageEntity(
    id = id,
    journeyId = journeyId,
    sequenceNumber = sequenceNumber,
    title = title,
    status = status.name,
    startedAt = startedAt,
    updatedAt = updatedAt,
    savedAt = savedAt
)
