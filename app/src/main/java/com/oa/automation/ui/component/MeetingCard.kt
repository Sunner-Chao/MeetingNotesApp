package com.oa.automation.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.runtime.LaunchedEffect
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

// ─── Design Tokens ────────────────────────────────────────────────────────────
private val CardRadius = 20.dp
private val CardPadding = 16.dp
private val StatusGreen = Color(0xFF2E7D32)

// ─── Main Component ────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var hasEverSwiped by remember { mutableStateOf(false) }
    val swipeThresholdPx = with(LocalDensity.current) { 56.dp.toPx() }

    // Animate delete background alpha: 0 → 1 as swipe goes 0 → threshold
    val deleteBgAlpha by animateFloatAsState(
        targetValue = (-offsetX.coerceAtLeast(0f) / swipeThresholdPx).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 80),
        label = "deleteBgAlpha"
    )

    // Swipe hint fades out after first successful swipe
    val hintAlpha by animateFloatAsState(
        targetValue = if (hasEverSwiped) 0f else 1f,
        animationSpec = tween(durationMillis = 400),
        label = "hintAlpha"
    )

    // ── Delete Dialog ───────────────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除会议") },
            text = {
                Column {
                    Text("确定要删除此会议及其所有记录吗？")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = meeting.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onDelete().also { showDeleteDialog = false } }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // ── Delete Reveal Layer ───────────────────────────────────────────────
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(CardRadius))
                .background(MaterialTheme.colorScheme.error.copy(alpha = deleteBgAlpha)),
            contentAlignment = Alignment.CenterEnd
        ) {
            // Swipe hint: shown until user has swiped at least once
            if (hintAlpha > 0f && !hasReport) {
                Row(
                    modifier = Modifier
                        .padding(end = 20.dp)
                        .offset(x = (-offsetX / 2).coerceIn(-swipeThresholdPx, 0f).roundToInt().dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = hintAlpha),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "删除",
                        color = Color.White.copy(alpha = hintAlpha),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // Delete icon always visible when background is revealed
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除",
                tint = Color.White.copy(alpha = deleteBgAlpha),
                modifier = Modifier
                    .padding(end = 20.dp)
                    .size(22.dp)
            )
        }

        // ── Foreground Card ──────────────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -swipeThresholdPx) {
                                hasEverSwiped = true
                                showDeleteDialog = true
                            }
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            val newOffset = offsetX + dragAmount
                            offsetX = newOffset.coerceIn(-swipeThresholdPx * 1.5f, 0f)
                            // Mark as swiped on first meaningful swipe
                            if (newOffset < -swipeThresholdPx / 2 && !hasEverSwiped) {
                                hasEverSwiped = true
                            }
                        }
                    )
                },
            shape = RoundedCornerShape(CardRadius),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    hasReport -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surface
                }
            ),
            onClick = if (hasReport) onReportClick else onClick
        ) {
            MeetingCardContent(
                meeting = meeting,
                hasReport = hasReport,
                isRegenerating = isRegenerating,
                onContinueRecording = onContinueRecording,
                onRegenerateReport = onRegenerateReport,
                onDelete = { showDeleteDialog = true },
                onEdit = onEdit,
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it }
            )
        }
    }
}

// ─── Inner Content ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingCardContent(
    meeting: Meeting,
    hasReport: Boolean,
    isRegenerating: Boolean,
    onContinueRecording: () -> Unit,
    onRegenerateReport: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)?,
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(CardPadding),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Left: Status Icon ────────────────────────────────────────────────
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = if (hasReport) {
                StatusGreen.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (hasReport) Icons.Default.CheckCircle else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (hasReport) StatusGreen else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // ── Center: Title + Meta ──────────────────────────────────────────────
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = meeting.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = formatDate(meeting.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasReport) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "已完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusGreen,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // ── Right: Actions ────────────────────────────────────────────────────
        if (hasReport) {
            IconButton(
                onClick = onContinueRecording,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "继续录音",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box {
                IconButton(
                    onClick = { onShowMenuChange(true) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { onShowMenuChange(false) }
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (isRegenerating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(if (isRegenerating) "生成中" else "重新生成")
                            }
                        },
                        onClick = {
                            onShowMenuChange(false)
                            onRegenerateReport()
                        },
                        enabled = !isRegenerating
                    )
                    onEdit?.let { editCallback ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text("修改名称")
                                }
                            },
                            onClick = {
                                onShowMenuChange(false)
                                editCallback()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        onClick = {
                            onShowMenuChange(false)
                            onDelete()
                        }
                    )
                }
            }
        } else {
            Text(
                text = "继续",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            onEdit?.let { editCallback ->
                IconButton(
                    onClick = editCallback,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ─── Utilities ─────────────────────────────────────────────────────────────────
private fun formatDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
