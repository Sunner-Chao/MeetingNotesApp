package com.oa.automation.infrastructure.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.Task
import com.oa.automation.domain.model.Transcript

@Entity(tableName = "meetings")
data class MeetingEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val audioFilePath: String?
)

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val speakerName: String?,
    val content: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val createdAt: Long
)

@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val summary: String,
    val keyPoints: List<String>,
    val tasks: List<Task>,
    val decisions: List<String>,
    val actionItems: List<String>,
    val rawContent: String = "",
    val templateName: String = "",
    val generatedAt: Long
)

fun MeetingEntity.toDomain() = Meeting(
    id = id,
    title = title,
    createdAt = createdAt,
    durationMs = durationMs,
    audioFilePath = audioFilePath
)

fun Meeting.toEntity() = MeetingEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    durationMs = durationMs,
    audioFilePath = audioFilePath
)

fun TranscriptEntity.toDomain() = Transcript(
    id = id,
    meetingId = meetingId,
    speakerName = speakerName,
    content = content,
    startTimeMs = startTimeMs,
    endTimeMs = endTimeMs,
    createdAt = createdAt
)

fun Transcript.toEntity() = TranscriptEntity(
    id = id,
    meetingId = meetingId,
    speakerName = speakerName,
    content = content,
    startTimeMs = startTimeMs,
    endTimeMs = endTimeMs,
    createdAt = createdAt
)

fun ReportEntity.toDomain() = Report(
    id = id,
    meetingId = meetingId,
    summary = summary,
    keyPoints = keyPoints,
    tasks = tasks,
    decisions = decisions,
    actionItems = actionItems,
    rawContent = rawContent,
    templateName = templateName,
    generatedAt = generatedAt
)

fun Report.toEntity() = ReportEntity(
    id = id,
    meetingId = meetingId,
    summary = summary,
    keyPoints = keyPoints,
    tasks = tasks,
    decisions = decisions,
    actionItems = actionItems,
    rawContent = rawContent,
    templateName = templateName,
    generatedAt = generatedAt
)
