package com.oa.automation.infrastructure.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.oa.automation.BuildConfig
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AndroidAppUpdate(
    val versionCode: Int,
    val versionName: String,
    val mandatory: Boolean,
    val releaseNotes: String,
    val publishedAt: String,
    val downloadUrl: String,
    val sha256: String?
)

sealed interface AppUpdateCheck {
    data object UpToDate : AppUpdateCheck
    data class Available(val update: AndroidAppUpdate) : AppUpdateCheck
}

data class DownloadedAppUpdate(val update: AndroidAppUpdate, val apk: File)

/** Network and installer boundary for the server-managed Android release channel. */
class AppUpdateService(private val context: Context) {
    private val client = OkHttpClient.Builder().build()

    suspend fun checkForUpdate(): Result<AppUpdateCheck> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = BuildConfig.DEFAULT_APP_UPDATE_ENDPOINT.trim()
            require(endpoint.isNotBlank()) { "未配置版本更新服务" }
            val response = client.newCall(Request.Builder().url(endpoint).get().build()).execute()
            response.use {
                if (it.code == 204) return@runCatching AppUpdateCheck.UpToDate
                require(it.isSuccessful) { "版本检查失败（HTTP ${it.code}）" }
                val body = it.body?.string().orEmpty()
                val update = parseUpdate(JSONObject(body), endpoint)
                if (update.versionCode > BuildConfig.VERSION_CODE) {
                    AppUpdateCheck.Available(update)
                } else {
                    AppUpdateCheck.UpToDate
                }
            }
        }
    }

    suspend fun download(update: AndroidAppUpdate, onProgress: (Int) -> Unit): Result<DownloadedAppUpdate> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = client.newCall(Request.Builder().url(update.downloadUrl).get().build()).execute()
                response.use {
                    require(it.isSuccessful) { "安装包下载失败（HTTP ${it.code}）" }
                    val body = requireNotNull(it.body) { "安装包响应为空" }
                    val targetDir = File(context.cacheDir, "updates").apply { mkdirs() }
                    val target = File(targetDir, "zhiwuben-${update.versionCode}.apk")
                    val temporary = File(targetDir, "${target.name}.part")
                    temporary.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val length = body.contentLength()
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                if (length > 0L) onProgress(((copied * 100L) / length).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                    require(temporary.length() > 0L) { "下载的安装包为空" }
                    update.sha256?.takeIf { it.isNotBlank() }?.let { expected ->
                        require(temporary.sha256().equals(expected, ignoreCase = true)) { "安装包校验失败" }
                    }
                    if (target.exists()) target.delete()
                    require(temporary.renameTo(target)) { "安装包准备失败" }
                    DownloadedAppUpdate(update, target)
                }
            }
        }

    fun canInstallPackages(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(downloaded: DownloadedAppUpdate) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            downloaded.apk
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    private fun parseUpdate(json: JSONObject, endpoint: String): AndroidAppUpdate {
        val relativeUrl = json.getString("download_url")
        return AndroidAppUpdate(
            versionCode = json.getInt("version_code"),
            versionName = json.getString("version_name"),
            mandatory = json.optBoolean("mandatory", false),
            releaseNotes = json.optString("release_notes"),
            publishedAt = json.optString("published_at"),
            downloadUrl = Uri.parse(endpoint).let { base ->
                if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) relativeUrl
                else "${base.scheme}://${base.authority}${if (relativeUrl.startsWith('/')) relativeUrl else "/$relativeUrl"}"
            },
            sha256 = json.optString("sha256").takeIf { it.isNotBlank() }
        )
    }
}

private fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
