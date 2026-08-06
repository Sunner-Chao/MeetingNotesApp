package com.oa.automation.domain.model

/** Review lifecycle for one immutable whole-journey note version. */
enum class JourneyEditionStatus {
    DRAFT,
    CONFIRMED;

    companion object {
        fun fromStorage(value: String): JourneyEditionStatus =
            entries.firstOrNull { it.name == value } ?: DRAFT
    }
}

/** A confirmed stage-note snapshot eligible for inclusion in a journey edition. */
data class ConfirmedJourneyStageDraft(
    val stageId: String,
    val sequenceNumber: Int,
    val stageTitle: String,
    val stageSavedAt: Long?,
    val draft: StageDraftVersion
)

data class JourneyEdition(
    val id: String,
    val journeyId: String,
    val versionNumber: Int,
    val content: String,
    val status: JourneyEditionStatus = JourneyEditionStatus.DRAFT,
    val sourceStageDraftIds: List<String> = emptyList(),
    val sourceStageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val confirmedAt: Long? = null
)
