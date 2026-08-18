package com.oa.automation.ui.screen.community

import org.junit.Assert.assertEquals
import org.junit.Test

class CommunityQuickTopicsTest {
    @Test
    fun quickTopicsKeepAllOptionCurrentSelectionAndRemoveDuplicates() {
        val topics = communityQuickTopics(
            CommunityUiState(
                tagFilter = "团队学习",
                facets = com.oa.automation.domain.model.CommunityFacets(
                    tags = listOf("团队学习", "现场观察", "团队学习", "")
                )
            )
        )

        assertEquals(listOf("", "团队学习", "现场观察"), topics)
    }

    @Test
    fun quickTopicsAlwaysStartWithAllOption() {
        assertEquals(
            listOf(""),
            communityQuickTopics(CommunityUiState())
        )
    }
}
