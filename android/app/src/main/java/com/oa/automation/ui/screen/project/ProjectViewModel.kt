package com.oa.automation.ui.screen.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.Project
import com.oa.automation.domain.model.ProjectAggregateSnapshot
import com.oa.automation.domain.model.ProjectDecisionRef
import com.oa.automation.domain.model.ProjectMeetingLink
import com.oa.automation.domain.model.ProjectRiskRef
import com.oa.automation.domain.model.ProjectStatus
import com.oa.automation.domain.model.ProjectTaskRef
import com.oa.automation.domain.model.buildProjectAggregateSnapshot
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ProjectRepository
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectUiState(
    val projects: List<Project> = emptyList(),
    val meetings: List<Meeting> = emptyList(),
    val selectedProject: Project? = null,
    val meetingLinks: List<ProjectMeetingLink> = emptyList(),
    val tasks: List<ProjectTaskRef> = emptyList(),
    val risks: List<ProjectRiskRef> = emptyList(),
    val decisions: List<ProjectDecisionRef> = emptyList(),
    val snapshot: ProjectAggregateSnapshot? = null,
    val isLoading: Boolean = false,
    val message: String? = null
)

class ProjectViewModel(
    private val projectRepository: ProjectRepository,
    private val meetingRepository: MeetingRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProjectUiState(isLoading = true))
    val uiState: StateFlow<ProjectUiState> = _uiState.asStateFlow()
    private var detailJob: Job? = null

    init {
        viewModelScope.launch {
            projectRepository.observeProjects().collect { projects ->
                _uiState.update { it.copy(projects = projects, isLoading = false) }
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(meetings = meetingRepository.getAllMeetings()) }
        }
    }

    fun selectProject(project: Project?) {
        detailJob?.cancel()
        _uiState.update {
            it.copy(
                selectedProject = project,
                meetingLinks = emptyList(),
                tasks = emptyList(),
                risks = emptyList(),
                decisions = emptyList(),
                snapshot = null
            )
        }
        if (project == null) return
        detailJob = viewModelScope.launch {
            launch { projectRepository.observeMeetingLinks(project.id).collect { links -> _uiState.update { it.copy(meetingLinks = links) } } }
            launch { projectRepository.observeTasks(project.id).collect { refs -> _uiState.update { it.copy(tasks = refs) } } }
            launch { projectRepository.observeRisks(project.id).collect { refs -> _uiState.update { it.copy(risks = refs) } } }
            launch { projectRepository.observeDecisions(project.id).collect { refs -> _uiState.update { it.copy(decisions = refs) } } }
            launch { projectRepository.observeLatestSnapshot(project.id).collect { snapshot -> _uiState.update { it.copy(snapshot = snapshot) } } }
        }
    }

    fun createProject(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank()) {
            _uiState.update { it.copy(message = "项目名称不能为空") }
            return
        }
        viewModelScope.launch {
            val project = Project(UUID.randomUUID().toString(), normalized)
            projectRepository.save(project)
                .onSuccess { selectProject(it) }
                .onFailure { error -> _uiState.update { it.copy(message = "创建项目失败: ${error.message}") } }
        }
    }

    fun archiveSelected() {
        val project = _uiState.value.selectedProject ?: return
        val archived = project.copy(
            status = if (project.status == ProjectStatus.ARCHIVED) ProjectStatus.ACTIVE else ProjectStatus.ARCHIVED,
            archivedAt = if (project.status == ProjectStatus.ARCHIVED) null else System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            projectRepository.save(archived).onSuccess { selectProject(it) }
                .onFailure { error -> _uiState.update { it.copy(message = "更新项目状态失败: ${error.message}") } }
        }
    }

    fun deleteSelected() {
        val project = _uiState.value.selectedProject ?: return
        viewModelScope.launch {
            projectRepository.delete(project.id)
                .onSuccess { selectProject(null) }
                .onFailure { error -> _uiState.update { it.copy(message = "删除项目失败: ${error.message}") } }
        }
    }

    fun linkMeeting(meetingId: String) {
        val project = _uiState.value.selectedProject ?: return
        if (_uiState.value.meetingLinks.any { it.meetingId == meetingId }) return
        viewModelScope.launch {
            projectRepository.linkMeeting(ProjectMeetingLink(project.id, meetingId))
                .onFailure { error -> _uiState.update { it.copy(message = "关联会议失败: ${error.message}") } }
        }
    }

    fun removeMeeting(meetingId: String) {
        val project = _uiState.value.selectedProject ?: return
        viewModelScope.launch {
            projectRepository.removeMeeting(project.id, meetingId)
                .onFailure { error -> _uiState.update { it.copy(message = "解除关联失败: ${error.message}") } }
        }
    }

    fun refreshSnapshot() {
        val project = _uiState.value.selectedProject ?: return
        val state = _uiState.value
        val snapshot = buildProjectAggregateSnapshot(
            snapshotId = "${project.id}-${System.currentTimeMillis()}",
            projectId = project.id,
            meetingLinks = state.meetingLinks,
            tasks = state.tasks,
            risks = state.risks,
            decisions = state.decisions
        )
        viewModelScope.launch {
            projectRepository.saveSnapshot(snapshot)
                .onFailure { error -> _uiState.update { it.copy(message = "更新项目概览失败: ${error.message}") } }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        detailJob?.cancel()
        super.onCleared()
    }
}
