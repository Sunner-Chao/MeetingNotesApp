package com.oa.automation.infrastructure.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.RecordingMarker
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.infrastructure.llm.AgentAttachment
import com.oa.automation.infrastructure.location.DeviceLocationProvider
import com.oa.automation.infrastructure.location.ExifLocationReader
import com.oa.automation.infrastructure.location.LocationSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MeetingAttachmentStore(
    private val context: Context,
    private val meetingRepository: MeetingRepository,
    private val locationProvider: DeviceLocationProvider,
    private val galleryBackupStore: MeetingGalleryBackupStore
) {
    fun observe(meetingId: String): Flow<List<MeetingAttachment>> = meetingRepository.observeAttachments(meetingId)

    fun observeJourneyStage(journeyStageId: String): Flow<List<MeetingAttachment>> =
        meetingRepository.observeAttachmentsByJourneyStageId(journeyStageId)

    suspend fun importImage(
        meetingId: String,
        source: Uri,
        captureLocation: Boolean = false,
        journeyStageId: String? = null,
        recordingMarker: RecordingMarker? = null
    ): Result<MeetingAttachment> = importImages(
        meetingId = meetingId,
        sources = listOf(source),
        captureLocation = captureLocation,
        journeyStageId = journeyStageId,
        recordingMarker = recordingMarker
    ).single()

    suspend fun importImages(
        meetingId: String,
        sources: List<Uri>,
        captureLocation: Boolean = false,
        journeyStageId: String? = null,
        recordingMarker: RecordingMarker? = null,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Result<MeetingAttachment>> = withContext(Dispatchers.IO) {
        val deviceLocation = if (captureLocation) locationProvider.capture() else null
        processImageImportsSequentially(
            sources = sources,
            importer = { source ->
                importImage(meetingId, source, deviceLocation, journeyStageId, recordingMarker)
            },
            onProgress = onProgress
        )
    }

    private suspend fun importImage(
        meetingId: String,
        source: Uri,
        deviceLocation: LocationSnapshot?,
        journeyStageId: String?,
        recordingMarker: RecordingMarker?
    ): Result<MeetingAttachment> {
        var targetFile: File? = null
        var partialFile: File? = null
        return try {
            val createdAt = System.currentTimeMillis()
            val mimeType = context.contentResolver.getType(source)?.takeIf { it.startsWith("image/") }
                ?: "image/jpeg"
            val originalName = queryDisplayName(source) ?: "photo.jpg"
            val extension = originalName.substringAfterLast('.', "")
                .replace(Regex("[^A-Za-z0-9]+"), "")
                .lowercase()
                .takeIf { it.isNotBlank() }
                ?: mimeType.substringAfter('/', "jpg")
            val attachmentId = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "meeting-attachments/$meetingId").apply { mkdirs() }
            val target = File(directory, "$attachmentId.$extension")
            val partial = File(directory, ".$attachmentId.$extension.part")
            targetFile = target
            partialFile = partial

            context.contentResolver.openInputStream(source)?.use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")
            check(partial.isFile && partial.length() > 0L) { "图片文件为空" }
            check(partial.renameTo(target)) { "无法保存图片文件" }

            // Prefer coordinates embedded in the image: the device may be elsewhere
            // when a previously captured gallery photo is imported.
            val location = ExifLocationReader.read(target, createdAt) ?: deviceLocation

            val attachment = MeetingAttachment(
                id = attachmentId,
                meetingId = meetingId,
                journeyStageId = journeyStageId,
                displayName = originalName,
                localPath = target.absolutePath,
                mimeType = mimeType,
                createdAt = createdAt,
                latitude = location?.latitude,
                longitude = location?.longitude,
                accuracyMeters = location?.accuracyMeters,
                locationCapturedAt = location?.capturedAt,
                locationSource = location?.source,
                recordingMarkerId = recordingMarker?.id,
                markerTimestampMs = recordingMarker?.timestampMs,
                markerTranscriptAnchor = recordingMarker?.transcriptAnchor?.takeIf { it.isNotBlank() }
            )
            meetingRepository.saveAttachment(attachment).getOrElse { error ->
                target.delete()
                throw error
            }
            val galleryUri = galleryBackupStore.backup(attachment).getOrNull()?.toString()
            val persistedAttachment = if (galleryUri.isNullOrBlank()) {
                attachment
            } else {
                attachment.copy(galleryUri = galleryUri).also { updated ->
                    // A gallery failure must not make the import fail. If the
                    // second database write fails, the stable gallery filename
                    // lets startup recovery find the already-created copy.
                    meetingRepository.saveAttachment(updated)
                }
            }
            Result.success(persistedAttachment)
        } catch (error: CancellationException) {
            partialFile?.delete()
            targetFile?.delete()
            throw error
        } catch (error: Throwable) {
            partialFile?.delete()
            targetFile?.delete()
            Result.failure(error)
        }
    }

    suspend fun delete(attachment: MeetingAttachment): Result<Unit> = withContext(Dispatchers.IO) {
        meetingRepository.deleteAttachment(attachment.id).onSuccess {
            File(attachment.localPath).delete()
        }
    }

    fun toAgentAttachments(attachments: List<MeetingAttachment>): List<AgentAttachment> = attachments.mapNotNull { attachment ->
        File(attachment.localPath).takeIf { it.isFile }?.let { file ->
            AgentAttachment(
                file = file,
                mimeType = attachment.mimeType,
                displayName = attachment.displayName,
                capturedAt = attachment.createdAt,
                latitude = attachment.latitude,
                longitude = attachment.longitude,
                accuracyMeters = attachment.accuracyMeters,
                locationCapturedAt = attachment.locationCapturedAt,
                locationSource = attachment.locationSource,
                recordingMarkerId = attachment.recordingMarkerId,
                markerTimestampMs = attachment.markerTimestampMs,
                markerTranscriptAnchor = attachment.markerTranscriptAnchor
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()
}

internal suspend fun <Source, Value> processImageImportsSequentially(
    sources: List<Source>,
    importer: suspend (Source) -> Result<Value>,
    onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
): List<Result<Value>> {
    val total = sources.size
    return buildList(total) {
        sources.forEach { source ->
            currentCoroutineContext().ensureActive()
            add(importer(source))
            onProgress(size, total)
            currentCoroutineContext().ensureActive()
        }
    }
}
