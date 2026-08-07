package com.oa.automation.ui.screen.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.CommunityCommentReportQueueItem
import com.oa.automation.domain.model.CommunityCollection
import com.oa.automation.domain.model.CommunityCollectionOperationsSummary
import com.oa.automation.domain.model.CommunityCollectionPost
import com.oa.automation.domain.model.CommunityModerationItem
import com.oa.automation.domain.model.CommunityOperationsSummary
import com.oa.automation.infrastructure.account.AccountApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CommunityModerationSection { POSTS, COMMENTS, COLLECTIONS }

data class CommunityModerationUiState(
    val section: CommunityModerationSection = CommunityModerationSection.POSTS,
    val filter: String = "pending",
    val items: List<CommunityModerationItem> = emptyList(),
    val commentReports: List<CommunityCommentReportQueueItem> = emptyList(),
    val collections: List<CommunityCollection> = emptyList(),
    val collectionSummary: CommunityCollectionOperationsSummary? = null,
    val summary: CommunityOperationsSummary? = null,
    val nextPostCursor: String? = null,
    val nextCommentReportCursor: String? = null,
    val nextCollectionCursor: String? = null,
    val isAdmin: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val processingPostId: String? = null,
    val processingReportId: String? = null,
    val error: String? = null,
    val message: String? = null,
    val rejectPostId: String? = null,
    val rejectReason: String = "",
    val deleteReportId: String? = null,
    val showCreateCollection: Boolean = false,
    val collectionTitle: String = "",
    val collectionDescription: String = "",
    val collectionDestination: String = "",
    val collectionTheme: String = "",
    val processingCollectionId: String? = null,
    val curatePostId: String? = null,
    val curateCollectionId: String = "",
    val curationNote: String = "",
    val curationPosition: String = "0",
    val selectedPostIds: Set<String> = emptySet(),
    val showBatchCurate: Boolean = false,
    val coverCollectionId: String? = null,
    val coverCandidates: List<CommunityCollectionPost> = emptyList(),
    val coverPostId: String = ""
)

class CommunityModerationViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(CommunityModerationUiState())
    val uiState: StateFlow<CommunityModerationUiState> = _uiState.asStateFlow()

    fun selectSection(section: CommunityModerationSection) {
        val state = _uiState.value
        if (state.section == section || state.isLoading || state.isLoadingMore) return
        _uiState.update { it.copy(section = section, error = null, message = null) }
        load()
    }

    fun load(filter: String = _uiState.value.filter) {
        loadPage(filter = filter, cursor = null, append = false)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore) return
        val cursor = if (state.section == CommunityModerationSection.POSTS) {
            state.nextPostCursor
        } else if (state.section == CommunityModerationSection.COMMENTS) {
            state.nextCommentReportCursor
        } else {
            state.nextCollectionCursor
        } ?: return
        loadPage(filter = state.filter, cursor = cursor, append = true)
    }

    private fun loadPage(filter: String, cursor: String?, append: Boolean) {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore) return
        viewModelScope.launch {
            val section = _uiState.value.section
            val session = configDataStore.authSessionFlow.first()
            val isAdmin = session?.user?.isAdmin == true
            _uiState.update {
                it.copy(
                    filter = filter,
                    isAdmin = isAdmin,
                    isLoading = !append,
                    isLoadingMore = append,
                    error = null
                )
            }
            if (!isAdmin || session == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = "仅管理员可访问审核工作台"
                    )
                }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            var loadedSuccessfully = false
            if (section == CommunityModerationSection.POSTS) {
                accountApiService.adminCommunityModerationQueue(
                    endpoint = endpoint,
                    token = session.accessToken,
                    status = filter,
                    cursor = cursor
                ).fold(
                    onSuccess = { page ->
                        loadedSuccessfully = true
                        _uiState.update { current ->
                            current.copy(
                                items = if (append) {
                                    (current.items + page.items).distinctBy(CommunityModerationItem::id)
                                } else {
                                    page.items
                                },
                                nextPostCursor = page.nextCursor,
                                selectedPostIds = if (append) current.selectedPostIds else emptySet(),
                                isLoading = false,
                                isLoadingMore = false
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isLoadingMore = false,
                                error = error.message ?: "审核队列加载失败"
                            )
                        }
                    }
                )
            } else if (section == CommunityModerationSection.COMMENTS) {
                accountApiService.adminCommunityCommentReports(
                    endpoint = endpoint,
                    token = session.accessToken,
                    cursor = cursor
                ).fold(
                    onSuccess = { page ->
                        loadedSuccessfully = true
                        _uiState.update { current ->
                            current.copy(
                                commentReports = if (append) {
                                    (current.commentReports + page.items)
                                        .distinctBy(CommunityCommentReportQueueItem::id)
                                } else {
                                    page.items
                                },
                                nextCommentReportCursor = page.nextCursor,
                                isLoading = false,
                                isLoadingMore = false
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isLoadingMore = false,
                                error = error.message ?: "评论举报队列加载失败"
                            )
                        }
                    }
                )
            } else {
                accountApiService.adminCommunityCollections(
                    endpoint = endpoint,
                    token = session.accessToken,
                    status = "all",
                    cursor = cursor
                ).fold(
                    onSuccess = { page ->
                        loadedSuccessfully = true
                        _uiState.update { current ->
                            current.copy(
                                collections = if (append) {
                                    (current.collections + page.items)
                                        .distinctBy(CommunityCollection::id)
                                } else {
                                    page.items
                                },
                                nextCollectionCursor = page.nextCursor,
                                isLoading = false,
                                isLoadingMore = false
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isLoadingMore = false,
                                error = error.message ?: "专题加载失败"
                            )
                        }
                    }
                )
            }
            if (!append && loadedSuccessfully) {
                if (section == CommunityModerationSection.COLLECTIONS) {
                    accountApiService.adminCommunityCollectionOperationsSummary(
                        endpoint = endpoint,
                        token = session.accessToken
                    ).onSuccess { summary ->
                        _uiState.update { it.copy(collectionSummary = summary) }
                    }
                } else {
                    accountApiService.adminCommunityOperationsSummary(
                        endpoint = endpoint,
                        token = session.accessToken
                    ).onSuccess { summary ->
                        _uiState.update { it.copy(summary = summary) }
                    }
                }
            }
        }
    }

    fun approve(postId: String) {
        moderate(postId, "approved", "")
    }

    fun openReject(postId: String) {
        _uiState.update { it.copy(rejectPostId = postId, rejectReason = "", error = null) }
    }

    fun dismissReject() {
        if (_uiState.value.processingPostId == null) {
            _uiState.update { it.copy(rejectPostId = null, rejectReason = "") }
        }
    }

    fun updateRejectReason(value: String) {
        _uiState.update { it.copy(rejectReason = value.take(500)) }
    }

    fun reject() {
        val state = _uiState.value
        val postId = state.rejectPostId ?: return
        if (state.rejectReason.isBlank()) {
            _uiState.update { it.copy(error = "拒绝发布时必须填写审核说明") }
            return
        }
        moderate(postId, "rejected", state.rejectReason)
    }

    fun keepComment(reportId: String) {
        resolveCommentReport(reportId, "keep")
    }

    fun openDeleteComment(reportId: String) {
        if (_uiState.value.processingReportId == null) {
            _uiState.update { it.copy(deleteReportId = reportId, error = null) }
        }
    }

    fun dismissDeleteComment() {
        if (_uiState.value.processingReportId == null) {
            _uiState.update { it.copy(deleteReportId = null) }
        }
    }

    fun deleteReportedComment() {
        val reportId = _uiState.value.deleteReportId ?: return
        resolveCommentReport(reportId, "delete")
    }

    fun openCreateCollection() {
        _uiState.update {
            it.copy(
                showCreateCollection = true,
                collectionTitle = "",
                collectionDescription = "",
                collectionDestination = "",
                collectionTheme = "",
                error = null
            )
        }
    }

    fun dismissCreateCollection() {
        if (_uiState.value.processingCollectionId == null) {
            _uiState.update { it.copy(showCreateCollection = false) }
        }
    }

    fun updateCollectionTitle(value: String) {
        _uiState.update { it.copy(collectionTitle = value.take(120)) }
    }

    fun updateCollectionDescription(value: String) {
        _uiState.update { it.copy(collectionDescription = value.take(1000)) }
    }

    fun updateCollectionDestination(value: String) {
        _uiState.update { it.copy(collectionDestination = value.take(120)) }
    }

    fun updateCollectionTheme(value: String) {
        _uiState.update { it.copy(collectionTheme = value.take(80)) }
    }

    fun createCollection() {
        val state = _uiState.value
        if (state.collectionTitle.isBlank() || state.processingCollectionId != null) return
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session?.user?.isAdmin != true) {
                _uiState.update { it.copy(error = "仅管理员可创建专题") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(processingCollectionId = "new", error = null) }
            accountApiService.createCommunityCollection(
                endpoint = endpoint,
                token = session.accessToken,
                title = state.collectionTitle,
                description = state.collectionDescription,
                destination = state.collectionDestination,
                theme = state.collectionTheme
            ).fold(
                onSuccess = { created ->
                    _uiState.update {
                        it.copy(
                            collections = listOf(created) + it.collections,
                            processingCollectionId = null,
                            showCreateCollection = false,
                            message = "专题已创建，请在帖子中收录内容"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(processingCollectionId = null, error = error.message ?: "专题创建失败")
                    }
                }
            )
        }
    }

    fun toggleCollection(collection: CommunityCollection) {
        val targetStatus = if (collection.status == "published") "unpublished" else "published"
        if (_uiState.value.processingCollectionId != null) return
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session?.user?.isAdmin != true) {
                _uiState.update { it.copy(error = "仅管理员可维护专题") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(processingCollectionId = collection.id, error = null) }
            accountApiService.setCommunityCollectionStatus(
                endpoint, session.accessToken, collection.id, targetStatus
            ).fold(
                onSuccess = { updated ->
                    _uiState.update {
                        it.copy(
                            collections = it.collections.map { item ->
                                if (item.id == updated.id) updated else item
                            },
                            processingCollectionId = null,
                            message = if (targetStatus == "published") "专题已发布" else "专题已下线"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(processingCollectionId = null, error = error.message ?: "专题状态更新失败")
                    }
                }
            )
        }
    }

    fun openCurate(postId: String) {
        _uiState.update {
            it.copy(
                curatePostId = postId,
                curateCollectionId = it.collections.firstOrNull()?.id.orEmpty(),
                curationNote = "",
                curationPosition = "0",
                error = null
            )
        }
        if (_uiState.value.collections.isEmpty()) loadCollectionsForCuration()
    }

    fun dismissCurate() {
        if (_uiState.value.processingCollectionId == null) {
            _uiState.update { it.copy(curatePostId = null) }
        }
    }

    fun selectCurateCollection(id: String) {
        _uiState.update { it.copy(curateCollectionId = id) }
    }

    fun updateCurationNote(value: String) {
        _uiState.update { it.copy(curationNote = value.take(200)) }
    }

    fun updateCurationPosition(value: String) {
        _uiState.update { it.copy(curationPosition = value.filter(Char::isDigit).take(4)) }
    }

    fun curatePost() {
        val state = _uiState.value
        val postId = state.curatePostId ?: return
        val collectionId = state.curateCollectionId
        if (collectionId.isBlank() || state.processingCollectionId != null) return
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session?.user?.isAdmin != true) {
                _uiState.update { it.copy(error = "仅管理员可收录专题") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(processingCollectionId = collectionId, error = null) }
            accountApiService.addCommunityCollectionPost(
                endpoint,
                session.accessToken,
                collectionId,
                postId,
                state.curationPosition.toIntOrNull() ?: 0,
                state.curationNote
            ).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            curatePostId = null,
                            processingCollectionId = null,
                            message = "已收录到专题"
                        )
                    }
                    load("all")
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(processingCollectionId = null, error = error.message ?: "专题收录失败")
                    }
                }
            )
        }
    }

    fun togglePostSelection(postId: String) {
        val item = _uiState.value.items.firstOrNull { it.id == postId }
        if (item?.review?.status != "approved") return
        _uiState.update {
            val selected = it.selectedPostIds.toMutableSet()
            if (!selected.add(postId)) selected.remove(postId)
            it.copy(selectedPostIds = selected)
        }
    }

    fun openBatchCurate() {
        if (_uiState.value.selectedPostIds.isEmpty()) return
        _uiState.update {
            it.copy(
                showBatchCurate = true,
                curateCollectionId = it.curateCollectionId.ifBlank {
                    it.collections.firstOrNull()?.id.orEmpty()
                },
                curationNote = "",
                curationPosition = "0",
                error = null
            )
        }
        if (_uiState.value.collections.isEmpty()) loadCollectionsForCuration()
    }

    fun dismissBatchCurate() {
        if (_uiState.value.processingCollectionId == null) {
            _uiState.update { it.copy(showBatchCurate = false) }
        }
    }

    fun batchCuratePosts() {
        val state = _uiState.value
        val collectionId = state.curateCollectionId
        val orderedPostIds = state.items.map(CommunityModerationItem::id)
            .filter(state.selectedPostIds::contains)
        if (collectionId.isBlank() || orderedPostIds.isEmpty() ||
            state.processingCollectionId != null
        ) return
        val startPosition = state.curationPosition.toIntOrNull() ?: 0
        if (startPosition + orderedPostIds.size - 1 > 9999) {
            _uiState.update { it.copy(error = "批量顺序不能超过 9999") }
            return
        }
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session?.user?.isAdmin != true) {
                _uiState.update { it.copy(error = "仅管理员可批量收录专题") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(processingCollectionId = collectionId, error = null) }
            accountApiService.batchAddCommunityCollectionPosts(
                endpoint = endpoint,
                token = session.accessToken,
                collectionId = collectionId,
                assignments = orderedPostIds.mapIndexed { index, postId ->
                    Triple(postId, startPosition + index, state.curationNote)
                }
            ).fold(
                onSuccess = { result ->
                    _uiState.update {
                        it.copy(
                            showBatchCurate = false,
                            selectedPostIds = emptySet(),
                            processingCollectionId = null,
                            message = "已批量收录 ${result.items.size} 篇笔记"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(processingCollectionId = null, error = error.message ?: "批量收录失败")
                    }
                }
            )
        }
    }

    fun openCollectionCover(collectionId: String) {
        if (_uiState.value.processingCollectionId != null) return
        _uiState.update {
            it.copy(
                coverCollectionId = collectionId,
                coverCandidates = emptyList(),
                coverPostId = "",
                processingCollectionId = collectionId,
                error = null
            )
        }
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session?.user?.isAdmin != true) {
                _uiState.update {
                    it.copy(coverCollectionId = null, processingCollectionId = null)
                }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            accountApiService.adminCommunityCollection(
                endpoint, session.accessToken, collectionId
            ).fold(
                onSuccess = { detail ->
                    val candidates = detail.posts.filter { post -> post.visible && post.hasMedia }
                    _uiState.update {
                        it.copy(
                            coverCandidates = candidates,
                            coverPostId = detail.coverPostId.takeIf { selected ->
                                candidates.any { candidate -> candidate.postId == selected }
                            }.orEmpty(),
                            processingCollectionId = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            coverCollectionId = null,
                            processingCollectionId = null,
                            error = error.message ?: "封面候选加载失败"
                        )
                    }
                }
            )
        }
    }

    fun selectCoverPost(postId: String) {
        _uiState.update { it.copy(coverPostId = postId) }
    }

    fun dismissCollectionCover() {
        if (_uiState.value.processingCollectionId == null) {
            _uiState.update { it.copy(coverCollectionId = null, coverCandidates = emptyList()) }
        }
    }

    fun saveCollectionCover() {
        val state = _uiState.value
        val collectionId = state.coverCollectionId ?: return
        if (state.processingCollectionId != null) return
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session?.user?.isAdmin != true) return@launch
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(processingCollectionId = collectionId, error = null) }
            accountApiService.setCommunityCollectionCover(
                endpoint,
                session.accessToken,
                collectionId,
                state.coverPostId.takeIf(String::isNotBlank)
            ).fold(
                onSuccess = { updated ->
                    _uiState.update {
                        it.copy(
                            collections = it.collections.map { item ->
                                if (item.id == updated.id) updated else item
                            },
                            coverCollectionId = null,
                            coverCandidates = emptyList(),
                            processingCollectionId = null,
                            message = if (state.coverPostId.isBlank()) "已恢复自动封面" else "专题封面已更新"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(processingCollectionId = null, error = error.message ?: "封面更新失败")
                    }
                }
            )
        }
    }

    private fun loadCollectionsForCuration() {
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first() ?: return@launch
            if (!session.user.isAdmin) return@launch
            val endpoint = configDataStore.accountEndpointFlow.first()
            accountApiService.adminCommunityCollections(endpoint, session.accessToken).onSuccess { page ->
                _uiState.update {
                    it.copy(
                        collections = page.items,
                        curateCollectionId = it.curateCollectionId.ifBlank {
                            page.items.firstOrNull()?.id.orEmpty()
                        }
                    )
                }
            }
        }
    }

    private fun resolveCommentReport(reportId: String, decision: String) {
        val state = _uiState.value
        if (state.processingReportId != null) return
        val targetCommentId = state.commentReports.firstOrNull { it.id == reportId }?.commentId
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session?.user?.isAdmin != true) {
                _uiState.update { it.copy(error = "仅管理员可处置评论举报") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update {
                it.copy(processingReportId = reportId, error = null, message = null)
            }
            accountApiService.resolveCommunityCommentReport(
                endpoint = endpoint,
                token = session.accessToken,
                reportId = reportId,
                decision = decision
            ).fold(
                onSuccess = {
                    _uiState.update { current ->
                        current.copy(
                            commentReports = current.commentReports.filterNot {
                                it.id == reportId ||
                                    (decision == "delete" && targetCommentId != null &&
                                        it.commentId == targetCommentId)
                            },
                            processingReportId = null,
                            deleteReportId = null,
                            message = if (decision == "delete") "评论已删除" else "评论已保留"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(processingReportId = null, error = error.message ?: "评论举报处置失败")
                    }
                }
            )
        }
    }

    private fun moderate(postId: String, decision: String, reason: String) {
        val state = _uiState.value
        if (state.processingPostId != null) return
        viewModelScope.launch {
            val session = configDataStore.authSessionFlow.first()
            if (session?.user?.isAdmin != true) {
                _uiState.update { it.copy(error = "仅管理员可处置社区内容") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            _uiState.update { it.copy(processingPostId = postId, error = null, message = null) }
            accountApiService.moderateCommunityPost(
                endpoint = endpoint,
                token = session.accessToken,
                postId = postId,
                decision = decision,
                reason = reason
            ).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            processingPostId = null,
                            rejectPostId = null,
                            rejectReason = "",
                            message = if (decision == "approved") "内容已通过审核" else "内容已拒绝并下线"
                        )
                    }
                    load(state.filter)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(processingPostId = null, error = error.message ?: "审核处理失败")
                    }
                }
            )
        }
    }
}
