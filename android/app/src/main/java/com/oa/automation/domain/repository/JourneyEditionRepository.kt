package com.oa.automation.domain.repository

import com.oa.automation.domain.model.JourneyEdition
import kotlinx.coroutines.flow.Flow

interface JourneyEditionRepository {
    suspend fun createEdition(
        journeyId: String,
        content: String,
        sourceStageDraftIds: List<String>,
        sourceStageCount: Int
    ): Result<JourneyEdition>

    fun observeLatest(journeyId: String): Flow<JourneyEdition?>

    suspend fun saveEdition(id: String, content: String): Result<JourneyEdition>

    suspend fun confirmEdition(id: String): Result<JourneyEdition>
}
