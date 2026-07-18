package com.oa.automation.domain.model

import com.oa.automation.BuildConfig

/**
 * LLM Engine Types
 * - P0: Local Ollama (priority, offline support)
 * - P1: Cloud API (backup)
 */
enum class LLMEngineType(val displayName: String) {
    AGENT_GATEWAY("云端 Agent"),
    LOCAL_OLLAMA("本地 Ollama"),
    CLOUD_API("云端大模型")
}

enum class AgentProvider(val displayName: String, val requestValue: String) {
    CODEX_CLI("Codex CLI", "codex-cli"),
    CLAUDE_CLI("Claude CLI", "claude-cli")
}

enum class CloudApiFormat(val displayName: String) {
    OPENAI_COMPAT("OpenAI Compatible"),
    CLAUDE_MESSAGES("Claude Messages")
}

enum class ReportTemplate(val displayName: String) {
    STANDARD("标准会议纪要"),
    ACTION_FOCUSED("行动项优先"),
    EXECUTIVE_BRIEF("管理层简报")
}

data class PresetReportTemplate(
    val name: String,
    val content: String
)

data class ReportTemplateConfig(
    val selectedName: String = "孔爵团队版表格会议纪要",
    val content: String = "",
    val isCustom: Boolean = false
)

/**
 * LLM Engine Configuration
 */
data class LLMConfig(
    val engineType: LLMEngineType = LLMEngineType.AGENT_GATEWAY,
    val agentEndpoint: String = BuildConfig.DEFAULT_AGENT_ENDPOINT,
    val agentAccessToken: String? = null,
    val agentProvider: AgentProvider = AgentProvider.CODEX_CLI,
    val localEndpoint: String = "http://localhost:11434",
    val localModel: String = "qwen2.5:7b",
    val cloudEndpoint: String? = BuildConfig.DEFAULT_LLM_CLOUD_ENDPOINT.takeIf { it.isNotBlank() },
    val cloudApiKey: String? = BuildConfig.DEFAULT_LLM_CLOUD_API_KEY.takeIf { it.isNotBlank() },
    val cloudModel: String? = BuildConfig.DEFAULT_LLM_CLOUD_MODEL.takeIf { it.isNotBlank() },
    val cloudApiFormat: CloudApiFormat = CloudApiFormat.CLAUDE_MESSAGES,
    val reportTemplate: ReportTemplate = ReportTemplate.STANDARD
) {
    companion object {
        val DEFAULT = LLMConfig()
    }
}

/**
 * Complete App Configuration combining STT and LLM settings
 */
data class AppConfig(
    val sttConfig: STTConfig = STTConfig.DEFAULT,
    val llmConfig: LLMConfig = LLMConfig.DEFAULT,
    val reportTemplateConfig: ReportTemplateConfig = ReportTemplateConfig()
) {
    companion object {
        val DEFAULT = AppConfig()
    }
}
