package com.oa.automation.ui.screen.community

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oa.automation.domain.model.CommunitySyncOperation
import com.oa.automation.domain.model.CommunitySyncStatus
import com.oa.automation.domain.model.MyCommunityPost
import com.oa.automation.domain.model.PublishedPost
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.infrastructure.community.PublishedPostMediaStore
import com.oa.automation.infrastructure.db.PublishedPostMediaEntity

@Composable
fun CommunityPublishingWorkbench(
    state: CommunityPublishingUiState,
    remotePosts: List<MyCommunityPost>,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit
) {
    val remotePostsById = remotePosts.associateBy { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Publish, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("我的发布", fontWeight = FontWeight.SemiBold)
                    Text(
                        "研学记录先在本机检查，再同步到社区。不会自动公开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.posts.isNotEmpty()) {
                    Text(state.posts.size.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (state.posts.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            ) {
                Text(
                    "完成一次研学记录并确认总游记后，这里会出现可发布的内容。",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            state.posts.forEach { item ->
                CommunityPublishingItemCard(
                    item = item,
                    remotePost = item.sync?.remotePostId?.let(remotePostsById::get),
                    onOpen = { onOpen(item.post.id) },
                    onRetry = { onRetry(item.post.id) }
                )
            }
        }
        state.message?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CommunityPublishingItemCard(
    item: CommunityPublishingItem,
    remotePost: MyCommunityPost?,
    onOpen: () -> Unit,
    onRetry: () -> Unit
) {
    val (label, color) = publishingStatusLabel(item, remotePost)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.24f))
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.post.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Surface(shape = RoundedCornerShape(5.dp), color = color.copy(alpha = 0.12f)) {
                        Text(label, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = color)
                    }
                }
                Text(
                    listOfNotNull(
                        item.post.destination.takeIf(String::isNotBlank),
                        item.post.travelDays.takeIf { it > 0 }?.let { "$it 天" },
                        "${item.post.stageTitles.size} 段"
                    ).joinToString("  · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                item.sync?.lastError?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Default.Edit, contentDescription = "打开发布检查", tint = MaterialTheme.colorScheme.primary)
            }
            if (item.sync?.status == CommunitySyncStatus.FAILED) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = "重试同步", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun publishingStatusLabel(
    item: CommunityPublishingItem,
    remotePost: MyCommunityPost? = null
): Pair<String, androidx.compose.ui.graphics.Color> {
    val color = when (item.sync?.status) {
        CommunitySyncStatus.FAILED -> androidx.compose.ui.graphics.Color(0xFFD13438)
        CommunitySyncStatus.PUBLISHED -> when (remotePost?.review?.status) {
            "approved" -> androidx.compose.ui.graphics.Color(0xFF107C10)
            "rejected" -> androidx.compose.ui.graphics.Color(0xFFD13438)
            else -> androidx.compose.ui.graphics.Color(0xFF0078D4)
        }
        CommunitySyncStatus.PUBLISHING,
        CommunitySyncStatus.UPLOADING -> androidx.compose.ui.graphics.Color(0xFF0078D4)
        else -> androidx.compose.ui.graphics.Color(0xFF605E5C)
    }
    val label = publishingStatusText(item, remotePost)
    return label to color
}

internal fun publishingStatusText(
    item: CommunityPublishingItem,
    remotePost: MyCommunityPost? = null
): String = when (item.sync?.status) {
        CommunitySyncStatus.PENDING -> "待同步"
        CommunitySyncStatus.UPLOADING -> "同步中"
        CommunitySyncStatus.PRIVATE_DRAFT -> "待发布"
        CommunitySyncStatus.PUBLISHING -> "发布中"
        CommunitySyncStatus.PUBLISHED -> remotePost?.toPublishingReviewLabel() ?: "审核中"
        CommunitySyncStatus.WITHDRAWING -> "撤回中"
        CommunitySyncStatus.WITHDRAWN -> "已撤回"
        CommunitySyncStatus.FAILED -> "同步失败"
        null -> when (item.post.status) {
            PublishedPostStatus.REVIEW -> "待检查"
            PublishedPostStatus.READY -> "待同步"
            PublishedPostStatus.WITHDRAWN -> "已撤回"
        }
    }

private fun MyCommunityPost.toPublishingReviewLabel(): String = when {
    status == "withdrawn" -> "已撤回"
    review.status == "approved" -> "已发布"
    review.status == "rejected" -> "需调整"
    else -> "审核中"
}

@Composable
fun CommunityPublishingReviewDialog(
    post: PublishedPost,
    media: List<PublishedPostMediaEntity>,
    isSaving: Boolean,
    onSaveReview: (Boolean, Boolean) -> Unit,
    onUpdateMetadata: (String, String, Int, List<String>, List<String>) -> Unit,
    onSetMediaIncluded: (String, Boolean) -> Unit,
    onMarkReady: (Boolean, Boolean) -> Unit,
    onPublish: () -> Unit,
    onWithdraw: () -> Unit,
    onDismiss: () -> Unit
) {
    val reviewable = post.status == PublishedPostStatus.REVIEW
    var privacyReviewed by remember(post.id, post.updatedAt) { mutableStateOf(post.privacyReviewed) }
    var rightsConfirmed by remember(post.id, post.updatedAt) { mutableStateOf(post.rightsConfirmed) }
    var destination by remember(post.id, post.updatedAt) { mutableStateOf(post.destination) }
    var travelDate by remember(post.id, post.updatedAt) { mutableStateOf(post.travelDate) }
    var days by remember(post.id, post.updatedAt) { mutableStateOf(post.travelDays.takeIf { it > 0 }?.toString() ?: "") }
    var tags by remember(post.id, post.updatedAt) { mutableStateOf(post.tags.joinToString(", ")) }
    var pois by remember(post.id, post.updatedAt) { mutableStateOf(post.pois.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发布前检查") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("快照 ${post.versionNumber} · 已移除 ${post.redactedCoordinateCount} 处精确位置", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (reviewable) {
                    item { OutlinedTextField(destination, { destination = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("目的地") }) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(travelDate, { travelDate = it.take(10) }, Modifier.weight(1f), singleLine = true, label = { Text("日期") })
                            OutlinedTextField(days, { days = it.filter(Char::isDigit).take(2) }, Modifier.width(86.dp), singleLine = true, label = { Text("天数") })
                        }
                    }
                    item { OutlinedTextField(tags, { tags = it.take(500) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("主题标签") }) }
                    item { OutlinedTextField(pois, { pois = it.take(500) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("地点 / POI") }) }
                }
                if (media.isNotEmpty()) {
                    item { Text("发布图片 ${media.count { it.status != PublishedPostMediaStore.EXCLUDED }}/${media.size}", style = MaterialTheme.typography.labelMedium) }
                    items(media, key = { it.id }) { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            if (reviewable) {
                                Checkbox(
                                    checked = item.status != PublishedPostMediaStore.EXCLUDED,
                                    onCheckedChange = { onSetMediaIncluded(item.id, it) },
                                    enabled = !isSaving
                                )
                            }
                        }
                    }
                }
                item {
                    if (reviewable) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(privacyReviewed, { privacyReviewed = it }, enabled = !isSaving)
                            Text("正文不含个人信息或精确位置", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(rightsConfirmed, { rightsConfirmed = it }, enabled = !isSaving)
                            Text("拥有正文及图片的发布权利", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedTextField(post.content, {}, Modifier.fillMaxWidth().heightIn(min = 120.dp), readOnly = true, label = { Text(post.title) }, enabled = false)
                }
            }
        },
        confirmButton = {
            if (reviewable) {
                Button(onClick = { onMarkReady(privacyReviewed, rightsConfirmed) }, enabled = privacyReviewed && rightsConfirmed && !isSaving) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp)); Text("完成发布准备")
                }
            } else TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            when (post.status) {
                PublishedPostStatus.REVIEW -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") }
                    TextButton(onClick = { onUpdateMetadata(destination, travelDate, days.toIntOrNull() ?: 0, tags.split(',', '，'), pois.split(',', '，')) }, enabled = !isSaving) { Text("保存信息") }
                    TextButton(onClick = { onSaveReview(privacyReviewed, rightsConfirmed) }, enabled = !isSaving) { Text("保存检查") }
                }
                PublishedPostStatus.READY -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onPublish, enabled = !isSaving) { Icon(Icons.Default.CloudUpload, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("同步并发布") }
                    TextButton(onClick = onWithdraw, enabled = !isSaving) { Icon(Icons.Default.Undo, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("撤回") }
                }
                PublishedPostStatus.WITHDRAWN -> Unit
            }
        },
        shape = RoundedCornerShape(18.dp)
    )
}
