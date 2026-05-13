package com.oa.automation.domain.model

/**
 * LLM Engine Types
 * - P0: Local Ollama (priority, offline support)
 * - P1: Cloud API (backup)
 */
enum class LLMEngineType(val displayName: String) {
    LOCAL_OLLAMA("本地 Ollama"),
    CLOUD_API("云端大模型")
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
    val selectedName: String = "通用会议纪要",
    val content: String = "",
    val isCustom: Boolean = false
)

/**
 * LLM Engine Configuration
 */
data class LLMConfig(
    val engineType: LLMEngineType = LLMEngineType.LOCAL_OLLAMA,
    val localEndpoint: String = "http://localhost:11434",
    val localModel: String = "qwen2.5:7b",
    val cloudEndpoint: String? = null,
    val cloudApiKey: String? = null,
    val cloudModel: String? = null,
    val cloudApiFormat: CloudApiFormat = CloudApiFormat.OPENAI_COMPAT,
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
