package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.Project
import com.oa.automation.domain.model.ProjectDecisionRef
import com.oa.automation.domain.model.ProjectMeetingLink
import com.oa.automation.domain.model.ProjectRiskRef
import com.oa.automation.domain.model.ProjectTaskRef
import com.oa.automation.domain.repository.ProjectRepository
import com.oa.automation.infrastructure.db.ProjectDao
import com.oa.automation.infrastructure.db.toDomain
import com.oa.automation.infrastructure.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProjectRepositoryImpl(private val dao: ProjectDao) : ProjectRepository {
    override fun observeProjects(): Flow<List<Project>> =
        dao.observeProjects().map { list -> list.map { it.toDomain() } }

    override suspend fun findProject(id: String): Result<Project?> = runCatching {
        dao.findProject(id)?.toDomain()
    }

    override suspend fun save(project: Project): Result<Project> = runCatching {
        require(project.id.isNotBlank()) { "Project id must not be blank" }
        require(project.name.isNotBlank()) { "Project name must not be blank" }
        dao.upsertProject(project.toEntity())
        project
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        dao.markProjectDeleted(id, System.currentTimeMillis())
    }

    override suspend fun linkMeeting(link: ProjectMeetingLink): Result<ProjectMeetingLink> = runCatching {
        require(link.projectId.isNotBlank()) { "Project id must not be blank" }
        require(link.meetingId.isNotBlank()) { "Meeting id must not be blank" }
        dao.upsertMeetingLink(link.toEntity())
        link
    }

    override fun observeMeetingLinks(projectId: String): Flow<List<ProjectMeetingLink>> =
        dao.observeMeetingLinks(projectId).map { list -> list.map { it.toDomain() } }

    override suspend fun removeMeeting(projectId: String, meetingId: String): Result<Unit> = runCatching {
        dao.removeMeetingLink(projectId, meetingId, System.currentTimeMillis())
    }

    override suspend fun saveTask(ref: ProjectTaskRef): Result<ProjectTaskRef> = runCatching {
        require(ref.projectId.isNotBlank() && ref.sourceReportId.isNotBlank()) { "Task source is incomplete" }
        dao.upsertTaskRef(ref.toEntity())
        ref
    }

    override fun observeTasks(projectId: String): Flow<List<ProjectTaskRef>> =
        dao.observeTaskRefs(projectId).map { list -> list.map { it.toDomain() } }

    override suspend fun saveRisk(ref: ProjectRiskRef): Result<ProjectRiskRef> = runCatching {
        require(ref.projectId.isNotBlank() && ref.sourceReportId.isNotBlank()) { "Risk source is incomplete" }
        dao.upsertRiskRef(ref.toEntity())
        ref
    }

    override fun observeRisks(projectId: String): Flow<List<ProjectRiskRef>> =
        dao.observeRiskRefs(projectId).map { list -> list.map { it.toDomain() } }

    override suspend fun saveDecision(ref: ProjectDecisionRef): Result<ProjectDecisionRef> = runCatching {
        require(ref.projectId.isNotBlank() && ref.sourceReportId.isNotBlank()) { "Decision source is incomplete" }
        dao.upsertDecisionRef(ref.toEntity())
        ref
    }

    override fun observeDecisions(projectId: String): Flow<List<ProjectDecisionRef>> =
        dao.observeDecisionRefs(projectId).map { list -> list.map { it.toDomain() } }
}
