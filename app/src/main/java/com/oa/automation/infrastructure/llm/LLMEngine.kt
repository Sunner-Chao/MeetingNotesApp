package com.oa.automation.infrastructure.llm

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.LLMConfig
import kotlinx.coroutines.flow.first
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
    suspend fun generateReport(transcript: String): ReportData {
        val appConfig = configDataStore.appConfigFlow.first()
        val config = appConfig.llmConfig
        val engine = LLMReportEngine.fromConfig(config)

        return engine.generateReport(transcript, appConfig.reportTemplateConfig).getOrElse { error ->
            // Return a default error report
            ReportData(
                summary = "生成报告失败: ${error.message}",
                keyPoints = emptyList(),
                tasks = emptyList(),
                decisions = emptyList(),
                actionItems = emptyList(),
                templateName = appConfig.reportTemplateConfig.selectedName
            )
        }
    }

    /**
     * Chat with LLM for refining reports
     * @param messages List of chat messages (system, user, assistant)
     * @return LLM response text
     */
    suspend fun chat(messages: List<ChatMessage>): Result<String> {
        val config = configDataStore.appConfigFlow.first().llmConfig
        val engine = LLMReportEngine.fromConfig(config)
        return engine.chat(messages)
    }

    /**
     * Generate report synchronously (blocking)
     * For use in non-suspend contexts
     */
    fun generateReportSync(transcript: String): ReportData {
        return runBlocking { generateReport(transcript) }
    }
}
