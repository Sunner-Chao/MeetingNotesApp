package com.oa.automation.domain.model

enum class PublishedPostStatus {
    REVIEW,
    READY,
    WITHDRAWN;

    companion object {
        fun fromStorage(value: String): PublishedPostStatus =
            entries.firstOrNull { it.name == value } ?: REVIEW
    }
}

/** P4-A snapshots never leave the device; public visibility is a later server capability. */
enum class PublishedPostVisibility {
    PRIVATE_PREVIEW;

    companion object {
        fun fromStorage(value: String): PublishedPostVisibility =
            entries.firstOrNull { it.name == value } ?: PRIVATE_PREVIEW
    }
}

data class PublishedPost(
    val id: String,
    val journeyId: String,
    val journeyEditionId: String,
    val versionNumber: Int,
    val sourceEditionVersion: Int,
    val title: String,
    val content: String,
    val status: PublishedPostStatus = PublishedPostStatus.REVIEW,
    val visibility: PublishedPostVisibility = PublishedPostVisibility.PRIVATE_PREVIEW,
    val aiAssisted: Boolean = true,
    val privacyReviewed: Boolean = false,
    val rightsConfirmed: Boolean = false,
    val redactedCoordinateCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val readyAt: Long? = null,
    val withdrawnAt: Long? = null,
    val destination: String = "",
    val travelDate: String = "",
    val travelDays: Int = 0,
    val stageTitles: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val pois: List<String> = emptyList()
)
