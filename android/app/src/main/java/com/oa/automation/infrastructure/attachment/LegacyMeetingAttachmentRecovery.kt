package com.oa.automation.infrastructure.attachment

import android.content.Context
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.repository.MeetingRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Moves attachment rows from cache storage to the meeting's durable files directory. */
class LegacyMeetingAttachmentRecovery(
    private val context: Context,
    private val meetingRepository: MeetingRepository,
    private val galleryBackupStore: MeetingGalleryBackupStore
) {
    suspend fun recover(): Int = withContext(Dispatchers.IO) {
        var recoveredCount = 0
        meetingRepository.getAllAttachments().forEach { attachment ->
            val source = File(attachment.localPath)
            var durableAttachment = attachment
            if (isCacheFile(source) && source.isFile && source.length() > 0L) {
                val destination = destinationFor(attachment, source)
                if (copyAtomically(source, destination)) {
                    durableAttachment = attachment.copy(localPath = destination.absolutePath)
                    meetingRepository.saveAttachment(durableAttachment)
                        .onSuccess {
                            source.delete()
                            recoveredCount++
                        }
                }
            }

            // Backfill gallery copies for attachments created before gallery
            // backup was introduced, including files just migrated from cache.
            if (
                durableAttachment.galleryUri.isNullOrBlank() &&
                    File(durableAttachment.localPath).isFile &&
                    durableAttachment.mimeType.startsWith("image/")
            ) {
                galleryBackupStore.backup(durableAttachment).getOrNull()?.toString()?.let { uri ->
                    meetingRepository.saveAttachment(durableAttachment.copy(galleryUri = uri))
                }
            }
        }
        recoveredCount
    }

    private fun destinationFor(attachment: MeetingAttachment, source: File): File {
        val extension = source.extension.ifBlank {
            attachment.displayName.substringAfterLast('.', "jpg").ifBlank { "jpg" }
        }
        return File(
            context.filesDir,
            "meeting-attachments/${attachment.meetingId}/${attachment.id}.$extension"
        )
    }

    private fun isCacheFile(file: File): Boolean {
        val cachePath = context.cacheDir.toPath().toAbsolutePath().normalize().toString() +
            File.separator
        val filePath = file.toPath().toAbsolutePath().normalize().toString()
        return filePath.startsWith(cachePath)
    }

    private fun copyAtomically(source: File, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        if (destination.isFile && destination.length() == source.length()) return true
        val partial = File(destination.parentFile, ".${destination.name}.part")
        return runCatching {
            source.copyTo(partial, overwrite = true)
            check(partial.length() == source.length()) { "图片文件复制不完整" }
            if (destination.exists() && !destination.delete()) error("无法替换旧图片文件")
            check(partial.renameTo(destination)) { "无法完成图片文件迁移" }
            true
        }.getOrElse {
            partial.delete()
            false
        }
    }
}
