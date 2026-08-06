package com.oa.automation.ui.screen.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.MyCommunityPost
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

    private suspend fun loadPublicPosts() {
        val endpoint = configDataStore.accountEndpointFlow.first()
        accountApiService.publicCommunityPosts(endpoint).fold(
            onSuccess = { page ->
                _uiState.update { it.copy(publicPosts = page.items, isLoading = false) }
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

data class CommunityPostDetailUiState(
    val post: PublicCommunityPost? = null,
    val isLoading: Boolean = true,
    val error: String? = null
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
                    _uiState.update { it.copy(post = post, isLoading = false) }
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
}
