package com.oa.automation.application.usecase

import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.renderedContent
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.StageDraftRepository
import com.oa.automation.infrastructure.attachment.MeetingAttachmentStore
import com.oa.automation.infrastructure.llm.AgentAttachment
import com.oa.automation.infrastructure.llm.ChatMessage
import com.oa.automation.infrastructure.llm.LLMEngine
import com.oa.automation.locale.SimplifiedChineseText
import kotlinx.coroutines.flow.first

class GenerateStageDraftUseCase(
    private val meetingRepository: MeetingRepository,
    private val stageDraftRepository: StageDraftRepository,
    private val attachmentStore: MeetingAttachmentStore,
    private val llmEngine: LLMEngine
) {
    suspend operator fun invoke(stage: JourneyStage): Result<com.oa.automation.domain.model.StageDraftVersion> {
        val transcripts = meetingRepository.findTranscriptsByJourneyStageId(stage.id).getOrElse {
            return Result.failure(Exception("读取阶段转写失败: ${it.message}"))
        }
        val attachments = meetingRepository.observeAttachmentsByJourneyStageId(stage.id).first()
        if (transcripts.isEmpty() && attachments.isEmpty()) {
            return Result.failure(IllegalStateException("当前阶段没有可生成笔记的转写或图片"))
        }

        val transcriptText = SimplifiedChineseText.normalize(
            transcripts.joinToString("\n") { it.renderedContent() }
        ).ifBlank { "未采集到可用转写" }
        val agentAttachments = attachmentStore.toAgentAttachments(attachments)
        val prompt = StageDraftPromptTemplates.build(
            stage = stage,
            transcriptText = transcriptText,
            attachmentNames = attachments.mapIndexed { index, attachment ->
                "图${index + 1}：${attachment.displayName}（${attachment.locationSource ?: "位置未确认"}）"
            }
        )
        val generated = llmEngine.chat(
            messages = listOf(
                ChatMessage("system", StageDraftPromptTemplates.SYSTEM_PROMPT),
                ChatMessage("user", prompt)
            ),
            attachments = agentAttachments
        ).getOrElse { return Result.failure(it) }
            .trim()
        if (generated.isBlank()) return Result.failure(IllegalStateException("阶段笔记内容为空"))

        return stageDraftRepository.createDraft(
            stageId = stage.id,
            content = generated,
            evidenceTranscriptCount = transcripts.size,
            evidenceAttachmentCount = attachments.size
        )
    }
}

internal object StageDraftPromptTemplates {
    const val SYSTEM_PROMPT = """你是研学考察阶段游记编辑。你的任务是把一个已经暂存的行程段整理成可编辑、可继续合并的中文 Markdown 图文笔记。

硬性规则：
1. 只使用提供的转写和图片证据；不要补充天气、心情、气味、价格、开放时间、人物身份、路线或评价。
2. 以自然游记叙事融合“所见所感、讲解精要、互动或发现”，不要输出审计表、工作总结、风险清单、行动项、事实附录或待确认清单。
3. 图片只能引用清单中的图号，不能创造不存在的图片编号。
4. GPS、EXIF 和文件名只能作为位置线索；无法可靠确认的信息直接省略，不把推测写成事实。
5. 有可靠语义对应的图片紧跟相关段落，使用单独一行的“[照片：图 N｜事实型图注]”；图片充足时每 1-3 个短段落安排一张，图注只描述可见且有依据的内容。
6. 本阶段应能独立形成一张轮播内容页：一个短标题、2-4 个短段落、1-2 个核心画面和对应照片。不要堆叠长篇说明。
7. 语气亲切、具体、有画面感；每段最多 1-2 个 emoji，不使用夸张营销口号，也不使用“现场事实”“学习收获”等正式栏目。
8. 默认生成现场故事页。只有本阶段证据明确包含路线顺序、操作步骤、准备清单或可靠参观信息时，才将其整理为路线页、过程页、清单页或实用提示页；不要为了套版补写信息。
9. 每个阶段只选择一种最自然的页面表达，叙事段落仍是核心，不要同时堆叠路线、清单、贴士和总结。
10. 本段明确包含观察题、寻找目标或记录任务时，可整理为研学任务卡；多个展品/设备有可靠名称与讲解时，可整理为重点展品图鉴；存在明确生产或参访步骤时，可整理为流程页；明确问题、回答和现场观察能够可靠配对时，可整理为问题线索页。
11. 展品知识只能来自本段照片、标牌、讲解和人工记录，不得补充网络排名。只有证据明确提到禁止拍摄、保密或手机封存时才写拍摄受限；没有图片不等于禁止拍摄。
12. 问题线索页使用“### 问题｜具体问题”“### 现场回答”“### 观察印证”和可选“### 继续探索”。无法确认回答归属时不得强行配对或调用网络知识补齐。
13. 本段围绕一个主要自然对象，且证据明确提供环境、可见特征、资源/威胁或继续观察中的至少两项时，可使用“### 现场环境”“### 可见特征”“### 资源或威胁”“### 继续观察”。物种不确定时只描述可见特征。
14. 本段至少有三张相关照片，且证据明确说明整体与局部关系时，可使用“### 整体观察”和“### 细节｜具体对象”。构件、材料和空间关系不明确时使用普通图文页。
15. 输出完整 Markdown 正文，不输出 JSON、过程解释、模板说明、核验附录或代码围栏。"""

    fun build(
        stage: JourneyStage,
        transcriptText: String,
        attachmentNames: List<String>
    ): String = buildString {
        appendLine("请生成研学阶段笔记。")
        appendLine("行程段：${stage.title}（第 ${stage.sequenceNumber} 段）")
        appendLine()
        appendLine("默认采用现场故事页；若证据天然适合路线、过程、清单或实用提示，可择一调整，不要混用全部模块。")
        appendLine("基础结构：")
        appendLine("# 第 ${stage.sequenceNumber} 站｜${stage.title} · 一句真实观察")
        appendLine("> 1-2 句开场印象，只使用本段证据。")
        appendLine("用 2-4 个短段落自然串联游览体验、讲解要点和现场互动，供一张轮播内容页直接使用。")
        appendLine("[照片：图 N｜与上文对应的事实型图注]")
        appendLine("用一句自然收束写下本站最值得带走的观察，不另设正式总结栏目。")
        appendLine()
        appendLine("转写证据：")
        appendLine("```text")
        appendLine(transcriptText)
        appendLine("```")
        appendLine()
        appendLine("图片证据清单（只能引用这些图号）：")
        if (attachmentNames.isEmpty()) appendLine("无图片证据")
        else attachmentNames.forEach(::appendLine)
    }
}
