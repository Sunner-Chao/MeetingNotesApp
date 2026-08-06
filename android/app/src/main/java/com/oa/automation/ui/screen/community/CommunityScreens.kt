package com.oa.automation.ui.screen.community

import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.MyCommunityPost
import com.oa.automation.domain.model.PublicCommunityPost
import java.text.SimpleDateFormat
import java.net.URL
import java.util.Date
import java.util.Locale
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CommunityScreen(
    onOpenPost: (String) -> Unit,
    viewModel: CommunityViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("研学社区", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = uiState.tab.ordinal) {
                Tab(
                    selected = uiState.tab == CommunityTab.DISCOVER,
                    onClick = { viewModel.selectTab(CommunityTab.DISCOVER) },
                    text = { Text("发现") },
                    icon = { Icon(Icons.Default.Explore, contentDescription = null) }
                )
                Tab(
                    selected = uiState.tab == CommunityTab.MINE,
                    onClick = { viewModel.selectTab(CommunityTab.MINE) },
                    text = { Text("我的发布") },
                    icon = { Icon(Icons.Default.AutoStories, contentDescription = null) }
                )
            }
            CommunityContent(
                state = uiState,
                onOpenPost = onOpenPost,
                onRefresh = viewModel::refresh,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onSearch = viewModel::search,
                onDestinationSelect = viewModel::selectDestination,
                onTagSelect = viewModel::selectTag,
                onPoiSelect = viewModel::selectPoi,
                onDaysSelect = viewModel::selectDays,
                onToggleHasMedia = viewModel::toggleHasMedia,
                onClearFilters = viewModel::clearFilters,
                onLoadMore = viewModel::loadMore
            )
        }
    }
}

@Composable
private fun CommunityContent(
    state: CommunityUiState,
    onOpenPost: (String) -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDestinationSelect: (String) -> Unit,
    onTagSelect: (String) -> Unit,
    onPoiSelect: (String) -> Unit,
    onDaysSelect: (Int, Int) -> Unit,
    onToggleHasMedia: () -> Unit,
    onClearFilters: () -> Unit,
    onLoadMore: () -> Unit
) {
    val isDiscover = state.tab == CommunityTab.DISCOVER
    val isEmpty = if (isDiscover) state.publicPosts.isEmpty() else state.myPosts.isEmpty()
    if (isDiscover) {
        CommunityDiscoverFilters(
            state = state,
            onSearchQueryChange = onSearchQueryChange,
            onSearch = onSearch,
            onDestinationSelect = onDestinationSelect,
            onTagSelect = onTagSelect,
            onPoiSelect = onPoiSelect,
            onDaysSelect = onDaysSelect,
            onToggleHasMedia = onToggleHasMedia,
            onClearFilters = onClearFilters
        )
    }
    when {
        state.isLoading && isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        isEmpty -> CommunityEmptyState(
            message = state.error ?: if (isDiscover) "暂无已通过审核的研学记录" else "暂无已同步的发布记录",
            isError = state.error != null,
            onRefresh = onRefresh
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.error?.let { error ->
                item { CommunityInlineError(error) }
            }
            if (isDiscover) {
                items(state.publicPosts, key = { it.id }) { post ->
                    PublicPostCard(
                        post = post,
                        mediaBaseUrl = state.mediaBaseUrl,
                        onClick = { onOpenPost(post.id) }
                    )
                }
                if (state.nextPublicCursor != null) {
                    item {
                        CommunityLoadMore(
                            isLoading = state.isLoadingMore,
                            onLoadMore = onLoadMore
                        )
                    }
                }
            } else {
                items(state.myPosts, key = { it.id }) { post -> MyPostCard(post) }
            }
        }
    }
}

@Composable
private fun CommunityDiscoverFilters(
    state: CommunityUiState,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDestinationSelect: (String) -> Unit,
    onTagSelect: (String) -> Unit,
    onPoiSelect: (String) -> Unit,
    onDaysSelect: (Int, Int) -> Unit,
    onToggleHasMedia: () -> Unit,
    onClearFilters: () -> Unit
) {
    var destinationExpanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    var tagExpanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    var poiExpanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    var daysExpanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    val hasFilters = state.searchQuery.isNotBlank() || state.destinationFilter.isNotBlank() ||
        state.tagFilter.isNotBlank() || state.poiFilter.isNotBlank() ||
        state.minDaysFilter > 0 || state.maxDaysFilter > 0 || state.hasMediaOnly
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清除搜索")
                        }
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                }
            },
            placeholder = { Text("搜索标题、地点或关键词") },
            shape = RoundedCornerShape(8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(
                    onClick = { destinationExpanded = true },
                    shape = RoundedCornerShape(7.dp),
                    color = if (state.destinationFilter.isBlank()) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.primaryContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(state.destinationFilter.ifBlank { "目的地" }, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                DropdownMenu(expanded = destinationExpanded, onDismissRequest = { destinationExpanded = false }) {
                    DropdownMenuItem(text = { Text("全部目的地") }, onClick = { destinationExpanded = false; onDestinationSelect("") })
                    state.facets.destinations.forEach { value ->
                        DropdownMenuItem(text = { Text(value) }, onClick = { destinationExpanded = false; onDestinationSelect(value) })
                    }
                }
            }
            Box {
                Surface(
                    onClick = { tagExpanded = true },
                    shape = RoundedCornerShape(7.dp),
                    color = if (state.tagFilter.isBlank()) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.primaryContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text("主题：${state.tagFilter.ifBlank { "全部" }}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                DropdownMenu(expanded = tagExpanded, onDismissRequest = { tagExpanded = false }) {
                    DropdownMenuItem(text = { Text("全部主题") }, onClick = { tagExpanded = false; onTagSelect("") })
                    state.facets.tags.forEach { value ->
                        DropdownMenuItem(text = { Text("#$value") }, onClick = { tagExpanded = false; onTagSelect(value) })
                    }
                }
            }
            Box {
                Surface(
                    onClick = { poiExpanded = true },
                    shape = RoundedCornerShape(7.dp),
                    color = if (state.poiFilter.isBlank()) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.primaryContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text("地点：${state.poiFilter.ifBlank { "全部" }}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                DropdownMenu(expanded = poiExpanded, onDismissRequest = { poiExpanded = false }) {
                    DropdownMenuItem(text = { Text("全部地点") }, onClick = { poiExpanded = false; onPoiSelect("") })
                    state.facets.pois.forEach { value ->
                        DropdownMenuItem(text = { Text(value) }, onClick = { poiExpanded = false; onPoiSelect(value) })
                    }
                }
            }
            Box {
                Surface(
                    onClick = { daysExpanded = true },
                    shape = RoundedCornerShape(7.dp),
                    color = if (state.minDaysFilter == 0 && state.maxDaysFilter == 0) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.primaryContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(communityDaysLabel(state.minDaysFilter, state.maxDaysFilter), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                DropdownMenu(expanded = daysExpanded, onDismissRequest = { daysExpanded = false }) {
                    DropdownMenuItem(text = { Text("全部天数") }, onClick = { daysExpanded = false; onDaysSelect(0, 0) })
                    DropdownMenuItem(text = { Text("1-3 天") }, onClick = { daysExpanded = false; onDaysSelect(1, 3) })
                    DropdownMenuItem(text = { Text("4-7 天") }, onClick = { daysExpanded = false; onDaysSelect(4, 7) })
                    DropdownMenuItem(text = { Text("8 天以上") }, onClick = { daysExpanded = false; onDaysSelect(8, 31) })
                }
            }
            Surface(
                onClick = onToggleHasMedia,
                shape = RoundedCornerShape(7.dp),
                color = if (state.hasMediaOnly) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text("仅看图片", modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
            }
            if (hasFilters) {
                TextButton(onClick = onClearFilters, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("清除") }
            }
        }
    }
}

@Composable
private fun CommunityLoadMore(
    isLoading: Boolean,
    onLoadMore: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        TextButton(onClick = onLoadMore, enabled = !isLoading) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(if (isLoading) "加载中" else "加载更多")
        }
    }
}

private fun communityDaysLabel(minDays: Int, maxDays: Int): String = when {
    minDays == 1 && maxDays == 3 -> "1-3 天"
    minDays == 4 && maxDays == 7 -> "4-7 天"
    minDays == 8 && maxDays == 31 -> "8 天以上"
    else -> "天数"
}

@Composable
private fun PublicPostCard(
    post: PublicCommunityPost,
    mediaBaseUrl: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            post.media.firstOrNull()?.let { media ->
                CommunityThumbnail(
                    url = "$mediaBaseUrl${media.thumbnailUrl}",
                    contentDescription = "研学图片",
                    modifier = Modifier.fillMaxWidth().height(184.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Verified,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    post.authorLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatCommunityDate(post.publishedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Text(post.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            CommunityPostMetadata(post)
            Text(
                post.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (post.media.isNotEmpty()) "${post.media.size} 张图片" else if (post.aiAssisted) "已标注 AI 协作" else "现场记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "查看详情",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun MyPostCard(post: MyCommunityPost) {
    val (label, color) = when {
        post.status == "withdrawn" -> "已撤回" to MaterialTheme.colorScheme.outline
        post.status == "private_draft" -> "私有草稿" to MaterialTheme.colorScheme.secondary
        post.review.status == "approved" -> "已通过" to MaterialTheme.colorScheme.primary
        post.review.status == "rejected" -> "需调整" to MaterialTheme.colorScheme.error
        post.review.status == "not_submitted" -> "待同步" to MaterialTheme.colorScheme.outline
        else -> "审核中" to MaterialTheme.colorScheme.tertiary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(post.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                }
            }
            Text(
                post.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (post.review.status == "rejected" && post.review.reason.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    post.review.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                formatCommunityDate(post.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun CommunityPostMetadata(post: PublicCommunityPost) {
    val details = buildList {
        if (post.destination.isNotBlank()) add(post.destination)
        if (post.travelDays > 0) add("${post.travelDays} 天")
        if (post.stages.isNotEmpty()) add("${post.stages.size} 段行程")
    }
    if (details.isNotEmpty() || post.tags.isNotEmpty()) {
        Text(
            text = (details + post.tags.take(3).map { "#$it" }).joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CommunityInlineError(error: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun CommunityEmptyState(
    message: String,
    isError: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (isError) Icons.Default.ErrorOutline else Icons.Default.AutoStories,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "重新加载")
            }
        }
    }
}

@Composable
fun CommunityPostDetailScreen(
    postId: String,
    onNavigateBack: () -> Unit,
    viewModel: CommunityPostDetailViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val post = uiState.post
    LaunchedEffect(postId) { viewModel.load(postId) }
    if (uiState.showReportDialog) {
        CommunityReportDialog(
            category = uiState.reportCategory,
            reason = uiState.reportReason,
            isSubmitting = uiState.isReporting,
            error = uiState.reportMessage,
            onCategoryChange = viewModel::selectReportCategory,
            onReasonChange = viewModel::updateReportReason,
            onDismiss = viewModel::dismissReportDialog,
            onSubmit = { viewModel.submitReport(postId) }
        )
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("研学笔记", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::openReportDialog) {
                        Icon(Icons.Default.Flag, contentDescription = "举报")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            post != null -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                uiState.reportMessage?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                CommunityPostDetail(
                    post = post,
                    mediaBaseUrl = uiState.mediaBaseUrl,
                    modifier = Modifier.weight(1f)
                )
            }
            else -> CommunityEmptyState(
                message = uiState.error ?: "内容暂不可查看",
                isError = true,
                onRefresh = { viewModel.load(postId) },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun CommunityReportDialog(
    category: String,
    reason: String,
    isSubmitting: Boolean,
    error: String?,
    onCategoryChange: (String) -> Unit,
    onReasonChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    val categories = listOf(
        "privacy" to "隐私信息",
        "copyright" to "版权问题",
        "safety" to "安全风险",
        "spam" to "广告或垃圾内容",
        "other" to "其他"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("举报这篇笔记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                categories.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCategoryChange(value) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = category == value, onClick = { onCategoryChange(value) })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("补充说明（可选）") },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !isSubmitting
                )
                error?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = !isSubmitting) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("提交")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("取消") }
        }
    )
}

@Composable
private fun CommunityPostDetail(
    post: PublicCommunityPost,
    mediaBaseUrl: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(post.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        Text(post.authorLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(formatCommunityDate(post.publishedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        if (post.media.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(post.media, key = { it.id }) { media ->
                    CommunityThumbnail(
                        url = "$mediaBaseUrl${media.thumbnailUrl}",
                        contentDescription = "研学图片",
                        modifier = Modifier.width(240.dp).height(160.dp)
                    )
                }
            }
        }
        CommunityPostMetadata(post)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(post.content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CommunityThumbnail(
    url: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {}
    }
}

private fun formatCommunityDate(timestamp: Long): String =
    SimpleDateFormat("yyyy/MM/dd", Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))
