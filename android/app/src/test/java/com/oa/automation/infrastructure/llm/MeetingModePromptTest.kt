package com.oa.automation.infrastructure.llm

import com.oa.automation.domain.model.ReportTemplateConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingModePromptTest {
    @Test
    fun negotiationPromptKeepsEmotionInterpretationGroundedInObservations() {
        val prompt = ReportPromptTemplates.buildUserPrompt(
            transcript = "甲方提出新的交付条件。",
            template = ReportTemplateConfig(selectedName = "博弈·洽谈会", content = "洽谈模板")
        )

        assertTrue(prompt.contains("可核对的语速变化"))
        assertTrue(prompt.contains("不得把它们解释为情绪、意图或人格事实"))
    }

    @Test
    fun standupPromptSeparatesCompletedPlannedAndBlockedWork() {
        val prompt = ReportPromptTemplates.buildUserPrompt(
            transcript = "昨天完成接口联调，今天处理阻塞。",
            template = ReportTemplateConfig(selectedName = "敏捷·站会", content = "站会模板")
        )

        assertTrue(prompt.contains("昨日完成 / 今日计划 / 阻塞"))
        assertTrue(prompt.contains("严格区分完成与计划时态"))
    }

    @Test
    fun directivePromptRequestsObservableSignalsWithoutEmotionClaims() {
        val prompt = ReportPromptTemplates.buildUserPrompt(
            transcript = "请各组周五前提交进度。",
            template = ReportTemplateConfig(selectedName = "宣贯·落实会", content = "宣贯模板")
        )

        assertTrue(prompt.contains("可观察互动信号"))
        assertTrue(prompt.contains("不使用“愤怒、焦虑、抵触、缺乏诚意”等情绪、意图或人格结论"))
    }

    @Test
    fun renamedForumPromptRetainsForumRosterBehavior() {
        val prompt = ReportPromptTemplates.buildUserPrompt(
            transcript = "主持人介绍嘉宾并进入圆桌讨论。",
            template = ReportTemplateConfig(selectedName = "论坛·共识会", content = "论坛模板")
        )

        assertTrue(prompt.contains("论坛·共识会"))
        assertTrue(prompt.contains("兼容旧“论坛会议”"))
        assertTrue(prompt.contains("参会人员名录"))
    }

    @Test
    fun customPromptDescribesOrderedOptionalModulesWithoutInventingContent() {
        val prompt = ReportPromptTemplates.buildUserPrompt(
            transcript = "讨论下周发布安排。",
            template = ReportTemplateConfig(selectedName = "自定义会议", content = "自定义模板")
        )

        assertTrue(prompt.contains("自定义会议"))
        // The module order now comes from the user's dragged arrangement, so the
        // prompt must forbid the model from resequencing it.
        assertTrue(prompt.contains("用户编排的模块顺序"))
        assertTrue(prompt.contains("不要新增、重排或重命名模块"))
        assertTrue(prompt.contains("不得为凑齐模板编造议题"))
    }

    @Test
    fun promptRequiresAuditableInteractionSignalSection() {
        assertTrue(ReportPromptTemplates.SYSTEM_PROMPT.contains("## 可观察互动信号"))
        assertTrue(ReportPromptTemplates.SYSTEM_PROMPT.contains("证据或时间轴"))
        assertTrue(ReportPromptTemplates.SYSTEM_PROMPT.contains("不要输出情绪、意图或人格标签"))
    }
}
