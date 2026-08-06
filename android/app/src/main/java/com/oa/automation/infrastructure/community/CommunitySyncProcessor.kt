package com.oa.automation.infrastructure.community

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.CommunitySyncOperation
import com.oa.automation.domain.model.CommunitySyncStatus
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.infrastructure.db.CommunitySyncOutboxDao
import com.oa.automation.infrastructure.db.PublishedPostDao
import com.oa.automation.infrastructure.db.toDomain
import kotlinx.coroutines.flow.first

class CommunitySyncProcessor(
    private val outboxDao: CommunitySyncOutboxDao,
    private val publishedPostDao: PublishedPostDao,
    private val accountApiService: AccountApiService,
    private val configDataStore: ConfigDataStore
) {
    suspend fun run(postId: String, runAttemptCount: Int): ProcessingResult {
        val outbox = outboxDao.findByPostId(postId) ?: return ProcessingResult.Success
        if (outbox.status == CommunitySyncStatus.PUBLISHED.name &&
            outbox.operation == CommunitySyncOperation.PUBLISH.name
        ) return ProcessingResult.Success
        if (outbox.status == CommunitySyncStatus.WITHDRAWN.name &&
            outbox.operation == CommunitySyncOperation.WITHDRAW.name
        ) return ProcessingResult.Success

        val post = publishedPostDao.findById(postId)
            ?: return fail(outbox, "发布快照不存在")
        val session = configDataStore.authSessionFlow.first()
        val endpoint = configDataStore.accountEndpointFlow.first()
        return try {
            when (CommunitySyncOperation.fromStorage(outbox.operation)) {
                CommunitySyncOperation.UPLOAD -> {
                    val activeSession = session ?: return fail(
                        outbox,
                        "请先登录账户再同步社区内容"
                    )
                    outboxDao.markRunning(
                        postId,
                        CommunitySyncStatus.UPLOADING.name,
                        outbox.attemptCount + 1,
                        System.currentTimeMillis()
                    )
                    val remote = accountApiService.createCommunityDraft(
                        endpoint,
                        activeSession.accessToken,
                        post.toDomain()
                    ).getOrThrow()
                    outboxDao.markRemoteState(
                        postId,
                        CommunitySyncStatus.PRIVATE_DRAFT.name,
                        remote.id,
                        System.currentTimeMillis()
                    )
                }

                CommunitySyncOperation.PUBLISH -> {
                    val activeSession = session ?: return fail(
                        outbox,
                        "请先登录账户再同步社区内容"
                    )
                    val remoteId = ensurePrivateDraft(postId, outbox, post, endpoint, activeSession.accessToken)
                    outboxDao.markRunning(
                        postId,
                        CommunitySyncStatus.PUBLISHING.name,
                        outbox.attemptCount + 1,
                        System.currentTimeMillis()
                    )
                    accountApiService.publishCommunityPost(
                        endpoint,
                        activeSession.accessToken,
                        remoteId
                    ).getOrThrow()
                    outboxDao.markRemoteState(
                        postId,
                        CommunitySyncStatus.PUBLISHED.name,
                        remoteId,
                        System.currentTimeMillis()
                    )
                }

                CommunitySyncOperation.WITHDRAW -> {
                    val remoteId = outbox.remotePostId
                    if (remoteId.isNullOrBlank()) {
                        outboxDao.markRemoteState(
                            postId,
                            CommunitySyncStatus.WITHDRAWN.name,
                            null,
                            System.currentTimeMillis()
                        )
                    } else {
                        val activeSession = session ?: return fail(
                            outbox,
                            "请先登录账户再同步社区内容"
                        )
                        outboxDao.markRunning(
                            postId,
                            CommunitySyncStatus.WITHDRAWING.name,
                            outbox.attemptCount + 1,
                            System.currentTimeMillis()
                        )
                        accountApiService.withdrawCommunityPost(
                            endpoint,
                            activeSession.accessToken,
                            remoteId
                        ).getOrThrow()
                        outboxDao.markRemoteState(
                            postId,
                            CommunitySyncStatus.WITHDRAWN.name,
                            remoteId,
                            System.currentTimeMillis()
                        )
                    }
                }
            }
            ProcessingResult.Success
        } catch (error: Exception) {
            val message = error.message?.take(500).orEmpty().ifBlank { "社区同步失败" }
            val attempt = outbox.attemptCount + 1
            outboxDao.markFailed(postId, attempt, message, System.currentTimeMillis())
            if (runAttemptCount < MAX_RETRIES && message.isRetryable()) {
                ProcessingResult.Retry
            } else {
                ProcessingResult.Failure(message)
            }
        }
    }

    private suspend fun ensurePrivateDraft(
        postId: String,
        outbox: com.oa.automation.infrastructure.db.CommunitySyncOutboxEntity,
        post: com.oa.automation.infrastructure.db.PublishedPostEntity,
        endpoint: String,
        token: String
    ): String {
        outbox.remotePostId?.takeIf { it.isNotBlank() }?.let { return it }
        outboxDao.markRunning(
            postId,
            CommunitySyncStatus.UPLOADING.name,
            outbox.attemptCount + 1,
            System.currentTimeMillis()
        )
        return accountApiService.createCommunityDraft(
            endpoint,
            token,
            post.toDomain()
        ).getOrThrow().id
    }

    private suspend fun fail(
        outbox: com.oa.automation.infrastructure.db.CommunitySyncOutboxEntity,
        message: String
    ): ProcessingResult {
        outboxDao.markFailed(
            outbox.postId,
            outbox.attemptCount + 1,
            message,
            System.currentTimeMillis()
        )
        return ProcessingResult.Failure(message)
    }

    private fun String.isRetryable(): Boolean {
        val value = lowercase()
        return value.contains("timeout") || value.contains("timed out") ||
            value.contains("network") || value.contains("connection") ||
            value.contains("请求失败") || value.contains("暂时不可用") ||
            value.contains("503") || value.contains("502")
    }

    companion object {
        private const val MAX_RETRIES = 5
    }
}

sealed interface ProcessingResult {
    data object Success : ProcessingResult
    data object Retry : ProcessingResult
    data class Failure(val message: String) : ProcessingResult
}
