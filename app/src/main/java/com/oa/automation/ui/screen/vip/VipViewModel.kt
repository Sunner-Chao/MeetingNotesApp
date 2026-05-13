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
    val constructionTemplate: PresetReportTemplate? = null,
    val activeTemplateName: String = "",
    val isApplying: Boolean = false,
    val isStarting: Boolean = false,
    val pendingMeetingId: String? = null,
    val message: String? = null
)

class VipViewModel(
    private val configDataStore: ConfigDataStore,
    private val startRecordingUseCase: StartRecordingUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(VipUiState())
    val uiState: StateFlow<VipUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val template = configDataStore.loadPresetTemplates().firstOrNull { it.name == CONSTRUCTION_TEMPLATE_NAME }
            configDataStore.appConfigFlow.collect { appConfig ->
                _uiState.update {
                    it.copy(
                        constructionTemplate = template,
                        activeTemplateName = appConfig.reportTemplateConfig.selectedName
                    )
                }
            }
        }
    }

    fun applyConstructionTemplate() {
        viewModelScope.launch {
            val template = _uiState.value.constructionTemplate ?: return@launch
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
                    message = "施工日志模板已启用"
                )
            }
        }
    }

    fun startConstructionLog() {
        viewModelScope.launch {
            val template = _uiState.value.constructionTemplate ?: return@launch
            _uiState.update { it.copy(isStarting = true, message = null) }
            configDataStore.updateReportTemplate(
                ReportTemplateConfig(
                    selectedName = template.name,
                    content = template.content,
                    isCustom = false
                )
            )
            val dateLabel = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            startRecordingUseCase("施工日志 $dateLabel")
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
                            message = "新建施工日志失败: ${error.message}"
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

    companion object {
        private const val CONSTRUCTION_TEMPLATE_NAME = "施工日志"
    }
}
