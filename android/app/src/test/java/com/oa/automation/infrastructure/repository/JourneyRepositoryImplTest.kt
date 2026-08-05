package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.JourneyStageStatus
import com.oa.automation.infrastructure.db.JourneyDao
import com.oa.automation.infrastructure.db.JourneyEntity
import com.oa.automation.infrastructure.db.JourneyStageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyRepositoryImplTest {
    @Test
    fun `create persists journey and initial stage`() = runBlocking {
        val dao = FakeJourneyDao()
        val repository = JourneyRepositoryImpl(dao)
        val stage = activeStage(id = "stage-1", sequenceNumber = 1)
        val journey = activeJourney(currentStageId = stage.id)

        assertEquals(journey, repository.create(journey, stage).getOrThrow())
        assertEquals(journey, repository.findByMeetingId("meeting-1").getOrThrow())
        assertEquals(listOf(stage), repository.observeStages("journey-1").first())
    }

    @Test
    fun `saving a current stage clears the active stage without creating an empty next stage`() = runBlocking {
        val dao = FakeJourneyDao()
        val repository = JourneyRepositoryImpl(dao)
        val first = activeStage(id = "stage-1", sequenceNumber = 1)
        repository.create(activeJourney(currentStageId = first.id), first).getOrThrow()

        val savedFirst = first.copy(
            status = JourneyStageStatus.SAVED,
            updatedAt = 200L,
            savedAt = 200L
        )
        val savedJourney = activeJourney(currentStageId = first.id, updatedAt = 200L).copy(
            currentStageId = null
        )

        repository.saveCurrentStage(savedJourney, savedFirst).getOrThrow()

        assertEquals(savedJourney, repository.findById("journey-1").getOrThrow())
        assertEquals(listOf(savedFirst), repository.observeStages("journey-1").first())
    }

    @Test
    fun `continuing a saved journey creates the next stage only when requested`() = runBlocking {
        val dao = FakeJourneyDao()
        val repository = JourneyRepositoryImpl(dao)
        val first = activeStage(id = "stage-1", sequenceNumber = 1)
        repository.create(activeJourney(currentStageId = first.id), first).getOrThrow()

        val savedFirst = first.copy(status = JourneyStageStatus.SAVED, savedAt = 200L, updatedAt = 200L)
        val waitingJourney = activeJourney(currentStageId = first.id, updatedAt = 200L).copy(
            currentStageId = null
        )
        repository.saveCurrentStage(waitingJourney, savedFirst).getOrThrow()

        val second = activeStage(id = "stage-2", sequenceNumber = 2, startedAt = 240L)
        val resumedJourney = waitingJourney.copy(currentStageId = second.id, updatedAt = 240L)
        repository.startNextStage(resumedJourney, second).getOrThrow()

        assertEquals(resumedJourney, repository.findById("journey-1").getOrThrow())
        assertEquals(listOf(savedFirst, second), repository.observeStages("journey-1").first())
    }

    @Test
    fun `next stage can only start when it becomes the current stage`() = runBlocking {
        val dao = FakeJourneyDao()
        val repository = JourneyRepositoryImpl(dao)
        val next = activeStage(id = "stage-2", sequenceNumber = 2)

        val result = repository.startNextStage(
            journey = activeJourney(currentStageId = "other-stage"),
            nextStage = next
        )

        assertTrue(result.isFailure)
        assertTrue(dao.journeys.isEmpty())
        assertTrue(dao.stages.isEmpty())
    }

    private fun activeJourney(
        currentStageId: String,
        updatedAt: Long = 100L
    ) = Journey(
        id = "journey-1",
        meetingId = "meeting-1",
        title = "城市更新研学",
        currentStageId = currentStageId,
        createdAt = 100L,
        updatedAt = updatedAt
    )

    private fun activeStage(
        id: String,
        sequenceNumber: Int,
        startedAt: Long = 100L
    ) = JourneyStage(
        id = id,
        journeyId = "journey-1",
        sequenceNumber = sequenceNumber,
        title = "第${sequenceNumber}段",
        startedAt = startedAt,
        updatedAt = startedAt
    )

    private class FakeJourneyDao : JourneyDao {
        val journeys = linkedMapOf<String, JourneyEntity>()
        val stages = linkedMapOf<String, JourneyStageEntity>()
        private val journeyUpdates = MutableStateFlow<List<JourneyEntity>>(emptyList())
        private val stageUpdates = MutableStateFlow<List<JourneyStageEntity>>(emptyList())

        override suspend fun insertJourney(entity: JourneyEntity) {
            check(journeys.putIfAbsent(entity.id, entity) == null)
            publishJourneys()
        }

        override suspend fun upsertJourney(entity: JourneyEntity) {
            journeys[entity.id] = entity
            publishJourneys()
        }

        override suspend fun findJourneyById(id: String): JourneyEntity? = journeys[id]

        override suspend fun findJourneyByMeetingId(meetingId: String): JourneyEntity? =
            journeys.values.firstOrNull { it.meetingId == meetingId }

        override fun observeJourneyByMeetingId(meetingId: String): Flow<JourneyEntity?> =
            MutableStateFlow(journeys.values.firstOrNull { it.meetingId == meetingId })

        override suspend fun deleteJourneyById(id: String) {
            journeys.remove(id)
            stages.entries.removeAll { it.value.journeyId == id }
            publishJourneys()
            publishStages()
        }

        override suspend fun insertStage(entity: JourneyStageEntity) {
            check(stages.putIfAbsent(entity.id, entity) == null)
            publishStages()
        }

        override suspend fun upsertStage(entity: JourneyStageEntity) {
            stages[entity.id] = entity
            publishStages()
        }

        override suspend fun findStageById(id: String): JourneyStageEntity? = stages[id]

        override suspend fun findLatestStage(journeyId: String): JourneyStageEntity? =
            stages.values.filter { it.journeyId == journeyId }.maxByOrNull { it.sequenceNumber }

        override fun observeStages(journeyId: String): Flow<List<JourneyStageEntity>> =
            MutableStateFlow(
                stages.values.filter { it.journeyId == journeyId }.sortedBy { it.sequenceNumber }
            )

        private fun publishJourneys() {
            journeyUpdates.value = journeys.values.toList()
        }

        private fun publishStages() {
            stageUpdates.value = stages.values.toList()
        }
    }
}
