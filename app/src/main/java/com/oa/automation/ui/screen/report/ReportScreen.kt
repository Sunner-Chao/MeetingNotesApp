package com.oa.automation.ui.screen.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oa.automation.domain.model.ExportFormat
import com.oa.automation.domain.model.Report
import com.oa.automation.infrastructure.export.ReportExporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    meetingId: String,
    onNavigateBack: () -> Unit,
    onContinueRecording: (String) -> Unit,
    viewModel: ReportViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showExportMenu by remember { mutableStateOf(false) }
    var showChatPanel by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val documentTitle = uiState.report?.documentTitle() ?: "会议纪要"
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(meetingId) {
        viewModel.loadReport(meetingId)
    }

    // 显示消息
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除报告") },
            text = { Text("确定要删除此${documentTitle}吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteReport(meetingId)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(documentTitle) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    if (uiState.report != null) {
                        // 保存按钮
                        IconButton(onClick = { viewModel.saveReport() }) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "保存"
                            )
                        }
                        // 润色按钮
                        IconButton(onClick = { showChatPanel = !showChatPanel }) {
                            Icon(
                                imageVector = if (showChatPanel) Icons.Default.KeyboardArrowDown else Icons.Default.Refresh,
                                contentDescription = if (showChatPanel) "收起润色" else "润色"
                            )
                        }
                        // 删除按钮
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除"
                            )
                        }
                        // 导出按钮
                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "导出"
                            )
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            ExportFormat.entries.forEach { format ->
                                DropdownMenuItem(
                                    text = { Text("${format.name} (${format.extension})") },
                                    onClick = {
                                        showExportMenu = false
                                        uiState.report?.let { report ->
                                            exportReport(context, report, format)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "生成报告失败",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.error ?: "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadReport(meetingId) }) {
                            Text("重试")
                        }
                    }
                }
                uiState.report != null -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 报告内容
                        ReportContent(
                            report = uiState.report!!,
                            isGenerating = uiState.isGenerating,
                            onRegenerate = { viewModel.regenerateReport(meetingId) },
                            onContinueRecording = { onContinueRecording(meetingId) },
                            transcriptText = uiState.transcriptText,
                            showTranscript = uiState.showTranscript,
                            onToggleTranscript = viewModel::toggleTranscript,
                            onExportTranscript = { text -> exportTranscript(context, text, documentTitle) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(bottom = if (showChatPanel) 0.dp else 0.dp)
                        )

                        // 聊天面板
                        if (showChatPanel) {
                            ChatPanel(
                                messages = uiState.chatMessages,
                                input = uiState.chatInput,
                                isLoading = uiState.isChatLoading,
                                error = uiState.chatError,
                                onInputChange = viewModel::updateChatInput,
                                onSend = viewModel::sendMessage,
                                onClear = viewModel::clearChat,
                                onMinimize = { showChatPanel = false },
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        text = "暂无${documentTitle}",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportContent(
    report: Report,
    isGenerating: Boolean,
    onRegenerate: () -> Unit,
    onContinueRecording: () -> Unit,
    transcriptText: String = "",
    showTranscript: Boolean = false,
    onToggleTranscript: () -> Unit = {},
    onExportTranscript: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ReportHeaderCard(
                report = report,
                onRegenerate = onRegenerate,
                onContinueRecording = onContinueRecording
            )

            if (transcriptText.isNotBlank()) {
                TranscriptSectionCard(
                    transcriptText = transcriptText,
                    showTranscript = showTranscript,
                    onToggle = onToggleTranscript,
                    onExport = { onExportTranscript(transcriptText) }
                )
            }

            if (report.rawContent.isNotBlank()) {
                MarkdownReportCard(content = report.rawContent)
            } else {
                StructuredReportContent(report = report)
            }
        }

        // 重新生成遮罩
        if (isGenerating) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("正在重新生成...")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportHeaderCard(
    report: Report,
    onRegenerate: () -> Unit,
    onContinueRecording: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TaskAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "已完成",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = report.templateName.ifBlank { "会议纪要" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "生成时间 ${formatReportTime(report.generatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onContinueRecording,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("继续录音")
                }
                Button(
                    onClick = onRegenerate,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重新生成")
                }
            }
        }
    }
}

@Composable
private fun MarkdownReportCard(content: String) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "正文",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            content.lines().forEach { line ->
                MarkdownLine(line)
            }
        }
    }
}

@Composable
private fun MarkdownLine(line: String) {
    val trimmed = line.trim()
    when {
        trimmed.startsWith("# ") -> Text(
            text = trimmed.removePrefix("# ").trim(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )
        trimmed.startsWith("## ") -> Text(
            text = trimmed.removePrefix("## ").trim(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp)
        )
        trimmed.startsWith("### ") -> Text(
            text = trimmed.removePrefix("### ").trim(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp)
        )
        trimmed.startsWith("|") -> Text(
            text = trimmed,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
        trimmed.isBlank() -> Spacer(modifier = Modifier.height(4.dp))
        trimmed == "---" -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        else -> Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StructuredReportContent(report: Report) {
    SectionCard(title = "会议概述") {
        Text(
            text = report.summary.ifEmpty { "暂无概述" },
            style = MaterialTheme.typography.bodyMedium
        )
    }

    if (report.keyPoints.isNotEmpty()) {
        SectionCard(title = "关键要点") {
            report.keyPoints.forEachIndexed { index, point ->
                Text(
                    text = "${index + 1}. $point",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }

    if (report.decisions.isNotEmpty()) {
        SectionCard(title = "决策事项") {
            report.decisions.forEach { decision ->
                Text(
                    text = "- $decision",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }

    if (report.tasks.isNotEmpty()) {
        SectionCard(title = "待办任务") {
            report.tasks.forEach { task ->
                Text(
                    text = "- ${task.content}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }

    if (report.actionItems.isNotEmpty()) {
        SectionCard(title = "行动项") {
            report.actionItems.forEach { item ->
                Text(
                    text = "- $item",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun TranscriptSectionCard(
    transcriptText: String,
    showTranscript: Boolean,
    onToggle: () -> Unit,
    onExport: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "转写原文",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showTranscript) {
                        FilledTonalButton(onClick = onExport) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("导出 TXT", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    TextButton(onClick = onToggle) {
                        Text(
                            if (showTranscript) "收起" else "查看",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            if (showTranscript) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = transcriptText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatPanel(
    messages: List<ChatMessageUi>,
    input: String,
    isLoading: Boolean,
    error: String?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "润色对话",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = onClear) {
                        Text("清空", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "收起",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 消息列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageItem(message = message)
                }
                if (isLoading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("思考中...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // 错误提示
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // 输入区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = { Text("输入润色要求...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onSend,
                    enabled = input.isNotBlank() && !isLoading
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送"
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageItem(message: ChatMessageUi) {
    val isUser = message.role == "user"
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = backgroundColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = if (isUser) "我" else "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun exportReport(context: Context, report: Report, format: ExportFormat) {
    try {
        val content = when (format) {
            ExportFormat.MARKDOWN -> ReportExporter.exportToMarkdown(report)
            ExportFormat.TXT -> ReportExporter.exportToText(report)
            ExportFormat.DOC, ExportFormat.PDF ->
                throw UnsupportedOperationException("${format.name} 导出暂不支持，请使用 Markdown 或 TXT 格式")
        }
        val fileName = "meeting_report_${System.currentTimeMillis()}.${format.extension}"
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val exportFile = File(exportDir, fileName)
        exportFile.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, report.documentTitle())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享${report.documentTitle()}"))
    } catch (e: Exception) {
        Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun exportTranscript(context: Context, transcriptText: String, title: String) {
    try {
        val fileName = "transcript_${title}_${System.currentTimeMillis()}.txt"
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val exportFile = File(exportDir, fileName)
        exportFile.writeText(transcriptText, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "转写文本 - $title")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享转写文本"))
    } catch (e: Exception) {
        Toast.makeText(context, "导出转写失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun formatReportTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun Report.documentTitle(): String =
    if (templateName.contains("施工日志")) "施工日志" else "会议纪要"
