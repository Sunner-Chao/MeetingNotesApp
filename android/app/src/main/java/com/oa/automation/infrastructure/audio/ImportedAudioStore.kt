package com.oa.automation.infrastructure.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportedAudio(val file: File, val displayName: String)

/** Copies document-provider audio into private storage before WorkManager receives it. */
class ImportedAudioStore(private val context: Context) {
    suspend fun import(uri: Uri): Result<ImportedAudio> = withContext(Dispatchers.IO) {
        var destination: File? = null
        try {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri).orEmpty()
            val displayName = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }.orEmpty().ifBlank { "会议音频" }
            val extension = displayName.substringAfterLast('.', "").lowercase()
            require(mimeType.startsWith("audio/") || extension in SUPPORTED_EXTENSIONS) {
                "请选择可识别的音频文件"
            }
            val destinationDir = File(context.filesDir, "imported-audio").apply { mkdirs() }
            val safeExtension = extension.takeIf { it in SUPPORTED_EXTENSIONS } ?: "audio"
            val target = File(destinationDir, "${UUID.randomUUID()}.$safeExtension")
            destination = target
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use(input::copyTo)
            } ?: error("无法读取导入的音频")
            require(target.length() > 0L) { "导入的音频文件为空" }
            Result.success(ImportedAudio(target, displayName))
        } catch (error: CancellationException) {
            destination?.delete()
            throw error
        } catch (error: Throwable) {
            destination?.delete()
            Result.failure(error)
        }
    }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("aac", "amr", "flac", "m4a", "mp3", "ogg", "opus", "wav", "webm")
    }
}
