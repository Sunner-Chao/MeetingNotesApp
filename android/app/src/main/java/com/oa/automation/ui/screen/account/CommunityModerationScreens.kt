package com.oa.automation.ui.screen.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.CommunityCommentReportQueueItem
import com.oa.automation.domain.model.CommunityCollection
import com.oa.automation.domain.model.CommunityCollectionOperationsSummary
import com.oa.automation.domain.model.CommunityModerationItem
import com.oa.automation.domain.model.CommunityOperationsSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

@Composable
fun CommunityModerationScreen(
    onNavigateBack: () -> Unit,
    viewModel: CommunityModerationViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    state.rejectPostId?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissReject,
            title = { Text("拒绝发布") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请留下作者可以执行的调整说明。")
                    OutlinedTextField(
                        value = state.rejectReason,
                        onValueChange = viewModel::updateRejectReason,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("审核说明") },
                        minLines = 3,
                        maxLines = 5,
                        enabled = state.processingPostId == null
                    )
                    state.error?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::reject,
                    enabled = state.processingPostId == null && state.rejectReason.isNotBlank()
                ) { Text("拒绝并下线") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissReject, enabled = state.processingPostId == null) {
                    Text("取消")
                }
            }
        )
    }

    state.deleteReportId?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteComment,
            title = { Text("删除这条评论？") },
            text = { Text("评论会从公开笔记中下线，并保留处置记录。") },
            confirmButton = {
                Button(
                    onClick = viewModel::deleteReportedComment,
                    enabled = state.processingReportId == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissDeleteComment,
                    enabled = state.processingReportId == null
                ) { Text("取消") }
            }
        )
    }

    if (state.showCreateCollection) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCreateCollection,
            title = { Text("新建专题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.collectionTitle,
                        onValueChange = viewModel::updateCollectionTitle,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("专题标题") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.collectionDescription,
                        onValueChange = viewModel::updateCollectionDescription,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("专题说明") },
                        minLines = 2,
                        maxLines = 4
                    )
                    OutlinedTextField(
                        value = state.collectionDestination,
                        onValueChange = viewModel::updateCollectionDestination,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("目的地") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.collectionTheme,
                        onValueChange = viewModel::updateCollectionTheme,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("主题") },
                        singleLine = true
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::createCollection,
                    enabled = state.collectionTitle.isNotBlank() && state.processingCollectionId == null
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCreateCollection) { Text("取消") }
            }
        )
    }

    state.curatePostId?.let {
        var collectionMenuExpanded by remember { mutableStateOf(false) }
        val selected = state.collections.firstOrNull { it.id == state.curateCollectionId }
        AlertDialog(
            onDismissRequest = viewModel::dismissCurate,
            title = { Text("收录专题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(
                            onClick = { collectionMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                selected?.title ?: "选择专题",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = collectionMenuExpanded,
                            onDismissRequest = { collectionMenuExpanded = false }
                        ) {
                            state.collections.forEach { collection ->
                                DropdownMenuItem(
                                    text = { Text(collection.title) },
                                    onClick = {
                                        viewModel.selectCurateCollection(collection.id)
                                        collectionMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.curationNote,
                        onValueChange = viewModel::updateCurationNote,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("收录说明") },
                        minLines = 2,
                        maxLines = 4
                    )
                    OutlinedTextField(
                        value = state.curationPosition,
                        onValueChange = viewModel::updateCurationPosition,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("展示顺序") },
                        singleLine = true
                    )
                    if (state.collections.isEmpty()) {
                        Text("请先在专题页创建专题", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::curatePost,
                    enabled = state.curateCollectionId.isNotBlank() &&
                        state.processingCollectionId == null
                ) { Text("收录") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissCurate) { Text("取消") } }
        )
    }

    if (state.showBatchCurate) {
        var collectionMenuExpanded by remember { mutableStateOf(false) }
        val selected = state.collections.firstOrNull { it.id == state.curateCollectionId }
        AlertDialog(
            onDismissRequest = viewModel::dismissBatchCurate,
            title = { Text("批量收录 ${state.selectedPostIds.size} 篇") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(
                            onClick = { collectionMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                selected?.title ?: "选择专题",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = collectionMenuExpanded,
                            onDismissRequest = { collectionMenuExpanded = false }
                        ) {
                            state.collections.forEach { collection ->
                                DropdownMenuItem(
                                    text = { Text(collection.title) },
                                    onClick = {
                                        viewModel.selectCurateCollection(collection.id)
                                        collectionMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.curationNote,
                        onValueChange = viewModel::updateCurationNote,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("统一收录说明") },
                        minLines = 2,
                        maxLines = 4
                    )
                    OutlinedTextField(
                        value = state.curationPosition,
                        onValueChange = viewModel::updateCurationPosition,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("起始顺序") },
                        singleLine = true
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::batchCuratePosts,
                    enabled = state.curateCollectionId.isNotBlank() &&
                        state.processingCollectionId == null
                ) { Text("批量收录") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissBatchCurate) { Text("取消") }
            }
        )
    }

    state.coverCollectionId?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissCollectionCover,
            title = { Text("选择专题封面") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = state.coverPostId.isBlank(),
                            onClick = { viewModel.selectCoverPost("") }
                        )
                        Text("自动选择首篇可用图片")
                    }
                    if (state.processingCollectionId != null) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (state.coverCandidates.isEmpty()) {
                        Text(
                            "专题内暂无已通过且包含图片的笔记",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                            items(state.coverCandidates, key = { it.postId }) { candidate ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = state.coverPostId == candidate.postId,
                                        onClick = { viewModel.selectCoverPost(candidate.postId) }
                                    )
                                    Text(
                                        candidate.title,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::saveCollectionCover,
                    enabled = state.processingCollectionId == null
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCollectionCover) { Text("取消") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("社区审核", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load() }, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val isPostSection = state.section == CommunityModerationSection.POSTS
            val isCommentSection = state.section == CommunityModerationSection.COMMENTS
            val isCollectionSection = state.section == CommunityModerationSection.COLLECTIONS
            val hasItems = when (state.section) {
                CommunityModerationSection.POSTS -> state.items.isNotEmpty()
                CommunityModerationSection.COMMENTS -> state.commentReports.isNotEmpty()
                CommunityModerationSection.COLLECTIONS -> state.collections.isNotEmpty()
            }
            TabRow(selectedTabIndex = state.section.ordinal) {
                Tab(
                    selected = isPostSection,
                    onClick = { viewModel.selectSection(CommunityModerationSection.POSTS) },
                    text = { Text("帖子") }
                )
                Tab(
                    selected = isCommentSection,
                    onClick = { viewModel.selectSection(CommunityModerationSection.COMMENTS) },
                    text = { Text("评论") }
                )
                Tab(
                    selected = isCollectionSection,
                    onClick = { viewModel.selectSection(CommunityModerationSection.COLLECTIONS) },
                    text = { Text("专题") }
                )
            }
            if (!isCollectionSection) state.summary?.let { ModerationSummary(it) }
            if (isCollectionSection) {
                state.collectionSummary?.let { CollectionOperationsSummary(it) }
            }
            if (isPostSection) {
                TabRow(selectedTabIndex = moderationFilters.indexOf(state.filter).coerceAtLeast(0)) {
                    moderationFilters.forEach { filter ->
                        Tab(
                            selected = state.filter == filter,
                            onClick = { viewModel.load(filter) },
                            text = { Text(moderationFilterLabel(filter)) }
                        )
                    }
                }
            }
            if (isCollectionSection) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "专题概览",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = viewModel::openCreateCollection) { Text("新建专题") }
                }
            }
            if (isPostSection && state.filter == "all" && state.selectedPostIds.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 ${state.selectedPostIds.size} 篇",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = viewModel::openBatchCurate) { Text("批量收录") }
                }
            }
            when {
                !state.isAdmin && !state.isLoading -> ModerationEmpty("仅管理员可访问审核工作台")
                state.isLoading && !hasItems -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp) }
                !hasItems -> ModerationEmpty(
                    state.error ?: when (state.section) {
                        CommunityModerationSection.POSTS -> "当前没有需要处理的内容"
                        CommunityModerationSection.COMMENTS -> "当前没有待处理的评论举报"
                        CommunityModerationSection.COLLECTIONS -> "尚未创建专题"
                    }
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                    state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
                    if (isPostSection) {
                        items(state.items, key = { it.id }) { item ->
                            ModerationPostCard(
                                item = item,
                                processing = state.processingPostId == item.id,
                                onApprove = { viewModel.approve(item.id) },
                                onReject = { viewModel.openReject(item.id) },
                                onCurate = { viewModel.openCurate(item.id) },
                                selectionEnabled = state.filter == "all",
                                selected = state.selectedPostIds.contains(item.id),
                                onToggleSelection = { viewModel.togglePostSelection(item.id) }
                            )
                        }
                    } else if (isCommentSection) {
                        items(state.commentReports, key = { it.id }) { report ->
                            ModerationCommentReportCard(
                                report = report,
                                processing = state.processingReportId == report.id,
                                onKeep = { viewModel.keepComment(report.id) },
                                onDelete = { viewModel.openDeleteComment(report.id) }
                            )
                        }
                    } else {
                        items(state.collections, key = { it.id }) { collection ->
                            ModerationCollectionCard(
                                collection = collection,
                                processing = state.processingCollectionId == collection.id,
                                onToggle = { viewModel.toggleCollection(collection) },
                                onCover = { viewModel.openCollectionCover(collection.id) }
                            )
                        }
                    }
                    val nextCursor = when (state.section) {
                        CommunityModerationSection.POSTS -> state.nextPostCursor
                        CommunityModerationSection.COMMENTS -> state.nextCommentReportCursor
                        CommunityModerationSection.COLLECTIONS -> state.nextCollectionCursor
                    }
                    if (nextCursor != null) {
                        item {
                            TextButton(
                                onClick = viewModel::loadMore,
                                enabled = !state.isLoadingMore,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
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

@Composable
private fun ModerationSummary(summary: CommunityOperationsSummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ModerationSummaryMetric("待审", summary.pendingPostCount)
        ModerationSummaryMetric("帖子举报", summary.reportedPostCount)
        ModerationSummaryMetric("评论举报", summary.openCommentReportCount)
        ModerationSummaryMetric("${summary.windowHours}h 限流", summary.limitedActionCount)
    }
}

@Composable
private fun CollectionOperationsSummary(summary: CommunityCollectionOperationsSummary) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ModerationSummaryMetric("专题", summary.totalCollectionCount)
        ModerationSummaryMetric("已发布", summary.publishedCollectionCount)
        ModerationSummaryMetric("公开笔记", summary.visiblePostCount)
        ModerationSummaryMetric("失效收录", summary.hiddenAssignmentCount)
        ModerationSummaryMetric("空专题", summary.publishedEmptyCount)
    }
}

@Composable
private fun ModerationSummaryMetric(label: String, value: Int) {
    Column(modifier = Modifier.width(72.dp)) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private val moderationFilters = listOf("pending", "reported", "all")

private fun moderationFilterLabel(filter: String): String = when (filter) {
    "reported" -> "被举报"
    "all" -> "全部"
    else -> "待审核"
}

@Composable
private fun ModerationPostCard(
    item: CommunityModerationItem,
    processing: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onCurate: () -> Unit,
    selectionEnabled: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionEnabled && item.review.status == "approved") {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    item.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.openReportCount > 0) {
                    Icon(Icons.Default.Flag, contentDescription = "举报", tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("${item.openReportCount}", color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                item.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            item.reports.forEach { report ->
                Text(
                    "${reportCategoryLabel(report.category)}${report.reason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove, enabled = !processing) { Text("通过") }
                OutlinedButton(onClick = onReject, enabled = !processing) { Text("拒绝") }
                if (item.review.status == "approved") {
                    TextButton(onClick = onCurate, enabled = !processing) { Text("收录专题") }
                }
                if (processing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
                }
            }
            Text(
                formatModerationDate(item.publishedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ModerationCollectionCard(
    collection: CommunityCollection,
    processing: Boolean,
    onToggle: () -> Unit,
    onCover: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    collection.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when (collection.status) {
                        "published" -> "已发布"
                        "unpublished" -> "已下线"
                        else -> "草稿"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (collection.status == "published") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (collection.description.isNotBlank()) {
                Text(
                    collection.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val metadata = listOfNotNull(
                collection.destination.takeIf { it.isNotBlank() },
                collection.theme.takeIf { it.isNotBlank() },
                "可见 ${collection.visiblePostCount} / 已收录 ${collection.assignedPostCount}"
            ).joinToString("  ·  ")
            Text(
                metadata,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onToggle,
                    enabled = !processing &&
                        (collection.status == "published" || collection.visiblePostCount > 0)
                ) {
                    Text(if (collection.status == "published") "下线" else "发布")
                }
                OutlinedButton(
                    onClick = onCover,
                    enabled = !processing && collection.visiblePostCount > 0
                ) { Text("选择封面") }
                if (processing) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun ModerationCommentReportCard(
    report: CommunityCommentReportQueueItem,
    processing: Boolean,
    onKeep: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Flag,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    report.postTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                report.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${reportCategoryLabel(report.category)}${report.reason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onKeep, enabled = !processing) { Text("保留") }
                Button(
                    onClick = onDelete,
                    enabled = !processing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("删除")
                }
                if (processing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
                        strokeWidth = 2.dp
                    )
                }
            }
            Text(
                formatModerationDate(report.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ModerationEmpty(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun reportCategoryLabel(category: String): String = when (category) {
    "privacy" -> "隐私信息"
    "copyright" -> "版权问题"
    "safety" -> "安全风险"
    "spam" -> "广告或垃圾内容"
    else -> "其他"
}

private fun formatModerationDate(timestamp: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))
