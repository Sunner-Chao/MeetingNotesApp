package com.oa.automation.domain.repository

import com.oa.automation.domain.model.Project
import com.oa.automation.domain.model.ProjectDecisionRef
import com.oa.automation.domain.model.ProjectMeetingLink
import com.oa.automation.domain.model.ProjectRiskRef
import com.oa.automation.domain.model.ProjectTaskRef
import com.oa.automation.domain.model.ProjectAggregateSnapshot
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun observeProjects(): Flow<List<Project>>
    suspend fun findProject(id: String): Result<Project?>
    suspend fun save(project: Project): Result<Project>
    suspend fun delete(id: String): Result<Unit>
    suspend fun linkMeeting(link: ProjectMeetingLink): Result<ProjectMeetingLink>
    fun observeMeetingLinks(projectId: String): Flow<List<ProjectMeetingLink>>
    suspend fun removeMeeting(projectId: String, meetingId: String): Result<Unit>
    suspend fun saveTask(ref: ProjectTaskRef): Result<ProjectTaskRef>
    fun observeTasks(projectId: String): Flow<List<ProjectTaskRef>>
    suspend fun saveRisk(ref: ProjectRiskRef): Result<ProjectRiskRef>
    fun observeRisks(projectId: String): Flow<List<ProjectRiskRef>>
    suspend fun saveDecision(ref: ProjectDecisionRef): Result<ProjectDecisionRef>
    fun observeDecisions(projectId: String): Flow<List<ProjectDecisionRef>>
    fun observeLatestSnapshot(projectId: String): Flow<ProjectAggregateSnapshot?>
    suspend fun saveSnapshot(snapshot: ProjectAggregateSnapshot): Result<ProjectAggregateSnapshot>
}
