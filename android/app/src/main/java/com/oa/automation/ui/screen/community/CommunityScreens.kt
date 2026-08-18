package com.oa.automation.ui.screen.community

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.MyCommunityPost
import com.oa.automation.domain.model.CommunityComment
import com.oa.automation.domain.model.CommunityCollection
import com.oa.automation.domain.model.CommunityInteractionState
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
    onOpenCollection: (String) -> Unit,
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
                Tab(
                    selected = uiState.tab == CommunityTab.SAVED,
                    onClick = { viewModel.selectTab(CommunityTab.SAVED) },
                    text = { Text("收藏") },
                    icon = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) }
                )
            }
            if (!uiState.availability.writeEnabled) {
                CommunityReadOnlyNotice()
            }
            CommunityContent(
                state = uiState,
                onOpenPost = onOpenPost,
                onOpenCollection = onOpenCollection,
                onCollectionDestinationSelect = viewModel::selectCollectionDestination,
                onCollectionThemeSelect = viewModel::selectCollectionTheme,
                onCollectionSortSelect = viewModel::selectCollectionSort,
                onClearCollectionFilters = viewModel::clearCollectionFilters,
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
private fun CommunityReadOnlyNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = "社区暂时只读",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CommunityContent(
    state: CommunityUiState,
    onOpenPost: (String) -> Unit,
    onOpenCollection: (String) -> Unit,
    onCollectionDestinationSelect: (String) -> Unit,
    onCollectionThemeSelect: (String) -> Unit,
    onCollectionSortSelect: (String) -> Unit,
    onClearCollectionFilters: () -> Unit,
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
    val isSaved = state.tab == CommunityTab.SAVED
    val isEmpty = when {
        isDiscover -> state.publicPosts.isEmpty()
        isSaved -> state.savedPosts.isEmpty() && state.savedCollections.isEmpty()
        else -> state.myPosts.isEmpty()
    }
    if (isDiscover) {
        if (state.collections.isNotEmpty() || state.collectionDestinationFilter.isNotBlank() ||
            state.collectionThemeFilter.isNotBlank() || state.collectionFacets.destinations.isNotEmpty() ||
            state.collectionFacets.themes.isNotEmpty()
        ) {
            CommunityCollectionStrip(
                state = state,
                mediaBaseUrl = state.mediaBaseUrl,
                onOpenCollection = onOpenCollection,
                onDestinationSelect = onCollectionDestinationSelect,
                onThemeSelect = onCollectionThemeSelect,
                onSortSelect = onCollectionSortSelect,
                onClearFilters = onClearCollectionFilters
            )
        }
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
    if (isSaved && state.savedCollections.isNotEmpty()) {
        CommunitySavedCollectionStrip(
            collections = state.savedCollections,
            mediaBaseUrl = state.mediaBaseUrl,
            onOpenCollection = onOpenCollection
        )
    }
    when {
        state.isLoading && isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        isEmpty -> CommunityEmptyState(
            message = state.error ?: when {
                isDiscover -> "暂无已通过审核的研学记录"
                isSaved -> "暂无收藏内容"
                else -> "暂无已同步的发布记录"
            },
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
            } else if (isSaved) {
                items(state.savedPosts, key = { it.id }) { post ->
                    PublicPostCard(
                        post = post,
                        mediaBaseUrl = state.mediaBaseUrl,
                        onClick = { onOpenPost(post.id) }
                    )
                }
                if (state.nextSavedCursor != null) {
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
private fun CommunityCollectionStrip(
    state: CommunityUiState,
    mediaBaseUrl: String,
    onOpenCollection: (String) -> Unit,
    onDestinationSelect: (String) -> Unit,
    onThemeSelect: (String) -> Unit,
    onSortSelect: (String) -> Unit,
    onClearFilters: () -> Unit
) {
    var destinationExpanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    var themeExpanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    var sortExpanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    val hasFilters = state.collectionDestinationFilter.isNotBlank() ||
        state.collectionThemeFilter.isNotBlank()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("编辑专题", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "精选路线",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(
                    onClick = { destinationExpanded = true },
                    shape = RoundedCornerShape(7.dp),
                    color = if (state.collectionDestinationFilter.isBlank()) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Text(
                        "目的地：${state.collectionDestinationFilter.ifBlank { "全部" }}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
                DropdownMenu(
                    expanded = destinationExpanded,
                    onDismissRequest = { destinationExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("全部目的地") },
                        onClick = { destinationExpanded = false; onDestinationSelect("") }
                    )
                    state.collectionFacets.destinations.forEach { value ->
                        DropdownMenuItem(
                            text = { Text(value) },
                            onClick = { destinationExpanded = false; onDestinationSelect(value) }
                        )
                    }
                }
            }
            Box {
                Surface(
                    onClick = { themeExpanded = true },
                    shape = RoundedCornerShape(7.dp),
                    color = if (state.collectionThemeFilter.isBlank()) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Text(
                        "主题：${state.collectionThemeFilter.ifBlank { "全部" }}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
                DropdownMenu(
                    expanded = themeExpanded,
                    onDismissRequest = { themeExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("全部主题") },
                        onClick = { themeExpanded = false; onThemeSelect("") }
                    )
                    state.collectionFacets.themes.forEach { value ->
                        DropdownMenuItem(
                            text = { Text(value) },
                            onClick = { themeExpanded = false; onThemeSelect(value) }
                        )
                    }
                }
            }
            Box {
                Surface(
                    onClick = { sortExpanded = true },
                    shape = RoundedCornerShape(7.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Text(
                        "排序：${collectionSortLabel(state.collectionSort)}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false }
                ) {
                    listOf(
                        "curated" to "人工顺序",
                        "recent" to "最近发布",
                        "richness" to "内容丰富度"
                    ).forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { sortExpanded = false; onSortSelect(value) }
                        )
                    }
                }
            }
            if (hasFilters) {
                TextButton(onClick = onClearFilters) { Text("清除") }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.collections, key = { it.id }) { collection ->
                Card(
                    modifier = Modifier.width(214.dp).height(238.dp)
                        .clickable { onOpenCollection(collection.id) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(92.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (collection.coverThumbnailUrl.isNotBlank()) {
                            CommunityThumbnail(
                                url = "$mediaBaseUrl${collection.coverThumbnailUrl}",
                                contentDescription = "专题封面",
                                    modifier = Modifier.fillMaxSize()
                            )
                            } else {
                                Icon(
                                    Icons.Default.AutoStories,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                collection.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val meta = listOfNotNull(
                                collection.destination.takeIf { it.isNotBlank() },
                                collection.theme.takeIf { it.isNotBlank() },
                                "${collection.postCount} 篇"
                            ).joinToString(" · ")
                            Text(
                                meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (collection.description.isNotBlank()) {
                                Text(
                                    collection.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            collection.previewPosts.firstOrNull()?.let { preview ->
                                Text(
                                    "预览 · ${preview.title}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun collectionSortLabel(sort: String): String = when (sort) {
    "recent" -> "最近发布"
    "richness" -> "内容丰富度"
    else -> "人工顺序"
}

@Composable
private fun CommunitySavedCollectionStrip(
    collections: List<CommunityCollection>,
    mediaBaseUrl: String,
    onOpenCollection: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "收藏专题",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(collections, key = { "saved-${it.id}" }) { collection ->
                Card(
                    modifier = Modifier.width(230.dp).height(92.dp)
                        .clickable { onOpenCollection(collection.id) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.width(82.dp).height(92.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (collection.coverThumbnailUrl.isNotBlank()) {
                                CommunityThumbnail(
                                    url = "$mediaBaseUrl${collection.coverThumbnailUrl}",
                                    contentDescription = "专题封面",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    Icons.Default.AutoStories,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                collection.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${collection.postCount} 篇 · ${collection.bookmarkCount} 收藏",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
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
    val context = LocalContext.current
    var showActions by remember { mutableStateOf(false) }
    var previewMediaIndex by remember { mutableStateOf<Int?>(null) }
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
    if (uiState.showCommentReportDialog) {
        CommunityCommentReportDialog(
            category = uiState.reportCategory,
            reason = uiState.reportReason,
            isSubmitting = uiState.isReporting,
            error = uiState.reportMessage,
            onCategoryChange = viewModel::selectReportCategory,
            onReasonChange = viewModel::updateReportReason,
            onDismiss = viewModel::dismissCommentReportDialog,
            onSubmit = viewModel::submitCommentReport
        )
    }
    if (post != null && previewMediaIndex != null) {
        CommunityMediaPreviewDialog(
            post = post,
            mediaBaseUrl = uiState.mediaBaseUrl,
            initialIndex = previewMediaIndex ?: 0,
            onDismiss = { previewMediaIndex = null }
        )
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "见闻",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { post?.let { shareCommunityPost(context, it) } },
                        enabled = post != null
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "分享")
                    }
                    Box {
                        IconButton(onClick = { showActions = true }, enabled = post != null) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showActions,
                            onDismissRequest = { showActions = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("举报内容") },
                                leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) },
                                onClick = {
                                    showActions = false
                                    viewModel.openReportDialog()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (post != null) {
                CommunityDetailBottomBar(
                    interaction = uiState.interaction,
                    isInteracting = uiState.isInteracting,
                    isSubmitting = uiState.isSubmittingComment,
                    writeEnabled = uiState.availability.writeEnabled,
                    draft = uiState.commentDraft,
                    onDraftChange = viewModel::updateCommentDraft,
                    onSubmit = { viewModel.submitComment(postId) },
                    onToggleLike = { viewModel.toggleLike(postId) },
                    onToggleBookmark = { viewModel.toggleBookmark(postId) }
                )
            }
        }
    ) { padding ->
        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            post != null -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (!uiState.availability.writeEnabled) {
                    CommunityReadOnlyNotice()
                }
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
                    interaction = uiState.interaction,
                    comments = uiState.comments,
                    commentsNextCursor = uiState.commentsNextCursor,
                    isLoadingComments = uiState.isLoadingComments,
                    isSubmittingComment = uiState.isSubmittingComment,
                    mediaBaseUrl = uiState.mediaBaseUrl,
                    modifier = Modifier.weight(1f),
                    onOpenMedia = { previewMediaIndex = it },
                    onDeleteComment = viewModel::deleteComment,
                    onLoadMoreComments = { viewModel.loadMoreComments(postId) },
                    onReportComment = viewModel::openCommentReportDialog
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
fun CommunityCollectionDetailScreen(
    collectionId: String,
    onOpenPost: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: CommunityCollectionDetailViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(collectionId) { viewModel.load(collectionId) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.collection?.title ?: "专题", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::toggleBookmark,
                        enabled = !state.isInteracting && state.collection != null
                    ) {
                        Icon(
                            if (state.interaction?.bookmarked == true) {
                                Icons.Default.Bookmark
                            } else {
                                Icons.Default.BookmarkBorder
                            },
                            contentDescription = if (state.interaction?.bookmarked == true) {
                                "取消收藏专题"
                            } else {
                                "收藏专题"
                            }
                        )
                    }
                    IconButton(
                        onClick = { shareCommunityCollection(context, state) },
                        enabled = state.collection != null
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "分享专题")
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp) }
            state.error != null -> CommunityEmptyState(
                message = state.error ?: "专题暂不可查看",
                isError = true,
                onRefresh = { viewModel.load(collectionId) },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.collection?.let { collection ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                collection.description.ifBlank { "人工编排的研学笔记集合" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val meta = listOfNotNull(
                                collection.destination.takeIf { it.isNotBlank() },
                                collection.theme.takeIf { it.isNotBlank() },
                                "${collection.postCount} 篇笔记",
                                "${collection.bookmarkCount} 收藏"
                            ).joinToString("  ·  ")
                            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            state.actionMessage?.let { message ->
                                Text(
                                    message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (state.posts.isEmpty()) {
                    item { Text("专题中的笔记暂不可查看", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(state.posts, key = { it.id }) { post ->
                        if (post.curationNote.isNotBlank()) {
                            Text(
                                "收录说明：${post.curationNote}",
                                modifier = Modifier.padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PublicPostCard(
                            post = post,
                            mediaBaseUrl = state.mediaBaseUrl,
                            onClick = { onOpenPost(post.id) }
                        )
                    }
                }
            }
        }
    }
}

private fun shareCommunityCollection(
    context: Context,
    state: CommunityCollectionDetailUiState
) {
    val collection = state.collection ?: return
    val share = state.share
    val meta = listOfNotNull(
        collection.destination.takeIf(String::isNotBlank),
        collection.theme.takeIf(String::isNotBlank),
        "${collection.postCount} 篇笔记"
    ).joinToString(" · ")
    val text = buildString {
        appendLine(share?.title ?: collection.title)
        if (meta.isNotBlank()) appendLine(meta)
        (share?.description ?: collection.description).takeIf(String::isNotBlank)?.let {
            appendLine(it)
        }
        if (state.shareUrl.isNotBlank()) append(state.shareUrl)
    }.trim()
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, collection.title)
                putExtra(Intent.EXTRA_TEXT, text)
            },
            "分享专题"
        )
    )
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
private fun CommunityCommentReportDialog(
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
        title = { Text("举报这条评论") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                categories.forEach { (value, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onCategoryChange(value) },
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
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
    interaction: CommunityInteractionState?,
    comments: List<CommunityComment>,
    commentsNextCursor: String?,
    isLoadingComments: Boolean,
    isSubmittingComment: Boolean,
    mediaBaseUrl: String,
    modifier: Modifier = Modifier,
    onOpenMedia: (Int) -> Unit,
    onDeleteComment: (String) -> Unit,
    onLoadMoreComments: () -> Unit,
    onReportComment: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (post.media.isNotEmpty()) {
            CommunityMediaPager(
                post = post,
                mediaBaseUrl = mediaBaseUrl,
                onOpenMedia = onOpenMedia
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = post.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = post.authorLabel.trim().take(1).ifBlank { "悟" },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            post.authorLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = "已审核",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        "发布于 ${formatCommunityDate(post.publishedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        CommunityPostMetadata(post)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SelectionContainer {
            CommunityRichText(post.content)
        }
        if (post.stages.isNotEmpty()) {
            CommunityStageTimeline(post.stages)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "现场讨论",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${interaction?.commentCount ?: post.commentCount} 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        CommunityComments(
            comments = comments,
            isLoading = isLoadingComments,
            isSubmitting = isSubmittingComment,
            nextCursor = commentsNextCursor,
            onLoadMore = onLoadMoreComments,
            onDelete = onDeleteComment,
            onReport = onReportComment
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun CommunityMediaPager(
    post: PublicCommunityPost,
    mediaBaseUrl: String,
    onOpenMedia: (Int) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { post.media.size })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.02f)
                .clip(RoundedCornerShape(10.dp))
        ) { page ->
            val media = post.media[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onOpenMedia(page) }
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CommunityThumbnail(
                    url = "$mediaBaseUrl${media.contentUrl.ifBlank { media.thumbnailUrl }}",
                    contentDescription = "现场影像 ${page + 1}",
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.58f)
                ) {
                    Text(
                        "${page + 1}/${post.media.size}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
        if (post.media.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(post.media.size) { index ->
                    Surface(
                        modifier = Modifier.padding(horizontal = 3.dp).size(
                            if (index == pagerState.currentPage) 18.dp else 6.dp,
                            6.dp
                        ),
                        shape = RoundedCornerShape(4.dp),
                        color = if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun CommunityRichText(content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseCommunityRichText(content).forEach { block ->
            when (block.kind) {
                CommunityRichTextKind.SPACER -> Spacer(Modifier.height(4.dp))
                CommunityRichTextKind.HEADING_THREE -> Text(
                    block.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                CommunityRichTextKind.HEADING_TWO -> Text(
                    block.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                CommunityRichTextKind.HEADING_ONE -> Text(
                    block.text,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                CommunityRichTextKind.BULLET -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(
                            "•",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            block.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                CommunityRichTextKind.PARAGRAPH -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                )
            }
        }
    }
}

internal enum class CommunityRichTextKind {
    HEADING_ONE,
    HEADING_TWO,
    HEADING_THREE,
    BULLET,
    PARAGRAPH,
    SPACER
}

internal data class CommunityRichTextBlock(
    val kind: CommunityRichTextKind,
    val text: String = ""
)

internal fun parseCommunityRichText(content: String): List<CommunityRichTextBlock> =
    content.lineSequence().map { rawLine ->
        val line = rawLine.trimEnd()
        when {
            line.isBlank() -> CommunityRichTextBlock(CommunityRichTextKind.SPACER)
            line.startsWith("### ") -> CommunityRichTextBlock(
                CommunityRichTextKind.HEADING_THREE,
                line.removePrefix("### ").trim()
            )
            line.startsWith("## ") -> CommunityRichTextBlock(
                CommunityRichTextKind.HEADING_TWO,
                line.removePrefix("## ").trim()
            )
            line.startsWith("# ") -> CommunityRichTextBlock(
                CommunityRichTextKind.HEADING_ONE,
                line.removePrefix("# ").trim()
            )
            line.startsWith("- ") || line.startsWith("* ") -> CommunityRichTextBlock(
                CommunityRichTextKind.BULLET,
                line.drop(2).trim()
            )
            else -> CommunityRichTextBlock(CommunityRichTextKind.PARAGRAPH, line)
        }
    }.toList()

@Composable
private fun CommunityStageTimeline(stages: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "行程分段",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        stages.forEachIndexed { index, stage ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    stage,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun CommunityDetailBottomBar(
    interaction: CommunityInteractionState?,
    isInteracting: Boolean,
    isSubmitting: Boolean,
    writeEnabled: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleBookmark: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    enabled = writeEnabled && !isSubmitting,
                    singleLine = true,
                    placeholder = { Text("写下你的观察") },
                    trailingIcon = {
                        IconButton(
                            onClick = onSubmit,
                            enabled = writeEnabled && draft.isNotBlank() && !isSubmitting
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发表评论")
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (writeEnabled) "记录一条有价值的现场观察" else "当前为只读状态",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleLike, enabled = writeEnabled && !isInteracting) {
                    Icon(
                        if (interaction?.liked == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (interaction?.liked == true) "取消点赞" else "点赞",
                        tint = if (interaction?.liked == true) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    (interaction?.likeCount ?: 0).toString(),
                    style = MaterialTheme.typography.labelSmall
                )
                IconButton(onClick = onToggleBookmark, enabled = writeEnabled && !isInteracting) {
                    Icon(
                        if (interaction?.bookmarked == true) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = if (interaction?.bookmarked == true) "取消收藏" else "收藏",
                        tint = if (interaction?.bookmarked == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CommunityComments(
    comments: List<CommunityComment>,
    isLoading: Boolean,
    isSubmitting: Boolean,
    nextCursor: String?,
    onLoadMore: () -> Unit,
    onDelete: (String) -> Unit,
    onReport: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (comments.isEmpty() && !isLoading) {
            Text(
                "还没有评论，先留下第一条观察",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        comments.forEach { comment ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            comment.authorLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatCommunityDate(comment.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                if (comment.canDelete) onDelete(comment.id) else onReport(comment.id)
                            },
                            enabled = !isSubmitting,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                if (comment.canDelete) Icons.Default.DeleteOutline else Icons.Default.Flag,
                                contentDescription = if (comment.canDelete) "删除评论" else "举报评论",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    Text(
                        comment.content,
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        if (nextCursor != null) {
            TextButton(onClick = onLoadMore, enabled = !isLoading && !isSubmitting) {
                Text("加载更多评论")
            }
        }
    }
}

private fun shareCommunityPost(context: Context, post: PublicCommunityPost) {
    val meta = listOfNotNull(
        post.destination.takeIf(String::isNotBlank),
        post.travelDate.takeIf(String::isNotBlank),
        post.travelDays.takeIf { it > 0 }?.let { "$it 天" }
    ).joinToString(" · ")
    val text = buildString {
        appendLine(post.title)
        if (meta.isNotBlank()) appendLine(meta)
        appendLine()
        append(post.content.trim().take(800))
        if (post.content.length > 800) append("...")
    }.trim()
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, post.title)
                putExtra(Intent.EXTRA_TEXT, text)
            },
            "分享见闻"
        )
    )
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun CommunityMediaPreviewDialog(
    post: PublicCommunityPost,
    mediaBaseUrl: String,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (post.media.size - 1).coerceAtLeast(0)),
        pageCount = { post.media.size }
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                CommunityThumbnail(
                    url = "$mediaBaseUrl${post.media[page].contentUrl.ifBlank { post.media[page].thumbnailUrl }}",
                    contentDescription = "现场影像 ${page + 1}",
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.9f)
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 12.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭预览", tint = Color.White)
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.58f)
            ) {
                Text(
                    "${pagerState.currentPage + 1}/${post.media.size}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }
        }
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
