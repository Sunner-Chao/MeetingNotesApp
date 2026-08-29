package com.oa.automation.ui.screen.community

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.CommunityCollection
import com.oa.automation.domain.model.MyCommunityPost
import com.oa.automation.domain.model.PublicCommunityPost
import com.oa.automation.ui.component.ZhiWuScreenBackground
import com.oa.automation.ui.formatBeijingTime
import com.oa.automation.ui.theme.LocalAppIsDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyCommunityScreen(
    onOpenPost: (String) -> Unit,
    onOpenCollection: (String) -> Unit,
    viewModel: CommunityViewModel = koinViewModel(),
    publishingViewModel: CommunityPublishingViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val publishingState by publishingViewModel.uiState.collectAsStateWithLifecycle()
    var showFilters by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var showActivities by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    val publishingSyncKey = publishingState.posts.joinToString(separator = "|") { item ->
        "${item.post.id}:${item.sync?.status}:${item.sync?.remotePostId}"
    }
    LaunchedEffect(state.tab, publishingSyncKey) {
        if (state.tab == CommunityTab.MINE &&
            publishingState.posts.any { it.sync?.status == com.oa.automation.domain.model.CommunitySyncStatus.PUBLISHED }
        ) {
            viewModel.refresh()
        }
    }

    ZhiWuScreenBackground {
        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                StudyCommunityHeader(
                    onRefresh = viewModel::refresh,
                    onOpenActivities = { showActivities = true }
                )
                CommunitySegmentedTabs(
                    selected = state.tab,
                    onSelected = viewModel::selectTab
                )
                StudyCommunityFeed(
                    modifier = Modifier.weight(1f),
                    state = state,
                    publishingState = publishingState,
                    onOpenPublishedPost = publishingViewModel::openReview,
                    onRetryPublishedPost = publishingViewModel::retry,
                    onOpenPost = onOpenPost,
                    onOpenCollection = onOpenCollection,
                    onQueryChange = viewModel::updateSearchQuery,
                    onTopicSelect = viewModel::selectQuickTopic,
                    onSearch = viewModel::search,
                    onOpenFilters = { showFilters = true },
                    onOpenActivities = { showActivities = true },
                    onRefresh = viewModel::refresh,
                    onLoadMore = viewModel::loadMore
                )
            }
        }
    }

    if (showFilters) {
        CommunityFilterSheet(
            state = state,
            onDismiss = { showFilters = false },
            onDestinationSelect = viewModel::selectDestination,
            onTagSelect = viewModel::selectTag,
            onPoiSelect = viewModel::selectPoi,
            onDaysSelect = viewModel::selectDays,
            onToggleHasMedia = viewModel::toggleHasMedia,
            onClear = viewModel::clearFilters,
            onApply = {
                showFilters = false
                viewModel.search()
            }
        )
    }

    if (showActivities) {
        CommunityActivitySheet(onDismiss = { showActivities = false })
    }

    publishingState.selectedPost?.let { post ->
        CommunityPublishingReviewDialog(
            post = post,
            media = publishingState.selectedMedia,
            isSaving = publishingState.isSaving,
            onSaveReview = publishingViewModel::saveReview,
            onUpdateMetadata = publishingViewModel::updateMetadata,
            onSetMediaIncluded = publishingViewModel::setMediaIncluded,
            onMarkReady = publishingViewModel::markReady,
            onPublish = publishingViewModel::publish,
            onWithdraw = publishingViewModel::withdraw,
            onDismiss = publishingViewModel::dismissReview
        )
    }
}

@Composable
private fun StudyCommunityHeader(
    onRefresh: () -> Unit,
    onOpenActivities: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "研学社区",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "智见同行",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Surface(
            onClick = onOpenActivities,
            modifier = Modifier.height(40.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "活动",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新")
        }
    }
}

@Composable
private fun CommunitySegmentedTabs(
    selected: CommunityTab,
    onSelected: (CommunityTab) -> Unit
) {
    val tabs = listOf(
        Triple(CommunityTab.DISCOVER, "发现", Icons.Default.Explore),
        Triple(CommunityTab.MINE, "发布", Icons.Default.AutoStories),
        Triple(CommunityTab.SAVED, "收藏", Icons.Default.BookmarkBorder)
    )
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalAppIsDarkTheme.current) 0.72f else 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            tabs.forEach { (tab, label, icon) ->
                val isSelected = selected == tab
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else Color.Transparent
                        )
                        .clickable { onSelected(tab) }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun StudyCommunityFeed(
    modifier: Modifier,
    state: CommunityUiState,
    publishingState: CommunityPublishingUiState,
    onOpenPublishedPost: (String) -> Unit,
    onRetryPublishedPost: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenCollection: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onTopicSelect: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenActivities: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit
) {
    val posts = when (state.tab) {
        CommunityTab.DISCOVER -> state.publicPosts
        CommunityTab.SAVED -> state.savedPosts
        CommunityTab.MINE -> emptyList()
    }
    val isEmpty = when (state.tab) {
        CommunityTab.DISCOVER -> state.publicPosts.isEmpty()
        CommunityTab.SAVED -> state.savedPosts.isEmpty() && state.savedCollections.isEmpty()
        CommunityTab.MINE -> state.myPosts.isEmpty() && publishingState.posts.isEmpty()
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = 156.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 18.dp),
        verticalItemSpacing = 10.dp,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.tab == CommunityTab.DISCOVER) {
            item(span = StaggeredGridItemSpan.FullLine) {
                CommunitySearchSurface(
                    query = state.searchQuery,
                    activeFilterCount = activeCommunityFilterCount(state),
                    onQueryChange = onQueryChange,
                    onSearch = onSearch,
                    onOpenFilters = onOpenFilters
                )
            }
            if (state.publicPosts.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    CommunityFeaturedCarousel(
                        posts = state.publicPosts,
                        mediaBaseUrl = state.mediaBaseUrl,
                        onOpenPost = onOpenPost
                    )
                }
            }
            if (state.isShowingSampleContent) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    CommunityPublicReferenceStrip(onOpenPost = onOpenPost)
                }
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                CommunityActivityEntry(onOpenActivities = onOpenActivities)
            }
            if (state.facets.tags.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    CommunityTopicStrip(
                        topics = communityQuickTopics(state),
                        selected = state.tagFilter,
                        onSelected = onTopicSelect
                    )
                }
            }
            if (!state.availability.writeEnabled) {
                item(span = StaggeredGridItemSpan.FullLine) { CommunityReadOnlyGlassNotice() }
            }
            if (state.collections.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    CommunityRouteStrip(
                        collections = state.collections,
                        mediaBaseUrl = state.mediaBaseUrl,
                        onOpenCollection = onOpenCollection
                    )
                }
            }
            if (!isEmpty) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    CommunityFeedSectionHeader(
                        resultCount = state.publicPosts.size,
                        hasActiveFilters = activeCommunityFilterCount(state) > 0,
                        isShowingSampleContent = state.isShowingSampleContent
                    )
                }
            }
        }
        if (state.tab == CommunityTab.MINE) {
            item(span = StaggeredGridItemSpan.FullLine) {
                CommunityPublishingWorkbench(
                    state = publishingState,
                    remotePosts = state.myPosts,
                    onOpen = onOpenPublishedPost,
                    onRetry = onRetryPublishedPost
                )
            }
            if (visibleRemoteCommunityPosts(state.myPosts, publishingState.posts).isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Text("服务端审核记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (state.tab == CommunityTab.SAVED && state.savedCollections.isNotEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                CommunityRouteStrip(
                    title = "收藏路线",
                    collections = state.savedCollections,
                    mediaBaseUrl = state.mediaBaseUrl,
                    onOpenCollection = onOpenCollection
                )
            }
        }

        when {
            state.isLoading && isEmpty -> item(span = StaggeredGridItemSpan.FullLine) { CommunityLoadingState() }
            isEmpty -> item(span = StaggeredGridItemSpan.FullLine) {
                StudyCommunityEmptyState(
                    tab = state.tab,
                    error = state.error,
                    onRefresh = onRefresh
                )
            }
            else -> {
                state.error?.let { error ->
                    item(span = StaggeredGridItemSpan.FullLine) { StudyCommunityInlineError(error, onRefresh) }
                }
                if (state.tab == CommunityTab.MINE && state.myPosts.isNotEmpty()) {
                    staggeredItems(
                        items = visibleRemoteCommunityPosts(state.myPosts, publishingState.posts),
                        key = { "mine-${it.id}" },
                        span = { StaggeredGridItemSpan.FullLine }
                    ) { post ->
                        StudyMyPostCard(post)
                    }
                } else {
                    staggeredItems(posts, key = { "post-${it.id}" }) { post ->
                        StudyPostCard(
                            post = post,
                            mediaBaseUrl = state.mediaBaseUrl,
                            onClick = { onOpenPost(post.id) }
                        )
                    }
                    val hasMore = when (state.tab) {
                        CommunityTab.DISCOVER -> state.nextPublicCursor != null
                        CommunityTab.SAVED -> state.nextSavedCursor != null
                        CommunityTab.MINE -> false
                    }
                    if (hasMore) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            TextButton(
                                onClick = onLoadMore,
                                enabled = !state.isLoadingMore,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (state.isLoadingMore) "加载中" else "加载更多")
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun visibleRemoteCommunityPosts(
    remotePosts: List<MyCommunityPost>,
    localPosts: List<CommunityPublishingItem>
): List<MyCommunityPost> {
    val representedRemoteIds = localPosts.mapNotNull { it.sync?.remotePostId }.toSet()
    return remotePosts.filterNot { it.id in representedRemoteIds }
}

private fun openExternalUri(
    context: Context,
    uriHandler: UriHandler,
    uri: String,
    targetLabel: String
) {
    runCatching {
        require(uri.startsWith("https://"))
        uriHandler.openUri(uri)
    }.onFailure {
        Toast.makeText(
            context.applicationContext,
            "无法打开$targetLabel，请稍后重试",
            Toast.LENGTH_SHORT
        ).show()
    }
}

@Composable
private fun CommunityPublicReferenceStrip(onOpenPost: (String) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "资料参考",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "国内资料",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyRow(
            contentPadding = PaddingValues(end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(MockStudyCommunityData.publicReferences, key = CommunityPublicReference::id) { reference ->
                CommunityPublicReferenceCard(
                    reference = reference,
                    onOpenSource = {
                        openExternalUri(context, uriHandler, reference.sourceUrl, "资料页面")
                    },
                    onOpenPost = { onOpenPost(reference.relatedPostId) }
                )
            }
        }
    }
}

@Composable
private fun CommunityPublicReferenceCard(
    reference: CommunityPublicReference,
    onOpenSource: () -> Unit,
    onOpenPost: () -> Unit
) {
    Surface(
        modifier = Modifier.width(286.dp).height(178.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalAppIsDarkTheme.current) 0.78f else 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                ) {
                    Text(
                        reference.destination,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(7.dp))
            Text(
                reference.referenceLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
            Text(
                reference.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                reference.sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                reference.summary,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenSource, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("查看国内资料")
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenPost, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("查看笔记")
                    Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
private fun CommunityActivityEntry(onOpenActivities: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "近期活动预告",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onOpenActivities,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text("查看全部")
                Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(17.dp))
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.52f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "近期活动信息整理 · 核对日期 ${MockStudyCommunityData.ACTIVITY_SNAPSHOT_DATE}；报名与时间以原页面为准",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                MockStudyCommunityData.activityNotices.take(3),
                key = CommunityActivityNotice::id
            ) { notice ->
                CommunityActivityNoticeCard(
                    notice = notice,
                    modifier = Modifier.width(286.dp),
                    onOpenSource = {
                        openExternalUri(context, uriHandler, notice.sourceUrl, "活动页面")
                    }
                )
            }
        }
    }
}

@Composable
private fun CommunityActivityNoticeCard(
    notice: CommunityActivityNotice,
    modifier: Modifier = Modifier,
    onOpenSource: () -> Unit
) {
    Surface(
        onClick = onOpenSource,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(
            alpha = if (LocalAppIsDarkTheme.current) 0.78f else 0.95f
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f)
                ) {
                    Text(
                        "活动",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    notice.locationLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                notice.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    notice.dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Text(
                    notice.priceLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                notice.summary,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notice.sourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "打开活动原页",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityActivitySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "活动预告",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "以下为近期活动信息整理，核对日期：${MockStudyCommunityData.ACTIVITY_SNAPSHOT_DATE}（北京时间）。智悟本不主办、不代报名，详情和状态请以原页面为准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(MockStudyCommunityData.activityNotices, key = CommunityActivityNotice::id) { notice ->
                CommunityActivityNoticeCard(
                    notice = notice,
                    modifier = Modifier.fillMaxWidth(),
                    onOpenSource = {
                        openExternalUri(context, uriHandler, notice.sourceUrl, "活动页面")
                    }
                )
            }
        }
    }
}

@Composable
private fun CommunityTopicStrip(
    topics: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 2.dp)
    ) {
        items(topics, key = { "topic-$it" }) { topic ->
            val isSelected = topic == selected
            Surface(
                onClick = { onSelected(topic) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                border = if (isSelected) null else BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = topic.ifBlank { "全部" },
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

private data class FeaturedCommunityPage(
    val post: PublicCommunityPost,
    val imageUrl: String
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CommunityFeaturedCarousel(
    posts: List<PublicCommunityPost>,
    mediaBaseUrl: String,
    onOpenPost: (String) -> Unit
) {
    val pages = remember(posts, mediaBaseUrl) {
        posts.take(4).flatMap { post ->
            post.media.take(2).map { media ->
                FeaturedCommunityPage(
                    post = post,
                    imageUrl = resolveCommunityMediaUrl(mediaBaseUrl, media.thumbnailUrl)
                )
            }
        }.take(7)
    }
    if (pages.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { pages.size })
    LaunchedEffect(pages.size) {
        while (pages.size > 1) {
            delay(4_200)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % pages.size)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (posts.all { MockStudyCommunityData.isSampleId(it.id) }) "精选内容" else "本周精选",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "自动浏览",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(214.dp)
        ) { pageIndex ->
            val page = pages[pageIndex]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenPost(page.post.id) }
            ) {
                StudyCommunityImage(
                    url = page.imageUrl,
                    contentDescription = page.post.title,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    color = Color.Black.copy(alpha = 0.62f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            page.post.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOfNotNull(
                                page.post.destination.takeIf(String::isNotBlank),
                                page.post.stages.firstOrNull()
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.56f)
                ) {
                    Text(
                        "${pageIndex + 1}/${pages.size}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(pages.size) { index ->
                Surface(
                    modifier = Modifier.padding(horizontal = 3.dp).size(
                        width = if (index == pagerState.currentPage) 18.dp else 6.dp,
                        height = 6.dp
                    ),
                    shape = RoundedCornerShape(4.dp),
                    color = if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                ) {}
            }
        }
    }
}

internal fun communityQuickTopics(state: CommunityUiState): List<String> = buildList {
    add("")
    if (state.tagFilter.isNotBlank()) add(state.tagFilter)
    addAll(state.facets.tags)
}.map(String::trim).distinct().take(8)

@Composable
private fun CommunityFeedSectionHeader(
    resultCount: Int,
    hasActiveFilters: Boolean,
    isShowingSampleContent: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when {
                hasActiveFilters -> "筛选结果"
                isShowingSampleContent -> "同行见闻"
                else -> "同行见闻"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "已加载 $resultCount 篇",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CommunitySearchSurface(
    query: String,
    activeFilterCount: Int,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenFilters: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalAppIsDarkTheme.current) 0.7f else 0.84f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isBlank()) {
                                Text(
                                    "搜索地点、主题或见闻",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    }
                )
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "清除搜索", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Box {
            Surface(
                onClick = onOpenFilters,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (activeFilterCount > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.FilterAlt,
                        contentDescription = "筛选",
                        tint = if (activeFilterCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (activeFilterCount > 0) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            activeFilterCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                    }
                }
            }
        }
    }
}

private fun activeCommunityFilterCount(state: CommunityUiState): Int = listOf(
    state.destinationFilter.isNotBlank(),
    state.tagFilter.isNotBlank(),
    state.poiFilter.isNotBlank(),
    state.minDaysFilter > 0 || state.maxDaysFilter > 0,
    state.hasMediaOnly
).count { it }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityFilterSheet(
    state: CommunityUiState,
    onDismiss: () -> Unit,
    onDestinationSelect: (String) -> Unit,
    onTagSelect: (String) -> Unit,
    onPoiSelect: (String) -> Unit,
    onDaysSelect: (Int, Int) -> Unit,
    onToggleHasMedia: () -> Unit,
    onClear: () -> Unit,
    onApply: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("筛选", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (activeCommunityFilterCount(state) > 0) {
                    TextButton(onClick = onClear) { Text("清除") }
                }
            }
            CommunityFilterChoices(
                title = "目的地",
                options = listOf("") + state.facets.destinations,
                selected = state.destinationFilter,
                label = { it.ifBlank { "全部" } },
                onSelected = onDestinationSelect
            )
            CommunityFilterChoices(
                title = "主题",
                options = listOf("") + state.facets.tags,
                selected = state.tagFilter,
                label = { it.ifBlank { "全部" } },
                onSelected = onTagSelect
            )
            CommunityFilterChoices(
                title = "地点",
                options = listOf("") + state.facets.pois,
                selected = state.poiFilter,
                label = { it.ifBlank { "全部" } },
                onSelected = onPoiSelect
            )
            CommunityFilterChoices(
                title = "行程",
                options = listOf("0:0", "1:3", "4:7", "8:31"),
                selected = "${state.minDaysFilter}:${state.maxDaysFilter}",
                label = {
                    when (it) {
                        "1:3" -> "1-3 天"
                        "4:7" -> "4-7 天"
                        "8:31" -> "8 天以上"
                        else -> "全部"
                    }
                },
                onSelected = { value ->
                    val (min, max) = value.split(':').map(String::toInt)
                    onDaysSelect(min, max)
                }
            )
            Surface(
                onClick = onToggleHasMedia,
                shape = RoundedCornerShape(8.dp),
                color = if (state.hasMediaOnly) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoStories, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("仅看有现场影像的记录", modifier = Modifier.weight(1f))
                    Text(
                        if (state.hasMediaOnly) "已开启" else "不限",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Button(
                onClick = onApply,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("查看结果")
            }
        }
    }
}

@Composable
private fun <T> CommunityFilterChoices(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options.distinct()) { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    label = { Text(label(option), maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

@Composable
private fun CommunityRouteStrip(
    collections: List<CommunityCollection>,
    mediaBaseUrl: String,
    onOpenCollection: (String) -> Unit,
    title: String = "精选路线"
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        LazyRow(
            contentPadding = PaddingValues(end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(collections, key = { "route-${it.id}" }) { collection ->
                Surface(
                    onClick = { onOpenCollection(collection.id) },
                    modifier = Modifier.width(224.dp).height(112.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                ) {
                    Row {
                        Box(
                            modifier = Modifier.width(88.dp).fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (collection.coverThumbnailUrl.isNotBlank()) {
                                StudyCommunityImage(
                                    url = resolveCommunityMediaUrl(mediaBaseUrl, collection.coverThumbnailUrl),
                                    contentDescription = "${collection.title}封面",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    Icons.Default.Explore,
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                collection.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                listOfNotNull(
                                    collection.destination.takeIf(String::isNotBlank),
                                    collection.theme.takeIf(String::isNotBlank),
                                    "${collection.postCount} 篇"
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyPostCard(
    post: PublicCommunityPost,
    mediaBaseUrl: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalAppIsDarkTheme.current) 0.74f else 0.88f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
    ) {
        Column {
            post.media.firstOrNull()?.let { media ->
                StudyCommunityImage(
                    url = resolveCommunityMediaUrl(mediaBaseUrl, media.thumbnailUrl),
                    contentDescription = "${post.title}现场影像",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (post.media.size > 1) 0.96f else 1.08f)
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    post.authorLabel.trim().take(1).ifBlank { "悟" },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            post.authorLabel,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = "已审核",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            formatStudyCommunityDate(post.publishedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                }
                Text(
                    post.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                StudyPostMetadata(post)
                Text(
                    communityPostExcerpt(post),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (post.media.isEmpty()) 4 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FavoriteBorder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(post.likeCount.toString(), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(post.commentCount.toString(), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    if (post.media.size > 1) {
                        Text(
                            "${post.media.size} 张",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "查看详情",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StudyPostMetadata(post: PublicCommunityPost) {
    val metadata = buildList {
        post.destination.takeIf(String::isNotBlank)?.let(::add)
        post.travelDays.takeIf { it > 0 }?.let { add("$it 天") }
        post.stages.takeIf { it.isNotEmpty() }?.let { add("${post.stages.size} 段") }
        post.tags.take(2).forEach { add("#$it") }
    }
    if (metadata.isNotEmpty()) {
        Text(
            metadata.joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StudyMyPostCard(post: MyCommunityPost) {
    val (status, tint) = when {
        post.status == "withdrawn" -> "已撤回" to MaterialTheme.colorScheme.onSurfaceVariant
        post.status == "private_draft" -> "草稿" to MaterialTheme.colorScheme.secondary
        post.review.status == "approved" -> "已发布" to MaterialTheme.colorScheme.primary
        post.review.status == "rejected" -> "需调整" to MaterialTheme.colorScheme.error
        post.review.status == "not_submitted" -> "待同步" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "审核中" to MaterialTheme.colorScheme.tertiary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    post.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(shape = RoundedCornerShape(6.dp), color = tint.copy(alpha = 0.12f)) {
                    Text(
                        status,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = tint
                    )
                }
            }
            Text(
                post.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (post.review.status == "rejected" && post.review.reason.isNotBlank()) {
                Text(
                    post.review.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                formatStudyCommunityDate(post.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CommunityLoadingState() {
    Box(Modifier.fillMaxWidth().height(330.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
    }
}

@Composable
private fun StudyCommunityEmptyState(
    tab: CommunityTab,
    error: String?,
    onRefresh: () -> Unit
) {
    Box(Modifier.fillMaxWidth().height(340.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 28.dp)
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
                Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        if (error != null) Icons.Default.Refresh
                        else if (tab == CommunityTab.SAVED) Icons.Default.BookmarkBorder
                        else if (tab == CommunityTab.MINE) Icons.Default.Person
                        else Icons.Default.Explore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                when {
                    error != null -> "暂时无法加载"
                    tab == CommunityTab.SAVED -> "还没有收藏"
                    tab == CommunityTab.MINE -> "还没有发布记录"
                    else -> "还没有公开记录"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                error ?: when (tab) {
                    CommunityTab.DISCOVER -> "新的研学记录会在审核后出现"
                    CommunityTab.MINE -> "完成研学记录后可同步发布"
                    CommunityTab.SAVED -> "收藏的路线和见闻会保存在这里"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (error != null) {
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新加载")
                }
            }
        }
    }
}

@Composable
private fun StudyCommunityInlineError(error: String, onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                error,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "重试", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CommunityReadOnlyGlassNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    ) {
        Text(
            "当前可浏览，发布服务暂未开放",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun StudyCommunityImage(
    url: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = CommunityImageCache.get(url), url) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { decodeCommunityBitmap(context, url) }
                    .getOrNull()
                    ?.also { CommunityImageCache.put(url, it) }
            }
        }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Default.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
            )
        }
    }
}

private object CommunityImageCache {
    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    @Synchronized
    fun get(url: String): Bitmap? = cache.get(url)

    @Synchronized
    fun put(url: String, bitmap: Bitmap) {
        cache.put(url, bitmap)
    }

    private fun cacheSizeKb(): Int =
        ((Runtime.getRuntime().maxMemory() / 1024L) / 8L).toInt().coerceAtLeast(4 * 1024)
}

private fun formatStudyCommunityDate(timestamp: Long): String =
    formatBeijingTime(timestamp, "MM-dd")
