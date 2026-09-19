package com.oa.automation.ui.screen.recording

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oa.automation.domain.model.CaptureInput
import com.oa.automation.domain.model.MeetingAudioSource
import com.oa.automation.domain.model.displayName
import com.oa.automation.domain.model.isImportedAudio
import com.oa.automation.domain.model.ImportedAudioSources

internal fun canEditSessionSource(state: RecordingUiState): Boolean =
    !state.isRecording && !state.isRecordingActionPending && !state.isFinalizingRecording &&
        !state.isTranscribing && !state.isImportingAudio && !state.isGeneratingReport &&
        !state.isSavingSession && !state.isSavingRoomBinding

internal fun sessionSourceLabel(state: RecordingUiState): String = when {
    state.inputMode == InputMode.IMPORT -> state.audioSource.takeIf { it.isImportedAudio() }
        ?.displayName() ?: "导入音频"
    state.isRecording && state.audioSource == MeetingAudioSource.UNKNOWN -> "正在确认麦克风"
    state.isRecording && state.preferredCaptureInput == CaptureInput.BLUETOOTH &&
        state.audioSource != MeetingAudioSource.BLUETOOTH_MICROPHONE -> "蓝牙未接入 · 实际使用${state.audioSource.displayName()}"
    state.isRecording -> "正在使用${state.audioSource.displayName()}"
    else -> if (state.preferredCaptureInput == CaptureInput.BLUETOOTH) "已选蓝牙耳机" else "已选手机麦克风"
}

@Composable
internal fun MeetingSessionSourceRow(
    state: RecordingUiState,
    muted: Color,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            imageVector = when (state.audioSource) {
                MeetingAudioSource.BLUETOOTH_MICROPHONE -> Icons.Default.Headphones
                MeetingAudioSource.IMPORTED_FILE, MeetingAudioSource.WECHAT_EXPORT,
                MeetingAudioSource.TENCENT_MEETING_EXPORT, MeetingAudioSource.PHONE_RECORDING -> Icons.Default.SettingsInputAntenna
                else -> Icons.Default.Mic
            },
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = sessionSourceLabel(state),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onOpen) {
            Text("来源")
        }
    }
}

@Composable
internal fun MeetingSessionSourceDialog(
    state: RecordingUiState,
    onSave: (CaptureInput, MeetingAudioSource, String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf(state.preferredCaptureInput) }
    var source by rememberSaveable { mutableStateOf(state.audioSource.takeIf { it.isImportedAudio() } ?: MeetingAudioSource.IMPORTED_FILE) }
    var externalId by rememberSaveable { mutableStateOf(state.externalSessionId) }
    var permissionError by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val editable = canEditSessionSource(state)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { input = CaptureInput.BLUETOOTH; permissionError = "" }
        else permissionError = "允许连接附近设备后，可使用蓝牙麦克风"
    }
    androidx.compose.runtime.LaunchedEffect(state.isSavingSession, saving) {
        if (saving && !state.isSavingSession) {
            saving = false
            if (state.error == null) onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话来源") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.inputMode == InputMode.VOICE) {
                Text("采集方式", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureInput.entries.forEach { option ->
                        OutlinedButton(
                            onClick = {
                                if (option == CaptureInput.BLUETOOTH && Build.VERSION.SDK_INT >= 31 &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                    permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                } else { input = option; permissionError = "" }
                            },
                            enabled = editable,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (input == option) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Icon(if (option == CaptureInput.PHONE) Icons.Default.Mic else Icons.Default.Headphones, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (option == CaptureInput.PHONE) "手机" else "蓝牙")
                            if (input == option) Icon(Icons.Default.Check, null, Modifier.size(15.dp))
                        }
                    }
                }
                Text(state.captureDeviceName?.let { "实际采集：$it" } ?: "开始录音后确认实际设备", style = MaterialTheme.typography.bodySmall)
                if (input == CaptureInput.BLUETOOTH) Text("请先在系统中连接支持通话的蓝牙耳机。", style = MaterialTheme.typography.bodySmall)
                if (permissionError.isNotBlank()) Text(permissionError, color = MaterialTheme.colorScheme.error)
                if (!editable) Text("本段录音进行中，采集方式暂不可更改。", style = MaterialTheme.typography.bodySmall)
                } else {
                Text("导入来源", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ImportedAudioSources.forEach { option ->
                        TextButton(onClick = { source = option }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                            Text(if (source == option) "✓ ${option.displayName()}" else option.displayName(), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                OutlinedTextField(
                    value = externalId,
                    onValueChange = { externalId = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = editable,
                    label = { Text("外部会议编号（可选）") },
                    supportingText = { Text("只记录编号，不代表已直接接入微信或腾讯会议") }
                )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { saving = true; onSave(input, source, externalId) }, enabled = editable && !saving) {
                Text(if (state.isSavingSession) "保存中" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        shape = RoundedCornerShape(12.dp)
    )
}
