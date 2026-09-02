package com.oa.automation.infrastructure.db

import com.oa.automation.domain.model.Project
import com.oa.automation.domain.model.ProjectDecisionRef
import com.oa.automation.domain.model.ProjectMeetingLink
import com.oa.automation.domain.model.ProjectRiskRef
import com.oa.automation.domain.model.ProjectTaskRef
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
}
