package com.oa.automation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oa.automation.infrastructure.update.AndroidAppUpdate

@Composable
fun AppUpdatePrompt(
    update: AndroidAppUpdate?,
    isDownloading: Boolean,
    progress: Int,
    message: String?,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit
) {
    if (update == null) return

    AlertDialog(
        onDismissRequest = { if (!update.mandatory && !isDownloading) onLater() },
        title = { Text("智悟本有新版本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("版本 ${update.versionName} 已发布，建议及时更新。")
                if (update.releaseNotes.isNotBlank()) {
                    Text(update.releaseNotes)
                }
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { (progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("正在下载 ${progress.coerceIn(0, 100)}%")
                }
                message?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(onClick = onUpdate, enabled = !isDownloading) {
                Text(if (isDownloading) "下载中" else "立即更新")
            }
        },
        dismissButton = if (!update.mandatory && !isDownloading) {
            {
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    TextButton(onClick = onLater) { Text("稍后提醒") }
                    TextButton(onClick = onIgnore) { Text("忽略此版本") }
                }
            }
        } else null
    )
}
