package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Upsert
    suspend fun upsertProject(entity: ProjectEntity)

    @Query("SELECT * FROM projects WHERE status != 'DELETED' ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun findProject(id: String): ProjectEntity?

    @Query("UPDATE projects SET status = 'DELETED', deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun markProjectDeleted(id: String, deletedAt: Long): Int

    @Upsert
    suspend fun upsertMeetingLink(entity: ProjectMeetingLinkEntity)

    @Query("SELECT * FROM project_meeting_links WHERE projectId = :projectId AND removedAt IS NULL ORDER BY linkedAt ASC")
    fun observeMeetingLinks(projectId: String): Flow<List<ProjectMeetingLinkEntity>>

    @Query("UPDATE project_meeting_links SET removedAt = :removedAt WHERE projectId = :projectId AND meetingId = :meetingId")
    suspend fun removeMeetingLink(projectId: String, meetingId: String, removedAt: Long)

    @Query("SELECT * FROM project_task_refs WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeTaskRefs(projectId: String): Flow<List<ProjectTaskRefEntity>>

    @Upsert
    suspend fun upsertTaskRef(entity: ProjectTaskRefEntity)

    @Query("SELECT * FROM project_risk_refs WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeRiskRefs(projectId: String): Flow<List<ProjectRiskRefEntity>>

    @Upsert
    suspend fun upsertRiskRef(entity: ProjectRiskRefEntity)

    @Query("SELECT * FROM project_decision_refs WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeDecisionRefs(projectId: String): Flow<List<ProjectDecisionRefEntity>>

    @Upsert
    suspend fun upsertDecisionRef(entity: ProjectDecisionRefEntity)
}
