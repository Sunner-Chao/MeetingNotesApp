package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PublishedPostMediaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PublishedPostMediaEntity>)

    @Query("SELECT * FROM published_post_media WHERE postId = :postId ORDER BY createdAt ASC")
    suspend fun findByPostId(postId: String): List<PublishedPostMediaEntity>

    @Query(
        "UPDATE published_post_media SET remoteMediaId = :remoteMediaId, status = :status, " +
            "lastError = NULL, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun markRemote(
        id: String,
        remoteMediaId: String,
        status: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE published_post_media SET status = :status, lastError = :lastError, " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun markFailed(id: String, status: String, lastError: String, updatedAt: Long): Int
}
