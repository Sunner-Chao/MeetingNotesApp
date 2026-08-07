package com.oa.automation.ui.screen.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.MyCommunityPost
import com.oa.automation.domain.model.CommunityAvailability
import com.oa.automation.domain.model.CommunityFacets
import com.oa.automation.domain.model.CommunityComment
import com.oa.automation.domain.model.CommunityInteractionState
import com.oa.automation.domain.model.CommunityCollection
import com.oa.automation.domain.model.CommunityCollectionFacets
import com.oa.automation.domain.model.CommunityCollectionInteractionState
import com.oa.automation.domain.model.CommunityCollectionShare
import com.oa.automation.domain.model.PublicCommunityPost
import com.oa.automation.infrastructure.account.AccountApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CommunityTab { DISCOVER, MINE, SAVED }

data class CommunityUiState(
    val tab: CommunityTab = CommunityTab.DISCOVER,
    val publicPosts: List<PublicCommunityPost> = emptyList(),
    val myPosts: List<MyCommunityPost> = emptyList(),
    val savedPosts: List<PublicCommunityPost> = emptyList(),
    val savedCollections: List<CommunityCollection> = emptyList(),
    val collections: List<CommunityCollection> = emptyList(),
    val collectionDestinationFilter: String = "",
    val collectionThemeFilter: String = "",
    val collectionSort: String = "curated",
    val collectionSortExplanation: String = "",
    val collectionFacets: CommunityCollectionFacets = CommunityCollectionFacets(),
    val searchQuery: String = "",
    val destinationFilter: String = "",
    val tagFilter: String = "",
    val poiFilter: String = "",
    val minDaysFilter: Int = 0,
    val maxDaysFilter: Int = 0,
    val hasMediaOnly: Boolean = false,
    val facets: CommunityFacets = CommunityFacets(),
    val mediaBaseUrl: String = "",
    val availability: CommunityAvailability = CommunityAvailability(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val nextPublicCursor: String? = null,
    val nextSavedCursor: String? = null,
    val error: String? = null
)

class CommunityViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(CommunityUiState())
    val uiState: StateFlow<CommunityUiState> = _uiState.asStateFlow()

    fun selectTab(tab: CommunityTab) {
        if (_uiState.value.tab == tab) return
        _uiState.update { it.copy(tab = tab, error = null) }
        if (tab == CommunityTab.MINE && _uiState.value.myPosts.isEmpty()) {
            refresh()
        } else if (tab == CommunityTab.SAVED && _uiState.value.savedPosts.isEmpty()) {
            refresh()
        }
    }

    fun refresh() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    nextPublicCursor = null,
                    nextSavedCursor = null,
                    error = null
                )
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            loadAvailability(endpoint)
            if (_uiState.value.tab == CommunityTab.DISCOVER) {
                loadPublicCollections()
                loadPublicPosts(cursor = null, append = false)
            } else if (_uiState.value.tab == CommunityTab.MINE) {
                loadMyPosts()
            } else {
                loadSavedCollections()
                loadSavedPosts(cursor = null, append = false)
            }
        }
    }

    private suspend fun loadAvailability(endpoint: String) {
        accountApiService.communityAvailability(endpoint).onSuccess { availability ->
            _uiState.update { it.copy(availability = availability) }
        }
    }

    fun updateSearchQuery(value: String) {
        _uiState.update { it.copy(searchQuery = value.take(100), error = null) }
    }

    fun search() = refresh()

    fun selectCollectionDestination(value: String) {
        _uiState.update { it.copy(collectionDestinationFilter = value, error = null) }
        refresh()
    }

    fun selectCollectionTheme(value: String) {
        _uiState.update { it.copy(collectionThemeFilter = value, error = null) }
        refresh()
    }

    fun selectCollectionSort(value: String) {
        if (value !in setOf("curated", "recent", "richness")) return
        _uiState.update { it.copy(collectionSort = value, error = null) }
        refresh()
    }

    fun clearCollectionFilters() {
        _uiState.update {
            it.copy(collectionDestinationFilter = "", collectionThemeFilter = "", error = null)
        }
        refresh()
    }

    fun selectDestination(value: String) {
        _uiState.update { it.copy(destinationFilter = value, error = null) }
        refresh()
    }

    fun selectTag(value: String) {
        _uiState.update { it.copy(tagFilter = value, error = null) }
        refresh()
    }

    fun selectPoi(value: String) {
        _uiState.update { it.copy(poiFilter = value, error = null) }
        refresh()
    }

    fun selectDays(minDays: Int, maxDays: Int) {
        _uiState.update {
            it.copy(
                minDaysFilter = minDays.coerceIn(0, 31),
                maxDaysFilter = maxDays.coerceIn(0, 31),
                error = null
            )
        }
        refresh()
    }

    fun toggleHasMedia() {
        _uiState.update { it.copy(hasMediaOnly = !it.hasMediaOnly, error = null) }
        refresh()
    }

    fun clearFilters() {
        _uiState.update {
            it.copy(
                searchQuery = "",
                destinationFilter = "",
                tagFilter = "",
                poiFilter = "",
                minDaysFilter = 0,
                maxDaysFilter = 0,
                hasMediaOnly = false,
                error = null
            )
        }
        refresh()
    }

    fun loadMore() {
        val state = _uiState.value
        if ((state.tab != CommunityTab.DISCOVER && state.tab != CommunityTab.SAVED) ||
            state.isLoading || state.isLoadingMore
        ) return
        val cursor = if (state.tab == CommunityTab.DISCOVER) {
            state.nextPublicCursor
        } else {
            state.nextSavedCursor
        } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, error = null) }
            if (_uiState.value.tab == CommunityTab.DISCOVER) {
                loadPublicPosts(cursor = cursor, append = true)
            } else {
                loadSavedPosts(cursor = cursor, append = true)
            }
        }
    }

    private suspend fun loadPublicPosts(cursor: String?, append: Boolean) {
        val endpoint = configDataStore.accountEndpointFlow.first()
        val filters = _uiState.value
        accountApiService.publicCommunityPosts(
            endpoint = endpoint,
            cursor = cursor,
            query = filters.searchQuery,
            destination = filters.destinationFilter,
            tag = filters.tagFilter,
            poi = filters.poiFilter,
            minDays = filters.minDaysFilter,
            maxDays = filters.maxDaysFilter,
            hasMedia = filters.hasMediaOnly
        ).fold(
            onSuccess = { page ->
                _uiState.update {
                    it.copy(
                        publicPosts = if (append) {
                            (it.publicPosts + page.items).distinctBy(PublicCommunityPost::id)
                        } else {
                            page.items
                        },
                        facets = page.facets ?: CommunityFacets(),
                        mediaBaseUrl = communityMediaBaseUrl(endpoint),
                        isLoading = false,
                        isLoadingMore = false,
                        nextPublicCursor = page.nextCursor
                    )
                }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = error.message ?: "研学社区加载失败"
                    )
                }
            }
        )
    }

    private suspend fun loadPublicCollections() {
        val endpoint = configDataStore.accountEndpointFlow.first()
        val filters = _uiState.value
        accountApiService.publicCommunityCollections(
            endpoint = endpoint,
            limit = 20,
            destination = filters.collectionDestinationFilter,
            theme = filters.collectionThemeFilter,
            sort = filters.collectionSort
        ).onSuccess { page ->
            _uiState.update {
                it.copy(
                    collections = page.items,
                    collectionFacets = page.facets,
                    collectionSort = page.sortMode,
                    collectionSortExplanation = page.sortExplanation,
                    mediaBaseUrl = communityMediaBaseUrl(endpoint)
                )
            }
        }
    }

    private suspend fun loadMyPosts() {
        val session = configDataStore.authSessionFlow.first()
        if (session == null) {
            _uiState.update {
                it.copy(isLoading = false, error = "登录后可查看我的发布")
            }
            return
        }
        val endpoint = configDataStore.accountEndpointFlow.first()
        accountApiService.myCommunityPosts(endpoint, session.accessToken).fold(
            onSuccess = { page ->
                _uiState.update { it.copy(myPosts = page.items, isLoading = false) }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "我的发布加载失败"
                    )
                }
            }
        )
    }

    private suspend fun loadSavedPosts(cursor: String?, append: Boolean) {
        val session = configDataStore.authSessionFlow.first()
        if (session == null) {
            _uiState.update {
                it.copy(isLoading = false, isLoadingMore = false, error = "登录后可查看收藏")
            }
            return
        }
        val endpoint = configDataStore.accountEndpointFlow.first()
        accountApiService.bookmarkedCommunityPosts(
            endpoint = endpoint,
            token = session.accessToken,
            cursor = cursor
        ).fold(
            onSuccess = { page ->
                _uiState.update {
                    it.copy(
                        savedPosts = if (append) {
                            (it.savedPosts + page.items).distinctBy(PublicCommunityPost::id)
                        } else {
                            page.items
                        },
                        nextSavedCursor = page.nextCursor,
                        isLoading = false,
                        isLoadingMore = false,
                        mediaBaseUrl = communityMediaBaseUrl(endpoint),
                        error = null
                    )
                }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = error.message ?: "收藏加载失败"
                    )
                }
            }
        )
    }

    private suspend fun loadSavedCollections() {
        val session = configDataStore.authSessionFlow.first() ?: return
        val endpoint = configDataStore.accountEndpointFlow.first()
        accountApiService.bookmarkedCommunityCollections(
            endpoint = endpoint,
            token = session.accessToken,
            limit = 50
        ).onSuccess { page ->
            _uiState.update {
                it.copy(
                    savedCollections = page.items,
                    mediaBaseUrl = communityMediaBaseUrl(endpoint)
                )
            }
        }
    }
}

private fun communityMediaBaseUrl(endpoint: String): String {
    val clean = endpoint.trim().trimEnd('/')
    return if (clean.endsWith("/api")) clean.removeSuffix("/api") else clean
}

data class CommunityCollectionDetailUiState(
    val collection: CommunityCollection? = null,
    val posts: List<PublicCommunityPost> = emptyList(),
    val interaction: CommunityCollectionInteractionState? = null,
    val share: CommunityCollectionShare? = null,
    val shareUrl: String = "",
    val mediaBaseUrl: String = "",
    val availability: CommunityAvailability = CommunityAvailability(),
    val isLoading: Boolean = true,
    val isInteracting: Boolean = false,
    val error: String? = null,
    val actionMessage: String? = null
)

class CommunityCollectionDetailViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(CommunityCollectionDetailUiState())
    val uiState: StateFlow<CommunityCollectionDetailUiState> = _uiState.asStateFlow()

    fun load(collectionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, actionMessage = null) }
            val endpoint = configDataStore.accountEndpointFlow.first()
            val availability = accountApiService.communityAvailability(endpoint)
                .getOrDefault(CommunityAvailability())
            accountApiService.publicCommunityCollection(endpoint, collectionId, limit = 50).fold(
                onSuccess = { detail ->
                    val session = configDataStore.authSessionFlow.first()
                    val interaction = session?.let {
                        accountApiService.communityCollectionInteraction(
                            endpoint, it.accessToken, collectionId
                        ).getOrNull()
                    }
                    val share = accountApiService.publicCommunityCollectionShare(
                        endpoint, collectionId
                    ).getOrNull()
                    _uiState.value = CommunityCollectionDetailUiState(
                        collection = detail.collection,
                        posts = detail.items,
                        interaction = interaction,
                        share = share,
                        shareUrl = endpoint.trimEnd('/') + "/community/collections/$collectionId",
                        mediaBaseUrl = communityMediaBaseUrl(endpoint),
                        availability = availability,
                        isLoading = false
                    )
                },
                onFailure = { error ->
                    _uiState.value = CommunityCollectionDetailUiState(
                        isLoading = false,
                        availability = availability,
                        error = error.message ?: "专题加载失败"
                    )
                }
            )
        }
    }

    fun toggleBookmark() {
        val state = _uiState.value
        val collectionId = state.collection?.id ?: return
        if (state.isInteracting) return
        if (!state.availability.writeEnabled) {
            _uiState.update { it.copy(actionMessage = "社区暂时只读") }
            return
        }
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session == null) {
                _uiState.update { it.copy(actionMessage = "登录后可收藏专题") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(isInteracting = true, actionMessage = null) }
            accountApiService.toggleCommunityCollectionBookmark(
                endpoint, session.accessToken, collectionId
            ).fold(
                onSuccess = { interaction ->
                    _uiState.update { current ->
                        current.copy(
                            collection = current.collection?.copy(
                                bookmarkCount = interaction.bookmarkCount
                            ),
                            interaction = interaction,
                            isInteracting = false,
                            actionMessage = if (interaction.bookmarked) "已收藏专题" else "已取消收藏"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isInteracting = false,
                            actionMessage = error.message ?: "专题收藏操作失败"
                        )
                    }
                }
            )
        }
    }
}

data class CommunityPostDetailUiState(
    val post: PublicCommunityPost? = null,
    val interaction: CommunityInteractionState? = null,
    val comments: List<CommunityComment> = emptyList(),
    val commentsNextCursor: String? = null,
    val mediaBaseUrl: String = "",
    val availability: CommunityAvailability = CommunityAvailability(),
    val isLoading: Boolean = true,
    val isLoadingComments: Boolean = false,
    val isInteracting: Boolean = false,
    val isSubmittingComment: Boolean = false,
    val commentDraft: String = "",
    val error: String? = null,
    val showReportDialog: Boolean = false,
    val reportCategory: String = "privacy",
    val reportReason: String = "",
    val isReporting: Boolean = false,
    val showCommentReportDialog: Boolean = false,
    val commentReportId: String = "",
    val reportMessage: String? = null
)

class CommunityPostDetailViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(CommunityPostDetailUiState())
    val uiState: StateFlow<CommunityPostDetailUiState> = _uiState.asStateFlow()

    fun load(postId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val endpoint = configDataStore.accountEndpointFlow.first()
            accountApiService.communityAvailability(endpoint).onSuccess { availability ->
                _uiState.update { it.copy(availability = availability) }
            }
            accountApiService.publicCommunityPost(endpoint, postId).fold(
                onSuccess = { post ->
                    _uiState.update {
                        it.copy(
                            post = post,
                            interaction = CommunityInteractionState(
                                postId = post.id,
                                likeCount = post.likeCount,
                                commentCount = post.commentCount
                            ),
                            mediaBaseUrl = communityMediaBaseUrl(endpoint),
                            isLoading = false
                        )
                    }
                    loadComments(endpoint, postId)
                    loadInteractions(endpoint, postId)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "内容暂不可查看"
                        )
                    }
                }
            )
        }
    }

    fun toggleLike(postId: String) {
        toggleInteraction { endpoint, token ->
            accountApiService.toggleCommunityLike(endpoint, token, postId)
        }
    }

    fun toggleBookmark(postId: String) {
        toggleInteraction { endpoint, token ->
            accountApiService.toggleCommunityBookmark(endpoint, token, postId)
        }
    }

    private fun toggleInteraction(
        action: suspend (String, String) -> Result<CommunityInteractionState>
    ) {
        if (!_uiState.value.availability.writeEnabled) {
            _uiState.update { it.copy(reportMessage = "社区暂时只读") }
            return
        }
        if (_uiState.value.isInteracting) return
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session == null) {
                _uiState.update { it.copy(reportMessage = "登录后可参与互动") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(isInteracting = true, reportMessage = null) }
            action(endpoint, session.accessToken).fold(
                onSuccess = { state ->
                    _uiState.update { it.copy(interaction = state, isInteracting = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isInteracting = false, reportMessage = error.message ?: "互动操作失败")
                    }
                }
            )
        }
    }

    private suspend fun loadInteractions(endpoint: String, postId: String) {
        val session = configDataStore.authSessionFlow.first() ?: return
        accountApiService.communityInteractions(endpoint, session.accessToken, postId)
            .onSuccess { state -> _uiState.update { it.copy(interaction = state) } }
    }

    private suspend fun loadComments(endpoint: String, postId: String) {
        _uiState.update { it.copy(isLoadingComments = true) }
        val session = configDataStore.authSessionFlow.first()
        val result = if (session == null) {
            accountApiService.publicCommunityComments(endpoint, postId)
        } else {
            accountApiService.accountCommunityComments(endpoint, session.accessToken, postId)
        }
        result.fold(
            onSuccess = { page ->
                _uiState.update {
                    it.copy(
                        comments = page.items,
                        commentsNextCursor = page.nextCursor,
                        isLoadingComments = false
                    )
                }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(isLoadingComments = false, reportMessage = error.message ?: "评论加载失败")
                }
            }
        )
    }

    fun loadMoreComments(postId: String) {
        val cursor = _uiState.value.commentsNextCursor ?: return
        if (_uiState.value.isLoadingComments) return
        viewModelScope.launch {
            val endpoint = configDataStore.accountEndpointFlow.first()
            val session = configDataStore.authSessionFlow.first()
            _uiState.update { it.copy(isLoadingComments = true, reportMessage = null) }
            val result = if (session == null) {
                accountApiService.publicCommunityComments(endpoint, postId, cursor)
            } else {
                accountApiService.accountCommunityComments(endpoint, session.accessToken, postId, cursor)
            }
            result.fold(
                onSuccess = { page ->
                    _uiState.update { state ->
                        state.copy(
                            comments = (state.comments + page.items).distinctBy(CommunityComment::id),
                            commentsNextCursor = page.nextCursor,
                            isLoadingComments = false
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoadingComments = false, reportMessage = error.message ?: "更多评论加载失败")
                    }
                }
            )
        }
    }

    fun updateCommentDraft(value: String) {
        _uiState.update { it.copy(commentDraft = value.take(1000), reportMessage = null) }
    }

    fun submitComment(postId: String) {
        if (!_uiState.value.availability.writeEnabled) {
            _uiState.update { it.copy(reportMessage = "社区暂时只读") }
            return
        }
        val content = _uiState.value.commentDraft.trim()
        if (content.isBlank() || _uiState.value.isSubmittingComment) return
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session == null) {
                _uiState.update { it.copy(reportMessage = "登录后可发表评论") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(isSubmittingComment = true, reportMessage = null) }
            accountApiService.createCommunityComment(endpoint, session.accessToken, postId, content).fold(
                onSuccess = { comment ->
                    _uiState.update { state ->
                        state.copy(
                            comments = listOf(comment) + state.comments,
                            commentDraft = "",
                            isSubmittingComment = false,
                            interaction = state.interaction?.copy(
                                commentCount = state.interaction.commentCount + 1
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSubmittingComment = false, reportMessage = error.message ?: "评论发布失败")
                    }
                }
            )
        }
    }

    fun deleteComment(commentId: String) {
        if (_uiState.value.isSubmittingComment) return
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first() ?: return@launch
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(isSubmittingComment = true, reportMessage = null) }
            accountApiService.deleteCommunityComment(endpoint, session.accessToken, commentId).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(
                            comments = state.comments.filterNot { it.id == commentId },
                            isSubmittingComment = false,
                            interaction = state.interaction?.copy(
                                commentCount = (state.interaction.commentCount - 1).coerceAtLeast(0)
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSubmittingComment = false, reportMessage = error.message ?: "评论删除失败")
                    }
                }
            )
        }
    }

    fun openCommentReportDialog(commentId: String) {
        _uiState.update {
            it.copy(
                showCommentReportDialog = true,
                commentReportId = commentId,
                reportCategory = "privacy",
                reportReason = "",
                reportMessage = null
            )
        }
    }

    fun dismissCommentReportDialog() {
        if (_uiState.value.isReporting) return
        _uiState.update {
            it.copy(showCommentReportDialog = false, commentReportId = "", reportReason = "")
        }
    }

    fun submitCommentReport() {
        val state = _uiState.value
        if (state.commentReportId.isBlank() || state.isReporting) return
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session == null) {
                _uiState.update { it.copy(reportMessage = "登录后可举报评论") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(isReporting = true, reportMessage = null) }
            accountApiService.reportCommunityComment(
                endpoint = endpoint,
                token = session.accessToken,
                commentId = state.commentReportId,
                category = state.reportCategory,
                reason = state.reportReason
            ).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            showCommentReportDialog = false,
                            commentReportId = "",
                            isReporting = false,
                            reportReason = "",
                            reportMessage = "已提交评论举报"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isReporting = false, reportMessage = error.message ?: "评论举报提交失败")
                    }
                }
            )
        }
    }

    fun openReportDialog() {
        _uiState.update { it.copy(showReportDialog = true, reportMessage = null, error = null) }
    }

    fun dismissReportDialog() {
        if (_uiState.value.isReporting) return
        _uiState.update { it.copy(showReportDialog = false, reportReason = "", reportMessage = null) }
    }

    fun selectReportCategory(category: String) {
        _uiState.update { it.copy(reportCategory = category, reportMessage = null) }
    }

    fun updateReportReason(reason: String) {
        _uiState.update { it.copy(reportReason = reason.take(1000), reportMessage = null) }
    }

    fun submitReport(postId: String) {
        val state = _uiState.value
        if (state.isReporting) return
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session == null) {
                _uiState.update { it.copy(reportMessage = "登录后可提交举报") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(isReporting = true, reportMessage = null) }
            accountApiService.reportCommunityPost(
                endpoint = endpoint,
                token = session.accessToken,
                postId = postId,
                category = state.reportCategory,
                reason = state.reportReason
            ).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            showReportDialog = false,
                            isReporting = false,
                            reportReason = "",
                            reportMessage = "已提交举报"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isReporting = false, reportMessage = error.message ?: "举报提交失败")
                    }
                }
            )
        }
    }

    fun clearReportMessage() {
        _uiState.update { it.copy(reportMessage = null) }
    }
}
