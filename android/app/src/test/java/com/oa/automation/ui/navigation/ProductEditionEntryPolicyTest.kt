package com.oa.automation.ui.navigation

import com.oa.automation.domain.model.ProductEdition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductEditionEntryPolicyTest {
    @Test
    fun lightEnjoyExposesOnlyTheNewEightTemplateCatalog() {
        val policy = ProductEntryPolicy.forEdition(ProductEdition.LIGHT_ENJOY)

        assertFalse(policy.showCommunityTab)
        assertFalse(policy.showSocialAccountActions)
        assertFalse(policy.showGrowthNotifications)
        assertFalse(policy.showStudyJourneyTemplate)
        assertTrue(policy.showProjectWorkspace)
        assertFalse(policy.shouldShowMeetingTemplate("研学考察"))
        assertTrue(policy.shouldShowMeetingTemplate("研学考察", preserveSelectedLegacy = true))
        val expected = listOf(
            "宣贯·落实会",
            "推演·进度会",
            "启迪·共创会",
            "博弈·洽谈会",
            "复盘·分析会",
            "敏捷·站会",
            "论坛·共识会",
            "自定义会议"
        )
        expected.forEach { assertTrue(policy.shouldShowMeetingTemplate(it)) }
        assertFalse(policy.shouldShowMeetingTemplate("通用会议"))
        assertFalse(policy.shouldShowMeetingTemplate("项目管理"))
        assertFalse(policy.shouldShowMeetingTemplate("论坛会议"))
        assertTrue(policy.shouldShowMeetingTemplate("项目管理", preserveSelectedLegacy = true))
    }

    @Test
    fun socialEditionRetainsCommunityAndAccountEntryPoints() {
        val policy = ProductEntryPolicy.forEdition(ProductEdition.SOCIAL)

        assertTrue(policy.showCommunityTab)
        assertTrue(policy.showSocialAccountActions)
        assertTrue(policy.showGrowthNotifications)
        assertTrue(policy.showStudyJourneyTemplate)
        assertFalse(policy.showProjectWorkspace)
        assertTrue(policy.shouldShowMeetingTemplate("研学考察"))
        assertTrue(policy.shouldShowMeetingTemplate("通用会议"))
        assertTrue(policy.shouldShowMeetingTemplate("项目管理"))
        assertTrue(policy.shouldShowMeetingTemplate("论坛会议"))
        assertFalse(policy.shouldShowMeetingTemplate("自定义会议"))
    }
}
