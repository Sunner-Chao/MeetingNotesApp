package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.CommunitySyncOperation
import com.oa.automation.domain.model.CommunitySyncState
import com.oa.automation.domain.model.CommunitySyncStatus
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.domain.repository.CommunitySyncRepository
import com.oa.automation.infrastructure.community.CommunitySyncEnqueuer
import com.oa.automation.infrastructure.db.CommunitySyncOutboxDao
import com.oa.automation.infrastructure.db.CommunitySyncOutboxEntity
import com.oa.automation.infrastructure.db.PublishedPostDao
import com.oa.automation.infrastructure.db.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CommunitySyncRepositoryImpl(
    private val outboxDao: CommunitySyncOutboxDao,
    private val publishedPostDao: PublishedPostDao,
    private val scheduler: CommunitySyncEnqueuer
) : CommunitySyncRepository {
    override suspend fun enqueueUpload(postId: String): Result<CommunitySyncState> = runCatching {
        val post = publishedPostDao.findById(postId) ?: error("Published post not found: $postId")
        require(post.status == PublishedPostStatus.READY.name) {
            "Only ready snapshots can be uploaded"
        }
        val existing = outboxDao.findByPostId(postId)
        if (existing == null) {
            outboxDao.upsert(newEntity(postId, CommunitySyncOperation.UPLOAD))
        } else if (existing.status !in terminalStatuses) {
            outboxDao.updateIntent(
                postId = postId,
                operation = CommunitySyncOperation.UPLOAD.name,
                status = CommunitySyncStatus.PENDING.name,
                updatedAt = System.currentTimeMillis()
            )
        }
        scheduler.enqueue(postId)
        outboxDao.findByPostId(postId)?.toDomain() ?: error("Community sync outbox disappeared")
    }

    override suspend fun requestPublish(postId: String): Result<CommunitySyncState> = runCatching {
        val post = publishedPostDao.findById(postId) ?: error("Published post not found: $postId")
        require(post.status == PublishedPostStatus.READY.name) {
            "Only ready snapshots can be published"
        }
        val existing = outboxDao.findByPostId(postId)
        if (existing == null) {
            outboxDao.upsert(newEntity(postId, CommunitySyncOperation.PUBLISH))
        } else if (existing.status != CommunitySyncStatus.PUBLISHED.name) {
            outboxDao.updateIntent(
                postId = postId,
                operation = CommunitySyncOperation.PUBLISH.name,
                status = CommunitySyncStatus.PENDING.name,
                updatedAt = System.currentTimeMillis()
            )
        }
        scheduler.enqueue(postId)
        outboxDao.findByPostId(postId)?.toDomain() ?: error("Community sync outbox disappeared")
    }

    override suspend fun requestWithdraw(postId: String): Result<CommunitySyncState> = runCatching {
        val post = publishedPostDao.findById(postId) ?: error("Published post not found: $postId")
        require(post.status == PublishedPostStatus.WITHDRAWN.name) {
            "Only withdrawn snapshots can be removed from the community"
        }
        val existing = outboxDao.findByPostId(postId)
        if (existing == null) {
            outboxDao.upsert(
                newEntity(
                    postId = postId,
                    operation = CommunitySyncOperation.WITHDRAW,
                    status = CommunitySyncStatus.WITHDRAWN
                )
            )
        } else if (existing.status != CommunitySyncStatus.WITHDRAWN.name) {
            outboxDao.updateIntent(
                postId = postId,
                operation = CommunitySyncOperation.WITHDRAW.name,
                status = CommunitySyncStatus.PENDING.name,
                updatedAt = System.currentTimeMillis()
            )
            scheduler.enqueue(postId)
        }
        outboxDao.findByPostId(postId)?.toDomain() ?: error("Community sync outbox disappeared")
    }

    override fun observe(postId: String): Flow<CommunitySyncState?> =
        outboxDao.observe(postId).map { it?.toDomain() }

    private fun newEntity(
        postId: String,
        operation: CommunitySyncOperation,
        status: CommunitySyncStatus = CommunitySyncStatus.PENDING
    ): CommunitySyncOutboxEntity {
        val now = System.currentTimeMillis()
        return CommunitySyncOutboxEntity(
            postId = postId,
            operation = operation.name,
            status = status.name,
            remotePostId = null,
            attemptCount = 0,
            lastError = null,
            createdAt = now,
            updatedAt = now
        )
    }

    private companion object {
        val terminalStatuses = setOf(
            CommunitySyncStatus.PUBLISHED.name,
            CommunitySyncStatus.WITHDRAWN.name
        )
    }
}
