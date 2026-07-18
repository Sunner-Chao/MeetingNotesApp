package com.oa.automation.infrastructure.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.infrastructure.llm.AgentAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MeetingAttachmentStore(
    private val context: Context,
    private val meetingRepository: MeetingRepository
) {
    fun observe(meetingId: String): Flow<List<MeetingAttachment>> = meetingRepository.observeAttachments(meetingId)

    suspend fun importImage(meetingId: String, source: Uri): Result<MeetingAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val mimeType = context.contentResolver.getType(source)?.takeIf { it.startsWith("image/") }
                ?: "image/jpeg"
            val originalName = queryDisplayName(source) ?: "photo.jpg"
            val extension = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
                ?: mimeType.substringAfter('/', "jpg")
            val attachmentId = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "meeting-attachments/$meetingId").apply { mkdirs() }
            val target = File(directory, "$attachmentId.$extension")

            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")

            val attachment = MeetingAttachment(
                id = attachmentId,
                meetingId = meetingId,
                displayName = originalName,
                localPath = target.absolutePath,
                mimeType = mimeType,
                createdAt = System.currentTimeMillis()
            )
            meetingRepository.saveAttachment(attachment).getOrElse { error ->
                target.delete()
                throw error
            }
        }
    }

    suspend fun delete(attachment: MeetingAttachment): Result<Unit> = withContext(Dispatchers.IO) {
        meetingRepository.deleteAttachment(attachment.id).onSuccess {
            File(attachment.localPath).delete()
        }
    }

    fun toAgentAttachments(attachments: List<MeetingAttachment>): List<AgentAttachment> = attachments.mapNotNull { attachment ->
        File(attachment.localPath).takeIf { it.isFile }?.let { file ->
            AgentAttachment(file, attachment.mimeType, attachment.displayName)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()
}
