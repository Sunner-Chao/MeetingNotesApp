package com.oa.automation.domain.model

enum class CommunitySyncOperation {
    UPLOAD,
    PUBLISH,
    WITHDRAW;

    companion object {
        fun fromStorage(value: String): CommunitySyncOperation =
            entries.firstOrNull { it.name == value } ?: UPLOAD
    }
}

enum class CommunitySyncStatus {
    PENDING,
    UPLOADING,
    PRIVATE_DRAFT,
    PUBLISHING,
    PUBLISHED,
    WITHDRAWING,
    WITHDRAWN,
    FAILED;

    companion object {
        fun fromStorage(value: String): CommunitySyncStatus =
            entries.firstOrNull { it.name == value } ?: PENDING
    }
}

data class CommunitySyncState(
    val postId: String,
    val operation: CommunitySyncOperation,
    val status: CommunitySyncStatus,
    val remotePostId: String? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
