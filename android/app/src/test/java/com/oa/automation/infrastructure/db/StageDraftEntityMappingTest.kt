package com.oa.automation.infrastructure.db

import com.oa.automation.domain.model.StageDraftStatus
import com.oa.automation.domain.model.StageDraftVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class StageDraftEntityMappingTest {
    @Test
    fun `stage draft round trip preserves review and evidence fields`() {
        val draft = StageDraftVersion(
            id = "draft-1",
            stageId = "stage-2",
            versionNumber = 3,
            content = "# 展馆参访\n\n现场观察：待确认",
            status = StageDraftStatus.CONFIRMED,
            evidenceTranscriptCount = 4,
            evidenceAttachmentCount = 7,
            createdAt = 100L,
            updatedAt = 180L,
            confirmedAt = 180L
        )

        assertEquals(draft, draft.toEntity().toDomain())
    }

    @Test
    fun `unknown persisted status defaults to editable draft`() {
        assertEquals(StageDraftStatus.DRAFT, StageDraftStatus.fromStorage("UNKNOWN"))
    }
}
