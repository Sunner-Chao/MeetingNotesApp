package com.oa.automation.application.usecase

import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyEdition
import com.oa.automation.domain.model.JourneyEditionStatus
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.PublishedPostRepository
import com.oa.automation.domain.repository.StageDraftRepository
import com.oa.automation.infrastructure.community.PublishedPostMediaStore

class CreatePublishedPostSnapshotUseCase(
    private val publishedPostRepository: PublishedPostRepository,
    private val stageDraftRepository: StageDraftRepository,
    private val meetingRepository: MeetingRepository,
    private val publishedPostMediaStore: PublishedPostMediaStore
) {
    suspend operator fun invoke(
        journey: Journey,
        edition: JourneyEdition
    ): Result<PublishedPost> {
        if (edition.journeyId != journey.id) {
            return Result.failure(IllegalArgumentException("总游记不属于当前旅程"))
        }
        if (edition.status != JourneyEditionStatus.CONFIRMED) {
            return Result.failure(IllegalStateException("请先确认总游记"))
        }
        val confirmedStages = stageDraftRepository.findLatestConfirmedByJourneyId(journey.id)
            .getOrElse { return Result.failure(it) }
            .filter { it.draft.id in edition.sourceStageDraftIds }
            .sortedBy { it.sequenceNumber }
        val sanitized = PreciseCoordinateSanitizer.sanitize(edition.content)
        val post = publishedPostRepository.createReviewSnapshot(
            journeyId = journey.id,
            journeyEditionId = edition.id,
            sourceEditionVersion = edition.versionNumber,
            title = journey.title.ifBlank { "研学考察" },
            content = sanitized.content,
            redactedCoordinateCount = sanitized.redactedCount,
            destination = journey.title.trim(),
            stageTitles = confirmedStages.map { it.stageTitle },
            tags = PublishMetadataExtractor.tags(sanitized.content),
            pois = PublishMetadataExtractor.pois(sanitized.content)
        ).getOrElse { return Result.failure(it) }
        val sourceDrafts = stageDraftRepository.findByIds(edition.sourceStageDraftIds)
            .getOrElse { return Result.failure(it) }
            .filter { it.id in edition.sourceStageDraftIds }
        val attachments = meetingRepository.findAttachmentsByJourneyStageIds(
            sourceDrafts.map { it.stageId }
        ).getOrElse { return Result.failure(it) }
        // This produces fresh JPEG files, which removes EXIF before any network upload.
        publishedPostMediaStore.prepare(post.id, attachments)
        return Result.success(post)
    }
}

private object PublishMetadataExtractor {
    private val tagPattern = Regex("(?<!\\w)#([\\p{L}\\p{N}_-]{1,40})")
    private val poiPattern = Regex(
        "(?m)^\\s*(?:地点|景点|POI)\\s*[:：]\\s*([^\\n#]{1,80})"
    )

    fun tags(content: String): List<String> = tagPattern.findAll(content)
        .map { it.groupValues[1].trim() }
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .take(50)
        .toList()

    fun pois(content: String): List<String> = poiPattern.findAll(content)
        .map { it.groupValues[1].trim().trimEnd('。', '.', '；', ';', ',') }
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .take(50)
        .toList()
}

internal data class SanitizedPublishContent(
    val content: String,
    val redactedCount: Int
)

internal object PreciseCoordinateSanitizer {
    private val coordinatePair = Regex(
        pattern = "(?<![\\d.])([+-]?(?:[0-8]?\\d|90)\\.\\d{3,7})" +
            "\\s*[,，]\\s*" +
            "([+-]?(?:(?:1[0-7]\\d)|(?:[0-9]?\\d)|180)\\.\\d{3,7})(?![\\d.])"
    )

    fun sanitize(content: String): SanitizedPublishContent {
        var redactedCount = 0
        val sanitized = coordinatePair.replace(content) { match ->
            val latitude = match.groupValues[1].toDoubleOrNull()
            val longitude = match.groupValues[2].toDoubleOrNull()
            if (latitude != null && longitude != null &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0
            ) {
                redactedCount++
                "[精确位置已移除]"
            } else {
                match.value
            }
        }
        return SanitizedPublishContent(sanitized, redactedCount)
    }
}
