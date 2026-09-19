package com.oa.automation.infrastructure.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.MeetingAudioSegment
import com.oa.automation.domain.model.MeetingOrigin
import com.oa.automation.domain.model.MeetingAudioSource
import com.oa.automation.domain.model.RecordingMarker
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.Task
import com.oa.automation.domain.model.Transcript

@Entity(tableName = "meetings", indices = [Index(value = ["ownerId"])])
data class MeetingEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val audioFilePath: String?,
    val origin: String,
    val selectedTemplateName: String? = null,
    val selectedSttEngineName: String? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN'")
    val audioSource: String = MeetingAudioSource.UNKNOWN.name,
    val captureDeviceName: String? = null,
    val externalSessionId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val detectedSpeakerCount: Int = 0,
    @ColumnInfo(defaultValue = "'PHONE'")
    val preferredCaptureInput: String = "PHONE",
    /** Account that owns this local row. Null is retained for legacy/anonymous rows. */
    val ownerId: String? = null,
    val meetingRoomId: String? = null
)

@Entity(
    tableName = "meeting_audio_segments",
    indices = [
        Index(value = ["meetingId"]),
        Index(value = ["meetingId", "sequenceNumber"], unique = true)
    ]
)
data class MeetingAudioSegmentEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val sequenceNumber: Int,
    val localPath: String,
    val durationMs: Long,
    val bytes: Long,
    val createdAt: Long
)

@Entity(
    tableName = "transcripts",
    indices = [Index(value = ["journeyStageId"])]
)
data class TranscriptEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val journeyStageId: String?,
    val speakerName: String?,
    val content: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val createdAt: Long
)

@Entity(tableName = "reports", indices = [Index(value = ["ownerId"])])
data class ReportEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val summary: String,
    val keyPoints: List<String>,
    val tasks: List<Task>,
    val decisions: List<String>,
    val actionItems: List<String>,
    val participants: List<com.oa.automation.domain.model.ForumParticipant>,
    val rawContent: String = "",
    val templateName: String = "",
    val workspaceBlockOrder: List<String> = emptyList(),
    val hiddenWorkspaceBlocks: List<String> = emptyList(),
    val generatedAt: Long,
    /** Account that owns this local row. Null is retained for legacy/anonymous rows. */
    val ownerId: String? = null
)

@Entity(
    tableName = "meeting_attachments",
    indices = [
        Index(value = ["meetingId"]),
        Index(value = ["journeyStageId"])
    ]
)
data class MeetingAttachmentEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val journeyStageId: String?,
    val displayName: String,
    val localPath: String,
    val mimeType: String,
    val createdAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val locationCapturedAt: Long?,
    val locationSource: String?,
    val recordingMarkerId: String?,
    val markerTimestampMs: Long?,
    val markerTranscriptAnchor: String?,
    val galleryUri: String?
)

@Entity(
    tableName = "recording_markers",
    indices = [
        Index(value = ["meetingId"]),
        Index(value = ["journeyStageId"])
    ]
)
data class RecordingMarkerEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val journeyStageId: String?,
    val timestampMs: Long,
    val transcriptAnchor: String,
    val createdAt: Long
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
    audioFilePath = audioFilePath,
    origin = MeetingOrigin.fromPersisted(origin),
    selectedTemplateName = selectedTemplateName,
    selectedSttEngineName = selectedSttEngineName,
    audioSource = runCatching { MeetingAudioSource.valueOf(audioSource) }
        .getOrDefault(MeetingAudioSource.UNKNOWN),
    captureDeviceName = captureDeviceName,
    externalSessionId = externalSessionId,
    detectedSpeakerCount = detectedSpeakerCount,
    preferredCaptureInput = runCatching { com.oa.automation.domain.model.CaptureInput.valueOf(preferredCaptureInput) }
        .getOrDefault(com.oa.automation.domain.model.CaptureInput.PHONE),
    ownerId = ownerId,
    meetingRoomId = meetingRoomId
)

fun MeetingAudioSegmentEntity.toDomain() = MeetingAudioSegment(
    id = id,
    meetingId = meetingId,
    sequenceNumber = sequenceNumber,
    localPath = localPath,
    durationMs = durationMs,
    bytes = bytes,
    createdAt = createdAt
)

fun MeetingAudioSegment.toEntity() = MeetingAudioSegmentEntity(
    id = id,
    meetingId = meetingId,
    sequenceNumber = sequenceNumber,
    localPath = localPath,
    durationMs = durationMs,
    bytes = bytes,
    createdAt = createdAt
)

fun Meeting.toEntity() = MeetingEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    durationMs = durationMs,
    audioFilePath = audioFilePath,
    origin = origin.name,
    selectedTemplateName = selectedTemplateName,
    selectedSttEngineName = selectedSttEngineName,
    audioSource = audioSource.name,
    captureDeviceName = captureDeviceName,
    externalSessionId = externalSessionId,
    detectedSpeakerCount = detectedSpeakerCount,
    preferredCaptureInput = preferredCaptureInput.name,
    ownerId = ownerId,
    meetingRoomId = meetingRoomId
)

fun TranscriptEntity.toDomain() = Transcript(
    id = id,
    meetingId = meetingId,
    journeyStageId = journeyStageId,
    speakerName = speakerName,
    content = content,
    startTimeMs = startTimeMs,
    endTimeMs = endTimeMs,
    createdAt = createdAt
)

fun Transcript.toEntity() = TranscriptEntity(
    id = id,
    meetingId = meetingId,
    journeyStageId = journeyStageId,
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
    participants = participants,
    rawContent = rawContent,
    templateName = templateName,
    workspaceBlockOrder = workspaceBlockOrder,
    hiddenWorkspaceBlocks = hiddenWorkspaceBlocks,
    generatedAt = generatedAt,
    ownerId = ownerId
)

fun Report.toEntity() = ReportEntity(
    id = id,
    meetingId = meetingId,
    summary = summary,
    keyPoints = keyPoints,
    tasks = tasks,
    decisions = decisions,
    actionItems = actionItems,
    participants = participants,
    rawContent = rawContent,
    templateName = templateName,
    workspaceBlockOrder = workspaceBlockOrder,
    hiddenWorkspaceBlocks = hiddenWorkspaceBlocks,
    generatedAt = generatedAt,
    ownerId = ownerId
)

fun MeetingAttachmentEntity.toDomain() = MeetingAttachment(
    id = id,
    meetingId = meetingId,
    journeyStageId = journeyStageId,
    displayName = displayName,
    localPath = localPath,
    mimeType = mimeType,
    createdAt = createdAt,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    locationCapturedAt = locationCapturedAt,
    locationSource = locationSource,
    recordingMarkerId = recordingMarkerId,
    markerTimestampMs = markerTimestampMs,
    markerTranscriptAnchor = markerTranscriptAnchor,
    galleryUri = galleryUri
)

fun MeetingAttachment.toEntity() = MeetingAttachmentEntity(
    id = id,
    meetingId = meetingId,
    journeyStageId = journeyStageId,
    displayName = displayName,
    localPath = localPath,
    mimeType = mimeType,
    createdAt = createdAt,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    locationCapturedAt = locationCapturedAt,
    locationSource = locationSource,
    recordingMarkerId = recordingMarkerId,
    markerTimestampMs = markerTimestampMs,
    markerTranscriptAnchor = markerTranscriptAnchor,
    galleryUri = galleryUri
)

fun RecordingMarkerEntity.toDomain() = RecordingMarker(
    id = id,
    meetingId = meetingId,
    journeyStageId = journeyStageId,
    timestampMs = timestampMs,
    transcriptAnchor = transcriptAnchor,
    createdAt = createdAt
)

fun RecordingMarker.toEntity() = RecordingMarkerEntity(
    id = id,
    meetingId = meetingId,
    journeyStageId = journeyStageId,
    timestampMs = timestampMs,
    transcriptAnchor = transcriptAnchor,
    createdAt = createdAt
)
