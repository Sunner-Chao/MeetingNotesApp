package com.oa.automation.infrastructure.community

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.CommunitySyncOperation
import com.oa.automation.domain.model.CommunitySyncStatus
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.infrastructure.db.CommunitySyncOutboxDao
import com.oa.automation.infrastructure.db.PublishedPostMediaDao
import com.oa.automation.infrastructure.db.PublishedPostMediaEntity
import com.oa.automation.infrastructure.db.PublishedPostDao
import com.oa.automation.infrastructure.db.toDomain
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileInputStream

class CommunitySyncProcessor(
    private val outboxDao: CommunitySyncOutboxDao,
    private val publishedPostDao: PublishedPostDao,
    private val publishedPostMediaDao: PublishedPostMediaDao,
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
                    syncMedia(postId, remote.id, endpoint, activeSession.accessToken)
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
                    syncMedia(postId, remoteId, endpoint, activeSession.accessToken)
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
            if (runAttemptCount < MAX_RETRIES && isCommunitySyncRetryable(message)) {
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

    private suspend fun syncMedia(
        postId: String,
        remotePostId: String,
        endpoint: String,
        token: String
    ) {
        publishedPostMediaDao.findByPostId(postId)
            .filter { it.status != PublishedPostMediaStore.EXCLUDED }
            .forEach { media ->
            try {
                val original = File(media.originalPath)
                val thumbnail = File(media.thumbnailPath)
                require(original.isFile && thumbnail.isFile) { "社区图片文件不存在" }
                require(original.length() == media.originalBytes) { "社区图片原图已变化" }
                require(thumbnail.length() == media.thumbnailBytes) { "社区图片缩略图已变化" }
                val remote = accountApiService.createCommunityMedia(
                    endpoint = endpoint,
                    token = token,
                    postId = remotePostId,
                    clientMediaId = media.id,
                    displayName = media.displayName,
                    mimeType = media.mimeType,
                    originalBytes = media.originalBytes,
                    originalSha256 = media.originalSha256,
                    thumbnailBytes = media.thumbnailBytes,
                    thumbnailSha256 = media.thumbnailSha256
                ).getOrThrow()
                publishedPostMediaDao.markRemote(
                    media.id,
                    remote.id,
                    "UPLOADING",
                    System.currentTimeMillis()
                )
                val afterOriginal = uploadVariant(
                    remote = remote,
                    variant = "original",
                    file = original,
                    endpoint = endpoint,
                    token = token,
                    remotePostId = remotePostId
                )
                val complete = uploadVariant(
                    remote = afterOriginal,
                    variant = "thumbnail",
                    file = thumbnail,
                    endpoint = endpoint,
                    token = token,
                    remotePostId = remotePostId
                )
                check(complete.status == "ready") { "社区图片尚未完成同步" }
                publishedPostMediaDao.markRemote(
                    media.id,
                    complete.id,
                    "READY",
                    System.currentTimeMillis()
                )
            } catch (error: Exception) {
                publishedPostMediaDao.markFailed(
                    media.id,
                    "FAILED",
                    error.message?.take(500).orEmpty().ifBlank { "社区图片同步失败" },
                    System.currentTimeMillis()
                )
                throw error
            }
        }
    }

    private suspend fun uploadVariant(
        remote: AccountApiService.CommunityMediaResponse,
        variant: String,
        file: File,
        endpoint: String,
        token: String,
        remotePostId: String
    ): AccountApiService.CommunityMediaResponse {
        val total = file.length()
        var offset = if (variant == "original") {
            remote.originalReceivedBytes
        } else {
            remote.thumbnailReceivedBytes
        }
        require(offset in 0..total) { "社区图片续传偏移量无效" }
        var latest = remote
        FileInputStream(file).use { input ->
            skipFully(input, offset)
            while (offset < total) {
                val count = minOf(CHUNK_BYTES.toLong(), total - offset).toInt()
                val bytes = readChunk(input, count)
                latest = accountApiService.uploadCommunityMediaChunk(
                    endpoint = endpoint,
                    token = token,
                    postId = remotePostId,
                    mediaId = remote.id,
                    variant = variant,
                    start = offset,
                    total = total,
                    bytes = bytes
                ).getOrThrow()
                offset = if (variant == "original") {
                    latest.originalReceivedBytes
                } else {
                    latest.thumbnailReceivedBytes
                }
            }
        }
        return latest
    }

    private fun skipFully(input: FileInputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) error("社区图片续传文件无效")
            remaining -= skipped
        }
    }

    private fun readChunk(input: FileInputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) error("社区图片读取不完整")
            offset += read
        }
        return bytes
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

    companion object {
        private const val MAX_RETRIES = 5
        private const val CHUNK_BYTES = 512 * 1024
    }
}

internal fun isCommunitySyncRetryable(message: String): Boolean {
    if (message.contains("社区写入暂时关闭")) return false
    val value = message.lowercase()
    return value.contains("timeout") || value.contains("timed out") ||
        value.contains("network") || value.contains("connection") ||
        value.contains("请求失败") || value.contains("暂时不可用") ||
        value.contains("503") || value.contains("502")
}

sealed interface ProcessingResult {
    data object Success : ProcessingResult
    data object Retry : ProcessingResult
    data class Failure(val message: String) : ProcessingResult
}
