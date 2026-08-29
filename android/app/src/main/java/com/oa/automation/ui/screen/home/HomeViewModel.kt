package com.oa.automation.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.application.usecase.StartRecordingUseCase
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingOrigin
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ScheduledMeeting
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.domain.repository.ScheduledMeetingRepository
import com.oa.automation.infrastructure.background.BackgroundTaskScheduler
import com.oa.automation.infrastructure.notification.ScheduledMeetingNotificationScheduler
import com.oa.automation.infrastructure.service.RecordingSessionController
import com.oa.automation.infrastructure.service.RecordingSessionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
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

enum class HomeLaunchAction {
    STANDARD,
    START_RECORDING,
    OPEN_IMPORT
}

internal fun HomeLaunchAction.toMeetingOrigin(): MeetingOrigin = when (this) {
    HomeLaunchAction.OPEN_IMPORT -> MeetingOrigin.FILE_IMPORT
    HomeLaunchAction.STANDARD,
    HomeLaunchAction.START_RECORDING -> MeetingOrigin.QUICK
}

internal fun Meeting.resumeLaunchAction(): HomeLaunchAction = when (origin) {
    MeetingOrigin.FILE_IMPORT -> HomeLaunchAction.OPEN_IMPORT
    MeetingOrigin.QUICK,
    MeetingOrigin.SCHEDULED -> HomeLaunchAction.STANDARD
}

data class PendingMeetingNavigation(
    val meetingId: String,
    val action: HomeLaunchAction
)

data class ActiveRecordingSummary(
    val meetingId: String,
    val meetingTitle: String,
    val isPaused: Boolean
)

data class HomeUiState(
    val meetings: List<MeetingWithReport> = emptyList(),
    val scheduledMeetings: List<ScheduledMeeting> = emptyList(),
    val reportTemplates: List<PresetReportTemplate> = emptyList(),
    val activeRecording: ActiveRecordingSummary? = null,
    val showActiveRecordingNotice: Boolean = false,
    val isLoading: Boolean = false,
    val configLoaded: Boolean = false,
    val regeneratingMeetingId: String? = null,
    val pendingNavigation: PendingMeetingNavigation? = null,
    val message: String? = null,
    val editingMeetingId: String? = null,
    val editingTitle: String = "",
    val displayName: String = "",
    val seenNotificationEvents: Set<String> = emptySet(),
    val hasUnreadNotifications: Boolean = false
)

class HomeViewModel(
    private val startRecordingUseCase: StartRecordingUseCase,
    private val meetingRepository: MeetingRepository,
    private val reportRepository: ReportRepository,
    private val scheduledMeetingRepository: ScheduledMeetingRepository,
    private val configDataStore: ConfigDataStore,
    private val taskScheduler: BackgroundTaskScheduler,
    private val recordingController: RecordingSessionController,
    private val scheduledMeetingScheduler: ScheduledMeetingNotificationScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(reportTemplates = configDataStore.loadPresetTemplates()) }
        viewModelScope.launch {
            configDataStore.authSessionFlow.collect { session ->
                _uiState.update {
                    it.copy(
                        displayName = session?.user?.displayName.orEmpty()
                            .ifBlank { session?.user?.username.orEmpty() }
                    )
                }
            }
        }
        // Load meetings
        viewModelScope.launch {
            combine(
                meetingRepository.getAllMeetingsFlow(),
                reportRepository.getAllReportsFlow()
            ) { meetings, reports ->
                meetingsWithReports(meetings, reports.map { it.meetingId }.toSet())
            }.collect { meetingsWithReport ->
                applyMeetings(meetingsWithReport)
            }
        }
        viewModelScope.launch {
            configDataStore.seenNotificationEventsFlow.collect { seenEvents ->
                _uiState.update { state ->
                    state.copy(
                        seenNotificationEvents = seenEvents,
                        hasUnreadNotifications = notificationEvents(state.meetings)
                            .any { it !in seenEvents }
                    )
                }
            }
        }
        // Load config
        viewModelScope.launch {
            configDataStore.appConfigFlow.collect { _ ->
                _uiState.update { it.copy(configLoaded = true) }
            }
        }
        viewModelScope.launch {
            scheduledMeetingRepository.observeUpcoming().collect { scheduled ->
                _uiState.update { it.copy(scheduledMeetings = scheduled) }
            }
        }
        viewModelScope.launch {
            recordingController.state.collect { session ->
                val activeRecording = session.toActiveRecordingSummary()
                _uiState.update { state ->
                    state.copy(
                        activeRecording = activeRecording,
                        showActiveRecordingNotice = state.showActiveRecordingNotice && activeRecording != null
                    )
                }
            }
        }
    }

    /** Refreshes the list after returning from recording or notifications. */
    fun refreshMeetings() {
        viewModelScope.launch {
            runCatching {
                val meetings = meetingRepository.getAllMeetings()
                val reportIds = reportRepository.getAllReportsFlow().value
                    .map { it.meetingId }
                    .toSet()
                meetingsWithReports(meetings, reportIds)
            }.onSuccess(::applyMeetings)
        }
    }

    private fun applyMeetings(meetings: List<MeetingWithReport>) {
        _uiState.update { state ->
            state.copy(
                meetings = meetings,
                hasUnreadNotifications = notificationEvents(meetings)
                    .any { it !in state.seenNotificationEvents }
            )
        }
    }

    fun startNewMeeting(
        title: String,
        action: HomeLaunchAction = HomeLaunchAction.STANDARD
    ) {
        if (action != HomeLaunchAction.OPEN_IMPORT &&
            recordingController.state.value.blocksNewRecording()
        ) {
            _uiState.update { it.copy(showActiveRecordingNotice = true) }
            return
        }
        viewModelScope.launch {
            val result = startRecordingUseCase(title, action.toMeetingOrigin())
            result
                .onSuccess { meeting ->
                    _uiState.update {
                        it.copy(pendingNavigation = PendingMeetingNavigation(meeting.id, action))
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(message = "创建会议失败: ${error.message}") }
                }
        }
    }

    fun dismissActiveRecordingNotice() {
        _uiState.update { it.copy(showActiveRecordingNotice = false) }
    }

    fun openActiveRecording() {
        val active = _uiState.value.activeRecording ?: return
        val action = _uiState.value.meetings
            .firstOrNull { it.meeting.id == active.meetingId }
            ?.meeting
            ?.resumeLaunchAction()
            ?: HomeLaunchAction.STANDARD
        _uiState.update {
            it.copy(
                showActiveRecordingNotice = false,
                pendingNavigation = PendingMeetingNavigation(active.meetingId, action)
            )
        }
    }

    fun suggestMeetingTitle(prefix: String = "会议记录"): String {
        val timeLabel = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        return "$prefix $timeLabel"
    }

    fun suggestScheduledTime(): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.HOUR_OF_DAY, 1)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun scheduleMeeting(
        title: String,
        scheduledAt: Long,
        reminderMinutes: Int,
        templateName: String?
    ) {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) {
            _uiState.update { it.copy(message = "会议标题不能为空") }
            return
        }
        if (scheduledAt <= System.currentTimeMillis()) {
            _uiState.update { it.copy(message = "预定时间需要晚于当前时间") }
            return
        }
        viewModelScope.launch {
            val scheduled = ScheduledMeeting(
                id = UUID.randomUUID().toString(),
                title = normalizedTitle,
                scheduledAt = scheduledAt,
                reminderMinutes = reminderMinutes,
                templateName = templateName
            )
            scheduledMeetingRepository.save(scheduled)
                .onSuccess {
                    scheduledMeetingScheduler.schedule(scheduled)
                    _uiState.update { it.copy(message = "预定会议已保存，提醒已安排") }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(message = "保存预定会议失败: ${error.message}") }
                }
        }
    }

    fun deleteScheduledMeeting(id: String) {
        viewModelScope.launch {
            scheduledMeetingRepository.delete(id)
                .onSuccess {
                    scheduledMeetingScheduler.cancel(id)
                    _uiState.update { it.copy(message = "预定会议已取消") }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(message = "取消失败: ${error.message}") }
                }
        }
    }

    fun clearPendingMeeting() {
        _uiState.update { it.copy(pendingNavigation = null) }
    }

    fun deleteMeeting(meetingId: String) {
        val active = recordingController.state.value
        if (active.meetingId == meetingId && (active.isRecording || active.isStarting || active.isStopping)) {
            _uiState.update { it.copy(message = "请先结束当前会议录音") }
            return
        }
        viewModelScope.launch {
            // DAO transaction removes the report and transcripts with the meeting.
            meetingRepository.delete(meetingId)
                .onSuccess { _uiState.update { it.copy(message = "会议已删除") } }
                .onFailure { error ->
                    _uiState.update { it.copy(message = "删除失败: ${error.message}") }
                }
        }
    }

    fun clearAllMeetings() {
        val active = recordingController.state.value
        if (active.isRecording || active.isStarting || active.isStopping) {
            _uiState.update { it.copy(message = "请先结束当前会议录音，再清空记录") }
            return
        }
        val meetingIds = _uiState.value.meetings.map { it.meeting.id }
        if (meetingIds.isEmpty()) return
        viewModelScope.launch {
            val failure = meetingIds.firstNotNullOfOrNull { id ->
                meetingRepository.delete(id).exceptionOrNull()
            }
            _uiState.update {
                it.copy(
                    message = failure?.let { error -> "清空失败: ${error.message}" }
                        ?: "会议记录已清空"
                )
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

    fun markNotificationsRead() {
        val currentEvents = notificationEvents(_uiState.value.meetings)
        _uiState.update {
            it.copy(
                seenNotificationEvents = currentEvents,
                hasUnreadNotifications = false
            )
        }
        viewModelScope.launch {
            configDataStore.saveSeenNotificationEvents(currentEvents)
        }
    }

    fun markNotificationRead(meetingId: String, hasReport: Boolean) {
        if (meetingId.isBlank()) return
        val event = "$meetingId:${if (hasReport) "report" else "meeting"}"
        val updatedEvents = _uiState.value.seenNotificationEvents + event
        _uiState.update {
            it.copy(
                seenNotificationEvents = updatedEvents,
                hasUnreadNotifications = notificationEvents(it.meetings)
                    .any { notification -> notification !in updatedEvents }
            )
        }
        viewModelScope.launch {
            configDataStore.saveSeenNotificationEvents(updatedEvents)
        }
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

private fun RecordingSessionState.toActiveRecordingSummary(): ActiveRecordingSummary? {
    val isActive = isRecording || isStarting || isStopping
    if (!isActive || meetingId.isBlank()) return null
    return ActiveRecordingSummary(
        meetingId = meetingId,
        meetingTitle = meetingTitle.ifBlank { "当前记录" },
        isPaused = isPaused
    )
}

/** A paused session has released the microphone and may be switched away. */
internal fun RecordingSessionState.blocksNewRecording(): Boolean =
    isStarting || isStopping || (isRecording && !isPaused)

private fun notificationEvents(meetings: List<MeetingWithReport>): Set<String> = meetings
    .mapTo(linkedSetOf()) { item ->
        "${item.meeting.id}:${if (item.hasReport) "report" else "meeting"}"
    }

internal fun meetingsWithReports(
    meetings: List<Meeting>,
    reportMeetingIds: Set<String>
): List<MeetingWithReport> = meetings
    .sortedByDescending { it.createdAt }
    .map { meeting ->
        MeetingWithReport(
            meeting = meeting,
            hasReport = meeting.id in reportMeetingIds
        )
    }
