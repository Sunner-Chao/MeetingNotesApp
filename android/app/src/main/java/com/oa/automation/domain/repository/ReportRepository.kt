package com.oa.automation.domain.repository

import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.ReportGeneration
import kotlinx.coroutines.flow.StateFlow

interface ReportRepository {
    suspend fun beginGeneration(meetingId: String, requestId: String, templateName: String? = null): ReportGeneration
    suspend fun findGeneration(meetingId: String): ReportGeneration?
    suspend fun completeGeneration(report: Report, requestId: String): Boolean
    suspend fun cancelGeneration(meetingId: String)
    suspend fun save(report: Report): Result<Report>
    suspend fun findByMeetingId(meetingId: String): Result<Report?>
    suspend fun deleteByMeetingId(meetingId: String): Result<Unit>
    fun getAllReportsFlow(): StateFlow<List<Report>>
}
