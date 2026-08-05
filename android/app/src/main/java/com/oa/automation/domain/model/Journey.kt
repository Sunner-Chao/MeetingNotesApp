package com.oa.automation.domain.model

/** Lifecycle of a study-tour journey. Recording pause is tracked separately. */
enum class JourneyStatus {
    ACTIVE,
    PAUSED,
    COMPLETED;

    companion object {
        fun fromStorage(value: String): JourneyStatus =
            entries.firstOrNull { it.name == value } ?: PAUSED
    }
}

/** Lifecycle of one durable section inside a journey. */
enum class JourneyStageStatus {
    ACTIVE,
    SAVED;

    companion object {
        fun fromStorage(value: String): JourneyStageStatus =
            entries.firstOrNull { it.name == value } ?: SAVED
    }
}

data class Journey(
    val id: String,
    val meetingId: String,
    val title: String,
    val status: JourneyStatus = JourneyStatus.ACTIVE,
    val currentStageId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val pausedAt: Long? = null,
    val completedAt: Long? = null
)

data class JourneyStage(
    val id: String,
    val journeyId: String,
    val sequenceNumber: Int,
    val title: String,
    val status: JourneyStageStatus = JourneyStageStatus.ACTIVE,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = startedAt,
    val savedAt: Long? = null
)
