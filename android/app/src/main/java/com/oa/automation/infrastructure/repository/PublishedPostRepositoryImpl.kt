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
    override suspend fun createReviewSnapshot(
        journeyId: String,
        journeyEditionId: String,
        sourceEditionVersion: Int,
        title: String,
        content: String,
        redactedCoordinateCount: Int
    ): Result<PublishedPost> = runCatching {
        require(journeyId.isNotBlank()) { "Journey id must not be blank" }
        require(journeyEditionId.isNotBlank()) { "Journey edition id must not be blank" }
        require(sourceEditionVersion > 0) { "Source edition version must be positive" }
        require(title.isNotBlank()) { "Published post title must not be blank" }
        require(content.isNotBlank()) { "Published post content must not be blank" }

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
