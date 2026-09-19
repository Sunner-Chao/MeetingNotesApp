package com.oa.automation.infrastructure.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.oa.automation.domain.model.ReportGeneration

@Entity(tableName = "report_generations")
data class ReportGenerationEntity(
    @PrimaryKey val meetingId: String,
    val ownerId: String,
    val requestId: String,
    val templateName: String,
    val reportId: String,
    val cancelled: Boolean = false,
    val completed: Boolean = false
)

fun ReportGenerationEntity.toDomain() = ReportGeneration(
    requestId, templateName, reportId, cancelled, completed
)
