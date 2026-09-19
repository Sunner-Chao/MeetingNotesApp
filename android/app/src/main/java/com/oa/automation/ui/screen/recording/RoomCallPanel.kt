package com.oa.automation.ui.screen.recording

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.*
import com.oa.automation.infrastructure.service.RoomCallController
import com.oa.automation.infrastructure.service.RoomCallService

@Composable
internal fun RoomCallDialog(controller: RoomCallController, onDismiss: () -> Unit) {
    val call by controller.state.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("会议通话") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RoomCallPanel(null, call, controller)
                if (!call.active && call.error == null) Text("已离开通话")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        shape = RoundedCornerShape(12.dp))
}

@Composable
internal fun RoomCallPanel(room: MeetingRoom?, call: RoomCallState, controller: RoomCallController) {
    val context = LocalContext.current
    var permissionError by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedId by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedTitle by rememberSaveable { mutableStateOf("") }
    fun join(id: String, title: String) {
        runCatching { RoomCallService.join(context, id, title) }
            .onFailure { permissionError = "无法启动通话，请返回页面后重试" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val id = requestedId
        requestedId = null
        val allowed = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (allowed && id != null) join(id, requestedTitle)
        else permissionError = "允许麦克风权限后即可加入；入会时默认静音"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (call.active) {
            Text(call.title, style = MaterialTheme.typography.titleSmall)
            Text(when (call.status) {
                RoomCallStatus.CONNECTING -> "正在加入通话…"
                RoomCallStatus.RECONNECTING -> "网络波动，正在重新连接…"
                else -> "通话中 · ${call.participants.size} 人"
            }, style = MaterialTheme.typography.bodySmall)
            if (call.status == RoomCallStatus.CONNECTING || call.status == RoomCallStatus.RECONNECTING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            call.deviceName?.let { Text("音频设备：$it", style = MaterialTheme.typography.bodySmall) }
            call.participants.forEach { member ->
                Text("${member.name}${if (member.isSelf) "（我）" else ""} · ${when {
                    member.muted -> "已静音"
                    member.speaking -> "正在发言"
                    else -> "麦克风开启"
                }}", style = MaterialTheme.typography.bodySmall,
                    color = if (member.speaking && !member.muted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = controller::toggleMicrophone,
                    shape = RoundedCornerShape(8.dp),
                    enabled = call.status == RoomCallStatus.CONNECTED && !call.changingMicrophone,
                    modifier = Modifier.weight(1f)) {
                    Icon(if (call.muted) Icons.Default.MicOff else Icons.Default.Mic, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (call.changingMicrophone) "切换中" else if (call.muted) "开启麦克风" else "静音")
                }
                OutlinedButton(onClick = { controller.leave() }, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CallEnd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("离开通话")
                }
            }
            Text("通话尚不录制或转写，离开通话后可使用本机录音。", style = MaterialTheme.typography.bodySmall)
        } else if (room?.state == "open") {
            if (room.mediaReady) FilledTonalButton(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), onClick = {
                permissionError = null
                val missing = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) {
                    join(room.id, room.title)
                } else {
                    requestedId = room.id
                    requestedTitle = room.title
                    permission.launch(missing.toTypedArray())
                }
            }) { Text("加入通话 · 默认静音") }
            else Text("多人通话服务尚未开放，仍可管理成员与录音意愿。", style = MaterialTheme.typography.bodySmall)
        }
        (permissionError ?: call.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
