package com.oa.automation.ui.screen.vip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.application.usecase.StartRecordingUseCase
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ReportTemplateConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VipUiState(
    val templates: List<PresetReportTemplate> = emptyList(),
    val activeTemplateName: String = "",
    val activeTemplateType: VipTemplateType = VipTemplateType.ENGINEERING,
    val isApplying: Boolean = false,
    val isStarting: Boolean = false,
    val pendingMeetingId: String? = null,
    val message: String? = null
)

enum class VipTemplateType(val displayName: String, val templateName: String) {
    ENGINEERING("工程行业施工日志", "工程行业施工日志"),
    DESIGN("建筑专业设计日志", "建筑专业设计日志")
}

class VipViewModel(
    private val configDataStore: ConfigDataStore,
    private val startRecordingUseCase: StartRecordingUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(VipUiState())
    val uiState: StateFlow<VipUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // 加载VIP专用模板
            val templates = configDataStore.loadVipTemplates()
            configDataStore.appConfigFlow.collect { appConfig ->
                val activeName = appConfig.reportTemplateConfig.selectedName
                val activeType = when (activeName) {
                    VipTemplateType.ENGINEERING.templateName -> VipTemplateType.ENGINEERING
                    VipTemplateType.DESIGN.templateName -> VipTemplateType.DESIGN
                    else -> _uiState.value.activeTemplateType
                }
                _uiState.update {
                    it.copy(
                        templates = templates,
                        activeTemplateName = activeName,
                        activeTemplateType = activeType
                    )
                }
            }
        }
    }

    fun selectTemplate(type: VipTemplateType) {
        _uiState.update { it.copy(activeTemplateType = type) }
    }

    /**
     * 切换模板启用/禁用状态
     */
    fun toggleTemplate() {
        viewModelScope.launch {
            val currentState = _uiState.value
            val isSelectedActive = currentState.activeTemplateName == currentState.activeTemplateType.templateName

            if (isSelectedActive) {
                // 禁用：切换回默认团队版模板
                val defaultTemplate = configDataStore.loadPresetTemplates().firstOrNull()
                if (defaultTemplate != null) {
                    _uiState.update { it.copy(isApplying = true, message = null) }
                    configDataStore.updateReportTemplate(
                        ReportTemplateConfig(
                            selectedName = defaultTemplate.name,
                            content = defaultTemplate.content,
                            isCustom = false
                        )
                    )
                    _uiState.update {
                        it.copy(
                            isApplying = false,
                            activeTemplateName = defaultTemplate.name,
                            message = "已禁用专业模板"
                        )
                    }
                }
            } else {
                // 启用：切换到选中的专业模板
                val template = currentState.templates.find {
                    it.name == currentState.activeTemplateType.templateName
                } ?: return@launch

                _uiState.update { it.copy(isApplying = true, message = null) }
                configDataStore.updateReportTemplate(
                    ReportTemplateConfig(
                        selectedName = template.name,
                        content = template.content,
                        isCustom = false
                    )
                )
                _uiState.update {
                    it.copy(
                        isApplying = false,
                        activeTemplateName = template.name,
                        message = "${currentState.activeTemplateType.displayName}已启用"
                    )
                }
            }
        }
    }

    fun startRecording() {
        viewModelScope.launch {
            val type = _uiState.value.activeTemplateType
            val template = _uiState.value.templates.find { it.name == type.templateName } ?: return@launch
            _uiState.update { it.copy(isStarting = true, message = null) }
            configDataStore.updateReportTemplate(
                ReportTemplateConfig(
                    selectedName = template.name,
                    content = template.content,
                    isCustom = false
                )
            )
            val dateLabel = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val meetingTitle = "${type.displayName} $dateLabel"
            startRecordingUseCase(meetingTitle)
                .onSuccess { meeting ->
                    _uiState.update {
                        it.copy(
                            isStarting = false,
                            activeTemplateName = template.name,
                            pendingMeetingId = meeting.id
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isStarting = false,
                            message = "新建记录失败: ${error.message}"
                        )
                    }
                }
        }
    }

    fun clearPendingNavigation() {
        _uiState.update { it.copy(pendingMeetingId = null) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
