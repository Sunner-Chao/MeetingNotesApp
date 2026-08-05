package com.oa.automation.infrastructure.db

import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.JourneyStageStatus
import com.oa.automation.domain.model.JourneyStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyEntityMappingTest {
    @Test
    fun `journey round trip preserves lifecycle fields`() {
        val journey = Journey(
            id = "journey-1",
            meetingId = "meeting-1",
            title = "城市更新研学",
            status = JourneyStatus.PAUSED,
            currentStageId = "stage-2",
            createdAt = 100L,
            updatedAt = 300L,
            pausedAt = 300L
        )

        assertEquals(journey, journey.toEntity().toDomain())
    }

    @Test
    fun `stage round trip preserves sequence and saved time`() {
        val stage = JourneyStage(
            id = "stage-1",
            journeyId = "journey-1",
            sequenceNumber = 1,
            title = "展馆参访",
            status = JourneyStageStatus.SAVED,
            startedAt = 100L,
            updatedAt = 240L,
            savedAt = 240L
        )

        assertEquals(stage, stage.toEntity().toDomain())
    }

    @Test
    fun `unknown stored statuses fail closed`() {
        assertEquals(JourneyStatus.PAUSED, JourneyStatus.fromStorage("UNKNOWN"))
        assertEquals(JourneyStageStatus.SAVED, JourneyStageStatus.fromStorage("UNKNOWN"))
    }
}
