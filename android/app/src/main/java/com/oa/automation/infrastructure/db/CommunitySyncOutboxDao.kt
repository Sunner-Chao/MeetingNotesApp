package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommunitySyncOutboxDao {
    @Query("SELECT * FROM community_sync_outbox ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CommunitySyncOutboxEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CommunitySyncOutboxEntity)

    @Query("SELECT * FROM community_sync_outbox WHERE postId = :postId LIMIT 1")
    suspend fun findByPostId(postId: String): CommunitySyncOutboxEntity?

    @Query("SELECT * FROM community_sync_outbox WHERE postId = :postId LIMIT 1")
    fun observe(postId: String): Flow<CommunitySyncOutboxEntity?>

    @Query(
        "UPDATE community_sync_outbox SET operation = :operation, status = :status, " +
            "lastError = NULL, updatedAt = :updatedAt WHERE postId = :postId"
    )
    suspend fun updateIntent(
        postId: String,
        operation: String,
        status: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE community_sync_outbox SET status = :status, attemptCount = :attemptCount, " +
            "lastError = NULL, updatedAt = :updatedAt WHERE postId = :postId"
    )
    suspend fun markRunning(
        postId: String,
        status: String,
        attemptCount: Int,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE community_sync_outbox SET status = :status, remotePostId = :remotePostId, " +
            "lastError = NULL, updatedAt = :updatedAt WHERE postId = :postId"
    )
    suspend fun markRemoteState(
        postId: String,
        status: String,
        remotePostId: String?,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE community_sync_outbox SET status = 'FAILED', attemptCount = :attemptCount, " +
            "lastError = :lastError, updatedAt = :updatedAt WHERE postId = :postId"
    )
    suspend fun markFailed(
        postId: String,
        attemptCount: Int,
        lastError: String,
        updatedAt: Long
    ): Int
}
