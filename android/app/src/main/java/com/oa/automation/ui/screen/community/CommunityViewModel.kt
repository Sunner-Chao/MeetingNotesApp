package com.oa.automation.ui.screen.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.MyCommunityPost
import com.oa.automation.domain.model.CommunityFacets
import com.oa.automation.domain.model.PublicCommunityPost
import com.oa.automation.infrastructure.account.AccountApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CommunityTab { DISCOVER, MINE }

data class CommunityUiState(
    val tab: CommunityTab = CommunityTab.DISCOVER,
    val publicPosts: List<PublicCommunityPost> = emptyList(),
    val myPosts: List<MyCommunityPost> = emptyList(),
    val searchQuery: String = "",
    val destinationFilter: String = "",
    val tagFilter: String = "",
    val hasMediaOnly: Boolean = false,
    val facets: CommunityFacets = CommunityFacets(),
    val mediaBaseUrl: String = "",
    val isLoading: Boolean = false,
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
        }
    }

    fun refresh() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            if (_uiState.value.tab == CommunityTab.DISCOVER) {
                loadPublicPosts()
            } else {
                loadMyPosts()
            }
        }
    }

    fun updateSearchQuery(value: String) {
        _uiState.update { it.copy(searchQuery = value.take(100), error = null) }
    }

    fun search() = refresh()

    fun selectDestination(value: String) {
        _uiState.update { it.copy(destinationFilter = value, error = null) }
        refresh()
    }

    fun selectTag(value: String) {
        _uiState.update { it.copy(tagFilter = value, error = null) }
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
                hasMediaOnly = false,
                error = null
            )
        }
        refresh()
    }

    private suspend fun loadPublicPosts() {
        val endpoint = configDataStore.accountEndpointFlow.first()
        val filters = _uiState.value
        accountApiService.publicCommunityPosts(
            endpoint = endpoint,
            query = filters.searchQuery,
            destination = filters.destinationFilter,
            tag = filters.tagFilter,
            hasMedia = filters.hasMediaOnly
        ).fold(
            onSuccess = { page ->
                _uiState.update {
                    it.copy(
                        publicPosts = page.items,
                        facets = page.facets ?: CommunityFacets(),
                        mediaBaseUrl = communityMediaBaseUrl(endpoint),
                        isLoading = false
                    )
                }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "研学社区加载失败"
                    )
                }
            }
        )
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
}

private fun communityMediaBaseUrl(endpoint: String): String {
    val clean = endpoint.trim().trimEnd('/')
    return if (clean.endsWith("/api")) clean.removeSuffix("/api") else clean
}

data class CommunityPostDetailUiState(
    val post: PublicCommunityPost? = null,
    val mediaBaseUrl: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val showReportDialog: Boolean = false,
    val reportCategory: String = "privacy",
    val reportReason: String = "",
    val isReporting: Boolean = false,
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
            accountApiService.publicCommunityPost(endpoint, postId).fold(
                onSuccess = { post ->
                    _uiState.update {
                        it.copy(
                            post = post,
                            mediaBaseUrl = communityMediaBaseUrl(endpoint),
                            isLoading = false
                        )
                    }
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
