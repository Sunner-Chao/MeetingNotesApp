package com.oa.automation.infrastructure.audio

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.serviceEndpointFor
import com.oa.automation.infrastructure.account.AccountSessionSynchronizer
import com.oa.automation.infrastructure.stt.STT_IPV4_RELAY_DNS
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.oa.automation.infrastructure.network.awaitResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ArchivedMeetingAudio(
    val id: String,
    val meetingId: String,
    val createdAt: String,
    val bytes: Long,
    val durationSec: Double?,
    val filename: String,
    val source: String,
    val downloadPath: String,
    val sha256: String = "",
    val serviceEndpoint: String = "",
    val localFilePath: String? = null
)

data class PreparedMeetingAudioShare(
    val uri: Uri,
    val displayName: String,
    val mimeType: String
)

data class ArchivedMeetingAudioPlaybackSource(
    val uri: Uri,
    val headers: Map<String, String>
)

class MeetingAudioArchiveService(
    private val context: Context,
    private val configDataStore: ConfigDataStore,
    private val accountSessionSynchronizer: AccountSessionSynchronizer? = null
) {
    private val client = OkHttpClient.Builder()
        .dns(STT_IPV4_RELAY_DNS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .build()
    private val gson = Gson()

    suspend fun list(meetingId: String): Result<List<ArchivedMeetingAudio>> = withContext(Dispatchers.IO) {
        runCatching {
            val config = loadSttConfig(refreshAccountSession = true, requireRefresh = false)
            val endpoints = listOfNotNull(
                config.localEndpoint.trim().trimEnd('/').takeIf { it.isNotBlank() },
                config.cloudEndpoint?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ).distinct()
            val results = endpoints.map { endpoint ->
                runCatching {
                    executeAuthorizedRequest(
                        endpointProvider = { endpoint },
                        requestFactory = { serviceEndpoint, token ->
                            val url = serviceEndpoint.toHttpUrlOrNull()
                                ?.newBuilder()
                                ?.addPathSegment("audio-archive")
                                ?.addQueryParameter("meeting_id", meetingId)
                                ?.build()
                                ?: error("STT 服务地址格式无效")
                            Request.Builder()
                                .url(url)
                                .addHeader("Authorization", "Bearer $token")
                                .get()
                                .build()
                        }
                    ) { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IOException(archiveHttpError(response.code, body))
                        gson.fromJson(body, ArchivedAudioListPayload::class.java).items.map {
                            it.toDomain(endpoint)
                        }
                    }
                }
            }
            val successful = results.mapNotNull { it.getOrNull() }
            if (successful.isEmpty()) {
                throw results.firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?: IOException("会议音频服务不可用")
            }
            deduplicateArchivedAudio(successful.flatten())
        }
    }

    suspend fun prepareShare(
        audio: ArchivedMeetingAudio,
        meetingTitle: String
    ): Result<PreparedMeetingAudioShare> = withContext(Dispatchers.IO) {
        runCatching {
            audio.localFilePath
                ?.let(::File)
                ?.takeIf { it.isFile && it.length() > 44L }
                ?.let { localFile ->
                    return@runCatching PreparedMeetingAudioShare(
                        uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            localFile
                        ),
                        displayName = buildShareFilename(meetingTitle, audio),
                        mimeType = audioMimeType(localFile.name)
                    )
                }
            executeAuthorizedRequest(
                endpointProvider = { config -> audio.serviceEndpoint.ifBlank { config.serviceEndpointFor() } },
                requestFactory = { serviceEndpoint, token ->
                    val url = serviceEndpoint.trim().trimEnd('/').toHttpUrlOrNull()
                        ?.newBuilder()
                        ?.addPathSegments(audio.downloadPath.trimStart('/'))
                        ?.build()
                        ?: error("STT 服务地址格式无效")
                    Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                }
            ) { response ->
                if (!response.isSuccessful) {
                    throw IOException(archiveHttpError(response.code, response.body?.string().orEmpty()))
                }
                val body = response.body ?: throw IOException("服务端返回了空音频")
                val filename = buildShareFilename(meetingTitle, audio)
                val directory = File(context.cacheDir, "exports/audio").apply { mkdirs() }
                directory.listFiles()
                    ?.filter { System.currentTimeMillis() - it.lastModified() > SHARE_CACHE_MAX_AGE_MS }
                    ?.forEach(File::delete)
                val target = File(directory, filename)
                val partial = File(directory, ".$filename.part")
                try {
                    partial.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                    if (target.exists() && !target.delete()) throw IOException("无法更新音频分享缓存")
                    if (!partial.renameTo(target)) throw IOException("无法准备音频分享文件")
                } finally {
                    partial.delete()
                }
                PreparedMeetingAudioShare(
                    uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        target
                    ),
                    displayName = filename,
                    mimeType = audioMimeType(filename)
                )
            }
        }
    }

    suspend fun preparePlayback(
        audio: ArchivedMeetingAudio
    ): Result<ArchivedMeetingAudioPlaybackSource> = withContext(Dispatchers.IO) {
        runCatching {
            audio.localFilePath
                ?.let(::File)
                ?.takeIf { it.isFile && it.length() > 44L }
                ?.let { localFile ->
                    return@runCatching ArchivedMeetingAudioPlaybackSource(
                        uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            localFile
                        ),
                        headers = emptyMap()
                    )
                }
            val config = loadSttConfig(refreshAccountSession = true, requireRefresh = false)
            val token = config.apiToken?.trim().orEmpty()
            require(token.isNotBlank()) { "STT 访问令牌未配置" }
            val serviceEndpoint = audio.serviceEndpoint.ifBlank { config.serviceEndpointFor() }
            val url = serviceEndpoint.trim().trimEnd('/').toHttpUrlOrNull()
                ?.newBuilder()
                ?.addPathSegments(audio.downloadPath.trimStart('/'))
                ?.build()
                ?: error("STT 服务地址格式无效")
            ArchivedMeetingAudioPlaybackSource(
                uri = Uri.parse(url.toString()),
                headers = mapOf("Authorization" to "Bearer $token")
            )
        }
    }

    suspend fun delete(audio: ArchivedMeetingAudio): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(audio.id.isNotBlank()) { "会议音频标识无效" }
            executeAuthorizedRequest(
                endpointProvider = { config -> audio.serviceEndpoint.ifBlank { config.serviceEndpointFor() } },
                requestFactory = { serviceEndpoint, token ->
                    val url = serviceEndpoint.trim().trimEnd('/').toHttpUrlOrNull()
                        ?.newBuilder()
                        ?.addPathSegment("audio-archive")
                        ?.addPathSegment(audio.id)
                        ?.build()
                        ?: error("STT 服务地址格式无效")
                    Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $token")
                        .delete()
                        .build()
                }
            ) { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(archiveHttpError(response.code, body))
            }
        }
    }

    private suspend fun <T> executeAuthorizedRequest(
        endpointProvider: (STTConfig) -> String = { it.serviceEndpointFor() },
        requestFactory: (String, String) -> Request,
        responseHandler: suspend (okhttp3.Response) -> T
    ): T {
        var config = loadSttConfig(refreshAccountSession = true, requireRefresh = false)
        var retriedAfterUnauthorized = false
        while (true) {
            val token = config.apiToken?.trim().orEmpty()
            require(token.isNotBlank()) { "STT 访问令牌未配置" }
            val endpoint = endpointProvider(config).trim().trimEnd('/')
            require(endpoint.isNotBlank()) { "STT 服务地址未配置" }
            val response = client.newCall(requestFactory(endpoint, token)).awaitResponse()
            if (response.code == 401 && !retriedAfterUnauthorized && hasAccountSession()) {
                response.close()
                config = loadSttConfig(refreshAccountSession = true, requireRefresh = true)
                retriedAfterUnauthorized = true
                continue
            }
            return response.use { responseHandler(it) }
        }
    }

    private suspend fun loadSttConfig(
        refreshAccountSession: Boolean,
        requireRefresh: Boolean
    ): STTConfig {
        val current = configDataStore.appConfigFlow.first().sttConfig
        if (!refreshAccountSession || !hasAccountSession()) return current
        val synchronizer = accountSessionSynchronizer ?: return current
        val refresh = synchronizer.refresh()
        if (refresh.isSuccess) return configDataStore.appConfigFlow.first().sttConfig
        if (requireRefresh) {
            throw IOException("登录会话已过期，请重新登录")
        }
        return current
    }

    private suspend fun hasAccountSession(): Boolean =
        configDataStore.authSessionFlow.first() != null

    suspend fun savePrepared(
        prepared: PreparedMeetingAudioShare,
        destination: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val source = resolver.openInputStream(prepared.uri)
                ?: throw IOException("无法读取待保存的会议音频")
            val output = resolver.openOutputStream(destination, "w")
                ?: throw IOException("无法打开所选保存位置")
            val copiedBytes = source.use { input ->
                output.use { target -> input.copyTo(target) }
            }
            if (copiedBytes <= 0L) throw IOException("保存的会议音频为空")
        }
    }

    private fun buildShareFilename(title: String, audio: ArchivedMeetingAudio): String {
        val safeTitle = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(60)
            .ifBlank { "会议录音" }
        val timestamp = runCatching {
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(audio.createdAt))
        }.getOrDefault(System.currentTimeMillis().toString())
        val extension = audio.filename.substringAfterLast('.', "wav")
        return "$safeTitle-$timestamp.$extension"
    }

    companion object {
        private const val SHARE_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }

    private data class ArchivedAudioListPayload(val items: List<ArchivedAudioPayload> = emptyList())

    private data class ArchivedAudioPayload(
        val id: String = "",
        @com.google.gson.annotations.SerializedName("meeting_id") val meetingId: String = "",
        @com.google.gson.annotations.SerializedName("created_at") val createdAt: String = "",
        val bytes: Long = 0,
        @com.google.gson.annotations.SerializedName("duration_sec") val durationSec: Double? = null,
        val filename: String = "",
        val source: String = "",
        val sha256: String = "",
        @com.google.gson.annotations.SerializedName("download_path") val downloadPath: String = ""
    ) {
        fun toDomain(serviceEndpoint: String) = ArchivedMeetingAudio(
            id = id,
            meetingId = meetingId,
            createdAt = createdAt,
            bytes = bytes,
            durationSec = durationSec,
            filename = filename,
            source = source,
            downloadPath = downloadPath,
            sha256 = sha256,
            serviceEndpoint = serviceEndpoint
        )
    }
}

internal fun deduplicateArchivedAudio(items: List<ArchivedMeetingAudio>): List<ArchivedMeetingAudio> {
    val seenHashes = mutableSetOf<Pair<String, String>>()
    val seenIds = mutableSetOf<String>()
    return items.filter { item ->
        val hashKey = item.sha256.trim().takeIf { it.isNotEmpty() }?.let { item.meetingId to it }
        val isDuplicate = item.id in seenIds || hashKey != null && hashKey in seenHashes
        if (!isDuplicate) {
            seenIds.add(item.id)
            hashKey?.let(seenHashes::add)
        }
        !isDuplicate
    }
}

private fun audioMimeType(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
    "wav" -> "audio/wav"
    "m4a", "mp4" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "aac" -> "audio/aac"
    "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    else -> "audio/*"
}

private fun archiveHttpError(code: Int, body: String): String = when (code) {
    401 -> "会议音频访问令牌无效或已过期"
    404 -> "会议音频不存在或已过保留期"
    else -> "会议音频服务请求失败 (HTTP $code): ${body.take(160)}"
}
