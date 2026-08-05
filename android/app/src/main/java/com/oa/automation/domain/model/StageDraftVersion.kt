package com.oa.automation.domain.model

/** Review lifecycle for one immutable stage-note version. */
enum class StageDraftStatus {
    DRAFT,
    CONFIRMED;

    companion object {
        fun fromStorage(value: String): StageDraftStatus =
            entries.firstOrNull { it.name == value } ?: DRAFT
    }
}

data class StageDraftVersion(
    val id: String,
    val stageId: String,
    val versionNumber: Int,
    val content: String,
    val status: StageDraftStatus = StageDraftStatus.DRAFT,
    val evidenceTranscriptCount: Int = 0,
    val evidenceAttachmentCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val confirmedAt: Long? = null
)
