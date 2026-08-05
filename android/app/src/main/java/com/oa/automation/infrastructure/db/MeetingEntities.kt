package com.oa.automation.infrastructure.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingAttachment
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

@Entity(
    tableName = "meeting_attachments",
    indices = [Index(value = ["meetingId"])]
)
data class MeetingAttachmentEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val displayName: String,
    val localPath: String,
    val mimeType: String,
    val createdAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val locationCapturedAt: Long?,
    val locationSource: String?
)

@Entity(
    tableName = "scheduled_meetings",
    indices = [Index(value = ["scheduledAt"])]
)
data class ScheduledMeetingEntity(
    @PrimaryKey val id: String,
    val title: String,
    val scheduledAt: Long,
    val reminderMinutes: Int,
    val templateName: String?,
    val createdAt: Long
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

fun MeetingAttachmentEntity.toDomain() = MeetingAttachment(
    id = id,
    meetingId = meetingId,
    displayName = displayName,
    localPath = localPath,
    mimeType = mimeType,
    createdAt = createdAt,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    locationCapturedAt = locationCapturedAt,
    locationSource = locationSource
)

fun MeetingAttachment.toEntity() = MeetingAttachmentEntity(
    id = id,
    meetingId = meetingId,
    displayName = displayName,
    localPath = localPath,
    mimeType = mimeType,
    createdAt = createdAt,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    locationCapturedAt = locationCapturedAt,
    locationSource = locationSource
)
