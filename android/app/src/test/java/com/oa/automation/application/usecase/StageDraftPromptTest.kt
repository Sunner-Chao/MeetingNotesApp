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
        assertTrue(StageDraftPromptTemplates.SYSTEM_PROMPT.contains("不能创造不存在的图片编号"))
    }
}
