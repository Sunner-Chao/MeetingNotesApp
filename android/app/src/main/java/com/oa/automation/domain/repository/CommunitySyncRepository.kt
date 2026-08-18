package com.oa.automation.domain.repository

import com.oa.automation.domain.model.CommunitySyncState
import kotlinx.coroutines.flow.Flow

interface CommunitySyncRepository {
    fun observeAll(): Flow<List<CommunitySyncState>>

    suspend fun enqueueUpload(postId: String): Result<CommunitySyncState>

    suspend fun requestPublish(postId: String): Result<CommunitySyncState>

    suspend fun requestWithdraw(postId: String): Result<CommunitySyncState>

    fun observe(postId: String): Flow<CommunitySyncState?>
}
