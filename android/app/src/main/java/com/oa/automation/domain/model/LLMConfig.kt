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
    CODEX_CLI("智能体小悟", "codex-cli"),
    CLAUDE_CLI("智能体小智", "claude-cli")
}

enum class CodexReasoningEffort(val displayName: String, val requestValue: String) {
    MINIMAL("极简", "minimal"),
    LOW("低", "low"),
    MEDIUM("中", "medium"),
    HIGH("高", "high"),
    XHIGH("超高", "xhigh")
}

enum class ClaudeReasoningEffort(val displayName: String, val requestValue: String) {
    LOW("低", "low"),
    MEDIUM("中", "medium"),
    HIGH("高", "high"),
    MAX("最高", "max")
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
    val content: String,
    val subtitle: String = ""
)

data class ReportTemplateConfig(
    val selectedName: String = "通用会议",
    val content: String = "",
    val isCustom: Boolean = false
)

enum class ReportDocumentKind(
    val documentTitle: String,
    val sourceTitle: String
) {
    MEETING_MINUTES("会议纪要", "会议记录"),
    CONSTRUCTION_DESIGN_LOG("工程/建筑日志", "现场记录")
}

fun reportDocumentKind(templateName: String): ReportDocumentKind {
    val isConstructionOrDesignLog = templateName.contains("施工日志") ||
        templateName.contains("设计日志") ||
        templateName.contains("施工/设计日志")
    return if (isConstructionOrDesignLog) {
        ReportDocumentKind.CONSTRUCTION_DESIGN_LOG
    } else {
        ReportDocumentKind.MEETING_MINUTES
    }
}

/**
 * LLM Engine Configuration
 */
data class LLMConfig(
    val engineType: LLMEngineType = LLMEngineType.AGENT_GATEWAY,
    val agentEndpoint: String = BuildConfig.DEFAULT_AGENT_ENDPOINT,
    val agentAccessToken: String? = null,
    val agentProvider: AgentProvider = AgentProvider.CODEX_CLI,
    val codexReasoningEffort: CodexReasoningEffort = CodexReasoningEffort.HIGH,
    val claudeReasoningEffort: ClaudeReasoningEffort = ClaudeReasoningEffort.HIGH,
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
