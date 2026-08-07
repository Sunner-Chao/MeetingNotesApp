package com.oa.automation.infrastructure.acceptance

import com.oa.automation.application.usecase.PreciseCoordinateSanitizer
import com.oa.automation.domain.model.CommunitySyncOperation
import com.oa.automation.domain.model.CommunitySyncStatus
import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyEditionStatus
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.JourneyStageStatus
import com.oa.automation.domain.model.PublishedPostStatus
import com.oa.automation.infrastructure.community.CommunitySyncEnqueuer
import com.oa.automation.infrastructure.db.CommunitySyncOutboxDao
import com.oa.automation.infrastructure.db.CommunitySyncOutboxEntity
import com.oa.automation.infrastructure.db.ConfirmedJourneyStageDraftRow
import com.oa.automation.infrastructure.db.JourneyDao
import com.oa.automation.infrastructure.db.JourneyEditionDao
import com.oa.automation.infrastructure.db.JourneyEditionEntity
import com.oa.automation.infrastructure.db.JourneyEntity
import com.oa.automation.infrastructure.db.JourneyStageEntity
import com.oa.automation.infrastructure.db.PublishedPostDao
import com.oa.automation.infrastructure.db.PublishedPostEntity
import com.oa.automation.infrastructure.db.StageDraftDao
import com.oa.automation.infrastructure.db.StageDraftVersionEntity
import com.oa.automation.infrastructure.repository.CommunitySyncRepositoryImpl
import com.oa.automation.infrastructure.repository.JourneyEditionRepositoryImpl
import com.oa.automation.infrastructure.repository.JourneyRepositoryImpl
import com.oa.automation.infrastructure.repository.PublishedPostRepositoryImpl
import com.oa.automation.infrastructure.repository.StageDraftRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-layer contract for the resumable study-tour workflow. The store models Room's
 * durable tables so recreating repositories represents a process restart without losing
 * a paused stage, confirmed source or community outbox intent.
 */
class StudyJourneyPublicationAcceptanceTest {
    @Test
    fun `paused stages become an immutable edition and a reviewable redacted post`() = runBlocking {
        val store = AcceptanceStore()
        val journeyRepository = JourneyRepositoryImpl(FakeJourneyDao(store))
        val draftRepository = StageDraftRepositoryImpl(FakeStageDraftDao(store))
        val editionRepository = JourneyEditionRepositoryImpl(FakeJourneyEditionDao(store))
        val postRepository = PublishedPostRepositoryImpl(FakePublishedPostDao(store))

        val firstStage = stage("stage-1", 1, "园林观察")
        val journey = journey(currentStageId = firstStage.id)
        journeyRepository.create(journey, firstStage).getOrThrow()

        val firstSavedStage = firstStage.copy(
            status = JourneyStageStatus.SAVED,
            savedAt = 200L,
            updatedAt = 200L
        )
        val pausedJourney = journey.copy(
            currentStageId = null,
            pausedAt = 200L,
            updatedAt = 200L
        )
        journeyRepository.saveCurrentStage(pausedJourney, firstSavedStage).getOrThrow()

        val firstDraft = draftRepository.createDraft(
            stageId = firstStage.id,
            content = "现场观察：园林借景。\n地点：拙政园",
            evidenceTranscriptCount = 2,
            evidenceAttachmentCount = 4
        ).getOrThrow()
        draftRepository.confirmDraft(firstDraft.id).getOrThrow()

        // Continue later: no empty stage is created until the next real segment starts.
        val secondStage = stage("stage-2", 2, "讲解交流").copy(startedAt = 300L, updatedAt = 300L)
        val resumedJourney = pausedJourney.copy(currentStageId = secondStage.id, updatedAt = 300L)
        journeyRepository.startNextStage(resumedJourney, secondStage).getOrThrow()
        val secondSavedStage = secondStage.copy(
            status = JourneyStageStatus.SAVED,
            savedAt = 400L,
            updatedAt = 400L
        )
        val waitingJourney = resumedJourney.copy(currentStageId = null, updatedAt = 400L)
        journeyRepository.saveCurrentStage(waitingJourney, secondSavedStage).getOrThrow()

        val secondDraft = draftRepository.createDraft(
            stageId = secondStage.id,
            content = "讲解摘录：保留原有水系。\n#建筑更新",
            evidenceTranscriptCount = 3,
            evidenceAttachmentCount = 1
        ).getOrThrow()
        draftRepository.confirmDraft(secondDraft.id).getOrThrow()

        val confirmed = draftRepository.findLatestConfirmedByJourneyId(journey.id).getOrThrow()
        assertEquals(listOf("stage-1", "stage-2"), confirmed.map { it.stageId })
        assertEquals(listOf(1, 2), confirmed.map { it.sequenceNumber })

        val edition = editionRepository.createEdition(
            journeyId = journey.id,
            content = "# 城市更新研学\n\n现场坐标 31.2304, 121.4737。\n${firstDraft.content}\n${secondDraft.content}",
            sourceStageDraftIds = confirmed.map { it.draft.id },
            sourceStageCount = confirmed.size
        ).getOrThrow()
        val confirmedEdition = editionRepository.confirmEdition(edition.id).getOrThrow()
        assertEquals(JourneyEditionStatus.CONFIRMED, confirmedEdition.status)
        assertTrue(editionRepository.saveEdition(edition.id, "不得覆盖已确认总游记").isFailure)

        val sanitized = PreciseCoordinateSanitizer.sanitize(confirmedEdition.content)
        assertEquals(1, sanitized.redactedCount)
        assertTrue(sanitized.content.contains("[精确位置已移除]"))
        val post = postRepository.createReviewSnapshot(
            journeyId = journey.id,
            journeyEditionId = confirmedEdition.id,
            sourceEditionVersion = confirmedEdition.versionNumber,
            title = journey.title,
            content = sanitized.content,
            redactedCoordinateCount = sanitized.redactedCount,
            destination = "苏州",
            travelDate = "2026-08-07",
            travelDays = 1,
            stageTitles = confirmed.map { it.stageTitle },
            tags = listOf("建筑更新"),
            pois = listOf("拙政园")
        ).getOrThrow()
        assertEquals(PublishedPostStatus.REVIEW, post.status)
        assertEquals(listOf("园林观察", "讲解交流"), post.stageTitles)
        assertEquals(1, post.redactedCoordinateCount)
        assertTrue(postRepository.markReady(post.id).isFailure)
        postRepository.saveReview(post.id, privacyReviewed = true, rightsConfirmed = true).getOrThrow()
        val ready = postRepository.markReady(post.id).getOrThrow()
        assertEquals(PublishedPostStatus.READY, ready.status)

        val firstSyncRepository = CommunitySyncRepositoryImpl(
            FakeOutboxDao(store),
            FakePublishedPostDao(store),
            CommunitySyncEnqueuer { store.enqueuedPostIds += it }
        )
        val uploaded = firstSyncRepository.enqueueUpload(post.id).getOrThrow()
        assertEquals(CommunitySyncOperation.UPLOAD, uploaded.operation)
        assertEquals(CommunitySyncStatus.PENDING, uploaded.status)

        // A publish tap while an upload is in flight replaces the desired operation,
        // while the durable outbox remains available to a newly created repository.
        store.outbox = store.outbox!!.copy(status = CommunitySyncStatus.UPLOADING.name)
        val publishRequested = firstSyncRepository.requestPublish(post.id).getOrThrow()
        assertEquals(CommunitySyncOperation.PUBLISH, publishRequested.operation)
        assertEquals(CommunitySyncStatus.PENDING, publishRequested.status)

        val afterRestart = CommunitySyncRepositoryImpl(
            FakeOutboxDao(store),
            FakePublishedPostDao(store),
            CommunitySyncEnqueuer { store.enqueuedPostIds += it }
        ).observe(post.id).first()!!
        assertEquals(CommunitySyncOperation.PUBLISH, afterRestart.operation)
        assertEquals(CommunitySyncStatus.PENDING, afterRestart.status)
        assertEquals(listOf(post.id, post.id), store.enqueuedPostIds)
    }

    private fun journey(currentStageId: String) = Journey(
        id = "journey-1",
        meetingId = "meeting-1",
        title = "城市更新研学",
        currentStageId = currentStageId,
        createdAt = 100L,
        updatedAt = 100L
    )

    private fun stage(id: String, sequence: Int, title: String) = JourneyStage(
        id = id,
        journeyId = "journey-1",
        sequenceNumber = sequence,
        title = title,
        startedAt = 100L,
        updatedAt = 100L
    )

    private class AcceptanceStore {
        val journeys = linkedMapOf<String, JourneyEntity>()
        val stages = linkedMapOf<String, JourneyStageEntity>()
        val drafts = linkedMapOf<String, StageDraftVersionEntity>()
        val editions = linkedMapOf<String, JourneyEditionEntity>()
        val posts = linkedMapOf<String, PublishedPostEntity>()
        var outbox: CommunitySyncOutboxEntity? = null
        val enqueuedPostIds = mutableListOf<String>()
    }

    private class FakeJourneyDao(private val store: AcceptanceStore) : JourneyDao {
        override suspend fun insertJourney(entity: JourneyEntity) {
            check(store.journeys.putIfAbsent(entity.id, entity) == null)
        }

        override suspend fun upsertJourney(entity: JourneyEntity) {
            store.journeys[entity.id] = entity
        }

        override suspend fun findJourneyById(id: String) = store.journeys[id]

        override suspend fun findJourneyByMeetingId(meetingId: String) =
            store.journeys.values.firstOrNull { it.meetingId == meetingId }

        override fun observeJourneyByMeetingId(meetingId: String): Flow<JourneyEntity?> =
            MutableStateFlow(store.journeys.values.firstOrNull { it.meetingId == meetingId })

        override suspend fun deleteJourneyById(id: String) {
            store.journeys.remove(id)
            store.stages.entries.removeAll { it.value.journeyId == id }
        }

        override suspend fun insertStage(entity: JourneyStageEntity) {
            check(store.stages.putIfAbsent(entity.id, entity) == null)
        }

        override suspend fun upsertStage(entity: JourneyStageEntity) {
            store.stages[entity.id] = entity
        }

        override suspend fun findStageById(id: String) = store.stages[id]

        override suspend fun findLatestStage(journeyId: String) =
            store.stages.values.filter { it.journeyId == journeyId }.maxByOrNull { it.sequenceNumber }

        override fun observeStages(journeyId: String): Flow<List<JourneyStageEntity>> = MutableStateFlow(
            store.stages.values.filter { it.journeyId == journeyId }.sortedBy { it.sequenceNumber }
        )

        override suspend fun insertJourneyWithInitialStage(journey: JourneyEntity, initialStage: JourneyStageEntity) {
            insertJourney(journey)
            insertStage(initialStage)
        }

        override suspend fun saveCurrentStage(journey: JourneyEntity, savedStage: JourneyStageEntity) {
            upsertStage(savedStage)
            upsertJourney(journey)
        }

        override suspend fun startNextStage(journey: JourneyEntity, nextStage: JourneyStageEntity) {
            insertStage(nextStage)
            upsertJourney(journey)
        }
    }

    private class FakeStageDraftDao(private val store: AcceptanceStore) : StageDraftDao {
        override suspend fun insert(entity: StageDraftVersionEntity) {
            check(store.drafts.putIfAbsent(entity.id, entity) == null)
        }

        override suspend fun findLatest(stageId: String) =
            store.drafts.values.filter { it.stageId == stageId }.maxByOrNull { it.versionNumber }

        override fun observeLatest(stageId: String): Flow<StageDraftVersionEntity?> = MutableStateFlow(
            store.drafts.values.filter { it.stageId == stageId }.maxByOrNull { it.versionNumber }
        )

        override suspend fun findLatestConfirmedByJourneyId(journeyId: String): List<ConfirmedJourneyStageDraftRow> =
            store.stages.values.filter { it.journeyId == journeyId }.sortedBy { it.sequenceNumber }.mapNotNull { stage ->
                store.drafts.values
                    .filter { it.stageId == stage.id && it.status == "CONFIRMED" }
                    .maxByOrNull { it.versionNumber }
                    ?.let { draft ->
                        ConfirmedJourneyStageDraftRow(
                            stageId = stage.id,
                            sequenceNumber = stage.sequenceNumber,
                            stageTitle = stage.title,
                            stageSavedAt = stage.savedAt,
                            draftId = draft.id,
                            versionNumber = draft.versionNumber,
                            content = draft.content,
                            status = draft.status,
                            evidenceTranscriptCount = draft.evidenceTranscriptCount,
                            evidenceAttachmentCount = draft.evidenceAttachmentCount,
                            createdAt = draft.createdAt,
                            updatedAt = draft.updatedAt,
                            confirmedAt = draft.confirmedAt
                        )
                    }
            }

        override suspend fun findById(id: String) = store.drafts[id]

        override suspend fun findByIds(ids: List<String>) = ids.mapNotNull(store.drafts::get)

        override suspend fun updateDraftContent(id: String, content: String, updatedAt: Long): Int {
            val current = store.drafts[id] ?: return 0
            if (current.status != "DRAFT") return 0
            store.drafts[id] = current.copy(content = content, updatedAt = updatedAt)
            return 1
        }

        override suspend fun markConfirmed(id: String, confirmedAt: Long): Int {
            val current = store.drafts[id] ?: return 0
            if (current.status != "DRAFT") return 0
            store.drafts[id] = current.copy(status = "CONFIRMED", confirmedAt = confirmedAt, updatedAt = confirmedAt)
            return 1
        }
    }

    private class FakeJourneyEditionDao(private val store: AcceptanceStore) : JourneyEditionDao {
        override suspend fun insert(entity: JourneyEditionEntity) {
            check(store.editions.putIfAbsent(entity.id, entity) == null)
        }

        override suspend fun findLatest(journeyId: String) =
            store.editions.values.filter { it.journeyId == journeyId }.maxByOrNull { it.versionNumber }

        override fun observeLatest(journeyId: String): Flow<JourneyEditionEntity?> = MutableStateFlow(
            store.editions.values.filter { it.journeyId == journeyId }.maxByOrNull { it.versionNumber }
        )

        override suspend fun findById(id: String) = store.editions[id]

        override suspend fun updateEditionContent(id: String, content: String, updatedAt: Long): Int {
            val current = store.editions[id] ?: return 0
            if (current.status != "DRAFT") return 0
            store.editions[id] = current.copy(content = content, updatedAt = updatedAt)
            return 1
        }

        override suspend fun markConfirmed(id: String, confirmedAt: Long): Int {
            val current = store.editions[id] ?: return 0
            if (current.status != "DRAFT") return 0
            store.editions[id] = current.copy(status = "CONFIRMED", confirmedAt = confirmedAt, updatedAt = confirmedAt)
            return 1
        }
    }

    private class FakePublishedPostDao(private val store: AcceptanceStore) : PublishedPostDao {
        override suspend fun insert(entity: PublishedPostEntity) {
            check(store.posts.putIfAbsent(entity.id, entity) == null)
        }

        override suspend fun findLatest(journeyId: String) =
            store.posts.values.filter { it.journeyId == journeyId }.maxByOrNull { it.versionNumber }

        override fun observeLatest(journeyId: String): Flow<PublishedPostEntity?> = MutableStateFlow(
            store.posts.values.filter { it.journeyId == journeyId }.maxByOrNull { it.versionNumber }
        )

        override suspend fun findById(id: String) = store.posts[id]

        override suspend fun updateReview(id: String, privacyReviewed: Boolean, rightsConfirmed: Boolean, updatedAt: Long): Int {
            val current = store.posts[id] ?: return 0
            if (current.status != "REVIEW") return 0
            store.posts[id] = current.copy(privacyReviewed = privacyReviewed, rightsConfirmed = rightsConfirmed, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateMetadata(id: String, destination: String, travelDate: String, travelDays: Int, tags: List<String>, pois: List<String>, updatedAt: Long): Int {
            val current = store.posts[id] ?: return 0
            if (current.status != "REVIEW") return 0
            store.posts[id] = current.copy(destination = destination, travelDate = travelDate, travelDays = travelDays, tags = tags, pois = pois, updatedAt = updatedAt)
            return 1
        }

        override suspend fun markReady(id: String, readyAt: Long): Int {
            val current = store.posts[id] ?: return 0
            if (current.status != "REVIEW" || !current.privacyReviewed || !current.rightsConfirmed) return 0
            store.posts[id] = current.copy(status = "READY", readyAt = readyAt, updatedAt = readyAt)
            return 1
        }

        override suspend fun markWithdrawn(id: String, withdrawnAt: Long): Int {
            val current = store.posts[id] ?: return 0
            if (current.status != "READY") return 0
            store.posts[id] = current.copy(status = "WITHDRAWN", withdrawnAt = withdrawnAt, updatedAt = withdrawnAt)
            return 1
        }
    }

    private class FakeOutboxDao(private val store: AcceptanceStore) : CommunitySyncOutboxDao {
        private val state = MutableStateFlow<CommunitySyncOutboxEntity?>(store.outbox)

        override suspend fun upsert(entity: CommunitySyncOutboxEntity) {
            store.outbox = entity
            state.value = entity
        }

        override suspend fun findByPostId(postId: String) = store.outbox?.takeIf { it.postId == postId }

        override fun observe(postId: String): Flow<CommunitySyncOutboxEntity?> = state

        override suspend fun updateIntent(postId: String, operation: String, status: String, updatedAt: Long): Int {
            val current = store.outbox?.takeIf { it.postId == postId } ?: return 0
            store.outbox = current.copy(operation = operation, status = status, lastError = null, updatedAt = updatedAt)
            state.value = store.outbox
            return 1
        }

        override suspend fun markRunning(postId: String, status: String, attemptCount: Int, updatedAt: Long): Int {
            val current = store.outbox?.takeIf { it.postId == postId } ?: return 0
            store.outbox = current.copy(status = status, attemptCount = attemptCount, lastError = null, updatedAt = updatedAt)
            state.value = store.outbox
            return 1
        }

        override suspend fun markRemoteState(postId: String, status: String, remotePostId: String?, updatedAt: Long): Int {
            val current = store.outbox?.takeIf { it.postId == postId } ?: return 0
            store.outbox = current.copy(status = status, remotePostId = remotePostId, lastError = null, updatedAt = updatedAt)
            state.value = store.outbox
            return 1
        }

        override suspend fun markFailed(postId: String, attemptCount: Int, lastError: String, updatedAt: Long): Int {
            val current = store.outbox?.takeIf { it.postId == postId } ?: return 0
            store.outbox = current.copy(status = "FAILED", attemptCount = attemptCount, lastError = lastError, updatedAt = updatedAt)
            state.value = store.outbox
            return 1
        }
    }
}
