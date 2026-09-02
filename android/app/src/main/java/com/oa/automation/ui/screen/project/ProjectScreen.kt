package com.oa.automation.ui.screen.project

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.Project
import com.oa.automation.domain.model.ProjectStatus
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProjectViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    BackHandler(enabled = state.selectedProject != null) { viewModel.selectProject(null) }

    if (showCreate) {
        ProjectNameDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name -> showCreate = false; viewModel.createProject(name) }
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除项目") },
            text = { Text("项目会被标记为已删除，原会议和纪要不会被删除。") },
            confirmButton = {
                Button(onClick = { showDelete = false; viewModel.deleteSelected() }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }
    if (showLink) {
        LinkMeetingDialog(
            meetingTitles = state.meetings
                .filterNot { meeting -> state.meetingLinks.any { it.meetingId == meeting.id } },
            onDismiss = { showLink = false },
            onSelect = { meetingId -> showLink = false; viewModel.linkMeeting(meetingId) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.selectedProject?.name ?: "项目") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.selectedProject == null) onNavigateBack() else viewModel.selectProject(null)
                    }) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (state.selectedProject == null) {
                        IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, "新建项目") }
                    } else {
                        IconButton(onClick = { showLink = true }) { Icon(Icons.Default.Link, "关联会议") }
                        IconButton(onClick = viewModel::archiveSelected) { Icon(Icons.Default.Archive, "归档或恢复项目") }
                        IconButton(onClick = { showDelete = true }) { Icon(Icons.Default.DeleteOutline, "删除项目") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (state.selectedProject == null) {
            ProjectList(state.projects, state.isLoading, onSelect = viewModel::selectProject, modifier = Modifier.padding(padding))
        } else {
            ProjectDetail(state = state, onRemoveMeeting = viewModel::removeMeeting, onRefreshSnapshot = viewModel::refreshSnapshot, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun ProjectList(
    projects: List<Project>,
    isLoading: Boolean,
    onSelect: (Project) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (isLoading) item { Text("正在加载项目…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (projects.isEmpty() && !isLoading) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("还没有项目", fontWeight = FontWeight.SemiBold)
                    Text("从右上角新建项目，再关联已有会议。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(projects, key = { it.id }) { project ->
            Card(onClick = { onSelect(project) }, shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(project.name, fontWeight = FontWeight.SemiBold)
                        Text(if (project.status == ProjectStatus.ARCHIVED) "已归档" else "进行中", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectDetail(
    state: ProjectUiState,
    onRemoveMeeting: (String) -> Unit,
    onRefreshSnapshot: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("项目概览", fontWeight = FontWeight.SemiBold)
                    val snapshot = state.snapshot
                    Text(
                        if (snapshot == null) "关联会议后刷新概览" else
                            "${snapshot.sourceMeetingCount} 场会议 · ${snapshot.openTaskCount} 项待办 · ${snapshot.openRiskCount} 项风险 · ${snapshot.pendingDecisionCount} 项待确认决策"
                    )
                    FilledTonalButton(onClick = onRefreshSnapshot) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("刷新概览")
                    }
                }
            }
        }
        item {
            Text("关联会议", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (state.meetingLinks.isEmpty()) {
            item { Text("尚未关联会议", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(state.meetingLinks, key = { it.meetingId }) { link ->
                val meeting = state.meetings.firstOrNull { it.id == link.meetingId }
                Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(meeting?.title ?: "已删除的会议", modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRemoveMeeting(link.meetingId) }) { Text("解除") }
                    }
                }
            }
        }
        item {
            Text("来源事项", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("任务 ${state.tasks.size} · 风险 ${state.risks.size} · 决策 ${state.decisions.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProjectNameDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建项目") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("项目名称") }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun LinkMeetingDialog(
    meetingTitles: List<com.oa.automation.domain.model.Meeting>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关联会议") },
        text = {
            if (meetingTitles.isEmpty()) Text("没有可关联的新会议。")
            else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                meetingTitles.forEach { meeting ->
                    TextButton(onClick = { onSelect(meeting.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(meeting.title, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
