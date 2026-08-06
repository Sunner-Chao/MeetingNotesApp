package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PublishedPostDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PublishedPostEntity)

    @Query(
        "SELECT * FROM published_posts " +
            "WHERE journeyId = :journeyId ORDER BY versionNumber DESC LIMIT 1"
    )
    suspend fun findLatest(journeyId: String): PublishedPostEntity?

    @Query(
        "SELECT * FROM published_posts " +
            "WHERE journeyId = :journeyId ORDER BY versionNumber DESC LIMIT 1"
    )
    fun observeLatest(journeyId: String): Flow<PublishedPostEntity?>

    @Query("SELECT * FROM published_posts WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PublishedPostEntity?

    @Query(
        "UPDATE published_posts SET privacyReviewed = :privacyReviewed, " +
            "rightsConfirmed = :rightsConfirmed, updatedAt = :updatedAt " +
            "WHERE id = :id AND status = 'REVIEW'"
    )
    suspend fun updateReview(
        id: String,
        privacyReviewed: Boolean,
        rightsConfirmed: Boolean,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE published_posts SET destination = :destination, travelDate = :travelDate, " +
            "travelDays = :travelDays, tags = :tags, pois = :pois, updatedAt = :updatedAt " +
            "WHERE id = :id AND status = 'REVIEW'"
    )
    suspend fun updateMetadata(
        id: String,
        destination: String,
        travelDate: String,
        travelDays: Int,
        tags: List<String>,
        pois: List<String>,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE published_posts SET status = 'READY', readyAt = :readyAt, updatedAt = :readyAt " +
            "WHERE id = :id AND status = 'REVIEW' AND privacyReviewed = 1 AND rightsConfirmed = 1"
    )
    suspend fun markReady(id: String, readyAt: Long): Int

    @Query(
        "UPDATE published_posts SET status = 'WITHDRAWN', withdrawnAt = :withdrawnAt, " +
            "updatedAt = :withdrawnAt WHERE id = :id AND status = 'READY'"
    )
    suspend fun markWithdrawn(id: String, withdrawnAt: Long): Int
}
