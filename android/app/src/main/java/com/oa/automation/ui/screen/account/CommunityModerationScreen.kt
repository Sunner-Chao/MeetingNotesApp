package com.oa.automation.ui.screen.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.CommunityCommentReportQueueItem
import com.oa.automation.domain.model.CommunityModerationItem
import com.oa.automation.infrastructure.account.AccountApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CommunityModerationSection { POSTS, COMMENTS }

data class CommunityModerationUiState(
    val section: CommunityModerationSection = CommunityModerationSection.POSTS,
    val filter: String = "pending",
    val items: List<CommunityModerationItem> = emptyList(),
    val commentReports: List<CommunityCommentReportQueueItem> = emptyList(),
    val isAdmin: Boolean = false,
    val isLoading: Boolean = false,
    val processingPostId: String? = null,
    val processingReportId: String? = null,
    val error: String? = null,
    val message: String? = null,
    val rejectPostId: String? = null,
    val rejectReason: String = "",
    val deleteReportId: String? = null
)

class CommunityModerationViewModel(
    private val configDataStore: ConfigDataStore,
    private val accountApiService: AccountApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(CommunityModerationUiState())
    val uiState: StateFlow<CommunityModerationUiState> = _uiState.asStateFlow()

    fun selectSection(section: CommunityModerationSection) {
        val state = _uiState.value
        if (state.section == section || state.isLoading) return
        _uiState.update { it.copy(section = section, error = null, message = null) }
        load()
    }

    fun load(filter: String = _uiState.value.filter) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            val section = _uiState.value.section
            val session = configDataStore.authSessionFlow.first()
            val isAdmin = session?.user?.isAdmin == true
            _uiState.update { it.copy(filter = filter, isAdmin = isAdmin, isLoading = true, error = null) }
            if (!isAdmin || session == null) {
                _uiState.update { it.copy(isLoading = false, error = "仅管理员可访问审核工作台") }
                return@launch
            }
            val endpoint = configDataStore.accountEndpointFlow.first()
            if (section == CommunityModerationSection.POSTS) {
                accountApiService.adminCommunityModerationQueue(
                    endpoint = endpoint,
                    token = session.accessToken,
                    status = filter
                ).fold(
                    onSuccess = { page ->
                        _uiState.update { it.copy(items = page.items, isLoading = false) }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(isLoading = false, error = error.message ?: "审核队列加载失败")
                        }
                    }
                )
            } else {
                accountApiService.adminCommunityCommentReports(
                    endpoint = endpoint,
                    token = session.accessToken
                ).fold(
                    onSuccess = { page ->
                        _uiState.update { it.copy(commentReports = page.items, isLoading = false) }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(isLoading = false, error = error.message ?: "评论举报队列加载失败")
                        }
                    }
                )
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

    private fun resolveCommentReport(reportId: String, decision: String) {
        val state = _uiState.value
        if (state.processingReportId != null) return
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
                            commentReports = current.commentReports.filterNot { it.id == reportId },
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
