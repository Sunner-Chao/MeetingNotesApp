package com.oa.automation.infrastructure.db

import com.oa.automation.domain.model.Project
import com.oa.automation.domain.model.ProjectDecisionRef
import com.oa.automation.domain.model.ProjectMeetingLink
import com.oa.automation.domain.model.ProjectRiskRef
import com.oa.automation.domain.model.ProjectTaskRef
import com.oa.automation.domain.model.ProjectAggregateSnapshot
import com.oa.automation.domain.model.buildProjectAggregateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectEntityMappingTest {
    @Test
    fun projectAndLinksRoundTrip() {
        val project = Project(id = "p1", name = "年度计划")
        val link = ProjectMeetingLink(projectId = project.id, meetingId = "m1")
        assertEquals(project, project.toEntity().toDomain())
        assertEquals(link, link.toEntity().toDomain())
    }

    @Test
    fun sourceProjectionsRoundTripWithoutLosingTraceability() {
        val task = ProjectTaskRef("t1", "p1", "m1", "r1", "task-1", "完成方案", assignee = "A")
        val risk = ProjectRiskRef("risk1", "p1", "m1", "r1", "risk-1", "接口阻塞", status = "开放")
        val decision = ProjectDecisionRef("d1", "p1", "m1", "r1", "decision-1", "采用本地优先", confirmed = true)
        assertEquals(task, task.toEntity().toDomain())
        assertEquals(risk, risk.toEntity().toDomain())
        assertEquals(decision, decision.toEntity().toDomain())
    }

    @Test
    fun aggregateSnapshotRoundTripsAsRebuildableReadModel() {
        val snapshot = ProjectAggregateSnapshot("s1", "p1", 3, 4, 1, 2)
        assertEquals(snapshot, snapshot.toEntity().toDomain())
    }

    @Test
    fun aggregateBuilderCountsOnlyActiveLinksAndOpenItems() {
        val snapshot = buildProjectAggregateSnapshot(
            snapshotId = "s1",
            projectId = "p1",
            meetingLinks = listOf(
                ProjectMeetingLink("p1", "m1"),
                ProjectMeetingLink("p1", "m2", removedAt = 12L)
            ),
            tasks = listOf(
                ProjectTaskRef("t1", "p1", "m1", "r1", "1", "open"),
                ProjectTaskRef("t2", "p1", "m1", "r1", "2", "done", completed = true)
            ),
            risks = listOf(
                ProjectRiskRef("r1", "p1", "m1", "r1", "1", "open", status = "开放"),
                ProjectRiskRef("r2", "p1", "m1", "r1", "2", "closed", status = "已关闭")
            ),
            decisions = listOf(
                ProjectDecisionRef("d1", "p1", "m1", "r1", "1", "pending"),
                ProjectDecisionRef("d2", "p1", "m1", "r1", "2", "confirmed", confirmed = true)
            ),
            generatedAt = 99L
        )
        assertEquals(ProjectAggregateSnapshot("s1", "p1", 1, 1, 1, 1, 99L), snapshot)
    }
}
