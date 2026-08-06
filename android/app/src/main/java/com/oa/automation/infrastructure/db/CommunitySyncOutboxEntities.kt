package com.oa.automation.infrastructure.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.oa.automation.domain.model.CommunitySyncOperation
import com.oa.automation.domain.model.CommunitySyncState
import com.oa.automation.domain.model.CommunitySyncStatus

@Entity(
    tableName = "community_sync_outbox",
    foreignKeys = [
        ForeignKey(
            entity = PublishedPostEntity::class,
            parentColumns = ["id"],
            childColumns = ["postId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["postId"], unique = true),
        Index(value = ["status"]),
        Index(value = ["updatedAt"])
    ]
)
data class CommunitySyncOutboxEntity(
    @PrimaryKey val postId: String,
    val operation: String,
    val status: String,
    val remotePostId: String?,
    val attemptCount: Int,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long
)

fun CommunitySyncOutboxEntity.toDomain() = CommunitySyncState(
    postId = postId,
    operation = CommunitySyncOperation.fromStorage(operation),
    status = CommunitySyncStatus.fromStorage(status),
    remotePostId = remotePostId,
    attemptCount = attemptCount,
    lastError = lastError,
    createdAt = createdAt,
    updatedAt = updatedAt
)
