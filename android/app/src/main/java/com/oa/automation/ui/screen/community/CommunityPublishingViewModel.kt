package com.oa.automation.ui.screen.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.domain.model.CommunitySyncOperation
import com.oa.automation.domain.model.CommunitySyncState
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.domain.repository.CommunitySyncRepository
import com.oa.automation.domain.repository.PublishedPostRepository
import com.oa.automation.infrastructure.community.PublishedPostMediaStore
import com.oa.automation.infrastructure.db.PublishedPostMediaEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommunityPublishingItem(
    val post: PublishedPost,
    val sync: CommunitySyncState?
)

data class CommunityPublishingUiState(
    val posts: List<CommunityPublishingItem> = emptyList(),
    val selectedPostId: String? = null,
    val selectedMedia: List<PublishedPostMediaEntity> = emptyList(),
    val isSaving: Boolean = false,
    val error: String? = null,
    val message: String? = null
) {
    val selectedPost: PublishedPost?
        get() = posts.firstOrNull { it.post.id == selectedPostId }?.post
}

class CommunityPublishingViewModel(
    private val publishedPostRepository: PublishedPostRepository,
    private val communitySyncRepository: CommunitySyncRepository,
    private val mediaStore: PublishedPostMediaStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(CommunityPublishingUiState())
    val uiState: StateFlow<CommunityPublishingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                publishedPostRepository.observeAll(),
                communitySyncRepository.observeAll()
            ) { posts, syncStates ->
                val syncByPost = syncStates.associateBy { it.postId }
                posts.map { CommunityPublishingItem(it, syncByPost[it.id]) }
            }.collect { items ->
                _uiState.update { state ->
                    state.copy(
                        posts = items,
                        selectedPostId = state.selectedPostId?.takeIf { id ->
                            items.any { it.post.id == id }
                        }
                    )
                }
            }
        }
    }

    fun refresh() {
        _uiState.update { it.copy(error = null, message = null) }
    }

    fun openReview(postId: String) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(selectedPostId = postId, selectedMedia = emptyList(), error = null) }
        viewModelScope.launch {
            val media = mediaStore.list(postId)
            if (_uiState.value.selectedPostId == postId) {
                _uiState.update { it.copy(selectedMedia = media) }
            }
        }
    }

    fun dismissReview() {
        if (!_uiState.value.isSaving) {
            _uiState.update { it.copy(selectedPostId = null, selectedMedia = emptyList(), error = null) }
        }
    }

    fun saveReview(privacyReviewed: Boolean, rightsConfirmed: Boolean) = launchAction("检查项已保存") {
        val post = requireSelected()
        publishedPostRepository.saveReview(post.id, privacyReviewed, rightsConfirmed).getOrThrow()
    }

    fun updateMetadata(
        destination: String,
        travelDate: String,
        travelDays: Int,
        tags: List<String>,
        pois: List<String>
    ) = launchAction("发布信息已保存") {
        publishedPostRepository.updateMetadata(
            id = requireSelected().id,
            destination = destination,
            travelDate = travelDate,
            travelDays = travelDays,
            tags = tags,
            pois = pois
        ).getOrThrow()
    }

    fun setMediaIncluded(mediaId: String, included: Boolean) {
        val post = _uiState.value.selectedPost ?: return
        if (post.status != PublishedPostStatus.REVIEW || _uiState.value.isSaving) return
        launchAction(if (included) "图片已恢复" else "图片已排除") {
            mediaStore.setIncluded(post.id, mediaId, included).getOrThrow()
            selectedMedia = mediaStore.list(post.id)
        }
    }

    fun markReady(privacyReviewed: Boolean, rightsConfirmed: Boolean) {
        if (!privacyReviewed || !rightsConfirmed) {
            _uiState.update { it.copy(error = "请先完成隐私与图片权利确认") }
            return
        }
        launchAction("已进入同步队列") {
            val reviewed = publishedPostRepository.saveReview(
                requireSelected().id,
                privacyReviewed = true,
                rightsConfirmed = true
            ).getOrThrow()
            val ready = publishedPostRepository.markReady(reviewed.id).getOrThrow()
            communitySyncRepository.enqueueUpload(ready.id).getOrThrow()
        }
    }

    fun publish() = launchAction("已加入发布队列") {
        communitySyncRepository.requestPublish(requireSelected().id).getOrThrow()
    }

    fun withdraw() = launchAction("已加入撤回队列") {
        val post = requireSelected()
        publishedPostRepository.withdraw(post.id).getOrThrow()
        communitySyncRepository.requestWithdraw(post.id).getOrThrow()
    }

    fun retry(postId: String) = launchAction("正在重试同步") {
        val item = _uiState.value.posts.firstOrNull { it.post.id == postId }
            ?: error("请先打开一条发布记录")
        when (item.sync?.operation ?: CommunitySyncOperation.UPLOAD) {
            CommunitySyncOperation.UPLOAD -> communitySyncRepository.enqueueUpload(item.post.id).getOrThrow()
            CommunitySyncOperation.PUBLISH -> communitySyncRepository.requestPublish(item.post.id).getOrThrow()
            CommunitySyncOperation.WITHDRAW -> communitySyncRepository.requestWithdraw(item.post.id).getOrThrow()
        }
    }

    private fun requireSelected(): PublishedPost =
        _uiState.value.selectedPost ?: error("请先打开一条发布记录")

    private fun launchAction(message: String, block: suspend CommunityPublishingViewModel.() -> Unit) {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null, message = null) }
            runCatching { block() }.fold(
                onSuccess = {
                    _uiState.update { it.copy(isSaving = false, message = message) }
                    _uiState.value.selectedPostId?.let { postId ->
                        _uiState.update { it.copy(selectedMedia = mediaStore.list(postId)) }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSaving = false, error = error.message ?: "操作失败，请稍后重试")
                    }
                }
            )
        }
    }

    private var selectedMedia: List<PublishedPostMediaEntity>
        get() = _uiState.value.selectedMedia
        set(value) { _uiState.update { it.copy(selectedMedia = value) } }
}
