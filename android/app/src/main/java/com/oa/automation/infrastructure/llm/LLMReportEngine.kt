package com.oa.automation.infrastructure.llm

import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.model.reportDocumentKind
import com.oa.automation.domain.model.ForumParticipant
import com.oa.automation.domain.model.MeetingMode
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
        attachments: List<AgentAttachment> = emptyList(),
        usageContext: AgentUsageContext? = null
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

data class AgentUsageContext(
    val meetingId: String,
    val usageKey: String
)

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
    val locationSource: String? = null,
    val recordingMarkerId: String? = null,
    val markerTimestampMs: Long? = null,
    val markerTranscriptAnchor: String? = null
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
    val participants: List<ForumParticipant> = emptyList(),
    val rawContent: String = "",
    val templateName: String = ""
)

data class TaskData(
    val content: String,
    val assignee: String? = null,
    val due: String? = null,
    val priority: String? = null
)

/**
 * Default system prompt for meeting report generation
 */
object ReportPromptTemplates {

    const val SYSTEM_PROMPT = """你是一个专业的 AI 文档生成助手，专门负责将录音转写、现场口述或原始记录整理成规范文档。

你的职责：
1. 依据用户选择的会议类型和模板生成文档；通用会议允许按内容智能调整章节
2. 从原始记录中提取关键信息、事实、数据和结论
3. 会议纪要场景下识别决策事项、行动项和参会人员
4. 工程/建筑日志场景下整理日期、天气、施工生产、设计工作、质量、安全、材料机具、验收、停工和加班等现场记录
5. 监理例会场景下整理进度对比、上次整改闭合、监理工作、建设单位要求、责任单位和完成时限
6. 参观考察场景采用游记式主叙事：按真实动线分站，每站自然融合游览体验、讲解精要、互动发现和现场图片；事实核验信息集中放在文末附录
7. 研学正文使用短段落和轻量标题，减少表格和连续“待确认”；不得虚构天气、心情、气味、路线、价格、开放时间、人物身份或评价
8. 研学考察不得混入负责人、截止时间、验收标准、优先级、行动项、风险清单等项目管理字段；讲解人员只记录角色、姓名和单位，不推断职务
9. 研学图片章节使用“照片集锦”；正文配图使用单独一行的“[照片：图 N｜事实型图注]”锚点，并且只能引用实际存在的图号
10. 论坛会议按主持串场、时间线、主题演讲、圆桌讨论和现场问答组织长篇内容，严格区分主持人、嘉宾与提问者
11. 会议模板如存在可核对的语速变化、长停顿、打断、沉默或重复强调，必须单独输出“## 可观察互动信号”章节；每行使用“现象 | 证据或时间轴 | 来源发言人（如能确认）”格式。没有明确证据时不要生成该章节，也不要输出情绪、意图或人格标签

输出格式要求：
- 使用中文输出
- 结构清晰，便于阅读
- 不编造未提及的事实；无法确认的字段写“未提及”
- 专用模板保留标题层级、表格和章节结构；通用会议可按真实内容增删、合并或重排章节
- Markdown 只用于客户端排版解析，不要使用代码围栏包裹整篇文档，也不要把 ##、> 等语法符号重复写成正文内容"""

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
4. 待办任务（如有，格式：任务内容 | 负责人 | 截止时间 | 优先级；未提及时写“未提及”）
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
        val structureRule = if (template.selectedName == "通用会议") {
            "先判断行政会议、头脑风暴、杂谈、讲座沙龙、经营讨论或混合型场景，再按真实内容增删、合并和重排章节；不要输出判断过程。"
        } else {
            "保留模板的标题层级、表格和章节结构。"
        }
        val meetingMode = MeetingMode.fromTemplateName(template.selectedName)
        val interactionSignalRule = if (meetingMode in setOf(
                MeetingMode.DIRECTIVE,
                MeetingMode.PROGRESS,
                MeetingMode.CO_CREATE,
                MeetingMode.NEGOTIATION,
                MeetingMode.RETROSPECTIVE,
                MeetingMode.STANDUP
            )
        ) {
            """
                可观察互动信号（如有证据）：
                - 单独列出有时间轴或发言人依据的语速变化、长停顿、打断、沉默、重复强调等现象。
                - 不使用“愤怒、焦虑、抵触、缺乏诚意”等情绪、意图或人格结论；无法核对时写“待确认”。
            """.trimIndent()
        } else {
            ""
        }
        val scenarioRule = when (meetingMode) {
            MeetingMode.GENERAL -> """
                通用会议智能适配：
                - 行政会议突出决定、责任人以及开始、检查、截止和汇报时间。
                - 头脑风暴保留创意、观点聚类、少数意见、关键假设和待验证方向。
                - 杂谈按话题脉络保留有价值的观点和案例，没有明确承诺时不生成行动项。
                - 讲座沙龙区分主持人、主讲人和提问者，按主题、案例、问答与启发组织内容。
            """.trimIndent()
            MeetingMode.DIRECTIVE -> """
                宣贯·落实会：
                - 提取明确指令、适用范围、生效时间、责任人、检查节点和汇报节点。
                - 严格区分已下达事项、讨论建议和待确认事项，不把建议写成决定。
            """.trimIndent()
            MeetingMode.PROGRESS -> """
                推演·进度会：
                - 提取里程碑、当前状态、延期原因、资源依赖和风险灯号。
                - 每个风险保留证据与后续动作，负责人和时间未提及时写“待确认”。
            """.trimIndent()
            MeetingMode.CO_CREATE -> """
                启迪·共创会：
                - 保留创意池、观点聚类、少数意见、关键假设和待验证实验。
                - 讨论建议、投票倾向和灵感不得直接写成已经批准的决定。
            """.trimIndent()
            MeetingMode.NEGOTIATION -> """
                博弈·洽谈会：
                - 区分双方立场、共识、分歧、条款、交换条件和待确认承诺。
                - 仅记录可核对的语速变化、打断、停顿、重复强调等互动现象；不得把它们解释为情绪、意图或人格事实。
            """.trimIndent()
            MeetingMode.RETROSPECTIVE -> """
                复盘·分析会：
                - 先还原事实时间线、影响和处置，再用 5 Whys 整理根因候选与证据。
                - 根因没有证据或未获人工确认时必须写“待确认”，改进动作需有明确承诺。
            """.trimIndent()
            MeetingMode.STANDUP -> """
                敏捷·站会：
                - 按成员或小组分开整理“昨日完成 / 今日计划 / 阻塞”，严格区分完成与计划时态。
                - 阻塞项突出责任人、协同人、解除条件和预计时间；短会不制造额外行动项。
            """.trimIndent()
            MeetingMode.FORUM -> """
                论坛会议长篇整理：
                - 按真实时间、主持转场、议程变化和发言人切换分段，不得把数小时内容过度压缩。
                - 区分主持人、主讲人、圆桌嘉宾和提问者，姓名不明确时写“待确认”。
                - 保留演讲论据、数据、案例、圆桌分歧和问答对应关系。
                - 在论坛信息之后、主体内容之前输出独立的“参会人员名录”表格，列为“姓名/称谓、单位、角色”，供客户端生成照片墙通讯录。
                - 名录只填写原文明确出现的人员；姓名不明确者不加入名录，不输出占位行，不从照片推断身份，不在后文重复整段名录。
            """.trimIndent()
            MeetingMode.STUDY -> """
                研学考察游记式整理：
                - 每一站只保留一个站点标题，正文使用 3-5 个连续短段落，自然融合现场体验、讲解精要和互动发现。
                - 禁止输出“时间与点位”“现场事实”“对方介绍”“参观者观点与互动”“学习收获”等固定标签或审计式栏目。
                - 正文占 80%-90%；事实与待确认附录最多各 4 条，并合并同类项。
                - 没有图片附件时不得生成图片编号、配图建议或空照片章节。
            """.trimIndent()
        }
        return """请根据以下$sourceName，依据给定模板生成一份完整的 Markdown $documentName。

要求：
1. $structureRule
2. 将模板中的占位符替换为能从转写文本推断出的真实内容；无法确认的字段写“未提及”。
3. 不要输出 JSON，不要解释过程，只输出最终正文。
4. 待办、决策、问题、工程量、人员、天气等内容必须来自$sourceName，不要编造。
5. 当内容适合用表格展示时（如工程量清单、材料清单、人员分工、进度对比等），必须使用 Markdown 表格输出，使信息更清晰直观。

$scenarioRule

$interactionSignalRule

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
