package com.oa.automation.infrastructure.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.domain.model.PublishedPostVisibility

@Entity(
    tableName = "published_posts",
    foreignKeys = [
        ForeignKey(
            entity = JourneyEntity::class,
            parentColumns = ["id"],
            childColumns = ["journeyId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = JourneyEditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["journeyEditionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["journeyId"]),
        Index(value = ["journeyEditionId"], unique = true),
        Index(value = ["journeyId", "versionNumber"], unique = true),
        Index(value = ["journeyId", "status"])
    ]
)
data class PublishedPostEntity(
    @PrimaryKey val id: String,
    val journeyId: String,
    val journeyEditionId: String,
    val versionNumber: Int,
    val sourceEditionVersion: Int,
    val title: String,
    val content: String,
    val status: String,
    val visibility: String,
    val aiAssisted: Boolean,
    val privacyReviewed: Boolean,
    val rightsConfirmed: Boolean,
    val redactedCoordinateCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val readyAt: Long?,
    val withdrawnAt: Long?,
    val destination: String = "",
    val travelDate: String = "",
    val travelDays: Int = 0,
    val stageTitles: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val pois: List<String> = emptyList()
)

fun PublishedPostEntity.toDomain() = PublishedPost(
    id = id,
    journeyId = journeyId,
    journeyEditionId = journeyEditionId,
    versionNumber = versionNumber,
    sourceEditionVersion = sourceEditionVersion,
    title = title,
    content = content,
    status = PublishedPostStatus.fromStorage(status),
    visibility = PublishedPostVisibility.fromStorage(visibility),
    aiAssisted = aiAssisted,
    privacyReviewed = privacyReviewed,
    rightsConfirmed = rightsConfirmed,
    redactedCoordinateCount = redactedCoordinateCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    readyAt = readyAt,
    withdrawnAt = withdrawnAt,
    destination = destination,
    travelDate = travelDate,
    travelDays = travelDays,
    stageTitles = stageTitles,
    tags = tags,
    pois = pois
)

fun PublishedPost.toEntity() = PublishedPostEntity(
    id = id,
    journeyId = journeyId,
    journeyEditionId = journeyEditionId,
    versionNumber = versionNumber,
    sourceEditionVersion = sourceEditionVersion,
    title = title,
    content = content,
    status = status.name,
    visibility = visibility.name,
    aiAssisted = aiAssisted,
    privacyReviewed = privacyReviewed,
    rightsConfirmed = rightsConfirmed,
    redactedCoordinateCount = redactedCoordinateCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    readyAt = readyAt,
    withdrawnAt = withdrawnAt,
    destination = destination,
    travelDate = travelDate,
    travelDays = travelDays,
    stageTitles = stageTitles,
    tags = tags,
    pois = pois
)
