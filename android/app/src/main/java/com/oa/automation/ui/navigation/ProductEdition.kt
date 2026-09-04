package com.oa.automation.ui.navigation

import com.oa.automation.domain.model.ProductEdition

/** Product-facing entry configuration; storage and APIs remain shared. */
data class ProductEntryPolicy(
    val showCommunityTab: Boolean,
    val showSocialAccountActions: Boolean,
    val showGrowthNotifications: Boolean,
    val showStudyJourneyTemplate: Boolean,
    val showProjectWorkspace: Boolean,
    private val visibleMeetingTemplateNames: Set<String>
) {
    /** Keep persisted meeting templates readable without exposing retired choices for new work. */
    fun shouldShowMeetingTemplate(templateName: String, preserveSelectedLegacy: Boolean = false): Boolean {
        val normalized = templateName.trim()
        return normalized.isNotBlank() &&
            (preserveSelectedLegacy || normalized in visibleMeetingTemplateNames)
    }

    companion object {
        fun forEdition(edition: ProductEdition): ProductEntryPolicy = when (edition) {
            ProductEdition.SOCIAL -> ProductEntryPolicy(
                showCommunityTab = true,
                showSocialAccountActions = true,
                showGrowthNotifications = true,
                showStudyJourneyTemplate = true,
                showProjectWorkspace = false,
                visibleMeetingTemplateNames = SOCIAL_MEETING_TEMPLATE_NAMES
            )
            ProductEdition.LIGHT_ENJOY -> ProductEntryPolicy(
                showCommunityTab = false,
                showSocialAccountActions = false,
                showGrowthNotifications = false,
                showStudyJourneyTemplate = false,
                showProjectWorkspace = true,
                visibleMeetingTemplateNames = LIGHT_ENJOY_MEETING_TEMPLATE_NAMES
            )
        }

        /** Frozen social catalog; additions for the light edition must not leak into it. */
        private val SOCIAL_MEETING_TEMPLATE_NAMES = setOf(
            "通用会议",
            "项目管理",
            "论坛会议",
            "研学考察"
        )

        /** The selectable catalog for new meetings in the light enjoyment edition. */
        private val LIGHT_ENJOY_MEETING_TEMPLATE_NAMES = setOf(
            "宣贯·落实会",
            "推演·进度会",
            "启迪·共创会",
            "博弈·洽谈会",
            "复盘·分析会",
            "敏捷·站会",
            "论坛·共识会",
            "自定义会议"
        )
    }
}
