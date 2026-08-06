package com.oa.automation.application.usecase

import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyEdition
import com.oa.automation.domain.model.JourneyEditionStatus
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.repository.PublishedPostRepository

class CreatePublishedPostSnapshotUseCase(
    private val publishedPostRepository: PublishedPostRepository
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
        val sanitized = PreciseCoordinateSanitizer.sanitize(edition.content)
        return publishedPostRepository.createReviewSnapshot(
            journeyId = journey.id,
            journeyEditionId = edition.id,
            sourceEditionVersion = edition.versionNumber,
            title = journey.title.ifBlank { "研学考察" },
            content = sanitized.content,
            redactedCoordinateCount = sanitized.redactedCount
        )
    }
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
