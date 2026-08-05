package com.oa.automation.application.usecase

import com.oa.automation.domain.model.JourneyStage
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
            transcripts.joinToString("\n") { transcript ->
                buildString {
                    transcript.speakerName?.takeIf { it.isNotBlank() }?.let { append("[$it] ") }
                    append(transcript.content)
                }
            }
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
    const val SYSTEM_PROMPT = """你是研学考察阶段笔记编辑。你的任务是把一个已经暂存的行程段整理成可编辑的中文 Markdown 阶段笔记。

硬性规则：
1. 只使用提供的转写和图片证据；没有依据的内容写“待确认”，不要补充价格、开放时间、人物身份或评价。
2. 严格保留行程段顺序，区分“现场观察”“讲解/交流”“团队启示”，不要把推测写成事实。
3. 图片只能引用清单中的图号，不能创造不存在的图片编号。
4. GPS、EXIF 和文件名只能作为位置线索，无法确认时标注“位置待确认”。
5. 输出完整 Markdown 正文，不输出 JSON、过程解释或营销口号。"""

    fun build(
        stage: JourneyStage,
        transcriptText: String,
        attachmentNames: List<String>
    ): String = buildString {
        appendLine("请生成研学阶段笔记。")
        appendLine("行程段：${stage.title}（第 ${stage.sequenceNumber} 段）")
        appendLine()
        appendLine("建议结构：")
        appendLine("# ${stage.title}")
        appendLine("## 本段亮点")
        appendLine("## 现场观察")
        appendLine("## 讲解与交流")
        appendLine("## 可借鉴做法")
        appendLine("## 待确认信息")
        appendLine("## 配图")
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
