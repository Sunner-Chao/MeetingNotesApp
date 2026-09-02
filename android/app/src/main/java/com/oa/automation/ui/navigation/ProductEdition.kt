package com.oa.automation.ui.navigation

import com.oa.automation.domain.model.ProductEdition

/** Product-facing entry configuration; storage and APIs remain shared. */
data class ProductEntryPolicy(
    val showCommunityTab: Boolean,
    val showSocialAccountActions: Boolean,
    val showGrowthNotifications: Boolean,
    val showStudyJourneyTemplate: Boolean,
    val showProjectWorkspace: Boolean
) {
    /** Keep old study records readable, while hiding the child-product template for new work. */
    fun shouldShowMeetingTemplate(templateName: String, preserveSelectedLegacy: Boolean = false): Boolean {
        if (showStudyJourneyTemplate || preserveSelectedLegacy) return true
        val normalized = templateName.trim()
        return normalized.isNotBlank() &&
            normalized != "研学考察" &&
            !normalized.contains("参观考察") &&
            !normalized.contains("游记")
    }

    companion object {
        fun forEdition(edition: ProductEdition): ProductEntryPolicy = when (edition) {
            ProductEdition.SOCIAL -> ProductEntryPolicy(
                showCommunityTab = true,
                showSocialAccountActions = true,
                showGrowthNotifications = true,
                showStudyJourneyTemplate = true,
                showProjectWorkspace = false
            )
            ProductEdition.LIGHT_ENJOY -> ProductEntryPolicy(
                showCommunityTab = false,
                showSocialAccountActions = false,
                showGrowthNotifications = false,
                showStudyJourneyTemplate = false,
                showProjectWorkspace = true
            )
        }
    }
}
