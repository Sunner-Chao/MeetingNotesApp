package com.oa.automation.application.usecase

import com.oa.automation.domain.model.ConfirmedJourneyStageDraft
import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyEdition
import com.oa.automation.domain.repository.JourneyEditionRepository
import com.oa.automation.domain.repository.StageDraftRepository
import com.oa.automation.infrastructure.llm.ChatMessage
import com.oa.automation.infrastructure.llm.LLMEngine

class GenerateJourneyEditionUseCase(
    private val stageDraftRepository: StageDraftRepository,
    private val journeyEditionRepository: JourneyEditionRepository,
    private val llmEngine: LLMEngine
) {
    suspend operator fun invoke(journey: Journey): Result<JourneyEdition> {
        val sources = stageDraftRepository.findLatestConfirmedByJourneyId(journey.id).getOrElse {
            return Result.failure(Exception("读取已确认阶段笔记失败: ${it.message}"))
        }
        if (sources.isEmpty()) {
            return Result.failure(IllegalStateException("请先确认至少一个阶段笔记"))
        }

        val generated = llmEngine.chat(
            messages = listOf(
                ChatMessage("system", JourneyEditionPromptTemplates.SYSTEM_PROMPT),
                ChatMessage("user", JourneyEditionPromptTemplates.build(journey, sources))
            )
        ).getOrElse { return Result.failure(it) }.trim()
        if (generated.isBlank()) return Result.failure(IllegalStateException("总游记内容为空"))

        return journeyEditionRepository.createEdition(
            journeyId = journey.id,
            content = generated,
            sourceStageDraftIds = sources.map { it.draft.id },
            sourceStageCount = sources.size
        )
    }
}

internal object JourneyEditionPromptTemplates {
    const val SYSTEM_PROMPT = """你是研学考察总游记编辑。你的任务是把已经确认的阶段笔记按真实行程顺序整理为一份可编辑的中文 Markdown 总游记。

硬性规则：
1. 只使用提供的已确认阶段笔记；不要补充交通、费用、开放时间、人物身份、评价、行程细节或图片编号。
2. 保持阶段的时间顺序和边界。阶段笔记中标为“待确认”的信息仍必须保留为待确认，不得转写为事实。
3. 保留现场观察、讲解/交流和团队启示之间的区分；可合并重复表述，但不得删除关键限定条件。
4. 输出完整 Markdown 正文，不输出 JSON、过程解释或营销口号。"""

    fun build(
        journey: Journey,
        sources: List<ConfirmedJourneyStageDraft>
    ): String = buildString {
        appendLine("请生成研学考察总游记。")
        appendLine("旅程标题：${journey.title}")
        appendLine("已确认阶段数：${sources.size}")
        appendLine()
        appendLine("建议结构：")
        appendLine("# ${journey.title}")
        appendLine("## 行程概览")
        appendLine("## 分段记录")
        appendLine("## 核心观察")
        appendLine("## 团队启示")
        appendLine("## 待确认信息")
        appendLine()
        appendLine("已确认阶段笔记（按行程顺序）：")
        sources.forEach { source ->
            appendLine("### 第 ${source.sequenceNumber} 段：${source.stageTitle}（阶段稿 v${source.draft.versionNumber}）")
            appendLine(source.draft.content)
            appendLine()
        }
    }
}
