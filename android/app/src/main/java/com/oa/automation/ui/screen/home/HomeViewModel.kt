package com.oa.automation.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.application.usecase.StartRecordingUseCase
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.infrastructure.background.BackgroundTaskScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MeetingWithReport(
    val meeting: Meeting,
    val hasReport: Boolean
)

data class HomeUiState(
    val meetings: List<MeetingWithReport> = emptyList(),
    val isLoading: Boolean = false,
    val configLoaded: Boolean = false,
    val regeneratingMeetingId: String? = null,
    val pendingMeetingId: String? = null,
    val message: String? = null,
    val editingMeetingId: String? = null,
    val editingTitle: String = ""
)

class HomeViewModel(
    private val startRecordingUseCase: StartRecordingUseCase,
    private val meetingRepository: MeetingRepository,
    private val reportRepository: ReportRepository,
    private val configDataStore: ConfigDataStore,
    private val taskScheduler: BackgroundTaskScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Load meetings
        viewModelScope.launch {
            combine(
                meetingRepository.getAllMeetingsFlow(),
                reportRepository.getAllReportsFlow()
            ) { meetings, reports ->
                val sortedMeetings = meetings.sortedByDescending { m -> m.createdAt }
                val reportMeetingIds = reports.map { it.meetingId }.toSet()
                val meetingsWithReport = sortedMeetings.map { meeting ->
                    MeetingWithReport(
                        meeting = meeting,
                        hasReport = meeting.id in reportMeetingIds
                    )
                }
                meetingsWithReport
            }.collect { meetingsWithReport ->
                _uiState.update { it.copy(meetings = meetingsWithReport) }
            }
        }
        // Load config
        viewModelScope.launch {
            configDataStore.appConfigFlow.collect { _ ->
                _uiState.update { it.copy(configLoaded = true) }
            }
        }
    }

    fun startNewMeeting(title: String) {
        viewModelScope.launch {
            val result = startRecordingUseCase(title)
            result
                .onSuccess { meeting ->
                    _uiState.update { it.copy(pendingMeetingId = meeting.id) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(message = "创建会议失败: ${error.message}") }
                }
        }
    }

    fun suggestMeetingTitle(): String {
        val timeLabel = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        return "会议记录 $timeLabel"
    }

    fun clearPendingMeeting() {
        _uiState.update { it.copy(pendingMeetingId = null) }
    }

    fun deleteMeeting(meetingId: String) {
        viewModelScope.launch {
            // DAO transaction removes the report and transcripts with the meeting.
            meetingRepository.delete(meetingId)
                .onSuccess { _uiState.update { it.copy(message = "会议已删除") } }
                .onFailure { error ->
                    _uiState.update { it.copy(message = "删除失败: ${error.message}") }
                }
        }
    }

    fun regenerateReport(meetingId: String) {
        taskScheduler.enqueueReport(meetingId)
        _uiState.update {
            it.copy(regeneratingMeetingId = null, message = "会议纪要已加入后台生成队列")
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun startEditTitle(meetingId: String, currentTitle: String) {
        _uiState.update { it.copy(editingMeetingId = meetingId, editingTitle = currentTitle) }
    }

    fun onTitleEditChange(newTitle: String) {
        _uiState.update { it.copy(editingTitle = newTitle) }
    }

    fun saveTitle() {
        val meetingId = _uiState.value.editingMeetingId ?: return
        val newTitle = _uiState.value.editingTitle.trim()
        if (newTitle.isBlank()) {
            _uiState.update { it.copy(message = "会议名称不能为空", editingMeetingId = null) }
            return
        }
        viewModelScope.launch {
            meetingRepository.updateTitle(meetingId, newTitle)
                .onSuccess {
                    _uiState.update {
                        it.copy(message = "会议名称已更新", editingMeetingId = null, editingTitle = "")
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(message = "更新失败: ${error.message}") }
                }
        }
    }

    fun cancelEditTitle() {
        _uiState.update { it.copy(editingMeetingId = null, editingTitle = "") }
    }
}
