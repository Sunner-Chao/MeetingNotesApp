package com.oa.automation.infrastructure.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ReportDao {
    @Query("SELECT * FROM report_generations WHERE meetingId = :meetingId AND ownerId = :ownerId")
    suspend fun findGeneration(meetingId: String, ownerId: String): ReportGenerationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGeneration(entity: ReportGenerationEntity)

    @Query("SELECT * FROM meetings WHERE id = :meetingId AND ownerId = :ownerId")
    suspend fun findGenerationMeeting(meetingId: String, ownerId: String): MeetingEntity?

    @Query("UPDATE meetings SET selectedTemplateName = :templateName WHERE id = :meetingId AND ownerId = :ownerId")
    suspend fun selectMeetingTemplate(meetingId: String, ownerId: String, templateName: String)

    @Transaction
    suspend fun beginGeneration(meetingId: String, ownerId: String, requestId: String, templateName: String?): ReportGenerationEntity {
        val meeting = checkNotNull(findGenerationMeeting(meetingId, ownerId)) { "会议不存在" }
        val selectedName = templateName?.takeIf(String::isNotBlank)
            ?: meeting.selectedTemplateName?.takeIf(String::isNotBlank)
            ?: error("请选择纪要模板")
        val previous = findByMeetingId(meetingId, ownerId)
        val generation = ReportGenerationEntity(
            meetingId, ownerId, requestId, selectedName,
            previous?.id ?: findGeneration(meetingId, ownerId)?.reportId ?: java.util.UUID.randomUUID().toString()
        )
        selectMeetingTemplate(meetingId, ownerId, selectedName)
        upsertGeneration(generation)
        deleteByMeetingId(meetingId, ownerId)
        return generation
    }

    @Transaction
    suspend fun completeGeneration(report: ReportEntity, requestId: String): Boolean {
        val ownerId = report.ownerId ?: return false
        val generation = findGeneration(report.meetingId, ownerId) ?: return false
        val meeting = findGenerationMeeting(report.meetingId, ownerId) ?: return false
        if (generation.cancelled || generation.requestId != requestId ||
            generation.reportId != report.id ||
            generation.templateName != report.templateName ||
            meeting.selectedTemplateName.orEmpty() != generation.templateName
        ) return false
        if (generation.completed) return findCurrentReport(report.meetingId, ownerId) == report
        upsertReport(report)
        upsertGeneration(generation.copy(completed = true))
        return true
    }

    @Transaction
    suspend fun saveCurrentReport(report: ReportEntity) {
        val ownerId = report.ownerId ?: error("会议账户未就绪")
        val meeting = checkNotNull(findGenerationMeeting(report.meetingId, ownerId)) { "会议不存在" }
        check(meeting.selectedTemplateName.isNullOrBlank() || meeting.selectedTemplateName == report.templateName) {
            "纪要模板已更新，请使用最新内容"
        }
        val generation = findGeneration(report.meetingId, ownerId)
        if (generation != null) {
            val current = findByMeetingId(report.meetingId, ownerId)
            check(generation.completed && !generation.cancelled && current != null &&
                current.id == report.id && current.generatedAt == report.generatedAt &&
                current.templateName == report.templateName) { "纪要已更新，请使用最新内容" }
        }
        upsertReport(report)
    }

    // Read the report, selected template and generation in one database snapshot.
    @Transaction
    suspend fun findCurrentReport(meetingId: String, ownerId: String): ReportEntity? {
        val meeting = findGenerationMeeting(meetingId, ownerId) ?: return null
        val report = findByMeetingId(meetingId, ownerId) ?: return null
        if (!meeting.selectedTemplateName.isNullOrBlank() && meeting.selectedTemplateName != report.templateName) return null
        val generation = findGeneration(meetingId, ownerId) ?: return report
        return report.takeIf {
            generation.completed && !generation.cancelled && generation.reportId == it.id &&
                generation.templateName == it.templateName && meeting.selectedTemplateName == it.templateName
        }
    }

    @Transaction
    suspend fun deleteCurrentReport(meetingId: String, ownerId: String) {
        findGeneration(meetingId, ownerId)?.let { upsertGeneration(it.copy(cancelled = true)) }
        deleteByMeetingId(meetingId, ownerId)
    }

    @Query("UPDATE report_generations SET cancelled = 1 WHERE meetingId = :meetingId AND ownerId = :ownerId AND completed = 0")
    suspend fun cancelGeneration(meetingId: String, ownerId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReport(entity: ReportEntity)

    @Query("SELECT * FROM reports WHERE ownerId = :ownerId AND meetingId = :meetingId LIMIT 1")
    suspend fun findByMeetingId(meetingId: String, ownerId: String? = null): ReportEntity?

    @Query("DELETE FROM reports WHERE ownerId = :ownerId AND meetingId = :meetingId")
    suspend fun deleteByMeetingId(meetingId: String, ownerId: String?)

    @Query("SELECT * FROM reports WHERE ownerId = :ownerId ORDER BY generatedAt DESC")
    fun observeAllReports(ownerId: String?): Flow<List<ReportEntity>>
}
