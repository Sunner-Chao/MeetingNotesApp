package com.oa.automation.infrastructure.db

import com.oa.automation.domain.model.JourneyEdition
import com.oa.automation.domain.model.JourneyEditionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyEditionEntityMappingTest {
    @Test
    fun `journey edition round trip preserves source snapshot and review fields`() {
        val edition = JourneyEdition(
            id = "edition-1",
            journeyId = "journey-1",
            versionNumber = 2,
            content = "# 城市更新研学",
            status = JourneyEditionStatus.CONFIRMED,
            sourceStageDraftIds = listOf("stage-draft-1", "stage-draft-3"),
            sourceStageCount = 2,
            createdAt = 100L,
            updatedAt = 220L,
            confirmedAt = 220L
        )

        assertEquals(edition, edition.toEntity().toDomain())
    }

    @Test
    fun `unknown persisted edition status defaults to draft`() {
        assertEquals(JourneyEditionStatus.DRAFT, JourneyEditionStatus.fromStorage("UNKNOWN"))
    }
}
