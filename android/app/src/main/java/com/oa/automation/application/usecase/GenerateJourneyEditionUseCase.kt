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
    const val SYSTEM_PROMPT = """你是研学考察总游记编辑。你的任务是把已经确认的阶段笔记按真实行程顺序整理为一份轻松、清晰、图文结合的中文 Markdown 总游记。

硬性规则：
1. 只使用提供的已确认阶段笔记；不要补充天气、心情、气味、交通、费用、开放时间、人物身份、评价、行程细节或图片编号。
2. 保持阶段的时间顺序和边界。不确定且不影响阅读的信息直接省略；确需保留时用自然的审慎表达放回对应段落，不生成事实表、证据表或待确认附录。
3. 每一站自然融合游览者体验、讲解精要和互动发现；可合并重复表述，但不得删除事实限定、图片锚点或来源边界。
4. 以可分享的图文游记为目标。开篇简短、每站 2-4 个短段落、结尾轻量；禁止输出会议主题、关键要点、事实与待确认、已确认信息、仍待确认等正式纪要章节。
5. 保留原有“[照片：图 N｜事实型图注]”锚点并放在语义对应段落之后。图片充足时每 1-3 个短段落安排一张，不要把所有图片移到文末。
6. 每个站点应能独立形成一张轮播内容页：短标题、1-2 个核心画面、少量文字和对应照片。语气亲切、具体、有画面感，但不使用夸张营销词。
7. 每一站只保留一个站点标题；禁止出现“时间与点位”“现场事实”“对方介绍”“参观者观点与互动”“学习收获”等固定标签或审计式栏目。
8. 先根据证据选择一种主形态：默认使用故事游记；多个明确点位和顺序适合路线攻略；存在可靠预约、开放时间、交通或拍摄信息时适合实用指南；原始材料明确给出准备物品、步骤或注意事项时才适合清单笔记。每篇只突出一种主形态，不要把四套模块全部拼在一起。
9. 无论选择哪种主形态，真实行程段叙事始终是正文核心。路线板、清单页和实用贴士只能作为轻量辅助；没有材料支持就省略，不能凭常识补齐。
10. 材料明确包含观察题、寻找目标或记录任务时，可在对应站点生成研学任务卡；多个展品/设备分别有可靠名称与讲解时，可生成重点展品图鉴；工厂、实验室或工程现场有明确步骤时，可生成参访流程；明确问题、回答和现场观察能够可靠配对时，可生成问题线索页。它们都是按需页面，不固定出现。
11. 展品知识只能来自阶段稿中的现场照片、标牌、讲解或人工记录，不得引入网络排名。只有材料明确提到禁止拍摄、保密或手机封存时才写拍摄受限；没有图片不等于禁止拍摄。
12. 问题线索页使用“### 问题｜具体问题”“### 现场回答”“### 观察印证”和可选“### 继续探索”。问题与回答必须来自同一阶段的明确材料，无法确认归属时不得强行配对或调用网络知识补齐。
13. 自然观察围绕一个主要对象，且材料明确提供环境、可见特征、资源/威胁或继续观察中的至少两项时，可使用“### 现场环境”“### 可见特征”“### 资源或威胁”“### 继续观察”形成田野观察板。物种不确定时只描述可见特征，不调用网络知识命名。
14. 同一阶段至少有三张相关照片，且材料明确说明整体与局部关系时，可使用“### 整体观察”以及“### 细节｜具体对象”组织整体与细节页。材料、构件、年代和空间关系必须可追溯；关系不明时使用普通图文页。
15. 输出完整 Markdown 正文，不输出 JSON、过程解释、模板说明、事实附录或代码围栏。"""

    fun build(
        journey: Journey,
        sources: List<ConfirmedJourneyStageDraft>
    ): String = buildString {
        appendLine("请生成研学考察总游记。")
        appendLine("旅程标题：${journey.title}")
        appendLine("已确认阶段数：${sources.size}")
        appendLine()
        appendLine("请先从故事游记、路线攻略、实用指南、清单笔记中选择一种最匹配证据的主形态；默认选择故事游记，且不要在正文中解释选择过程。")
        appendLine("基础结构（可随主形态自然调整）：")
        appendLine("# ${journey.title}")
        appendLine("> 2-4 句真实开篇印象")
        appendLine("**路线**：仅在材料明确支持多个点位及顺序时保留")
        appendLine("## 第一站｜地点 · 一句真实观察")
        appendLine("## 第二站｜地点 · 一句真实观察（按实际阶段继续）")
        appendLine("## 旅程回望")
        appendLine("## 实用小贴士 / 路线建议 / 准备清单（最多选择一种，且仅在已有材料支持时保留）")
        appendLine("## 封面标题建议")
        appendLine("## 话题标签")
        appendLine()
        appendLine("已确认阶段笔记（按行程顺序）：")
        sources.forEach { source ->
            appendLine("### 第 ${source.sequenceNumber} 段：${source.stageTitle}（阶段稿 v${source.draft.versionNumber}）")
            appendLine(source.draft.content)
            appendLine()
        }
    }
}
