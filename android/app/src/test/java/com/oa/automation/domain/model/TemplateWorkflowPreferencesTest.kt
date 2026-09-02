package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateWorkflowPreferencesTest {
    @Test
    fun defaultsKeepMotionEnabledAndNoSeenTemplates() {
        val preferences = TemplateWorkflowPreferences.DEFAULT
        assertFalse(preferences.reducedMotion)
        assertTrue(preferences.seenTemplateNames.isEmpty())
    }

    @Test
    fun seenTemplatesRemainASet() {
        val preferences = TemplateWorkflowPreferences(
            reducedMotion = true,
            seenTemplateNames = setOf("复盘·分析会", "复盘·分析会")
        )
        assertTrue(preferences.reducedMotion)
        assertEquals(setOf("复盘·分析会"), preferences.seenTemplateNames)
    }
}
