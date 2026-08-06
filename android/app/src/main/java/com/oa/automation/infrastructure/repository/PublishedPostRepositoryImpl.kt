package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.domain.model.PublishedPostVisibility
import com.oa.automation.domain.repository.PublishedPostRepository
import com.oa.automation.infrastructure.db.PublishedPostDao
import com.oa.automation.infrastructure.db.toDomain
import com.oa.automation.infrastructure.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class PublishedPostRepositoryImpl(
    private val dao: PublishedPostDao
) : PublishedPostRepository {
    suspend fun createReviewSnapshot(
        journeyId: String,
        journeyEditionId: String,
        sourceEditionVersion: Int,
        title: String,
        content: String,
        redactedCoordinateCount: Int
    ): Result<PublishedPost> = createReviewSnapshot(
        journeyId = journeyId,
        journeyEditionId = journeyEditionId,
        sourceEditionVersion = sourceEditionVersion,
        title = title,
        content = content,
        redactedCoordinateCount = redactedCoordinateCount,
        destination = "",
        travelDate = "",
        travelDays = 0,
        stageTitles = emptyList(),
        tags = emptyList(),
        pois = emptyList()
    )

    override suspend fun createReviewSnapshot(
        journeyId: String,
        journeyEditionId: String,
        sourceEditionVersion: Int,
        title: String,
        content: String,
        redactedCoordinateCount: Int,
        destination: String,
        travelDate: String,
        travelDays: Int,
        stageTitles: List<String>,
        tags: List<String>,
        pois: List<String>
    ): Result<PublishedPost> = runCatching {
        require(journeyId.isNotBlank()) { "Journey id must not be blank" }
        require(journeyEditionId.isNotBlank()) { "Journey edition id must not be blank" }
        require(sourceEditionVersion > 0) { "Source edition version must be positive" }
        require(title.isNotBlank()) { "Published post title must not be blank" }
        require(content.isNotBlank()) { "Published post content must not be blank" }
        require(destination.trim().length <= 120) { "Destination is too long" }
        require(travelDate.isBlank() || Regex("\\d{4}-\\d{2}-\\d{2}").matches(travelDate.trim())) {
            "Travel date must use YYYY-MM-DD"
        }
        require((stageTitles + tags + pois).all { it.trim().length <= 80 }) {
            "Stage, tag and POI values must be 80 characters or fewer"
        }

        val previous = dao.findLatest(journeyId)
        val now = System.currentTimeMillis()
        val post = PublishedPost(
            id = UUID.randomUUID().toString(),
            journeyId = journeyId,
            journeyEditionId = journeyEditionId,
            versionNumber = (previous?.versionNumber ?: 0) + 1,
            sourceEditionVersion = sourceEditionVersion,
            title = title.trim(),
            content = content.trim(),
            status = PublishedPostStatus.REVIEW,
            visibility = PublishedPostVisibility.PRIVATE_PREVIEW,
            aiAssisted = true,
            redactedCoordinateCount = redactedCoordinateCount.coerceAtLeast(0),
            destination = destination.trim(),
            travelDate = travelDate.trim(),
            travelDays = travelDays.coerceIn(0, 31),
            stageTitles = stageTitles.map(String::trim).filter(String::isNotBlank).distinct().take(50),
            tags = tags.map(String::trim).filter(String::isNotBlank).distinct().take(50),
            pois = pois.map(String::trim).filter(String::isNotBlank).distinct().take(50),
            createdAt = now,
            updatedAt = now
        )
        dao.insert(post.toEntity())
        post
    }

    override fun observeLatest(journeyId: String): Flow<PublishedPost?> =
        dao.observeLatest(journeyId).map { it?.toDomain() }

    override suspend fun saveReview(
        id: String,
        privacyReviewed: Boolean,
        rightsConfirmed: Boolean
    ): Result<PublishedPost> = runCatching {
        val existing = dao.findById(id) ?: error("Published post not found: $id")
        require(existing.status == PublishedPostStatus.REVIEW.name) {
            "Only review snapshots can be updated"
        }
        check(
            dao.updateReview(
                id = id,
                privacyReviewed = privacyReviewed,
                rightsConfirmed = rightsConfirmed,
                updatedAt = System.currentTimeMillis()
            ) == 1
        ) { "Published post is no longer reviewable" }
        dao.findById(id)?.toDomain() ?: error("Published post disappeared: $id")
    }

    override suspend fun updateMetadata(
        id: String,
        destination: String,
        travelDate: String,
        travelDays: Int,
        tags: List<String>,
        pois: List<String>
    ): Result<PublishedPost> = runCatching {
        val existing = dao.findById(id) ?: error("Published post not found: $id")
        require(existing.status == PublishedPostStatus.REVIEW.name) {
            "Only review snapshots can update metadata"
        }
        val cleanDestination = destination.trim()
        val cleanTravelDate = travelDate.trim()
        require(cleanDestination.length <= 120) { "Destination is too long" }
        require(cleanTravelDate.isBlank() || Regex("\\d{4}-\\d{2}-\\d{2}").matches(cleanTravelDate)) {
            "Travel date must use YYYY-MM-DD"
        }
        require(travelDays in 0..31) { "Travel days must be between 0 and 31" }
        fun normalize(values: List<String>): List<String> {
            val cleaned = values.map(String::trim).filter(String::isNotBlank)
            require(cleaned.all { it.length <= 80 }) {
                "Tag and POI values must be 80 characters or fewer"
            }
            return cleaned.distinctBy(String::lowercase).take(50)
        }
        val now = System.currentTimeMillis()
        check(dao.updateMetadata(
            id = id,
            destination = cleanDestination,
            travelDate = cleanTravelDate,
            travelDays = travelDays,
            tags = normalize(tags),
            pois = normalize(pois),
            updatedAt = now
        ) == 1) { "Published post metadata is no longer editable" }
        dao.findById(id)?.toDomain() ?: error("Published post disappeared: $id")
    }

    override suspend fun markReady(id: String): Result<PublishedPost> = runCatching {
        val existing = dao.findById(id) ?: error("Published post not found: $id")
        require(existing.status == PublishedPostStatus.REVIEW.name) {
            "Published post is no longer under review"
        }
        require(existing.privacyReviewed && existing.rightsConfirmed) {
            "Privacy and content rights must be confirmed"
        }
        check(dao.markReady(id, System.currentTimeMillis()) == 1) {
            "Published post cannot be marked ready"
        }
        dao.findById(id)?.toDomain() ?: error("Published post disappeared: $id")
    }

    override suspend fun withdraw(id: String): Result<PublishedPost> = runCatching {
        val existing = dao.findById(id) ?: error("Published post not found: $id")
        require(existing.status == PublishedPostStatus.READY.name) {
            "Only ready snapshots can be withdrawn"
        }
        check(dao.markWithdrawn(id, System.currentTimeMillis()) == 1) {
            "Published post cannot be withdrawn"
        }
        dao.findById(id)?.toDomain() ?: error("Published post disappeared: $id")
    }
}
