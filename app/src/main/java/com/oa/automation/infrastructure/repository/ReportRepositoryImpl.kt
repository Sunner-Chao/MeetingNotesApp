package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.Report
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.infrastructure.db.ReportDao
import com.oa.automation.infrastructure.db.toDomain
import com.oa.automation.infrastructure.db.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ReportRepositoryImpl(
    private val reportDao: ReportDao
) : ReportRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportsFlow = reportDao.observeAllReports()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    override suspend fun save(report: Report): Result<Report> {
        return runCatching {
            reportDao.upsertReport(report.toEntity())
            report
        }
    }

    override suspend fun findByMeetingId(meetingId: String): Result<Report?> {
        return runCatching {
            reportDao.findByMeetingId(meetingId)?.toDomain()
        }
    }

    override suspend fun deleteByMeetingId(meetingId: String): Result<Unit> {
        return runCatching {
            reportDao.deleteByMeetingId(meetingId)
        }
    }

    override fun getAllReportsFlow(): StateFlow<List<Report>> {
        return reportsFlow
    }
}
