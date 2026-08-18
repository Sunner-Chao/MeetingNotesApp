package com.oa.automation.application.usecase

import com.oa.automation.domain.model.ConfirmedJourneyStageDraft
import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.StageDraftStatus
import com.oa.automation.domain.model.StageDraftVersion
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyEditionPromptTest {
    @Test
    fun `prompt preserves confirmed stage order and no invention rule`() {
        val first = ConfirmedJourneyStageDraft(
            stageId = "stage-1",
            sequenceNumber = 1,
            stageTitle = "展馆参访",
            stageSavedAt = 100L,
            draft = StageDraftVersion(
                id = "draft-1",
                stageId = "stage-1",
                versionNumber = 1,
                content = "现场观察：幕墙改造。",
                status = StageDraftStatus.CONFIRMED
            )
        )
        val second = first.copy(
            stageId = "stage-2",
            sequenceNumber = 2,
            stageTitle = "企业交流",
            draft = first.draft.copy(id = "draft-2", stageId = "stage-2", content = "讲解交流：待确认")
        )

        val prompt = JourneyEditionPromptTemplates.build(
            journey = Journey(id = "journey-1", meetingId = "meeting-1", title = "城市更新研学"),
            sources = listOf(first, second)
        )

        assertTrue(prompt.contains("旅程标题：城市更新研学"))
        assertTrue(prompt.indexOf("第 1 段：展馆参访") < prompt.indexOf("第 2 段：企业交流"))
        assertTrue(prompt.contains("旅程回望"))
        assertTrue(!prompt.contains("事实与待确认附录"))
        assertTrue(JourneyEditionPromptTemplates.SYSTEM_PROMPT.contains("不要补充天气、心情、气味、交通、费用"))
        assertTrue(JourneyEditionPromptTemplates.SYSTEM_PROMPT.contains("每 1-3 个短段落安排一张"))
        assertTrue(JourneyEditionPromptTemplates.SYSTEM_PROMPT.contains("每一站只保留一个站点标题"))
        assertTrue(JourneyEditionPromptTemplates.SYSTEM_PROMPT.contains("禁止出现“时间与点位”"))
        assertTrue(JourneyEditionPromptTemplates.SYSTEM_PROMPT.contains("独立形成一张轮播内容页"))
        assertTrue(JourneyEditionPromptTemplates.SYSTEM_PROMPT.contains("不生成事实表、证据表或待确认附录"))
        assertTrue(JourneyEditionPromptTemplates.SYSTEM_PROMPT.contains("问题线索页"))
        assertTrue(JourneyEditionPromptTemplates.SYSTEM_PROMPT.contains("不得强行配对或调用网络知识补齐"))
        assertTrue(JourneyEditionPromptTemplates.SYSTEM_PROMPT.contains("田野观察板"))
        assertTrue(JourneyEditionPromptTemplates.SYSTEM_PROMPT.contains("### 整体观察"))
    }
}
