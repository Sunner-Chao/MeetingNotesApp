package com.oa.automation.ui.screen.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateWorkflowTest {
    @Test
    fun knownTemplatesExposeFourStructuredSteps() {
        listOf("宣贯·落实会", "推演·进度会", "启迪·共创会", "博弈·洽谈会", "复盘·分析会", "敏捷·站会")
            .forEach { name ->
                val workflow = templateWorkflowFor(name)
                assertEquals(name, workflow.templateName)
                assertEquals(4, workflow.steps.size)
                assertTrue(workflow.goal.isNotBlank())
                assertTrue(workflow.output.isNotBlank())
                assertTrue(workflow.steps.all { it.title.isNotBlank() && it.detail.isNotBlank() })
            }
    }

    @Test
    fun aliasesUseTheSameWorkflowFamily() {
        assertEquals(
            templateWorkflowFor("复盘·分析会").steps.map { it.title },
            templateWorkflowFor("复盘分析会").steps.map { it.title }
        )
        assertEquals(
            templateWorkflowFor("启迪·共创会").steps.map { it.title },
            templateWorkflowFor("头脑风暴").steps.map { it.title }
        )
    }

    @Test
    fun blankOrUnknownTemplateFallsBackToGeneralMeeting() {
        val blank = templateWorkflowFor("")
        val unknown = templateWorkflowFor("临时会议")
        assertEquals("通用会议", blank.templateName)
        assertEquals("临时会议", unknown.templateName)
        assertFalse(blank.steps.isEmpty())
        assertTrue(blank.steps.last().title.contains("生成"))
    }
}
