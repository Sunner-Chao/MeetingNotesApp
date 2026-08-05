package com.oa.automation.infrastructure.textimport

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class SharedTextImport(
    val id: String = UUID.randomUUID().toString(),
    val text: String
)

class SharedTextImportCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val _pending = MutableStateFlow<SharedTextImport?>(null)
    val pending: StateFlow<SharedTextImport?> = _pending.asStateFlow()

    suspend fun accept(intent: Intent?): Boolean = withContext(Dispatchers.IO) {
        if (intent?.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)) {
            return@withContext false
        }
        val incomingIntent = intent ?: return@withContext false
        val parts = buildList {
            incomingIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            incomingIntent.clipData?.let { clip ->
                repeat(clip.itemCount) { index ->
                    clip.getItemAt(index).text
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
            sharedUris(incomingIntent).mapNotNullTo(this) { uri -> readText(uri) }
        }
        val text = parts.joinToString("\n\n").trim()
        if (text.isBlank()) return@withContext false
        _pending.value = SharedTextImport(text = text)
        true
    }

    fun consume(id: String) {
        if (_pending.value?.id == id) _pending.value = null
    }

    suspend fun readClipboard(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip ?: error("剪贴板中没有可导入的文字")
            val parts = buildList {
                repeat(clip.itemCount) { index ->
                    val item = clip.getItemAt(index)
                    val text = item.text?.toString()
                        ?: item.uri?.let(::readText)
                        ?: item.coerceToText(appContext)?.toString()
                    text?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            parts.joinToString("\n\n").takeIf { it.isNotBlank() }
                ?: error("剪贴板中没有可导入的文字")
        }
    }

    suspend fun readDocument(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching { readText(uri) ?: error("该文件不是可读取的文本格式") }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> = buildList {
        intent.data?.let(::add)
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
        intent.clipData?.let { clip ->
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
        }
    }.distinct()

    private fun readText(uri: Uri): String? {
        val mimeType = resolver.getType(uri).orEmpty().lowercase()
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }
            .orEmpty()
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val isText = mimeType.startsWith("text/") ||
            mimeType in setOf("application/json", "application/xml", "application/csv") ||
            extension in setOf("txt", "md", "markdown", "csv", "json", "xml", "log")
        if (!isText) return null
        return resolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?.takeIf { it.isNotBlank() }
    }
}
