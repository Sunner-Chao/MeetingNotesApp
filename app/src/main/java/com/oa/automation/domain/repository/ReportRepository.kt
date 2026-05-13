package com.oa.automation.domain.repository

import com.oa.automation.domain.model.Report
import kotlinx.coroutines.flow.StateFlow

interface ReportRepository {
    suspend fun save(report: Report): Result<Report>
    suspend fun findByMeetingId(meetingId: String): Result<Report?>
    suspend fun deleteByMeetingId(meetingId: String): Result<Unit>
    fun getAllReportsFlow(): StateFlow<List<Report>>
}
