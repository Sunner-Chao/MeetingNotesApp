package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingModeTest {
    @Test
    fun legacyTemplateNamesResolveToTheirNewMeetingModes() {
        assertEquals(MeetingMode.DIRECTIVE, MeetingMode.fromTemplateName("行政会议"))
        assertEquals(MeetingMode.PROGRESS, MeetingMode.fromTemplateName("项目管理"))
        assertEquals(MeetingMode.CO_CREATE, MeetingMode.fromTemplateName("头脑风暴"))
    }

    @Test
    fun newMeetingModesRemainStableByPersistedTemplateName() {
        MeetingMode.entries.forEach { mode ->
            assertEquals(mode, MeetingMode.fromTemplateName(mode.templateName))
        }
    }

    @Test
    fun unknownTemplateFallsBackToGeneralMode() {
        assertEquals(MeetingMode.GENERAL, MeetingMode.fromTemplateName("自定义模板"))
    }
}
