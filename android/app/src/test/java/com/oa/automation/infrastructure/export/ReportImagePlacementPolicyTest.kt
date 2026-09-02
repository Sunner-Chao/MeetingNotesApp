package com.oa.automation.infrastructure.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportImagePlacementPolicyTest {
    @Test
    fun projectManagementImagesAreAlwaysAppendedWithSignInAppendix() {
        assertFalse(shouldInlineReportImage("项目管理"))
        assertFalse(shouldInlineReportImage("孔爵项目表格"))
        assertEquals("会议影像资料与签到表", reportImageAppendixTitle("项目管理"))
    }

    @Test
    fun studyImagesRemainInlineAndForumUsesRegularAppendix() {
        assertTrue(shouldInlineReportImage("研学考察"))
        assertEquals("照片集锦", reportImageAppendixTitle("研学考察"))
        assertTrue(shouldInlineReportImage("论坛会议"))
        assertEquals("会议图片", reportImageAppendixTitle("论坛会议"))
    }
}
