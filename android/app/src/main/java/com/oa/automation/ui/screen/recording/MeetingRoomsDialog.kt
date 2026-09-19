package com.oa.automation.ui.screen.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import com.oa.automation.infrastructure.service.RoomCallController

@Composable
internal fun MeetingRoomsDialog(
    onDismiss: () -> Unit,
    boundRoomId: String?,
    bindingEnabled: Boolean,
    bindingBusy: Boolean,
    bindingError: String?,
    onRoomSelected: (String?) -> Unit,
    viewModel: MeetingRoomsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val callController: RoomCallController = koinInject()
    val call by callController.state.collectAsStateWithLifecycle()
    var joining by rememberSaveable(state.userId) { mutableStateOf(false) }
    var title by rememberSaveable(state.userId) { mutableStateOf("") }
    var code by rememberSaveable(state.userId) { mutableStateOf("") }
    var consent by rememberSaveable(state.userId) { mutableStateOf(false) }
    var confirmingEnd by rememberSaveable(state.userId, state.selected?.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestBoundRoomId by rememberUpdatedState(boundRoomId)
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.open(latestBoundRoomId)
            try { awaitCancellation() } finally { viewModel.close() }
        }
    }
    val room = state.selected
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (room == null) "会议房间" else room.title) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RoomCallPanel(room, call, callController)
                if (state.busy || bindingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                bindingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (boundRoomId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本条记录已关联房间", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRoomSelected(null) }, enabled = bindingEnabled && !bindingBusy) { Text("解除关联") }
                    }
                }
                if (room == null) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { joining = false }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(if (!joining) "✓ 创建" else "创建") }
                        TextButton(onClick = { joining = true }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(if (joining) "✓ 加入" else "加入") }
                    }
                    if (joining) OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { char -> char in '0'..'9' }.take(9) },
                        label = { Text("九位会议号") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth()
                    ) else OutlinedTextField(
                        value = title, onValueChange = { title = it.take(120) },
                        label = { Text("主题（可选）") }, singleLine = true,
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth()
                    )
                    RoomConsentRow(consent, !state.busy, { consent = it })
                    FilledTonalButton(
                        onClick = { if (joining) viewModel.join(code, consent) else viewModel.create(title, consent) },
                        enabled = !state.busy && state.userId.isNotBlank() && (!joining || code.length == 9),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()
                    ) { Text(if (joining) "加入房间" else "创建房间") }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("我的房间", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("刷新") }
                    }
                    if (state.rooms.isEmpty() && !state.busy) Text("还没有加入的房间", style = MaterialTheme.typography.bodySmall)
                    state.rooms.forEach { item ->
                        OutlinedButton(onClick = { viewModel.select(item) }, enabled = !state.busy, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(item.title)
                                Text("${item.code} · ${if (item.state == "ended") "已结束" else "会前准备"}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("会议号 ${room.code}", modifier = Modifier.weight(1f))
                        TextButton(onClick = { clipboard.setText(AnnotatedString(room.code)) }) { Text("复制") }
                    }
                    Text(if (room.state == "ended") "会议已结束" else "房间成员 · ${room.members.count { it.leftAt == null }} 人", style = MaterialTheme.typography.labelLarge)
                    room.members.forEach { member ->
                        val role = if (member.userId == room.hostId) "主持人" else "成员"
                        val status = if (member.leftAt != null) "已离开" else if (member.recordingConsent) "同意录音" else "未同意录音"
                        Text("${member.displayName} · $role · $status", style = MaterialTheme.typography.bodySmall)
                    }
                    if (room.state == "open") {
                        RoomConsentRow(room.members.firstOrNull { it.userId == state.userId }?.recordingConsent == true, !state.busy, viewModel::consent)
                        Text("关联记录后，所有在场成员同意才可开始或继续录音。成员撤回同意后，录音会在下次同步时暂停。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (room.state == "open") FilledTonalButton(
                        onClick = { onRoomSelected(room.id) },
                        enabled = bindingEnabled && !bindingBusy && !state.busy && boundRoomId != room.id,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()
                    ) { Text(if (boundRoomId == room.id) "已关联本条记录" else "关联本条记录") }
                    if (!bindingEnabled && !bindingBusy) Text("请先暂停，再修改房间关联。", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::refresh, enabled = !state.busy, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) { Text("刷新") }
                        if (room.hostId == state.userId && room.state == "open") {
                            OutlinedButton(onClick = { confirmingEnd = true }, enabled = !state.busy, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) { Text("结束房间") }
                        } else {
                            OutlinedButton(onClick = viewModel::leave, enabled = !state.busy, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) { Text("离开房间") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { if (room != null) TextButton(onClick = viewModel::showList, enabled = !state.busy) { Text("房间列表") } },
        shape = RoundedCornerShape(12.dp)
    )
    if (confirmingEnd && room != null) AlertDialog(
        onDismissRequest = { confirmingEnd = false },
        title = { Text("结束这个房间？") },
        text = { Text("结束后所有成员将离开通话，已有房间信息仍可查看。") },
        confirmButton = { TextButton(onClick = { confirmingEnd = false; viewModel.end() }, enabled = !state.busy) { Text("结束") } },
        dismissButton = { TextButton(onClick = { confirmingEnd = false }) { Text("返回") } },
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun RoomConsentRow(consent: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = consent, onCheckedChange = onChange, enabled = enabled)
        Text("我同意本次会议录音与转写", style = MaterialTheme.typography.bodySmall)
    }
}
