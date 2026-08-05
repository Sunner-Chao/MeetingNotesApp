package com.oa.automation.domain.repository

import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyStage
import kotlinx.coroutines.flow.Flow

interface JourneyRepository {
    suspend fun create(journey: Journey, initialStage: JourneyStage): Result<Journey>
    suspend fun save(journey: Journey): Result<Journey>
    suspend fun findById(id: String): Result<Journey?>
    suspend fun findByMeetingId(meetingId: String): Result<Journey?>
    fun observeByMeetingId(meetingId: String): Flow<Journey?>
    suspend fun delete(id: String): Result<Unit>

    suspend fun saveStage(stage: JourneyStage): Result<JourneyStage>
    suspend fun findStageById(id: String): Result<JourneyStage?>
    fun observeStages(journeyId: String): Flow<List<JourneyStage>>

    suspend fun saveCurrentStage(
        journey: Journey,
        savedStage: JourneyStage
    ): Result<Journey>

    suspend fun startNextStage(
        journey: Journey,
        nextStage: JourneyStage
    ): Result<Journey>
}
