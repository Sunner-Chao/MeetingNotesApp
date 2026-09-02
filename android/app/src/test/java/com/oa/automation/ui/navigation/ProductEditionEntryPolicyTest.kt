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
    }

    @Test
    fun socialEditionRetainsCommunityAndAccountEntryPoints() {
        val policy = ProductEntryPolicy.forEdition(ProductEdition.SOCIAL)

        assertTrue(policy.showCommunityTab)
        assertTrue(policy.showSocialAccountActions)
        assertTrue(policy.showGrowthNotifications)
    }
}
