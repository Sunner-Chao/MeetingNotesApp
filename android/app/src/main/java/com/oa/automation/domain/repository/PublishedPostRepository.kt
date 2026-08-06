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
        redactedCoordinateCount: Int,
        destination: String = "",
        travelDate: String = "",
        travelDays: Int = 0,
        stageTitles: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        pois: List<String> = emptyList()
    ): Result<PublishedPost>

    fun observeLatest(journeyId: String): Flow<PublishedPost?>

    suspend fun saveReview(
        id: String,
        privacyReviewed: Boolean,
        rightsConfirmed: Boolean
    ): Result<PublishedPost>

    suspend fun updateMetadata(
        id: String,
        destination: String,
        travelDate: String,
        travelDays: Int,
        tags: List<String>,
        pois: List<String>
    ): Result<PublishedPost>

    suspend fun markReady(id: String): Result<PublishedPost>

    suspend fun withdraw(id: String): Result<PublishedPost>
}
