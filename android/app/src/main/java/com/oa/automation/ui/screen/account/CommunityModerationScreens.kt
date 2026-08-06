package com.oa.automation.ui.screen.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.CommunityCommentReportQueueItem
import com.oa.automation.domain.model.CommunityModerationItem
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
            val hasItems = if (isPostSection) state.items.isNotEmpty() else state.commentReports.isNotEmpty()
            TabRow(selectedTabIndex = state.section.ordinal) {
                Tab(
                    selected = isPostSection,
                    onClick = { viewModel.selectSection(CommunityModerationSection.POSTS) },
                    text = { Text("帖子") }
                )
                Tab(
                    selected = !isPostSection,
                    onClick = { viewModel.selectSection(CommunityModerationSection.COMMENTS) },
                    text = { Text("评论") }
                )
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
            when {
                !state.isAdmin && !state.isLoading -> ModerationEmpty("仅管理员可访问审核工作台")
                state.isLoading && !hasItems -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp) }
                !hasItems -> ModerationEmpty(
                    state.error ?: if (isPostSection) "当前没有需要处理的内容" else "当前没有待处理的评论举报"
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
                                onReject = { viewModel.openReject(item.id) }
                            )
                        }
                    } else {
                        items(state.commentReports, key = { it.id }) { report ->
                            ModerationCommentReportCard(
                                report = report,
                                processing = state.processingReportId == report.id,
                                onKeep = { viewModel.keepComment(report.id) },
                                onDelete = { viewModel.openDeleteComment(report.id) }
                            )
                        }
                    }
                }
            }
        }
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
    onReject: () -> Unit
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
