package com.oa.automation.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.oa.automation.domain.model.Meeting
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingCard(
    meeting: Meeting,
    hasReport: Boolean = false,
    isRegenerating: Boolean = false,
    onClick: () -> Unit,
    onReportClick: () -> Unit = {},
    onContinueRecording: () -> Unit = {},
    onRegenerateReport: () -> Unit = {},
    onDelete: () -> Unit = {},
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val deleteAlpha by animateFloatAsState(
        targetValue = (-offsetX / swipeThresholdPx).coerceIn(0f, 1f),
        animationSpec = tween(90),
        label = "deleteAlpha"
    )

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("删除会议", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    text = "“${meeting.title}”及其录音、转写和纪要将被永久删除。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                modifier = Modifier.padding(end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error.copy(alpha = deleteAlpha)
                )
                Text(
                    text = "删除",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error.copy(alpha = deleteAlpha)
                )
            }
        }

        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .pointerInput(swipeThresholdPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -swipeThresholdPx) showDeleteDialog = true
                            offsetX = 0f
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(-swipeThresholdPx * 1.7f, 0f)
                        }
                    )
                },
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            if (hasReport) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondary
                        )
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp, top = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = meeting.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            MeetingMetadata(meeting = meeting, hasReport = hasReport)
                        }

                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "会议操作",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            MeetingMenu(
                                expanded = showMenu,
                                hasReport = hasReport,
                                isRegenerating = isRegenerating,
                                onDismiss = { showMenu = false },
                                onContinueRecording = onContinueRecording,
                                onRegenerateReport = onRegenerateReport,
                                onEdit = onEdit,
                                onDelete = { showDeleteDialog = true }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = if (hasReport) onReportClick else onContinueRecording,
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = if (hasReport) Icons.AutoMirrored.Filled.Article else Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (hasReport) "查看纪要" else "继续记录")
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))
            }
        }
    }
}

@Composable
private fun MeetingMetadata(meeting: Meeting, hasReport: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusLabel(hasReport = hasReport)
        Icon(
            imageVector = Icons.Default.AccessTime,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = formatDate(meeting.createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (meeting.durationMs > 0) {
            Text(
                text = formatDuration(meeting.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusLabel(hasReport: Boolean) {
    val container = if (hasReport) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (hasReport) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(shape = MaterialTheme.shapes.small, color = container) {
        Text(
            text = if (hasReport) "纪要完成" else "待完善",
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun MeetingMenu(
    expanded: Boolean,
    hasReport: Boolean,
    isRegenerating: Boolean,
    onDismiss: () -> Unit,
    onContinueRecording: () -> Unit,
    onRegenerateReport: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (hasReport) {
            DropdownMenuItem(
                text = { Text("继续录音") },
                leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onContinueRecording()
                }
            )
            DropdownMenuItem(
                text = { Text(if (isRegenerating) "纪要生成中" else "重新生成纪要") },
                leadingIcon = {
                    if (isRegenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                },
                enabled = !isRegenerating,
                onClick = {
                    onDismiss()
                    onRegenerateReport()
                }
            )
        }
        onEdit?.let { edit ->
            DropdownMenuItem(
                text = { Text("修改名称") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    onDismiss()
                    edit()
                }
            )
        }
        DropdownMenuItem(
            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            onClick = {
                onDismiss()
                onDelete()
            }
        )
    }
}

private fun formatDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - timestamp).coerceAtLeast(0)
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> SimpleDateFormat("今天 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))
        diff < 172_800_000 -> SimpleDateFormat("昨天 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))
        else -> SimpleDateFormat("MM月dd日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalMinutes = durationMs / 60_000
    return if (totalMinutes < 1) "< 1 分钟" else "${totalMinutes} 分钟"
}
