package com.oa.automation.domain.repository

import com.oa.automation.domain.model.PublishedPost
import kotlinx.coroutines.flow.Flow

interface PublishedPostRepository {
    suspend fun createReviewSnapshot(
        journeyId: String,
        journeyEditionId: String,
        sourceEditionVersion: Int,
        title: String,
        content: String,
        redactedCoordinateCount: Int
    ): Result<PublishedPost>

    fun observeLatest(journeyId: String): Flow<PublishedPost?>

    suspend fun saveReview(
        id: String,
        privacyReviewed: Boolean,
        rightsConfirmed: Boolean
    ): Result<PublishedPost>

    suspend fun markReady(id: String): Result<PublishedPost>

    suspend fun withdraw(id: String): Result<PublishedPost>
}
