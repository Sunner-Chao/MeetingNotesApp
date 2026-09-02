package com.oa.automation.ui.navigation

import com.oa.automation.domain.model.ProductEdition

/** Product-facing entry configuration; storage and APIs remain shared. */
data class ProductEntryPolicy(
    val showCommunityTab: Boolean,
    val showSocialAccountActions: Boolean,
    val showGrowthNotifications: Boolean
) {
    companion object {
        fun forEdition(edition: ProductEdition): ProductEntryPolicy = when (edition) {
            ProductEdition.SOCIAL -> ProductEntryPolicy(
                showCommunityTab = true,
                showSocialAccountActions = true,
                showGrowthNotifications = true
            )
            ProductEdition.LIGHT_ENJOY -> ProductEntryPolicy(
                showCommunityTab = false,
                showSocialAccountActions = false,
                showGrowthNotifications = false
            )
        }
    }
}
