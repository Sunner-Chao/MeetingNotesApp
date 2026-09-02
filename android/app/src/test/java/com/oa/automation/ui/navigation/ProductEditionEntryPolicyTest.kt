package com.oa.automation.ui.navigation

import com.oa.automation.domain.model.ProductEdition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductEditionEntryPolicyTest {
    @Test
    fun lightEnjoyHidesSocialAndKeepsSharedMeetingSurfacesAvailable() {
        val policy = ProductEntryPolicy.forEdition(ProductEdition.LIGHT_ENJOY)

        assertFalse(policy.showCommunityTab)
        assertFalse(policy.showSocialAccountActions)
        assertFalse(policy.showGrowthNotifications)
        assertFalse(policy.showStudyJourneyTemplate)
        assertFalse(policy.shouldShowMeetingTemplate("研学考察"))
        assertTrue(policy.shouldShowMeetingTemplate("项目管理"))
    }

    @Test
    fun socialEditionRetainsCommunityAndAccountEntryPoints() {
        val policy = ProductEntryPolicy.forEdition(ProductEdition.SOCIAL)

        assertTrue(policy.showCommunityTab)
        assertTrue(policy.showSocialAccountActions)
        assertTrue(policy.showGrowthNotifications)
        assertTrue(policy.showStudyJourneyTemplate)
        assertTrue(policy.shouldShowMeetingTemplate("研学考察"))
    }
}
