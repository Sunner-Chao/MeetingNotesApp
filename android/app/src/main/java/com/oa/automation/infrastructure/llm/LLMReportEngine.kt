package com.oa.automation.infrastructure.llm

import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.model.reportDocumentKind
import java.io.File

/**
 * Interface for LLM report generation engines
 */
interface LLMReportEngine {

    /**
     * Generate meeting report from transcript
     */
    suspend fun generateReport(
        transcript: String,
        template: ReportTemplateConfig,
        attachments: List<AgentAttachment> = emptyList()
    ): Result<ReportData>

    /**
     * Chat with LLM for refining reports
     * @param messages List of chat messages (system, user, assistant)
     * @return LLM response text
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        attachments: List<AgentAttachment> = emptyList()
    ): Result<String>

    /**
     * Get the engine type
     */
    fun getEngineType(): LLMEngineType

    /**
     * Get display name
     */
    fun getDisplayName(): String

    /**
     * Check if engine is available/configured
     */
    fun isAvailable(): Boolean

    /**
     * Create engine from configuration
     */
    companion object {
        fun fromConfig(config: LLMConfig): LLMReportEngine {
            return when (config.engineType) {
                LLMEngineType.AGENT_GATEWAY -> AgentGatewayEngine(config)
                LLMEngineType.LOCAL_OLLAMA -> OllamaEngine(config)
                LLMEngineType.CLOUD_API -> CloudLLMEngine(config)
            }
        }
    }
}

/**
 * Chat message model
 */
data class ChatMessage(
    val role: String, // "system", "user", "assistant"
    val content: String
)

data class AgentAttachment(
    val file: File,
    val mimeType: String,
    val displayName: String,
    /** Capture time from the local meeting attachment record, when available. */
    val capturedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val locationCapturedAt: Long? = null,
    val locationSource: String? = null
)

/**
 * Report data model returned by LLM engines
 */
data class ReportData(
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val tasks: List<TaskData> = emptyList(),
    val decisions: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
    val rawContent: String = "",
    val templateName: String = ""
)

data class TaskData(
    val content: String,
    val assignee: String? = null,
    val due: String? = null
)

/**
 * Default system prompt for meeting report generation
 */
object ReportPromptTemplates {

    const val SYSTEM_PROMPT = """你是一个专业的 AI 文档生成助手，专门负责将录音转写、现场口述或原始记录整理成规范文档。

你的职责：
1. 严格按用户提供的模板生成文档
2. 从原始记录中提取关键信息、事实、数据和结论
3. 会议纪要场景下识别决策事项、行动项和参会人员
4. 工程/建筑日志场景下整理日期、天气、施工生产、设计工作、质量、安全、材料机具、验收、停工和加班等现场记录
5. 监理例会场景下整理进度对比、上次整改闭合、监理工作、建设单位要求、责任单位和完成时限
6. 参观考察场景下按实际行程顺序保留每个点位的时间、现场观察、讲解交流、可借鉴做法和后续启示

输出格式要求：
- 使用中文输出
- 结构清晰，便于阅读
- 不编造未提及的事实；无法确认的字段写“未提及”
- 保留模板的标题层级、表格和章节结构"""

    const val REFINEMENT_SYSTEM_PROMPT = """你是一个专业的会议纪要润色助手。用户会向你提供当前的会议纪要内容，你可以帮助用户：
1. 润色文字，使表达更加专业流畅
2. 调整结构，使逻辑更加清晰
3. 补充或修改内容
4. 回答关于会议纪要的问题

请在用户要求修改时，直接输出修改后的完整会议纪要内容。
如果用户只是提问而不需要修改，请直接回答问题。"""

    const val USER_PROMPT_TEMPLATE_STANDARD = """请根据以下会议记录，生成一份规范的会议纪要：

会议记录：
{transcript}

请按以下格式输出：
1. 会议概述（一段话总结）
2. 关键要点（5-8条）
3. 决策事项（如有）
4. 待办任务（如有，格式：任务内容 | 负责人 | 截止时间）
5. 行动项（如有）"""

    const val USER_PROMPT_TEMPLATE_ACTION = """请根据以下会议记录，输出“行动项驱动”的会议纪要：

会议记录：
{transcript}

输出要求：
1. 会议结论（简短）
2. 风险与阻塞（如果有）
3. 待办任务清单（必须结构化，格式：任务内容 | 负责人 | 截止时间 | 优先级）
4. 行动项（按优先级排序）
5. 需要跟进的问题"""

    const val USER_PROMPT_TEMPLATE_EXECUTIVE = """请根据以下会议记录，生成“管理层简报版”会议纪要：

会议记录：
{transcript}

输出要求：
1. 一段话摘要（不超过120字）
2. 关键决策（最多5条）
3. 业务影响（对进度/成本/风险的影响）
4. 关键里程碑与负责人
5. 下次会议建议议程"""

    fun buildUserPrompt(transcript: String, template: ReportTemplateConfig): String {
        val rawTemplate = template.content.ifBlank { USER_PROMPT_TEMPLATE_STANDARD }
        val documentKind = reportDocumentKind(template.selectedName)
        val documentName = documentKind.documentTitle
        val sourceName = documentKind.sourceTitle
        return """请根据以下$sourceName，严格按给定模板生成一份完整的 Markdown $documentName。

要求：
1. 保留模板的标题层级、表格和章节结构。
2. 将模板中的占位符替换为能从转写文本推断出的真实内容；无法确认的字段写“未提及”。
3. 不要输出 JSON，不要解释过程，只输出最终正文。
4. 待办、决策、问题、工程量、人员、天气等内容必须来自$sourceName，不要编造。
5. 当内容适合用表格展示时（如工程量清单、材料清单、人员分工、进度对比等），必须使用 Markdown 表格输出，使信息更清晰直观。

模板「${template.selectedName}」：
```markdown
$rawTemplate
```

${sourceName}：
```text
$transcript
```""".trimIndent()
    }
}
