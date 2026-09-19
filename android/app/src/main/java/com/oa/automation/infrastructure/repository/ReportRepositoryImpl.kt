package com.oa.automation.infrastructure.repository

import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.ReportGeneration
import com.oa.automation.domain.repository.ReportRepository
import com.oa.automation.infrastructure.db.ReportDao
import com.oa.automation.infrastructure.db.toDomain
import com.oa.automation.infrastructure.db.toEntity
import com.oa.automation.data.local.ConfigDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReportRepositoryImpl(
    private val reportDao: ReportDao,
    private val configDataStore: ConfigDataStore
) : ReportRepository {

    private suspend fun currentOwner() = configDataStore.currentLocalWorkspaceAccountId() ?: "__anonymous__"

    override suspend fun beginGeneration(meetingId: String, requestId: String, templateName: String?): ReportGeneration {
        val ownerId = currentOwner()
        val selectedName = templateName?.takeIf(String::isNotBlank)
            ?: reportDao.findGenerationMeeting(meetingId, ownerId)?.selectedTemplateName?.takeIf(String::isNotBlank)
            ?: configDataStore.appConfigFlow.first().reportTemplateConfig.selectedName
        return reportDao.beginGeneration(meetingId, ownerId, requestId, selectedName).toDomain()
    }

    override suspend fun findGeneration(meetingId: String): ReportGeneration? =
        reportDao.findGeneration(meetingId, currentOwner())?.toDomain()

    override suspend fun completeGeneration(report: Report, requestId: String): Boolean =
        reportDao.completeGeneration(report.copy(ownerId = currentOwner()).toEntity(), requestId)

    override suspend fun cancelGeneration(meetingId: String) {
        reportDao.cancelGeneration(meetingId, currentOwner())
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportsFlow = configDataStore.localWorkspaceAccountIdFlow
        .map { it ?: "__anonymous__" }
        .flatMapLatest { ownerId -> reportDao.observeAllReports(ownerId) }
        .map { list -> list.map { it.toDomain() } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    override suspend fun save(report: Report): Result<Report> {
        return runCatching {
            val ownerId = configDataStore.currentLocalWorkspaceAccountId() ?: "__anonymous__"
            reportDao.saveCurrentReport(report.copy(ownerId = ownerId).toEntity())
            report
        }
    }

    override suspend fun findByMeetingId(meetingId: String): Result<Report?> {
        return runCatching {
            reportDao.findCurrentReport(meetingId, currentOwner())?.toDomain()
        }
    }

    override suspend fun deleteByMeetingId(meetingId: String): Result<Unit> {
        return runCatching {
            reportDao.deleteCurrentReport(meetingId, currentOwner())
        }
    }

    override fun getAllReportsFlow(): StateFlow<List<Report>> {
        return reportsFlow
    }
}
