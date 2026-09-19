package com.oa.automation.infrastructure.llm

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ReportTemplateConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * LLM Engine Facade
 * Provides a unified interface for LLM operations, delegating to the appropriate engine
 * based on the current configuration.
 *
 * This class replaces the old stub implementation with real LLM engine integration.
 */
class LLMEngine(
    private val configDataStore: ConfigDataStore
) {

    private val gson = com.google.gson.Gson()

    /**
     * Generate a meeting report from transcript
     * Uses the configured LLM engine (Ollama or Cloud API)
     */
    suspend fun generateReport(
        transcript: String,
        attachments: List<AgentAttachment> = emptyList(),
        meetingId: String? = null,
        usageKey: String? = null,
        meetingTemplateName: String? = null
    ): ReportData {
        val appConfig = configDataStore.appConfigFlow.first()
        val config = appConfig.llmConfig
        val accountAccessToken = configDataStore.authSessionFlow.first()?.accessToken
        val engine = LLMReportEngine.fromConfig(config, accountAccessToken)
        val template = resolveMeetingReportTemplate(
            meetingTemplateName,
            appConfig.reportTemplateConfig,
            configDataStore.loadPresetTemplates() + configDataStore.loadVipTemplates()
        )

        val usageContext = meetingId?.takeIf { it.isNotBlank() }?.let {
            AgentUsageContext(it, usageKey?.takeIf(String::isNotBlank) ?: java.util.UUID.randomUUID().toString())
        }
        return engine.generateReport(
            transcript,
            template,
            attachments,
            usageContext
        )
            .getOrElse { error ->
                if (error is CancellationException) throw error
                throw error
            }
    }

    /**
     * Chat with LLM for refining reports
     * @param messages List of chat messages (system, user, assistant)
     * @return LLM response text
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        attachments: List<AgentAttachment> = emptyList()
    ): Result<String> {
        val config = configDataStore.appConfigFlow.first().llmConfig
        val accountAccessToken = configDataStore.authSessionFlow.first()?.accessToken
        val engine = LLMReportEngine.fromConfig(config, accountAccessToken)
        return engine.chat(messages, attachments)
    }

    /**
     * Generate report synchronously (blocking)
     * For use in non-suspend contexts
     */
    fun generateReportSync(transcript: String): ReportData {
        return runBlocking { generateReport(transcript) }
    }
}

/** A background job belongs to its meeting, independently of other screens' selection. */
internal fun resolveMeetingReportTemplate(
    meetingTemplateName: String?,
    current: ReportTemplateConfig,
    presets: List<PresetReportTemplate>
): ReportTemplateConfig {
    val name = meetingTemplateName?.takeIf { it.isNotBlank() } ?: return current
    if (name == current.selectedName) return current
    val preset = presets.firstOrNull { it.name == name }
        ?: error("此会议模板暂不可用，请重新选择模板")
    return ReportTemplateConfig(selectedName = preset.name, content = preset.content)
}
