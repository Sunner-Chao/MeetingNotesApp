package com.oa.automation.infrastructure.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "published_post_media",
    foreignKeys = [
        ForeignKey(
            entity = PublishedPostEntity::class,
            parentColumns = ["id"],
            childColumns = ["postId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["postId"]),
        Index(value = ["postId", "sourceAttachmentId"], unique = true),
        Index(value = ["status"])
    ]
)
data class PublishedPostMediaEntity(
    @PrimaryKey val id: String,
    val postId: String,
    val sourceAttachmentId: String,
    val displayName: String,
    val originalPath: String,
    val thumbnailPath: String,
    val mimeType: String,
    val originalBytes: Long,
    val originalSha256: String,
    val thumbnailBytes: Long,
    val thumbnailSha256: String,
    val remoteMediaId: String?,
    val status: String,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long
)
