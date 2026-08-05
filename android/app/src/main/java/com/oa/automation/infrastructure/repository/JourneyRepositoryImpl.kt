package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.JourneyStageStatus
import com.oa.automation.domain.model.JourneyStatus
import com.oa.automation.domain.repository.JourneyRepository
import com.oa.automation.infrastructure.db.JourneyDao
import com.oa.automation.infrastructure.db.toDomain
import com.oa.automation.infrastructure.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class JourneyRepositoryImpl(
    private val dao: JourneyDao
) : JourneyRepository {
    override suspend fun create(
        journey: Journey,
        initialStage: JourneyStage
    ): Result<Journey> = runCatching {
        require(journey.id.isNotBlank()) { "Journey id must not be blank" }
        require(journey.meetingId.isNotBlank()) { "Meeting id must not be blank" }
        require(journey.status == JourneyStatus.ACTIVE) { "A new journey must be active" }
        require(initialStage.journeyId == journey.id) { "Initial stage belongs to another journey" }
        require(initialStage.sequenceNumber == 1) { "Initial stage sequence must be 1" }
        require(initialStage.status == JourneyStageStatus.ACTIVE) { "Initial stage must be active" }
        require(journey.currentStageId == initialStage.id) { "Initial stage must be current" }

        dao.insertJourneyWithInitialStage(journey.toEntity(), initialStage.toEntity())
        journey
    }

    override suspend fun save(journey: Journey): Result<Journey> = runCatching {
        dao.upsertJourney(journey.toEntity())
        journey
    }

    override suspend fun findById(id: String): Result<Journey?> = runCatching {
        dao.findJourneyById(id)?.toDomain()
    }

    override suspend fun findByMeetingId(meetingId: String): Result<Journey?> = runCatching {
        dao.findJourneyByMeetingId(meetingId)?.toDomain()
    }

    override fun observeByMeetingId(meetingId: String): Flow<Journey?> =
        dao.observeJourneyByMeetingId(meetingId).map { it?.toDomain() }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        dao.deleteJourneyById(id)
    }

    override suspend fun saveStage(stage: JourneyStage): Result<JourneyStage> = runCatching {
        dao.upsertStage(stage.toEntity())
        stage
    }

    override suspend fun findStageById(id: String): Result<JourneyStage?> = runCatching {
        dao.findStageById(id)?.toDomain()
    }

    override fun observeStages(journeyId: String): Flow<List<JourneyStage>> =
        dao.observeStages(journeyId).map { stages -> stages.map { it.toDomain() } }

    override suspend fun saveCurrentStage(
        journey: Journey,
        savedStage: JourneyStage
    ): Result<Journey> = runCatching {
        require(savedStage.journeyId == journey.id) { "Saved stage belongs to another journey" }
        require(savedStage.status == JourneyStageStatus.SAVED) { "Current stage must be saved" }
        require(journey.status == JourneyStatus.ACTIVE) { "Journey must remain active" }
        require(journey.currentStageId == null) { "No active stage should remain after saving" }

        dao.saveCurrentStage(
            journey = journey.toEntity(),
            savedStage = savedStage.toEntity()
        )
        journey
    }

    override suspend fun startNextStage(
        journey: Journey,
        nextStage: JourneyStage
    ): Result<Journey> = runCatching {
        require(nextStage.journeyId == journey.id) { "Next stage belongs to another journey" }
        require(nextStage.status == JourneyStageStatus.ACTIVE) { "Next stage must be active" }
        require(journey.status == JourneyStatus.ACTIVE) { "Journey must be active" }
        require(journey.currentStageId == nextStage.id) { "Next stage must be current" }
        val latestSequence = dao.findLatestStage(journey.id)?.sequenceNumber ?: 0
        require(nextStage.sequenceNumber == latestSequence + 1) {
            "Next stage sequence must follow the last saved stage"
        }

        dao.startNextStage(
            journey = journey.toEntity(),
            nextStage = nextStage.toEntity()
        )
        journey
    }
}
