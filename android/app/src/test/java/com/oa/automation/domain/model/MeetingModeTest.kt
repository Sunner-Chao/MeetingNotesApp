package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingModeTest {
    @Test
    fun legacyTemplateNamesResolveToTheirNewMeetingModes() {
        assertEquals(MeetingMode.DIRECTIVE, MeetingMode.fromTemplateName("行政会议"))
        assertEquals(MeetingMode.PROGRESS, MeetingMode.fromTemplateName("项目管理"))
        assertEquals(MeetingMode.PROGRESS, MeetingMode.fromTemplateName("推演·进度会"))
        assertEquals(MeetingMode.CO_CREATE, MeetingMode.fromTemplateName("头脑风暴"))
        assertEquals(MeetingMode.FORUM, MeetingMode.fromTemplateName("论坛·共识会"))
        assertEquals(MeetingMode.FORUM, MeetingMode.fromTemplateName("聚智·论道会"))
        assertEquals(MeetingMode.CUSTOM, MeetingMode.fromTemplateName("自定义会议"))
        assertEquals(MeetingMode.STUDY, MeetingMode.fromTemplateName("参观考察（游记）"))
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
