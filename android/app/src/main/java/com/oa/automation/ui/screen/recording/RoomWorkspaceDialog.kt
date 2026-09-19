package com.oa.automation.ui.screen.recording

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.oa.automation.domain.model.MeetingRoom
import com.oa.automation.ui.screen.report.MarkdownLine
import kotlinx.coroutines.awaitCancellation
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun RoomWorkspaceDialog(room: MeetingRoom, userId: String, onDismiss: () -> Unit,
    viewModel: RoomWorkspaceViewModel = koinViewModel()) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    var showReport by rememberSaveable(room.id, userId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val contentHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.65f)
    LaunchedEffect(controller, room.id, userId, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controller.open(room.id)
            try { awaitCancellation() } finally { controller.close() }
        }
    }
    LaunchedEffect(state.segments.lastOrNull(), showReport) {
        if (!showReport && !listState.isScrollInProgress && state.segments.isNotEmpty() &&
            (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.segments.lastIndex - 2) {
            listState.scrollToItem(state.segments.lastIndex)
        }
    }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(12.dp),
        title = { Text(room.title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = contentHeight), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showReport = false }, modifier = Modifier.weight(1f)) { Text(if (!showReport) "✓ 实时转录" else "实时转录") }
                    TextButton(onClick = { showReport = true }, modifier = Modifier.weight(1f)) { Text(if (showReport) "✓ 会议纪要" else "会议纪要") }
                }
                Text(when (state.transcription.state) {
                    "starting" -> "正在连接本地转写…"
                    "running" -> "本地转写中 · 按成员保留发言"
                    "stopping" -> "正在保存最后一句…"
                    "paused", "failed", "interrupted" -> state.transcription.message.ifBlank { "转写已暂停" }
                    else -> if (state.transcription.available) "由主持人开始，成员可同步查看" else "房间转写服务尚未就绪"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.loading || state.busy || state.transcription.state in setOf("starting", "stopping") || (showReport && state.report?.generating == true)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (room.hostId == userId) {
                    if (!showReport) FilledTonalButton(
                        onClick = { controller.transcription(!state.transcription.active) },
                        enabled = !state.busy && state.transcription.state != "stopping" && (state.transcription.active || (room.state == "open" && room.allRecordingConsented && state.transcription.available)),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.transcription.active) "暂停转写" else if (state.segments.isEmpty()) "开始转写" else "继续转写") }
                    else FilledTonalButton(onClick = controller::generateReport,
                        enabled = !state.busy && !state.transcription.active && state.segments.isNotEmpty() && state.report?.generating != true && state.transcription.available,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.report?.generating == true) "正在整理…" else if (state.report?.state == "ready") "更新这段纪要" else "生成这段纪要") }
                }
                if (!room.allRecordingConsented && room.state == "open" && !state.transcription.active) {
                    Text("等待房间成员全部同意录音与转写", style = MaterialTheme.typography.bodySmall)
                }
                if (showReport) {
                    if (state.transcription.active) Text("暂停转写后，可整理已有内容。", style = MaterialTheme.typography.bodySmall)
                    if (state.report?.state == "failed") Text(state.report?.message.orEmpty(), color = MaterialTheme.colorScheme.error)
                    if (state.report?.state == "ready" && state.report!!.sourceRevision < state.transcription.revision) {
                        Text("已有新的发言，这份纪要尚未更新。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.report?.state == "ready") LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.report!!.text.lines()) { MarkdownLine(it) }
                    } else if (state.report?.generating != true) Text("生成后，房间成员可在这里查看同一份纪要。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    if (state.segments.isEmpty() && !state.loading) Text("发言后，文字会按成员和时间段显示在这里。", style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.segments, key = { it.id }) { row ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${transcriptTimestamp(row.startMs)}–${transcriptTimestamp(row.endMs)} · ${row.displayName}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(row.text, style = MaterialTheme.typography.bodyMedium,
                                    color = if (row.finalized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            if (!showReport && state.segments.isNotEmpty()) IconButton(onClick = {
                clipboard.setText(AnnotatedString(state.segments.joinToString("\n\n") {
                    "[${transcriptTimestamp(it.startMs)}–${transcriptTimestamp(it.endMs)}] ${it.displayName}：${it.text}"
                }))
            }) { Icon(Icons.Default.ContentCopy, contentDescription = "复制转录文字") }
        })
}
