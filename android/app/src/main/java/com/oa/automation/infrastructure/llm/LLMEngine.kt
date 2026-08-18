package com.oa.automation.infrastructure.llm

import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.LLMConfig
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
        usageKey: String? = null
    ): ReportData {
        val appConfig = configDataStore.appConfigFlow.first()
        val config = appConfig.llmConfig
        val engine = LLMReportEngine.fromConfig(config)

        val usageContext = meetingId?.takeIf { it.isNotBlank() }?.let {
            AgentUsageContext(it, usageKey?.takeIf(String::isNotBlank) ?: java.util.UUID.randomUUID().toString())
        }
        return engine.generateReport(
            transcript,
            appConfig.reportTemplateConfig,
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
        val engine = LLMReportEngine.fromConfig(config)
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
