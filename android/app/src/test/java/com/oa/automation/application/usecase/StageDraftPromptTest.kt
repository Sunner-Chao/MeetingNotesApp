package com.oa.automation.application.usecase

import com.oa.automation.domain.model.JourneyStage
import org.junit.Assert.assertTrue
import org.junit.Test

class StageDraftPromptTest {
    @Test
    fun `prompt identifies the saved stage and limits images to supplied evidence`() {
        val prompt = StageDraftPromptTemplates.build(
            stage = JourneyStage(
                id = "stage-2",
                journeyId = "journey-1",
                sequenceNumber = 2,
                title = "智慧展厅参访"
            ),
            transcriptText = "讲解员介绍了低碳改造方案。",
            attachmentNames = listOf("图1：展厅入口.jpg（GPS）", "图2：沙盘.jpg（位置待确认）")
        )

        assertTrue(prompt.contains("智慧展厅参访（第 2 段）"))
        assertTrue(prompt.contains("图1：展厅入口.jpg（GPS）"))
        assertTrue(prompt.contains("图2：沙盘.jpg（位置待确认）"))
        assertTrue(prompt.contains("游览体验、讲解要点和现场互动"))
        assertTrue(prompt.contains("[照片：图 N｜与上文对应的事实型图注]"))
        assertTrue(!prompt.contains("待确认补记"))
        assertTrue(StageDraftPromptTemplates.SYSTEM_PROMPT.contains("不能创造不存在的图片编号"))
        assertTrue(StageDraftPromptTemplates.SYSTEM_PROMPT.contains("不要输出审计表"))
        assertTrue(StageDraftPromptTemplates.SYSTEM_PROMPT.contains("独立形成一张轮播内容页"))
        assertTrue(StageDraftPromptTemplates.SYSTEM_PROMPT.contains("问题线索页"))
        assertTrue(StageDraftPromptTemplates.SYSTEM_PROMPT.contains("不得强行配对或调用网络知识补齐"))
        assertTrue(StageDraftPromptTemplates.SYSTEM_PROMPT.contains("### 现场环境"))
        assertTrue(StageDraftPromptTemplates.SYSTEM_PROMPT.contains("### 整体观察"))
    }
}
