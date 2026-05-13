package com.oa.automation.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.ui.component.MeetingCard
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToRecording: (String) -> Unit,
    onNavigateToReport: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit,
    onNavigateToVip: () -> Unit,
    viewModel: HomeViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var draftMeetingTitle by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    // 显示消息
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.meetings) {
        val latestMeetingWithReport = uiState.meetings.firstOrNull()
        if (latestMeetingWithReport != null && viewModel.pendingMeetingId != null) {
            onNavigateToRecording(latestMeetingWithReport.meeting.id)
            viewModel.clearPendingMeeting()
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建会议") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "先给这次会议起个名字，后续也可以继续修改。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = draftMeetingTitle,
                        onValueChange = { draftMeetingTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("会议名称") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalTitle = draftMeetingTitle.trim().ifBlank { viewModel.suggestMeetingTitle() }
                        viewModel.startNewMeeting(finalTitle)
                        showCreateDialog = false
                    }
                ) {
                    Text("开始录音")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Title editing dialog
    uiState.editingMeetingId?.let { _ ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelEditTitle() },
            title = { Text("修改会议名称") },
            text = {
                OutlinedTextField(
                    value = uiState.editingTitle,
                    onValueChange = { viewModel.onTitleEditChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("会议名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.saveTitle() }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelEditTitle() }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Meeting Notes") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = onNavigateToVip) {
                        Icon(
                            imageVector = Icons.Default.WorkspacePremium,
                            contentDescription = "VIP 专区"
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    draftMeetingTitle = viewModel.suggestMeetingTitle()
                    showCreateDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建会议"
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                OverviewCard(
                    meetingCount = uiState.meetings.size,
                    onOpenVip = onNavigateToVip,
                    onCreateMeeting = {
                        draftMeetingTitle = viewModel.suggestMeetingTitle()
                        showCreateDialog = true
                    }
                )
            }

            if (uiState.meetings.isEmpty()) {
                item {
                    EmptyMeetingState(
                        onCreateMeeting = {
                            draftMeetingTitle = viewModel.suggestMeetingTitle()
                            showCreateDialog = true
                        }
                    )
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "最近会议",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${uiState.meetings.size} 条",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(uiState.meetings) { meetingWithReport ->
                    MeetingCard(
                        meeting = meetingWithReport.meeting,
                        hasReport = meetingWithReport.hasReport,
                        isRegenerating = uiState.regeneratingMeetingId == meetingWithReport.meeting.id,
                        onClick = {
                            if (meetingWithReport.hasReport) {
                                onNavigateToReport(meetingWithReport.meeting.id)
                            } else {
                                onNavigateToRecording(meetingWithReport.meeting.id)
                            }
                        },
                        onReportClick = { onNavigateToReport(meetingWithReport.meeting.id) },
                        onContinueRecording = { onNavigateToRecording(meetingWithReport.meeting.id) },
                        onRegenerateReport = { viewModel.regenerateReport(meetingWithReport.meeting.id) },
                        onDelete = { viewModel.deleteMeeting(meetingWithReport.meeting.id) },
                        onEdit = { viewModel.startEditTitle(meetingWithReport.meeting.id, meetingWithReport.meeting.title) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(
    meetingCount: Int,
    onOpenVip: () -> Unit,
    onCreateMeeting: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column {
                    Text(
                        text = "会议助手",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "录音中分段预览，结束后再生成最终纪要。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = meetingCount.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "已创建会议",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onOpenVip) {
                        Icon(
                            imageVector = Icons.Default.WorkspacePremium,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("VIP")
                    }
                    Button(onClick = onCreateMeeting) {
                        Text("开始新会议")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMeetingState(
    onCreateMeeting: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "还没有会议记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "先输入会议名称，再开始录音。录音页会边录边给出分段转写预览。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onCreateMeeting) {
                Text("创建第一条会议")
            }
        }
    }
}
